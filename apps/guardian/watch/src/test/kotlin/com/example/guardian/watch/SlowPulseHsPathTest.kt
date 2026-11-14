// Health Services 경로 느린 맥 시험 — 어댑터(유예 · 보류 held)와 엔진의 관문(이탈 문턱 45)을 함께 돌려 be-qa 2차 열을 고정한다(합동 점검 B-1)
package com.example.guardian.watch

import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.watch.core.WatchEngine
import com.example.guardian.watch.sensor.HsHeartAdapter
import com.example.guardian.watch.sensor.HsHeartAdapter.Availability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowPulseHsPathTest {
    private fun ch(v: WristVerdict) = when (v) {
        WristVerdict.NORMAL -> 'N'; WristVerdict.PULSE_WEAK -> 'W'; WristVerdict.CHECK_WEAR -> 'C'; WristVerdict.PULSE_ABSENT -> 'A'
    }

    /**
     * 0초부터 [endS] 초까지 1초마다 — [events] 로 그 초의 가용성 · 심박을 어댑터에 넣고, 어댑터 tick, 엔진 판정을 한 글자로 적는다.
     * 감시 서비스와 같은 순서다(HS 콜백 → 1초 tick 의 어댑터 tick → 엔진 tick).
     */
    private fun line(endS: Int, source: WatchSource = WatchSource.HEALTH_SERVICES, events: (Long, HsHeartAdapter) -> Unit): String {
        val e = WatchEngine(source, lowConfidence = true)
        val a = HsHeartAdapter(0, e::hr)
        a.onAvailability(Availability.AVAILABLE, 0)
        val sb = StringBuilder()
        for (sec in 0..endS) {
            val t = sec * 1_000L
            events(t, a)
            a.tick(t)
            sb.append(ch(e.tick(t).verdict))
        }
        return sb.toString()
    }

    private fun firstW(s: String) = s.indexOf('W')

    /** R1 — 35회/분 + 9초마다 측정 중 1초. */
    @Test fun bradyWithAcquiringBlips() {
        val l = line(40) { t, a ->
            if (t % 10_000 == 9_000L) a.onAvailability(Availability.ACQUIRING, t)
            else {
                if (t % 10_000 == 0L) a.onAvailability(Availability.AVAILABLE, t)
                a.onBpm(35.0, t)
            }
        }
        assertTrue("첫 W 10~12초 — $l", firstW(l) in 10..12)
        assertTrue("한 번 나면 이어짐 — $l", l.substring(firstW(l)).all { it == 'W' })
    }

    /** R2 — 38 · 39 · 38 · 41 흔들림. 41 하나가 서맥을 지우지 않는다. */
    @Test fun oscillating38to41() {
        val bpms = listOf(38.0, 39.0, 38.0, 41.0)
        val l = line(40) { t, a -> a.onBpm(bpms[((t / 1000) % 4).toInt()], t) }
        assertTrue("첫 W 10초대 — $l", firstW(l) in 10..19)
    }

    /** R3 — 5초 묶음 전달, 10초에 70 → 35. 70 인 동안은 판정이 없고, 느린 맥은 20초대에 난다. */
    @Test fun fiveSecondBatches70to35() {
        val l = line(60) { t, a ->
            if (t >= 5_000 && t % 5_000 == 0L) for (s in t - 5_000 until t step 1_000) a.onBpm(if (s < 10_000) 70.0 else 35.0, s)
        }
        assertTrue("첫 W 20초대 — $l", firstW(l) in 20..29)
    }

    /** V2 — 35회/분 20초 뒤 42 로 회복. 40~44 띠는 5초 이어져야 풀린다. */
    @Test fun recoveryTo42ClearsAfter5s() {
        val l = line(40) { t, a -> a.onBpm(if (t < 20_000) 35.0 else 42.0, t) }
        assertEquals("회복 직전까지 W — $l", 'W', l[19])
        assertEquals("42 가 4초째까지는 W — $l", "WWWWW", l.substring(20, 25))
        assertTrue("5초 뒤 풀림 — $l", l.substring(25).all { it == 'N' })
    }

    /** F1 — 42 안정에 39 한 번은 판정 없음. */
    @Test fun stable42WithSingle39IsNotBrady() {
        val l = line(40) { t, a -> a.onBpm(if (t == 5_000L) 39.0 else 42.0, t) }
        assertEquals(-1, firstW(l))
    }

    /** 보류 표본에는 held 가 붙는다 — 묶음 사이 보류와 깜빡임 유예 보류 둘 다. 실제 표본에는 붙지 않는다. */
    @Test fun heldSamplesAreMarked() {
        val out = mutableListOf<HrSample>()
        val a = HsHeartAdapter(0) { out += it }
        a.onBpm(60.0, 0)
        a.tick(1_000) // 묶음 사이 보류
        a.onAvailability(Availability.ACQUIRING, 1_500)
        a.tick(2_500) // 유예 보류
        a.tick(5_000) // 유예 넘음 — 신호 약함(보류 아님)
        assertEquals(listOf(false, true, true, true, false), out.map { it.held })
    }

    /** Sensor SDK · 시뮬레이션 경로는 이탈 문턱을 바꾸지 않는다 — 41 하나로 지운다(지금 동작). */
    @Test fun sensorSdkPathKeepsOldClear() {
        val bpms = listOf(38.0, 39.0, 38.0, 41.0)
        val l = line(40, source = WatchSource.SENSOR_SDK) { t, a -> a.onBpm(bpms[((t / 1000) % 4).toInt()], t) }
        assertEquals(-1, firstW(l))
    }
}
