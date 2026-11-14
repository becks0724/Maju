// 차 문자 대조 연결 시험(A-3) — 차는 냈다는데 내 기록에 없으면 10초 뒤(넘겨받을 때는 즉시) 스스로 보내고, 차에 응답 시각이 생기면 취소한다
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

class CarSmsReconcileWiringTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { true })
    private val id = "event-${mono0 + serverOffset}"

    private fun carS4(responseAt: Long? = null, state: FlowState = FlowState.S4) = FlowStatus.of(
        FlowSnapshot(
            state = state, sinceMs = mono, powerOn = true, consents = Consents(true, true, true), worn = true, stopped = true,
            alerting = state == FlowState.S4, incidentActive = true, incidentSmsSent = true,
            facts = IncidentFacts(firstAlertAtMs = mono0, noResponseAtMs = mono0 + 35_000, responseAtMs = responseAt, eventId = id),
        ),
        Owner.CAR,
    )

    private fun carSync(f: FlowStatus?, ageMs: Long = 0) {
        mono += 2_000
        engine.buildSync("tok", mono)
        val peer = CarPeer(1, wall0, mono + serverOffset - ageMs, Vehicle(speedKmh = 0.0, gear = Gear.P), f)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, emptyList()), mono, mono)
        engine.tick(mono)
    }

    @Test fun carSaysSentButNoRecord_phoneSendsAfter10s_thenCancelsOnResponse() {
        repeat(4) { carSync(carS4()) }
        assertEquals("10초 전에는 차 보낼 함을 기다린다", 0, fx.sms.size)
        repeat(2) { carSync(carS4()) }
        assertEquals(1, fx.sms.size)
        repeat(3) { carSync(carS4()) }
        assertEquals("한 번만", 1, fx.sms.size)
        carSync(carS4(responseAt = mono, state = FlowState.S2))
        assertEquals(2, fx.sms.size)
    }

    /** 넘겨받을 때는 기다리지 않는다 — 차 링크가 끊겨 휴대폰이 주인이 되면 바로 보낸다. */
    @Test fun takeOver_sendsImmediately() {
        carSync(carS4())
        assertEquals(0, fx.sms.size)
        repeat(6) { mono += 2_000; engine.onSyncError(mono); engine.tick(mono) }
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(1, fx.sms.size)
    }
}
