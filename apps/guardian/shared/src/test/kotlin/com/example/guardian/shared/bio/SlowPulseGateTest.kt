// 느린 맥(A3 서맥) 관문 시험 — 합동 점검 B-1 재현(be-qa r2gate): 깜빡임 · 38↔41 흔들림 · 5초 묶음 · 40~44 회복 · 보류 표본 · 순서 시계
package com.example.guardian.shared.bio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowPulseGateTest {
    /** Health Services 경로 — 이탈 문턱 45(지움은 45 이상 3초 또는 40 이상 5초). */
    private val hs = GateParams(bradyExitBpm = 45)

    private fun ok(at: Long, bpm: Int, held: Boolean = false) = HrSample(at, bpm, HrStatus.OK, listOf(60_000 / bpm), held)
    private fun weak(at: Long) = HrSample(at, 0, HrStatus.WEAK_SIGNAL)

    private fun ch(v: WristVerdict) = when (v) {
        WristVerdict.NORMAL -> 'N'; WristVerdict.PULSE_WEAK -> 'W'; WristVerdict.CHECK_WEAR -> 'C'; WristVerdict.PULSE_ABSENT -> 'A'
    }

    /** 0초부터 [endS] 초까지 1초마다 그 초에 도착한 표본을 넣고 판정을 한 글자로 적는다. PPG · 가속도는 없다(A3 만 본다). */
    private fun line(p: GateParams, endS: Int, arrivals: (Long) -> List<HrSample>): String {
        val g = PulseGate(p)
        val sb = StringBuilder()
        for (sec in 0..endS) {
            val t = sec * 1_000L
            arrivals(t).forEach(g::addHr)
            sb.append(ch(g.evaluate(t, driving = true).verdict))
        }
        return sb.toString()
    }

    private fun firstW(s: String) = s.indexOf('W')

    @Test fun bradyWithFlickerHeldByAdapterStillAlertsIn10s() {
        // R1 — 35bpm, 9초마다 측정 중 1초. 어댑터 유예가 그 1초를 보류 표본(마지막 실제 표본의 다시 싣기)으로 메운다
        val l = line(hs, 40) { t -> listOf(ok(t, 35, held = t % 10_000 == 9_000L)) }
        assertEquals("첫 W 10초 — $l", 10, firstW(l))
        assertTrue("보류 표본이 서맥 시작을 지우지 않는다 — $l", l.substring(10).all { it == 'W' })
    }

    @Test fun notOkStatusStillClearsBradyAtOnceInGate() {
        // 「OK 아님」 유예는 어댑터 몫이다 — 관문은 지금처럼 바로 지운다(app-wear 와 합의, 겹쳐 6초가 되지 않게)
        val l = line(hs, 40) { t -> listOf(if (t % 10_000 == 9_000L) weak(t) else ok(t, 35)) }
        assertEquals("상태 −8 이 9초마다 서맥 시작을 지움 — $l", -1, firstW(l))
    }

    @Test fun oscillating38to41IsBradyOnHsPath() {
        // R2 — 38 · 39 · 38 · 41 반복. 41 하나가 서맥을 지우면 끝내 W 가 나지 않았다
        val bpms = listOf(38, 39, 38, 41)
        val l = line(hs, 40) { t -> listOf(ok(t, bpms[((t / 1000) % 4).toInt()])) }
        assertEquals("첫 W 10초 — $l", 10, firstW(l))
    }

    @Test fun sensorSdkPathKeepsOldClearRule() {
        // 기본값(이탈 문턱 = 진입 문턱 40) — 40 이상 표본 하나로 바로 지운다(Sensor SDK 경로 동작 그대로)
        val bpms = listOf(38, 39, 38, 41)
        assertEquals(-1, firstW(line(GateParams(), 40) { t -> listOf(ok(t, bpms[((t / 1000) % 4).toInt()])) }))
        val v = line(GateParams(), 40) { t -> listOf(ok(t, if (t < 20_000) 35 else 42)) }
        assertEquals('W', v[19]); assertEquals('N', v[20])
    }

    @Test fun batchedDeliveryDoesNotDropPastSamples() {
        // R3 — 5초 묶음, 10초에 70 → 35. 묶음 사이에는 어댑터가 마지막 실제 표본을 지금 시각으로 보류해 싣는다.
        // 보류 표본이 순서 시계를 밀면 뒤에 온 묶음의 과거 표본(10~14초의 35)이 버려져 첫 W 가 늦어졌다
        fun bpm(s: Long) = if (s < 10_000) 70 else 35
        val l = line(hs, 40) { t ->
            if (t < 5_000) emptyList()
            else {
                val batch = if (t % 5_000 == 0L) (t - 5_000 until t step 1_000).map { ok(it, bpm(it)) } else emptyList()
                val lastReal = (t / 5_000) * 5_000 - 1_000
                batch + ok(t, bpm(lastReal), held = true)
            }
        }
        assertTrue("70bpm 동안 W 없음 — $l", l.substring(0, 15).none { it == 'W' })
        assertEquals("첫 W 20초(1초 전달과 같음) — $l", 20, firstW(l))
    }

    @Test fun recoveryTo42ClearsAfter5s() {
        // V2 — 35bpm 20초 → 42bpm. 40~44 띠도 5초 이어지면 지운다(띠 안 회복이 끝내 풀리지 않던 구멍)
        val l = line(hs, 40) { t -> listOf(ok(t, if (t < 20_000) 35 else 42)) }
        assertEquals("띠 안 4초까지는 W — $l", 'W', l[24])
        assertTrue("25초부터 N — $l", l.substring(25).all { it == 'N' })
    }

    @Test fun recoveryTo60ClearsAfter3s() {
        val l = line(hs, 40) { t -> listOf(ok(t, if (t < 20_000) 35 else 60)) }
        assertEquals('W', l[22])
        assertTrue("23초부터 N — $l", l.substring(23).all { it == 'N' })
    }

    @Test fun bandSampleDoesNotBreakExitRun() {
        // be-qa 관문 2 — 45 · 45 · 43 · 45 는 이탈 첫 표본(20초)부터 3초 뒤 지움. 43 하나가 45 이상 연속을 끊으면 거짓 W 가 늘었다
        val seq = mapOf(20_000L to 45, 21_000L to 45, 22_000L to 43, 23_000L to 45)
        val l = line(hs, 30) { t -> listOf(ok(t, seq[t] ?: if (t < 20_000) 35 else 45)) }
        assertEquals('W', l[22])
        assertEquals("23초에 지움 — $l", 'N', l[23])
    }

    @Test fun stable42WithOne39IsNotBrady() {
        // F1 · F2 — 42 안정에 39 한 번, 43~44 흔들림에 20초마다 39 한 번은 거짓 W 가 아니다
        assertEquals(-1, firstW(line(hs, 60) { t -> listOf(ok(t, if (t == 5_000L) 39 else 42)) }))
        assertEquals(-1, firstW(line(hs, 60) { t ->
            listOf(ok(t, if (t % 20_000 == 5_000L) 39 else if ((t / 1000) % 2 == 0L) 43 else 44))
        }))
    }

    @Test fun heldSamplesKeepBeatTimeButDoNotStartBrady() {
        val g = PulseGate(hs)
        g.addHr(ok(0, 70))
        // 보류 표본(35 를 지금 시각으로) — 박동 시각은 갱신해 A3 박동 공백을 내지 않지만, 서맥 시작은 세지 않는다
        for (t in 1_000L..20_000L step 1_000) g.addHr(ok(t, 35, held = true))
        val r = g.evaluate(20_000, true)
        assertEquals(WristVerdict.NORMAL, r.verdict)
        assertEquals(20_000L, r.lastPulseAtMs)
    }

    @Test fun statusOnlySampleDoesNotPushOrderClock() {
        // 순서 시계는 실제 심박 표본만 민다 — 상태만 있는 표본 뒤에 온 과거 실제 표본을 버리지 않는다
        val g = PulseGate()
        g.addHr(ok(10_000, 70))
        g.addHr(weak(20_000))
        g.addHr(ok(15_000, 70))
        assertEquals("상태 표본 뒤의 과거 실제 표본을 받음", 15_000L, g.evaluate(20_000, true).lastPulseAtMs)
        // 실제 표본끼리는 지금처럼 거꾸로 간 표본을 버린다
        g.addHr(ok(12_000, 70))
        g.addHr(HrSample(12_500, 70, HrStatus.OK, listOf(857)))
        assertEquals(15_000L, g.evaluate(20_000, true).lastPulseAtMs)
    }

    @Test fun heldSampleDoesNotPushOrderClock() {
        val g = PulseGate()
        g.addHr(ok(10_000, 70))
        g.addHr(ok(20_000, 35, held = true))
        g.addHr(ok(15_000, 35))
        // 15초의 실제 35 가 받아져 서맥 시작이 15초 — 25초에 W
        assertEquals(WristVerdict.NORMAL, g.evaluate(15_000, true).verdict)
        g.addHr(ok(25_000, 35))
        assertEquals(WristVerdict.PULSE_WEAK, g.evaluate(25_000, true).verdict)
    }
}
