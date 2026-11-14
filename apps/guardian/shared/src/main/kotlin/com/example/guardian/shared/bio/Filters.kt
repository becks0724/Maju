// PPG 전처리 — 2차 Butterworth 바이쿼드(고역 · 저역)를 이은 대역통과와 스펙트럼 · 자기상관 계산
package com.example.guardian.shared.bio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ 바이쿼드 한 단(직접형 I). Q = 1/√2 이면 2차 Butterworth. */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    /** 첫 입력값을 정상 상태로 보고 내부 상태를 맞춘다(시작 · 기준 재설정 때 큰 과도 응답 방지). */
    fun primeWith(x: Double) {
        val dcGain = (b0 + b1 + b2) / (1 + a1 + a2)
        x1 = x; x2 = x; y1 = x * dcGain; y2 = x * dcGain
    }

    companion object {
        private const val Q_BUTTERWORTH = 0.7071067811865476

        fun lowPass(fs: Double, fc: Double): Biquad {
            val w = 2 * PI * fc / fs
            val alpha = sin(w) / (2 * Q_BUTTERWORTH)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun highPass(fs: Double, fc: Double): Biquad {
            val w = 2 * PI * fc / fs
            val alpha = sin(w) / (2 * Q_BUTTERWORTH)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

/** 0.5–4Hz 대역통과(25Hz) — 2차 Butterworth 고역 + 2차 Butterworth 저역. */
class BandPass(fs: Double = 25.0, low: Double = 0.5, high: Double = 4.0) {
    private val hp = Biquad.highPass(fs, low)
    private val lp = Biquad.lowPass(fs, high)
    private var primed = false

    fun process(x: Double): Double {
        if (!primed) { hp.primeWith(x); lp.primeWith(0.0); primed = true }
        return lp.process(hp.process(x))
    }

    fun reset() { primed = false }
}

object Spectral {
    fun std(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * 대역 [fLow, fHigh] 안 최대 봉우리(±1 칸) 전력 ÷ 나머지 대역 전력. 직접 DFT, 해상도 fs/N.
     * 신호가 거의 없으면 0 을 돌려준다.
     */
    fun bandSnr(v: List<Double>, fs: Double, fLow: Double, fHigh: Double): Double {
        val n = v.size
        if (n < 8) return 0.0
        val m = v.average()
        val x = DoubleArray(n) { v[it] - m }
        val kLow = kotlin.math.ceil(fLow * n / fs).toInt()
        val kHigh = kotlin.math.floor(fHigh * n / fs).toInt()
        if (kHigh - kLow < 3) return 0.0
        val p = DoubleArray(kHigh - kLow + 1)
        for (k in kLow..kHigh) {
            var re = 0.0; var im = 0.0
            val w = 2 * PI * k / n
            for (i in 0 until n) { re += x[i] * cos(w * i); im -= x[i] * sin(w * i) }
            p[k - kLow] = re * re + im * im
        }
        val total = p.sum()
        if (total <= 1e-12) return 0.0
        val peak = p.indices.maxBy { p[it] }
        var peakPower = 0.0
        for (j in (peak - 1)..(peak + 1)) if (j in p.indices) peakPower += p[j]
        val rest = total - peakPower
        return if (rest <= 1e-12) Double.MAX_VALUE else peakPower / rest
    }

    /** 정규화 자기상관의 지연 [lagMin, lagMax](샘플) 구간 최대값. */
    fun maxAutocorr(v: List<Double>, lagMin: Int, lagMax: Int): Double {
        val n = v.size
        if (n <= lagMax) return 0.0
        val m = v.average()
        val x = DoubleArray(n) { v[it] - m }
        val r0 = x.sumOf { it * it }
        if (r0 <= 1e-12) return 0.0
        var best = Double.NEGATIVE_INFINITY
        for (lag in lagMin..lagMax) {
            var s = 0.0
            for (i in 0 until n - lag) s += x[i] * x[i + lag]
            best = maxOf(best, s / r0)
        }
        return best
    }
}
