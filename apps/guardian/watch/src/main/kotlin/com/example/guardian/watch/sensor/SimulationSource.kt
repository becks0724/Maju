// 테스트 모드 출처 — :shared Simulator 가 만든 합성 샘플을 실제 시각에 맞춰 실제와 같은 관문에 흘려 넣는다
package com.example.guardian.watch.sensor

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.SimTrace
import com.example.guardian.shared.bio.Simulator

/**
 * TEST_SCENARIO 하나의 재생. 샘플 시각은 [startAtMs](단조)부터다. [pumpTo] 를 부를 때마다 그 시각까지의 샘플을 넣는다.
 * 관문을 건너뛰지 않는다(설계 §3) — 판정은 받은 쪽([SampleSink])의 관문이 한다.
 */
class SimulationSource private constructor(
    val scenario: Scenario,
    val startAtMs: Long,
    val durationMs: Long,
    private val trace: SimTrace,
    /** 응답 뒤 남은 시간을 정상 신호로 바꾼 재생인가([calm]). */
    val calmed: Boolean = false,
) {
    private var ip = 0
    private var ia = 0
    private var ih = 0

    fun pumpTo(nowMs: Long, sink: SampleSink) {
        while (ip < trace.ppg.size && trace.ppg[ip].atMs <= nowMs) sink.ppg(trace.ppg[ip++])
        while (ia < trace.accel.size && trace.accel[ia].atMs <= nowMs) sink.accel(trace.accel[ia++])
        while (ih < trace.hr.size && trace.hr[ih].atMs <= nowMs) sink.hr(trace.hr[ih++])
    }

    fun isOver(nowMs: Long) = nowMs >= startAtMs + durationMs

    /** 사람이 응답한 뒤 — 시나리오 표시 · 시작 · 끝 시각은 그대로 두고, [nowMs] 부터 끝까지는 정상 신호를 재생한다. */
    fun calm(nowMs: Long, simulator: Simulator): SimulationSource {
        val left = (startAtMs + durationMs - nowMs).coerceAtLeast(0)
        val normal = simulator.generate(Scenario.NORMAL, durationMs = left, startAtMs = nowMs)
        return SimulationSource(scenario, startAtMs, durationMs, normal, calmed = true)
    }

    companion object {
        /** 기준선(5창 · 15초)이 잡힌 뒤 변화가 오도록 20초에 사건을 둔다. 짧은 시나리오는 절반 지점. */
        const val EVENT_AT_MS = 20_000L

        /** 지시의 durationMs 를 이 범위로 묶는다(네트워크 값 — 너무 짧거나 끝없는 테스트를 막음). */
        const val MIN_DURATION_MS = 5_000L
        const val MAX_DURATION_MS = 600_000L

        fun create(scenario: Scenario, durationMs: Long, nowMs: Long, simulator: Simulator = Simulator()): SimulationSource {
            val d = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            val trace = simulator.generate(scenario, durationMs = d, eventAtMs = minOf(EVENT_AT_MS, d / 2), startAtMs = nowMs)
            return SimulationSource(scenario, nowMs, d, trace)
        }
    }
}
