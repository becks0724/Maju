// 가상 시계 무대 — 가짜 중계 하나와 차 · 휴대폰 · 워치를 같은 가상 시간에서 주기대로 돌리고, 기기마다 시계 차이를 준다
package com.example.guardian.integration

import com.example.guardian.car.CarHub
import com.example.guardian.car.CarLoop
import com.example.guardian.car.CarPorts
import com.example.guardian.car.CarReading
import com.example.guardian.car.AmbientDecision
import com.example.guardian.car.RelayResult
import com.example.guardian.car.ScreenView
import com.example.guardian.car.SyncReport
import com.example.guardian.car.UxGate
import com.example.guardian.car.VehicleMapping
import com.example.guardian.phone.core.Body119
import com.example.guardian.phone.core.LastSyncWait
import com.example.guardian.phone.core.Withdrawal
import com.example.guardian.phone.core.Notice
import com.example.guardian.phone.core.PendingSms
import com.example.guardian.phone.core.PhoneEffects
import com.example.guardian.phone.core.PhoneEngine
import com.example.guardian.phone.core.PhoneStore
import com.example.guardian.phone.core.Registration
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.SimTrace
import com.example.guardian.shared.bio.Simulator
import com.example.guardian.shared.protocol.CarSyncResponse
import com.example.guardian.shared.protocol.GuardianSmsDedup
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.PairJoinRequest
import com.example.guardian.shared.protocol.PairJoinResponse
import com.example.guardian.shared.protocol.PairStartResponse
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Role
import com.example.guardian.shared.protocol.SeqGenerator
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.report.Consents
import com.example.guardian.watch.core.Clocks
import com.example.guardian.watch.core.WatchAction
import com.example.guardian.watch.core.WatchEngine
import com.example.guardian.watch.net.HttpResponse
import com.example.guardian.watch.net.HttpTransport
import com.example.guardian.watch.net.RelayApi
import com.example.guardian.watch.net.SyncRunner
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.Executor

/**
 * 가상 시간 [elapsed](ms)이 [STEP_MS] 씩 간다. 중계 시계 = [EPOCH] + elapsed.
 * 기기 시계 — 단조 = 기기마다 다른 부팅 기준 + elapsed, 벽시계 = 중계 시계 + 기기 시계 차이.
 * 주기 — 차 2초(위상 0), 휴대폰 2초(위상 1초), 워치 1초(위상 0.5초, sync 는 2초마다 + 판정이 바뀌면 즉시).
 */
class World {
    var elapsed = 0L
        private set
    val relayNow: Long get() = EPOCH + elapsed
    val relay = FakeRelay({ relayNow })

    /** 워치 → 휴대폰 Data Layer 로 오간 JSON(G2 감시). */
    val dataLayer = ArrayList<String>()

    private class Ticker(val periodMs: Long, val phaseMs: Long, val action: () -> Unit)
    private val tickers = ArrayList<Ticker>()
    private val afterStep = ArrayList<() -> Unit>()

    fun every(periodMs: Long, phaseMs: Long, action: () -> Unit) { tickers += Ticker(periodMs, phaseMs, action) }

    /** 매 단계 뒤에 부를 불변식 검사. */
    fun check(f: () -> Unit) { afterStep += f }

    fun advance(ms: Long) {
        val end = elapsed + ms
        while (elapsed < end) {
            elapsed += STEP_MS
            for (t in tickers) if ((elapsed - t.phaseMs) % t.periodMs == 0L) t.action()
            afterStep.forEach { it() }
        }
    }

    /** [cond] 가 참이 될 때까지 돌린다. [maxMs] 안에 안 되면 [what] 으로 실패. 걸린 시간을 돌려준다. */
    fun runUntil(maxMs: Long, what: String, cond: () -> Boolean): Long {
        val start = elapsed
        while (!cond()) {
            if (elapsed - start >= maxMs) throw AssertionError("${maxMs / 1000}초 안에 일어나지 않음: $what (가상 ${elapsed / 1000}초)")
            advance(STEP_MS)
        }
        return elapsed - start
    }

    companion object {
        const val STEP_MS = 250L
        const val EPOCH = 1_761_800_000_000L
    }
}

/** 기기 시계 하나 — 단조 기준과 벽시계 차이를 따로 준다. */
class DeviceClock(private val world: World, private val monoBase: Long, private val wallSkewMs: Long) : Clocks {
    /** 사용자가 벽시계를 바꾼 만큼(앞 + · 뒤 −) — 앱2-하6 재현용. */
    var wallShiftMs = 0L
    override fun mono(): Long = monoBase + world.elapsed
    override fun wall(): Long = world.relayNow + wallSkewMs + wallShiftMs
}

/** 등록 정보 — G2 감시가 중계 JSON 에서 찾는 글자들. */
object Fixture {
    const val NAME = "홍길동"
    const val GUARDIAN_NAME = "김보호"
    const val GUARDIAN_PHONE = "010-0000-0004"
    const val GUARDIAN_DIGITS = "01000000004"
    const val PLATE = "123가4567"
    const val COLOR = "펄화이트"
    val BIRTH: LocalDate = LocalDate.of(1960, 3, 1)

    fun registration(consents: Consents = Consents(true, true, true), powerOn: Boolean = true) = Registration(
        name = NAME, birthDate = BIRTH, carColor = COLOR, carPlate = PLATE, guardianName = GUARDIAN_NAME,
        guardianPhone = GUARDIAN_PHONE, consents = consents, powerOn = powerOn,
    )

    /** 중계 · Data Layer JSON 에 한 번도 나오면 안 되는 등록 정보 글자. */
    val forbiddenText = listOf(NAME, GUARDIAN_NAME, GUARDIAN_PHONE, GUARDIAN_DIGITS, PLATE, COLOR, "1960-03-01")

    /** 원신호(PPG · 가속도 · 심박 샘플) 필드 이름 — 워치 밖으로 나가면 안 된다(G2). 휴대폰 위치도 중계로 가지 않는다(감사 중4 A 안). */
    val forbiddenKeys = setOf("ppg", "accel", "green", "ir", "red", "x", "y", "z", "bpm", "ibiMs", "samples", "hrSamples", "phoneLocation")
}

// ── 차 ──

class RecordingCarPorts : CarPorts {
    val spoken = ArrayList<String>()
    val searches = ArrayList<String>()
    val ambient = ArrayList<AmbientDecision>()
    override fun speak(text: String) { spoken += text }
    override fun openSearch(query: String) { searches += query }
    override fun ambient(decision: AmbientDecision) { ambient += decision }
}

class CarDevice(private val world: World, monoBase: Long = 5_000_000, wallSkewMs: Long = 90_000) {
    val clock = DeviceClock(world, monoBase, wallSkewMs)
    val ports = RecordingCarPorts()
    private var n = 0
    val hub = CarHub(ports, newId = { "car-${n++}" })
    var token: String? = null
    var linkUp = true
    /** 중계는 요청을 처리했는데 응답만 유실(읽기 제한 초과 · 내려받기 유실 흉내, 합동 점검 판2-1). */
    var respLost = false
    var reading: CarReading = parked()
    /** 내비 위치를 받은 지 얼마나 지났나(단계마다 그만큼 앞선 단조 시각을 받은 시각으로 넣는다). null = 받은 적 없음. */
    var naviAgeMs: Long? = 0
    val reports = ArrayList<SyncReport>()
    val errors = ArrayList<Exception>()
    private val seq = SeqGenerator(clock.wall())

    /** 단계마다 본 차 화면(시각 · 속도 · 화면). */
    val screens = ArrayList<Triple<Long, Double, ScreenView>>()

    private val loop = CarLoop(
        hub = hub,
        relay = { req ->
            if (!linkUp) RelayResult.Failed("link down")
            else {
                val r = world.relay.post("/v1/car/sync", Json.encode(req.toJson()))
                if (respLost) RelayResult.Failed("read timeout")
                else if (r.status == 200) RelayResult.Ok(CarSyncResponse.fromJson(JsonObj(Json.parseObject(r.body))))
                else RelayResult.Http(r.status, JsonObj(Json.parseObject(r.body)).strOrNull("error"))
            }
        },
        net = Executor { it.run() },
        deliver = { it() },
        clock = clock::mono,
        onUnauthorized = { token = null; hub.onPairLost(clock.mono()) }, // CarController.forgetPair 와 같다
        onReport = { reports += it },
        onError = { errors += it },
    )

    fun install(period: Long = 2_000, phase: Long = 0) = world.every(period, phase) { step() }

    fun step() {
        val r = if (reading.naviLat == null) reading else reading.copy(naviAtMs = naviAgeMs?.let { clock.mono() - it })
        loop.step(r, token, seq::next, clock.wall())
        screens += Triple(world.elapsed, VehicleMapping.speedKmh(reading.speedMps) ?: -1.0, hub.screen())
    }

    /** 차 화면 「괜찮아요」 — 누른 순간의 최신 신호로 다시 확인한다. */
    fun pressOk(): Boolean = hub.onCarButton(clock.mono(), reading)

    fun pairStart(): String {
        val r = world.relay.post("/v1/pair/start", "{}")
        val p = PairStartResponse.fromJson(JsonObj(Json.parseObject(r.body)))
        token = p.carToken
        return p.pairCode
    }

    fun state() = hub.snapshot.state

    companion object {
        // 에어백은 실차처럼 false 로 읽는다 — 차는 false 를 한 번 읽은 뒤의 true 만 사고로 본다(시작부터 켜진 에어백 막기, 독립 검토 상)
        fun driving(kmh: Double = 60.0) = CarReading(
            speedMps = (kmh / 3.6).toFloat(), gearRaw = VehicleMapping.GEAR_DRIVE, parkingBrake = false, airbagDeployed = false,
            ux = UxGate(requiresDo = true, noSetup = true), make = "현대", model = "그랜저", year = 2026,
            naviAddress = "서울 중구 세종대로 110", naviLat = 37.56650, naviLon = 126.97800,
        )

        fun parked() = CarReading(
            speedMps = 0f, gearRaw = VehicleMapping.GEAR_PARK, parkingBrake = null, airbagDeployed = false,
            ux = UxGate(requiresDo = false, noSetup = false), make = "현대", model = "그랜저", year = 2026,
            naviAddress = "서울 중구 세종대로 110", naviLat = 37.56650, naviLon = 126.97800,
        )
    }
}

// ── 휴대폰 ──

class MemStore(var reg: Registration) : PhoneStore {
    private val sms = LinkedHashSet<String>()
    private val done = HashSet<String>()
    /** 보낸(또는 테스트 확인을 마친) 보호자 문자 키 `kind:eventId`. */
    val smsKeys: Set<String> get() = sms
    override val registration: Registration get() = reg
    override fun isSmsSent(key: String) = key in sms
    override fun markSmsSent(key: String) { sms += key }
    override var smsRecords: List<GuardianSmsDedup.Record> = emptyList()
    override fun isCommandDone(id: String) = id in done
    override fun markCommandDone(id: String) { done += id }
    override var savedFlow: com.example.guardian.phone.core.SavedFlow? = null
    // 생산 저장소(Prefs)처럼 실제로 둔다 — 기본 구현(늘 null · 쓰기 무시)이면 통합 시험 전체에서 「참여 전 사건」 방어와 원격 상한이 꺼진다(3차 검토)
    override var pairJoinedAtServerMs: Long? = null
    override var mutedEventId: String? = null
    override var remoteSmsLimit: String? = null
    override var carOriginEventIds: List<String> = emptyList()
}

/** 휴대폰 바깥 동작 기록. 문자는 「보냄」으로만 기록하고, 119 로 보내는 함수는 없다. */
class PhoneLog(private val world: World) : PhoneEffects {
    data class Sms(val atMs: Long, val to: String, val body: String)
    val alarms = ArrayList<String>()
    var alarmOn = false
    val sms = ArrayList<Sms>()
    val asked = ArrayList<PendingSms>()
    val noResponse = ArrayList<Body119>()
    val composeOpened = ArrayList<String>()
    val previews = ArrayList<Body119>()
    val lines = ArrayList<String>()
    val notices = ArrayList<Notice>()
    val smsResults = ArrayList<Pair<String, Boolean?>>()

    override fun startAlarm(stage: String, level: Int, test: Boolean) { alarms += "start $stage $level test=$test"; alarmOn = true }
    override fun stopAlarm() { alarms += "stop"; alarmOn = false }
    override fun muteAlarm() { alarms += "mute"; alarmOn = false }
    // 문자 결과(sentIntent)는 곧바로 「보냄」으로 돌려준다 — 휴대폰 시험의 가짜(Fakes.smsDelivery 기본값)와 같다
    override fun sendSms(to: String, body: String, done: (Boolean) -> Unit): Boolean { sms += Sms(world.elapsed, to, body); done(true); return true }
    override fun guardianSmsResult(kind: String, sent: Boolean?) { smsResults += kind to sent }
    override fun askTestSms(pending: PendingSms) { asked += pending }
    override fun showNoResponse(body: Body119) { noResponse += body }
    override fun open119Compose(body: String) { composeOpened += body }
    override fun show119Preview(body: Body119) { previews += body }
    override fun showLine(line: String, button: String?, test: Boolean) { lines += line }
    override fun notice(n: Notice) { notices += n }
    override fun clearTestScreens() { lines += "clearTestScreens" }
}

class PhoneDevice(
    private val world: World,
    reg: Registration = Fixture.registration(),
    monoBase: Long = 123_456_789,
    wallSkewMs: Long = -30_000,
) {
    val clock = DeviceClock(world, monoBase, wallSkewMs)
    val store = MemStore(reg)
    val log = PhoneLog(world)
    var token: String? = null
    var linkUp = true
    /** 사람이 테스트 문자 확인 대화에서 「보내기」를 누른다(자동). */
    var confirmTestSms = true
    /** 휴대폰 GPS(속도 km/h). null 이면 위치를 못 잡음. */
    var gpsKmh: Double? = 0.3
    private var n = 0
    val engine = PhoneEngine(
        store = store, effects = log, newId = { "phone-${n++}" }, wallNow = clock::wall,
        seqStartEpochMs = clock.wall(), startMono = clock.mono(), relayActive = { token != null },
    )

    /** Data Layer 로 받은 최신 워치 값(WatchLink.take 와 같이 마지막 하나). */
    private var dlInbox: Pair<Watch, Long>? = null

    fun receiveDataLayer(json: String) {
        dlInbox = Watch.fromJson(JsonObj(Json.parseObject(json))) to clock.mono()
    }

    fun install(period: Long = 2_000, phase: Long = 1_000) = world.every(period, phase) { cycle() }

    /** 철회가 맡긴 마지막 sync(GuardianService.lastWait 와 같다). */
    private var lastWait: LastSyncWait? = null

    /** 「동의 철회」 — MainActivity.withdraw 와 같은 순서(동의 · 토큰은 바로 지우고, 잡아 둔 토큰으로 마지막 sync 뒤 짝 끝내기). */
    fun withdraw() {
        Withdrawal.end(
            token,
            clear = { store.reg = store.reg.copy(consents = Consents(), powerOn = false); store.smsRecords = emptyList(); token = null },
            lastSync = { t, after -> lastWait = LastSyncWait(t, after) },
            endPair = { t -> world.relay.post("/v1/pair/end", Json.encode(mapOf("token" to t))) },
        )
    }

    /** GuardianService.cycle 와 같은 순서 — 워치 값 · 위치 → tick → sync → tick → (보낼 것이 남으면) sync → tick. */
    fun cycle() {
        if (!store.registration.powerOn) {
            engine.shutdown(clock.mono())
            val w = lastWait
            val r = syncWith(token ?: w?.token)
            if (w != null && !w.done(r, clock.mono())) return
            w?.finish(); lastWait = null
            return
        }
        dlInbox?.let { (w, at) -> engine.onDataLayerWatch(w, at); dlInbox = null }
        gpsKmh?.let { if (store.registration.consents.location) engine.onLocation(loc(it), clock.mono()) }
        engine.tick(clock.mono())
        syncOnce()
        engine.tick(clock.mono())
        if (engine.hasOutgoing()) { syncOnce(); engine.tick(clock.mono()) }
        if (confirmTestSms) engine.executor.pending().forEach { engine.confirmTestSms(it.key, true) }
    }

    fun syncOnce() { syncWith(token) }

    /** sync 한 번 — 받은 응답(실패 · 보내지 않음이면 null). */
    private fun syncWith(t: String?): PhoneSyncResponse? {
        t ?: return null
        if (!linkUp) { engine.onSyncError(); return null }
        val sent = clock.mono()
        val req = engine.buildSync(t, sent)
        val r = world.relay.post("/v1/phone/sync", Json.encode(req.toJson(store.registration.consents.health)))
        when (r.status) {
            200 -> return PhoneSyncResponse.fromJson(JsonObj(Json.parseObject(r.body))).also { engine.onSyncResponse(it, sent, clock.mono()) }
            401 -> { token = null; engine.onUnauthorized() }
            else -> engine.onSyncError()
        }
        return null
    }

    fun join(code: String) {
        val r = world.relay.post("/v1/pair/join", Json.encode(PairJoinRequest(code, Role.PHONE).toJson()))
        check(r.status == 200) { "휴대폰 짝 참여 실패 ${r.body}" }
        token = PairJoinResponse.fromJson(JsonObj(Json.parseObject(r.body))).token
        store.pairJoinedAtServerMs = com.example.guardian.phone.core.PhoneStore.JOIN_PENDING // MainActivity 참여 성공과 같다
    }

    fun status() = engine.status(clock.mono())

    fun loc(kmh: Double) = Loc(37.56700, 126.97900, 8.0, kmh, clock.wall(), "서울 중구 태평로1가")
}

// ── 워치 ──

class WatchDevice(
    private val world: World,
    monoBase: Long = 777_000,
    wallSkewMs: Long = 4_000,
    /** 중계 직접 경로(/v1/watch/sync)를 쓰는가. */
    var direct: Boolean = true,
) {
    val clock = DeviceClock(world, monoBase, wallSkewMs)
    val engine = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false)
    var token: String? = null
    var linkUp = true
    /** Data Layer 로 휴대폰에 보낼 곳(null 이면 안 보냄). */
    var dataLayerTo: PhoneDevice? = null
    val actions = ArrayList<Pair<Long, WatchAction>>()
    private var lastVerdict: String? = null
    private var ticks = 0L

    /**
     * 새 워치(계약 §4.3 한 판) — 값의 `responseSeq` · `alerting` 은 워치 엔진이 그대로 싣는다(경보가 울리는 동안의 누름만 번호를 올림, 엔진 규칙).
     * false 면 옛 워치 흉내 — 엔진 값에서 두 필드를 지우고 누름에 번호를 주지 않는다.
     */
    var newWatchFields = true
    val responseSeq: Long? get() = engine.responseSeq
    /** 워치 저장소(PairStore.nextResponseSeq)처럼 누를 때마다 1 오르는 번호. */
    private var storedSeq = 0L

    private fun decorate(watch: MutableMap<String, Any?>) {
        if (newWatchFields) return
        watch.remove("responseSeq")
        watch.remove("alerting")
    }

    @Suppress("UNCHECKED_CAST")
    private fun decorateRequest(path: String, body: String): String {
        if (path != "/v1/watch/sync") return body
        val o = Json.parseObject(body).toMutableMap()
        val w = (o["watch"] as? Map<String, Any?>)?.toMutableMap() ?: return body
        decorate(w)
        o["watch"] = w
        return Json.encode(o)
    }

    /** 워치가 받지 못할 지시(중계는 ttl 동안 계속 내려 주지만 워치에 닿지 않음 — 합동 점검 C-2 재현). 지시 JSON 맵을 받는다. */
    var dropCommand: (Map<String, Any?>) -> Boolean = { false }
    /** 다음 응답에 끼워 넣을 묵은 지시(늦게 도착한 지시 흉내). */
    val lateCommands = ArrayList<Map<String, Any?>>()

    private val api = RelayApi(HttpTransport { path, body ->
        if (!linkUp) throw IOException("link down")
        val r = world.relay.post(path, decorateRequest(path, body))
        HttpResponse(r.status, if (path == "/v1/watch/sync" && r.status == 200) tamper(r.body) else r.body)
    })

    @Suppress("UNCHECKED_CAST")
    private fun tamper(body: String): String {
        val o = Json.parseObject(body).toMutableMap()
        val cmds = (o["commands"] as? List<Map<String, Any?>>).orEmpty().filterNot(dropCommand) + lateCommands
        lateCommands.clear()
        o["commands"] = cmds
        return Json.encode(o)
    }
    private val runner = SyncRunner(api, engine, clock)

    /** 실제 센서 흉내 — 기본은 정상 맥박(손목 정지)이 끝없이 이어진다. 시나리오 중에는 엔진이 버린다. */
    private var real: SimTrace = Simulator(seed = 3).generate(Scenario.NORMAL, durationMs = 40 * 60_000L, eventAtMs = 0, startAtMs = clock.mono())
    private var ip = 0
    private var ia = 0
    private var ih = 0

    /** 실제 센서 입력을 [scenario] 로 바꾼다(테스트 아님 — 실제 사건 흉내). 지금부터 [eventInMs] 뒤에 변화. */
    fun realSignal(scenario: Scenario, eventInMs: Long = 20_000) {
        real = Simulator(seed = 5).generate(scenario, durationMs = 20 * 60_000L, eventAtMs = eventInMs, startAtMs = clock.mono())
        ip = 0; ia = 0; ih = 0
    }

    fun install(phase: Long = 500) = world.every(1_000, phase) { tick() }

    fun tick() {
        val now = clock.mono()
        while (ip < real.ppg.size && real.ppg[ip].atMs <= now) engine.ppg(real.ppg[ip++])
        while (ia < real.accel.size && real.accel[ia].atMs <= now) engine.accel(real.accel[ia++])
        while (ih < real.hr.size && real.hr[ih].atMs <= now) engine.hr(real.hr[ih++])
        val reading = engine.tick(now)
        val changed = reading.verdict.name != lastVerdict
        lastVerdict = reading.verdict.name
        dataLayerTo?.let { phone ->
            val json = Json.encode(engine.watch(clock).toJson(healthConsent = true).toMutableMap().also { decorate(it) })
            world.dataLayer += json
            phone.receiveDataLayer(json)
        }
        if (changed || ++ticks % 2 == 0L) sync()
    }

    fun sync(depth: Int = 0) {
        val t = token ?: return
        if (!direct) return
        when (val o = runner.runOnce(t, healthConsent = true)) {
            is SyncRunner.Outcome.Ok -> {
                o.actions.forEach { actions += world.elapsed to it }
                if (o.acksLeft && depth < 2) sync(depth + 1)
            }
            SyncRunner.Outcome.Unauthorized -> token = null
            is SyncRunner.Outcome.Failed -> Unit
        }
    }

    /** 워치 화면 「괜찮아요」 — 응답 시각을 한 번 잡고 바로 sync(MonitorService.respond 와 같음). */
    fun pressOk() {
        // 번호는 엔진이 경보가 울리는 동안에만 받는다(경보 밖 누름은 번호를 올리지 않음 — MonitorService 와 같은 길)
        engine.respond(clock.mono(), clock.wall(), if (newWatchFields) ({ ++storedSeq }) else null)
        sync()
    }

    fun join(code: String) {
        val r = api.join(code)
        check(r is com.example.guardian.watch.net.RelayResult.Ok) { "워치 짝 참여 실패 $r" }
        token = r.value.token
    }

    val alerting: Boolean get() = engine.alert != null
}
