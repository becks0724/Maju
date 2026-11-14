// 판정 주인 시험(A-4 휴대폰 쪽) — 주인은 「차 요청 10초 안 + flow.owner == CAR」, 차가 내려놓거나 · 경보 중 사건 id 가 사라지거나 · 401 이면 넘겨받고, 미해결 사건은 차가 같은 id 를 실을 때까지 쥔다
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnerLeaseTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { true })

    private val id = "event-1"

    private fun flow(state: FlowState, owner: Owner = Owner.CAR, eventId: String? = id, test: Boolean = false) = FlowStatus.of(
        FlowSnapshot(
            state = state, sinceMs = mono, test = test, powerOn = true, consents = Consents(true, true, true), worn = true, stopped = true,
            alerting = state in PhoneEngine.ALARM_STATES, incidentActive = state != FlowState.OFF && state != FlowState.S0,
            facts = IncidentFacts(firstAlertAtMs = mono - 40_000, eventId = eventId),
        ),
        owner,
    )

    /** 차가 방금 중계에 닿았다(신선) — [f] 를 싣고. */
    private fun carSync(f: FlowStatus?) {
        mono += 2_000
        engine.buildSync("tok", mono)
        val peer = CarPeer(1, wall0, mono + serverOffset, Vehicle(speedKmh = 0.0, gear = Gear.P), f)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, emptyList()), mono, mono)
        engine.tick(mono)
    }

    private fun shownId() = engine.status(mono).eventId
    private fun state() = engine.status(mono).flowState

    @Test fun carDropsOwnership_phoneTakesSameIncident() {
        carSync(flow(FlowState.S4))
        assertEquals(Owner.CAR, engine.owner)
        carSync(flow(FlowState.S4, owner = Owner.PHONE))
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(FlowState.S4, state())
        assertEquals(id, shownId())
    }

    /** 차 재시작 — 경보 중이던 차 흐름의 사건 id 가 사라졌다(새 상태 기계 S0). 휴대폰이 그 사건을 넘겨받는다. */
    @Test fun carRestartedDuringAlarm_phoneTakesHeldIncident() {
        carSync(flow(FlowState.S4))
        carSync(flow(FlowState.S0, eventId = null))
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(FlowState.S4, state())
        assertEquals(id, shownId())
    }

    /** 재시작한 차의 첫 흐름이 「OFF · owner=PHONE · id 없음」이어도 마지막 실제 사건을 넘겨받는다(candidate 와 따로 쥔다). */
    @Test fun restartedCarFirstFlowOffOwnerPhone_phoneTakesHeldIncident() {
        carSync(flow(FlowState.S4))
        carSync(flow(FlowState.OFF, owner = Owner.PHONE, eventId = null))
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(FlowState.S4, state())
        assertEquals(id, shownId())
    }

    @Test fun unauthorizedDuringIncident_phoneTakesCarFlow() {
        carSync(flow(FlowState.S4))
        engine.onUnauthorized()
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(FlowState.S4, state())
        assertEquals(id, shownId())
    }

    /** 미해결 사건 — 차가 다시 신선해도 같은 사건 id 를 owner=CAR 로 실을 때까지 넘겨주지 않는다. */
    @Test fun leaseHeldUntilCarCarriesSameId() {
        carSync(flow(FlowState.S4))
        carSync(flow(FlowState.S0, eventId = null))
        assertEquals(Owner.PHONE, engine.owner)
        carSync(flow(FlowState.S0, eventId = null))
        carSync(flow(FlowState.S0, eventId = null))
        assertEquals("차가 사건 id 를 싣기 전에는 쥔다", Owner.PHONE, engine.owner)
        assertEquals(FlowState.S4, state())
        carSync(flow(FlowState.S4))
        assertEquals("같은 id 를 실으면 넘긴다", Owner.CAR, engine.owner)
    }

    // ── 정상 종료를 재시작으로 보지 않는다 ──

    @Test fun responseThenRest_staysCar() {
        carSync(flow(FlowState.S4))
        carSync(flow(FlowState.S2))
        carSync(flow(FlowState.S0))
        carSync(flow(FlowState.S0, eventId = null))
        assertEquals(Owner.CAR, engine.owner)
    }

    @Test fun accidentAndAccidentResponse_staysCar() {
        carSync(flow(FlowState.S4))
        carSync(flow(FlowState.S5, eventId = "accident-1"))
        carSync(flow(FlowState.S0, eventId = "accident-1"))
        assertEquals(Owner.CAR, engine.owner)
    }

    /** 전원 끔 — 경보 중 OFF 로 가도 사건 id 는 남는다. */
    @Test fun powerOffDuringAlarm_staysCar() {
        carSync(flow(FlowState.S4))
        carSync(flow(FlowState.OFF))
        assertEquals(Owner.CAR, engine.owner)
    }

    /** 테스트 사건은 쥐지 않는다 — 테스트 흐름이 사라져도 넘겨받지 않는다. */
    @Test fun testIncidentNotHeld() {
        carSync(flow(FlowState.S4, test = true))
        carSync(flow(FlowState.S0, eventId = null))
        assertEquals(Owner.CAR, engine.owner)
    }
}
