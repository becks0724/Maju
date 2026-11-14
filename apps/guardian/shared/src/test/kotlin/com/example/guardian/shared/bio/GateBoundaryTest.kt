// 관문 기준값 경계 시험(θ_snr · θ_ac · A1 10% · θ_drive · θ_park · 느슨함 0.4 · DC 급변 0.3)과 be-qa 변형 시나리오
package com.example.guardian.shared.bio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class GateBoundaryTest {
    private val gate = PulseGate()
    private val p = gate.params

    @Test fun defaultsAreDesignValues() {
        assertEquals(1.5, p.thetaSnr, 0.0); assertEquals(0.7, p.thetaAc, 0.0); assertEquals(0.10, p.acDropRatio, 0.0)
        assertEquals(0.35, p.thetaDrive, 0.0); assertEquals(0.05, p.thetaPark, 0.0)
        assertEquals(0.4, p.looseRedRatio, 0.0); assertEquals(0.3, p.dcJumpRatio, 0.0)
    }

    /** 두 정현파가 정확히 DFT 칸에 놓이면 SNR = A²/B² 이다(새는 전력 없음). */
    private fun twoTone(ratio: Double): List<Double> {
        val a = sqrt(ratio)
        return (0 until 250).map { val t = it / 25.0; a * sin(2 * PI * 1.0 * t) + sin(2 * PI * 2.5 * t) }
    }

    @Test fun snrBoundary() {
        assertEquals(1.49, Spectral.bandSnr(twoTone(1.49), 25.0, 40.0 / 60, 220.0 / 60), 1e-6)
        assertTrue("1.49 < 1.5", gate.snrLow(twoTone(1.49)))
        assertFalse("1.51 ≥ 1.5", gate.snrLow(twoTone(1.51)))
        assertFalse("같으면 낮지 않음", PulseGate(GateParams(thetaSnr = Spectral.bandSnr(twoTone(1.5), 25.0, 40.0 / 60, 220.0 / 60))).snrLow(twoTone(1.5)))
    }

    @Test fun autocorrBoundary() {
        val v = (0 until 250).map { sin(2 * PI * 1.3 * it / 25.0) + 0.8 * sin(2 * PI * 3.1 * it / 25.0 + 1) }
        val ac = Spectral.maxAutocorr(v, 7, 38)
        assertTrue(PulseGate(GateParams(thetaAc = ac + 1e-9)).acLow(v))
        assertFalse("같으면 낮지 않음", PulseGate(GateParams(thetaAc = ac)).acLow(v))
        assertFalse(PulseGate(GateParams(thetaAc = ac - 1e-9)).acLow(v))
        // 기본값 0.7 바로 위 · 아래 — 잡음 섞는 양을 맞춰 자기상관 최대를 0.69 · 0.71 로 만든다
        val tone = (0 until 250).map { sin(2 * PI * 1.3 * it / 25.0) }
        val below = scaleAc(tone, 0.69); val above = scaleAc(tone, 0.71)
        assertEquals(0.69, Spectral.maxAutocorr(below, 7, 38), 1e-3)
        assertEquals(0.71, Spectral.maxAutocorr(above, 7, 38), 1e-3)
        assertTrue(gate.acLow(below))
        assertFalse(gate.acLow(above))
    }

    /** 같은 시드의 잡음을 섞는 양을 이분 탐색해 자기상관 최대를 [target] 에 맞춘다. */
    private fun scaleAc(v: List<Double>, target: Double): List<Double> {
        var lo = 0.0; var hi = 100.0
        repeat(80) {
            val mid = (lo + hi) / 2
            val ac = Spectral.maxAutocorr(mix(v, mid), 7, 38)
            if (ac > target) lo = mid else hi = mid
        }
        return mix(v, (lo + hi) / 2)
    }

    private fun mix(v: List<Double>, w: Double): List<Double> {
        val r = java.util.Random(11)
        return v.map { it + w * r.nextGaussian() }
    }

    @Test fun a1TenPercentBoundary() {
        assertTrue("정확히 10% 는 급감", gate.isLowWindow(10.0, 100.0))
        assertTrue(gate.isLowWindow(9.99, 100.0))
        assertFalse(gate.isLowWindow(10.01, 100.0))
    }

    /** 가속도 크기를 9.81 ± a 로 번갈아 주면 표준편차가 정확히 a 다. */
    private fun stillWith(std: Double, driving: Boolean): Boolean {
        val g = PulseGate()
        for (i in 0 until 250) g.addAccel(AccelSample(i * 40L, 0.0, 0.0, 9.81 + if (i % 2 == 0) std else -std))
        return g.wristStill(250 * 40L - 40, driving)
    }

    @Test fun thetaDriveAndParkBoundary() {
        assertTrue(stillWith(0.349, driving = true)); assertFalse(stillWith(0.351, driving = true))
        assertTrue(stillWith(0.049, driving = false)); assertFalse(stillWith(0.051, driving = false))
        assertFalse("주행 기준으로는 정지인 진동도 정차 기준으로는 움직임", stillWith(0.2, driving = false))
        assertTrue(stillWith(0.2, driving = true))
    }

    @Test fun looseRatioBoundary() {
        assertFalse(gate.isLooseRatio(0.61, 1.0)); assertTrue(gate.isLooseRatio(0.59, 1.0))
        assertFalse(gate.isLooseRatio(1.39, 1.0)); assertTrue(gate.isLooseRatio(1.41, 1.0))
    }

    @Test fun dcJumpBoundary() {
        assertFalse(gate.isDcJump(100.0, 129.0)); assertTrue(gate.isDcJump(100.0, 131.0))
        assertFalse(gate.isDcJump(100.0, 71.0)); assertTrue(gate.isDcJump(100.0, 69.0))
    }

    // ── be-qa 변형 시나리오(QaGateTest 를 단언으로 옮김) ──

    private fun after(r: List<Pair<Long, GateResult>>) = r.filter { it.first >= 47_000 }.map { it.second.verdict }

    @Test fun nonLossScenariosNeverAbsentUnderVibrationOrDriving() {
        for (sc in listOf(Scenario.NORMAL, Scenario.LOOSE, Scenario.NOT_WORN, Scenario.MOTION, Scenario.AMPLITUDE_DROP))
            for (vib in listOf(0.0, 0.15, 0.5)) for (drv in listOf(true, false)) for (seed in 1L..3L) {
                val v = after(Simulator(seed).generate(sc, carVibrationStd = vib).replay(PulseGate(), drv))
                assertFalse("$sc vib=$vib driving=$drv", WristVerdict.PULSE_ABSENT in v)
                if (sc != Scenario.AMPLITUDE_DROP) assertFalse("$sc 은 약해짐도 아님", WristVerdict.PULSE_WEAK in v)
            }
    }

    @Test fun pulseLostWithVibration() {
        fun last(vib: Double, drv: Boolean) = Simulator(1).generate(Scenario.PULSE_LOST, carVibrationStd = vib).replay(PulseGate(), drv).last().second.verdict
        assertEquals(WristVerdict.PULSE_ABSENT, last(0.0, false))
        assertEquals(WristVerdict.PULSE_ABSENT, last(0.15, true))
        // 정차 기준(θ_park)보다 큰 진동이나 θ_drive 보다 큰 진동이면 손목 정지가 아니라 약해짐에 머문다 — 보수적 [추정]
        assertEquals(WristVerdict.PULSE_WEAK, last(0.15, false))
        assertEquals(WristVerdict.PULSE_WEAK, last(0.5, true))
    }

    @Test fun mildLooseIsNotPulseLoss() {
        for (factor in listOf(1.4, 1.6, 1.2)) {
            val t = Simulator(3).generate(Scenario.LOOSE)
            val ppg = t.ppg.map { if (it.atMs >= 45_000) it.copy(red = it.red * factor) else it }
            val v = after(SimTrace(ppg, t.accel, t.hr).replay(PulseGate(), false))
            assertFalse("적색 DC ≈${0.5 * factor}", WristVerdict.PULSE_ABSENT in v || WristVerdict.PULSE_WEAK in v)
        }
    }

    @Test fun hrStreamStopped() {
        val lost = Simulator(5).generate(Scenario.PULSE_LOST)
        val v = after(SimTrace(lost.ppg, lost.accel, lost.hr.filter { it.atMs < 45_000 }).replay(PulseGate(), false))
        assertEquals("심박 상태 없이도 PPG 로 소실 판정", WristVerdict.PULSE_ABSENT, v.last())
        val normal = Simulator(5).generate(Scenario.NORMAL)
        val v2 = after(SimTrace(normal.ppg, normal.accel, normal.hr.filter { it.atMs < 45_000 }).replay(PulseGate(), false))
        assertTrue(v2.all { it == WristVerdict.NORMAL })
    }
}
