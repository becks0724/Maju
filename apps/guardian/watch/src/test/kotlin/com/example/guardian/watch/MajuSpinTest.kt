// 워치 기다림 회전 로고 판단 시험 — 짝짓기 확인 중에만 로고, 애니메이션 배율 0 이면 정지, 감속 계획(motion.md)
package com.example.guardian.watch

import com.example.guardian.watch.ui.MajuSpin
import com.example.guardian.watch.ui.PairOutcome
import com.example.guardian.watch.ui.SpinMode
import com.example.guardian.watch.ui.pairSpinnerShown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MajuSpinTest {
    @Test fun spinnerOnlyWhileChecking() {
        assertTrue(pairSpinnerShown(PairOutcome.CHECKING))
        // 결과 · 오류는 기다림이 아니다
        assertFalse(pairSpinnerShown(PairOutcome.DONE))
        assertFalse(pairSpinnerShown(PairOutcome.INVALID))
        assertFalse(pairSpinnerShown(PairOutcome.NETWORK))
    }

    @Test fun animationOffIsAlwaysStill() {
        assertEquals(SpinMode.STILL, MajuSpin.mode(waiting = true, animatorScale = 0f))
        assertEquals(SpinMode.ROTATE, MajuSpin.mode(waiting = true, animatorScale = 1f))
        assertEquals(SpinMode.SETTLE, MajuSpin.mode(waiting = false, animatorScale = 1f))
    }

    @Test fun settlePlan() {
        assertEquals(MajuSpin.Settle(270f, 4_500, 60f, 2_000), MajuSpin.settle(30f))
        assertEquals(MajuSpin.Settle(0f, 0, 30f, 1_000), MajuSpin.settle(330f))
        assertEquals(MajuSpin.Settle(0f, 0, 0f, 0), MajuSpin.settle(0f))
    }
}
