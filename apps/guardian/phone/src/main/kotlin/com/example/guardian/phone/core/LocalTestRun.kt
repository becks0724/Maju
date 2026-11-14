// 워치 없이 휴대폰 안에서 테스트 모드를 돌리는 판정 생성기 — :shared Simulator 시나리오를 실제와 같은 PulseGate 에 넣는다
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.GateResult
import com.example.guardian.shared.bio.PulseGate
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.Simulator
import com.example.guardian.shared.bio.WristVerdict

/**
 * [startMono] 부터 시나리오를 재생한다. 처음 [EVENT_AT_MS] 는 정상, 그 뒤 시나리오 변화(관문 기준이 잡히는 데 15초 남짓 걸린다).
 * [stopped] 는 차 상태 흉내(정차 · 주행) — 관문의 손목 정지 기준(θ_park · θ_drive)도 이에 맞춘다.
 */
class LocalTestRun(val scenario: Scenario, private val startMono: Long, val stopped: Boolean) {
    private val trace = Simulator().generate(scenario, durationMs = DURATION_MS, eventAtMs = EVENT_AT_MS)
    private val gate = PulseGate()
    private var ip = 0
    private var ia = 0
    private var ih = 0
    var last: GateResult? = null
        private set

    fun verdictAt(nowMono: Long): WristVerdict {
        val t = (nowMono - startMono).coerceIn(0, DURATION_MS)
        while (ip < trace.ppg.size && trace.ppg[ip].atMs <= t) gate.addPpg(trace.ppg[ip++])
        while (ia < trace.accel.size && trace.accel[ia].atMs <= t) gate.addAccel(trace.accel[ia++])
        while (ih < trace.hr.size && trace.hr[ih].atMs <= t) gate.addHr(trace.hr[ih++])
        return gate.evaluate(t, driving = !stopped).also { last = it }.verdict
    }

    companion object {
        const val DURATION_MS = 120_000L
        const val EVENT_AT_MS = 20_000L
    }
}
