// 손목 움직임 응답 감지 — 가속도를 저역 필터로 걸러 경보 진동 · 차 떨림은 빼고, 팔목이 짧은 시간에 크게 돌아간 것만 응답으로 본다
package com.example.guardian.watch.core

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 방식(구글 손목 제스처 방식 참고 — 중력 방향 변화를 본다).
 * - 축마다 2단 저역 필터(차단 [cutoffHz])로 중력 성분만 남긴다. 진동 모터 · 노면 떨림은 빠른 떨림이라 여기서 거의 사라진다.
 * - 걸러진 중력 벡터가 최근 [windowMs] 안에서 [minAngleDeg] 넘게 돌았으면 응답이다. 진동은 손목 방향을 바꾸지 않는다.
 * - 한 번 응답한 뒤 [refractoryMs] 동안은 다시 세지 않는다.
 * 기본값은 모두 [추정] — 실기기 경보 진동 · 주행 실측으로 다시 정한다. 샘플 간격은 고정이 아니어도 된다(25Hz · 50Hz 모두).
 */
class WristResponseDetector(
    private val cutoffHz: Double = 2.0,
    private val minAngleDeg: Double = 40.0,
    private val windowMs: Long = 1_000,
    private val refractoryMs: Long = 2_000,
    /** 샘플 사이가 이보다 벌어지면 필터를 처음부터 다시 잡는다(센서 멈춤 · 감시 재시작). */
    private val gapResetMs: Long = 500,
) {
    private var lp1: DoubleArray? = null
    private var lp2: DoubleArray? = null
    private var lastAtMs = 0L
    private var lastFireAtMs: Long? = null
    private val history = ArrayDeque<Pair<Long, DoubleArray>>()

    fun reset() {
        lp1 = null; lp2 = null; history.clear()
    }

    /** 가속도 한 샘플(m/s², 중력 포함). 응답으로 볼 움직임이면 true. */
    fun add(atMs: Long, x: Double, y: Double, z: Double): Boolean {
        val raw = doubleArrayOf(x, y, z)
        val a1 = lp1; val a2 = lp2
        if (a1 == null || a2 == null || atMs <= lastAtMs || atMs - lastAtMs > gapResetMs) {
            reset()
            lp1 = raw.copyOf(); lp2 = raw.copyOf(); lastAtMs = atMs
            history.addLast(atMs to raw.copyOf())
            return false
        }
        val dt = (atMs - lastAtMs) / 1000.0
        val alpha = dt / (1.0 / (2 * PI * cutoffHz) + dt)
        for (i in 0..2) {
            a1[i] += alpha * (raw[i] - a1[i])
            a2[i] += alpha * (a1[i] - a2[i])
        }
        lastAtMs = atMs
        history.addLast(atMs to a2.copyOf())
        while (history.isNotEmpty() && history.first().first < atMs - windowMs) history.removeFirst()

        val fired = lastFireAtMs
        if (fired != null && atMs - fired < refractoryMs) return false
        if (history.any { angleDeg(it.second, a2) >= minAngleDeg }) {
            lastFireAtMs = atMs
            history.clear()
            history.addLast(atMs to a2.copyOf())
            return true
        }
        return false
    }

    private fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val na = norm(a); val nb = norm(b)
        if (na < MIN_G || nb < MIN_G) return 0.0 // 방향을 정할 수 없는 크기(자유 낙하 · 충격 직후)는 세지 않는다
        val cos = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1.0, 1.0)
        return acos(cos) * 180 / PI
    }

    private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    companion object {
        private const val MIN_G = 3.0

        /**
         * 손목 응답은 기본으로 끈다(PM 확인 요청 2026-10-30 — 오응답은 놓침을 만든다).
         * 시험 근거 — 진동 · 노면 충격 · 느린 기울어짐은 응답으로 잡지 않지만, 핸들을 0.6초에 60도 돌리는 조향은
         * 응답 동작과 구별하지 못한다(WristResponseDetectorTest). 실기기에서 조향 · 경보 진동 중 손목 동작을 실측해
         * 구별하는 정의(예 두 번 흔들기 + 회전 방향 왕복)를 정하기 전까지 응답은 화면 버튼만 받는다.
         */
        const val WRIST_RESPONSE_ENABLED = false
    }
}
