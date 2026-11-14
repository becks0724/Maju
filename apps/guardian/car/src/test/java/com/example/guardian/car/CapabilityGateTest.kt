// Pleos 능력 확인 판단 시험 — 운전석이 없거나 오류면 그 질의를 멈추고, 실패면 10분마다 다시 확인, 에어백을 못 읽으면 상세 안내(Pleos F-2)
package com.example.guardian.car

import com.example.guardian.car.CapabilityGate.Cap
import com.example.guardian.car.CapabilityGate.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityGateTest {
    @Test fun firstCheckIsDueAndQueriesRunUntilAnswered() {
        val g = CapabilityGate()
        assertTrue("시작 뒤 한 번 확인", g.checkDue(0))
        g.onChecking(0)
        assertFalse("확인 중에는 다시 묻지 않음", g.checkDue(1_000))
        for (s in Signal.entries) assertTrue("답 전에는 지금처럼 묻는다 $s", g.shouldQuery(s))
        assertFalse("답 전에는 안내 없음", g.airbagUnreadable())
    }

    @Test fun readableWithDriverSeatKeepsQuery() {
        val g = CapabilityGate()
        g.onChecking(0)
        g.onResult(Signal.AIRBAG, readable = true, driverSeat = true)
        assertEquals(Cap.OK, g.status(Signal.AIRBAG))
        assertTrue(g.shouldQuery(Signal.AIRBAG))
        assertFalse(g.airbagUnreadable())
    }

    @Test fun missingDriverSeatOrNoReadStopsThatQueryOnly() {
        val g = CapabilityGate()
        g.onChecking(0)
        g.onResult(Signal.AIRBAG, readable = true, driverSeat = false)
        g.onResult(Signal.SEAT, readable = false, driverSeat = true)
        g.onResult(Signal.BRAKE, readable = true, driverSeat = true)
        assertFalse("운전석 없음", g.shouldQuery(Signal.AIRBAG))
        assertFalse("읽기 권한 없음", g.shouldQuery(Signal.SEAT))
        assertTrue(g.shouldQuery(Signal.BRAKE))
        assertTrue("에어백을 못 읽으면 상세 안내", g.airbagUnreadable())
        assertFalse("미지원은 오류가 아니라 10분 재확인 없음(다음 시작에 다시)", g.checkDue(600_000))
    }

    @Test fun errorStopsQueryAndRechecksEvery10Minutes() {
        val g = CapabilityGate()
        g.onChecking(0)
        g.onError(Signal.AIRBAG)
        g.onResult(Signal.BRAKE, readable = true, driverSeat = true)
        g.onResult(Signal.SEAT, readable = true, driverSeat = true)
        assertFalse("오류면 그 질의를 멈춘다", g.shouldQuery(Signal.AIRBAG))
        assertTrue(g.airbagUnreadable())
        assertFalse(g.checkDue(599_999))
        assertTrue("10분 뒤 다시 확인", g.checkDue(600_000))
        g.onChecking(600_000)
        g.onResult(Signal.AIRBAG, readable = true, driverSeat = true)
        assertTrue("다시 확인에서 되면 질의를 되살린다", g.shouldQuery(Signal.AIRBAG))
        assertFalse(g.airbagUnreadable())
        assertFalse("모두 답했으면 더 확인하지 않음", g.checkDue(1_200_000))
    }

    @Test fun noVehicleSdkMeansNothingToCheck() {
        val g = CapabilityGate()
        g.onChecking(0)
        for (s in Signal.entries) g.onError(s)
        assertTrue(g.airbagUnreadable())
        assertTrue(g.checkDue(600_000))
    }
}
