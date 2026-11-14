// 손목 응답 감지 시험 — 경보 진동 · 차 떨림 · 느린 기울어짐은 응답이 아니고, 짧게 팔목을 돌리면 응답이다
package com.example.guardian.watch

import com.example.guardian.watch.core.WristResponseDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import java.util.Random

class WristResponseDetectorTest {
    private val g = 9.81

    /** [hz] 로 [ms] 동안 샘플을 넣고 응답으로 본 횟수를 센다. [at] 은 시작 뒤 경과 초 → (x, y, z). */
    private fun count(d: WristResponseDetector, hz: Int, ms: Long, startMs: Long = 0, at: (Double) -> Triple<Double, Double, Double>): Int {
        val step = 1000L / hz
        var n = 0
        var t = 0L
        while (t < ms) {
            val (x, y, z) = at(t / 1000.0)
            if (d.add(startMs + t, x, y, z)) n++
            t += step
        }
        return n
    }

    @Test fun alertVibrationIsNotResponse() {
        // 손목은 가만히(중력 z), 진동은 축마다 크게 떨림 — 무작위 표준편차 1.5 m/s² + 12Hz · 9Hz 사인 2 m/s²
        for (hz in listOf(25, 50)) for (seed in 1..5) {
            val rnd = Random(seed.toLong())
            val n = count(WristResponseDetector(), hz, 30_000) { s ->
                val buzz = 2.0 * sin(2 * PI * 12 * s)
                Triple(1.5 * rnd.nextGaussian() + buzz, 1.5 * rnd.nextGaussian() + 2.0 * sin(2 * PI * 9 * s), g + 1.5 * rnd.nextGaussian() + buzz)
            }
            assertEquals("진동은 응답이 아님 hz=$hz seed=$seed", 0, n)
        }
    }

    @Test fun carVibrationAndSlowTiltAreNotResponse() {
        // 주행 진동(z 0.8 m/s² 떨림) + 5초에 걸친 90도 기울어짐(조향 · 자세 변화처럼 느린 회전)
        val rnd = Random(7L)
        val n = count(WristResponseDetector(), 25, 10_000) { s ->
            val th = (PI / 2) * (s / 5.0).coerceAtMost(1.0)
            Triple(g * sin(th) + 0.3 * rnd.nextGaussian(), 0.3 * rnd.nextGaussian(), g * cos(th) + 0.8 * rnd.nextGaussian())
        }
        assertEquals(0, n)
    }

    /**
     * 한계 기록 — 핸들을 0.6초에 60도 돌리는 조작(손이 핸들에 있으면 팔목도 같이 돈다)은 응답 동작과 구별되지 않는다.
     * 이 정의로는 운전 중 조향이 응답으로 잡혀 경보가 꺼질 수 있다(오응답 = 놓침) → 기본값은 「버튼만」(WRIST_RESPONSE_ENABLED = false).
     */
    @Test fun steeringLikeTurnIsIndistinguishableSoWristResponseIsOffByDefault() {
        val n = count(WristResponseDetector(), 25, 4_000) { s ->
            val th = (PI / 3) * ((s - 1.0) / 0.6).coerceIn(0.0, 1.0)
            Triple(g * sin(th), 0.0, g * cos(th))
        }
        assertEquals("조향과 같은 회전도 응답으로 잡힌다", 1, n)
        assertFalse(WristResponseDetector.WRIST_RESPONSE_ENABLED)
    }

    @Test fun roadShockIsNotResponse() {
        // 노면 충격 — 0.1초 동안 z 로 ±15 m/s² 튐(손목 방향은 그대로)
        val n = count(WristResponseDetector(), 50, 5_000) { s ->
            val bump = if (s in 2.0..2.1) 15.0 * sin(2 * PI * 10 * (s - 2.0)) else 0.0
            Triple(0.0, 0.0, g + bump)
        }
        assertEquals(0, n)
    }

    @Test fun quickWristTurnIsResponse() {
        for (hz in listOf(25, 50)) {
            val d = WristResponseDetector()
            // 2초 가만히 → 0.4초에 걸쳐 90도 돌림 → 가만히. 진동을 함께 켜 둔다(경보 중 응답)
            val rnd = Random(3L)
            val n = count(d, hz, 5_000) { s ->
                val th = (PI / 2) * ((s - 2.0) / 0.4).coerceIn(0.0, 1.0)
                Triple(g * sin(th) + 1.0 * rnd.nextGaussian(), 1.0 * rnd.nextGaussian(), g * cos(th) + 1.0 * rnd.nextGaussian())
            }
            assertEquals("한 번 돌림 = 응답 한 번 hz=$hz", 1, n)
        }
    }

    @Test fun refractoryThenSecondTurnCountsAgain() {
        val d = WristResponseDetector()
        // 2초에 돌리고 6초에 되돌림 — 두 번 응답
        val n = count(d, 25, 9_000) { s ->
            val th = when {
                s < 2.0 -> 0.0
                s < 2.4 -> (PI / 2) * (s - 2.0) / 0.4
                s < 6.0 -> PI / 2
                s < 6.4 -> (PI / 2) * (1 - (s - 6.0) / 0.4)
                else -> 0.0
            }
            Triple(g * sin(th), 0.0, g * cos(th))
        }
        assertEquals(2, n)
    }

    @Test fun gapResetsFilterSoNoFalseTurn() {
        val d = WristResponseDetector()
        assertFalse(d.add(0, 0.0, 0.0, g))
        assertFalse(d.add(40, 0.0, 0.0, g))
        // 센서가 멈췄다가(1초) 다른 자세로 다시 시작 — 끊긴 사이의 변화는 응답으로 세지 않는다
        assertFalse(d.add(1_040, g, 0.0, 0.0))
        assertFalse(d.add(1_080, g, 0.0, 0.0))
    }
}
