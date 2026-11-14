// 포그라운드 서비스 멈춤 조건 시험 — 「토큰 없음 그리고 OFF · S0」일 때만 멈춘다(C-7 · Pleos D-6, 사건 중 401 에도 경보가 이어지게)
package com.example.guardian.car

import com.example.guardian.shared.flow.FlowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicePolicyTest {
    @Test fun s1WithoutTokenKeepsService() {
        assertTrue("사건 중 401 — 경보가 이어져야 한다", ServicePolicy.keep(hasToken = false, state = FlowState.S1))
    }

    @Test fun stopsOnlyWithoutTokenAndIdle() {
        assertFalse(ServicePolicy.keep(hasToken = false, state = FlowState.OFF))
        assertFalse(ServicePolicy.keep(hasToken = false, state = FlowState.S0))
        for (s in FlowState.entries) assertTrue("토큰 있음 $s", ServicePolicy.keep(hasToken = true, state = s))
        for (s in FlowState.entries - setOf(FlowState.OFF, FlowState.S0)) {
            assertTrue("토큰 없음 $s", ServicePolicy.keep(hasToken = false, state = s))
        }
    }

    /** 멈춤은 「유지 → 멈춤」으로 바뀔 때(처음 판단이 멈춤인 경우 포함) 한 번만 낸다. 시작은 창이 보일 때 화면이 맡는다. */
    @Test fun gateStopsOnceOnTransition() {
        val g = ServiceGate()
        assertEquals("처음부터 짝 없음 · 꺼짐 — 다시 뜬 서비스도 끈다", ServiceGate.Change.STOP, g.update(keep = false))
        assertNull("같은 판단은 다시 내지 않음", g.update(keep = false))
        assertNull("유지로 바뀜 — 시작은 화면 몫", g.update(keep = true))
        assertNull(g.update(keep = true))
        assertEquals(ServiceGate.Change.STOP, g.update(keep = false))
    }
}
