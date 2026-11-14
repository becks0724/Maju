// 넘겨받은 차 사건의 문자 방어 시험(PM 결정 19 · sec-audit) — 차에서 온 사건 id 는 휴대폰 상태 기계가 낸 문자도 원격으로 보고 방어 · 원격 상한을 건다
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
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdoptedIncidentGuardTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private var engine = newEngine()
    private fun newEngine() = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { true })

    private fun server() = mono + serverOffset
    private val drive = Vehicle(speedKmh = 60.0, gear = Gear.D)
    private val parked = Vehicle(speedKmh = 0.0, gear = Gear.P)

    /** 차 흐름 — 중계 시계(SERVER). [start] 는 사건 시작(첫 경보 · 사고)의 중계 시각. */
    private fun flow(
        state: FlowState, eventId: String?, start: Long? = null, owner: Owner = Owner.CAR, alerting: Boolean = false, stage: Int = 0,
        stopped: Boolean = false, responseAt: Long? = null, smsSent: Boolean = false, accSent: Boolean = false,
    ): FlowStatus {
        val acc = eventId?.startsWith("accident") == true
        val snap = FlowSnapshot(
            state = state, sinceMs = start ?: server(), powerOn = true, consents = Consents(true, true, true), worn = true,
            incidentActive = eventId != null, incidentSmsSent = smsSent, accidentSent = accSent, alerting = alerting, stage = stage, alertStartMs = start ?: 0, stopped = stopped,
            facts = IncidentFacts(
                firstAlertAtMs = if (acc) null else start, accidentAtMs = if (acc) start else null, responseAtMs = responseAt, eventId = eventId,
            ),
        )
        return FlowStatus(snap.state, snap.sinceMs, owner, false, snap, SnapshotClock.SERVER)
    }

    private fun carSync(f: FlowStatus?, v: Vehicle = parked, commands: List<Command> = emptyList()) {
        mono += 2_000
        engine.buildSync("tok", mono)
        engine.onSyncResponse(PhoneSyncResponse(server(), CarPeer(1, wall0, server(), v, f), null, commands), mono, mono)
        engine.tick(mono)
    }

    private fun join() { store.pairJoinedAtServerMs = PhoneStore.JOIN_PENDING; carSync(null) }
    private fun ticks(n: Int) = repeat(n) { mono += 2_000; engine.tick(mono) }
    private fun events() = store.smsRecords.count { it.sent && it.kind != GuardianSmsWireKind.CANCEL }

    /** 차 토큰 쪽이 사건을 열고 내려놓기를 되풀이(새 id) — 10분 안 원격 상한 3통을 넘지 않는다(sec-audit p4, 고치기 전 43통). */
    @Test fun takeoverLoopRespectsRemoteLimit() {
        carSync(flow(FlowState.S0, null))
        val t0 = mono
        while (mono - t0 < 10 * 60_000) {
            val start = server() - 120_000
            val id = "event-${server()}"
            carSync(flow(FlowState.S1, id, start, alerting = true, stage = 1), drive)
            carSync(flow(FlowState.S1, id, start, alerting = true, stage = 1, owner = Owner.PHONE), drive)
            ticks(3)
            carSync(flow(FlowState.S0, id, start), drive)
            carSync(flow(FlowState.S0, null), drive)
        }
        assertEquals("10분 3통(전에는 43통)", RemoteSmsLimit.SHORT_MAX, fx.sms.size)
    }

    /** 응답 뒤(S1R)에 나쁜 판정 · 10분 전 약해짐을 심은 차 스냅숏 — 휴대폰이 자기 워치 값 없이 새 사건을 열지 않는다(sec-audit p7b). */
    private fun seededS1R(owner: Owner, id: String, since: Long? = null): FlowStatus {
        val old = server() - 10 * 60_000
        val snap = FlowSnapshot(
            state = FlowState.S1R, sinceMs = since ?: old, powerOn = true, consents = Consents(true, true, true), worn = true,
            verdict = com.example.guardian.shared.bio.WristVerdict.PULSE_ABSENT, incidentActive = true, weakSinceMs = old, watchSeenAtMs = server(),
            lastNormalAtMs = old, alertStartMs = old, facts = IncidentFacts(firstAlertAtMs = old, responseAtMs = old + 5_000, eventId = id),
        )
        return FlowStatus(snap.state, snap.sinceMs, owner, false, snap, SnapshotClock.SERVER)
    }

    @Test fun seededRestStateDoesNotOpenNewIncident() {
        join()
        val id = "event-${server() - 600_000}"
        carSync(flow(FlowState.S0, null), drive)
        carSync(seededS1R(Owner.CAR, id), drive)
        carSync(seededS1R(Owner.PHONE, id), drive)
        repeat(150) { mono += 2_000; engine.onSyncError(mono); engine.tick(mono) }
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals("워치 값 없이 새 사건 문자를 내지 않는다", 0, fx.sms.size)
    }

    private fun badWatch() = com.example.guardian.shared.protocol.Watch(
        com.example.guardian.shared.bio.WristVerdict.PULSE_ABSENT, 0, wall0, worn = true,
        source = com.example.guardian.shared.protocol.WatchSource.SENSOR_SDK, test = false, responseAt = null,
    )

    /** 차 링크가 끊긴 채 휴대폰 워치가 나쁜 값 — 새 사건(휴대폰이 만든 id)이 열릴 때까지. 그 사건 id. */
    private fun ownWatchOpensIncident(oldId: String?, steps: Int = 40): String {
        repeat(steps) { mono += 2_000; engine.onDataLayerWatch(badWatch(), mono); engine.onSyncError(mono); engine.tick(mono) }
        val st = engine.status(mono)
        assertTrue("새 사건이 열림 — ${st.flowState}", st.flowState in PhoneEngine.ALARM_STATES || st.flowState == FlowState.S4)
        return st.eventId!!.also { assertTrue(it != oldId) }
    }

    /**
     * 넘겨받은 기계가 연 새 사건(휴대폰이 만든 id)도 차에서 온 사건으로 적힌다 — 원격 방어 · 상한을 탄다(app-qa 조건: 이 표시 줄을 지우면 실패해야 함).
     * 대조군 — 넘겨받지 않은 휴대폰이 스스로 연 사건은 적히지 않는다.
     */
    @Test fun adoptedMachineNewIdsAreRemote() {
        carSync(flow(FlowState.S0, null))
        val x = "event-${server() - 600_000}"
        // 방금 응답한 S1R — 쉬는 시간이 남아 S0 을 거치지 않고, 휴대폰 워치의 나쁜 값으로 바로 새 사건(재경보)을 연다
        val since = server()
        carSync(seededS1R(Owner.CAR, x, since), drive)
        carSync(seededS1R(Owner.PHONE, x, since), drive)
        assertEquals(Owner.PHONE, engine.owner)
        val y = ownWatchOpensIncident(x, steps = 15)
        assertTrue("넘겨받은 기계의 새 id 는 차에서 온 사건", engine.isCarOriginEvent(y))
        // 주행 중 무응답이 이어져 그 사건 문자가 나가면 원격 상한 칸에 세어진다
        repeat(30) { mono += 2_000; engine.onDataLayerWatch(badWatch(), mono); engine.onSyncError(mono); engine.tick(mono) }
        assertTrue("사건 문자가 나감", store.smsRecords.any { it.sent && it.eventId == y })
        assertTrue("원격 상한 칸에 세어짐", RemoteSmsLimit.decode(store.remoteSmsLimit).sent.any { it.eventId == y })
    }

    /**
     * 독립 검토 ① — 넘겨받은 기계가 보정값 없이(중계에 60초 넘게 못 닿음) 휴대폰에서 새로 연 사건(event-L…)의 사건 문자는 나간다.
     * 휴대폰이 참여한 뒤 스스로 연 사건이라 「참여 전 사건」일 수 없다 — 원격 상한만 건다.
     */
    @Test fun adoptedMachineNewLocalIdIncident_smsSent() {
        join()
        repeat(5) { carSync(flow(FlowState.S0, null), drive) }
        val x = "event-${server() - 30_000}"
        val since = server()
        carSync(seededS1R(Owner.CAR, x, since), drive)
        carSync(seededS1R(Owner.PHONE, x, since), drive)
        repeat(35) { mono += 2_000; engine.onSyncError(mono); engine.tick(mono) } // 70초 — 보정값이 없어진다
        val y = ownWatchOpensIncident(x, steps = 40) // 주행 중 무응답 → S3
        assertTrue("보정값 없이 연 새 id — $y", y.contains("-L"))
        assertEquals("새 사건 문자 1통", 1, store.smsRecords.count { it.sent && it.eventId == y && it.kind == GuardianSmsWireKind.EVENT })
    }

    /**
     * 독립 검토 ② — 응답 뒤 이어진 사고(S5 가 앞 사건의 응답 시각을 이어 받음)의 사고 문자도 진행 중으로 보고 60초 뒤 다시 보낸다.
     * 사고는 응답으로 끝나지 않는다(CarSmsReconcile 과 같은 기준).
     */
    @Test fun accidentAfterResponse_resendsAfter60s() {
        repeat(3) { carSync(flow(FlowState.S0, null)) }
        val id = "accident-${server()}"
        fx.smsDelivery = false
        carSync(flow(FlowState.S5, id, server() - 2_000, accSent = true, responseAt = server() - 30_000))
        ticks(8) // 차가 끊김 — 넘겨받으며 대조로 사고 문자를 즉시 보내려다 실패
        assertEquals(Owner.PHONE, engine.owner)
        assertTrue("첫 시도", fx.sms.isNotEmpty())
        ticks(20) // 40초 — 지시 ttl(30초) 안의 10초 재시도도 모두 실패해 지시 길은 끝난다
        fx.smsDelivery = true
        ticks(35) // 70초 — 남은 길은 진행 중 사건의 60초 다시 보내기뿐
        assertTrue("60초 뒤 다시 보내 사고 문자가 나감", store.smsRecords.any { it.sent && it.kind == GuardianSmsWireKind.ACCIDENT && it.eventId == id })
    }

    @Test fun ownIncidentWithoutTakeover_isNotCarOrigin() {
        val y = ownWatchOpensIncident(null)
        assertFalse(engine.isCarOriginEvent(y))
    }

    /** (b) 시각을 못 읽는 id — 새로 참여한 휴대폰은 참여 30분 전에 열린 진행 중 accident-L 사고 문자를 보내지 않는다(차 흐름의 SERVER 시작 시각, sec-audit p2). */
    @Test fun localIdStartedBeforeJoin_blockedByFlowStart() {
        join()
        val start = store.pairJoinedAtServerMs!! - 30 * 60_000
        val id = "accident-L777"
        val p = Payload.SendGuardianSms(GuardianSmsWireKind.ACCIDENT, id, WireFacts(SnapshotClock.LOCAL, accidentAt = 1_000), test = false)
        carSync(flow(FlowState.S5, id, start, accSent = true), commands = listOf(Command("acc-1", p.type, p.toJson(), 30_000, false, Party.CAR, CommandTo.PHONE)))
        repeat(8) { carSync(flow(FlowState.S5, id, start, accSent = true)) }
        assertEquals(0, fx.sms.size)
    }

    /** (b) 넘겨받은 직후 대조(차가 묵음 · 지금 차의 사건 아님)도 쥐고 있던 차 흐름의 시작 시각으로 판정해 정상 사고 문자 1통(app-qa 가 남긴 놓침, sec-audit p6). */
    @Test fun takeoverLocalIdAccident_sentOnce() {
        join()
        repeat(40) { carSync(flow(FlowState.S0, null)) }
        val id = "accident-L999"
        carSync(flow(FlowState.S5, id, server() - 3_000, accSent = true))
        repeat(15) { mono += 2_000; engine.onSyncError(mono); engine.tick(mono) }
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(1, fx.sms.size)
    }

    /** sec-audit p10 — 사건 id 가 비어 있는 경보 흐름을 넘겨받게 하는 고리도 10분 3통 이하(고치기 전 43통). */
    @Test fun idlessIncidentTakeoverLoopRespectsLimit() {
        carSync(flow(FlowState.S0, null))
        val t0 = mono
        while (mono - t0 < 10 * 60_000) {
            val start = server() - 120_000
            carSync(flow(FlowState.S1, null, start, alerting = true, stage = 1).let { it.copy(snapshot = it.snapshot.copy(incidentActive = true)) }, drive)
            carSync(flow(FlowState.S1, null, start, alerting = true, stage = 1, owner = Owner.PHONE).let { it.copy(snapshot = it.snapshot.copy(incidentActive = true)) }, drive)
            ticks(3)
            carSync(flow(FlowState.S0, null), drive)
        }
        // id 없는 사건은 넘겨받지 않는다 — 휴대폰은 자기 워치 값으로만 판정하고, 이 시험에는 워치 값이 없으니 문자도 없다
        assertEquals("넘겨받기 0회 · 0통(고치기 전 50통)", 0, fx.sms.size)
        assertTrue(store.carOriginEventIds.isEmpty())
    }

    /** sec-audit p11 — 새로 참여한 휴대폰이 참여 전에 시작된 id 없는 차 사건을 넘겨받아도 0통. */
    @Test fun idlessPreJoinIncidentTakeover_noSms() {
        join()
        val start = store.pairJoinedAtServerMs!! - 120_000
        val f = { o: Owner -> flow(FlowState.S1, null, start, alerting = true, stage = 1, owner = o).let { it.copy(snapshot = it.snapshot.copy(incidentActive = true)) } }
        carSync(f(Owner.CAR), drive)
        carSync(f(Owner.PHONE), drive)
        ticks(40)
        assertEquals(0, fx.sms.size)
    }

    /** 참여 전에 시작된 차 사건을 넘겨받아도 휴대폰 상태 기계가 사건 문자를 내지 않는다(sec-audit p3). */
    @Test fun takeoverOfPreJoinIncident_noSms() {
        join()
        val start = store.pairJoinedAtServerMs!! - 120_000
        val id = "event-$start"
        carSync(flow(FlowState.S1, id, start, alerting = true, stage = 1), drive)
        carSync(flow(FlowState.S1, id, start, alerting = true, stage = 1, owner = Owner.PHONE), drive)
        ticks(40)
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(0, fx.sms.size)
    }

    /** 정상 넘겨받기 — 참여 뒤 시작된 사건은 사건 문자 1통, 응답하면 취소 1통. */
    @Test fun normalTakeover_eventThenCancel() {
        join()
        repeat(30) { carSync(flow(FlowState.S0, null)) } // 참여 뒤 1분
        val start = server() - 40_000
        val id = "event-$start"
        carSync(flow(FlowState.S4, id, start, alerting = true, stopped = true, smsSent = true))
        ticks(8) // 차가 끊김(차 문자는 휴대폰에 닿지 않음) — 넘겨받아 대조로 보낸다
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals("사건 문자 1통", 1, events())
        engine.phoneResponse(mono)
        ticks(2)
        assertEquals("취소 1통", 1, store.smsRecords.count { it.sent && it.kind == GuardianSmsWireKind.CANCEL })
    }

    /** 휴대폰이 처음부터 스스로 연 사건은 방어 · 상한 밖이다 — 차 지시로 3통이 찬 뒤에도 나간다. */
    @Test fun ownIncidentIsOutsideLimit() {
        carSync(flow(FlowState.S0, null))
        for (i in 1..3) {
            val p = Payload.SendGuardianSms(GuardianSmsWireKind.EVENT, "event-${server() + i}", WireFacts(SnapshotClock.SERVER, firstAlertAt = server()), test = false)
            carSync(flow(FlowState.S0, null), commands = listOf(Command("cmd-$i", p.type, p.toJson(), 30_000, false, Party.CAR, CommandTo.PHONE)))
        }
        assertEquals(3, fx.sms.size)
        val e = engine.executor
        val own = Payload.SendGuardianSms(GuardianSmsWireKind.EVENT, "event-own", WireFacts(SnapshotClock.LOCAL, firstAlertAt = mono), test = false)
        e.execute(Command("own-1", own.type, own.toJson(), 30_000, false, Party.PHONE, CommandTo.PHONE), ExecContext(stopped = true, testSession = false, nowLocal = mono, nowWall = wall0))
        assertEquals("휴대폰 사건은 상한 밖", 4, fx.sms.size)
    }

    /** 앱을 다시 띄워 저장된(넘겨받은) 사건을 되살려도 「차에서 온 사건」 표시가 남는다 — 상한 밖으로 나가지 않는다. */
    @Test fun adoptedMarkSurvivesRestart() {
        carSync(flow(FlowState.S0, null))
        val start = server() - 5_000
        val id = "event-$start"
        carSync(flow(FlowState.S1, id, start, alerting = true, stage = 1), drive)
        carSync(flow(FlowState.S1, id, start, alerting = true, stage = 1, owner = Owner.PHONE), drive)
        ticks(1)
        assertEquals(Owner.PHONE, engine.owner)
        assertTrue(id in store.carOriginEventIds)
        engine = newEngine()
        assertTrue("새 엔진도 표시를 읽는다", engine.isCarOriginEvent(id))
    }
}
