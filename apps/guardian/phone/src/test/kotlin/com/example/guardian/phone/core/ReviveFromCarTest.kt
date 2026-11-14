// 다시 뜬 휴대폰 되살리기 시험(C-5 · 앱2-중4) — 차가 주인인 실제 경보 흐름을 보면 알람 · 119 본문 · 문자 결과 줄을 사건 id 마다 한 번 되살린다, 소리를 줄인 사건은 다시 켜지 않는다
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.GuardianSmsDedup
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviveFromCarTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val id = "event-${mono0 + serverOffset - 60_000}"
    private fun newEngine() = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { true })

    private fun carFlow(state: FlowState = FlowState.S4, test: Boolean = false) = FlowStatus.of(
        FlowSnapshot(
            state = state, sinceMs = mono, test = test, powerOn = true, consents = Consents(true, true, true), worn = true, stopped = true,
            alerting = true, incidentActive = true, incidentSmsSent = true, composeOpened = state == FlowState.S4,
            facts = IncidentFacts(firstAlertAtMs = mono - 60_000, noResponseAtMs = mono - 25_000, stoppedAtMs = mono - 70_000, eventId = id),
        ),
        Owner.CAR,
    )

    private fun sync(e: PhoneEngine, f: FlowStatus) {
        mono += 2_000
        e.buildSync("tok", mono)
        e.onSyncResponse(PhoneSyncResponse(mono + serverOffset, CarPeer(1, wall0, mono + serverOffset, Vehicle(speedKmh = 0.0, gear = Gear.P), f), null, emptyList()), mono, mono)
        e.tick(mono)
    }

    @Test fun restartedPhoneRevivesAlarm119AndResultLine() {
        store.smsRecords = listOf(GuardianSmsDedup.Record(GuardianSmsWireKind.EVENT, id, Party.CAR, null, null, false, wall0 - 20_000, sent = true))
        val e = newEngine()
        sync(e, carFlow())
        assertEquals("알람을 되살린다", 1, fx.alarms.size)
        assertNotNull("119 본문(P12)을 되살린다", e.executor.last119)
        assertEquals("작성 화면은 열지 않는다(G4)", 0, fx.composed.size)
        assertTrue("문자 결과 줄", fx.smsLog.contains("EVENT" to true))
        // 같은 사건은 한 번만
        sync(e, carFlow())
        assertEquals(1, fx.alarms.size)
        assertEquals(1, fx.smsLog.size)
    }

    @Test fun mutedIncidentIsNotRung() {
        store.mutedEventId = id
        val e = newEngine()
        sync(e, carFlow())
        assertTrue(fx.alarms.isEmpty())
        assertNotNull(e.executor.last119)
    }

    @Test fun testFlowIsNotRevived() {
        val e = newEngine()
        sync(e, carFlow(test = true))
        assertTrue(fx.alarms.isEmpty())
        assertNull(e.executor.last119)
    }

    /** 휴대폰에서 작성 화면을 열면(소리 줄이기) 그 사건 id 를 저장한다. */
    @Test fun openingComposeRemembersMutedIncident() {
        val e = newEngine()
        sync(e, carFlow())
        assertTrue(e.openCompose(mono))
        assertEquals(id, store.mutedEventId)
    }
}
