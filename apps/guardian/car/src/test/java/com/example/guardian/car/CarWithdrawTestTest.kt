// 차가 주인일 때 테스트 세션이 끝나면 테스트 경보를 거두는지 시험 — 휴대폰 test=false + 신선한 워치 test=false 가 10초 넘게 이어질 때 한 번(B-7, app-phone 과 맞춤)
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWithdrawTestTest {
    /** 테스트 시나리오로 연 경보(S1 · test). */
    private fun testAlarm(): Rig {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.NORMAL, test = true), peerFlow = settings(test = true))
        r.hub.onReading(MOVING, 4_000)
        r.sync(4_000, watch(WristVerdict.PULSE_WEAK, test = true), peerFlow = settings(test = true))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        assertTrue(r.hub.snapshot.test)
        return r
    }

    @Test fun sessionEndWithdrawsTestAlarmAfter10s() {
        val r = testAlarm()
        for (t in 6_000L..16_000L step 2_000) {
            r.hub.onReading(MOVING, t)
            r.sync(t, watch(WristVerdict.NORMAL, test = false), peerFlow = settings(test = false))
        }
        assertEquals("10초까지는 거두지 않는다", FlowState.S1, r.hub.snapshot.state)
        r.hub.onReading(MOVING, 18_000)
        r.sync(18_000, watch(WristVerdict.NORMAL, test = false), peerFlow = settings(test = false))
        assertEquals("10초 넘게 끝 — 거둠", FlowState.S0, r.hub.snapshot.state)
        assertFalse(r.hub.snapshot.test)
    }

    @Test fun staleWatchOrMissingPhoneDoesNotCountAsEnd() {
        val r = testAlarm()
        for (t in 6_000L..30_000L step 2_000) {
            r.hub.onReading(MOVING, t)
            r.sync(t, watch(WristVerdict.NORMAL, test = false, ageMs = 40_000), peerFlow = settings(test = false))
        }
        assertTrue("낡은 워치 값은 끝의 증거가 아님 — 테스트 경보 유지", r.hub.snapshot.test && r.hub.snapshot.state != FlowState.S0)
    }

    @Test fun realIncidentIsNeverWithdrawn() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        for (t in 4_000L..20_000L step 2_000) {
            r.hub.onReading(MOVING, t)
            r.sync(t, watch(WristVerdict.PULSE_WEAK, test = false), peerFlow = settings(test = false))
        }
        assertTrue("실제 사건은 그대로", r.hub.snapshot.state != FlowState.S0)
    }
}
