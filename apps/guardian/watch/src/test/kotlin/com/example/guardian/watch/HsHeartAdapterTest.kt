// Health Services 심박 변환 시험 — bpm 은 상태 정상 + 간격 하나로, 착용 안 함은 1초마다 상태 −3 으로 다시 싣고, 유효 심박 10초 공백 · 등록 실패는 신호 없음
package com.example.guardian.watch

import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.HrStatus
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.watch.core.GateRunner
import com.example.guardian.watch.sensor.HsHeartAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HsHeartAdapterTest {
    @Test fun bpmBecomesOkSampleWithInterval() {
        val out = mutableListOf<HrSample>()
        val a = HsHeartAdapter(0) { out += it }
        a.onAvailability(HsHeartAdapter.Availability.AVAILABLE, 0)
        a.onBpm(75.0, 1_000)
        assertEquals(listOf(HrSample(1_000, 75, HrStatus.OK, listOf(800))), out)
    }

    @Test fun offBodyRepeatsEverySecondAndGateSaysCheckWear() {
        val gate = GateRunner()
        val a = HsHeartAdapter(0, gate::hr)
        a.onAvailability(HsHeartAdapter.Availability.OFF_BODY, 0)
        for (t in 500L..10_000L step 500) a.tick(t)
        // 관문은 3초 넘은 심박을 버린다 — 다시 싣지 않으면 10초 뒤 판정이 「착용 확인」에서 풀린다
        assertEquals(WristVerdict.CHECK_WEAR, gate.evaluate(10_000).verdict)
    }

    // ── 신호 없음(코드 리뷰 2026-11-02 상 1) ──

    /** 재현 — 측정 중(ACQUIRING)이 이어지면 관문은 NORMAL 인데(PPG 없이 느슨함을 못 봄), 10초 넘으면 신호 없음이어야 한다. */
    @Test fun acquiringFor10sIsNoSignalWhileGateStillSaysNormal() {
        val gate = GateRunner()
        val a = HsHeartAdapter(0, gate::hr)
        a.onAvailability(HsHeartAdapter.Availability.ACQUIRING, 0)
        for (t in 1_000L..10_000L step 1_000) {
            a.tick(t)
            assertFalse("$t ms — 10초까지는 아직 아님", a.noSignal(t))
        }
        assertEquals("관문만 보면 정상으로 보인다(결함의 뿌리)", WristVerdict.NORMAL, gate.evaluate(10_000).verdict)
        a.tick(10_001)
        assertTrue("10초 넘김", a.noSignal(10_001))
        // 쓸 수 없음(UNAVAILABLE)도 같다
        a.onAvailability(HsHeartAdapter.Availability.UNAVAILABLE, 12_000)
        assertTrue(a.noSignal(12_000))
    }

    /** 가용성 콜백이 하나도 안 와도(시작 뒤 아무 값 없음) 시작 시각 기준 10초 뒤 신호 없음. */
    @Test fun noCallbackAtAllIsNoSignalAfter10sFromStart() {
        val a = HsHeartAdapter(5_000) { }
        assertFalse(a.noSignal(15_000))
        assertTrue(a.noSignal(15_001))
    }

    @Test fun heartRateComingBackClearsNoSignalAndGapCountsFromLastBeat() {
        val a = HsHeartAdapter(0) { }
        a.onAvailability(HsHeartAdapter.Availability.ACQUIRING, 0)
        assertTrue(a.noSignal(11_000))
        a.onBpm(72.0, 11_500)
        assertFalse("정상 bpm 이 오면 바로 풀림", a.noSignal(11_500))
        // 그 뒤 값이 끊기면 마지막 유효 심박 기준으로 다시 센다
        assertFalse(a.noSignal(21_500))
        assertTrue(a.noSignal(21_501))
        a.onBpm(70.0, 22_000)
        assertFalse(a.noSignal(22_000))
    }

    @Test fun zeroOrNonFiniteBpmIsNotAValidBeat() {
        val a = HsHeartAdapter(0) { }
        a.onAvailability(HsHeartAdapter.Availability.AVAILABLE, 0)
        a.onBpm(0.0, 9_000)
        a.onBpm(Double.NaN, 9_500)
        assertTrue(a.noSignal(10_001))
    }

    /** 착용 안 함은 신호 없음이 아니다 — 관문 CHECK_WEAR 그대로(받는 쪽이 「착용 확인」을 띄운다). */
    @Test fun offBodyIsCheckWearNotNoSignal() {
        val gate = GateRunner()
        val a = HsHeartAdapter(0, gate::hr)
        a.onAvailability(HsHeartAdapter.Availability.OFF_BODY, 0)
        for (t in 1_000L..30_000L step 1_000) a.tick(t)
        assertFalse(a.noSignal(30_000))
        assertEquals(WristVerdict.CHECK_WEAR, gate.evaluate(30_000).verdict)
        // 착용 안 함에서 측정 중으로 돌아오면 마지막 유효 심박(없음 → 시작) 기준이라 바로 신호 없음이다
        a.onAvailability(HsHeartAdapter.Availability.ACQUIRING, 31_000)
        assertTrue(a.noSignal(31_000))
    }

    @Test fun registrationFailureIsNoSignalAtOnce() {
        val a = HsHeartAdapter(0) { }
        a.onRegistrationFailed()
        assertTrue(a.noSignal(0))
        assertTrue("착용 안 함 콜백이 와도 등록 실패는 그대로", run { a.onAvailability(HsHeartAdapter.Availability.OFF_BODY, 1_000); a.noSignal(1_000) })
    }

    // ── 묶음 전달(운동 세션, 화면 꺼짐 — 주행 3 결함 11-02) ──

    /**
     * 재현 — 1초 간격으로 잰 심박이 5초마다 묶음으로 오면, 관문은 3초 넘은 심박을 버리므로(`hrStaleMs`) 묶음 사이에
     * 심박 숫자가 비고 A3 판정도 못 한다. 어댑터가 마지막 유효 심박을 1초마다 다시 실어 관문에는 늘 신선하게 보여야 한다.
     */
    @Test fun fiveSecondBatchesKeepGateHeartRateEverySecond() {
        val gate = GateRunner()
        val a = HsHeartAdapter(0, gate::hr)
        a.onAvailability(HsHeartAdapter.Availability.AVAILABLE, 0)
        val missing = mutableListOf<Long>()
        for (t in 1_000L..60_000L step 1_000) {
            if (t % 5_000 == 0L) for (s in t - 4_000..t step 1_000) a.onBpm(72.0, s) // 5초치 묶음(샘플 자체 시각)
            a.tick(t)
            val r = gate.evaluate(t)
            assertEquals("$t ms 판정", WristVerdict.NORMAL, r.verdict)
            if (t >= 5_000 && r.hr == null) missing += t
            assertFalse("$t ms 신호 없음 아님", a.noSignal(t))
        }
        assertEquals("묶음 사이에 심박이 비는 초가 없어야 한다", emptyList<Long>(), missing)
    }

    /** 보류는 신호 없음 창(10초)까지만 — 그 뒤 관문 심박은 비고 신호 없음이 된다(실제 끊김은 숨기지 않음). */
    @Test fun heldHeartRateStopsAtNoSignalWindow() {
        val gate = GateRunner()
        val a = HsHeartAdapter(0, gate::hr)
        a.onAvailability(HsHeartAdapter.Availability.AVAILABLE, 0)
        a.onBpm(70.0, 5_000)
        for (t in 6_000L..15_000L step 1_000) {
            a.tick(t)
            assertEquals("$t ms — 10초 안은 보류", 70, gate.evaluate(t).hr)
            assertFalse(a.noSignal(t))
        }
        for (t in 16_000L..19_000L step 1_000) {
            a.tick(t)
            assertTrue("$t ms 신호 없음", a.noSignal(t))
        }
        // 마지막 보류(15초)가 관문 유효 시간 3초를 지나면 심박이 빈다
        assertNull("10초 넘으면 더 싣지 않아 관문 심박이 빈다", gate.evaluate(19_000).hr)
    }

    /** 묶음 안에서 시각이 뒤섞여 와도 마지막 유효 심박 시각은 뒤로 가지 않는다. */
    @Test fun olderSampleDoesNotMoveLastBeatBackwards() {
        val a = HsHeartAdapter(0) { }
        a.onAvailability(HsHeartAdapter.Availability.AVAILABLE, 0)
        a.onBpm(72.0, 10_000)
        a.onBpm(70.0, 8_000)
        assertFalse(a.noSignal(20_000))
        assertTrue(a.noSignal(20_001))
    }

    // ── 가용성 깜빡임 유예(합동 점검 B-1) ──

    /** 1초 전달 [bpm] 열에 [acquiringEvery10s] 면 10초마다 9초째에 측정 중 1초를 끼운다. 첫 PULSE_WEAK 시각(없으면 null). */
    private fun firstWeakAt(bpm: Double, endMs: Long = 60_000, acquiringEvery10s: Boolean = false): Long? {
        val gate = GateRunner()
        val a = HsHeartAdapter(0, gate::hr)
        a.onAvailability(HsHeartAdapter.Availability.AVAILABLE, 0)
        for (t in 0L..endMs step 1_000) {
            if (acquiringEvery10s && t % 10_000 == 9_000L) {
                a.onAvailability(HsHeartAdapter.Availability.ACQUIRING, t)
            } else {
                if (acquiringEvery10s && t % 10_000 == 0L) a.onAvailability(HsHeartAdapter.Availability.AVAILABLE, t)
                a.onBpm(bpm, t)
            }
            a.tick(t)
            if (gate.evaluate(t).verdict == WristVerdict.PULSE_WEAK) return t
        }
        return null
    }

    /**
     * 재현(be-qa 2차 R1) — 35회/분이 이어지는데 Health Services 가 9초마다 1초씩 「측정 중」을 내면, 지금은 그때마다 신호 약함
     * 표본이 서맥 시작을 지워 느린 맥 판정(10초 지속)이 끝내 나지 않는다. 3초 유예로 마지막 유효 심박을 보류하면 12초 안에 난다.
     */
    @Test fun shortAcquiringBlipsDoNotResetSlowPulse() {
        val at = firstWeakAt(35.0, acquiringEvery10s = true)
        assertTrue("12초 안에 느린 맥 판정 — 실제 $at", at != null && at <= 12_000)
    }

    /** 유예는 3초까지 — 그 안은 마지막 유효 심박(상태 정상)을 1초마다 다시 싣고, 4초째부터 신호 약함을 싣는다. */
    @Test fun acquiringLongerThan3sBecomesWeakSignal() {
        val out = mutableListOf<HrSample>()
        val a = HsHeartAdapter(0) { out += it }
        a.onAvailability(HsHeartAdapter.Availability.AVAILABLE, 0)
        for (t in 0L..10_000L step 1_000) { a.onBpm(35.0, t); a.tick(t) }
        a.onAvailability(HsHeartAdapter.Availability.ACQUIRING, 10_500)
        for (t in 11_000L..15_000L step 1_000) a.tick(t)
        fun statusAt(t: Long) = out.last { it.atMs == t }.status
        for (t in listOf(10_500L, 12_000L, 13_000L)) { // 11초는 10.5초에 실은 뒤 1초가 안 돼 건너뜀
            assertEquals("$t ms — 유예 안은 보류", HrStatus.OK, statusAt(t))
            assertEquals(35, out.last { it.atMs == t }.bpm)
        }
        assertEquals("측정 중 3.5초 — 신호 약함", HrStatus.WEAK_SIGNAL, statusAt(14_000))
        assertEquals(HrStatus.WEAK_SIGNAL, statusAt(15_000))
    }

    /** 측정 중 ↔ 쓸 수 없음을 오가도 유예는 처음 떠난 때부터 잰다(늘어나지 않음). */
    @Test fun graceIsNotExtendedByAcquiringUnavailableFlips() {
        val out = mutableListOf<HrSample>()
        val a = HsHeartAdapter(0) { out += it }
        a.onBpm(60.0, 0)
        a.onAvailability(HsHeartAdapter.Availability.ACQUIRING, 1_000)
        a.onAvailability(HsHeartAdapter.Availability.UNAVAILABLE, 3_000)
        a.tick(4_000)
        assertEquals(HrStatus.OK, out.last().status)
        a.onAvailability(HsHeartAdapter.Availability.ACQUIRING, 4_500)
        assertEquals("떠난 지 3.5초 — 신호 약함", HrStatus.WEAK_SIGNAL, out.last().status)
    }

    /** 미착용은 유예 없이 바로 착용 확인이다. */
    @Test fun offBodyHasNoGrace() {
        val out = mutableListOf<HrSample>()
        val a = HsHeartAdapter(0) { out += it }
        a.onBpm(60.0, 0)
        a.onAvailability(HsHeartAdapter.Availability.OFF_BODY, 500)
        assertEquals(HrStatus.OFF_WRIST, out.last().status)
    }

    // ── IBI 표시값 아래쪽만 묶기(합동 점검 B-3) ──

    /** 241회/분 이상은 간격이 250ms 아래로 내려가 「유효 박동 아님」이 되던 것을 250 으로 묶는다. 30 미만은 2000 넘는 그대로. */
    @Test fun intervalIsClampedOnlyFromBelow() {
        val expected = mapOf(20.0 to 3_000, 25.0 to 2_400, 29.0 to 2_069, 241.0 to 250, 250.0 to 250)
        for ((bpm, ibi) in expected) {
            val out = mutableListOf<HrSample>()
            val a = HsHeartAdapter(0) { out += it }
            a.onBpm(bpm, 1_000)
            assertEquals("$bpm 회/분", listOf(ibi), out.single().ibiMs)
        }
    }

    /** 241 · 250회/분이 이어져도 박동 공백(느린 맥 판정)으로 오판하지 않는다 — 고치기 전에는 4초쯤 PULSE_WEAK 가 났다. */
    @Test fun fastRateIsNotMistakenForPulseGap() {
        for (bpm in listOf(241.0, 250.0)) assertNull("$bpm 회/분", firstWeakAt(bpm))
    }

    /** 30회/분 미만은 지금처럼 박동 공백으로 빨리 잡는다(간격을 위쪽까지 묶으면 10초로 늦어진다 — be-qa 2차). */
    @Test fun veryLowRateStillCaughtAsPulseGap() {
        for (bpm in listOf(20.0, 25.0, 29.0)) {
            val at = firstWeakAt(bpm)
            assertTrue("$bpm 회/분 — 5초 안 — 실제 $at", at != null && at <= 5_000)
        }
    }
}
