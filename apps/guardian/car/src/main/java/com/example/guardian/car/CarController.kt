// 차 앱 실행부 — 주기 스레드가 2초마다 차량 읽기 · 상태 기계 · 차 안 경보를 돌리고, 네트워크 스레드가 중계 sync · 짝짓기를 맡는다(프로세스에 하나)
package com.example.guardian.car

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.SeqGenerator
import com.example.guardian.shared.protocol.TestAction
import java.util.concurrent.Executor

/** 화면이 읽는 상태. 메인 스레드에서만 쓴다. */
class CarUiState {
    var screen by mutableStateOf(ScreenView(null, null, false))
    var paired by mutableStateOf(false)
    var pairCode by mutableStateOf<String?>(null)
    var pairFailed by mutableStateOf(false)
    /** 짝짓기 · 새 연결 번호 · 권한 요청 창을 열어도 되는지(NO_SETUP 제한 없음 + 정차). */
    var setupOk by mutableStateOf(false)
    var linkOk by mutableStateOf(false)
    var flowState by mutableStateOf(FlowState.OFF)
    var clockVerified by mutableStateOf(true)
    var relayConfigured by mutableStateOf(true)
    var speedGranted by mutableStateOf(false)
    /** 차 sync 응답으로 본 휴대폰 · 워치 · 서버 연결. */
    var links by mutableStateOf(Links.NONE)
    /** 짝지은 뒤 상태 화면(상태 한 줄 · 정차 중 연결 줄 · 워치 칸 · 테스트 입구 · 주행 중 워치 한 줄은 10초 묶음 뒤 값). */
    var idle by mutableStateOf(IdleView(CarTexts.STATUS_WATCHING, emptyList(), false))
    /** 「휴대폰 연결 끊기」를 보일지 — 짝 있음 + 확정 정지 + 사건 없음([CarHub.unpairOk]). */
    var unpairOk by mutableStateOf(false)
    /** 연결 끊기 때 중계에 알리지 못함 — 번호 화면에 「중계 기록은 24시간 뒤 사라짐」. */
    var unpairRelayLeft by mutableStateOf(false)
    /** 포그라운드 서비스를 붙잡아 둘지([ServicePolicy]) — 참이 되면 창이 보이는 동안 화면이 서비스를 시작한다. */
    var serviceWanted by mutableStateOf(false)
    /** 이 프로세스에서 차속 권한 창을 띄운 적 있음 — 그 뒤에도 권한이 없으면 거부로 본다(G-2). 화면이 쓴다. */
    var speedAsked by mutableStateOf(false)
    /** 에어백 전개 신호를 읽을 수 없는 차(F-2) — 정차 화면 상세 안내에만. */
    var airbagUnreadable by mutableStateOf(false)
    /** 지금 사건의 보호자 문자 결과(A-5) — 화면 줄용. */
    var smsStatus by mutableStateOf<SmsStatus?>(null)
}

/**
 * 스레드 규칙 — 허브 · 짝짓기 상태는 주기 스레드(`guardian-tick`)에서만 만진다. HTTP 는 모두 네트워크 스레드(`guardian-net`)에서 하고
 * 결과만 주기 스레드로 돌려보낸다. 중계가 멈춰도 2초 주기(차량 읽기 · tick · 차 안 경보)는 밀리지 않는다.
 */
class CarController private constructor(context: Context) {
    val ui = CarUiState()

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val tick = Handler(HandlerThread("guardian-tick").apply { start() }.looper)
    private val netHandler = Handler(HandlerThread("guardian-net").apply { start() }.looper)
    private val net = Executor { netHandler.post(it) }
    /** 중계 토큰은 Keystore 키로 감싸 저장한다(감사 하1). 주기 스레드에서만 만진다. */
    private val store = CarPairStore(PrefsKeyValue(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)), KeystoreTokenCipher()) { Log.w(TAG, it) }
    private val relay = RelayClient(BuildConfig.RELAY_BASE)
    private val signals: CarSignalsSource = CarSignalsSource(app) { ux -> onTick { hub.onUx(ux); publish() } }
    private val voice = CarVoice(app)
    private val light = AmbientLight { signals.vehicleSdk() }
    private val hub: CarHub = CarHub(object : CarPorts {
        override fun speak(text: String) = voice.say(text)
        override fun openSearch(query: String) { signals.openSearch(query) }
        override fun ambient(decision: AmbientDecision) { light.apply(decision) }
    })
    private val seq = SeqGenerator(System.currentTimeMillis())
    private val loop = CarLoop(
        hub = hub, relay = relay::carSync, net = net, deliver = { onTick(it) }, clock = ::now,
        onUnauthorized = ::forgetPair,
        onError = { e -> Log.w(TAG, "sync 결과 처리 실패 ${e.javaClass.simpleName}") },
        onUnparsable = { reason -> Log.w(TAG, "sync 응답 해석 실패(링크는 유지) $reason") },
        onHttpError = { code -> Log.w(TAG, "sync 실패 HTTP $code") },
        onReport = { rep ->
            if (rep.adopted) Log.i(TAG, "휴대폰 흐름을 넘겨받음 · 시계 확인=${rep.clockVerified}")
            val rejected = rep.remote.values.count { it == RemoteOutcome.REJECTED }
            if (rejected > 0) Log.w(TAG, "받은 지시 거부 ${rejected}건(금지 모드 · 틀린 형식)")
            publish()
        },
    )

    // 주기 스레드 전용
    private var pairCode: String? = null
    private var pairCodeUntilMs = 0L
    private var nextPairTryMs = 0L
    private var pairFailed = false
    private var pairBusy = false
    private var unpairRelayLeft = false
    private val bioThrottle = BioThrottle()
    private val serviceGate = ServiceGate()

    private val period = object : Runnable {
        override fun run() {
            val started = now()
            runCatching { step() }.onFailure { Log.w(TAG, "주기 처리 실패 ${it.javaClass.simpleName}") }
            tick.postDelayed(this, (PERIOD_MS - (now() - started)).coerceAtLeast(0))
        }
    }

    init { tick.post(period) }

    /**
     * 차 화면 「괜찮아요」. 버튼이 보일 때(UX 제한 없음 + 정지 화면 + 정지)만 응답으로 받는다(허브가 다시 확인).
     * 누른 순간의 최신 UX 값 · 속도를 다시 읽어 확인한다 — 주기 값이 2초 늦을 수 있어서(G5).
     */
    fun pressOk() = onTick { hub.onCarButton(now(), signals.read()); publish() }

    /**
     * 차 화면 「테스트 시작」(확인 창의 「시작」) · 「테스트 멈추기」. 누른 순간의 최신 UX 값 · 속도를 다시 읽어 넣은 뒤
     * 허브가 정차 · 입구 조건을 확인한다 — 주기 값이 2초 늦을 수 있어서(G5, pressOk 와 같은 방식).
     */
    fun requestTest(action: TestAction) = onTick {
        val t = now()
        hub.onReading(signals.read(), t)
        if (hub.requestTest(action, t)) Log.i(TAG, "테스트 요청 ${action.name}")
        publish()
    }

    /** 워치를 다시 붙일 새 연결 번호. NO_SETUP 제한이 없고 정차일 때만. */
    fun requestNewCode() = onTick {
        val token = token() ?: return@onTick
        if (!hub.setupOk() || pairBusy) return@onTick
        pairBusy = true
        net.execute {
            val r = relay.pairCode(token)
            onTick {
                pairBusy = false
                when (r) {
                    is RelayResult.Ok -> showCode(r.value.pairCode, r.value.expiresInSec)
                    is RelayResult.Http -> if (r.code == 401) forgetPair() else {
                        r.httpFailureLog("새 연결 번호")?.let { Log.w(TAG, it) }
                        pairFailed = true
                    }
                    is RelayResult.Failed, is RelayResult.Unparsable -> pairFailed = true
                }
                publish()
            }
        }
    }

    /**
     * 차 화면 「휴대폰 연결 끊기」(확인 창의 「끊기」, 감사 하3). 누른 순간의 최신 차량 · UX 값을 넣은 뒤 문(확정 정지 · 사건 없음)을 다시 보고,
     * 기기 쪽(토큰 · 켬 기억 · 짝 상태 · 보낼 함 · ack)을 먼저 지운 뒤 꺼낸 토큰으로 pair/end 를 한 번 보낸다([CarUnpair]).
     * 나가 있는 sync 결과는 버린다 — 옛 토큰의 성공 응답이 앞 운전자 상태를 다시 채우지 않게.
     */
    fun disconnectPhone() = onTick {
        val t = now()
        hub.onReading(signals.read(), t)
        if (pairBusy) { publish(); return@onTick } // 버튼은 pairBusy 동안 숨겨져 있다 — 그 사이 눌린 것은 화면만 맞춘다
        val start = CarUnpair.start(hub, store, t)
        if (start !is CarUnpair.Start.Cleared) { publish(); return@onTick }
        loop.discardInFlight()
        pairCode = null
        pairFailed = false
        nextPairTryMs = 0
        unpairRelayLeft = false
        Log.i(TAG, "휴대폰 연결 끊기 — 차 쪽 지움")
        val token = start.token
        if (token != null && relay.configured) {
            pairBusy = true // 끝내기가 돌아올 때까지 새 짝짓기를 미룬다(같은 중계에 순서대로)
            net.execute {
                val r = relay.pairEnd(token)
                onTick {
                    pairBusy = false
                    unpairRelayLeft = !CarUnpair.relayCleared(r)
                    r.httpFailureLog("연결 끊기")?.let { Log.w(TAG, it) }
                    publish()
                }
            }
        }
        publish()
    }

    private fun step() {
        val t = now()
        if (pairCode != null && t > pairCodeUntilMs) pairCode = null
        val token = token()?.takeIf { relay.configured }
        signals.locationConsent = hub.snapshot.consents.location
        loop.step(signals.read(), token, seq::next, System.currentTimeMillis())
        if (token() == null) autoPair(t)
        publish()
    }

    /** 짝이 없으면 NO_SETUP 제한이 없는 정차 중에 pair/start 한다. 실패하면 10초 뒤 다시. */
    private fun autoPair(t: Long) {
        if (!relay.configured || pairBusy || t < nextPairTryMs || !hub.setupOk()) return
        nextPairTryMs = t + PAIR_RETRY_MS
        pairBusy = true
        net.execute {
            val r = relay.pairStart()
            onTick {
                pairBusy = false
                if (r is RelayResult.Ok) {
                    store.saveToken(r.value.carToken)
                    showCode(r.value.pairCode, r.value.expiresInSec)
                } else {
                    r.httpFailureLog("짝짓기")?.let { Log.w(TAG, it) }
                    pairFailed = true
                }
                publish()
            }
        }
    }

    private fun showCode(code: String, expiresInSec: Int) {
        pairCode = code
        pairCodeUntilMs = now() + expiresInSec * 1_000L
        pairFailed = false
    }

    /** 401 — 짝이 끝났거나 만료(24시간) · 중계 재기동. 토큰과 허브의 옛 짝 흔적을 지우고 다시 짝짓기로(경보 중이면 경보는 이어 간다). */
    private fun forgetPair() {
        store.forgetToken()
        hub.onPairLost(now())
        pairCode = null
        nextPairTryMs = 0
        Log.i(TAG, "토큰 무효 — 다시 짝짓기")
    }

    private fun token(): String? = store.token()

    private fun publish() {
        val t = now()
        val screen = hub.screen()
        val state = hub.snapshot.state
        val setupOk = hub.setupOk()
        val linkOk = hub.carIsOwner(t)
        val paired = token() != null
        val code = pairCode
        val failed = pairFailed
        val verified = hub.clockVerified
        val speed = signals.speedGranted()
        val airbagUnreadable = signals.airbagUnreadable()
        val smsStatus = hub.smsStatus()
        val links = hub.links(t)
        val snap = hub.snapshot
        // 짝짓기 · 새 번호 · 끝내기 요청이 나가 있는 동안은 숨긴다(app-qa 하2 — 눌러도 무시되는 버튼을 두지 않게)
        val unpairOk = paired && !pairBusy && hub.unpairOk()
        if (links.phone) unpairRelayLeft = false // 새 짝의 휴대폰이 붙으면 지난 안내는 걷는다
        val relayLeft = unpairRelayLeft
        // 부팅 · 업데이트 뒤 다시 띄울지(CarResume) — 휴대폰 설정을 받은 뒤(sync 성공 10초 안)의 흐름 켬을 바뀔 때만 저장한다.
        // 시작 직후 아직 sync 전의 꺼짐(기본값)으로 저장값을 덮지 않게
        store.rememberPower(linkOk, snap.powerOn)
        // 짝 없음 + OFF · S0 이면 서비스를 놓는다(C-7). 사건 중 401 이면 경보 상태라 붙잡아 둔다
        val keepService = ServicePolicy.keep(paired, state)
        if (serviceGate.update(keepService) == ServiceGate.Change.STOP) main.post { GuardianService.stop(app) }
        val raw = CarScreen.idle(
            linkOk, state, snap.powerOn, snap.consents.all, links, setupOk, code,
            bio = hub.bio(t), test = if (setupOk) hub.testEntry(t) else null, via = hub.watchVia,
            notice = CarScreen.noticeLine(state, screen),
        )
        // 주행 중 워치 한 줄은 10초에 한 번만 바꾼다. 정차로 돌아오면 묶음을 풀어 다음 주행 첫 순간에 바로 그린다
        if (setupOk) bioThrottle.reset()
        val idle = if (setupOk) raw else raw.copy(driveBio = bioThrottle.show(t, raw.driveBio))
        main.post {
            ui.screen = screen
            ui.flowState = state
            ui.setupOk = setupOk
            ui.linkOk = linkOk
            ui.paired = paired
            ui.pairCode = code
            ui.pairFailed = failed
            ui.clockVerified = verified
            ui.relayConfigured = relay.configured
            ui.speedGranted = speed
            ui.links = links
            ui.idle = idle
            ui.unpairOk = unpairOk
            ui.unpairRelayLeft = relayLeft
            ui.serviceWanted = keepService
            ui.airbagUnreadable = airbagUnreadable
            ui.smsStatus = smsStatus
        }
    }

    /** tick 스레드에 일을 올린다. 예외가 나도 tick 스레드(감시 전체)가 멈추지 않게 감싸고 예외 종류만 남긴다. */
    private fun onTick(block: () -> Unit) {
        tick.post { runCatching(block).onFailure { Log.w(TAG, "tick 작업 실패 ${it.javaClass.simpleName}") } }
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        private const val TAG = "CarController"
        private const val PREFS = "guardian_car"
        /** 계약 권장 주기 — 차 2초. */
        const val PERIOD_MS = 2_000L
        private const val PAIR_RETRY_MS = 10_000L

        @Volatile private var instance: CarController? = null

        /** 다시 띄우기 판단용 저장값 — (마지막 흐름 켬, 짝 토큰 있음). 수신자가 프로세스 시작 직후 읽는다. */
        fun savedResumeState(context: Context): Pair<Boolean, Boolean> =
            CarPairStore.resumeState(PrefsKeyValue(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))

        fun get(context: Context): CarController =
            instance ?: synchronized(this) { instance ?: CarController(context).also { instance = it } }
    }
}
