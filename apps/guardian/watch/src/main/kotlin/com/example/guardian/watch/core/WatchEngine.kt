// 워치 앱의 판단 중심 — 실제 · 시뮬레이션 샘플을 관문에 넣고, 지시를 받아 경보 · 시나리오 상태를 들고, 보낼 Watch 값을 만든다
package com.example.guardian.watch.core

import com.example.guardian.shared.bio.AccelSample
import com.example.guardian.shared.bio.GateParams
import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.PpgSample
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.Simulator
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchAlertStatus
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.watch.sensor.SampleSink
import com.example.guardian.watch.sensor.SimulationSource

/** 지금 울리고 있는 경보. [respondedAtMs] 는 이 경보 중 응답한 단조 시각, [eventId] 는 지시에 실려 온 사건 id(없으면 옛 지시). */
data class ActiveAlert(
    val commandId: String,
    val payload: Payload.AlertStart,
    val test: Boolean,
    val startedAtMs: Long,
    val respondedAtMs: Long? = null,
    val eventId: String? = null,
)

/**
 * 안드로이드에 기대지 않는다(JVM 시험). 부르는 쪽이 한 스레드에서 부르거나 이 객체로 동기화한다.
 * - 실제 센서 샘플은 [SampleSink] 로 받는다. 시나리오가 도는 동안은 버린다(합성 · 실제를 한 관문에 섞지 않음).
 * - 시나리오가 시작 · 끝날 때 관문을 새로 만든다 — 끝나면 실제 출처로 돌아가 기준선을 다시 잡는다.
 */
class WatchEngine(
    private var realSource: WatchSource,
    lowConfidence: Boolean,
    private val simulator: Simulator = Simulator(),
) : SampleSink {
    var lowConfidence: Boolean = lowConfidence
        private set
    private var gate = realGate()
    private var sim: SimulationSource? = null
    val commands = CommandProcessor()

    var reading: Reading = Reading.INITIAL
        private set
    var alert: ActiveAlert? = null
        private set
    /**
     * 실제 출처가 신호를 못 잡고 있다(Health Services 유효 심박 10초 공백 · 등록 실패, 서비스가 매 초 넣는다).
     * 보낼 값의 `noSignal` 이 된다 — 판정([reading])은 그대로 두고 받는 쪽이 쓰지 않는다(계약 §4.3). 시나리오 중에는 싣지 않는다.
     */
    var noSignal: Boolean = false

    /**
     * 보낼 응답 시각(벽시계 에포크 ms). 응답 순간에 한 번만 잡아 둔다 — sync 마다 단조 → 벽시계로 다시 바꾸면
     * 두 시계를 따로 읽는 차이 · NTP 보정으로 값이 흔들리고, 받는 쪽이 「바뀐 responseAt」을 새 응답으로 읽어
     * 운전자 조작 없이 다음 경보가 꺼질 수 있다(app-qa 1차 검증 불합격 사유).
     */
    private var responseAtWall: Long? = null

    /**
     * 응답 번호(B-5) — 경보가 울리는 동안 누를 때마다 1씩 오르고 바로 디스크에 쓴다(`PairStore.nextResponseSeq`). 감시를 다시 켜면
     * 저장값을 넣는다. 응답한 적이 없으면 null. 보낼 값의 `responseSeq`(계약 §4.3)다.
     */
    var responseSeq: Long? = null

    /** 지금 울리는 경보(없으면 null) — 보낼 값의 `alerting`(계약 §4.3). 주인이 자기 흐름과 맞춰 보고 시작 · 정지를 다시 낸다(C-2). */
    val alerting: WatchAlertStatus? get() = alert?.let { WatchAlertStatus(it.eventId, it.payload.stage, it.payload.level) }

    /** 마지막 맥박 시각도 같은 이유로 새 박동을 볼 때 한 번만 벽시계로 바꾼다(단조 값 → 벽시계 값). */
    private var pulseMono: Long? = null
    private var pulseWall: Long? = null

    val scenario: Scenario? get() = sim?.scenario

    /** 시나리오가 끝나는 단조 시각(화면 「남은 n초」). */
    val scenarioEndsAtMs: Long? get() = sim?.let { it.startAtMs + it.durationMs }
    val source: WatchSource get() = if (sim != null) WatchSource.SIMULATION else realSource

    override fun ppg(s: PpgSample) { if (sim == null) gate.ppg(s) }
    override fun accel(s: AccelSample) { if (sim == null) gate.accel(s) }
    override fun hr(s: HrSample) { if (sim == null) gate.hr(s) }

    /** 실제 출처를 바꾼다(Sensor SDK 실패 → Health Services). 시나리오 중이 아니면 관문을 새로 만든다. */
    fun useRealSource(kind: WatchSource, lowConfidence: Boolean) {
        realSource = kind
        this.lowConfidence = lowConfidence
        noSignal = false
        if (sim == null) gate = realGate()
    }

    /** 1초마다 부른다. 시나리오 샘플을 넣고, 끝났으면 실제로 돌아가고, 판정한다. */
    fun tick(nowMs: Long): Reading {
        sim?.let { s ->
            s.pumpTo(nowMs, gate)
            if (s.isOver(nowMs)) {
                sim = null
                gate = realGate()
            }
        }
        reading = gate.evaluate(nowMs)
        return reading
    }

    /** 정지한 사건 id(최근 [STOPPED_MEMORY] 개, 감시가 도는 동안). 그 사건의 늦게 온 시작을 버린다. */
    private val stoppedEvents = LinkedHashSet<String>()

    /**
     * 받은 지시를 처리하고, 경보처럼 기기에서 해야 할 동작을 돌려준다. 사건 id 가 실린 경보 지시는 아래 규칙을 거친다(계약 한 판 준비 · C-2).
     * 거른 지시도 ack 는 한다([CommandProcessor] 가 이미 실었다). 사건 id 가 없는 옛 지시는 지금 규칙 그대로다.
     * - 정지한 사건 id 의 시작은 단계 · 강도와 무관하게 버린다(정지 뒤 늦게 온 2단계가 다시 울리지 않게).
     * - 같은 사건 · 단계 · 강도가 울리는 중이면 다시 걸지 않는다(같은 경보가 두 길로 와도 진동이 덜컥 다시 걸리지 않게).
     * - 사건 id 가 있는 정지는 지금 울리는 경보의 사건 id 가 같을 때만 듣는다. 울리는 경보가 사건 id 없는 옛 지시면 끈다
     *   (비교할 id 가 없다 — 끝없이 울리는 것보다 낫다). 정지한 사건 id 는 울리는 경보와 관계없이 기억한다.
     */
    fun handle(received: List<Command>, nowMs: Long): List<WatchAction> {
        val kept = ArrayList<WatchAction>()
        for (a in commands.accept(received)) {
            when (a) {
                is WatchAction.StartAlert -> {
                    if (!acceptStart(a)) continue
                    alert = ActiveAlert(a.commandId, a.alert, a.test, nowMs, eventId = a.eventId)
                }
                is WatchAction.StopAlert -> {
                    if (!acceptStop(a)) continue
                    calmScenario(nowMs)
                    alert = null
                }
                is WatchAction.RunScenario -> startScenario(a.scenario, a.durationMs, nowMs)
                WatchAction.StopScenario -> stopScenario(nowMs)
            }
            kept += a
        }
        return kept
    }

    private fun acceptStart(a: WatchAction.StartAlert): Boolean {
        val id = a.eventId ?: return true
        if (id in stoppedEvents) return false
        val cur = alert ?: return true
        return !(cur.eventId == id && cur.payload.stage == a.alert.stage && cur.payload.level == a.alert.level)
    }

    private fun acceptStop(a: WatchAction.StopAlert): Boolean {
        val id = a.eventId ?: return true
        stoppedEvents.remove(id)
        stoppedEvents += id
        while (stoppedEvents.size > STOPPED_MEMORY) stoppedEvents.remove(stoppedEvents.first())
        val curId = alert?.eventId ?: return true
        return curId == id
    }

    /** 워치 화면 「멈추기」 · TEST_SCENARIO_STOP — 시나리오를 끝내고 실제 출처로 돌아간다(관문은 기준선부터). 없으면 그대로. */
    fun stopScenario(nowMs: Long) {
        if (sim == null) return
        sim = null
        gate = realGate()
        reading = gate.evaluate(nowMs)
    }

    fun startScenario(scenario: Scenario, durationMs: Long, nowMs: Long) {
        sim = SimulationSource.create(scenario, durationMs, nowMs, simulator)
        gate = GateRunner()
        reading = gate.evaluate(nowMs)
    }

    /** 워치 버튼 · 손목 움직임 응답. 경보는 ALERT_STOP 이 올 때까지 계속한다(계약 §4.6 · 설계 §14). */
    fun respond(nowMs: Long, wallMs: Long, nextResponseSeq: (() -> Long)? = null) {
        responseAtWall = wallMs
        // 응답 번호는 경보가 울리는 동안의 누름만 올린다 — 경보 밖 누름이 늦게 보여 다음 경보를 끄지 않게(be-dev 제안 · B-5)
        if (alert != null && nextResponseSeq != null) responseSeq = nextResponseSeq()
        alert = alert?.copy(respondedAtMs = nowMs)
        calmScenario(nowMs)
    }

    /**
     * 테스트 시나리오에서 울린 경보에 사람이 응답했다 — 워치 버튼 · 손목은 [respond] 로 바로, 휴대폰 · 차 화면 응답은
     * 흐름이 응답 뒤(S1R · S2)로 가며 내는 ALERT_STOP 으로 안다. 남은 시간은 정상 신호를 새 관문에 넣어
     * 응답 뒤 재경보(나쁜 판정 30초 지속 · PULSE_ABSENT 악화)가 나지 않게 한다. 시나리오 표시 · 끝 시각 · 테스트 표시는 그대로다.
     * 실제 신호의 재경보 규칙(:shared 흐름 기계)은 바꾸지 않는다. 이 시나리오 전에 울린 경보의 ALERT_STOP 은 무시한다.
     */
    private fun calmScenario(nowMs: Long) {
        val s = sim ?: return
        val a = alert ?: return
        if (s.calmed || a.startedAtMs < s.startAtMs) return
        sim = s.calm(nowMs, simulator)
        gate = GateRunner()
        reading = gate.evaluate(nowMs)
    }

    /**
     * 실제 출처의 관문. Health Services 는 1초 심박만 있어 40 근처 흔들림 · 깜빡임에 서맥 판정이 지워지므로
     * 이탈 문턱을 45 로 올린다(지움 = 45 이상 3초 또는 40 이상 5초, 합동 점검 B-1). Sensor SDK 는 지금 규칙 그대로다.
     * 시뮬레이션 관문은 기본값이다(시나리오 파형은 Sensor SDK 경로를 흉내 낸다).
     */
    private fun realGate() = GateRunner(if (realSource == WatchSource.HEALTH_SERVICES) HS_GATE else GateParams())

    /** 짝이 끝났거나 감시를 끌 때 — 경보를 거둔다. */
    fun clearAlert() {
        alert = null
    }

    /** 보낼 워치 상태. 원신호는 싣지 않는다 — 계약 §4.3 필드만(G2). 시각은 벽시계 에포크 ms 로 바꿔 싣는다. */
    fun watch(clocks: Clocks): Watch = Watch(
        verdict = reading.verdict,
        hr = reading.hr,
        lastPulseAt = lastPulseWall(clocks),
        worn = reading.worn,
        source = source,
        test = sim != null,
        responseAt = responseAtWall,
        noSignal = sim == null && noSignal,
        responseSeq = responseSeq,
        // 새 워치는 alerting 키를 늘 싣는다(안 울리면 null) — 키가 없으면 주인이 옛 워치로 보고 다시 내기를 하지 않는다
        alerting = alerting,
        alertingReported = true,
    )

    private fun lastPulseWall(clocks: Clocks): Long? {
        val mono = reading.lastPulseAtMs ?: return null
        if (mono != pulseMono) {
            pulseMono = mono
            pulseWall = clocks.toWall(mono)
        }
        return pulseWall
    }

    companion object {
        /** Health Services 경로 관문 매개변수(B-1 — be-dev `GateParams.bradyExitBpm`). */
        val HS_GATE = GateParams(bradyExitBpm = 45)

        /** 정지한 사건 id 기억 개수(app-wear 3차 표결 C-2). */
        const val STOPPED_MEMORY = 16
    }
}
