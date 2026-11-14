// 차 기다림 회전 로고 판단 시험 — 정차 중 번호 화면에서만 회전, 주행 제한 · 애니메이션 배율 0 이면 감속 없이 정지, 감속 계획(motion.md)
package com.example.guardian.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MajuSpinTest {
    @Test fun pairingWaitsWhileCodeShownOrBeingFetched() {
        assertTrue(CarScreen.pairingWaiting(pairCode = "123456", unpairRelayLeft = false, pairFailed = false))
        // 번호를 받는 중(「연결 번호를 받는 중」)도 기다림
        assertTrue(CarScreen.pairingWaiting(pairCode = null, unpairRelayLeft = false, pairFailed = false))
        // 중계 연결 실패 · 중계 기록 안내는 기다림이 아니다
        assertFalse(CarScreen.pairingWaiting(pairCode = null, unpairRelayLeft = false, pairFailed = true))
        assertFalse(CarScreen.pairingWaiting(pairCode = null, unpairRelayLeft = true, pairFailed = false))
        // 번호가 있으면 화면이 번호를 보이므로 기다림(화면의 when 순서와 같다)
        assertTrue(CarScreen.pairingWaiting(pairCode = "123456", unpairRelayLeft = true, pairFailed = true))
    }

    @Test fun drivingRestrictionStopsAtOnce() {
        // 주행 제한이면 기다리는 중이어도 감속 없이 정지 로고
        assertEquals(SpinMode.STILL, MajuSpin.mode(waiting = true, animatorScale = 1f, restricted = true))
        assertEquals(SpinMode.STILL, MajuSpin.mode(waiting = false, animatorScale = 1f, restricted = true))
    }

    @Test fun animationOffIsAlwaysStill() {
        assertEquals(SpinMode.STILL, MajuSpin.mode(waiting = true, animatorScale = 0f, restricted = false))
    }

    @Test fun parkedWaitingRotatesAndEndSettles() {
        assertEquals(SpinMode.ROTATE, MajuSpin.mode(waiting = true, animatorScale = 1f, restricted = false))
        assertEquals(SpinMode.SETTLE, MajuSpin.mode(waiting = false, animatorScale = 1f, restricted = false))
    }

    @Test fun settlePlan() {
        assertEquals(MajuSpin.Settle(270f, 4_500, 60f, 2_000), MajuSpin.settle(30f))
        assertEquals(MajuSpin.Settle(0f, 0, 30f, 1_000), MajuSpin.settle(330f))
        assertEquals(MajuSpin.Settle(0f, 0, 0f, 0), MajuSpin.settle(720f))
    }
}
