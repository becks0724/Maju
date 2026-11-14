// 차 앱의 주인(허브) 핵심 — 차량 신호 · sync 응답을 상태 기계 입력으로 바꾸고, 지시를 차 안 실행 · 상대 전송으로 나누며, 주인 넘김을 처리한다(안드로이드 의존 없음)
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowParams
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Handover
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.Ownership
import com.example.guardian.shared.flow.ResponseSource
import com.example.guardian.shared.flow.WatchFreshness
import com.example.guardian.shared.protocol.CarDisplay
import com.example.guardian.shared.protocol.CarSyncRequest
import com.example.guardian.shared.protocol.CarSyncResponse
import com.example.guardian.shared.protocol.ClockOffset
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.DirectiveCommands
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.GuardianSmsOutbox
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.ResponseChange
import com.example.guardian.shared.protocol.SmsDone
import com.example.guardian.shared.protocol.SmsDoneState
import com.example.guardian.shared.protocol.TestAction
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchAlertReconcile
import com.example.guardian.shared.protocol.WatchResponseTracker
import com.example.guardian.shared.text.Phrase
import java.util.UUID

/** 받은 지시 하나를 어떻게 처리했는지(기록 · 시험용). 모두 ack 한다. */
enum class RemoteOutcome {
    /** 금지 모드(BLUE_RED_BLINK) · 틀린 payload · 모르는 type(응답 해석에서 건너뛴 지시, 계약 §4.6 「지원하지 않음」) — 실행하지 않음. */
    REJECTED,
    /** 차가 주인이라 휴대폰이 낸 차 안 지시는 지난 것 — 차 상태 기계가 직접 경보한다. */
    IGNORED_CAR_OWNER,
    /** 이미 처리한 id(중계는 ack 전까지 다시 보낸다). */
    DUPLICATE,
    /** FLOW_SNAPSHOT — 넘겨받기는 응답의 peer.flow 로 한다. */
    SNAPSHOT_NOTED,
}

/** 워치 연결 — 신선한 값(계약 §4.3 30초 규칙)이 있으면 착용 여부까지, 없으면 신호 없음. */
enum class WatchLink { WORN, NOT_WORN, NO_SIGNAL }

/**
 * 차 sync 응답으로 본 연결 상태(화면 연결 줄 · 번호 화면 조건, [CarHub.links]).
 * - [phone] — 응답 `peer` 가 10초 안의 것(계약 §6 `serverTime − peer.receivedAt`, 응답 뒤 지난 로컬 시간 포함).
 * - [watch] — 응답 `watch` 의 `ageMs`(+ 응답 뒤 지난 로컬 시간)가 30초 이하인지와 상태 기계의 착용 판정.
 * - [server] — 차의 마지막 sync 성공이 10초 안(차가 주인).
 * - [watchSendingNoSignal] — 판정은 신호 없음이지만 워치가 붙어 `noSignal` 값을 30초 안에 보내고 있다(번호 화면용, 리뷰 11-08 의심).
 */
data class Links(val phone: Boolean, val watch: WatchLink, val server: Boolean, val watchSendingNoSignal: Boolean = false) {
    val watchSeen: Boolean get() = watch != WatchLink.NO_SIGNAL

    /** 워치가 중계에 붙어 있다 — 판정 신호가 있거나, 없어도 「신호 없음」을 보내는 중. */
    val watchJoined: Boolean get() = watchSeen || watchSendingNoSignal

    companion object {
        val NONE = Links(phone = false, watch = WatchLink.NO_SIGNAL, server = false)
    }
}

/** 보낸 테스트 요청 — 어떤 동작을 언제(단조 ms) 보냈는지. 결과는 흐름 `test` 로만 판단한다(계약 §4.6 「차에서 테스트 요청」). */
data class TestPending(val action: TestAction, val atMs: Long)

/** 지금 사건의 보호자 문자 결과(휴대폰 `flow.smsDone`) — 차 화면 줄용(A-5). */
enum class SmsStatus { CONFIRMED, NOT_SENT }

/** sync 응답 처리 결과. */
data class SyncReport(val adopted: Boolean, val clockVerified: Boolean, val remote: Map<String, RemoteOutcome>)

/**
 * 한 스레드에서만 부른다(앱은 `guardian-tick` 스레드). 모든 시각은 단조 시계(`SystemClock.elapsedRealtime`) ms 다.
 *
 * 주인 규칙(계약 §6) — 차가 마지막으로 sync 에 성공한 지 10초 안이면 차가 주인이다.
 * - 주인이면 상태 기계 지시 가운데 차 몫(SPEAK · SHOW · OPEN_SEARCH · AMBIENT)은 바로 실행하고 나머지는 보낼 함(outbox)에 쌓는다.
 * - 주인이 아니면(링크 10초 넘게 끊김) 차 안 경보만 계속하고 상대에게 낼 지시는 쌓지 않는다(보낼 함도 비운다).
 * - 주인이 아니던 차가 다시 붙었을 때 휴대폰이 주인으로 들고 있던 흐름(peer.flow, owner=PHONE)이 있으면 그것을 넘겨받는다.
 *
 * 보호자 문자 · 119 작성 지시는 사건 사실(WireFacts)만 실어 늘 PHONE 으로 보낸다 — 본문은 휴대폰이 등록 프로필로 조립한다(:shared IncidentComposer).
 * 실제 보호자 문자는 공용 보낼 함([GuardianSmsOutbox], A-2)에 두고 휴대폰이 `flow.smsDone` 으로 확인할 때까지 요청마다 같은 지시 id 로 싣는다 —
 * 주인이 아니어도 · 응답이 유실돼도 · 401 뒤에도 남긴다. 차 음성은 주인 + 휴대폰이 10초 안에 보일 때만 「보냅니다」, 아니면 「늦어질 수 있습니다」(A-5).
 *
 * 테스트 표시 — 휴대폰 설정의 테스트 모드 또는 신선한 워치 값의 `test`(휴대폰이 워치에 TEST_SCENARIO 를 보낸 상태)가 있으면 흐름을 테스트로 둔다.
 * 상태 기계는 테스트 표시를 OFF · S0 에서만 켜므로, 워치 판정보다 먼저 넣어 그 판정이 연 사건부터 테스트가 된다. 둘 다 꺼지면(세션 끝 ·
 * 워치가 실제 센서로 돌아옴) 상태 기계가 테스트 사건의 응답 뒤(S1R · S2)에서도 표시를 끄고 S0 으로 돌아간다(2026-11-07).
 */
class CarHub(
    private val ports: CarPorts,
    private val params: FlowParams = FlowParams(ambientEnabled = AmbientPolicy.ENABLED),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val ttlMs: Long = COMMAND_TTL_MS,
    ambientEnabled: Boolean = AmbientPolicy.ENABLED,
) {
    private var machine = FlowMachine(params)
    private val clock = ClockOffset()
    val executor = InCarExecutor(ports, ambientEnabled)

    private var lastOkMs: Long? = null
    private val outbox = ArrayList<Command>()
    /** 실제 보호자 문자 보낼 함(A-2). 「휴대폰 연결 끊기」만 비운다. */
    private var sms = GuardianSmsOutbox()
    /** 요청 seq → 보낼 함의 요청 번호 — 응답을 처리하면 그 번호로 보류를 푼다. */
    private val smsRequestNo = LinkedHashMap<Long, Long>()
    /** 휴대폰이 마지막으로 실어 보낸 문자 결과 — 화면 줄용. */
    private var lastSmsDone: List<SmsDone>? = null
    private val pendingAcks = LinkedHashSet<String>()
    private val handled = LinkedHashSet<String>()
    private var reading = CarReading()
    /** 워치 응답 — 번호(responseSeq)가 있으면 번호, 옛 워치면 responseAt 규칙(계약 §4.3 · B-5). 짝이 바뀌면 새로 만든다. */
    private var watchResponse = WatchResponseTracker()
    /** 워치 경보 맞추기 상태(계약 §4.3 · C-2) — 차가 주인일 때만 쓴다. */
    private var alertSync = WatchAlertReconcile.State()
    private var phoneResponse = ResponseChange(ResponseSource.PHONE_SCREEN)
    /** 휴대폰 설정의 테스트 모드(마지막으로 받은 값). */
    private var phoneTest = false
    /** 차가 마지막으로 낸 음성(사건 id · 문구) — 넘겨받을 때 같은 단계 음성을 되풀이하지 않게. */
    private var lastVoice: Pair<String?, Phrase>? = null

    /**
     * 휴대폰 단독(휴대폰 안) 시험이 도는 중(`flow.localTest`, 계약 §4.5 · 2026-11-07) — **표시 전용**. 상태 기계에 넣지 않는다(판정 · 경보 · 문자는 실제 그대로).
     * 차의 시작 요청을 풀고 테스트 입구에 「휴대폰에서 테스트 중 · 차 화면은 실제 상태입니다」를 둔다.
     */
    var phoneLocalTest = false
        private set
    /** 중계가 마지막 휴대폰 상태를 받은 순간을 내 단조 시계로 옮긴 값(받은 시각 − 응답의 `serverTime − peer.receivedAt`). 화면 연결 줄용. */
    private var peerSeenMs: Long? = null
    /** 마지막으로 받은 워치 값과 받은 단조 시각 — 화면 워치 칸용. 메모리에만 둔다(G2 — 저장 · 기록 금지). */
    private var lastWatch: Watch? = null
    private var lastWatchAtMs = 0L

    /** 이 허브(프로세스)가 처음 입력을 받은 단조 시각 — 시작 유예의 기준(A-4). */
    private var startMs: Long? = null
    /** 시작 유예가 끝났는지 — 휴대폰 흐름을 한 번 보았거나 6초([Handover.carWristAllowed])가 지남. */
    private var holdDone = false
    /** 이번 테스트에서 테스트 표시(휴대폰 · 워치)를 본 적 있음 — 흐름이 테스트가 아니게 되면 지운다(B-7, app-phone 과 맞춤). */
    private var testSeen = false
    /** 휴대폰 · 워치가 함께 「테스트 아님」을 보인 첫 시각. */
    private var testEndSinceMs: Long? = null

    /** 이 프로세스가 직접 읽은 마지막 에어백 값(null = 아직 못 읽음). 시작부터 true 인 값은 전이가 아니다. */
    private var lastAirbag: Boolean? = null
    /**
     * 이 프로세스가 에어백 false 를 한 번이라도 읽었는지. 그 전의 true 는 상태 기계에 모름(null)으로 넘긴다 — Pleos 에어백은 비동기라 첫 읽기가 늘 null 이고,
     * 상태 기계는 기본값 false 에서 null → true 를 전이로 본다. 앞선 사고로 true 가 남은 차가 뜰 때마다 사고를 열지 않게(독립 검토 상, 결정 16 · 21).
     */
    private var airbagFalseSeen = false
    /** 흐름이 OFF(설정 모름)일 때 본 에어백 false → true 전이 시각 — 감시가 켜지면 사고를 연다(3차 ①ⓐ). */
    private var heldAirbagAtMs: Long? = null
    /** 신선한 휴대폰 흐름의 설정을 한 번이라도 받았는지 — 꺼짐 · 미동의를 「확인됨」으로 볼 수 있는지. */
    private var settingsSeen = false

    /** 휴대폰의 차 화면 표시 설정(null = 모름 → 숨김). 메모리에만 둔다 — 앱을 다시 켜면 모름에서 시작한다. */
    var carDisplay: CarDisplay? = null
        private set

    /** 보낸 테스트 요청 가운데 결과(흐름 test 바뀜)를 아직 못 본 것. */
    var testPending: TestPending? = null
        private set

    /** 넘겨받을 때 시계 보정값이 없어 늦어지는 쪽으로 다시 잡았는지 — 화면에 「시계 미확인」. */
    var clockVerified = true
        private set

    val snapshot: FlowSnapshot get() = machine.snapshot()

    /** 지금 보낼 지시 — 문자 보낼 함(확인 전까지 남음) + 그 밖의 보낼 함(시험 · 기록용 복사본). */
    val pendingCommands: List<Command> get() = sms.items.map { it.command } + outbox

    /**
     * 지금 사건의 보호자 문자 결과(A-5) — 휴대폰이 보냄을 확인하면 CONFIRMED(화면 줄 「보호자에게 알림 보냄」, 음성은 새로 내지 않음),
     * 보내지 않았다고 알리면 NOT_SENT(정차 열에만 한 줄), 그 밖은 null.
     */
    fun smsStatus(): SmsStatus? {
        val id = snapshot.facts.eventId ?: return null
        if (sms.confirmed(id, lastSmsDone)) return SmsStatus.CONFIRMED
        val notSent = lastSmsDone?.any { it.eventId == id && it.kind != GuardianSmsWireKind.CANCEL && it.done == SmsDoneState.NOT_SENT } == true
        return if (notSent) SmsStatus.NOT_SENT else null
    }

    fun owner(nowMs: Long): Owner = Ownership.decide(nowMs, lastOkMs)
    fun carIsOwner(nowMs: Long) = owner(nowMs) == Owner.CAR

    /** 프로토콜 Vehicle(위치는 위치 동의가 있을 때만). [serverOffsetMs] 가 있으면 내비 위치 잰 시각(naviAt)도 싣는다. */
    fun vehicle(serverOffsetMs: Long? = null): Vehicle = VehicleMapping.toProtocol(trustedReading(), snapshot.consents.location, serverOffsetMs)

    /**
     * 내 상태 기계와 중계(휴대폰)가 함께 보는 차량 값 — 이 프로세스가 에어백 false 를 한 번 읽기 전의 true 는 모름(null)으로 거른다([airbagFalseSeen]).
     * 한 곳에서 걸러 두 길이 같은 값을 본다. 앞선 사고로 true 가 남은 차가 다시 떠도 어느 쪽도 새 전이로 보지 않게(독립 검토 상).
     */
    private fun trustedReading(): CarReading =
        if (reading.airbagDeployed == true && !airbagFalseSeen) reading.copy(airbagDeployed = null) else reading

    /** 지금 정지 — 상태 기계의 디바운스 확정 정지와 지금 신호의 정지가 둘 다 참일 때만(버튼 · 검색 화면 문). */
    fun stoppedNow(): Boolean = snapshot.stopped && VehicleMapping.toFlow(vehicle()).stopped(params.stopSpeedKmh)

    /** 경보 버튼 · 응급실 검색 문 — UX 제한이 DO 를 요구하지 않고(값이 없으면 제한됨) 자체 정지 판정도 정지일 때만. */
    fun touchOk(): Boolean = UxGate.allowsTouch(reading.ux) && stoppedNow()

    /** 짝짓기 · 새 연결 번호 · 권한 요청 창 문 — NO_SETUP 제한이 없고(값이 없으면 제한됨) 정차일 때만. */
    fun setupOk(): Boolean = UxGate.allowsSetup(reading.ux) && VehicleMapping.parkedForSetup(vehicle())

    /**
     * 「휴대폰 연결 끊기」 문 — 짝짓기 문([setupOk], NO_SETUP 제한 없음 + 정차)에 더해 **확정 정지**(기어 P)이고
     * 사건이 없을 때(OFF · S0)만. 경보(S1 · AP · S3 · S4 · S5)와 응답 뒤(S1R · S2)에는 경보 흐름 · 취소 문자를 끊지 않게 막는다(G5 · 감사 하3).
     */
    fun unpairOk(): Boolean {
        val v = vehicle()
        val confirmedStop = v.gear == Gear.P // 주차 브레이크만으로는 아님(사용자 결정 「완전 정지는 기어 P 또는 시동 꺼짐」)
        return setupOk() && confirmedStop && snapshot.state.let { it == FlowState.OFF || it == FlowState.S0 }
    }

    fun screen(): ScreenView = InCarExecutor.screen(snapshot, executor.lastShow, touchOk())

    /**
     * 화면 연결 줄 — 휴대폰은 중계가 받은 지 10초 안(계약 §6), 워치는 상태 기계가 본 마지막 워치 값이 30초 안(계약 §4.3)이고
     * 그때 착용 판정, 서버는 차가 주인(마지막 sync 성공 10초 안). 나이는 모두 응답 뒤 지난 내 단조 시간까지 더해 잰다.
     */
    fun links(nowMs: Long): Links {
        val s = snapshot
        val phone = peerSeenMs?.let { nowMs - it in 0..Ownership.CAR_FRESH_MS } == true
        val watchFresh = s.watchSeenAtMs?.let { !WatchFreshness.isStale(nowMs - it, params.watchStaleMs) } == true
        val watch = when {
            !watchFresh -> WatchLink.NO_SIGNAL
            s.worn -> WatchLink.WORN
            else -> WatchLink.NOT_WORN
        }
        val sendingNoSignal = watch == WatchLink.NO_SIGNAL &&
            watchNow(nowMs)?.let { (w, age) -> w.noSignal && !WatchFreshness.isStale(age, params.watchStaleMs) } == true
        return Links(phone, watch, carIsOwner(nowMs), sendingNoSignal)
    }

    /** 마지막 워치 값의 받는 길(연결 줄 워치 줄). */
    val watchVia get() = lastWatch?.via

    /** 마지막으로 받은 워치 값과 지금 나이(중계 `ageMs` + 받은 뒤 지난 내 단조 시간). 받은 적 없으면 null. */
    fun watchNow(nowMs: Long): Pair<Watch, Long>? =
        lastWatch?.let { it to WatchFreshness.viaRelay(watchAge(it), lastWatchAtMs, nowMs) }

    /**
     * `TEST_REQUEST`(늘 test=true, 부를 때마다 새 id)를 휴대폰 몫으로 쌓고 결과를 기다린다. 조건 확인은 부르는 쪽(CarHubScreen
     * `requestTest`)이 한다. 결과는 휴대폰 흐름 test 로만 본다(계약 §4.6 「차에서 테스트 요청」).
     */
    fun sendTestRequest(action: TestAction, nowMs: Long) {
        val p = if (action == TestAction.START) Payload.TestRequest.START else Payload.TestRequest.STOP
        outbox += Command(newId(), p.type, p.toJson(), ttlMs, test = true, from = Party.CAR, to = CommandTo.PHONE)
        testPending = TestPending(action, nowMs)
    }

    // ── 입력 ──

    /** 차량 신호를 새로 읽었다(주기마다). 차량 입력은 타이머 확인도 겸한다. */
    fun onReading(r: CarReading, nowMs: Long) {
        if (startMs == null) startMs = nowMs
        reading = r
        if (r.airbagDeployed == false) airbagFalseSeen = true
        feed(FlowInput.Vehicle(VehicleMapping.toFlow(vehicle()), nowMs), nowMs)
        feed(FlowInput.Link(lastOkMs, nowMs), nowMs)
        noteAirbag(r.airbagDeployed, nowMs)
        resolveHeldAirbag(nowMs)
    }

    /**
     * 3차 ①ⓐ — 흐름이 OFF 인 동안(다시 뜬 차가 휴대폰 설정을 받기 전 · 유예 중) 상태 기계는 에어백 전이를 사고로 열지 않고 소비한다.
     * 이 프로세스가 직접 본 false → true 전이만 [HELD_AIRBAG_MS] 동안 쥔다. 시작부터 true 인 값(앞선 사고 뒤 다시 뜬 차)은 전이가 아니라 쥐지 않는다(ⓑ).
     * 명시적 false 가 오면 쥔 것을 버린다(같은 사고가 끝났다).
     */
    private fun noteAirbag(airbag: Boolean?, nowMs: Long) {
        if (airbag == null) return
        if (airbag && lastAirbag == false && snapshot.state == FlowState.OFF) heldAirbagAtMs = nowMs
        if (!airbag) heldAirbagAtMs = null
        lastAirbag = airbag
    }

    /**
     * 쥔 에어백 전이를 정리한다 — 흐름이 OFF 를 벗어나면(켬 · 동의 · 착용이 확인돼 S0, 또는 휴대폰 사건을 받음) 사고를 연다.
     * 상태 기계는 「전이에서만 새 사고」라 차량 입력을 false → true 로 한 번 더 넣어 연다(공용 규칙 그대로, 이미 S5 면 열지 않음).
     * 신선한 휴대폰 설정이 꺼짐 · 미동의로 확인되거나 [HELD_AIRBAG_MS] 가 지나면 버린다(미착용으로 OFF 에 머물면 시간으로 버려짐 — 「미착용은 권한 밖」).
     */
    private fun resolveHeldAirbag(nowMs: Long) {
        val at = heldAirbagAtMs ?: return
        val s = snapshot
        when {
            nowMs - at > HELD_AIRBAG_MS -> heldAirbagAtMs = null
            s.state == FlowState.OFF -> if (settingsSeen && (!s.powerOn || !s.consents.all)) heldAirbagAtMs = null
            else -> {
                heldAirbagAtMs = null
                if (s.state == FlowState.S5) return
                val v = VehicleMapping.toFlow(vehicle())
                feed(FlowInput.Vehicle(v.copy(airbagDeployed = false), nowMs), nowMs)
                feed(FlowInput.Vehicle(v.copy(airbagDeployed = true), nowMs), nowMs)
            }
        }
    }

    fun tick(nowMs: Long) = feed(FlowInput.Tick(nowMs), nowMs)

    /** UX 제한 값이 바뀌었다(리스너) — 다음 주기를 기다리지 않고 바로 반영한다(버튼 · 검색 문이 곧바로 닫히게, G5). */
    fun onUx(ux: UxGate?) {
        reading = reading.copy(ux = ux)
    }

    /**
     * 차 화면 「괜찮아요」. [latest] 는 누른 순간에 다시 읽은 차량 신호(최신 UX 값 · 속도)다 — 직전 주기 값이 아니라 이것으로
     * 버튼이 보일 조건(UX 제한 없음 · 정지 화면 · 정지 중)을 다시 확인하고, 아니면 버린다.
     */
    fun onCarButton(nowMs: Long, latest: CarReading): Boolean {
        onReading(latest, nowMs)
        if (screen().button == null) return false
        feed(FlowInput.Response(ResponseSource.CAR_BUTTON, nowMs), nowMs)
        return true
    }

    // ── sync ──

    /**
     * 보낼 요청. flow 는 늘 싣는다 — `FlowStatus.forSync`(보정값이 있으면 중계 시각 SERVER, 없을 때만 내 단조 시각 LOCAL).
     * owner 는 지금 판정 그대로라 다시 붙은 첫 요청은 PHONE 이다(휴대폰이 주인을 이어 가고 차는 응답에서 넘겨받는다).
     */
    fun buildRequest(token: String, seq: Long, sentAtWallMs: Long, nowMs: Long): CarSyncRequest {
        val offset = clock.offsetAt(nowMs)
        val flow = FlowStatus.forSync(snapshot, owner(nowMs), offset)
        val s = snapshot
        val (next, smsCommands) = sms.buildRequest(nowMs, s.facts.eventId.takeIf { s.incidentActive })
        sms = next
        smsRequestNo[seq] = next.lastRequestNo
        while (smsRequestNo.size > SMS_REQUEST_KEEP) smsRequestNo.remove(smsRequestNo.keys.first())
        // 문자가 앞(사건 문자 → 취소), 그 뒤 그 밖의 지시 — 한 요청 32개 상한 안에서
        val commands = (smsCommands + outbox).take(MAX_COMMANDS_PER_SYNC)
        return CarSyncRequest(
            token = token, seq = seq, sentAt = sentAtWallMs, vehicle = vehicle(offset), flow = flow,
            commands = commands, acks = pendingAcks.toList(),
        )
    }

    /** sync 성공. [sentMs] · [receivedMs] 는 요청을 보낸 · 응답을 받은 단조 시각. */
    fun onSyncSuccess(req: CarSyncRequest, sentMs: Long, receivedMs: Long, resp: CarSyncResponse): SyncReport {
        val wasOwner = carIsOwner(receivedMs)
        if (startMs == null) startMs = receivedMs
        clock.add(sentMs, receivedMs, resp.serverTime)
        lastOkMs = receivedMs
        dropDelivered(req)

        feed(FlowInput.ClockSync(clock.offsetAt(receivedMs), receivedMs), receivedMs)
        feed(FlowInput.Link(lastOkMs, receivedMs), receivedMs)

        val peer = resp.peer
        val peerAge = peer?.let { resp.serverTime - it.receivedAt }
        val peerFresh = peerAge != null && peerAge in 0..Ownership.CAR_FRESH_MS
        if (peerAge != null && peerAge >= 0) peerSeenMs = receivedMs - peerAge
        val peerFlow = peer?.flow
        var adopted = false
        // 꺼짐(OFF) 흐름은 이어 갈 사건 · 타이머가 없다 — 넘겨받지 않고 설정(켬 · 동의)만 아래에서 받는다(내 정지 판정을 지우지 않게)
        if (peerFresh && peerFlow != null && peerFlow.owner == Owner.PHONE && peerFlow.state != FlowState.OFF &&
            (!wasOwner || Handover.carMayAccept(snapshot, peerFlow, peerFresh))
        ) {
            adopt(peerFlow, receivedMs)
            adopted = true
        }
        // 시작 유예(A-4) — 신선한 owner=PHONE 흐름을 받으면 끝(상한 6초). 휴대폰이 옛 차를 주인으로 보고 싣는 owner=CAR 설정 스냅숏(OFF)은
        // 답이 아니다 — 휴대폰은 다음 sync 에서 차 흐름의 사건 소실을 보고 owner=PHONE 으로 사건을 쥔다(app-phone 실측, carIncidentLost)
        if (peerFresh && peerFlow != null && peerFlow.owner == Owner.PHONE) holdDone = true
        // 켬 · 동의 · 테스트 모드는 신선한 휴대폰 흐름으로만 받는다(A-4 — 낡은 값으로 옛 설정을 켜지 않게)
        if (peerFresh && peerFlow != null) { applySettings(peerFlow.snapshot, receivedMs); phoneLocalTest = peerFlow.localTest; settingsSeen = true }
        applyTestMark(resp.watch, receivedMs, peerFresh)
        // 시작 요청은 흐름 test(워치 시나리오) 또는 휴대폰 단독 시험 표시로 풀린다 — 휴대폰이 답한 것이다(계약 §4.6 「차에서 테스트 요청」)
        testPending?.let { if ((it.action == TestAction.START) == snapshot.test || (it.action == TestAction.START && phoneLocalTest)) testPending = null }
        // peer 가 없으면 모름 → 숨김(계약 §4.1). peer 가 있으면 오래됐어도(휴대폰 끊김) 중계가 들고 있는 마지막 값을 쓴다
        carDisplay = peer?.carDisplay
        resp.watch?.let {
            lastWatch = it
            lastWatchAtMs = receivedMs
            onWatch(it, receivedMs, wasOwner)
        }
        // 휴대폰 화면 「괜찮아요」 — 정지 때만 유효(상태 기계가 지킨다, G5). peer 가 없으면 부르지 않는다(계약 §4.1)
        if (peer != null) phoneResponse.onValue(peer.responseAt, receivedMs)?.let { feed(it, receivedMs) }
        val remote = LinkedHashMap<String, RemoteOutcome>()
        for (c in resp.commands) remote[c.id] = onRemote(c)
        // 모르는 type · 형식이 틀린 지시 — 그 지시만 건너뛰고 「지원하지 않음」으로 ack(계약 §4.6)
        for (id in resp.skippedCommandIds) remote[id] = onSkipped(id)
        reconcileWatchAlert(resp.watch, receivedMs, wasOwner)
        // 응답을 다 처리했다(그로 나온 문자는 이미 보낼 함에) — 보류를 풀고, 휴대폰이 확인한 것을 지운다(A-1 · A-2)
        resolveHeldAirbag(receivedMs)
        smsRequestNo.remove(req.seq)?.let { sms = sms.onResponseHandled(it) }
        peerFlow?.smsDone?.let { lastSmsDone = it }
        sms = sms.onSmsDone(peerFlow?.smsDone)
        return SyncReport(adopted, clockVerified, remote)
    }

    /** sync 실패(연결 · 5xx). 10초 넘게 실패하면 휴대폰이 주인이라 보고 보낼 함을 비운다. */
    fun onSyncFailure(nowMs: Long) {
        feed(FlowInput.Link(lastOkMs, nowMs), nowMs)
        if (!carIsOwner(nowMs)) outbox.clear()
    }

    /**
     * 중계가 200 을 주었는데 본문을 못 읽었다(상대 빌드가 다른 형식, 리뷰 중 7). 중계는 요청을 받아들였으므로 **링크는 살아 있다** —
     * 마지막 성공 시각을 갱신해 주인을 내려놓지 않고, 실어 보낸 지시 · ack 는 전달된 것으로 지운다. 상태 입력(peer · watch · 지시)은 없다.
     */
    fun onSyncUnparsable(req: CarSyncRequest, nowMs: Long) {
        lastOkMs = nowMs
        dropDelivered(req)
        smsRequestNo.remove(req.seq)?.let { sms = sms.onResponseHandled(it) }
        feed(FlowInput.Link(lastOkMs, nowMs), nowMs)
    }

    /** 중계가 받아들인 요청에 실어 보낸 지시 · ack 를 보낼 함에서 지운다. */
    private fun dropDelivered(req: CarSyncRequest) {
        val sent = req.commands.map { it.id }.toSet()
        outbox.removeAll { it.id in sent }
        pendingAcks.removeAll(req.acks.toSet())
    }

    /**
     * 짝 폐기(중계 401 — 짝이 끝났거나 중계가 다시 떠 짝을 잊음). 옛 짝의 상대 흔적 — 워치 값 · 휴대폰을 본 시각 · 표시 설정 ·
     * 워치 · 휴대폰 응답 기준 · 휴대폰 테스트 모드 · 기다리던 테스트 요청 — 을 지워 화면에 옛 값이 남지 않게 한다.
     * 보낼 함(outbox) · 보낼 ack 도 버린다 — 옛 짝에게 낸 지시(알람 · 119 작성 · 테스트 요청)가 새 짝 휴대폰에서 실행되지 않게(리뷰 중2).
     * 실제 보호자 문자는 공용 보낼 함([GuardianSmsOutbox])에 그대로 남는다 — 다시 짝지은 뒤 휴대폰이 확인할 때까지 싣는다(A-2).
     * 주인 창은 바로 닫는다(마지막 성공 시각을 지움) — 옛 짝 창 10초 동안 새 지시(119 작성 · 알람)가 쌓이지 않게(코드 리뷰 11-09 차 하1).
     * 경보가 아니면(OFF · S0 · 응답 뒤 S1R · S2) 상태 기계를 꺼짐으로 새로 시작하고 「시계 미확인」을 되돌린다 — 켬 · 동의는 새 짝의 휴대폰이
     * 다시 보낸다(그때까지 「꺼짐 · 휴대폰 앱에서 켜세요」). 응답 뒤 「응답 확인됨」이 옛 짝 표시로 최대 10분 남지 않게(리뷰 11-08 하2).
     * 차량 판정(정지 디바운스 · 에어백 · 시계 보정)은 옛 짝과 무관하므로 이어 간다.
     * 경보 중(S1 · AP · S3 · S4 · S5)이면 상태 기계는 그대로 둔다 — 차 안 경보가 먼저다(운전자 안전).
     */
    fun onPairLost(nowMs: Long) {
        val s = snapshot
        outbox.clear()
        smsRequestNo.clear()
        lastSmsDone = null
        lastOkMs = null
        pendingAcks.clear()
        peerSeenMs = null
        lastWatch = null
        lastWatchAtMs = 0
        carDisplay = null
        phoneTest = false
        phoneLocalTest = false
        testPending = null
        watchResponse = WatchResponseTracker()
        alertSync = WatchAlertReconcile.State()
        phoneResponse = ResponseChange(ResponseSource.PHONE_SCREEN)
        if (s.state in ALARM_STATES) return
        clockVerified = true
        machine = FlowMachine.restore(
            FlowSnapshot(
                sinceMs = nowMs, stopped = s.stopped, rawStopped = s.rawStopped, stopPendingSinceMs = s.stopPendingSinceMs,
                nullSpeedCount = s.nullSpeedCount, airbag = s.airbag, serverOffsetMs = s.serverOffsetMs,
                facts = IncidentFacts(carStateMissing = s.facts.carStateMissing),
            ),
            params,
        )
        executor.clearShow()
    }

    /**
     * 휴대폰 연결 끊기(사람이 고름) — 짝 폐기와 같이 옛 짝의 흔적을 지우고, 보낼 함은 남김없이 비운다.
     * [unpairOk] 일 때만 부르므로 상태 기계는 꺼짐으로 새로 시작한다(새 짝의 휴대폰이 켜기를 보낼 때까지 꺼짐).
     */
    fun onUnpair(nowMs: Long) {
        onPairLost(nowMs)
        outbox.clear()
        sms = GuardianSmsOutbox()
    }

    // ── 안쪽 ──

    /**
     * 휴대폰이 주인으로 돌린 흐름을 내 단조 시계로 되돌려 이어 간다(계약 §6 넘김 시계 보정). 다른 기기 스냅숏이라 sameDevice=false.
     * 경보 상태면 그 단계 음성을 한 번 낸다 — 차가 이미 같은 사건 · 같은 음성을 냈으면 되풀이하지 않는다(11-02 남은 하).
     */
    private fun adopt(flow: FlowStatus, nowMs: Long) {
        val r = flow.restoreLocal(clock.offsetAt(nowMs), nowMs, sameDevice = false)
        // 에어백 사실은 휴대폰 스냅숏이 아니라 내가 읽은 값으로 — 휴대폰의 false 를 받아 와 내 true 읽기가 새 사고가 되지 않게(독립 검토 상)
        machine = FlowMachine.restore(r.snapshot.copy(airbag = lastAirbag ?: r.snapshot.airbag), params)
        clockVerified = r.clockVerified
        executor.clearShow()
        executor.ambient(r.snapshot.ambientMode)
        stageVoice(r.snapshot)?.let { dispatch(listOf(Directive.Speak(it, r.snapshot.test)), nowMs) }
    }

    /** 경보 상태의 단계 음성 — 상태 기계가 그 상태에 들어갈 때 내는 것과 같다(AP 는 경보 2 단계에서만 음성). 경보가 아니면 null. */
    private fun stageVoice(s: FlowSnapshot): Phrase? = when (s.state) {
        FlowState.S1 -> Phrase.VOICE_S1_PULL_OVER
        FlowState.AP -> Phrase.VOICE_AP_CHECK.takeIf { s.stage == 2 }
        FlowState.S3 -> Phrase.VOICE_S3_NO_RESPONSE
        FlowState.S4 -> Phrase.VOICE_S4_NO_RESPONSE
        FlowState.S5 -> Phrase.VOICE_S5_ACCIDENT
        else -> null
    }?.takeIf { (s.facts.eventId to it) != lastVoice }

    /** 기능 켬 · 동의 3종 · 테스트 모드는 휴대폰(등록 · 동의 화면)이 정한다 — 휴대폰 흐름 스냅숏에서 읽는다. */
    private fun applySettings(s: FlowSnapshot, nowMs: Long) {
        phoneTest = s.test
        if (s.consents != snapshot.consents) feed(FlowInput.ConsentsChanged(s.consents, nowMs), nowMs)
        if (s.powerOn != snapshot.powerOn) feed(FlowInput.Power(s.powerOn, nowMs), nowMs)
    }

    /**
     * 테스트 표시 = 휴대폰 테스트 모드 또는 신선한 워치 값의 test. 워치 판정보다 먼저 넣는다 — 상태 기계는 OFF · S0 에서만 켜므로
     * 진행 중 사건의 표시는 바뀌지 않고, 시뮬레이션 판정이 여는 사건은 처음부터 테스트다. 끄기는 테스트 사건의 응답 뒤(S1R · S2)에서도
     * 받아 S0 으로 돌아가며, 그때 남은 시뮬레이션 판정은 버려지고 바로 뒤에 넣는 실제 워치 값이 다음 판정이 된다(2026-11-07).
     */
    private fun applyTestMark(w: Watch?, nowMs: Long, peerFresh: Boolean) {
        if (!holdOver(nowMs)) return // 시작 유예 중에는 테스트 표시도 넣지 않는다(A-4)
        val watchFresh = w != null && !WatchFreshness.isStale(watchAge(w), params.watchStaleMs)
        val watchTest = watchFresh && w!!.test
        val want = phoneTest || watchTest
        if (want) testSeen = true
        if (want != snapshot.test) feed(FlowInput.TestMode(want, nowMs), nowMs)
        withdrawIfSessionEnded(nowMs, peerFresh && !phoneTest && watchFresh && !w!!.test)
    }

    /**
     * B-7 차 쪽 — 차가 주인이고 흐름이 테스트인데, 이번 테스트에서 표시를 본 뒤 신선한 휴대폰 흐름의 test=false 와 신선한 워치 값의
     * test=false 가 함께 [TEST_END_HOLD_MS] 넘게 이어지면 세션이 끝난 것으로 보고 거두기를 한 번 넣는다(app-phone 과 맞춤 —
     * 휴대폰의 C-1 세션 끝 창과 같다). 재시작 직후의 false · 시작 직후 첫 시뮬레이션 값 전의 false 는 [testSeen] 이 막는다.
     * 상태 기계는 테스트 흐름에만 듣고(S5 · 실제 사건은 무시) 거둔 뒤 S0 이다.
     */
    private fun withdrawIfSessionEnded(nowMs: Long, endSignal: Boolean) {
        if (!snapshot.test) { testSeen = false; testEndSinceMs = null; return }
        if (!endSignal || !testSeen || !carIsOwner(nowMs)) { testEndSinceMs = null; return }
        val since = testEndSinceMs ?: nowMs.also { testEndSinceMs = it }
        if (nowMs - since <= TEST_END_HOLD_MS) return
        testEndSinceMs = null
        testSeen = false
        feed(FlowInput.WithdrawTest(nowMs), nowMs)
    }

    /** 손목 판정 하나를 넣어도 되는지 — 유예가 끝났거나 나쁜 판정이 아님([Handover.carWristAllowed] 판정판). */
    private fun wristAllowed(verdict: WristVerdict, nowMs: Long): Boolean =
        holdOver(nowMs) || Handover.carWristAllowed(startMs ?: nowMs, nowMs, false, verdict)

    /** 시작 유예가 끝났는지(A-4) — 휴대폰 흐름을 보았거나 시작 뒤 6초([Handover.carWristAllowed])가 지남. */
    private fun holdOver(nowMs: Long): Boolean {
        if (!holdDone && startMs?.let { Handover.carWristAllowed(it, nowMs, false) } == true) holdDone = true
        return holdDone
    }


    /**
     * 계약 §4.3 주인 쪽 — 차가 주인이면 내 흐름과 워치가 실은 `alerting` 을 맞춘다(워치가 안 울리면 시작, 경보가 아닌데 울리면 그 사건 id 로 정지,
     * 각 10초에 한 번, 옛 워치는 다시 내지 않음). 나온 지시는 상태 기계 지시와 같은 길로 보낸다.
     */
    private fun reconcileWatchAlert(w: Watch?, nowMs: Long, wasOwner: Boolean) {
        // 이 응답 직전에도 주인이었을 때만 — 10초 넘게 끊겼다 다시 붙은 첫 응답에서는 맞추기를 하지 않고 상태를 새로 시작한다(독립 검토 중)
        if (!wasOwner) { alertSync = WatchAlertReconcile.State(); return }
        val fresh = w != null && !WatchFreshness.isStale(watchAge(w), params.watchStaleMs)
        val r = WatchAlertReconcile.step(snapshot, w, fresh, alertSync, nowMs, params)
        alertSync = r.state
        if (r.directives.isNotEmpty()) dispatch(r.directives, nowMs)
    }

    /** 휴대폰이 10초 안에 보였는가(중계가 휴대폰 상태를 받은 시각 기준, [links] 의 phone 과 같음). */
    private fun phoneSeen(nowMs: Long): Boolean = peerSeenMs?.let { nowMs - it in 0..Ownership.CAR_FRESH_MS } == true

    private fun watchAge(w: Watch) = w.ageMs ?: (WatchFreshness.STALE_MS + 1)

    /**
     * 응답 최상위 watch(중계가 고른 값). 경과는 중계가 잰 ageMs 를 쓰고, 없으면 낡은 값으로 본다(30초 규칙).
     * 워치 버튼 응답은 `ResponseChange` — 처음 값은 기준, 1초 넘게 커질 때만 응답(±1ms 흔들림 무시, 계약 §4.3).
     * 낡은 워치 값의 응답 변화는 기준만 옮기고 넣지 않는다.
     * `noSignal`(워치가 신호를 못 잡음) 값은 판정 입력으로 넣지 않는다 — 마지막으로 넣은 값에서 30초가 지나면 상태 기계의
     * 기존 「워치 신호 없음」 경로를 탄다(코드 리뷰 2026-11-02 상 1). 응답은 그대로 처리한다.
     */
    private fun onWatch(w: Watch, nowMs: Long, wasOwner: Boolean) {
        val age = watchAge(w)
        // 시작 유예 중에도 나쁘지 않은 판정(NORMAL · CHECK_WEAR)은 넣어 감시(S0)에 들어간다 — 나쁜 판정만 막는다(PM 결정 16)
        if (!w.noSignal && wristAllowed(w.verdict, nowMs)) feed(FlowInput.Wrist(w.verdict, nowMs, age), nowMs)
        // 받은 모든 워치 값으로 번호를 따라간다 — 나온 응답은 이 응답 직전에도 주인이었을 때만 넣는다(be-dev c614bd18).
        // sync 성공 안이라 지금은 늘 주인이다 — 10초 넘게 끊겼다 다시 붙은 첫 값은 끊긴 동안 다른 사건에 누른 번호일 수 있어 기준만 옮긴다(독립 검토 중)
        val response = watchResponse.onWatch(w, nowMs) ?: return
        if (!WatchFreshness.isStale(age, params.watchStaleMs) && wasOwner) feed(response, nowMs)
    }

    private fun onRemote(c: Command): RemoteOutcome {
        if (!acknowledge(c.id)) return RemoteOutcome.DUPLICATE
        if (c.type == CommandType.FLOW_SNAPSHOT) return RemoteOutcome.SNAPSHOT_NOTED
        executor.parseRemote(c) ?: return RemoteOutcome.REJECTED
        return RemoteOutcome.IGNORED_CAR_OWNER
    }

    private fun onSkipped(id: String): RemoteOutcome =
        if (acknowledge(id)) RemoteOutcome.REJECTED else RemoteOutcome.DUPLICATE

    /** 다음 sync 의 acks 에 넣고, 처음 본 id 면 true(중계는 ack 전까지 다시 보낸다). */
    private fun acknowledge(id: String): Boolean {
        pendingAcks += id
        if (!handled.add(id)) return false
        while (handled.size > HANDLED_KEEP) handled.remove(handled.first())
        return true
    }

    private fun feed(input: FlowInput, nowMs: Long) = dispatch(machine.handle(input), nowMs)

    private fun dispatch(directives: List<Directive>, nowMs: Long) {
        val owner = carIsOwner(nowMs)
        val offset = clock.offsetAt(nowMs)
        val smsCommands = ArrayList<Command>()
        for (raw in directives) {
            var d = raw
            if (d is Directive.Speak) {
                lastVoice = snapshot.facts.eventId to d.phrase // 원래 문구로 기록 — 넘겨받을 때 같은 단계를 되풀이하지 않게
                if (!(owner && phoneSeen(nowMs))) SMS_DELAYED[d.phrase]?.let { d = Directive.Speak(it, raw.test) }
            }
            for (c in DirectiveCommands.toCommands(d, Party.CAR, newId, ttlMs, offset)) {
                when {
                    c.resolvedTo == CommandTo.CAR -> executor.parseRemote(c)?.let { executor.execute(it, snapshot.state, touchOk()) }
                    // 실제 보호자 문자는 주인이 아니어도 보낼 함에 남긴다(A-2). 테스트 문자는 지금처럼 주인일 때만
                    c.type == CommandType.SEND_GUARDIAN_SMS && !c.test -> smsCommands += c
                    owner -> outbox += c
                }
            }
        }
        if (smsCommands.isNotEmpty()) sms = sms.add(smsCommands, nowMs)
        while (outbox.size > OUTBOX_KEEP) outbox.removeAt(0)
    }

    companion object {
        /** 지시 ttl — 오래된 경보가 늦게 실행되지 않게 짧게 둔다 [추정]. */
        const val COMMAND_TTL_MS = 30_000L
        /** 계약 §4.1 — 한 번에 최대 32개. */
        const val MAX_COMMANDS_PER_SYNC = 32
        const val OUTBOX_KEEP = 128
        const val HANDLED_KEEP = 512
        /** 설정을 받기 전에 본 에어백 전이를 쥐는 기간(시작 유예 상한과 같음). */
        const val HELD_AIRBAG_MS = Handover.CAR_START_GRACE_MS
        /** 요청 seq → 문자 요청 번호 표의 크기 상한(응답이 오지 않은 요청이 쌓이지 않게). */
        private const val SMS_REQUEST_KEEP = 64
        /** 테스트 세션 끝으로 볼 「테스트 아님」 지속 시간(휴대폰 C-1 과 같음). */
        const val TEST_END_HOLD_MS = 10_000L

        /** 문자 전달을 확인할 수 없을 때(주인 아님 · 휴대폰이 10초 넘게 안 보임) 바꿔 말할 음성(A-5). */
        private val SMS_DELAYED = mapOf(
            Phrase.VOICE_S3_NO_RESPONSE to Phrase.VOICE_S3_SMS_DELAYED,
            Phrase.VOICE_S4_NO_RESPONSE to Phrase.VOICE_S4_SMS_DELAYED,
            Phrase.VOICE_S5_ACCIDENT to Phrase.VOICE_S5_SMS_DELAYED,
        )

        /** 경보 상태 — 짝 폐기 때 상태 기계를 그대로 두는 상태(CarScreen.ALARM_STATES 와 같다, :integration 이 CarScreen 을 들이지 않아 따로 둔다). */
        private val ALARM_STATES = setOf(FlowState.S1, FlowState.AP, FlowState.S3, FlowState.S4, FlowState.S5)
    }
}
