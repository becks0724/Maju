// 휴대폰 앱의 두뇌 — sync 요청 · 응답 처리, 판정 주인 선택과 넘김(계약 §6), 휴대폰이 주인일 때 상태 기계, 테스트 모드를 묶는다(안드로이드 의존 없음)
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowParams
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Handover
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.Ownership
import com.example.guardian.shared.flow.ResponseSource
import com.example.guardian.shared.flow.SpeedSource
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.flow.WatchFreshness
import com.example.guardian.shared.flow.shiftedBy
import com.example.guardian.shared.protocol.CarDisplay
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.ClockOffset
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.DirectiveCommands
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.GuardianSmsDedup
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.ResponseChange
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.report.IncidentComposer
import com.example.guardian.shared.report.ReportFailure
import com.example.guardian.shared.report.ReportResult
import com.example.guardian.shared.report.Reports
import com.example.guardian.shared.protocol.PhoneSyncRequest
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.SeqGenerator
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.PhoneSmsGuard
import com.example.guardian.shared.protocol.SmsDone
import com.example.guardian.shared.protocol.CarSmsReconcile
import com.example.guardian.shared.protocol.WatchAlertReconcile
import com.example.guardian.shared.protocol.WatchResponseTracker
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.protocol.SmsDoneState
import com.example.guardian.shared.protocol.TestAction
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchReach
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.report.Consents
import kotlin.math.abs

enum class TestMode { LOCAL, WATCH }

enum class TestStart { LOCAL, WATCH, NEED_SETUP, NOT_STOPPED, BUSY }

/** 진행 중인 테스트. LOCAL 은 휴대폰 안 시뮬레이션 + 별도 상태 기계, WATCH 는 워치에 TEST_SCENARIO 를 보낸 상태. */
class TestSession(
    val mode: TestMode,
    val scenario: Scenario,
    val startMono: Long,
    /** 테스트는 정차 중에만 시작하므로 휴대폰 안 시뮬레이션의 차 상태는 늘 정차다(설계 spec P15). */
    val stoppedSim: Boolean = true,
    val run: LocalTestRun? = null,
    val machine: FlowMachine? = null,
    /** 차 화면의 테스트 요청으로 시작했는가 — 표시만(P16 「차 화면에서 시작함」), 판단에 쓰지 않는다. */
    val fromCar: Boolean = false,
) {
    /** LOCAL — 사람이 테스트 경보에 응답했다(시험 흐름이 S1R · S2 에 들어선 적이 있다). 그 뒤 시뮬레이션 신호는 정상으로 낸다. */
    var responded: Boolean = false

    /** WATCH — 신선한 워치 값이 test=true 인 것을 마지막으로 본 단조 시각(C-1). 한 번도 못 봤으면 null. */
    var lastSimSeenAt: Long? = null
}

/** 화면에 보일 엔진 상태. */
data class EngineStatus(
    val owner: Owner?,
    val carFresh: Boolean,
    val flowState: FlowState?,
    val stopped: Boolean,
    val watchVerdict: WristVerdict?,
    val watchStale: Boolean,
    val clockUnverified: Boolean,
    val canRespond: Boolean,
    val has119: Boolean,
    val test: TestMode?,
    val testScenario: Scenario?,
    val testFlowState: FlowState?,
    /** 경보 단계(1 진동 · 2 소리, 0 없음) — 지금 보이는 흐름(LOCAL 시험이면 시험 흐름) 기준. */
    val stage: Int,
    /** 지금 보이는 흐름이 테스트 표시인가(테스트 띠). */
    val flowTest: Boolean,
    /** 첫 경보부터 지난 초(P10 「응답 없음 23초」 · P12 머리). 사건이 없으면 null. */
    val noResponseSec: Int?,
    /** 정차 시각(표시용 벽시계 ms). 모르면 null. */
    val stoppedSinceWall: Long?,
    /** 화면에 보일 흐름 상태 — LOCAL 시험이면 시험 흐름, 아니면 [flowState]. */
    val shownState: FlowState?,
    /** 차가 주인일 때 휴대폰 「괜찮아요」를 보냈고 아직 ALERT_STOP 이 오지 않음(「응답 보냄 · 확인 중」). */
    val responsePending: Boolean = false,
    /** 워치가 중계에 직접 붙어 있어 워치에 지시를 보낼 수 있다(계약 §4.3 지시 가능 규칙). 표시만 — 켜기를 막지 않는다. */
    val watchDirect: Boolean = false,
    /** 화면 잠금 · 테스트 시작용 이동 판단. 잠금은 [Motion.DRIVING] 일 때만. 경보 흐름은 [stopped] 를 쓴다. */
    val motion: Motion = if (stopped) Motion.STOPPED else Motion.DRIVING,
    /** 「모름」 동안 「정차 중입니다」를 눌러 둔 유효한 확인이 있다. */
    val stopConfirmed: Boolean = false,
    /** 지금 테스트가 차 화면에서 시작됐다(표시만). */
    val testFromCar: Boolean = false,
    /** 지금 보이는 흐름의 사건 id — 경보 화면이 이번 사건의 문자 기록만 고른다(B1 결함 6). 없으면 null. */
    val eventId: String? = null,
    /**
     * 실제 알람이 울리는데(받은 ALERT_START 뒤 멈춤 없음) 알려진 주인 상태가 경보가 아니다 — 이어받기에서 차의 경보 끝을 못 받은 채 남은 알람.
     * 경보 화면은 닫지 않고 끌 수단(정차 중 「괜찮아요」 · 워치 버튼)을 남긴다(사용자 결정 2026-11-08).
     */
    val strandedAlarm: Boolean = false,
    /** 남은 알람을 사람이 「괜찮아요」 · 워치 버튼으로 껐다(다음 실제 알람 전까지) — 경보 화면이 응답 결과를 보인다. */
    val alarmAnswered: Boolean = false,
    /**
     * 워치에 경보가 가지 않는다(C-7 · 앱 흐름 상1 최소안) — 켜짐 · Data Layer 워치 값 신선 · 중계 주소 있음 · 휴대폰 sync 성공 중 ·
     * 워치 직접 연결 없음이 [PhoneEngine.WARN_AFTER_MS] 이어졌다. 워치 값은 휴대폰으로 오지만 경보 · 응답 지시는 중계 직접 경로로만 닿는다.
     */
    val watchAlertUnreachable: Boolean = false,
    /** 중계 주소가 있는데 sync 가 [PhoneEngine.WARN_AFTER_MS] 넘게 실패 중이다(하9). 다시 닿으면 바로 거짓. */
    val serverUnreachable: Boolean = false,
    /** 지금 사건에 보내지 못해 다시 보낼 보호자 문자가 있다 — 정지일 때 경보 화면이 「보호자 문자 다시 보내기」를 보인다(위임 결정 3). */
    val canResendSms: Boolean = false,
)

/**
 * 모든 시각 인자는 단조 시계(`SystemClock.elapsedRealtime`)다. [wallNow] 는 sentAt · 문자 표시 시각에만 쓴다.
 *
 * 판정 주인(계약 §6) — 응답의 `serverTime − peer.receivedAt`(둘 다 중계 시계)로 차의 마지막 sync 를 로컬 단조 시각으로 옮겨 두고,
 * 지금과 10초 넘게 벌어지거나 peer 가 없으면 휴대폰이 주인이다(중계가 끊겨 응답이 없어도 시간이 지나면 휴대폰이 주인이 된다).
 * 넘겨받을 때는 차의 마지막 flow(또는 FLOW_SNAPSHOT)를 `restoreLocal` 로 되돌려 이어 가고, 넘길 때는 중계 시각 스냅숏을 FLOW_SNAPSHOT 으로 보낸다.
 */
class PhoneEngine(
    private val store: PhoneStore,
    effects: PhoneEffects,
    private val newId: () -> String,
    private val wallNow: () -> Long,
    seqStartEpochMs: Long,
    private val startMono: Long,
    /** 중계 주소와 토큰이 있어 sync 를 하는가. 없으면 처음부터 휴대폰이 주인. */
    private val relayActive: () -> Boolean,
    private val params: FlowParams = FlowParams(),
) {
    /**
     * 실제 알람이 울리는 중인가 — 테스트가 아닌 알람 켜기(받은 ALERT_START · 저장 복원) 뒤 멈춤(ALERT_STOP · 테스트 끝 · 사고 처리)이 없었다.
     * 엔진과 실행기의 알람 켜기 · 멈춤은 모두 아래 [effects] 를 거치므로 여기서 기록한다. 소리 줄이기(mute)는 경보 끝이 아니라 그대로 둔다.
     */
    private var realAlarmOn = false

    /** 남은 알람([strandedAlarm])을 사람이 껐다 — 다음 실제 알람 켜기 전까지 경보 화면이 응답 결과를 보인다. */
    private var alarmAnswered = false

    private val effects: PhoneEffects = trackAlarm(effects)

    private fun trackAlarm(inner: PhoneEffects): PhoneEffects = object : PhoneEffects by inner {
        override fun startAlarm(stage: String, level: Int, test: Boolean) {
            if (!test) { realAlarmOn = true; alarmAnswered = false }
            inner.startAlarm(stage, level, test)
        }

        override fun stopAlarm() {
            realAlarmOn = false
            inner.stopAlarm()
        }

        // 사람이 소리를 줄인 사건은 저장한다 — 다시 뜬 휴대폰이 그 사건 알람을 다시 켜지 않게(C-5)
        override fun muteAlarm() {
            shownEventId()?.let { store.mutedEventId = it }
            inner.muteAlarm()
        }
    }

    /** 본문 조립(:shared IncidentComposer) — 지시의 사건 사실 + 이 휴대폰의 등록 정보 · 차 상태 · 워치 · 위치. */
    private val bodies = object : BodySource {
        override fun guardianBody(p: Payload.SendGuardianSms, from: Party): ReportResult {
            val c = phoneContext(p, from) ?: return ReportResult.Fail(profileFailure())
            return IncidentComposer.guardianSms(p, from, clock.offsetAt(lastNow), c)
        }

        override fun compose119Body(p: Payload.Open119Compose, from: Party): ReportResult {
            val c = phoneContext(p.asEventProbe(), from) ?: return ReportResult.Fail(profileFailure())
            return IncidentComposer.compose119(p, from, clock.offsetAt(lastNow), c)
        }

        override fun ultra119(p: Payload.Open119Compose, from: Party): String? {
            val c = phoneContext(p.asEventProbe(), from) ?: return null
            val facts = p.facts.toLocal(p.eventId, clock.offsetAt(lastNow), lastNow, sameDevice = from == Party.PHONE).facts
            return (Reports.ultraShort119(IncidentComposer.input(facts, p.test, c)) as? ReportResult.Ok)?.text
        }

        // 본문은 머리 · 끈 시각 · 꼬리뿐 — 위치 · 차 · 워치 값을 넘기지 않는다(G2 최소)
        override fun guardianPowerOff(offAtWallMs: Long): ReportResult {
            val reg = store.registration
            val profile = reg.profile() ?: return ReportResult.Fail(profileFailure())
            val c = IncidentComposer.PhoneContext(profile, reg.consents, null, null, null, lastNow, wallNow())
            return Reports.guardianPowerOff(IncidentComposer.input(IncidentFacts(), test = false, c), offAtWallMs)
        }
    }

    // 초기화식에서는 생성자 인자 effects 가 속성을 가린다 — 알람을 기록하는 쪽(this.effects)을 넘긴다
    val executor = CommandExecutor(store, this.effects, bodies)

    /** 본문 조립에 쓰는 「지금」(단조) — 공개 진입점마다 갱신한다. */
    private var lastNow = startMono

    /**
     * 차가 주인일 때 정차 중 휴대폰 화면 「괜찮아요」를 누른 순간의 벽시계 — 누를 때 **한 번만** 잡고 sync 의 `responseAt` 에 그대로 싣는다
     * (sync 마다 다시 계산하지 않는다 — 워치에서 같은 결함, 계약 §4.1). 다시 누르면 새 값.
     */
    var phoneResponseAtWall: Long? = null
        private set

    /** 휴대폰 화면 응답을 보냈고 아직 ALERT_STOP 이 오지 않음 — 「응답 보냄 · 확인 중」. */
    private var responsePending = false
    /** 워치 응답 — 경로(중계 경유 · Data Layer)마다 공통 규칙 하나씩. 같은 누름이 두 경로로 오면 [seenResponseAt] 으로 한 번만 넣는다. */
    private class ResponsePath {
        val change = ResponseChange(ResponseSource.WATCH_BUTTON)
        var started = false
        var last: Long? = null
    }
    /** 워치 응답 번호 추적(B-5) — 짝이 바뀌면 새로 만든다. */
    private var responseTracker = WatchResponseTracker()
    private var seqSeen = false
    /** 워치 경보 맞추기 상태(C-2) — 마지막 다시 냄 시각 · 모르는 사건을 지켜본 시각. */
    private var watchAlertState = WatchAlertReconcile.State()

    /** 짝이 끝나거나 새로 맺어졌다 — 워치 응답 번호 기준을 새로 잡는다(옛 워치의 번호로 응답하지 않게). */
    private fun resetWatchResponse() { responseTracker = WatchResponseTracker(); seqSeen = false }
    private val relayResponse = ResponsePath()
    private val dlResponse = ResponsePath()
    private val seenResponseAt = ArrayDeque<Long>()
    private var pendingWatchResponse: FlowInput.Response? = null
    private val incidentSeenAt = HashMap<String, Long>()
    private val seq = SeqGenerator(seqStartEpochMs)
    private val clock = ClockOffset()
    private var firstSyncDone = false

    // 차
    private var lastCar: CarPeer? = null
    private var carFreshAtLocal: Long? = null
    private var candidate: FlowStatus? = null
    private var candidateAtLocal: Long? = null

    /**
     * 마지막으로 본 차의 실제 사건 흐름(owner CAR · 테스트 아님 · OFF · S0 아님)과 그 흐름이 차에서 나온 단조 시각(A-4) — [candidate] 와 따로 쥔다.
     * 재시작한 차의 첫 흐름(OFF · id 없음)이 [candidate] 를 덮어도 이 사건을 넘겨받을 수 있게.
     */
    private var heldCar: FlowStatus? = null
    private var heldCarAtLocal = 0L

    /** 경보 상태이던 차 흐름의 사건 id 가 사라졌다(차 재시작) — 차가 신선해도 주인으로 보지 않고 [heldCar] 를 넘겨받는다. */
    private var carLostIncident = false

    // 워치 — 중계가 고른 값과 Data Layer 로 받은 값
    private var relayWatch: Watch? = null
    private var relayWatchAtLocal = 0L
    private var dlWatch: Watch? = null
    private var dlWatchAtLocal = 0L
    private var dlWatchUnsent = false
    /** 지금 보내는 요청에 실은 Data Layer 값 — 응답이 오면 그 값이 아직 마지막 값일 때만 「보냄」으로 친다. */
    private var inFlightWatch: Watch? = null
    /** 워치 직접 경로 나이(응답의 watchDirectAgeMs, 중계 시계)와 그 응답을 받은 단조 시각 — 워치에 지시를 보낼 수 있는지(§4.3). */
    private var watchDirectAgeMs: Long? = null
    private var watchDirectAtLocal = 0L

    // 휴대폰 위치
    private var phoneLoc: Loc? = null
    private var phoneLocAtLocal = 0L
    /** 화면 잠금용 이동 판단에만 쓰는 GPS 속도(km/h) — 속도 정확도로 거른 값([GpsSpeed]). 경보 흐름 · 중계는 [phoneLoc] 의 거르기 전 속도를 쓴다. */
    private var phoneMotionKmh: Double? = null

    // 판정 주인
    var owner: Owner? = null
        private set
    private var machine: FlowMachine? = null
    private var fedConsents: Consents? = null
    private var fedPower: Boolean? = null
    /** 상태 기계에 마지막으로 넣은 시각(단조) — 끄기([shutdown])가 이 시각으로 꺼짐을 넣는다. */
    private var machineAt = startMono

    /**
     * 앞선 프로세스가 주인일 때 저장한 사건 스냅숏(벽시계 → 내 단조 시각으로 되돌림, [SavedFlow]) — 처음 휴대폰이 주인이 될 때 이어 간다.
     * [RESTORE_MAX_AGE_MS] 가 지났거나 사건이 아니면(OFF · S0) 읽을 때 지우고(G2), 차가 먼저 주인이 되면 차의 흐름이 맞으므로 버린다.
     */
    private var restored: FlowSnapshot? = null

    /** [restored] 를 저장한 순간을 내 단조 시각으로 옮긴 값 — 차 후보 흐름과 어느 쪽이 더 최근인지 가린다. */
    private var restoredAtLocal = 0L

    /** 저장된 스냅숏이 있는가(지울 때만 한 번 쓰게). */
    private var savedActive = false

    /** 저장에서 복원한 뒤 첫 GPS 위치를 기다리는 동안(단조 시각 한계) 차량 입력을 넣지 않는다 — 속도 모름이 복원한 정차를 주행으로 뒤집지 않게. */
    private var vehicleGraceUntil: Long? = null

    /**
     * 차에게서 넘겨받은 흐름이 정차였다 — GPS 가 실제 움직임([VehicleState.GPS_STOP_KMH] 초과)을 보일 때까지 속도 모름을 차량 입력으로 넣지 않는다.
     * 시동을 끈 뒤 차 앱이 사라지고 주차장에서 GPS 가 비면 「속도 모름 = 주행」으로 5초 뒤 바뀌어 「괜찮아요」 · 119 작성 화면이 막히던 것(app-qa 2차 조건).
     */
    private var holdStopUntilMove = false

    init {
        store.savedFlow?.let { saved ->
            val wall = wallNow()
            val age = wall - saved.savedAtWallMs
            if (age in 0..RESTORE_MAX_AGE_MS && incident(saved.snapshot.state)) {
                restored = saved.snapshot.shiftedBy(startMono - wall)
                restoredAtLocal = startMono - age
                savedActive = true
            } else {
                store.savedFlow = null
            }
        }
    }

    // 보낼 것
    private class Out(val cmd: Command, val atLocal: Long)
    private val outbox = ArrayList<Out>()
    private val acks = LinkedHashSet<String>()
    private var inFlightCmds: List<Out> = emptyList()
    private var inFlightAcks: List<String> = emptyList()
    private class Retry(val cmd: Command, val untilLocal: Long)
    private val localRetry = ArrayList<Retry>()

    var test: TestSession? = null
        private set

    /** 「모름」 상태에서 「정차 중입니다」를 누른 단조 시각. 주행 · 정차 확정 · 10분이면 지운다. */
    private var stopConfirmAt: Long? = null

    /** 마지막 실제 주행 판단(차 또는 GPS)의 단조 시각 — 주행 유지용. 정차 판단이면 지운다. LOCAL 시험의 흉내 값은 쓰지 않는다. */
    private var lastDrivingAt: Long? = null

    // ── 입력 ──

    /**
     * 휴대폰 위치. [loc] 의 속도(거르기 전)는 경보 흐름의 정지 판정 · 문자 본문에, [motionSpeedKmh](정확도로 거른 속도)는
     * 화면 잠금용 이동 판단에만 쓴다(사용자 결정 2026-11-01 — 경보 흐름의 정차 판단은 바꾸지 않는다). 따로 주지 않으면 같은 값.
     */
    fun onLocation(loc: Loc, atMono: Long, motionSpeedKmh: Double? = loc.speedKmh) {
        if (!loc.isFinite) return
        phoneLoc = loc
        phoneLocAtLocal = atMono
        phoneMotionKmh = motionSpeedKmh
    }

    /** 건강 관련 동의가 없으면 워치 값을 쓰지도 보내지도 않는다(G2). */
    fun onDataLayerWatch(w: Watch, atMono: Long) {
        if (!store.registration.consents.health) return
        dlWatch = w
        dlWatchAtLocal = atMono
        dlWatchUnsent = true
    }

    // ── sync ──

    fun hasOutgoing(): Boolean = outbox.isNotEmpty() || acks.isNotEmpty()

    fun buildSync(token: String, nowMono: Long): PhoneSyncRequest {
        val consents = store.registration.consents
        outbox.removeAll { nowMono - it.atLocal > it.cmd.ttlMs }
        // 같은 Data Layer 값을 거듭 보내면 중계가 새 값으로 여겨 신선도가 틀어진다 — 새로 받은 것만 싣고, 중계가 받았다고 답한 뒤에
        // 「보냄」으로 친다(실패하면 다음 sync 에 다시, 11-02 남은 하). 오래 묵은 값은 다시 싣지 않는다
        val watch = if (consents.health && dlWatchUnsent && nowMono - dlWatchAtLocal <= DL_RESEND_MAX_MS) dlWatch else null
        inFlightWatch = watch
        // 휴대폰 위치는 중계로 보내지 않는다 — 문자 본문에만 로컬로 쓴다([phoneContext], 감사 중4 A 안 · 계약 §8)
        val flow = syncFlow(nowMono)
        inFlightCmds = outbox.take(MAX_COMMANDS)
        inFlightAcks = acks.toList()
        return PhoneSyncRequest(
            token = token, seq = seq.next(), sentAt = wallNow(), watch = watch, flow = flow,
            commands = inFlightCmds.map { it.cmd }, acks = inFlightAcks, responseAt = phoneResponseAtWall,
            // 차 화면 표시 설정 — 매 sync 에 싣는다(계약 §4.1). 차는 이 값을 모르면 숨긴다. 차 화면 표시 동의 전(판 1)이면 스위치와 따로 늘 숨김
            carDisplay = CarDisplay(hideHr = store.registration.hideHrForCar),
            // 지금 저장된 건강 관련 동의 — false 면 중계가 워치 직접 값을 저장하지 않고 차에 내리지 않는다(감사 하4 · 계약 §8)
            healthConsent = consents.health,
        )
    }

    /**
     * sync 에 늘 싣는 flow — 꺼짐이어도 싣는다(차가 이것으로 켬 · 동의 3종 · 테스트를 안다). 시계는 `FlowStatus.forSync` 규칙 하나
     * (보정값이 있으면 중계 시각, 없으면 내 단조 시각). 휴대폰이 주인이면 상태 기계 스냅숏(owner PHONE),
     * 차가 주인이거나 아직 모르면 설정만 담은 OFF 스냅숏(owner CAR — 휴대폰이 주인인 것처럼 읽히지 않게).
     */
    private fun syncFlow(now: Long): FlowStatus {
        val reg = store.registration
        val off = clock.offsetAt(now)
        // 휴대폰 안 시험(LOCAL)은 표시 전용 선택 필드 flow.localTest 로만 알린다(계약 §4.5) — 차는 이것으로 테스트 요청을 풀고 안내만 보인다
        val localTest = test?.mode == TestMode.LOCAL
        val m = machine?.takeIf { owner == Owner.PHONE }
        // 꺼짐 · 동의가 하나라도 없으면 주인이어도 설정만 담은 OFF — 건강 판정 · 사건 사실 · 사건 id 를 싣지 않는다(app-qa 하2, G2)
        // 테스트는 워치 시뮬레이션(WATCH)일 때만 — 휴대폰 안 시험(LOCAL)은 휴대폰 밖으로 아무 신호도 내지 않으므로 차가 실제 워치 값을 테스트로 다루면 안 된다(2026-11-07)
        val snap = if (m != null && reg.powerOn && reg.consents.all) m.snapshot()
        else FlowSnapshot(state = FlowState.OFF, sinceMs = now, test = watchSimulation(now), powerOn = reg.powerOn, consents = reg.consents)
        // 문자 결과(A-1) — 주인이든 아니든 싣는다. 차는 이것으로 다시 싣기를 멈춘다(ack 는 확인이 아니다)
        return FlowStatus.forSync(snap, if (m != null) Owner.PHONE else Owner.CAR, off).copy(localTest = localTest, smsDone = smsDone(now))
    }

    /**
     * flow.smsDone(A-1) — 휴대폰 문자 기록(최근 24시간)에서 지금 짝에서 생긴 실제 사건 문자의 결과. 결과를 기다리거나 다시 보낼 문자는 PENDING,
     * 보냄 · 확인 못 함은 SENT, 보내지 않음 · 최종 실패는 NOT_SENT(:shared SmsDone). 진행 중인 사건(내 상태 기계 · 차 흐름)의 것은 미해결로,
     * 그 밖은 기록 시각을 끝난 시각으로 보고 10분 안만 싣는다([SmsDone.select]). 테스트 문자는 싣지 않는다.
     */
    private fun smsDone(now: Long): List<SmsDone> {
        val wall = wallNow()
        val joinedWall = joinedAtServer()?.let { j -> clock.offsetAt(now)?.let { off -> j - off - now + wall } } ?: 0L
        val pending = executor.pendingKeys()
        val ongoing = setOfNotNull(
            machine?.takeIf { owner == Owner.PHONE }?.snapshot()?.takeIf { incident(it.state) }?.facts?.eventId,
            lastCar?.flow?.takeIf { incident(it.state) }?.snapshot?.facts?.eventId,
        )
        val entries = GuardianSmsDedup.prune(store.smsRecords, wall).map { r ->
            val d = SmsDone.of(r).let { if ("${r.kind.name}:${r.eventId}" in pending) it.copy(done = SmsDoneState.PENDING) else it }
            SmsDone.Entry(d, r.test, r.recordedAtWallMs, if (r.eventId in ongoing) null else r.recordedAtWallMs)
        }
        return SmsDone.select(entries, wall, joinedWall)
    }

    /** 이번 짝 참여 시각(중계 시각). 아직 모르면 null. */
    private fun joinedAtServer(): Long? = store.pairJoinedAtServerMs?.takeIf { it != PhoneStore.JOIN_PENDING }

    /** 차 흐름에서 처음 본 사건 id → 그때의 중계 시각 — 「참여 전에 본 사건」(A-2 방어의 seenEventIds). */
    private val flowSeenAtServer = LinkedHashMap<String, Long>()

    /** 차 흐름이 중계 시계(SERVER)로 말한 사건 시작 시각(사건 id → 첫 경보 · 사고 시각) — 시각을 읽을 수 없는 id 의 참여 전 판정(sec-audit). */
    private val carFlowStartServer = LinkedHashMap<String, Long>()

    /** 차에서 넘겨받은 사건 id — 저장해 두고 되살린 뒤에도 원격 문자로 본다(PM 결정 19). */
    private val carOrigin = LinkedHashSet<String>(store.carOriginEventIds)

    fun isCarOriginEvent(id: String): Boolean = id in carOrigin

    /** 넘겨받은 기계가 휴대폰에서 새로 만든 사건 id(차가 지은 넘겨받은 id 는 아님) — 저장해 두고 되살린 뒤에도 쓴다. */
    private val adoptedOwn = LinkedHashSet<String>(store.adoptedOwnEventIds)

    fun isAdoptedOwnEvent(id: String): Boolean = id in adoptedOwn

    /** 지금 넘겨받은 기계가 차에서 받아 이어 가는 사건 id — 이것과 다른 id 는 휴대폰이 새로 만든 것이다. */
    private var adoptedFromId: String? = null

    private fun markAdoptedOwn(id: String) {
        if (!adoptedOwn.add(id)) return
        while (adoptedOwn.size > CAR_ORIGIN_MAX) adoptedOwn.remove(adoptedOwn.first())
        store.adoptedOwnEventIds = adoptedOwn.toList()
    }

    /** 지금 상태 기계가 차에서 넘겨받은 것인가 — 사건 없는 쉬는 상태(OFF · S0)에 닿거나 넘겨줄 때까지. */
    private var adoptedMachine = false

    /**
     * 넘겨받을 차 스냅숏 — 경보 · 사고 중이 아니면(응답 뒤 S1R · S2, 쉬는 S0 · OFF) 차가 실어 온 판정 · 약해진 시각을 비운다(sec-audit p7).
     * 휴대폰은 자기 워치 값으로만 새 사건을 연다 — 차 토큰을 가진 쪽이 「응답 뒤 나쁜 판정 지속」을 심어 새 사건을 열게 하지 못하게.
     */
    private fun adoptedSnapshot(s: FlowSnapshot): FlowSnapshot =
        if (s.state in ALARM_STATES || s.state == FlowState.S5) s else s.copy(verdict = WristVerdict.NORMAL, weakSinceMs = null)

    private fun markCarOrigin(id: String) {
        if (!carOrigin.add(id)) return
        while (carOrigin.size > CAR_ORIGIN_MAX) carOrigin.remove(carOrigin.first())
        store.carOriginEventIds = carOrigin.toList()
    }

    /** 원격 사건 문자 방어(A-2) — 내 문자 기록의 사건 id 와 이번 참여 전에 본 흐름의 사건 id 는 본 사건이다. */
    private fun smsGuard(now: Long): (GuardianSmsWireKind, String, Long?) -> PhoneSmsGuard.Verdict {
        val joined = joinedAtServer()
        val nowServer = clock.offsetAt(now)?.let { now + it }
        // 시각을 읽을 수 없는 id(event-L · accident-L, 차가 보정 전에 연 사건)는 지금 신선한 차 흐름의 진행 중 사건일 때만 허용한다(공용 규칙)
        val live = lastCar?.flow?.takeIf { carFresh(now) && incident(it.state) && !it.test }?.snapshot?.facts?.eventId
        return { kind, id, payloadStart ->
            // 보낸 기록만 「내가 본 사건」이다 — 방어에 막혀 남긴 「보내지 않음」 기록을 치면 다음 길(대조 · 다른 종류)이 풀린다(sec-audit 결함 1)
            val seen = store.smsRecords.filter { it.sent }.map { it.eventId }.toSet() +
                flowSeenAtServer.filter { (_, at) -> joined != null && at < joined }.keys
            // 사건 시작 시각은 차 흐름(SERVER)에서 본 것이 먼저 — 보낼 함이 다시 싣는 옛 지시 사실은 보정 전 LOCAL 일 수 있다(be-dev)
            PhoneSmsGuard.check(kind, id, nowServer, joined, seen, live, carFlowStartServer[id] ?: payloadStart)
        }
    }

    fun onSyncError(nowMono: Long = lastNow) {
        firstSyncDone = true
        syncOk = false
        if (syncFailingSince == null) syncFailingSince = nowMono
    }

    /** 마지막 sync 가 성공했는가(C-7 경고 조건 — 실패 중이면 워치 직접 경로를 알 수 없어 경고하지 않는다). */
    private var syncOk = false
    /** sync 가 실패하기 시작한 단조 시각(하9). 성공하면 지운다. */
    private var syncFailingSince: Long? = null
    /** C-7 경고 조건이 처음 참이 된 단조 시각. 조건이 깨지면 지운다. */
    private var watchAlertSince: Long? = null

    /** C-7 경고 조건(지속 시간 빼고). */
    private fun watchAlertCondition(now: Long): Boolean =
        store.registration.powerOn && relayActive() && syncOk && dlWatch != null &&
            !WatchFreshness.isStale(WatchFreshness.viaDataLayer(dlWatchAtLocal, now), params.watchStaleMs) && !watchReachable(now)

    private fun trackWarnings(now: Long) {
        watchAlertSince = if (watchAlertCondition(now)) watchAlertSince ?: now else null
    }

    /**
     * 401 — 짝이 끝났다. 차 링크 · 중계 워치 정보를 버린다(앱은 다시 짝을 맺는다). 차의 마지막 흐름([candidate] · [heldCar])은 남겨
     * 다음 틱에 넘겨받는다 — 사건 중 401 이면 같은 사건 id 로 이어 가 같은 무응답이 새 사건이 되거나 사라지지 않게(A-4 · 판정 · 중계 2).
     */
    fun onUnauthorized() {
        firstSyncDone = true
        syncOk = false; syncFailingSince = null
        lastCar = null; carFreshAtLocal = null; relayWatch = null
        resetWatchResponse()
        watchDirectAgeMs = null
        outbox.clear(); acks.clear()
    }

    fun onSyncResponse(resp: PhoneSyncResponse, sentMono: Long, recvMono: Long) {
        lastNow = recvMono
        firstSyncDone = true
        syncOk = true; syncFailingSince = null
        clock.add(sentMono, recvMono, resp.serverTime)
        // 짝을 막 맺었다 — 첫 응답의 중계 시각을 참여 시각으로 남긴다(A-2)
        if (store.pairJoinedAtServerMs == PhoneStore.JOIN_PENDING) { store.pairJoinedAtServerMs = resp.serverTime; resetWatchResponse() }
        resp.peer?.flow?.snapshot?.facts?.eventId?.let {
            flowSeenAtServer.getOrPut(it) { resp.serverTime }
            // 차 토큰을 가진 쪽이 id 를 매번 바꿔도 끝없이 자라지 않게 최근 것만 둔다(sec-audit 권고)
            while (flowSeenAtServer.size > FLOW_SEEN_MAX) flowSeenAtServer.remove(flowSeenAtServer.keys.first())
        }
        resp.peer?.flow?.takeIf { it.clock == SnapshotClock.SERVER && !it.test }?.snapshot?.facts?.let { f ->
            val id = f.eventId ?: return@let
            (if (id.startsWith("accident")) f.accidentAtMs else f.firstAlertAtMs)?.let { carFlowStartServer[id] = it }
            while (carFlowStartServer.size > FLOW_SEEN_MAX) carFlowStartServer.remove(carFlowStartServer.keys.first())
        }
        outbox.removeAll(inFlightCmds.toSet())
        acks.removeAll(inFlightAcks.toSet())
        inFlightCmds = emptyList(); inFlightAcks = emptyList()
        // 보낸 뒤 새 값이 왔으면 그것은 아직 보내지 않았다
        if (inFlightWatch != null && inFlightWatch === dlWatch) dlWatchUnsent = false
        inFlightWatch = null

        val peer = resp.peer
        if (peer == null) {
            lastCar = null; carFreshAtLocal = null
        } else {
            val carAt = recvMono - (resp.serverTime - peer.receivedAt).coerceAtLeast(0)
            lastCar = peer
            carFreshAtLocal = carAt
            peer.flow?.let { candidate = it; candidateAtLocal = carAt; trackHeld(it, carAt) }
            // 차 문자 대조(A-3) — 신선한 차 흐름(owner CAR)마다. 차는 냈다는데 내 기록에 없으면 10초 뒤 스스로, 응답 시각이 생겼으면 취소
            peer.flow?.takeIf { it.owner == Owner.CAR && carFresh(recvMono) }?.let {
                val local = it.restoreLocal(clock.offsetAt(recvMono), recvMono).snapshot
                reconcile(local, Party.CAR, recvMono, immediate = false)
            }
        }
        if (store.registration.consents.health) resp.watch?.let { relayWatch = it; relayWatchAtLocal = recvMono }
        watchDirectAgeMs = resp.watchDirectAgeMs
        watchDirectAtLocal = recvMono
        // 해석하며 건너뛴 지시(모르는 type · 형식 틀림)는 그 지시만 「지원하지 않음」으로 ack 한다(계약 §4.6)
        acks += resp.skippedCommandIds
        // 실제 사고 · (LOCAL 중) 실제 경보 지시가 테스트 표시 · 미리보기로 처리되지 않게 지시를 실행하기 전에 테스트를 끝낸다
        if (realIncidentEndsTest(recvMono) || resp.commands.any(::isRealAccidentSms)) endTestForAccident()

        // 꺼짐 · 동의 없음(끄기 · 철회 뒤 마지막 sync 들) — 경보 멈춤만 실행하고 나머지 지시는 실행하지 않고 ack 만 한다(app-qa 하3)
        val off = !store.registration.powerOn || !store.registration.consents.all
        for (c in resp.commands) {
            if (off && c.type != CommandType.ALERT_STOP) { acks += c.id; continue }
            if (c.type == CommandType.ALERT_STOP) responsePending = false
            // 허용 표(계약 §4.6 command-roles)에서 휴대폰으로 FLOW_SNAPSHOT 을 낼 수 있는 쪽은 없다 — 차의 흐름은 peer.flow 로만 받는다.
            // 받은 것은 넘겨받기 후보로 쓰지 않고 ack 만 한다(sec-audit — 낸 쪽 확인 없이 후보가 바뀌던 길)
            if (c.type == CommandType.FLOW_SNAPSHOT) { acks += c.id; continue }
            if (c.type == CommandType.TEST_REQUEST) {
                onTestRequest(c, recvMono)
                acks += c.id
                continue
            }
            if (executor.execute(c, execContext(recvMono))) acks += c.id
        }
        // 다시 뜬 휴대폰 되살리기(C-5) — 이번 응답의 지시(ALERT_START 등)를 먼저 실행한 뒤에 본다
        if (!off) peer?.flow?.takeIf { it.owner == Owner.CAR && carFresh(recvMono) }?.let {
            reviveFromCar(it.restoreLocal(clock.offsetAt(recvMono), recvMono).snapshot, recvMono)
        }
    }

    // ── 주기 ──

    /** 2초마다(그리고 사건 직후) 부른다. */
    fun tick(nowMono: Long) {
        lastNow = nowMono
        trackResponse(nowMono)
        trackWarnings(nowMono)
        decideOwner(nowMono)
        // 꺼짐(켬 false · 동의 빠짐)은 [shutdown] 한 곳에서만 넣는다 — sync 도중 끄기가 들어와도 틱이 먼저 OFF 로 만들어
        // 「응답 확인 꺼짐」 문자 판단이 사건을 잃거나, 지금 시각으로 꺼짐을 넣어 S3 · S4 에 한 번 들어가지 않게(app-qa 2026-11-08 중 1).
        // 그동안에도 저장한 사건은 지운다(G2 — 꺼진 뒤 스냅숏을 남기지 않는다)
        if (owner == Owner.PHONE) machine?.let { if (offNotFed()) clearSavedFlow() else runMachine(it, nowMono) }
        // 남은 알람은 워치 버튼 응답으로도 끈다 — 주행 중에는 워치 버튼이 유일한 응답 수단이다(G5)
        if (pendingWatchResponse != null && strandedAlarm(nowMono)) answerStrandedAlarm()
        if (ownerState(nowMono) in ALARM_STATES) alarmAnswered = false // 새 경보 — 앞서 끈 남은 알람의 응답 결과를 이어 쓰지 않는다
        if (realIncidentEndsTest(nowMono)) endTestForAccident()
        motionNow(nowMono)
        pendingWatchResponse = null // 차가 주인일 때의 응답은 차가 받는다 — 넘겨받은 뒤 옛 응답을 다시 쓰지 않게 지운다
        test?.let { t ->
            val elapsed = nowMono - t.startMono
            when {
                // 경보가 풀리지 않을 때의 마지막 안전장치
                elapsed > LocalTestRun.DURATION_MS + TEST_GRACE_MS -> stopTest()
                // 워치가 시뮬레이션을 멈췄거나 시작하지 않았다 — 남은 세션이 실제 값을 테스트로 가리지 않게(C-1)
                watchSessionGone(t, nowMono) -> stopTest()
                // 시나리오 길이가 지났고 그 테스트의 흐름이 경보 중이 아니면 바로 끝낸다 — 시뮬레이션 신호가 없어 더 일어날 일이 없다
                elapsed > LocalTestRun.DURATION_MS && testFlowIdle(t, nowMono) == true -> stopTest()
                t.mode == TestMode.LOCAL -> runLocalTest(t, nowMono)
            }
        }
        localRetry.removeAll { nowMono > it.untilLocal || executor.execute(it.cmd, execContext(nowMono)) }
        executor.onTick(execContext(nowMono))
    }

    /**
     * 끄기(켬 false · 동의 철회) — 서비스가 마지막 sync 를 보내고 멈추기 직전에 부른다(코드 리뷰 2026-11-08 중 1).
     * 휴대폰이 주인이면 상태 기계에 꺼짐(켬 · 동의)을 넣어 OFF 로 만든다 — 경보 멈춤(ALERT_STOP)으로 알람이 sync 왕복 없이 바로 멈추고,
     * 차 · 워치 몫 멈춤 지시와 OFF 스냅숏이 마지막 sync 에 실려 차가 경보 중인 흐름을 이어받지 않는다. 저장한 사건도 지운다(G2).
     * 꺼짐(켬 · 동의)은 다른 입력보다 먼저, 상태 기계가 이미 처리한 마지막 시각([machineAt])으로 넣는다 — 상태 기계는 어떤 입력이든
     * 그 시각의 타이머를 먼저 보므로, 마지막 틱 뒤 무응답 문턱을 넘긴 지금 시각으로 넣으면 S3 · S4 에 한 번 들어가 문자 · 119 · 안내를 낸 뒤
     * 꺼진다(1차 app-qa 참고). 그 뒤의 시계 · 링크 · 틱은 OFF 에서 받는다. 꺼짐은 여기 한 곳에서만 넣는다 — [tick] 은 꺼짐을 아직 넣지
     * 않았으면 상태 기계를 돌리지 않고 기다린다(app-qa 2026-11-08 중 1).
     * 그보다 먼저(사건을 OFF 로 지우기 전에) 「응답 확인 꺼짐」 보호자 문자를 보낼지 정하고 보내기를 시도한다([powerOffNotice]).
     */
    fun shutdown(now: Long) {
        lastNow = now
        powerOffNotice(now)
        // 묻던 테스트 문자(P17 — 보호자 번호 · 본문)와 테스트 119 미리보기 본문을 버린다 — 끈 뒤 · 철회 뒤 화면에 남지 않게(코드 리뷰 2026-11-09 새로 찾은 것)
        if (executor.pending().isNotEmpty()) effects.clearTestSmsAsk() // 묻는 알림도 본문을 담고 있다
        executor.dropTestState()
        val m = machine?.takeIf { owner == Owner.PHONE } ?: return
        val reg = store.registration
        val at = minOf(machineAt, now)
        val out = ArrayList<Directive>()
        if (fedPower != reg.powerOn) { out += m.handle(FlowInput.Power(reg.powerOn, at)); fedPower = reg.powerOn }
        if (fedConsents != reg.consents) { out += m.handle(FlowInput.ConsentsChanged(reg.consents, at)); fedConsents = reg.consents }
        dispatch(out, m.snapshot(), now, local = false)
        runMachine(m, now)
    }

    /**
     * 실제 사건 도중 켬을 끄면 보호자에게 「응답 확인이 꺼졌다」는 사실만 한 통 보낸다(사용자 결정 2026-11-08) — 사건 문자를 받은 보호자가
     * 소식 없이 기다리지 않게. 차가 주인이어도 보호자 문자는 휴대폰이 보내고 기록도 휴대폰에 있으므로 휴대폰 기록만 본다(계약 · 차는 그대로).
     * 보낼 조건(모두) — 켬 끄기(동의가 모두 있음 — 철회는 G2 경로라 보내지 않는다) · 지금 사건(주인 흐름, 테스트 아님)이 있음 ·
     * 같은 사건(:shared `GuardianSmsDedup.sameIncident`)에 실제로 보낸 EVENT 또는 ACCIDENT 가 있음 · 그 마지막 것이 같은 사건에 마지막으로 보낸
     * CANCEL 보다 뒤(기록 시각 비교 — CANCEL 이 없으면 참) ·
     * 이 사건에 아직 보내지 않음(휴대폰 안 표시 `POWER_OFF:<사건 id>`, 보내기 시도 전에 남긴다 — 시도는 한 번뿐).
     */
    private fun powerOffNotice(now: Long) {
        val reg = store.registration
        if (reg.powerOn || !reg.consents.all) return
        val (facts, from, verified) = currentIncident(now) ?: return
        val eventId = facts.eventId ?: return
        val wall = wallNow()
        fun probe(kind: GuardianSmsWireKind): GuardianSmsDedup.Record {
            val anchor = if (kind == GuardianSmsWireKind.ACCIDENT) facts.accidentAtMs else facts.firstAlertAtMs
            return GuardianSmsDedup.Record(kind, eventId, from, anchor?.takeIf { verified }?.plus(wall - now), null, test = false, recordedAtWallMs = wall)
        }
        val records = GuardianSmsDedup.prune(store.smsRecords, wall)
        val cancels = records.filter { it.kind == GuardianSmsWireKind.CANCEL }
        val contacted = records.filter {
            it.sent && (it.kind == GuardianSmsWireKind.EVENT || it.kind == GuardianSmsWireKind.ACCIDENT) &&
                GuardianSmsDedup.sameIncident(it, probe(it.kind), cancels)
        }
        val lastContact = contacted.maxOfOrNull { it.recordedAtWallMs } ?: return
        // 마지막 보호자 연락(EVENT · ACCIDENT)이 마지막 취소 문자보다 뒤일 때만 — 취소 뒤 사고 문자가 나갔으면 보낸다(PM 보정 2026-11-08)
        val lastCancel = cancels.filter { it.sent && GuardianSmsDedup.sameIncident(it, probe(GuardianSmsWireKind.CANCEL), cancels) }
            .maxOfOrNull { it.recordedAtWallMs }
        if (lastCancel != null && lastContact <= lastCancel) return
        // 같은 사건으로 묶인 다른 사건 id(두 주인)로도 한 번만 — 그 id 들 어느 것에든 보냈으면 다시 보내지 않는다
        val keys = (listOf(eventId) + contacted.map { it.eventId }).distinct().map { "${CommandExecutor.POWER_OFF_KIND}:$it" }
        if (keys.any(store::isSmsSent)) return
        keys.forEach(store::markSmsSent)
        executor.guardianPowerOff(eventId, wall)
    }

    /**
     * 끄기 직전 지금 사건 — 휴대폰이 주인이면 내 상태 기계, 차가 주인이면 마지막 차 flow(owner CAR)의 사건 사실(내 단조 시각)과
     * 낸 쪽 · 시각 확인 여부. 주인 판정이 아직 CAR 이면 차 링크가 막 10초 문턱을 넘었어도 마지막 차 flow 가 가장 최근 사실이다(app-qa 하).
     * 사건이 아니거나(OFF · S0) 테스트 사건이거나 주인을 모르면 null.
     */
    private fun currentIncident(now: Long): Triple<IncidentFacts, Party, Boolean>? {
        machine?.takeIf { owner == Owner.PHONE }?.snapshot()?.let { s ->
            return if (incident(s.state) && !s.test) Triple(s.facts, Party.PHONE, true) else null
        }
        val car = lastCar?.flow?.takeIf { owner == Owner.CAR && it.owner == Owner.CAR } ?: return null
        if (!incident(car.state) || car.test) return null
        val r = car.restoreLocal(clock.offsetAt(now), now)
        return Triple(r.snapshot.facts, Party.CAR, r.clockVerified)
    }

    private fun incident(s: FlowState) = s != FlowState.OFF && s != FlowState.S0

    /** 꺼짐(켬 false · 동의 빠짐)인데 아직 상태 기계에 넣지 않았다 — [shutdown] 이 넣을 때까지 틱은 상태 기계를 돌리지 않는다. */
    private fun offNotFed(): Boolean {
        val reg = store.registration
        return (!reg.powerOn || !reg.consents.all) && (fedPower != reg.powerOn || fedConsents != reg.consents)
    }

    /**
     * 차 흐름(owner CAR)마다 실제 사건을 쥔다(A-4). 경보 상태(S1 · AP · S3 · S4)이던 사건의 id 가 OFF · S0 흐름에서 사라지면 차 재시작으로 본다 —
     * 상태 기계에서 경보 상태를 벗어나는 정상 길은 응답(S1R · S2, 같은 id) · 무응답(S3 · S4) · 사고(S5) · 꺼짐(OFF, id 남음)뿐이다.
     * 사건이 정상으로 끝나 OFF · S0 이 되면 놓는다. 차가 내려놓은 흐름(owner PHONE)은 여기서 보지 않는다 — 넘겨받을 때 [heldCar] 가 이어 준다.
     */
    private fun trackHeld(f: FlowStatus, at: Long) {
        if (f.owner != Owner.CAR) return
        val rest = f.state == FlowState.OFF || f.state == FlowState.S0
        if (Handover.carIncidentLost(heldCar, f)) { carLostIncident = true; return }
        if (!f.test && !rest) { heldCar = f; heldCarAtLocal = at; carLostIncident = false } else if (rest) heldCar = null
    }

    /**
     * 주인 = 「차 요청이 10초 안에 닿음 + flow.owner == CAR」(A-4). 차가 내려놓거나(owner PHONE) · 경보 중 사건 id 를 잃거나([carLostIncident]) · 401 · 끊김이면
     * 휴대폰이 주인이다. 휴대폰이 미해결 실제 사건을 쥐고 있으면 차가 같은 사건 id 를 owner CAR 로 실을 때까지 넘겨주지 않는다(사건 임대).
     */
    private fun decideOwner(now: Long) {
        if (relayActive() && !firstSyncDone && now - startMono < Ownership.CAR_FRESH_MS) return
        val carFlow = lastCar?.flow
        // 흐름을 싣지 않는 옛 차(flow null)는 주인이라고 본다(계약 이전 동작)
        var next = if (carLostIncident) Owner.PHONE else Ownership.decide(now, carFreshAtLocal, carClaimsOwner = carFlow == null || carFlow.owner == Owner.CAR)
        if (next == Owner.CAR && owner == Owner.PHONE) {
            val mine = machine?.snapshot()
            if (mine != null && !Handover.phoneMayRelease(mine, carFlow)) next = Owner.PHONE
        }
        if (next == Owner.CAR) { restored = null; clearSavedFlow() } // 차가 살아 있으면 차의 흐름이 맞다(두 주인 금지) — 내 옛 저장은 버리고 지운다(G2)
        if (next == owner) return
        if (next == Owner.PHONE) takeOver(now) else handOver(now)
        owner = next
    }

    /**
     * 차 → 휴대폰. 최근 차 flow([candidate])와 앞선 프로세스의 저장([restored]) 가운데 고른다 — 차 flow 가 비사건(S0)이고 저장이 사건이면 저장,
     * 둘 다 사건이면 더 최근 쪽, 저장이 없으면 차 flow, 둘 다 없으면 새로 시작. 차 링크가 신선하면 여기 오지 않는다(차가 주인, 두 주인 금지).
     * 차의 낡은 S0 이 저장된 사건을 지워 같은 무응답이 새 사건 · 두 번째 문자가 되지 않게(app-qa 2026-11-07).
     * 차가 켠 실제 알람이 아직 울리고 차 flow 가 실제 경보 상태면 [HANDOFF_MAX_AGE_MS] 가 지났어도 이어 간다 — 경보가 살아 있으므로
     * (사용자 결정 2026-11-08). 이어 갈 것이 없으면 알람은 끄지 않고 [strandedAlarm] 으로 경보 화면에 끌 수단을 남긴다.
     */
    private fun takeOver(now: Long) {
        // 최근 차 흐름이 사건이 아니면(재시작한 차의 OFF · S0, 내려놓은 빈 흐름) 마지막으로 쥔 실제 사건을 쓴다(A-4)
        val held = heldCar
        val useHeld = held != null && candidate.let { it == null || !incident(it.state) || it.test }
        val c0 = if (useHeld) held else candidate
        val c0At = if (useHeld) heldCarAtLocal else candidateAtLocal
        heldCar = null; carLostIncident = false
        val cand = c0?.takeIf { c ->
            val alarmAlive = realAlarmOn && !c.test && c.state in ALARM_STATES
            // 사건 상태인데 사건 id 가 없는 차 흐름은 넘겨받지 않는다(sec-audit — 문자 지시가 대신 지은 event-L id 는 어느 표시에도 없어
            // 방어 · 원격 상한을 비켜 갔다). 정상 차는 사건을 열 때 늘 id 를 만든다. 넘겨받지 않으면 휴대폰은 자기 워치 값으로 판정한다
            val idless = c.snapshot.facts.eventId == null && (incident(c.state) || c.snapshot.incidentActive)
            !idless && c.state != FlowState.OFF && (alarmAlive || c0At?.let { now - it <= HANDOFF_MAX_AGE_MS } == true)
        }
        val saved = restored
        restored = null
        val candIncident = cand != null && cand.state != FlowState.S0
        val useSaved = saved != null && (cand == null || !candIncident || restoredAtLocal >= (c0At ?: Long.MIN_VALUE))
        machine = when {
            useSaved -> FlowMachine.restore(saved!!.copy(serverOffsetMs = clock.offsetAt(now)), params)
            // 다른 기기(차)의 flow — sameDevice=false(기본): SERVER 면 내 보정값으로, LOCAL 이면 늦어지는 쪽으로 재기준
            cand != null -> FlowMachine.restore(adoptedSnapshot(cand.restoreLocal(clock.offsetAt(now), now).snapshot), params)
            else -> FlowMachine(params)
        }
        holdStopUntilMove = !useSaved && cand != null && machine?.snapshot()?.stopped == true
        // 바깥 상태는 복원한 상태 기계를 대입한 뒤에 되살린다 — 119 지시의 정지 판정([stopped])이 복원한 정차를 보게(코드 리뷰 2026-11-08 하3)
        if (useSaved) resumeEffects(saved!!, now)
        // 넘겨받은 직후 · 저장 사건을 되살린 직후 대조(A-3) — 기다리지 않는다. 차가 냈다는 문자가 내 기록에 없으면 지금 보낸다
        // 차에서 넘겨받은 기계 — 그 기계가 내는 보호자 문자는 사건 id 가 바뀌어도(새 사건 · 사고 id) 원격으로 본다(PM 결정 19 · sec-audit).
        // 내가 저장한 사건을 되살린 것은 저장할 때의 표시(그 사건 id 가 차에서 온 것인가)를 따른다 — 앱을 다시 띄워도 표시가 남는다
        adoptedMachine = if (useSaved) saved!!.facts.eventId?.let(::isCarOriginEvent) == true else cand != null
        if (!useSaved) cand?.snapshot?.facts?.eventId?.let(::markCarOrigin)
        // 이어 가는 차의 사건 id — 저장에서 되살렸고 그 id 가 휴대폰이 새로 만든 것이면 「차에서 받은 id」는 모른다(null)
        adoptedFromId = if (useSaved) saved!!.facts.eventId?.takeUnless(::isAdoptedOwnEvent) else cand?.snapshot?.facts?.eventId
        when {
            useSaved -> reconcile(saved, Party.PHONE, now, immediate = true)
            cand != null -> reconcile(machine?.snapshot(), Party.CAR, now, immediate = true)
        }
        fedConsents = null; fedPower = null
    }

    /** 이 프로세스가 되살린 사건 id(C-5) — 사건 id 마다 한 번. */
    private val revived = HashSet<String>()

    /**
     * 다시 뜬 휴대폰(앱2-중4 · C-5) — 차가 주인인 실제 경보 흐름을 처음 보면 그 사건의 휴대폰 쪽 화면을 차 흐름 사실과 저장 기록으로 되살린다.
     * ① 소리 단계(경보 2 · S3 · S4)인데 실제 알람이 꺼져 있으면 켠다 — 사람이 소리를 줄인 사건([PhoneStore.mutedEventId])은 켜지 않는다.
     * ② S4 이고 차가 119 작성 지시를 냈으면(composeOpened) 그 사실로 P12 본문을 다시 만든다 — 작성 화면은 사람이 누를 때만 연다(G4).
     * ③ 화면 문자 결과 줄은 저장된 문자 기록(그 사건 id)에서 채운다. 이미 처리한 지시 id 는 그대로라 문자는 다시 나가지 않는다.
     */
    private fun reviveFromCar(car: FlowSnapshot, now: Long) {
        val id = car.facts.eventId ?: return
        if (car.test || car.state !in ALARM_STATES || !revived.add(id)) return
        val sound = car.stage >= 2 || car.state == FlowState.S3 || car.state == FlowState.S4
        if (sound && !realAlarmOn && store.mutedEventId != id) effects.startAlarm(car.state.name, 2, false)
        if (car.state == FlowState.S4 && car.composeOpened && executor.last119 == null) {
            val p = Payload.Open119Compose(id, WireFacts.of(car.facts, clock.offsetAt(now)), previewOnly = false, test = false)
            executor.execute(Command("revive-119-$id", p.type, p.toJson(), TTL_MS, false, Party.CAR, CommandTo.PHONE), execContext(now))
        }
        store.smsRecords.filter { it.eventId == id && !it.test }.forEach { effects.guardianSmsResult(it.kind.name, id, it.sent) }
    }

    /** 지금 보이는 사건 id — 내 상태 기계(주인일 때) 또는 신선한 차 흐름. */
    private fun shownEventId(): String? =
        machine?.takeIf { owner == Owner.PHONE }?.snapshot()?.facts?.eventId ?: lastCar?.flow?.snapshot?.facts?.eventId

    /** 대조 기다림(키 → 처음 본 단조 시각) — :shared CarSmsReconcile 이 돌려준 것을 다음 호출에 넘긴다. */
    private var reconcileWaiting: Map<String, Long> = emptyMap()

    /**
     * 차 흐름(또는 되살린 내 흐름) [snap](내 단조 시각)과 내 문자 기록을 사건 id 로 맞춘다(A-3, :shared CarSmsReconcile).
     * 낼 문자는 실행기를 그대로 탄다 — 중복 막기 · 묵은 사건 · 참여 전 사건 방어([from] 이 CAR 면) · 동의 · 번호 확인이 모두 걸린다.
     * 지시 id 는 사건 · 종류마다 하나라 대조를 거듭해도 한 번만 실행되고, 보내지 못하면 지시 ttl 동안 다시 시도한다.
     * 원격 상한(D-6) — 차 흐름 대조는 from=CAR 라 실행기의 [RemoteSmsLimit] 에 그대로 걸린다. 지시 id 는 [RECONCILE_PREFIX] 로 시작한다.
     */
    private fun reconcile(snap: FlowSnapshot?, from: Party, now: Long, immediate: Boolean) {
        val r = CarSmsReconcile.step(snap, store.smsRecords, reconcileWaiting, now, immediate)
        reconcileWaiting = r.waiting
        val facts = snap?.facts ?: return
        for (a in r.actions) {
            val p = Payload.SendGuardianSms(a.kind, a.eventId, WireFacts.of(facts, clock.offsetAt(now)), test = false)
            runLocal(Command("$RECONCILE_PREFIX${a.kind.name}-${a.eventId}", p.type, p.toJson(), TTL_MS, false, from, CommandTo.PHONE), now)
        }
    }

    /**
     * 저장된 사건을 이어 갈 때 바깥 상태도 되살린다 — 경보 2(소리) 중이었으면 알람을 다시 켜고(코드 리뷰 중 4 「알람」),
     * 정차 뒤 무응답(S4)에서 119 지시를 이미 냈으면 같은 사실로 다시 내 P12 본문을 되찾는다(지시는 PHONE 몫이라 휴대폰 안에서만 돈다).
     */
    private fun resumeEffects(saved: FlowSnapshot, now: Long) {
        // 첫 GPS 위치가 올 때까지(최대 VEHICLE_GRACE_MS) 차량 입력을 넣지 않는다 — 복원한 정차 · S4 가 속도 모름으로 S3 가 되지 않게
        vehicleGraceUntil = now + VEHICLE_GRACE_MS
        // 경보 2 는 stage 2, 무응답(S3 · S4)은 stage 0 이지만 소리는 ALERT_STOP 까지 이어진다(설계 §14)
        val sound = saved.stage >= 2 || saved.state == FlowState.S3 || saved.state == FlowState.S4
        if (saved.alerting && sound) effects.startAlarm(saved.state.name, 2, saved.test)
        if (saved.state == FlowState.S4 && saved.composeOpened) {
            dispatch(listOf(Directive.Open119Compose(saved.facts, previewOnly = saved.test, test = saved.test)), saved, now, local = false)
        }
    }

    /** 휴대폰 → 차. 진행 중이면 스냅숏을 FLOW_SNAPSHOT 으로 즉시 알린다(시계는 forSync 규칙). 차가 이어 가므로 저장은 지운다. */
    private fun handOver(now: Long) {
        val m = machine ?: return
        val snap = m.snapshot()
        if (snap.state != FlowState.OFF) {
            val p = Payload.FlowSnapshot(FlowStatus.forSync(snap, Owner.PHONE, clock.offsetAt(now)))
            outbox += Out(Command(newId(), p.type, p.toJson(), TTL_MS, snap.test, Party.PHONE, CommandTo.CAR), now)
        }
        machine = null
        adoptedMachine = false
        clearSavedFlow()
    }

    /**
     * 주인일 때 매 주기 사건 스냅숏을 저장한다(시각은 벽시계로 옮김) — 프로세스가 죽었다 떠도 같은 무응답을 새 사건으로 다시 열어
     * 보호자 문자가 한 번 더 가지 않게. 사건이 아니면(OFF · S0) 지운다.
     */
    private fun persistFlow(snap: FlowSnapshot, now: Long) {
        // 쓰기 직전에 켜짐을 다시 본다 — 끄기와 경쟁해 꺼진 뒤 스냅숏이 남지 않게
        if (!incident(snap.state) || !store.registration.powerOn) { clearSavedFlow(); return }
        val wall = wallNow()
        store.savedFlow = SavedFlow(snap.shiftedBy(wall - now), wall)
        savedActive = true
    }

    private fun clearSavedFlow() {
        if (!savedActive) return
        store.savedFlow = null
        savedActive = false
    }

    private fun runMachine(m: FlowMachine, now: Long) {
        machineAt = now
        val reg = store.registration
        val out = ArrayList<Directive>()
        out += m.handle(FlowInput.ClockSync(clock.offsetAt(now), now))
        out += m.handle(FlowInput.Link(carFreshAtLocal, now))
        if (fedConsents != reg.consents) { out += m.handle(FlowInput.ConsentsChanged(reg.consents, now)); fedConsents = reg.consents }
        if (fedPower != reg.powerOn) { out += m.handle(FlowInput.Power(reg.powerOn, now)); fedPower = reg.powerOn }
        // 테스트 표시 = 신선한 워치 값이 시뮬레이션(test)인 동안만(차 CarHub.applyTestMark 와 같은 규칙, C-1) — 세션만으로는 켜지 않는다.
        // 워치가 「멈추기」로 실제 센서에 돌아왔거나 시나리오가 아직 닿지 않았을 때의 실제 값이 테스트로 가려지지 않게(앱2-중2).
        // 세션을 멈춘 직후 워치가 실제 센서로 돌아오기 전의 시뮬레이션 값은 그대로 테스트다(2026-11-07). 아니면 상태 기계가
        // 테스트 사건의 응답 뒤(S1R · S2)에서도 표시를 끄고 S0 으로 돌아간다
        out += m.handle(FlowInput.TestMode(freshWatchTest(now), now))
        // 차 상태 없음 — 정지 판정은 휴대폰 GPS 속도로 대신한다(기어 · 주차 브레이크 모름, 출처 PHONE_GPS 라야 0.x km/h 흔들림을 정지로 본다).
        // 저장에서 복원한 직후에는 첫 위치가 올 때까지(유예 안) 넣지 않는다 — 속도 모름 두 번이 복원한 정차를 주행으로 뒤집지 않게
        val v = gpsVehicle(now)
        if (holdStopUntilMove && v.speedKmh != null && !v.stopped(params.stopSpeedKmh)) holdStopUntilMove = false
        val grace = vehicleGraceUntil
        if (v.speedKmh != null || ((grace == null || now >= grace) && !holdStopUntilMove)) {
            vehicleGraceUntil = null
            out += m.handle(FlowInput.Vehicle(v, now))
        }
        if (reg.consents.health) currentWatch(now)?.let { (w, age) ->
            // noSignal(워치가 신호를 못 잡음) 값은 판정 입력으로 넣지 않는다 — 30초 뒤 상태 기계의 「워치 신호 없음」 경로(계약 §4.3,
            // 코드 리뷰 2026-11-02 상 1). 응답은 그대로 넣는다
            // 세션이 끝난 뒤 워치가 실제 센서로 돌아오기 전의 남은 시뮬레이션 값은 판정에 넣지 않는다 — 거둔 테스트 사건을 다시 열지 않게(B-7)
            val leftoverSim = w.test && test?.mode != TestMode.WATCH
            if (!w.noSignal && !leftoverSim) out += m.handle(FlowInput.Wrist(w.verdict, now, age))
            pendingWatchResponse?.let { out += m.handle(it) }
        }
        out += m.handle(FlowInput.Tick(now))
        // 워치 경보 맞추기(계약 §4.3 · C-2) — 내 흐름과 워치가 실어 보낸 경보가 어긋나면 시작 · 정지를 다시 낸다(각 10초에 한 번, 공용 규칙)
        val cw = currentWatch(now)?.takeIf { reg.consents.health }
        val fresh = cw != null && !WatchFreshness.isStale(cw.second, params.watchStaleMs)
        val sync = WatchAlertReconcile.step(m.snapshot(), cw?.first, fresh, watchAlertState, now, params)
        watchAlertState = sync.state
        out += sync.directives
        val snapAfter = m.snapshot()
        // 넘겨받은 기계가 낸 보호자 문자의 사건 id 는 차에서 온 사건으로 적는다(새 id 라도) — 실행기가 원격으로 보고 방어 · 상한을 건다
        if (adoptedMachine) snapAfter.facts.eventId?.takeIf { snapAfter.incidentActive }?.let { id ->
            markCarOrigin(id)
            // 차에서 받은 id 가 아니면 휴대폰이 참여 뒤 새로 연 사건이다 — 참여 전 방어에서 빼고 원격 상한만(독립 검토 ①)
            if (id != adoptedFromId) markAdoptedOwn(id)
        }
        dispatch(out, snapAfter, now, local = false)
        if (adoptedMachine && (snapAfter.state == FlowState.OFF || snapAfter.state == FlowState.S0) && !snapAfter.incidentActive) adoptedMachine = false
        persistFlow(m.snapshot(), now)
    }

    /**
     * 워치 응답 — :shared [ResponseChange](처음 본 값은 기준, 기준보다 1초 넘게 커질 때만 응답, 기준은 마지막으로 본 값, 계약 §4.3)를
     * 경로마다 따로 돌린다. 한 경로 안의 값은 워치 벽시계 순서라 시계가 뒤로 보정돼도 그 뒤 누름부터 다시 받는다(11-02 남은 하 —
     * 지금까지 본 최댓값만 받으면 옛 최댓값을 넘을 때까지 응답을 놓쳤다). 두 경로가 번갈아 와도 기준이 섞이지 않고,
     * 한 경로에서 이미 본 값(여유 안)은 다른 경로에서 새로 와도 응답으로 넣지 않는다 — 같은 누름이 늦게 도착한 것이다.
     * 경로가 늦게 붙어 첫 값이 다른 경로의 마지막 값보다 1초 넘게 크면 그 경로에서는 기준이지만 새 누름이다(app-qa 하1) —
     * 응답으로 넣는다. 매 틱 다시 들어오는 같은 값(응답 아님)은 본 값으로 적지 않는다.
     */
    private fun trackResponse(now: Long) {
        // 응답 번호(계약 §4.3 · B-5) — 새 워치(번호 또는 alerting 을 싣는 워치)를 한 번이라도 본 뒤에는 공용 WatchResponseTracker 하나로만 받는다.
        // 주인이든 아니든 매 틱 가장 신선한 워치 값 하나를 넣는다 — 두 경로의 같은 번호는 기준과 같아 한 번만, 재설치(번호 다시 시작)는
        // 공용 규칙(작은 번호가 연달아 REINSTALL_RUN 번)이 판정한다. 응답은 주인일 때만 상태 기계에 들어간다(runMachine)
        if (seqSeen || listOfNotNull(relayWatch, dlWatch).any { it.responseSeq != null || it.alertingReported }) {
            seqSeen = true
            currentWatch(now)?.first?.let { w -> responseTracker.onWatch(w, now)?.let { pendingWatchResponse = it } }
            return
        }
        relayWatch?.let { feedResponse(relayResponse, dlResponse, it.responseAt, now) }
        dlWatch?.let { feedResponse(dlResponse, relayResponse, it.responseAt, now) }
    }

    private fun feedResponse(path: ResponsePath, other: ResponsePath, r: Long?, now: Long) {
        val first = !path.started
        path.started = true
        var response = path.change.onValue(r, now)
        if (r == null) return
        path.last = r
        val otherLast = other.last
        if (first && otherLast != null && r - otherLast > ResponseChange.MARGIN_MS) response = FlowInput.Response(ResponseSource.WATCH_BUTTON, now)
        if (!first && response == null) return // 같은 값의 반복 · 작아진 값 — 본 값으로 적지 않는다
        if (seenResponseAt.any { abs(it - r) <= ResponseChange.MARGIN_MS }) return
        seenResponseAt.addLast(r)
        if (seenResponseAt.size > SEEN_RESPONSES) seenResponseAt.removeFirst()
        if (response != null) pendingWatchResponse = response
    }

    /** 가장 최근 워치 값과 그 경과(ms). 중계 경유는 ageMs + 응답 뒤 지난 시간, Data Layer 는 받은 뒤 지난 시간. */
    private fun currentWatch(now: Long): Pair<Watch, Long>? {
        val viaRelay = relayWatch?.let { w -> w.ageMs?.let { w to WatchFreshness.viaRelay(it, relayWatchAtLocal, now) } }
        val viaDl = dlWatch?.let { it to WatchFreshness.viaDataLayer(dlWatchAtLocal, now) }
        return listOfNotNull(viaRelay, viaDl).minByOrNull { it.second }
    }

    /** 경보 흐름용 GPS 속도 — 거르기 전 값. */
    private fun gpsSpeed(now: Long): Double? = phoneLoc?.takeIf { now - phoneLocAtLocal <= LOC_FRESH_MS }?.speedKmh

    /** 화면 잠금용 GPS 속도 — 정확도로 거른 값. 오차 큰 정지 속도는 null(「모름」). */
    private fun motionGpsSpeed(now: Long): Double? = phoneMotionKmh?.takeIf { phoneLoc != null && now - phoneLocAtLocal <= LOC_FRESH_MS }

    private fun gpsVehicle(now: Long) = VehicleState(speedKmh = gpsSpeed(now), speedSource = SpeedSource.PHONE_GPS)

    private fun carFresh(now: Long) = Ownership.decide(now, carFreshAtLocal) == Owner.CAR

    /** 정지 판정 — 차가 살아 있으면 차 값(속도 · 기어 · 주차 브레이크), 아니면 휴대폰 GPS 속도. 시험(LOCAL)은 흉내 값. */
    fun stopped(now: Long): Boolean {
        test?.takeIf { it.mode == TestMode.LOCAL }?.let { return it.stoppedSim }
        val car = lastCar
        if (car != null && carFresh(now)) {
            val v = car.vehicle
            return VehicleState(v.speedKmh, v.gear, v.parkingBrake, ignitionOn = v.ignitionOn).stopped(params.stopSpeedKmh)
        }
        machine?.let { return it.snapshot().stopped }
        return gpsVehicle(now).stopped(params.stopSpeedKmh)
    }

    /**
     * 화면 잠금 · 테스트 시작용 이동 판단([MotionRules]). 차 연결도 GPS 속도도 없으면 「모름」, 다만 2분 안에 주행 판단이 있었으면
     * 「주행」(주행 유지) — [stopped] 의 뜻은 그대로 둔다. GPS 유무는 정확도로 거른 속도로 본다. 차 연결이 없을 때 정차는
     * [stopped](거르기 전 속도의 디바운스)에 더해 지금 거른 속도가 [VehicleState.GPS_STOP_KMH] 이하여야 한다 — 오차 큰 저속으로
     * 디바운스가 정지를 확정한 뒤 좋은 주행 표본이 와도 잠금이 풀리지 않게 한다(app-qa 하 지적, 2026-11-01).
     * 부를 때마다 스스로 확인을 다시 보고, 판단이 주행 · 정차로 바뀌었거나 10분이 지났으면 지운다(한 번 풀리면 되살아나지 않는다).
     */
    private fun motionNow(now: Long): Motion {
        val sim = test?.mode == TestMode.LOCAL
        val carKnown = sim || (lastCar != null && carFresh(now))
        val gpsKmh = motionGpsSpeed(now)
        val gpsKnown = gpsKmh != null
        val st = stopped(now) && (carKnown || (gpsKmh != null && gpsKmh <= VehicleState.GPS_STOP_KMH))
        if (!sim) when (MotionRules.motion(carKnown, gpsKnown, st)) {
            Motion.DRIVING -> lastDrivingAt = now
            Motion.STOPPED -> lastDrivingAt = null
            Motion.UNKNOWN -> Unit
        }
        val m = MotionRules.motion(carKnown, gpsKnown, st, MotionRules.drivingHeld(lastDrivingAt, now))
        if (!MotionRules.confirmValid(stopConfirmAt, m, now)) stopConfirmAt = null
        return m
    }

    /** 「정차 중입니다」 — 「모름」일 때만 받는다. 받았으면 true. */
    fun confirmStopped(now: Long): Boolean {
        lastNow = now
        if (motionNow(now) != Motion.UNKNOWN) return false
        stopConfirmAt = now
        return true
    }

    private fun execContext(now: Long) =
        ExecContext(
            stopped = stopped(now), testSession = watchSimulation(now), offsetMs = clock.offsetAt(now), nowLocal = now, nowWall = wallNow(),
            smsGuard = smsGuard(now), incidentOngoing = { incidentOngoing(it, now) }, carOriginEvent = ::isCarOriginEvent,
            adoptedOwnEvent = ::isAdoptedOwnEvent,
        )

    /**
     * 사건 [eventId] 가 아직 진행 중인가(위임 결정 3 다시 보내기) — 내 상태 기계(주인일 때) 또는 신선한 차 흐름의 같은 id 가
     * 경보 · 사고 상태(S1 · AP · S3 · S4 · S5)이고 응답 시각이 없다. 그 밖(응답 · 끝 · 모름)은 거짓.
     */
    private fun incidentOngoing(eventId: String, now: Long): Boolean {
        val snap = machine?.takeIf { owner == Owner.PHONE }?.snapshot()?.takeIf { it.facts.eventId == eventId }
            ?: lastCar?.flow?.takeIf { carFresh(now) && it.snapshot.facts.eventId == eventId }?.snapshot
            ?: return false
        // 사고(S5)는 응답으로 끝나지 않는다 — 응답 뒤 이어진 사고는 앞 사건의 응답 시각을 이어 받으므로 보지 않는다(독립 검토 ②, CarSmsReconcile 과 같은 기준)
        if (snap.state == FlowState.S5) return true
        return snap.facts.responseAtMs == null && snap.state in ALARM_STATES
    }

    /** 경보 화면 「보호자 문자 다시 보내기」 — 정지일 때만(G5) 지금 사건의 보내지 못한 문자를 바로 다시 보낸다. */
    fun resendGuardianSms(now: Long): Boolean {
        lastNow = now
        val id = status(now).eventId ?: return false
        return executor.resendNow(id, execContext(now))
    }

    /**
     * 워치가 시뮬레이션 값을 내는 세션(WATCH)이고 지금 신선한 워치 값이 test=true 인가(C-1) — 이때만 지시의 테스트 표시 없는 문자를 막고
     * 119 를 미리보기로 돌리며 차에 테스트로 알린다. 세션이 남아 있어도 워치가 실제 값을 내면 실제다(앱2-중2).
     * LOCAL 은 시뮬레이션 · 별도 상태 기계 · 지시(test=true)가 모두 휴대폰 안에 머물러, 그동안 보이는 test=false 지시는 실제 워치 값의 실제 사건이다(2026-11-07).
     */
    private fun watchSimulation(now: Long) = test?.mode == TestMode.WATCH && freshWatchTest(now)

    /** 건강 관련 동의가 있고 가장 최근 워치 값이 신선하며 test=true 인가. */
    private fun freshWatchTest(now: Long): Boolean = freshWatch(now)?.test == true

    /** 건강 관련 동의가 있을 때 신선한(watchStaleMs 안) 가장 최근 워치 값. */
    private fun freshWatch(now: Long): Watch? = if (!store.registration.consents.health) null
    else currentWatch(now)?.takeIf { (_, age) -> !WatchFreshness.isStale(age, params.watchStaleMs) }?.first

    /**
     * WATCH 세션 끝 규칙(C-1, app-wear 와 맞춤) — 첫 test=true 를 본 뒤에는 마지막 test=true 로부터 [SIM_GONE_MS] 넘게 신선한 값이
     * test=false 면(워치 「멈추기」 · 워치 재시작) 끝, 시작 뒤 [SIM_FIRST_MS] 안에 test=true 를 한 번도 못 보면(TEST_SCENARIO 미도달) 끝.
     * 값이 아예 오지 않으면(신선하지 않음) 10초 규칙은 세지 않고 시나리오 길이 · 안전장치에 맡긴다.
     */
    private fun watchSessionGone(t: TestSession, now: Long): Boolean {
        if (t.mode != TestMode.WATCH) return false
        val w = freshWatch(now)
        if (w?.test == true) { t.lastSimSeenAt = now; return false }
        val seen = t.lastSimSeenAt ?: return now - t.startMono > SIM_FIRST_MS
        return w != null && now - seen > SIM_GONE_MS
    }

    /** 워치가 중계에 직접 붙어 있어 워치에 지시가 닿는가(계약 §4.3 · §4.7 — Data Layer 값만으로는 아니다). */
    private fun watchReachable(now: Long) = WatchReach.canCommandWatch(watchDirectAgeMs, watchDirectAtLocal, now)

    /** 상태 기계 지시 → 프로토콜 지시. 휴대폰 몫은 바로 실행, 차 · 워치 몫은 다음 sync 로. LOCAL 시험은 휴대폰 밖으로 아무것도 보내지 않는다. */
    private fun dispatch(directives: List<Directive>, snap: FlowSnapshot, now: Long, local: Boolean) {
        for (d in directives) {
            if (local && d is Directive.Show) { effects.showLine(d.phrase.ko, null, d.test); continue }
            val cmds = DirectiveCommands.toCommands(d, Party.PHONE, newId, TTL_MS, clock.offsetAt(now))
            // 자기 대상(PHONE)은 받은 지시와 같은 실행 함수로 바로, 나머지만 중계로(중계는 자기 대상이 섞이면 요청 전체를 400)
            val (mine, others) = DirectiveCommands.splitLocal(cmds, Party.PHONE)
            for (c in mine) runLocal(c, now)
            for (c in others) {
                // ALL(경보 멈춤)은 중계가 보낸 쪽을 빼고 전하므로 휴대폰 몫은 여기서 실행한다
                if (c.resolvedTo == CommandTo.ALL) runLocal(c, now)
                if (!local) outbox += Out(c, now)
            }
        }
    }

    private fun runLocal(c: Command, now: Long) {
        if (!executor.execute(c, execContext(now))) localRetry += Retry(c, now + c.ttlMs)
    }

    /**
     * 본문 조립에 넘길 휴대폰 값. vehicle 은 차 링크가 살아 있을 때만 `peer.vehicle`(끊겼으면 null — 「차 상태 없음」),
     * watch 의 ageMs 는 지금 기준 경과로 고쳐 넣는다. LOCAL 시험의 테스트 지시는 흉내 차(정차 · P)와 시뮬레이션 워치 —
     * 시험 중이라도 실제 지시(test=false)의 본문은 실제 차 · 워치 값으로 만든다.
     */
    private fun phoneContext(probe: Payload.SendGuardianSms, from: Party): IncidentComposer.PhoneContext? {
        val now = lastNow
        val reg = store.registration
        val profile = reg.profile() ?: return null
        val localTest = test?.takeIf { it.mode == TestMode.LOCAL && probe.test }
        // 차에서 온 글자 칸(차종 · 내비 주소)은 거른 뒤에만 본문에 넣는다 — 번호 · 링크 꼴이면 그 칸만 뺀다(D-7)
        val vehicle = if (localTest != null) Vehicle(speedKmh = 0.0, gear = Gear.P) else lastCar?.vehicle?.takeIf { carFresh(now) }?.let(CarTextFilter::vehicle)
        val watch = if (localTest != null) localWatch(now)?.copy(ageMs = 0) else currentWatch(now)?.let { (w, age) -> w.copy(ageMs = age) }
        return IncidentComposer.PhoneContext(
            profile = profile, consents = reg.consents, vehicle = vehicle, watch = watch,
            // 위치의 at 은 잰 시각을 휴대폰 벽시계로 다시 잡은 값(받은 단조 시각 기준) — 문자 위치 줄 시각 · 30초 신선도(공통 하4)
            phoneLocation = if (reg.consents.location) phoneLoc?.takeIf { now - phoneLocAtLocal <= LOC_SEND_MS }?.copy(at = wallNow() - (now - phoneLocAtLocal)) else null,
            nowLocalMs = now, nowWallMs = wallNow(),
            guardianContacted = guardianContacted(probe, from, now),
            serverOffsetMs = clock.offsetAt(now),
        )
    }

    /** 이 사건에 보호자 사건 문자(EVENT)를 실제로 보냈는가 — 보낸 기록과 :shared 「같은 사건」 규칙으로 본다(계약 §6.2). */
    private fun guardianContacted(probe: Payload.SendGuardianSms, from: Party, now: Long): Boolean {
        val records = store.smsRecords
        val next = GuardianSmsDedup.record(
            probe.copy(kind = GuardianSmsWireKind.EVENT), from, clock.offsetAt(now), now, wallNow(), sameDevice = from == Party.PHONE,
        )
        val cancels = records.filter { it.kind == GuardianSmsWireKind.CANCEL }
        return records.any { it.sent && it.kind == GuardianSmsWireKind.EVENT && GuardianSmsDedup.sameIncident(it, next, cancels) }
    }

    /** 119 지시의 사건 사실로 「이 사건 문자」를 흉내 낸다 — 보호자 연락 여부를 같은 규칙으로 보려고. */
    private fun Payload.Open119Compose.asEventProbe() = Payload.SendGuardianSms(GuardianSmsWireKind.EVENT, eventId, facts, test)

    private fun profileFailure() =
        if (!PhoneNumbers.isGuardianNumber(store.registration.guardianPhone) || store.registration.guardianName.isBlank()) ReportFailure.GUARDIAN_MISSING
        else ReportFailure.EMERGENCY_INFO_CONSENT_MISSING

    // ── 응답 · 119 ──

    /** 휴대폰 화면 「괜찮아요」 — 상태 기계가 정지일 때만 받는다(G5). 휴대폰이 주인이거나 LOCAL 시험일 때만 쓸 수 있다. */
    fun phoneResponse(now: Long) {
        lastNow = now
        // 남은 알람 — 정지일 때만(G5) 알람을 끄고 응답으로 남긴다. 상태 기계는 경보가 아니라 넣을 응답이 없다
        if (strandedAlarm(now)) { if (stopped(now)) answerStrandedAlarm(); return }
        val t = test
        if (t?.mode == TestMode.LOCAL && t.machine != null) {
            dispatch(t.machine.handle(FlowInput.Response(ResponseSource.PHONE_SCREEN, now)), t.machine.snapshot(), now, local = true)
            return
        }
        val m = machine?.takeIf { owner == Owner.PHONE }
        if (m == null) {
            // 차가 주인 — 정지일 때만(G5) 누른 순간의 벽시계를 한 번 잡아 sync responseAt 으로 차에 보낸다
            if (stopped(now)) { phoneResponseAtWall = wallNow(); responsePending = true }
            return
        }
        dispatch(m.handle(FlowInput.Response(ResponseSource.PHONE_SCREEN, now)), m.snapshot(), now, local = false)
    }

    /**
     * 실제 알람이 울리는데 알려진 주인 상태(휴대폰이 주인이면 내 상태 기계, 차가 주인이면 신선한 차 flow)가 경보가 아니다 —
     * 경보 끝을 못 받은 채 남은 알람. 주인 상태를 모르면(이어받기 전 틈) 아니다(경보 화면은 직전 화면을 둔다).
     */
    private fun strandedAlarm(now: Long): Boolean {
        if (!realAlarmOn) return false
        val state = ownerState(now) ?: return false
        return state !in ALARM_STATES
    }

    /**
     * 알려진 주인 상태. 차 flow 는 차가 주인(owner=CAR)이라고 실은 것만 — 돌아온 차의 첫 요청에 실린 자기 옛 flow(owner=PHONE, 아직 넘겨받지 않음)는
     * 주인 상태가 아니라 「모름」(null)이다(app-qa · PM 결정 2026-11-08).
     */
    private fun ownerState(now: Long): FlowState? =
        machine?.takeIf { owner == Owner.PHONE }?.state ?: lastCar?.flow?.takeIf { carFresh(now) && it.owner == Owner.CAR }?.state

    private fun answerStrandedAlarm() {
        effects.stopAlarm()
        alarmAnswered = true
    }

    /** 사람이 「119 문자 작성 화면 열기」 · 「다시 열기」를 눌렀다. */
    fun openCompose(now: Long): Boolean { lastNow = now; return executor.openCompose(execContext(now)) }

    fun confirmTestSms(key: String, send: Boolean) = executor.confirmTestSms(key, send)

    // ── 테스트 모드 ──

    /**
     * 워치가 중계에 직접 붙어 있으면(계약 §4.3 지시 가능 규칙) TEST_SCENARIO(to WATCH)를 보내고, 아니면 휴대폰 안에서
     * Simulator + PulseGate 로 판정을 만들어 별도 상태 기계(test 표시)를 돌린다. 워치 테스트를 골랐는데 직접 경로가 없으면
     * 「휴대폰만으로 테스트」 안내를 띄운다. 어느 쪽이든 문자는 확인 뒤 보호자에게만, 119 는 미리보기만.
     */
    fun startTest(scenario: Scenario, phoneOnly: Boolean, now: Long, fromCar: Boolean = false): TestStart {
        lastNow = now
        val reg = store.registration
        if (!reg.canTurnOn || !reg.powerOn) return TestStart.NEED_SETUP
        // 정차, 또는 「모름」 + 「정차 중입니다」 확인(10분 안). 주행이면 확인이 있어도 시작하지 않는다(G5)
        if (!MotionRules.canStartTest(motionNow(now), stopConfirmAt != null)) return TestStart.NOT_STOPPED
        // 실제 알람만 남아 울리는 동안도 진행 중으로 본다 — 테스트 알람 · 「괜찮아요」가 남은 실제 알람과 섞이지 않게
        if (flowState(now)?.let { incident(it) } == true || strandedAlarm(now)) return TestStart.BUSY
        stopTest()
        val watchAlive = reg.consents.health && watchReachable(now)
        if (!watchAlive && !phoneOnly) effects.notice(Notice.TEST_PHONE_ONLY_NO_DIRECT_WATCH)
        if (watchAlive && !phoneOnly) {
            val p = Payload.TestScenario(scenario, LocalTestRun.DURATION_MS)
            val c = Command(newId(), p.type, p.toJson(), TTL_MS * 2, true, Party.PHONE, CommandTo.WATCH)
            outbox += Out(c, now)
            test = TestSession(TestMode.WATCH, scenario, now, true, fromCar = fromCar)
            return TestStart.WATCH
        }
        val m = FlowMachine(params)
        m.handle(FlowInput.ClockSync(clock.offsetAt(now), now))
        m.handle(FlowInput.Power(true, now))
        m.handle(FlowInput.ConsentsChanged(reg.consents, now))
        m.handle(FlowInput.TestMode(true, now))
        m.handle(FlowInput.Vehicle(simVehicle(true), now))
        test = TestSession(TestMode.LOCAL, scenario, now, true, LocalTestRun(scenario, now, true), m, fromCar)
        return TestStart.LOCAL
    }

    /**
     * 차가 보낸 테스트 요청(계약 §4.6 「차에서 테스트 요청」) — 홈 「테스트 시작」 · 「테스트 멈추기」와 같은 길 · 같은 검사.
     * 시작은 [startTest](준비 · 정차 또는 「모름」 + 확인 · 사건 없음, 워치 직접 경로면 워치 시나리오 · 아니면 휴대폰 단독 + 안내),
     * 멈춤은 [stopTest]. 이미 테스트 중이면 시작 요청은 무시한다(다시 시작하지 않음).
     * 거부는 따로 답하지 않는다 — 부른 쪽이 ack 만 하고, 차는 flow.test 가 15초 안에 켜지지 않으면 「휴대폰이 답하지 않습니다」를 보인다.
     * 차 링크가 살아 있으면 이동 판단은 차 값이라 차가 정차 중에 보낸 시작은 정차로 통과한다. 「모름」(차 링크 끊김)이면 확인이 있을 때만.
     * 같은 id 는 한 번만, 차가 아닌 쪽이 보낸 요청 · 형식이 틀린 요청은 실행하지 않는다.
     */
    private fun onTestRequest(c: Command, now: Long) {
        if (store.isCommandDone(c.id)) return
        store.markCommandDone(c.id)
        // 차가 보낸 테스트 지시(늘 test:true, 계약 §4.6)만 실행한다
        if (c.from != Party.CAR || !c.test) return
        val p = try {
            Payload.fromJson(c.type, c.payload) as Payload.TestRequest
        } catch (e: RuntimeException) {
            return
        }
        lastNow = now
        when (p.action) {
            TestAction.START -> if (test == null) startTest(Payload.TestRequest.START_SCENARIO, phoneOnly = false, now = now, fromCar = true)
            TestAction.STOP -> stopTest()
        }
    }

    /**
     * 시나리오 길이(120초)가 지난 세션을 끝내도 되는가 — 그 테스트의 흐름이 경보 중(S1 · AP · S3 · S4)이 아니면 참(OFF · S0 · S1R · S2 · S5).
     * 무응답 시연이 도중에 잘리지 않게 경보 중이면 기다리고, 응답 등으로 벗어나는 다음 틱에 끝낸다.
     * LOCAL 은 자기 시험 상태 기계, WATCH 는 휴대폰이 주인이면 내 상태 기계, 차가 주인이면 받은 차 flow 로 본다.
     * 차 flow 를 아직 못 받았거나 주인을 모르면 null — 경보 중이 아니라고 단정하지 않고 안전장치(7분)에 맡긴다(PM 결정 2026-11-07).
     */
    private fun testFlowIdle(t: TestSession, now: Long): Boolean? {
        val state = when {
            t.mode == TestMode.LOCAL -> t.machine?.state
            owner == Owner.PHONE -> machine?.state
            owner == Owner.CAR -> lastCar?.flow?.takeIf { carFresh(now) }?.state
            else -> null
        } ?: return null
        return state !in ALARM_STATES
    }

    /** 「테스트 멈춤」 · 시나리오 끝(경보 중이 아닐 때) · 시간 초과 · 새 테스트 시작 전. 워치 테스트였으면 워치 시나리오도 멈춘다. */
    fun stopTest() = endTest(alwaysStopAlarm = false)

    /** [stopTest] · [endTestForAccident] 의 공통 몸통 — 실제 사건으로 끝낼 때만 알람을 늘 멈춘다. */
    private fun endTest(alwaysStopAlarm: Boolean) {
        val t = test ?: return
        test = null
        if (alwaysStopAlarm || (t.mode == TestMode.LOCAL && t.machine?.snapshot()?.alerting == true)) effects.stopAlarm()
        stopWatchScenario(t, lastNow)
        // 워치 세션이 정말 끝났다 — 내가 주인이고 테스트 흐름이 경보 중이면 한 번 거둔다(B-7 — 경보 중에는 테스트 표시 끄기를 받지 않아 그대로면
        // 실제 사건이 열리지 않는다). 응답 뒤(S1R · S2)는 워치가 실제 값으로 돌아올 때 표시 끄기로 S0 이 된다. 실제 흐름 · S5 는 상태 기계가 무시한다.
        // 차가 주인이면 차가 휴대폰 flow.test 가 꺼진 것 + 신선한 워치 test=false 10초로 같은 입력을 넣는다(app-android 와 맞춤)
        if (t.mode == TestMode.WATCH) machine?.takeIf { owner == Owner.PHONE && it.snapshot().test && it.state in ALARM_STATES }?.let { m ->
            dispatch(m.handle(FlowInput.WithdrawTest(lastNow)), m.snapshot(), lastNow, local = false)
        }
        // 답하지 않은 테스트 문자 확인은 「보내지 않음」으로 거두고 다시 묻지 않는다(PM 결정 2026-11-08, 실제 사고로 끝날 때와 같은 처리),
        // 테스트 미리보기 · 테스트 알림(119 미리보기 · 테스트 문자 확인)을 거둔다(B1 결함 5)
        executor.dropTestState()
        effects.clearTestScreens()
    }

    /** 워치에 TEST_SCENARIO 를 보냈던 테스트(WATCH)면 TEST_SCENARIO_STOP(to WATCH, test)을 보낸다(계약 §4.6). */
    private fun stopWatchScenario(t: TestSession, now: Long) {
        if (t.mode != TestMode.WATCH) return
        val p = Payload.TestScenarioStop
        outbox += Out(Command(newId(), p.type, p.toJson(), TTL_MS, true, Party.PHONE, CommandTo.WATCH), now)
    }

    /**
     * 실제 에어백 사고(S5 · test=false)가 보이는가 — 휴대폰이 주인이면 내 상태 기계(넘겨받은 S5 포함), 차가 주인이면 차의 flow.
     * 휴대폰에는 에어백 입력이 없어서 사고는 늘 차에서 온다(계약 §4.6 「에어백 사고는 늘 실제」).
     */
    private fun realAccident(now: Long): Boolean {
        val mine = machine?.takeIf { owner == Owner.PHONE }?.snapshot()
        if (mine != null) return mine.state == FlowState.S5 && !mine.test
        val car = lastCar?.flow?.takeIf { carFresh(now) } ?: return false
        return car.state == FlowState.S5 && !car.test
    }

    /**
     * 진행 중인 테스트를 끝내야 하는 실제 사건이 보이는가 — 실제 사고(S5)는 어느 세션이든, 실제 경보(S1 · AP · S3 · S4 · test=false)는
     * LOCAL 세션일 때만. LOCAL 중 실제 워치 값이 연 사건은 틀림없이 실제라 시험을 거두고 화면 · 문자를 실제로 돌린다(2026-11-07).
     * WATCH 세션의 test=false 경보는 표시가 늦은 시뮬레이션 사건일 수 있어 끝내지 않는다(그 문자는 실행기가 막는다, 계약 §4.6).
     */
    private fun realIncidentEndsTest(now: Long): Boolean {
        if (realAccident(now)) return true
        if (test?.mode != TestMode.LOCAL) return false
        val flow = machine?.takeIf { owner == Owner.PHONE }?.snapshot()?.let { it.state to it.test }
            ?: lastCar?.flow?.takeIf { carFresh(now) }?.let { it.state to it.test }
            ?: return false
        return flow.first in ALARM_STATES && !flow.second
    }

    /** 아직 실행하지 않은 실제 사고 문자 지시인가 — 이미 실행한 지시가 다시 와도 새 테스트를 끝내지 않는다. */
    private fun isRealAccidentSms(c: Command): Boolean {
        if (c.type != CommandType.SEND_GUARDIAN_SMS || c.test || store.isCommandDone(c.id)) return false
        val p = runCatching { Payload.fromJson(c.type, c.payload) as Payload.SendGuardianSms }.getOrNull() ?: return false
        return p.kind == GuardianSmsWireKind.ACCIDENT && !p.test
    }

    /**
     * 테스트 도중 실제 사건(사고, 또는 LOCAL 중 실제 경보) — 휴대폰 테스트(LOCAL 시뮬레이션 · 별도 상태 기계 · WATCH 표시)를 멈추고 테스트 화면을 거둔다.
     * 테스트 경보는 차가 보낸 test=true ALERT_STOP 으로도 멈추지만, 넘겨받기 · 지시 유실에 대비해 여기서도 멈춘다(S5 는 경보가 없다).
     * 워치 테스트였으면 TEST_SCENARIO_STOP 으로 워치 시나리오도 멈춘다(계약 §4.6 「에어백 사고는 늘 실제」).
     */
    private fun endTestForAccident() = endTest(alwaysStopAlarm = true)

    private fun runLocalTest(t: TestSession, now: Long) {
        val m = t.machine ?: return
        val run = t.run ?: return
        // 사람이 응답한 뒤에는 시뮬레이션 신호를 정상으로 바꾼다 — 상태 기계의 「응답 뒤 나쁜 판정 30초 지속 → 새 사건」(실제 신호용 규칙)이
        // 시나리오의 남은 나쁜 신호로 같은 시연을 다시 열지 않게(B1 결함 2). 규칙은 그대로, 시험 입력만 바꾼다
        if (m.state == FlowState.S1R || m.state == FlowState.S2) t.responded = true
        val verdict = if (t.responded) WristVerdict.NORMAL else run.verdictAt(now)
        val out = ArrayList<Directive>()
        out += m.handle(FlowInput.Vehicle(simVehicle(t.stoppedSim), now))
        out += m.handle(FlowInput.Wrist(verdict, now, 0))
        out += m.handle(FlowInput.Tick(now))
        dispatch(out, m.snapshot(), now, local = true)
    }

    /** LOCAL 시험의 워치 사실 — 시뮬레이션 출처 · 테스트 표시. 마지막 맥박 시각은 표시용 벽시계로 옮긴다. */
    private fun localWatch(now: Long): Watch? {
        val t = test ?: return null
        val r = t.run?.last ?: return null
        val wall = wallNow()
        return Watch(
            verdict = r.verdict, hr = r.hr, lastPulseAt = r.lastPulseAtMs?.let { wall + (t.startMono + it - now) },
            worn = r.verdict != WristVerdict.CHECK_WEAR, source = WatchSource.SIMULATION, test = true, responseAt = null,
        )
    }

    private fun simVehicle(stopped: Boolean) =
        if (stopped) VehicleState(speedKmh = 0.0, gear = Gear.P) else VehicleState(speedKmh = 60.0, gear = Gear.D)

    // ── 화면 ──

    /** 화면 · 테스트 시작에 쓰는 흐름 상태 — 휴대폰이 주인이면 내 상태 기계, 아니면 신선한 차 flow. */
    private fun flowState(now: Long): FlowState? =
        machine?.takeIf { owner == Owner.PHONE }?.state ?: lastCar?.flow?.state?.takeIf { carFresh(now) }

    fun status(now: Long): EngineStatus {
        val m = machine?.takeIf { owner == Owner.PHONE }
        val state = flowState(now)
        val t = test
        val tState = t?.machine?.state
        val stopped = stopped(now)
        val respondState = if (t?.mode == TestMode.LOCAL) tState else m?.state
        val w = currentWatch(now)
        // 지금 보이는 흐름 — LOCAL 시험이면 시험 상태 기계, 휴대폰이 주인이면 내 상태 기계, 차가 주인이면 차가 보낸 flow
        val shownSnap = t?.machine?.snapshot() ?: m?.snapshot()
        val carFlow = lastCar?.flow?.takeIf { carFresh(now) && shownSnap == null }
        val shownState = shownSnap?.state ?: carFlow?.state
        val eventId = shownSnap?.facts?.eventId ?: carFlow?.snapshot?.facts?.eventId
        val incident = shownState in ALARM_STATES && eventId != null
        if (incident) incidentSeenAt.getOrPut(eventId!!) { now }
        val noResponseSec = when {
            !incident -> null
            shownSnap != null -> shownSnap.facts.noResponseSec(now)
            else -> ((now - incidentSeenAt[eventId]!!) / 1000).toInt()
        }
        val wall = wallNow()
        val motion = motionNow(now)
        return EngineStatus(
            owner = owner, carFresh = carFresh(now), flowState = state, stopped = stopped,
            // 워치가 신호를 못 잡고 있다고 알린 값(noSignal)은 화면에서도 신호 없음으로 본다(옛 판정을 보이지 않는다)
            watchVerdict = w?.first?.verdict, watchStale = w == null || WatchFreshness.isStale(w.second) || w.first.noSignal,
            clockUnverified = m?.snapshot()?.clockUnverified == true,
            canRespond = stopped && respondState in RESPONDABLE,
            has119 = executor.last119 != null, test = t?.mode, testScenario = t?.scenario, testFlowState = tState,
            stage = shownSnap?.stage ?: carFlow?.snapshot?.stage ?: 0,
            flowTest = t?.mode == TestMode.LOCAL || watchSimulation(now) || shownSnap?.test == true || carFlow?.test == true,
            noResponseSec = noResponseSec,
            stoppedSinceWall = shownSnap?.facts?.stoppedAtMs?.takeIf { incident }?.let { wall + (it - now) },
            shownState = shownState,
            responsePending = responsePending && owner == Owner.CAR,
            watchDirect = watchReachable(now),
            motion = motion,
            stopConfirmed = stopConfirmAt != null,
            testFromCar = t?.fromCar == true,
            eventId = eventId,
            strandedAlarm = strandedAlarm(now),
            alarmAnswered = alarmAnswered,
            watchAlertUnreachable = watchAlertCondition(now) && watchAlertSince?.let { now - it >= WARN_AFTER_MS } == true,
            serverUnreachable = relayActive() && syncFailingSince?.let { now - it >= WARN_AFTER_MS } == true,
            canResendSms = executor.canResend(eventId),
        )
    }

    companion object {
        const val TTL_MS = 30_000L
        const val MAX_COMMANDS = 32
        /** 차의 마지막 flow 를 이어 갈 수 있는 최대 경과 — 오래된 사건을 되살리지 않게 [추정]. */
        const val HANDOFF_MAX_AGE_MS = 60_000L
        /** 저장해 둔 내 상태 기계 스냅숏([SavedFlow])을 다시 뜰 때 되살리는 최대 경과 — 재부팅을 덮는 5분(PM 결정 2026-11-07, 실측 뒤 조정). */
        const val RESTORE_MAX_AGE_MS = 300_000L
        /** 복원 직후 첫 GPS 위치를 기다리는 유예 — 그동안 차량 입력을 넣지 않아 속도 모름이 복원한 정차를 주행으로 뒤집지 않게 [추정]. */
        const val VEHICLE_GRACE_MS = 60_000L
        const val LOC_FRESH_MS = 10_000L
        const val LOC_SEND_MS = 30_000L
        /**
         * 보내지 못한 Data Layer 워치 값을 다시 싣는 최대 경과 — 중계는 받은 시각으로 나이를 재므로 오래 묵은 값을 다시 보내면
         * 그만큼 신선해 보인다. sync 주기(2초) 몇 번 · 워치 Data Layer 주기(약 1초)를 덮는 값 [추정].
         */
        const val DL_RESEND_MAX_MS = 10_000L
        const val TEST_GRACE_MS = 300_000L
        /** WATCH 세션 — 첫 test=true 를 본 뒤 신선한 값이 이만큼 넘게 test=false 면 끝(C-1, 레드 · 블루 합의 값). */
        const val SIM_GONE_MS = 10_000L
        /** WATCH 세션 — 시작 뒤 이만큼 안에 test=true 를 한 번도 못 보면 끝(C-1, app-wear 확인 — 워치는 받은 뒤 1~2초 안에 보낸다). */
        const val SIM_FIRST_MS = 15_000L
        /** 상태 경고(C-7 워치 경보 닿지 않음 · 하9 서버 닿지 않음)를 내기까지 조건이 이어져야 하는 시간 — 2초 주기 깜빡임으로 알림이 계속 다시 걸리지 않게. */
        const val WARN_AFTER_MS = 30_000L
        /** 대조(A-3)로 낸 문자 지시 id 머리 — 이 길로 나간 문자임을 기록 · 로그에서 가린다. */
        const val RECONCILE_PREFIX = "reconcile-"
        /** 「참여 전에 본 사건」으로 기억하는 차 흐름 사건 id 수 — 하루 사건 몇 개를 넉넉히 덮는 값 [추정]. */
        const val FLOW_SEEN_MAX = 64
        /** 기억하는 「차에서 넘겨받은 사건 id」 수 [추정]. */
        const val CAR_ORIGIN_MAX = 64
        /** 두 경로의 같은 누름을 가리려고 기억하는 최근 응답 값 개수 — 누름 몇 번이면 충분하다 [추정]. */
        private const val SEEN_RESPONSES = 16
        private val RESPONDABLE = setOf(FlowState.S1, FlowState.S3, FlowState.AP, FlowState.S4, FlowState.S5)
        /** 전체 화면 경보를 띄우는 상태(P10 · P10' · P11 · P12). */
        val ALARM_STATES = setOf(FlowState.S1, FlowState.S3, FlowState.AP, FlowState.S4)
    }
}
