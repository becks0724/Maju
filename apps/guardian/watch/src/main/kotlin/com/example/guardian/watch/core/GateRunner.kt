// :shared 신호 관문을 워치 판정 모드(주행 기준 고정)로 돌려 워치 상태 값(verdict · hr · lastPulseAt · worn)을 만든다
package com.example.guardian.watch.core

import com.example.guardian.shared.bio.AccelSample
import com.example.guardian.shared.bio.GateParams
import com.example.guardian.shared.bio.GateReason
import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.PpgSample
import com.example.guardian.shared.bio.PulseGate
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.watch.sensor.SampleSink

/** 한 번의 판정 결과. 시각은 단조 ms. */
data class Reading(
    val verdict: WristVerdict,
    val hr: Int?,
    val lastPulseAtMs: Long?,
    val worn: Boolean,
    val reasons: Set<GateReason>,
) {
    companion object {
        val INITIAL = Reading(WristVerdict.NORMAL, null, null, worn = true, reasons = setOf(GateReason.WARMING_UP))
    }
}

/** 판정은 :shared [PulseGate] 그대로다. 앱은 샘플을 넣고 결과를 옮겨 담기만 한다. */
class GateRunner(params: GateParams = GateParams()) : SampleSink {
    private val gate = PulseGate(params)

    override fun ppg(s: PpgSample) = gate.addPpg(s)
    override fun accel(s: AccelSample) = gate.addAccel(s)
    override fun hr(s: HrSample) = gate.addHr(s)

    fun evaluate(nowMs: Long): Reading {
        val r = gate.evaluate(nowMs, driving = GATE_DRIVING)
        return Reading(r.verdict, r.hr, r.lastPulseAtMs, worn = GateReason.OFF_WRIST !in r.reasons, reasons = r.reasons)
    }

    companion object {
        /**
         * 관문 모드는 늘 주행 기준(θ_drive)이다(PM 결정 2026-10-30).
         * - 워치는 차 상태(속도 · 기어)를 직접 받지 못한다.
         * - 엔진이 켜진 정차 진동에서도 움직임 없는 손목을 A2 「정지」로 잡아야 놓침이 준다(θ_park 는 차 진동에서 정지를 못 잡는다).
         * - 기준을 넓혀 생길 수 있는 오판은 B 두 파장 확인(녹색 · 적외선 둘 다 맥 없음)이 막는다.
         * 실기기 · 차 진동 실측 뒤 다시 본다.
         */
        const val GATE_DRIVING = true
    }
}
