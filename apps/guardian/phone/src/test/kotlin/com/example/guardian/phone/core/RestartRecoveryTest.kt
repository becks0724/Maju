// 프로세스 재시작 뒤 상태 보존 시험(코드 리뷰 중 4) — 휴대폰 주인은 같은 부팅 · 재부팅 모두 사건을 이어 가 문자를 다시 보내지 않고, 차의 낡은 비사건 흐름에 지워지지 않으며, GPS 공백 · 5분 창 · 끄기 · G2 저장 내용 · 테스트 사건 복원, 차 주인은 처리한 지시 · 문자 기록으로 중복을 막는다
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.WristVerdict
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
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.text.SmsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartRecoveryTest {
    private val store = MemoryStore()
    private var ids = 0
    private var relay = false
    private val serverOffset = 5_000_000_000L
    private val wall0 = 1_793_000_000_000L

    /** 벽시계와 단조 시계를 따로 둔다 — 재부팅은 단조만 0 근처로 되돌린다. */
    private var wall = wall0
    private var mono = 1_000_000L

    private fun newEngine(fx: FakeEffects) = PhoneEngine(store, fx, { "c${ids++}" }, { wall }, wall0, mono, { relay })

    private fun real(v: WristVerdict) = Watch(v, 70, wall, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = null)
    private fun loc() = Loc(37.57590, 126.97690, 8.0, 0.0, wall, "서울 종로구 세종대로 172")
    private fun feed(e: PhoneEngine, w: Watch) { e.onDataLayerWatch(w, mono); e.onLocation(loc(), mono) }

    private fun advance(e: PhoneEngine, ms: Long, each: () -> Unit) {
        val end = mono + ms
        while (mono < end) { mono += 1_000; wall += 1_000; each(); e.tick(mono) }
    }

    private fun guardian(fx: FakeEffects) = fx.sms.filter { it.first == GUARDIAN }

    /** 휴대폰 주인(중계 없음) — 정차 중 맥박 없음 40초 → S4 · 사건 문자 한 통. */
    private fun runToNoResponse(fx: FakeEffects): PhoneEngine {
        val e = newEngine(fx)
        feed(e, real(WristVerdict.NORMAL))
        e.tick(mono)
        assertEquals(Owner.PHONE, e.owner)
        advance(e, 40_000) { feed(e, real(WristVerdict.PULSE_ABSENT)) }
        assertEquals(FlowState.S4, e.status(mono).flowState)
        assertEquals(1, guardian(fx).size)
        assertNotNull("사건 중에는 스냅숏이 저장돼 있다", store.savedFlow)
        return e
    }

    /** 다시 뜬 엔진이 사건을 이어 가는지 — 상태 · 경과 · 알람 · P12, 그리고 같은 사건 문자를 다시 보내지 않고 응답 뒤 취소 문자만 한 통. */
    private fun assertContinues(fx2: FakeEffects, e2: PhoneEngine, secBefore: Int) {
        e2.tick(mono)
        assertEquals(Owner.PHONE, e2.owner)
        val st = e2.status(mono)
        assertEquals("진행 중 사건을 이어 간다", FlowState.S4, st.shownState)
        assertTrue("첫 경보부터 지난 시간이 이어진다(${st.noResponseSec})", (st.noResponseSec ?: 0) >= secBefore)
        assertEquals("경보 2 알람을 다시 켠다", listOf("S4"), fx2.alarms)
        assertEquals("정차 뒤 무응답 화면(P12)을 다시 띄운다", 1, fx2.noResponses.size)
        assertTrue(fx2.previews.isEmpty())
        advance(e2, 60_000) { feed(e2, real(WristVerdict.PULSE_ABSENT)) }
        assertTrue("같은 사건의 문자를 다시 보내지 않는다", guardian(fx2).isEmpty())
        e2.phoneResponse(mono)
        assertEquals(FlowState.S2, e2.status(mono).flowState)
        assertEquals("응답 뒤 취소 문자 한 통", 1, guardian(fx2).size)
        assertFalse(guardian(fx2)[0].second.startsWith(SmsText.TEST_PREFIX))
        assertTrue(store.sent.any { it.startsWith("CANCEL:") })
    }

    @Test fun phoneOwner_restartInSameBoot_continuesIncidentWithoutSecondSms() {
        val fx1 = FakeEffects()
        val e1 = runToNoResponse(fx1)
        val sec = e1.status(mono).noResponseSec!!
        // 프로세스가 죽고 3초 뒤 같은 부팅에서 다시 뜬다(단조 시계 이어짐)
        mono += 3_000; wall += 3_000
        val fx2 = FakeEffects()
        assertContinues(fx2, newEngine(fx2), sec + 3)
    }

    @Test fun phoneOwner_restartAfterReboot_continuesIncidentWithWallClock() {
        val fx1 = FakeEffects()
        val e1 = runToNoResponse(fx1)
        val sec = e1.status(mono).noResponseSec!!
        // 재부팅 — 단조 시계가 0 근처에서 다시 시작, 벽시계만 3분 흐름(PM 결정 2026-11-07 — 재부팅을 덮는 5분 창)
        mono = 7_000; wall += 180_000
        val fx2 = FakeEffects()
        assertContinues(fx2, newEngine(fx2), sec + 180)
    }

    /** 저장이 복원 창(5분)을 넘었으면 옛 사건을 되살리지 않고 읽는 즉시 지운다 — 이어지는 무응답은 새 사건이라 문자가 나간다. */
    @Test fun phoneOwner_staleSavedFlowIsDroppedOnRead() {
        runToNoResponse(FakeEffects())
        assertEquals(300_000L, PhoneEngine.RESTORE_MAX_AGE_MS)
        mono += PhoneEngine.RESTORE_MAX_AGE_MS + 1_000; wall += PhoneEngine.RESTORE_MAX_AGE_MS + 1_000
        val fx2 = FakeEffects()
        val e2 = newEngine(fx2)
        assertNull("창이 지난 저장은 읽을 때 지운다(G2)", store.savedFlow)
        e2.tick(mono)
        assertTrue(e2.status(mono).shownState in setOf(null, FlowState.OFF, FlowState.S0))
        assertTrue(fx2.alarms.isEmpty())
        assertTrue(fx2.noResponses.isEmpty())
        advance(e2, 40_000) { feed(e2, real(WristVerdict.PULSE_ABSENT)) }
        assertEquals("새 사건이라 문자가 나간다", 1, guardian(fx2).size)
    }

    /** 복원 직후 GPS 가 아직 없어도(위치 공백) 복원한 정차 · S4 를 지킨다 — 속도 null 두 번으로 주행 → S3 가 되지 않는다. */
    @Test fun phoneOwner_restartWithoutGpsKeepsRestoredParkedState() {
        runToNoResponse(FakeEffects())
        mono += 3_000; wall += 3_000
        val fx2 = FakeEffects()
        val e2 = newEngine(fx2)
        e2.tick(mono)
        assertEquals(FlowState.S4, e2.status(mono).shownState)
        // 위치 없이 워치 값만 30초
        advance(e2, 30_000) { e2.onDataLayerWatch(real(WristVerdict.PULSE_ABSENT), mono) }
        assertEquals("GPS 공백 동안 복원 상태 유지", FlowState.S4, e2.status(mono).shownState)
        assertTrue(e2.status(mono).stopped)
        // 첫 위치(정차)가 오면 그대로 S4
        advance(e2, 4_000) { feed(e2, real(WristVerdict.PULSE_ABSENT)) }
        assertEquals(FlowState.S4, e2.status(mono).shownState)
        assertTrue(guardian(fx2).isEmpty())
    }

    /**
     * 코드 리뷰 2026-11-08 하3 — 복원 직후 GPS 가 없어도 119 지시는 복원한 상태 기계의 정차로 처리한다.
     * 대입 전에 바깥 상태를 되살리면 정지 판정이 GPS(모름 = 주행)로 떨어져 「차가 멈추면 119 …」 안내가 잘못 남았다.
     */
    @Test fun phoneOwner_restartWithoutGps_noWaitingStopNotice() {
        runToNoResponse(FakeEffects())
        mono += 3_000; wall += 3_000
        val fx2 = FakeEffects()
        val e2 = newEngine(fx2)
        e2.tick(mono)
        assertEquals(FlowState.S4, e2.status(mono).shownState)
        assertFalse("정차인데 「차가 멈추면 119 …」 안내", Notice.COMPOSE_WAITING_STOP in fx2.notices)
        assertEquals("P12 는 바로 한 번", 1, fx2.noResponses.size)
    }

    /**
     * 저장에서 되살린 정지는 유예(60초)가 끝나도 위치가 없으면 지금 규칙으로 돌아간다 — 속도 모름은 주행이라 S4 → S3(G5 「모름을 정지로 보지 않는다」).
     * GPS 가 움직일 때까지 정지를 쥐는 것은 넘겨받은 흐름만이다(B-4 끝 문장, PM 결정 24 — StrandedAlarmTakeOverTest.takenOverStoppedFlowStaysStoppedWithoutGps).
     */
    @Test fun phoneOwner_restartGpsGraceExpiresThenNormalRule() {
        runToNoResponse(FakeEffects())
        mono += 3_000; wall += 3_000
        val fx2 = FakeEffects()
        val e2 = newEngine(fx2)
        e2.tick(mono)
        advance(e2, PhoneEngine.VEHICLE_GRACE_MS - 2_000) { e2.onDataLayerWatch(real(WristVerdict.PULSE_ABSENT), mono) }
        assertEquals(FlowState.S4, e2.status(mono).shownState)
        advance(e2, 15_000) { e2.onDataLayerWatch(real(WristVerdict.PULSE_ABSENT), mono) }
        assertEquals("유예 뒤에는 속도 모름 = 주행", FlowState.S3, e2.status(mono).shownState)
    }

    // ── 중계 살아 있음 · 차 후보와 저장의 우선순위 ──

    private fun carFlowAt(state: FlowState, eventId: String?, ageMs: Long): FlowStatus {
        val t = mono - ageMs
        val facts = if (eventId == null) IncidentFacts() else IncidentFacts(firstAlertAtMs = t - 40_000, stoppedAtMs = t - 50_000, noResponseAtMs = t - 5_000, eventId = eventId)
        val incident = eventId != null
        return FlowStatus.of(
            FlowSnapshot(
                state = state, sinceMs = t, powerOn = true, consents = Consents(true, true, true), stopped = true,
                incidentActive = incident, alerting = incident, incidentSmsSent = incident, composeOpened = incident, facts = facts,
            ),
            Owner.CAR,
        )
    }

    /** 차가 [ageMs] 전에 중계에 닿았고 그때 흐름이 [flow]. */
    private fun carSyncAged(e: PhoneEngine, flow: FlowStatus?, ageMs: Long) {
        e.buildSync("tok", mono)
        val peer = CarPeer(1, wall, mono + serverOffset - ageMs, Vehicle(speedKmh = 0.0, gear = Gear.P, parkingBrake = true), flow)
        e.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, emptyList()), mono, mono)
    }

    /** 차가 S0 에서 끊겨 휴대폰이 주인으로 S4 · 문자 1통까지. 중계에는 차의 낡은 S0 이 남아 있다. */
    private fun carDroppedThenPhoneIncident(fx: FakeEffects): PhoneEngine {
        relay = true
        val e = newEngine(fx)
        feed(e, real(WristVerdict.NORMAL))
        carSyncAged(e, carFlowAt(FlowState.S0, null, 0), 1_000)
        e.tick(mono)
        assertEquals(Owner.CAR, e.owner)
        // 차가 끊긴다 — 중계 오류만 이어짐
        advance(e, 12_000) { e.onSyncError(); feed(e, real(WristVerdict.NORMAL)) }
        assertEquals(Owner.PHONE, e.owner)
        advance(e, 40_000) { e.onSyncError(); feed(e, real(WristVerdict.PULSE_ABSENT)) }
        assertEquals(FlowState.S4, e.status(mono).flowState)
        assertEquals(1, guardian(fx).size)
        assertNotNull(store.savedFlow)
        return e
    }

    /** 「차 S0 + 저장 S4」 — 재시작 뒤 중계가 돌려준 차의 낡은 비사건 흐름이 저장된 사건을 지우면 안 된다(두 번째 문자). */
    @Test fun phoneOwner_restart_staleCarIdleFlowDoesNotEraseSavedIncident() {
        carDroppedThenPhoneIncident(FakeEffects())
        mono += 3_000; wall += 3_000
        val fx2 = FakeEffects()
        val e2 = newEngine(fx2)
        // 중계는 살아 있고 차의 마지막 상태(S0)는 55초 전 것
        carSyncAged(e2, carFlowAt(FlowState.S0, null, 55_000), 55_000)
        e2.tick(mono)
        assertEquals(Owner.PHONE, e2.owner)
        assertEquals("저장된 사건을 이어 간다", FlowState.S4, e2.status(mono).shownState)
        advance(e2, 60_000) { carSyncAged(e2, carFlowAt(FlowState.S0, null, 55_000 + 3_000), 115_000); feed(e2, real(WristVerdict.PULSE_ABSENT)) }
        assertTrue("두 번째 사건 문자 없음", guardian(fx2).isEmpty())
    }

    /**
     * H2(결정 24) — 저장에서 되살린 정지는 「넘겨받은 정지 유지」를 쓰지 않는다. 차 후보가 있어도 저장 쪽을 골랐으면, GPS 가 없을 때
     * 복원 유예(VEHICLE_GRACE_MS) 뒤에는 속도 모름 = 주행으로 S3 가 된다.
     */
    @Test fun phoneOwner_restartFromSavedWithCarCandidate_noStopHold() {
        carDroppedThenPhoneIncident(FakeEffects())
        mono += 3_000; wall += 3_000
        val fx2 = FakeEffects()
        val e2 = newEngine(fx2)
        carSyncAged(e2, carFlowAt(FlowState.S0, null, 55_000), 55_000) // 차 후보는 있으나 저장 쪽(사건)을 고른다
        e2.tick(mono)
        assertEquals(Owner.PHONE, e2.owner)
        assertEquals(FlowState.S4, e2.status(mono).shownState)
        advance(e2, PhoneEngine.VEHICLE_GRACE_MS + 15_000) { e2.onSyncError(mono); e2.onDataLayerWatch(real(WristVerdict.PULSE_ABSENT), mono) } // GPS 없음
        assertEquals("유예 뒤에는 속도 모름 = 주행", FlowState.S3, e2.status(mono).shownState)
    }

    /** 「차 S4 + 저장 S4」 — 둘 다 사건이면 더 최근 쪽. 저장이 3초 전이고 차 흐름이 30초 전이면 저장. */
    @Test fun phoneOwner_restart_bothIncidents_newerWins_saved() {
        val e1 = carDroppedThenPhoneIncident(FakeEffects())
        val mine = e1.status(mono).let { store.savedFlow!!.snapshot.facts.eventId }
        mono += 3_000; wall += 3_000
        val fx2 = FakeEffects()
        val e2 = newEngine(fx2)
        carSyncAged(e2, carFlowAt(FlowState.S4, "car-event", 30_000), 30_000)
        e2.tick(mono)
        assertEquals(Owner.PHONE, e2.owner)
        assertEquals(FlowState.S4, e2.status(mono).shownState)
        assertEquals("더 최근인 저장 쪽", mine, store.savedFlow!!.snapshot.facts.eventId)
    }

    /** 「차 S4 + 저장 S4」 — 저장이 40초 전이고 차 흐름이 20초 전이면 차. */
    @Test fun phoneOwner_restart_bothIncidents_newerWins_car() {
        carDroppedThenPhoneIncident(FakeEffects())
        mono += 40_000; wall += 40_000
        val fx2 = FakeEffects()
        val e2 = newEngine(fx2)
        carSyncAged(e2, carFlowAt(FlowState.S4, "car-event", 20_000), 20_000)
        e2.tick(mono)
        assertEquals(Owner.PHONE, e2.owner)
        assertEquals(FlowState.S4, e2.status(mono).shownState)
        assertEquals("더 최근인 차 쪽", "car-event", store.savedFlow!!.snapshot.facts.eventId)
    }

    /** 「신선한 차 주인」 — 재시작 때 차 링크가 살아 있으면 차가 주인이고(두 주인 금지) 저장은 쓰지 않고 지운다. */
    @Test fun phoneOwner_restart_freshCarIsOwnerAndSavedIsDropped() {
        carDroppedThenPhoneIncident(FakeEffects())
        mono += 3_000; wall += 3_000
        val fx2 = FakeEffects()
        val e2 = newEngine(fx2)
        carSyncAged(e2, carFlowAt(FlowState.S0, null, 1_000), 1_000)
        e2.tick(mono)
        assertEquals(Owner.CAR, e2.owner)
        assertEquals(FlowState.S0, e2.status(mono).flowState)
        assertTrue(fx2.alarms.isEmpty())
        assertTrue(fx2.noResponses.isEmpty())
        assertNull("차가 주인이 되면 저장을 지운다(G2)", store.savedFlow)
    }

    // ── 끄기 · 저장 내용 ──

    /** 끄면 스냅숏이 남지 않는다 — persistFlow 가 쓰기 직전 켜짐을 다시 본다. */
    @Test fun powerOffLeavesNoSavedFlow() {
        val e = runToNoResponse(FakeEffects())
        store.registration = fullRegistration(powerOn = false)
        e.tick(mono)
        assertNull(store.savedFlow)
    }

    /** 저장되는 것은 상태 · 판정 · 시각 · 사건 id 뿐 — 이름 · 번호 · 위치 · 심박 수치 · 원신호가 없다(G2). */
    @Test fun savedFlowHoldsOnlyFlowStateAndVerdict() {
        runToNoResponse(FakeEffects())
        val text = store.savedFlow!!.encode()
        for (bad in listOf("홍길동", "김보호", GUARDIAN, "12가3456", "37.456", "126.897", "세종대로")) assertFalse("저장에 「$bad」", text.contains(bad))
        val keys = HashSet<String>()
        fun collect(v: Any?) { when (v) { is Map<*, *> -> v.forEach { (k, x) -> keys += k as String; collect(x) }; is List<*> -> v.forEach(::collect) } }
        collect(com.example.guardian.shared.protocol.Json.parse(text))
        val forbidden = setOf("hr", "heartRate", "lat", "lon", "address", "name", "guardianName", "guardianPhone", "phoneLocation", "ppg", "accel", "samples", "birthDate")
        assertTrue("원신호 · 개인정보 필드 ${keys intersect forbidden}", (keys intersect forbidden).isEmpty())
    }

    // ── WATCH 세션의 테스트 사건 ──

    /** 워치 시뮬레이션 세션 중 저장된 테스트 사건 — 복원 뒤에도 테스트이고 실제 문자는 나가지 않는다. */
    @Test fun watchSession_savedTestIncidentRestoresAsTestWithoutRealSms() {
        val fx1 = FakeEffects()
        val e1 = newEngine(fx1)
        feed(e1, real(WristVerdict.NORMAL))
        e1.buildSync("tok", mono)
        e1.onSyncResponse(PhoneSyncResponse(mono + serverOffset, null, null, emptyList(), watchDirectAgeMs = 1_000), mono, mono)
        e1.tick(mono)
        assertEquals(TestStart.WATCH, e1.startTest(com.example.guardian.shared.bio.Scenario.PULSE_LOST, phoneOnly = false, now = mono))
        val sim = { v: WristVerdict -> real(v).copy(source = WatchSource.SIMULATION, test = true) }
        advance(e1, 40_000) { feed(e1, sim(WristVerdict.PULSE_ABSENT)) }
        assertEquals(FlowState.S4, e1.status(mono).flowState)
        assertTrue(e1.status(mono).flowTest)
        assertTrue("테스트 문자는 묻기만", fx1.sms.isEmpty() && fx1.asked.size == 1)
        assertTrue(store.savedFlow!!.snapshot.test)

        mono += 3_000; wall += 3_000
        val fx2 = FakeEffects()
        val e2 = newEngine(fx2)
        e2.tick(mono)
        advance(e2, 10_000) { feed(e2, sim(WristVerdict.PULSE_ABSENT)) }
        val st = e2.status(mono)
        assertEquals(FlowState.S4, st.shownState)
        assertTrue("복원 뒤에도 테스트", st.flowTest)
        assertTrue(e2.buildSync("tok", mono).flow!!.test)
        assertTrue("실제 119 화면이 아니라 미리보기", fx2.noResponses.isEmpty())
        e2.phoneResponse(mono)
        advance(e2, 2_000) { feed(e2, sim(WristVerdict.NORMAL)) }
        assertTrue("실제 문자 없음(취소도 묻기만)", fx2.sms.isEmpty())
    }

    /** 휴대폰 주인이 사건 중 차에 주인을 넘기면 저장을 지운다 — 차가 이어 가므로 휴대폰 재시작이 옛 사건을 되살리지 않는다. */
    @Test fun phoneOwner_handOverClearsSavedFlow() {
        val fx = FakeEffects()
        val e = runToNoResponse(fx)
        relay = true
        e.buildSync("tok", mono)
        // 차가 같은 사건 id 를 owner CAR 로 실어야 넘긴다(A-4 사건 임대)
        val id = e.status(mono).eventId
        val carTook = FlowStatus.of(FlowSnapshot(state = FlowState.S4, powerOn = true, consents = Consents(true, true, true), facts = IncidentFacts(eventId = id)), Owner.CAR)
        val peer = CarPeer(1, wall, mono + serverOffset, Vehicle(speedKmh = 0.0, gear = Gear.P, parkingBrake = true), carTook)
        e.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, emptyList()), mono, mono)
        e.tick(mono)
        assertEquals(Owner.CAR, e.owner)
        assertNull(store.savedFlow)
    }

    // ── 차 주인 ──

    private fun carFlow(state: FlowState) =
        FlowStatus.of(FlowSnapshot(state = state, powerOn = true, consents = Consents(true, true, true)), Owner.CAR)

    private fun carSync(e: PhoneEngine, flow: FlowStatus?, commands: List<Command> = emptyList()) {
        e.buildSync("tok", mono)
        val peer = CarPeer(1, wall, mono + serverOffset - 1_000, Vehicle(speedKmh = 0.0, gear = Gear.P, parkingBrake = true), flow)
        e.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, commands), mono, mono)
    }

    private fun smsCmd(id: String, kind: GuardianSmsWireKind, responseAt: Long? = null): Command {
        val p = Payload.SendGuardianSms(
            kind, "event-L1", WireFacts(SnapshotClock.SERVER, firstAlertAt = mono + serverOffset - 35_000, noResponseAt = mono + serverOffset, responseAt = responseAt), test = false,
        )
        return Command(id, p.type, p.toJson(), 30_000, false, Party.CAR, CommandTo.PHONE)
    }

    /** 차가 주인 — 휴대폰이 다시 떠도 차가 계속 주인이고, 다시 온 같은 지시 · 같은 사건의 새 지시는 보내지 않으며, 취소는 한 통. */
    @Test fun carOwner_restartKeepsCarOwnerAndDoesNotResend() {
        relay = true
        val fx1 = FakeEffects()
        val e1 = newEngine(fx1)
        carSync(e1, carFlow(FlowState.S0))
        e1.tick(mono)
        assertEquals(Owner.CAR, e1.owner)
        mono += 35_000; wall += 35_000
        carSync(e1, carFlow(FlowState.S4), listOf(smsCmd("e-1", GuardianSmsWireKind.EVENT)))
        assertEquals(1, guardian(fx1).size)
        assertNull("차가 주인일 때는 스냅숏을 저장하지 않는다", store.savedFlow)

        mono += 3_000; wall += 3_000
        val fx2 = FakeEffects()
        val e2 = newEngine(fx2)
        carSync(e2, carFlow(FlowState.S4), listOf(smsCmd("e-1", GuardianSmsWireKind.EVENT)))
        e2.tick(mono)
        assertEquals(Owner.CAR, e2.owner)
        assertTrue("처리한 지시는 다시 실행하지 않는다", guardian(fx2).isEmpty())
        assertTrue(e2.buildSync("tok", mono).acks.contains("e-1"))
        mono += 2_000; wall += 2_000
        carSync(e2, carFlow(FlowState.S4), listOf(smsCmd("e-2", GuardianSmsWireKind.EVENT)))
        assertTrue("같은 사건의 새 지시도 문자 기록으로 막는다", guardian(fx2).isEmpty())
        mono += 2_000; wall += 2_000
        carSync(e2, carFlow(FlowState.S2), listOf(smsCmd("e-3", GuardianSmsWireKind.CANCEL, responseAt = mono + serverOffset)))
        assertEquals("취소 문자는 한 통", 1, guardian(fx2).size)
        assertEquals(FlowState.S2, e2.status(mono).shownState)
    }
}
