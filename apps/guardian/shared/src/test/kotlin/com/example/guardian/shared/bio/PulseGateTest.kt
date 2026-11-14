// 신호 관문 시험 — 시나리오 6종 판정, 느슨함 · 미착용이 맥 소실로 오판되지 않음, 주행 진동에서 손목 정지가 아님
package com.example.guardian.shared.bio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseGateTest {
    private val event = 45_000L
    private val warm = 20_000L // 기준(5창 · 15초)이 잡힌 뒤부터 본다

    private fun run(s: Scenario, driving: Boolean = false, vib: Double = 0.0, seed: Long = 42) =
        Simulator(seed).generate(s, eventAtMs = event, carVibrationStd = vib).replay(PulseGate(), driving)

    private fun verdicts(r: List<Pair<Long, GateResult>>, from: Long, to: Long = Long.MAX_VALUE) =
        r.filter { it.first in from..to }.map { it.second.verdict }

    @Test fun normalStaysNormal() {
        for (seed in 1L..5L) {
            val r = run(Scenario.NORMAL, seed = seed)
            assertTrue(verdicts(r, warm).all { it == WristVerdict.NORMAL })
            assertEquals(72, r.last().second.hr)
        }
    }

    @Test fun warmingUpReportedBeforeBaseline() {
        val r = run(Scenario.NORMAL)
        assertTrue(GateReason.WARMING_UP in r.first().second.reasons)
        assertEquals(WristVerdict.NORMAL, r.first().second.verdict)
    }

    @Test fun pulseLossGoesWeakThenAbsentWithin15s() {
        for (seed in 1L..5L) {
            val r = run(Scenario.PULSE_LOST, seed = seed)
            assertTrue("소실 전에는 정상", verdicts(r, warm, event).all { it == WristVerdict.NORMAL })
            val firstAbsent = r.first { it.second.verdict == WristVerdict.PULSE_ABSENT }.first
            assertTrue("15초 안 소실 판정 $firstAbsent", firstAbsent - event in 1..15_000)
            val firstWeak = r.first { it.second.verdict == WristVerdict.PULSE_WEAK }.first
            assertTrue("약해짐이 먼저", firstWeak < firstAbsent)
            assertTrue("소실 뒤 유지", verdicts(r, firstAbsent).all { it == WristVerdict.PULSE_ABSENT })
            val last = r.last().second
            assertTrue(GateReason.A1_AMPLITUDE_DROP in last.reasons)
            assertTrue(GateReason.A2_WRIST_STILL in last.reasons)
            assertTrue(GateReason.B_NO_PULSE_BOTH in last.reasons)
            val lastPulse = assertNotNullL(last.lastPulseAtMs)
            assertTrue("마지막 맥박 시각은 소실 무렵 $lastPulse", lastPulse in (event - 4_000)..(event + 3_000))
        }
    }

    @Test fun pulseLossWhileDrivingWithCarVibrationIsAbsent() {
        val r = run(Scenario.PULSE_LOST, driving = true, vib = 0.15)
        assertEquals(WristVerdict.PULSE_ABSENT, r.last().second.verdict)
    }

    @Test fun amplitudeDropIsWeakNotAbsent() {
        for (seed in 1L..5L) {
            val r = run(Scenario.AMPLITUDE_DROP, seed = seed)
            val after = verdicts(r, event + 15_000)
            assertTrue(after.all { it == WristVerdict.PULSE_WEAK })
            assertFalse("맥이 아직 보이면 두 파장 확인이 막는다", GateReason.B_NO_PULSE_BOTH in r.last().second.reasons)
        }
    }

    @Test fun looseIsCheckWearNotAbsent() {
        for (seed in 1L..5L) {
            val r = run(Scenario.LOOSE, seed = seed)
            val after = verdicts(r, event + 2_000)
            assertFalse(after.any { it == WristVerdict.PULSE_ABSENT || it == WristVerdict.PULSE_WEAK })
            assertEquals(WristVerdict.CHECK_WEAR, after.last())
            assertTrue(GateReason.LOOSE in r.last().second.reasons)
        }
    }

    @Test fun offWristIsCheckWearNotAbsent() {
        val r = run(Scenario.NOT_WORN)
        val after = verdicts(r, event + 1_000)
        assertTrue(after.all { it == WristVerdict.CHECK_WEAR })
        assertTrue(GateReason.OFF_WRIST in r.last().second.reasons)
    }

    @Test fun motionIsHeldNotAbsent() {
        val r = run(Scenario.MOTION)
        val after = r.filter { it.first >= event + 1_000 }
        assertTrue(after.all { it.second.verdict == WristVerdict.NORMAL && GateReason.MOTION_HOLD in it.second.reasons })
    }

    @Test fun driveVibrationIsNotStillAndNormal() {
        for (seed in 1L..5L) {
            val r = run(Scenario.DRIVE_VIBRATION, driving = true, seed = seed)
            val after = r.filter { it.first >= warm }
            assertTrue(after.all { GateReason.A2_WRIST_STILL !in it.second.reasons })
            assertTrue(after.all { it.second.verdict == WristVerdict.NORMAL })
        }
    }

    @Test fun parkedRestingWristIsStill() {
        val gate = PulseGate()
        Simulator().generate(Scenario.NORMAL, durationMs = 15_000).replay(gate, driving = false)
        assertTrue(gate.wristStill(14_000, driving = false))
    }

    @Test fun beatGapA3WhenStatusOkWithoutIbi() {
        val gate = PulseGate()
        gate.addHr(HrSample(0, 70, HrStatus.OK, listOf(850)))
        for (t in 1_000L..3_000L step 1_000) gate.addHr(HrSample(t, 70, HrStatus.OK))
        assertFalse(GateReason.A3_BEAT_GAP in gate.evaluate(3_999, false).reasons)
        gate.addHr(HrSample(4_000, 70, HrStatus.OK))
        val r = gate.evaluate(4_000, false)
        assertTrue(GateReason.A3_BEAT_GAP in r.reasons)
        assertEquals(WristVerdict.PULSE_WEAK, r.verdict)
    }

    @Test fun bradycardiaA3After10s() {
        val gate = PulseGate()
        for (t in 0L..9_000L step 1_000) gate.addHr(HrSample(t, 35, HrStatus.OK, listOf(1700)))
        assertFalse(GateReason.A3_BEAT_GAP in gate.evaluate(9_999, false).reasons)
        gate.addHr(HrSample(10_000, 35, HrStatus.OK, listOf(1700)))
        assertTrue(GateReason.A3_BEAT_GAP in gate.evaluate(10_000, false).reasons)
    }

    @Test fun dcJumpResetsBaseline() {
        val gate = PulseGate()
        val trace = Simulator().generate(Scenario.NORMAL, durationMs = 30_000)
        trace.replay(gate, false)
        // 센서 자동 이득으로 DC 가 두 배가 된 1초
        for (i in 0 until 50) gate.addPpg(PpgSample(30_000L + i * 40, 100_000.0, 120_000.0, 80_000.0))
        val r = gate.evaluate(32_000, false)
        assertTrue(GateReason.BASELINE_RESET in r.reasons)
        assertTrue(GateReason.WARMING_UP in r.reasons)
        assertEquals(WristVerdict.NORMAL, r.verdict)
    }

    @Test fun simulatorIsDeterministic() {
        val a = Simulator(7).generate(Scenario.PULSE_LOST)
        val b = Simulator(7).generate(Scenario.PULSE_LOST)
        assertEquals(a, b)
    }

    @Test fun bandPassRemovesDcAndKeepsPulse() {
        val bp = BandPass()
        val out = (0 until 500).map { bp.process(50_000 + 100 * kotlin.math.sin(2 * Math.PI * 1.2 * it / 25.0)) }
        val tail = out.drop(250)
        assertTrue(kotlin.math.abs(tail.average()) < 5)
        assertTrue(Spectral.std(tail) in 50.0..80.0)
    }

    /** 11-02 남은 하(PM 결정 그 샘플만 버림) — 시각이 거꾸로 간 PPG 샘플이 3초 창을 지우지 않는다. */
    @Test fun backwardPpgSampleIsDroppedAlone() {
        val trace = Simulator().generate(Scenario.NORMAL, durationMs = 30_000)
        val gate = PulseGate()
        var ih = 0
        for ((i, s) in trace.ppg.withIndex()) {
            while (ih < trace.hr.size && trace.hr[ih].atMs <= s.atMs) gate.addHr(trace.hr[ih++])
            gate.addPpg(s)
            if (i % 25 == 24) gate.addPpg(s.copy(atMs = s.atMs - 2_000)) // 1초마다 2초 거꾸로 간 샘플
        }
        val r = gate.evaluate(29_960, false)
        assertFalse("기준이 잡혀야 한다 ${r.reasons}", GateReason.WARMING_UP in r.reasons)
        assertEquals(WristVerdict.NORMAL, r.verdict)
    }

    /** 의심 하2 — 창 마감도 심박 샘플 나이를 본다. 3초 넘게 낡은 「정상」 심박으로는 맥박 기준 창을 쌓지 않는다. */
    @Test fun staleHrDoesNotBuildPulseBaseline() {
        val gate = PulseGate()
        gate.addHr(HrSample(0, 72, HrStatus.OK, listOf(833)))
        Simulator().generate(Scenario.NORMAL, durationMs = 30_000).ppg.forEach(gate::addPpg)
        val r = gate.evaluate(29_960, false)
        assertTrue("낡은 심박으로 기준을 잡으면 안 됨 ${r.reasons}", GateReason.WARMING_UP in r.reasons)
        assertNull(r.lastPulseAtMs?.takeIf { it > 3_000 })
    }

    /** 코드 리뷰 11-09 하(의심) — 시각이 거꾸로 간 심박 샘플도 버린다. 늦게 온 옛 「미착용」이 지금 판정을 바꾸지 않는다. */
    @Test fun backwardHrSampleIsDropped() {
        val gate = PulseGate()
        gate.addHr(HrSample(10_000, 72, HrStatus.OK, listOf(833)))
        gate.addHr(HrSample(9_000, 0, HrStatus.OFF_WRIST))
        assertEquals(WristVerdict.NORMAL, gate.evaluate(10_500, false).verdict)
        assertEquals(72, gate.evaluate(10_500, false).hr)
    }

    /** 같은 규칙 — 시각이 거꾸로 간 가속도 샘플은 손목 정지(A2) 창에 넣지 않는다. */
    @Test fun backwardAccelSampleIsDropped() {
        val gate = PulseGate()
        for (i in 0..250) gate.addAccel(AccelSample(i * 40L, 0.0, 0.0, 9.8))
        assertTrue(gate.wristStill(10_000, driving = false))
        gate.addAccel(AccelSample(9_500, 30.0, 30.0, 30.0))
        assertTrue("거꾸로 간 큰 흔들림 한 개가 창에 들어가면 안 됨", gate.wristStill(10_000, driving = false))
    }

    private fun assertNotNullL(v: Long?): Long { assertNotNull(v); return v!! }
}
