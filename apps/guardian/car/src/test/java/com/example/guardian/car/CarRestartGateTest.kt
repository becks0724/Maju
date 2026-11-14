// 다시 뜬 차의 시작 유예 시험 — 휴대폰 흐름을 한 번 볼 때까지(상한 6초) 손목 판정을 넣지 않고, 신선한 휴대폰 흐름으로만 켬 · 동의, 주인이어도 휴대폰 사건을 받아들임(A-4)
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarRestartGateTest {
    /** 휴대폰 쪽 사건 — 주행 중 1,000 에 첫 경보(event-1000). 중계 시각 = 차 시각(skew 0). */
    private fun phoneIncident(): FlowMachine = FlowMachine().apply {
        handle(FlowInput.ClockSync(0, 0))
        handle(FlowInput.Vehicle(VehicleState(60.0, Gear.D, false), 0))
        handle(FlowInput.ConsentsChanged(Consents(true, true, true), 0))
        handle(FlowInput.Power(true, 0))
        handle(FlowInput.Wrist(WristVerdict.NORMAL, 0))
        handle(FlowInput.Wrist(WristVerdict.PULSE_WEAK, 1_000))
    }

    /** 재시작 순간 판정이 계속 나쁨 — 휴대폰은 아직 옛 차를 주인으로 본다(owner=CAR). 새 사건을 열지 않고 휴대폰이 넘겨받은 사건을 받는다. */
    @Test fun restartWithBadVerdictWaitsAndAdoptsPhoneIncident() {
        val phone = phoneIncident()
        val r = Rig()
        r.hub.onReading(MOVING, 5_000)
        r.sync(5_000, watch(WristVerdict.PULSE_ABSENT), peerFlow = FlowStatus.forHandoff(phone.snapshot(), Owner.CAR, 0))
        assertNull("유예 중 — 손목 판정으로 새 사건을 열지 않는다", r.hub.snapshot.facts.eventId)
        assertFalse(r.hub.snapshot.state in setOf(FlowState.S1, FlowState.AP, FlowState.S3, FlowState.S4))

        // 휴대폰이 차 흐름 OFF 를 보고 넘겨받음 → owner=PHONE. 차는 이미 주인(첫 sync 성공)이지만 받아들인다
        r.hub.onReading(MOVING, 7_000)
        val rep = r.sync(7_000, watch(WristVerdict.PULSE_ABSENT), peerFlow = FlowStatus.forHandoff(phone.snapshot(), Owner.PHONE, 0))
        assertTrue("주인이어도 내 흐름이 OFF · S0 + 사건 없음이면 휴대폰 사건을 받는다", rep.adopted)
        assertEquals("사건 id 그대로 — 35초가 처음부터 다시 돌지 않음", "event-1000", r.hub.snapshot.facts.eventId)
        assertEquals(FlowState.S1, r.hub.snapshot.state)
    }

    /** 휴대폰 흐름이 계속 옛 것(owner=CAR 사건)이면 6초 뒤에는 지금처럼 손목 판정을 넣는다. */
    @Test fun gateOpensAfter6sWithoutAnswer() {
        val phone = phoneIncident()
        val stale = FlowStatus.forHandoff(phone.snapshot(), Owner.CAR, 0)
        val r = Rig()
        for (t in listOf(5_000L, 7_000L, 9_000L)) {
            r.hub.onReading(MOVING, t)
            r.sync(t, watch(WristVerdict.PULSE_ABSENT), peerFlow = stale)
        }
        assertNull("6초 안 — 판정 보류", r.hub.snapshot.facts.eventId)
        r.hub.onReading(MOVING, 11_000)
        r.sync(11_000, watch(WristVerdict.NORMAL), peerFlow = stale)
        r.hub.onReading(MOVING, 13_000)
        r.sync(13_000, watch(WristVerdict.PULSE_ABSENT), peerFlow = stale)
        assertTrue("6초가 지나면 손목 판정을 넣는다", r.hub.snapshot.facts.eventId != null)
    }

    /** 사건 없는 신선한 휴대폰 흐름을 보면 바로 연다 — 평소 시작은 늦어지지 않는다. */
    @Test fun idlePhoneFlowOpensGateAtOnce() {
        val r = Rig()
        r.arm(MOVING)
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
    }

    /** 켬 · 동의는 신선한 휴대폰 흐름(10초 안)으로만 받는다 — 낡은 값으로 옛 설정을 켜지 않는다. */
    @Test fun stalePhoneSettingsAreNotApplied() {
        val r = Rig()
        r.hub.onReading(MOVING, 0)
        r.sync(0, watch(WristVerdict.NORMAL), peerFlow = settings(power = true), peerAgeMs = 20_000)
        assertEquals(FlowState.OFF, r.hub.snapshot.state)
        assertFalse(r.hub.snapshot.powerOn)
    }

    /** 내 흐름에 사건이 있으면(주인) 휴대폰 사건을 받지 않는다 — 두 사건을 섞지 않는다. */
    @Test fun ownerWithOwnIncidentDoesNotAdopt() {
        val phone = phoneIncident()
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        val mine = r.hub.snapshot.facts.eventId
        assertTrue(mine != null)
        assertFalse(r.sync(4_000, watch(WristVerdict.PULSE_WEAK), peerFlow = FlowStatus.forHandoff(phone.snapshot(), Owner.PHONE, 0)).adopted)
        assertEquals(mine, r.hub.snapshot.facts.eventId)
    }

    /**
     * app-phone 실측(통합 carRestartWhileSignalBad) — 3초 재시작 직후 휴대폰은 옛 차를 주인으로 보고 설정만 담은 OFF 흐름(owner=CAR)을 싣는다.
     * 이것은 답이 아니다 — 유예를 풀지 않고, 휴대폰이 사건을 owner=PHONE 으로 쥐면 그 사건을 받는다.
     */
    @Test fun ownerCarSettingsSnapshotDoesNotOpenGate() {
        val phone = phoneIncident()
        val r = Rig()
        r.hub.onReading(MOVING, 5_000)
        r.sync(5_000, watch(WristVerdict.PULSE_ABSENT), peerFlow = settings(owner = Owner.CAR))
        r.hub.onReading(MOVING, 7_000)
        r.sync(7_000, watch(WristVerdict.PULSE_ABSENT), peerFlow = settings(owner = Owner.CAR))
        assertNull("설정 스냅숏만으로 새 사건을 열지 않는다", r.hub.snapshot.facts.eventId)
        r.hub.onReading(MOVING, 9_000)
        assertTrue(r.sync(9_000, watch(WristVerdict.PULSE_ABSENT), peerFlow = FlowStatus.forHandoff(phone.snapshot(), Owner.PHONE, 0)).adopted)
        assertEquals("event-1000", r.hub.snapshot.facts.eventId)
    }
}
