// 문자 결과 싣기(A-1)와 원격 사건 문자 방어(A-2 휴대폰 쪽) 시험 — flow.smsDone, 10분 넘게 묵은 사건 · 참여 전 본 적 없는 사건의 문자는 보내지 않음
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.SmsDone
import com.example.guardian.shared.protocol.SmsDoneState
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsDoneGuardTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { true })

    private fun server() = mono + serverOffset
    private fun carFlow(state: FlowState, eventId: String?) = FlowStatus.of(
        FlowSnapshot(state = state, powerOn = true, consents = Consents(true, true, true), incidentActive = eventId != null, facts = IncidentFacts(eventId = eventId)),
        Owner.CAR,
    )

    private fun sms(eventId: String, kind: GuardianSmsWireKind = GuardianSmsWireKind.EVENT, test: Boolean = false): Command {
        val p = Payload.SendGuardianSms(kind, eventId, WireFacts(SnapshotClock.SERVER, firstAlertAt = server() - 35_000), test = test)
        return Command("cmd-${ids++}", p.type, p.toJson(), 30_000, test, Party.CAR, CommandTo.PHONE)
    }

    private fun carSync(flow: FlowStatus?, commands: List<Command> = emptyList()) {
        mono += 2_000
        engine.buildSync("tok", mono)
        val peer = CarPeer(1, wall0, server(), Vehicle(speedKmh = 0.0, gear = Gear.P), flow)
        engine.onSyncResponse(PhoneSyncResponse(server(), peer, null, commands), mono, mono)
        engine.tick(mono)
    }

    private fun done() = engine.buildSync("tok", mono).flow!!.smsDone.orEmpty()

    // ── A-1 ──

    @Test fun sentEventIsInSmsDone_evenWhenCarOwns() {
        carSync(carFlow(FlowState.S0, null))
        val id = "event-${server()}"
        carSync(carFlow(FlowState.S4, id), listOf(sms(id)))
        assertEquals(1, fx.sms.size)
        assertEquals(Owner.CAR, engine.owner)
        assertEquals(listOf(SmsDone(id, GuardianSmsWireKind.EVENT, SmsDoneState.SENT)), done())
    }

    @Test fun waitingResultIsPending() {
        fx.smsDelivery = null
        carSync(carFlow(FlowState.S0, null))
        val id = "event-${server()}"
        carSync(carFlow(FlowState.S4, id), listOf(sms(id)))
        assertEquals(SmsDoneState.PENDING, done().single().done)
    }

    @Test fun testSmsNotCarried() {
        carSync(carFlow(FlowState.S0, null))
        val id = "event-${server()}"
        carSync(carFlow(FlowState.S4, id), listOf(sms(id, test = true)))
        engine.executor.pending().forEach { engine.confirmTestSms(it.key, send = true) }
        assertTrue(done().isEmpty())
    }

    // ── A-2 ──

    @Test fun staleEventSmsIsNotSent_recordedNotSent() {
        carSync(carFlow(FlowState.S0, null))
        val id = "event-${server() - 11 * 60_000}"
        carSync(carFlow(FlowState.S4, id), listOf(sms(id)))
        assertTrue(fx.sms.isEmpty())
        assertEquals(listOf(SmsDone(id, GuardianSmsWireKind.EVENT, SmsDoneState.NOT_SENT)), done())
    }

    @Test fun eventBeforeJoinNeverSeen_isNotSent() {
        store.pairJoinedAtServerMs = PhoneStore.JOIN_PENDING
        carSync(null) // 참여 뒤 첫 응답 — 참여 시각을 남긴다
        val joined = store.pairJoinedAtServerMs!!
        assertTrue(joined > 0)
        val id = "event-${joined - 60_000}" // 참여 1분 전에 시작된 사건
        carSync(carFlow(FlowState.S4, id), listOf(sms(id)))
        assertTrue("참여 전 · 본 적 없는 사건", fx.sms.isEmpty())
    }

    /** 차가 보정 전에 연 사고(accident-L…) — 지금 신선한 차 흐름의 진행 중 사건이면 보낸다(app-qa · 공용 liveCarEventId). */
    @Test fun localIdAccidentOfLiveCarFlow_isSent() {
        store.pairJoinedAtServerMs = PhoneStore.JOIN_PENDING
        carSync(null)
        val id = "accident-L123"
        carSync(carFlow(FlowState.S5, id), listOf(sms(id, GuardianSmsWireKind.ACCIDENT)))
        assertEquals(1, fx.sms.size)
    }

    /** 같은 L 꼴이라도 지금 차의 사건이 아니면(다시 짝지은 뒤 남은 옛 사건) 막는다. */
    @Test fun localIdNotLive_isBlocked() {
        store.pairJoinedAtServerMs = PhoneStore.JOIN_PENDING
        carSync(null)
        carSync(carFlow(FlowState.S0, null), listOf(sms("accident-L999", GuardianSmsWireKind.ACCIDENT)))
        assertTrue(fx.sms.isEmpty())
    }

    /** sec-audit 결함 1 — 막혀서 남긴 「보내지 않음」 기록은 본 사건이 아니다. 대조 길(다른 지시 id) · 같은 id 의 정차 관찰도 막힌다. */
    @Test fun blockedRecordDoesNotUnlockLaterPaths() {
        store.pairJoinedAtServerMs = PhoneStore.JOIN_PENDING
        carSync(null)
        val id = "event-${store.pairJoinedAtServerMs!! - 60_000}"
        val s4 = FlowStatus.of(
            FlowSnapshot(
                state = FlowState.S4, powerOn = true, consents = Consents(true, true, true), incidentActive = true, incidentSmsSent = true,
                facts = IncidentFacts(eventId = id),
            ),
            Owner.CAR,
        )
        carSync(s4, listOf(sms(id)))
        assertTrue("차 지시는 막힘", fx.sms.isEmpty())
        repeat(8) { carSync(s4) } // 16초 — 대조 길이 10초 기다린 뒤 낼 차례
        carSync(s4, listOf(sms(id, GuardianSmsWireKind.OBSERVE)))
        assertTrue("대조 길 · 정차 관찰도 막힘", fx.sms.isEmpty())
    }

    @Test fun eventAfterJoin_isSent() {
        store.pairJoinedAtServerMs = PhoneStore.JOIN_PENDING
        carSync(null)
        val id = "event-${server()}"
        carSync(carFlow(FlowState.S4, id), listOf(sms(id)))
        assertEquals(1, fx.sms.size)
    }

    /** 다시 짝지은 같은 휴대폰 — 참여 전에 그 사건 흐름을 봤으면 보낸다. */
    @Test fun eventBeforeJoinButSeenBeforeJoin_isSent() {
        val id = "event-${server()}"
        carSync(carFlow(FlowState.S4, id))
        store.pairJoinedAtServerMs = PhoneStore.JOIN_PENDING
        carSync(carFlow(FlowState.S4, id), listOf(sms(id)))
        assertEquals(1, fx.sms.size)
    }

    @Test fun cancelIsNeverBlocked() {
        carSync(carFlow(FlowState.S0, null))
        val id = "event-${server()}"
        carSync(carFlow(FlowState.S4, id), listOf(sms(id)))
        mono += 11 * 60_000
        carSync(carFlow(FlowState.S2, id), listOf(sms(id, GuardianSmsWireKind.CANCEL)))
        assertEquals(2, fx.sms.size)
        assertFalse(fx.smsLog.any { it.second == false })
    }
}
