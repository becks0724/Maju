// 「응답 확인 꺼짐」 보호자 문자 시험(사용자 결정 2026-11-08) — 사건 문자를 보낸 실제 사건에서 취소 없이 켬을 끄면 사실만 담은 문자 한 통, 그 밖에는 보내지 않는다
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.GuardianSmsDedup
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
import com.example.guardian.shared.report.Reports
import com.example.guardian.shared.text.ForbiddenWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeFormatter

class PowerOffNoticeTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L

    /** 휴대폰이 주인(중계 없음). */
    private val phoneEngine by lazy { newEngine(relay = false) }

    /** 차가 주인(중계 있음). */
    private val carEngine by lazy { newEngine(relay = true) }

    private fun newEngine(relay: Boolean) = PhoneEngine(
        store = store, effects = fx, newId = { "c${ids++}" }, wallNow = { wall0 + (mono - mono0) },
        seqStartEpochMs = wall0, startMono = mono, relayActive = { relay },
    )

    private fun off(consents: Consents = Consents(true, true, true)) { store.registration = fullRegistration(consents = consents, powerOn = false) }
    private fun on() { store.registration = fullRegistration() }

    private fun powerOffTexts() = fx.sms.map { it.second }.filter { "응답 확인이 꺼졌습니다" in it }

    private fun sentKinds() = store.smsRecords.filter { it.sent }.map { it.kind }

    // ── 휴대폰이 주인 ──

    private fun feed(e: PhoneEngine, v: WristVerdict) {
        e.onDataLayerWatch(Watch(v, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = null), mono)
        e.onLocation(Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "서울 종로구 세종대로 172"), mono)
    }

    /** 정차 중 맥박 없음 — [until] 상태 가운데 하나가 될 때까지. */
    private fun phoneTo(until: Set<FlowState>) {
        val e = phoneEngine
        feed(e, WristVerdict.NORMAL)
        e.tick(mono)
        assertEquals(Owner.PHONE, e.owner)
        var n = 0
        while (e.status(mono).flowState !in until) { mono += 1_000; feed(e, WristVerdict.PULSE_ABSENT); e.tick(mono); assertTrue(++n < 200) }
    }

    /** S4 까지 — 보호자 사건 문자(EVENT)를 보냈다. */
    private fun phoneEventSent() {
        phoneTo(setOf(FlowState.S4))
        assertEquals(listOf(GuardianSmsWireKind.EVENT), sentKinds())
    }

    @Test fun eventSentThenPowerOffSendsOneFactOnlyNotice() {
        phoneEventSent()
        val before = fx.sms.size
        off()
        phoneEngine.shutdown(mono)
        assertEquals("한 통만 더 보낸다", before + 1, fx.sms.size)
        val (to, body) = fx.sms.last()
        assertEquals(GUARDIAN, to)
        val hm = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(wall0 + (mono - mono0)).atZone(Reports.SEOUL))
        assertEquals(
            "[앱 안내] $hm\n${hm}에 휴대폰에서 응답 확인이 꺼졌습니다. 이후 운전자 상태는 앱으로 확인되지 않습니다.\n운전자 무응답 확인 앱 자동 작성 · 의료 판정 아님",
            body,
        )
        // G1 — 금지어 · 할 일 시키기(「…세요」) · 안전 단정이 없다. G2 — 위치 · 이름 · 번호를 싣지 않는다
        assertTrue(ForbiddenWords.violations(body).toString(), ForbiddenWords.violations(body).isEmpty())
        for (w in listOf("세요", "안전", "괜찮", "37.45", "홍길동", GUARDIAN)) assertFalse("「$w」: $body", body.contains(w))
        // 끄기는 그대로 — 상태 기계 OFF · 마지막 flow OFF
        assertEquals(FlowState.OFF, phoneEngine.buildSync("tok", mono).flow!!.state)
        // 끄기 주기가 다시 돌아도(끄기 · 철회 마지막 sync 대기) 다시 보내지 않는다
        phoneEngine.shutdown(mono + 2_000)
        assertEquals(before + 1, fx.sms.size)
        assertTrue("계약의 문자 기록(EVENT · CANCEL · …)에 새 종류를 넣지 않는다", store.smsRecords.all { it.kind == GuardianSmsWireKind.EVENT })
    }

    @Test fun cancelAlreadySentMeansNoNotice() {
        phoneEventSent()
        phoneEngine.phoneResponse(mono)
        assertEquals(listOf(GuardianSmsWireKind.EVENT, GuardianSmsWireKind.CANCEL), sentKinds())
        val before = fx.sms.size
        off()
        phoneEngine.shutdown(mono)
        assertEquals(before, fx.sms.size)
    }

    @Test fun beforeAnyGuardianSmsNoNotice_S1AndAp() {
        phoneTo(setOf(FlowState.S1, FlowState.AP))
        assertTrue(fx.sms.isEmpty())
        off()
        phoneEngine.shutdown(mono)
        assertTrue(fx.sms.toString(), fx.sms.isEmpty())
    }

    @Test fun beforeAnyGuardianSmsNoNotice_S2() {
        phoneTo(setOf(FlowState.S1, FlowState.AP))
        phoneEngine.phoneResponse(mono)
        phoneEngine.tick(mono)
        assertTrue(phoneEngine.status(mono).flowState in setOf(FlowState.S1R, FlowState.S2))
        off()
        phoneEngine.shutdown(mono)
        assertTrue(fx.sms.toString(), fx.sms.isEmpty())
    }

    /** 동의 철회(켬 false + 동의 없음)는 G2 경로 — 사건 문자를 보냈어도 보내지 않는다. */
    @Test fun consentWithdrawalSendsNothing() {
        phoneEventSent()
        val before = fx.sms.size
        off(Consents())
        phoneEngine.shutdown(mono)
        assertEquals(before, fx.sms.size)
        // 동의 하나만 거둬도 철회다
        off(Consents(true, false, true))
        phoneEngine.shutdown(mono)
        assertEquals(before, fx.sms.size)
    }

    /** 보내지 못해도(권한 없음 · SIM 없음 — 요청 거절) 끄기는 그대로이고 「보내지 못함」 안내 · 문자 기록만 남는다. 다시 시도하지 않는다. */
    @Test fun sendRefusedStillShutsDownAndRecordsFailure() {
        phoneEventSent()
        val stops = fx.alarmStops
        fx.smsResult = false
        off()
        phoneEngine.shutdown(mono)
        assertTrue(fx.notices.contains(Notice.SMS_FAILED))
        assertEquals(CommandExecutor.POWER_OFF_KIND to false, fx.smsLog.last())
        assertTrue("알람은 멈춘다", fx.alarmStops > stops)
        assertEquals(FlowState.OFF, phoneEngine.buildSync("tok", mono).flow!!.state)
        fx.smsResult = true
        phoneEngine.shutdown(mono + 2_000)
        assertTrue(powerOffTexts().isEmpty())
    }

    /**
     * app-qa 중 1 재현 — 끄기가 sync 도중에 들어와 shutdown 보다 틱이 먼저 와도 한 통. 꺼짐은 shutdown 한 곳에서만 상태 기계에 넣는다.
     */
    @Test fun tickBeforeShutdownStillSendsNotice() {
        phoneEventSent()
        val before = fx.sms.size
        off()
        phoneEngine.tick(mono)
        phoneEngine.tick(mono + 1_000)
        phoneEngine.shutdown(mono + 1_000)
        assertEquals("틱 뒤 shutdown 에서도 한 통", before + 1, fx.sms.size)
        assertEquals(1, powerOffTexts().size)
        assertEquals(FlowState.OFF, phoneEngine.buildSync("tok", mono + 1_000).flow!!.state)
    }

    /**
     * 같은 경합 — 경보 1 · 2(S1 · AP) 중 끄기가 들어오고 무응답 문턱을 넘긴 시각에 틱이 먼저 와도 S3 · S4 에 들어가 보호자 문자 · 119 ·
     * 안내를 내지 않는다(틱은 꺼짐을 넣지 않고 기다리고, shutdown 이 마지막으로 처리한 시각으로 꺼짐을 넣는다).
     */
    @Test fun tickBeforeShutdownPastThresholdDoesNotEnterNoResponse() {
        phoneTo(setOf(FlowState.S1, FlowState.AP))
        val lines = fx.lines.size
        mono += 120_000
        off()
        phoneEngine.tick(mono)
        phoneEngine.shutdown(mono)
        assertTrue("보호자 문자를 보내지 않는다: ${fx.sms}", fx.sms.isEmpty())
        assertTrue("119 지시를 내지 않는다", fx.noResponses.isEmpty() && fx.composed.isEmpty())
        assertEquals("무응답 안내를 띄우지 않는다: ${fx.lines}", lines, fx.lines.size)
        assertTrue("알람은 멈춘다", fx.alarmStops > 0)
        assertEquals(FlowState.OFF, phoneEngine.buildSync("tok", mono).flow!!.state)
        assertTrue("저장한 사건은 지운다(G2)", store.savedFlow == null)
    }

    /**
     * 보내기 결과가 엔진에 「보내지 못함」으로 돌아오면 안내 · 기록이 남는다(이 시험의 가짜는 결과를 바로 돌려준다).
     * 실기기에서는 늦게 오는 실패 결과를 서비스가 멈추며 받지 못한다 — 안내 · 기록은 요청 거절(동기)에만 남는다고 본다(app-qa 하, PM 결정).
     */
    @Test fun deliveryFailureRecordsFailure() {
        phoneEventSent()
        fx.smsDelivery = false
        off()
        phoneEngine.shutdown(mono)
        assertEquals(1, powerOffTexts().size)
        assertEquals(CommandExecutor.POWER_OFF_KIND to false, fx.smsLog.last())
        assertTrue(fx.notices.contains(Notice.SMS_FAILED))
    }

    // ── 차가 주인 ──

    /** 차가 돌리던 상태 기계 — 정차 중 맥박 없음 → S4. */
    private fun carS4(test: Boolean = false): FlowStatus {
        val car = FlowMachine()
        var t = 50_000L
        car.handle(FlowInput.ClockSync(serverOffset + mono - t, t))
        car.handle(FlowInput.Power(true, t))
        car.handle(FlowInput.ConsentsChanged(Consents(true, true, true), t))
        if (test) car.handle(FlowInput.TestMode(true, t))
        repeat(10) { t += 1_000; car.handle(FlowInput.Vehicle(VehicleState(0.0, Gear.P, true), t)) }
        car.handle(FlowInput.Wrist(WristVerdict.NORMAL, t))
        repeat(60) { t += 1_000; car.handle(FlowInput.Wrist(WristVerdict.PULSE_ABSENT, t)); car.handle(FlowInput.Tick(t)) }
        assertEquals(FlowState.S4, car.state)
        return FlowStatus.forHandoff(car.snapshot(), Owner.CAR, serverOffset + mono - t)
    }

    private fun carSms(kind: GuardianSmsWireKind, flow: FlowStatus, test: Boolean = false): Command {
        val f = flow.snapshot.facts
        val p = Payload.SendGuardianSms(kind, f.eventId!!, WireFacts(SnapshotClock.SERVER, firstAlertAt = f.firstAlertAtMs, responseAt = f.responseAtMs), test)
        return Command("car-${ids++}", p.type, p.toJson(), 30_000, test, Party.CAR, CommandTo.PHONE)
    }

    private fun carSync(flow: FlowStatus, commands: List<Command> = emptyList()) {
        val e = carEngine
        e.buildSync("tok", mono)
        val peer = CarPeer(1, wall0, mono + serverOffset, Vehicle(speedKmh = 0.0, gear = Gear.P), flow)
        e.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, commands), mono, mono)
        e.tick(mono)
        assertEquals(Owner.CAR, e.owner)
    }

    /** 차가 주인이어도 휴대폰이 보낸 기록만으로 정한다 — 차 · 중계로 새 지시를 내지 않는다(계약 그대로). */
    @Test fun carOwnerUsesPhoneRecordsOnly() {
        val flow = carS4()
        carSync(flow, listOf(carSms(GuardianSmsWireKind.EVENT, flow)))
        assertEquals(listOf(GuardianSmsWireKind.EVENT), sentKinds())
        off()
        carEngine.shutdown(mono)
        assertEquals(1, powerOffTexts().size)
        assertTrue("차 · 워치로 보낼 문자 지시가 없다", carEngine.buildSync("tok", mono).commands.none { it.type == CommandType.SEND_GUARDIAN_SMS })
    }

    /** 차가 주인 · 차가 낸 취소 문자를 이미 보냈다 → 보내지 않는다. */
    @Test fun carOwnerCancelAlreadySentMeansNoNotice() {
        val flow = carS4()
        carSync(flow, listOf(carSms(GuardianSmsWireKind.EVENT, flow)))
        mono += 1_000
        carSync(flow, listOf(carSms(GuardianSmsWireKind.CANCEL, flow.copy(snapshot = flow.snapshot.copy(facts = flow.snapshot.facts.copy(responseAtMs = mono + serverOffset))))))
        assertEquals(listOf(GuardianSmsWireKind.EVENT, GuardianSmsWireKind.CANCEL), sentKinds())
        off()
        carEngine.shutdown(mono)
        assertTrue(powerOffTexts().isEmpty())
    }

    /** app-qa 하 재현 — 차가 주인 · 차 링크가 막 10초 문턱을 넘었고 아직 주인 판정 틱 전이면 마지막 차 flow 로 보낸다. */
    @Test fun carLinkJustStaleBeforeOwnerTickStillSends() {
        val flow = carS4()
        carSync(flow, listOf(carSms(GuardianSmsWireKind.EVENT, flow)))
        mono += 11_000
        off()
        carEngine.shutdown(mono)
        assertEquals(1, powerOffTexts().size)
    }

    private fun rec(kind: GuardianSmsWireKind, id: String, at: Long) =
        GuardianSmsDedup.Record(kind, id, Party.CAR, null, null, test = false, recordedAtWallMs = at)

    /** PM 보정 — 같은 사건에서 마지막 보호자 연락(EVENT · ACCIDENT)이 마지막 취소보다 뒤면 보낸다. 취소 뒤 사고 문자 → 보냄. */
    @Test fun accidentAfterCancelSends() {
        val flow = carS4()
        carSync(flow)
        val id = flow.snapshot.facts.eventId!!
        store.smsRecords = listOf(rec(GuardianSmsWireKind.EVENT, id, wall0 - 3_000), rec(GuardianSmsWireKind.CANCEL, id, wall0 - 2_000), rec(GuardianSmsWireKind.ACCIDENT, id, wall0 - 1_000))
        off()
        carEngine.shutdown(mono)
        assertEquals(1, powerOffTexts().size)
    }

    /** 사건 문자 뒤 취소 문자 → 보내지 않는다(기록 시각 비교). */
    @Test fun eventThenCancelByRecordTimeSendsNothing() {
        val flow = carS4()
        carSync(flow)
        val id = flow.snapshot.facts.eventId!!
        store.smsRecords = listOf(rec(GuardianSmsWireKind.EVENT, id, wall0 - 3_000), rec(GuardianSmsWireKind.CANCEL, id, wall0 - 2_000))
        off()
        carEngine.shutdown(mono)
        assertTrue(fx.sms.isEmpty())
    }

    /**
     * 두 주인 묶음 — 휴대폰이 스스로 연 사건(다른 id)의 EVENT 가 차 사건과 첫 경보 시각으로 묶이면(:shared sameIncident 규칙 2)
     * 보내고, 두 id 모두에 한 번 표시를 남겨 어느 쪽으로 다시 와도 한 통만.
     */
    @Test fun twoOwnerIncidentsByTimeMarkBothIds() {
        val flow = carS4()
        carSync(flow)
        val carId = flow.snapshot.facts.eventId!!
        // 차 첫 경보(중계 시각) → 휴대폰 벽시계: 단조 = 중계 − 보정값, 벽시계 = 단조 + (wall0 − mono0)
        val carFirstWall = flow.snapshot.facts.firstAlertAtMs!! - serverOffset + (wall0 - mono0)
        store.smsRecords = listOf(GuardianSmsDedup.Record(GuardianSmsWireKind.EVENT, "event-L999", Party.PHONE, carFirstWall - 1_000, null, test = false, recordedAtWallMs = wall0))
        off()
        carEngine.shutdown(mono)
        assertEquals(1, powerOffTexts().size)
        assertTrue(store.sent.toString(), "POWER_OFF:event-L999" in store.sent && "POWER_OFF:$carId" in store.sent)
        on()
        carSync(flow)
        off()
        carEngine.shutdown(mono)
        assertEquals(1, powerOffTexts().size)
    }

    /** 끄고 · 다시 켜고 · 같은 사건 중 다시 끄면 한 통만. 앱을 다시 띄워도(같은 저장소) 다시 보내지 않는다. */
    @Test fun secondPowerOffInSameIncidentSendsNothing() {
        val flow = carS4()
        carSync(flow, listOf(carSms(GuardianSmsWireKind.EVENT, flow)))
        off()
        carEngine.shutdown(mono)
        assertEquals(1, powerOffTexts().size)
        on()
        mono += 2_000
        carSync(flow)
        off()
        carEngine.shutdown(mono)
        assertEquals(1, powerOffTexts().size)
        // 다시 띄운 앱
        on()
        val again = newEngine(relay = true)
        again.buildSync("tok", mono)
        again.onSyncResponse(PhoneSyncResponse(mono + serverOffset, CarPeer(1, wall0, mono + serverOffset, Vehicle(speedKmh = 0.0, gear = Gear.P), flow), null, emptyList()), mono, mono)
        again.tick(mono)
        off()
        again.shutdown(mono)
        assertEquals(1, powerOffTexts().size)
    }

    /** 테스트 사건은 보내지 않는다 — 테스트 사건 문자를 보냈어도. */
    @Test fun testIncidentSendsNothing() {
        val flow = carS4(test = true)
        carSync(flow)
        val f = flow.snapshot.facts
        store.smsRecords = listOf(GuardianSmsDedup.Record(GuardianSmsWireKind.EVENT, f.eventId!!, Party.CAR, null, null, test = true, recordedAtWallMs = wall0))
        off()
        carEngine.shutdown(mono)
        assertTrue(fx.sms.isEmpty())
    }

    /** 사고(ACCIDENT) 문자를 보낸 사건도 같다. 보내지 않은(「보내지 않음」) 기록은 세지 않는다. */
    @Test fun accidentSentCountsUnsentEventDoesNot() {
        val flow = carS4()
        carSync(flow)
        val id = flow.snapshot.facts.eventId!!
        store.smsRecords = listOf(GuardianSmsDedup.Record(GuardianSmsWireKind.EVENT, id, Party.CAR, null, null, test = false, recordedAtWallMs = wall0, sent = false))
        off()
        carEngine.shutdown(mono)
        assertTrue("보내지 않은 EVENT 만 있으면 보내지 않는다", fx.sms.isEmpty())
        on()
        store.smsRecords = listOf(GuardianSmsDedup.Record(GuardianSmsWireKind.ACCIDENT, id, Party.CAR, null, null, test = false, recordedAtWallMs = wall0))
        off()
        carEngine.shutdown(mono)
        assertEquals(1, powerOffTexts().size)
    }

    /** 다른 사건(다른 id)의 기록만 있으면 보내지 않는다 — 지난 사건 문자로 지금 끄기를 알리지 않는다. */
    @Test fun otherIncidentRecordDoesNotCount() {
        val flow = carS4()
        carSync(flow)
        store.smsRecords = listOf(GuardianSmsDedup.Record(GuardianSmsWireKind.EVENT, "event-other", Party.CAR, null, null, test = false, recordedAtWallMs = wall0))
        off()
        carEngine.shutdown(mono)
        assertTrue(fx.sms.isEmpty())
    }

    /** 보호자 번호가 없으면 보내지 않는다(안내만). */
    @Test fun noGuardianNumberSendsNothing() {
        val flow = carS4()
        carSync(flow)
        store.smsRecords = listOf(GuardianSmsDedup.Record(GuardianSmsWireKind.EVENT, flow.snapshot.facts.eventId!!, Party.CAR, null, null, test = false, recordedAtWallMs = wall0))
        store.registration = fullRegistration(powerOn = false).copy(guardianPhone = "")
        carEngine.shutdown(mono)
        assertTrue(fx.sms.isEmpty())
        assertTrue(fx.notices.contains(Notice.SMS_SKIPPED_NO_GUARDIAN))
    }
}
