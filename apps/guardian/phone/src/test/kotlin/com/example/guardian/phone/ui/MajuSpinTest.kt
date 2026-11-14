// 휴대폰 기다림 회전 로고 판단 시험 — 애니메이션 배율 0 이면 정지, 기다림 동안 회전, 끝나면 감속해 정지 각도에 서는 계획(motion.md)
package com.example.guardian.phone.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MajuSpinTest {
    @Test fun animationOffIsAlwaysStill() {
        assertEquals(SpinMode.STILL, MajuSpin.mode(waiting = true, animatorScale = 0f))
        assertEquals(SpinMode.STILL, MajuSpin.mode(waiting = false, animatorScale = 0f))
    }

    @Test fun waitingRotatesAndEndSettles() {
        assertEquals(SpinMode.ROTATE, MajuSpin.mode(waiting = true, animatorScale = 1f))
        assertEquals(SpinMode.ROTATE, MajuSpin.mode(waiting = true, animatorScale = 0.5f))
        assertEquals(SpinMode.SETTLE, MajuSpin.mode(waiting = false, animatorScale = 1f))
    }

    @Test fun specNumbers() {
        // 한 바퀴 6초 = 초당 60°, 시작 0.6초에 18°, 마지막 60°를 2초에
        assertEquals(360f, MajuSpin.DEG_PER_S * MajuSpin.LAP_MS / 1000f, 0.001f)
        assertEquals(18f, MajuSpin.START_DEG, 0f)
        assertEquals(600, MajuSpin.START_MS)
        assertEquals(60f, MajuSpin.SETTLE_DEG, 0f)
        assertEquals(2_000, MajuSpin.SETTLE_MS)
    }

    @Test fun settleFarFromStopRunsLinearThenLast60() {
        // 30°에서 멈추면 다음 정지 각도 360°까지 330° — 270°를 4.5초 등속, 60°를 2초 감속
        assertEquals(MajuSpin.Settle(270f, 4_500, 60f, 2_000), MajuSpin.settle(30f))
        // 여러 바퀴 쌓인 각도도 같다
        assertEquals(MajuSpin.Settle(270f, 4_500, 60f, 2_000), MajuSpin.settle(720f + 30f))
    }

    @Test fun settleLongestIsFivePlusTwoSeconds() {
        val p = MajuSpin.settle(0.001f)
        assertEquals(5_000, p.linearMs)
        assertEquals(2_000, p.easeMs)
    }

    @Test fun settleNearStopOnlyEasesTheRest() {
        // 330°면 30°만 남아 등속 없이 1초 감속(마지막 60°와 같은 빠르기)
        assertEquals(MajuSpin.Settle(0f, 0, 30f, 1_000), MajuSpin.settle(330f))
    }

    @Test fun settleAtStopDoesNothing() {
        assertEquals(MajuSpin.Settle(0f, 0, 0f, 0), MajuSpin.settle(0f))
        assertEquals(MajuSpin.Settle(0f, 0, 0f, 0), MajuSpin.settle(360f))
    }
}
