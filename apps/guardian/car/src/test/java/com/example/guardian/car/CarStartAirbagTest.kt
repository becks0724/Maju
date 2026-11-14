// 다시 뜬 차의 시작 유예 중 에어백 사고 시험 — 유예 중에도 정상 판정으로 감시에 들어가 사고를 열고, 설정을 받기 전 본 전이는 잠시 쥐었다 열며, 시작부터 켜진 에어백 · 휴대폰이 쥔 사고는 새 사고를 열지 않음
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarStartAirbagTest {
    private val crash = MOVING.copy(airbagDeployed = true)
    private val intact = MOVING.copy(airbagDeployed = false)

    private fun Rig.accidentSms() = hub.pendingCommands.count { it.type == CommandType.SEND_GUARDIAN_SMS && it.payload["kind"] == "ACCIDENT" }

    /** 휴대폰 쪽 사건 흐름 — [airbag] 이면 사고(S5), 아니면 손목 무응답(S3). 중계 시각 = 차 시각. */
    private fun phoneFlow(airbag: Boolean): FlowStatus {
        val m = FlowMachine().apply {
            handle(FlowInput.ClockSync(0, 0))
            handle(FlowInput.Vehicle(VehicleState(60.0, Gear.D, false), 0))
            handle(FlowInput.ConsentsChanged(Consents(true, true, true), 0))
            handle(FlowInput.Power(true, 0))
            handle(FlowInput.Wrist(WristVerdict.NORMAL, 0))
            if (airbag) handle(FlowInput.Vehicle(VehicleState(60.0, Gear.D, false, airbagDeployed = true), 1_000))
            else { handle(FlowInput.Wrist(WristVerdict.PULSE_WEAK, 1_000)); handle(FlowInput.Tick(37_000)) }
        }
        return FlowStatus.forHandoff(m.snapshot(), Owner.PHONE, 0)
    }

    /** app-qa 탐침 p4 — 유예 중(휴대폰이 owner=CAR 켬 설정을 싣는 중) 에어백이 전개되면 사고를 연다. */
    @Test fun airbagDuringStartHoldOpensAccident() {
        val r = Rig()
        r.hub.onReading(intact, 0)
        r.sync(0, watch(WristVerdict.NORMAL), peerFlow = settings(owner = Owner.CAR))
        assertEquals("유예 중에도 정상 판정으로 감시에 들어간다", FlowState.S0, r.hub.snapshot.state)
        r.hub.onReading(crash, 2_000)
        assertEquals(FlowState.S5, r.hub.snapshot.state)
        assertEquals(1, r.accidentSms())
    }

    /** 유예 중에도 나쁜 판정은 넣지 않는다 — 새 손목 사건을 열지 않는다(A-4). */
    @Test fun badVerdictStillHeldDuringStart() {
        val r = Rig()
        r.hub.onReading(intact, 0)
        r.sync(0, watch(WristVerdict.NORMAL), peerFlow = settings(owner = Owner.CAR))
        r.hub.onReading(intact, 2_000)
        r.sync(2_000, watch(WristVerdict.PULSE_ABSENT), peerFlow = settings(owner = Owner.CAR))
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        assertNull(r.hub.snapshot.facts.eventId)
    }

    /** ⓐ 휴대폰 설정이 오기 전(OFF)에 이 프로세스가 본 false → true — 쥐었다가 켬 · 동의 · 착용이 확인되면 사고를 연다. */
    @Test fun airbagEdgeSeenBeforeSettingsOpensAccidentOnceArmed() {
        val r = Rig()
        r.hub.onReading(intact, 0)
        r.hub.onReading(crash, 1_000)
        assertEquals(FlowState.OFF, r.hub.snapshot.state)
        r.hub.onReading(crash, 2_000)
        r.sync(2_000, watch(WristVerdict.NORMAL), peerFlow = settings(owner = Owner.CAR))
        assertEquals(FlowState.S5, r.hub.snapshot.state)
        assertEquals(1, r.accidentSms())
    }

    /** ⓐ 꺼짐으로 확인되면 쥔 전이를 버린다 — 나중에 켜도 그 전이로 사고를 열지 않는다. */
    @Test fun heldAirbagEdgeIsDroppedWhenPowerIsOff() {
        val r = Rig()
        r.hub.onReading(intact, 0)
        r.hub.onReading(crash, 1_000)
        r.hub.onReading(crash, 2_000)
        r.sync(2_000, watch(WristVerdict.NORMAL), peerFlow = settings(power = false, owner = Owner.CAR))
        r.hub.onReading(crash, 4_000)
        r.sync(4_000, watch(WristVerdict.NORMAL), peerFlow = settings(owner = Owner.CAR))
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        assertEquals(0, r.accidentSms())
    }

    /** ⓐ 쥐는 기간은 6초 — 그 뒤에 감시가 켜지면 열지 않는다(한계). */
    @Test fun heldAirbagEdgeExpiresAfter6s() {
        val r = Rig()
        r.hub.onReading(intact, 0)
        r.hub.onReading(crash, 1_000)
        for (t in 2_000L..8_000L step 2_000) r.hub.onReading(crash, t)
        r.sync(8_000, watch(WristVerdict.NORMAL), peerFlow = settings(owner = Owner.CAR))
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        assertEquals(0, r.accidentSms())
    }

    /**
     * ⓑ 시작할 때 이미 true 인 에어백은 전이가 아니다 — 시동을 걸 때마다 사고 문자가 나가지 않는다.
     * 생산 읽기 순서 — Pleos 에어백은 비동기라 첫 읽기는 늘 null 이고, 값은 다음 주기에 실린다. 그 사이 첫 sync 로 감시(S0)에 들어간다.
     */
    @Test fun airbagAlreadyTrueAtStartIsNotAnAccident() {
        val r = Rig()
        r.hub.onReading(MOVING, 0) // 에어백 null — 아직 Pleos 응답 전
        r.sync(0, watch(WristVerdict.NORMAL))
        assertEquals("첫 응답에서 감시에 들어감", FlowState.S0, r.hub.snapshot.state)
        for (t in 2_000L..20_000L step 2_000) { r.hub.onReading(crash, t); r.sync(t, watch(WristVerdict.NORMAL)) }
        assertEquals("null → true 는 사고가 아니다", FlowState.S0, r.hub.snapshot.state)
        assertEquals(0, r.accidentSms())
    }

    /** null → false → true 는 이 프로세스가 본 전이 — 사고 한 번. */
    @Test fun nullThenFalseThenTrueIsOneAccident() {
        val r = Rig()
        r.hub.onReading(MOVING, 0)
        r.sync(0, watch(WristVerdict.NORMAL))
        r.hub.onReading(intact, 2_000)
        r.sync(2_000, watch(WristVerdict.NORMAL))
        r.hub.onReading(crash, 4_000)
        assertEquals(FlowState.S5, r.hub.snapshot.state)
        r.sync(4_000, watch(WristVerdict.NORMAL))
        r.hub.onReading(crash, 6_000)
        assertEquals("사고는 한 번", 1, r.accidentSms())
    }

    /** 사고에 응답해 감시로 돌아간 뒤 차 앱이 다시 떠도(새 프로세스) 남은 true 로 사고를 다시 열지 않는다. */
    @Test fun restartAfterAnsweredAccidentDoesNotReopen() {
        val first = Rig()
        first.hub.onReading(MOVING, 0)
        first.sync(0, watch(WristVerdict.NORMAL))
        first.hub.onReading(intact, 2_000)
        first.hub.onReading(crash, 4_000)
        assertEquals(FlowState.S5, first.hub.snapshot.state)
        // 다시 뜬 차 — 휴대폰은 감시 중(S0) 흐름을 싣는다
        val again = Rig()
        again.hub.onReading(MOVING, 100_000)
        again.sync(100_000, watch(WristVerdict.NORMAL))
        for (t in 102_000L..110_000L step 2_000) { again.hub.onReading(crash, t); again.sync(t, watch(WristVerdict.NORMAL)) }
        assertEquals(FlowState.S0, again.hub.snapshot.state)
        assertEquals(0, again.accidentSms())
    }

    /** 휴대폰 감시 흐름(airbag=false 스냅숏)을 넘겨받은 뒤 true 읽기 — 내가 false 를 읽은 적이 없으면 사고 아님. */
    @Test fun adoptedFalseSnapshotDoesNotMakeStartTrueAnAccident() {
        val r = Rig()
        r.hub.onReading(MOVING, 0)
        val watching = com.example.guardian.shared.flow.FlowSnapshot(state = FlowState.S0, powerOn = true, consents = Consents(true, true, true), worn = true, watchSeenAtMs = 0)
        assertTrue(r.sync(0, watch(WristVerdict.NORMAL), peerFlow = FlowStatus.of(watching, Owner.PHONE)).adopted)
        r.hub.onReading(crash, 2_000)
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        assertEquals(0, r.accidentSms())
    }

    /** 같은 넘겨받기 — 내가 false 를 읽은 적이 있으면 그 뒤 true 는 사고. */
    @Test fun adoptedSnapshotAfterOwnFalseReadStillOpensAccident() {
        val r = Rig()
        r.hub.onReading(intact, 0)
        val watching = com.example.guardian.shared.flow.FlowSnapshot(state = FlowState.S0, powerOn = true, consents = Consents(true, true, true), worn = true, watchSeenAtMs = 0)
        assertTrue(r.sync(0, watch(WristVerdict.NORMAL), peerFlow = FlowStatus.of(watching, Owner.PHONE)).adopted)
        r.hub.onReading(crash, 2_000)
        assertEquals(FlowState.S5, r.hub.snapshot.state)
        assertEquals(1, r.accidentSms())
    }

    /** ⓒ 휴대폰이 사고(S5)를 owner=PHONE 으로 쥐고 있으면 다시 뜬 차는 그 사고를 받고 새 사고를 열지 않는다(사고 문자 두 통 금지). */
    @Test fun restartedCarTakesPhoneAccidentWithoutANewOne() {
        val phone = phoneFlow(airbag = true)
        assertEquals(FlowState.S5, phone.state)
        val r = Rig()
        r.hub.onReading(crash, 5_000)
        assertTrue(r.sync(5_000, watch(WristVerdict.NORMAL), peerFlow = phone).adopted)
        for (t in 7_000L..15_000L step 2_000) { r.hub.onReading(crash, t); r.sync(t, watch(WristVerdict.NORMAL), peerFlow = phone) }
        assertEquals(FlowState.S5, r.hub.snapshot.state)
        assertEquals("휴대폰의 사고 id 그대로", phone.snapshot.facts.eventId, r.hub.snapshot.facts.eventId)
        assertEquals("차가 사고 문자를 새로 내지 않는다", 0, r.accidentSms())
    }

    /** ⓒ 휴대폰이 손목 사건(S3)을 쥔 채, 차가 유예 중(나쁜 판정 보류 · OFF)에 에어백 전개 — 휴대폰 사건을 받은 뒤 그 흐름에서 사고 한 번. */
    @Test fun airbagDuringHoldWhilePhoneHoldsWristIncident() {
        val phone = phoneFlow(airbag = false)
        assertEquals(FlowState.S3, phone.state)
        val r = Rig()
        r.hub.onReading(intact, 40_000)
        r.sync(40_000, watch(WristVerdict.PULSE_ABSENT), peerFlow = settings(owner = Owner.CAR))
        r.hub.onReading(crash, 41_000)
        assertFalse("아직 휴대폰 사건을 받기 전 — 새 손목 사건 없음", r.hub.snapshot.state in setOf(FlowState.S1, FlowState.AP, FlowState.S3, FlowState.S4))
        r.hub.onReading(crash, 42_000)
        assertTrue(r.sync(42_000, watch(WristVerdict.PULSE_ABSENT), peerFlow = phone).adopted)
        assertEquals(FlowState.S5, r.hub.snapshot.state)
        assertEquals(1, r.accidentSms())
    }

    /** 중계로 나가는 값도 같은 규칙 — 다시 뜬 차의 첫 값이 true 면 sync 요청의 에어백은 모름(null). 휴대폰 상태 기계가 전이로 보지 않게. */
    @Test fun relayValueIsUnknownUntilFalseSeen() {
        val r = Rig()
        r.hub.onReading(crash, 0)
        assertNull("false 를 읽기 전의 true 는 모름으로 싣는다", r.hub.buildRequest("tok", 1, 0, 0).vehicle.airbagDeployed)
        r.hub.onReading(crash, 2_000)
        assertNull(r.hub.buildRequest("tok", 2, 0, 2_000).vehicle.airbagDeployed)
    }

    /** false 를 읽은 뒤의 실제 전개는 중계 값도 true — 휴대폰이 주인이면 휴대폰이 사고를 연다. */
    @Test fun relayValueIsTrueAfterFalseSeen() {
        val r = Rig()
        r.hub.onReading(intact, 0)
        assertEquals(false, r.hub.buildRequest("tok", 1, 0, 0).vehicle.airbagDeployed)
        r.hub.onReading(crash, 2_000)
        assertEquals(true, r.hub.buildRequest("tok", 2, 0, 2_000).vehicle.airbagDeployed)
    }
}
