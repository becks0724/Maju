// 손목 신호 관문(설계 §3) — A1 진폭 급감 · A2 손목 정지 · A3 박동 공백 · B 두 파장 확인 · 착용 검사로 손목 판정을 낸다
package com.example.guardian.shared.bio

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 샘플을 시간순으로 넣고 [evaluate] 로 판정을 받는다. 시각은 샘플 · 인자로만 받는다(시계 의존 없음).
 * 원신호는 이 객체 안에서만 쓰고 밖으로 내보내지 않는다(G2) — 결과는 판정 · 이유 · 마지막 맥박 시각 · 심박만.
 *
 * 판정 규칙.
 * - 심박 상태 −3 → CHECK_WEAR. −2 → 판정 보류(NORMAL + MOTION_HOLD).
 * - 심박 상태 −8 · −10 이면서 적색 DC 가 착용 기준에서 벗어남 → CHECK_WEAR(LOOSE).
 * - (A1 또는 A3) + A2 + B → PULSE_ABSENT, (A1 또는 A3) 만 → PULSE_WEAK, 나머지 NORMAL.
 */
class PulseGate(val params: GateParams = GateParams()) {
    private val p = params
    private val greenBp = BandPass(p.fs, p.bandLowHz, p.bandHighHz)
    private val irBp = BandPass(p.fs, p.bandLowHz, p.bandHighHz)

    private class Filtered(val atMs: Long, val green: Double, val ir: Double)
    private val filtered = ArrayDeque<Filtered>()

    // A1 창
    private var windowIndex = Long.MIN_VALUE
    private val windowValues = ArrayList<Double>()
    private var windowStartMs = 0L
    private val pulseWindows = ArrayDeque<Double>()
    private var lowStreak = 0
    private var lastPulseWindowEndMs: Long? = null

    // DC 1초 평균
    private var dcSecond = Long.MIN_VALUE
    private var dcGreenSum = 0.0; private var dcRedSum = 0.0; private var dcCount = 0
    private var prevGreenDc: Double? = null
    private var currentRedDc: Double? = null
    private val redRef = ArrayDeque<Double>()
    private var settleUntilMs = Long.MIN_VALUE

    // A2
    private class Mag(val atMs: Long, val v: Double)
    private val accel = ArrayDeque<Mag>()

    // 심박
    private var latestHr: HrSample? = null
    private var firstHrAtMs: Long? = null
    private var lastValidIbiAtMs: Long? = null
    private var bradySinceMs: Long? = null

    private val expectedPerWindow = (p.acWindowMs * p.fs / 1000).roundToInt()

    private var lastPpgAtMs = Long.MIN_VALUE
    private var lastAccelAtMs = Long.MIN_VALUE
    private var lastHrAtMs = Long.MIN_VALUE

    /**
     * 시각이 직전 샘플보다 거꾸로 간 샘플은 그 샘플만 버린다 — 지금 3초 창 · DC 평균 · 필터를 건드리지 않는다(PM 결정 2026-11-08).
     * 심박 · 가속도도 같은 규칙이다(코드 리뷰 11-09).
     */
    fun addPpg(s: PpgSample) {
        if (s.atMs < lastPpgAtMs) return
        lastPpgAtMs = s.atMs
        trackDc(s)
        val g = greenBp.process(s.green)
        val i = irBp.process(s.ir)
        filtered.addLast(Filtered(s.atMs, g, i))
        while (filtered.isNotEmpty() && filtered.first().atMs <= s.atMs - p.bWindowMs) filtered.removeFirst()
        trackWindow(s.atMs, g)
    }

    fun addAccel(s: AccelSample) {
        if (s.atMs < lastAccelAtMs) return
        lastAccelAtMs = s.atMs
        accel.addLast(Mag(s.atMs, sqrt(s.x * s.x + s.y * s.y + s.z * s.z)))
        while (accel.isNotEmpty() && accel.first().atMs <= s.atMs - p.stillWindowMs) accel.removeFirst()
    }

    /**
     * 순서 시계([lastHrAtMs])는 실제 심박 표본(보류 아님 · 상태 정상 · bpm > 0)만 민다 — 상태만 있는 표본 · 보류 표본이 뒤에 온 묶음의
     * 과거 실제 표본을 버리게 하지 않는다(합동 점검 B-1). 신선도([latestHr])는 시각이 더 새 표본으로만 바꾼다.
     * 보류 표본([HrSample.held])은 신선도와 박동 시각만 갱신하고 서맥 시작 · 지움 시간은 세지 않는다.
     */
    fun addHr(s: HrSample) {
        if (s.atMs < lastHrAtMs) return
        val newest = latestHr?.let { s.atMs >= it.atMs } ?: true
        val ok = s.status == HrStatus.OK
        if (s.held) {
            if (newest) latestHr = s
            if (ok && s.ibiMs.any { it in 250..2000 }) lastValidIbiAtMs = maxOf(lastValidIbiAtMs ?: s.atMs, s.atMs)
            return
        }
        val real = ok && s.bpm > 0
        if (!real && !newest) return // 이미 받은 것보다 오래된 상태 표본
        if (real) lastHrAtMs = s.atMs
        if (firstHrAtMs == null) firstHrAtMs = s.atMs
        if (newest) latestHr = s
        if (ok && s.ibiMs.any { it in 250..2000 }) lastValidIbiAtMs = maxOf(lastValidIbiAtMs ?: s.atMs, s.atMs)
        trackBrady(s, ok)
    }

    // 느린 맥 지움 — 이탈 문턱 이상이 이어지기 시작한 시각 · 진입 문턱 이상이 이어지기 시작한 시각
    private var exitSinceMs: Long? = null
    private var bandSinceMs: Long? = null

    /** 서맥 시작 · 지움. 「OK 아님」은 바로 지운다(깜빡임 유예는 어댑터 몫). 이탈 문턱이 진입 문턱과 같으면 40 이상 하나로 바로 지운다. */
    private fun trackBrady(s: HrSample, ok: Boolean) {
        if (!ok) { bradySinceMs = null; exitSinceMs = null; bandSinceMs = null; return }
        if (s.bpm < p.bradyBpm) {
            bradySinceMs = bradySinceMs ?: s.atMs
            exitSinceMs = null; bandSinceMs = null
            return
        }
        bandSinceMs = bandSinceMs ?: s.atMs
        // 이탈 문턱 이상의 시계는 40 미만(위)에서만 다시 시작한다 — 40~44 하나가 끊으면 거짓 W 가 는다(be-qa 관문 2 실행 — 1초 24 → 13/3000)
        exitSinceMs = if (s.bpm >= p.bradyExitBpm) (exitSinceMs ?: s.atMs) else exitSinceMs
        val clear = p.bradyExitBpm <= p.bradyBpm ||
            exitSinceMs?.let { s.atMs - it >= p.bradyExitHoldMs } == true ||
            s.atMs - bandSinceMs!! >= p.bradyBandClearMs
        if (clear) bradySinceMs = null
    }

    fun evaluate(nowMs: Long, driving: Boolean): GateResult {
        val hr = latestHr?.takeIf { nowMs - it.atMs <= p.hrStaleMs }
        val hrOut = hr?.takeIf { it.status == HrStatus.OK && it.bpm > 0 }?.bpm
        val lastPulse = listOfNotNull(lastPulseWindowEndMs, lastValidIbiAtMs).maxOrNull()
        fun result(v: WristVerdict, r: Set<GateReason>) = GateResult(v, r, lastPulse, hrOut)

        when (hr?.status) {
            HrStatus.OFF_WRIST -> return result(WristVerdict.CHECK_WEAR, setOf(GateReason.OFF_WRIST))
            HrStatus.MOTION -> return result(WristVerdict.NORMAL, setOf(GateReason.MOTION_HOLD))
            HrStatus.WEAK_SIGNAL, HrStatus.WEAK_SIGNAL_2 ->
                if (looksLoose()) return result(WristVerdict.CHECK_WEAR, setOf(GateReason.LOOSE))
        }

        val reasons = mutableSetOf<GateReason>()
        if (nowMs < settleUntilMs) reasons += GateReason.BASELINE_RESET
        val established = pulseWindows.size >= p.baselineMinWindows
        if (!established) reasons += GateReason.WARMING_UP

        val a1 = established && lowStreak >= p.acDropWindows
        val gapRef = lastValidIbiAtMs ?: firstHrAtMs
        val a3 = hr?.status == HrStatus.OK && (
            (gapRef != null && nowMs - gapRef >= p.beatGapMs) ||
                (bradySinceMs != null && nowMs - bradySinceMs!! >= p.bradyMs))
        val a2 = wristStill(nowMs, driving)
        val b = noPulseBoth()
        if (a1) reasons += GateReason.A1_AMPLITUDE_DROP
        if (a2) reasons += GateReason.A2_WRIST_STILL
        if (a3) reasons += GateReason.A3_BEAT_GAP
        if (b) reasons += GateReason.B_NO_PULSE_BOTH

        val verdict = when {
            (a1 || a3) && a2 && b -> WristVerdict.PULSE_ABSENT
            a1 || a3 -> WristVerdict.PULSE_WEAK
            else -> WristVerdict.NORMAL
        }
        return result(verdict, reasons)
    }

    /** A2 — 가속도 크기 표준편차가 주행 · 정차 기준 아래. 창이 덜 찼으면 false. */
    fun wristStill(nowMs: Long, driving: Boolean): Boolean {
        val v = accel.filter { it.atMs > nowMs - p.stillWindowMs }.map { it.v }
        if (v.size < p.stillWindowMs * p.fs / 1000 * p.minCoverage) return false
        return Spectral.std(v) < if (driving) p.thetaDrive else p.thetaPark
    }

    // ── 기준 비교(경계값 시험용으로 따로 둔다) ──

    /** B — 대역 SNR 이 θ_snr 아래. */
    internal fun snrLow(v: List<Double>) = Spectral.bandSnr(v, p.fs, p.pulseLowBpm / 60, p.pulseHighBpm / 60) < p.thetaSnr

    /** B — 자기상관 최대가 θ_ac 아래. */
    internal fun acLow(v: List<Double>) =
        Spectral.maxAutocorr(v, (p.acLagMinS * p.fs).roundToInt(), (p.acLagMaxS * p.fs).roundToInt()) < p.thetaAc

    /** A1 — 창 AC 가 기준의 10% 이하. */
    internal fun isLowWindow(ac: Double, baseline: Double) = ac <= p.acDropRatio * baseline

    /** 느슨함 — 적색 DC 가 기준에서 40% 넘게 벗어남. */
    internal fun isLooseRatio(cur: Double, ref: Double) = abs(cur / ref - 1) > p.looseRedRatio

    /** DC 급변 — 1초 평균이 직전보다 30% 넘게 바뀜. */
    internal fun isDcJump(prev: Double, cur: Double) = abs(cur - prev) / prev > p.dcJumpRatio

    private fun noPulseBoth(): Boolean {
        if (filtered.size < p.bWindowMs * p.fs / 1000 * p.minCoverage) return false
        // 가벼운 자기상관을 먼저 — 정상 맥박이면 무거운 DFT(snrLow)를 건너뛴다. 둘 다 순수 계산이라 결과는 같다.
        fun noPulse(v: List<Double>): Boolean = acLow(v) && snrLow(v)
        return noPulse(filtered.map { it.green }) && noPulse(filtered.map { it.ir })
    }

    private fun looksLoose(): Boolean {
        val cur = currentRedDc ?: return false
        if (redRef.size < 3) return false
        val ref = Spectral.median(redRef.toList())
        if (ref <= 0) return false
        return isLooseRatio(cur, ref)
    }

    private fun trackDc(s: PpgSample) {
        val sec = Math.floorDiv(s.atMs, 1000L)
        if (sec != dcSecond) {
            if (dcCount > 0) finishDcSecond(s.atMs)
            dcSecond = sec; dcGreenSum = 0.0; dcRedSum = 0.0; dcCount = 0
        }
        dcGreenSum += s.green; dcRedSum += s.red; dcCount++
    }

    private fun finishDcSecond(atMs: Long) {
        val green = dcGreenSum / dcCount
        val red = dcRedSum / dcCount
        currentRedDc = red
        val prev = prevGreenDc
        prevGreenDc = green
        if (prev != null && prev > 0 && isDcJump(prev, green)) {
            resetBaseline(atMs)
            return
        }
        val hrOk = latestHr?.let { it.status == HrStatus.OK && atMs - it.atMs <= p.hrStaleMs } == true
        if (hrOk) {
            redRef.addLast(red)
            while (redRef.size > p.baselineWindows) redRef.removeFirst()
        }
    }

    private fun resetBaseline(atMs: Long) {
        pulseWindows.clear(); lowStreak = 0
        filtered.clear(); windowValues.clear(); windowIndex = Long.MIN_VALUE
        greenBp.reset(); irBp.reset()
        settleUntilMs = atMs + p.settleMs
    }

    private fun trackWindow(atMs: Long, g: Double) {
        val idx = Math.floorDiv(atMs, p.acWindowMs)
        if (idx != windowIndex) {
            if (windowIndex != Long.MIN_VALUE) finishWindow()
            windowIndex = idx; windowStartMs = idx * p.acWindowMs; windowValues.clear()
        }
        windowValues += g
    }

    private fun finishWindow() {
        if (windowStartMs < settleUntilMs) return
        if (windowValues.size < expectedPerWindow * p.minCoverage) return
        val ac = Spectral.std(windowValues)
        val windowEnd = windowStartMs + p.acWindowMs
        // 심박 상태가 정상이고 낡지 않았을 때만 맥박 창으로 쌓는다([finishDcSecond] 의 hrOk 와 같은 규칙, 리뷰 의심 하2)
        val hrOk = latestHr?.let { it.status == HrStatus.OK && windowEnd - it.atMs <= p.hrStaleMs } == true
        if (pulseWindows.size >= p.baselineMinWindows) {
            val baseline = Spectral.median(pulseWindows.toList())
            if (isLowWindow(ac, baseline)) { lowStreak++; return }
            lowStreak = 0
        }
        if (hrOk) {
            pulseWindows.addLast(ac)
            while (pulseWindows.size > p.baselineWindows) pulseWindows.removeFirst()
            lastPulseWindowEndMs = windowEnd
        }
    }
}
