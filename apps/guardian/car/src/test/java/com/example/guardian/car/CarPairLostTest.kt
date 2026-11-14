// 401 짝 폐기 시험 — 허브가 옛 짝의 흔적(워치 값 · 휴대폰 본 시각 · 표시 설정 · 응답 기준)을 지우고, 감시 중이면 꺼짐으로, 경보 중이면 경보를 이어 간다
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CarDisplay
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.protocol.CarSyncResponse
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhonePeer
import com.example.guardian.shared.protocol.Via
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPairLostTest {
    private fun hrWatch(responseAt: Long? = null, verdict: WristVerdict = WristVerdict.NORMAL) =
        Watch(verdict, 72, null, true, WatchSource.SENSOR_SDK, false, responseAt, Via.WATCH, 0, 0)

    /** 휴대폰 설정 · 표시 설정 · 워치 값을 실은 sync. */
    private fun Rig.syncAll(t: Long, watch: Watch?, display: CarDisplay? = CarDisplay(hideHr = false)) {
        val req = hub.buildRequest("tok", 1, 0, t)
        val serverTime = server(t + 50)
        // 차가 주인이 되기 전에는 휴대폰이 주인(owner=PHONE)으로 싣는다 — 차 시작 유예(A-4)가 풀린다
        val peer = PhonePeer(1, 0, serverTime - 1_000, null, settings(owner = if (hub.carIsOwner(t)) Owner.CAR else Owner.PHONE), null, display)
        hub.onSyncSuccess(req, t, t + 100, CarSyncResponse(serverTime, peer, watch, emptyList()))
    }

    private fun Rig.idle(t: Long) = hub.snapshot.let {
        CarScreen.idle(hub.carIsOwner(t), it.state, it.powerOn, it.consents.all, hub.links(t), hub.setupOk(), bio = hub.bio(t))
    }

    @Test fun watchingCarForgetsThePeerAndTurnsOff() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.syncAll(0, hrWatch())
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        assertEquals(72, r.hub.bio(200).hr)
        assertTrue(r.hub.links(200).phone)

        r.hub.onPairLost(2_000)

        assertEquals(FlowState.OFF, r.hub.snapshot.state)
        assertFalse(r.hub.snapshot.powerOn)
        assertTrue("워치 칸은 옛 심박이 아니라 신호 없음", r.hub.bio(2_000).noSignal)
        assertFalse("휴대폰 연결됨이 남지 않는다", r.hub.links(2_000).phone)
        assertEquals(WatchLink.NO_SIGNAL, r.hub.links(2_000).watch)
        assertNull(r.hub.carDisplay)
        assertNull(r.hub.watchVia)
        assertNull("지난 안내 줄도 지운다", r.hub.screen().line)
        // 정차 판정은 그대로 — 새 짝짓기 문(setupOk)과 정지 화면 판정이 흔들리지 않게
        assertTrue(r.hub.snapshot.stopped)
        assertTrue(r.hub.setupOk())

        // 주인 창을 바로 닫으므로(리뷰 11-09 ①) 새 짝 sync 전에는 「서버 연결 끊김」, 다음 주기에도 S0 로 돌아가지 않는다
        r.hub.onReading(PARKED, 4_000)
        assertEquals(FlowState.OFF, r.hub.snapshot.state)
        assertEquals(CarTexts.STATUS_SERVER_LOST, r.idle(4_000).line)
        // 새 짝 sync 가 되면 휴대폰이 켜기를 보낼 때까지 「꺼짐 · 휴대폰 앱에서 켜세요」
        r.syncNoPeer(5_000)
        assertEquals(FlowState.OFF, r.hub.snapshot.state)
        assertEquals(CarTexts.STATUS_OFF, r.idle(5_200).line)

        // 새 짝이 붙어 켜기 · 워치 값이 오면 다시 감시
        r.syncAll(6_000, hrWatch())
        assertEquals(FlowState.S0, r.hub.snapshot.state)
    }

    @Test fun offCarStaysOffAndForgetsThePeer() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        val req = r.hub.buildRequest("tok", 1, 0, 0)
        val serverTime = r.server(50)
        val peer = PhonePeer(1, 0, serverTime - 1_000, null, settings(power = false), null, CarDisplay(false))
        r.hub.onSyncSuccess(req, 0, 100, CarSyncResponse(serverTime, peer, hrWatch(), emptyList()))
        assertEquals(FlowState.OFF, r.hub.snapshot.state)
        r.hub.onPairLost(2_000)
        assertEquals(FlowState.OFF, r.hub.snapshot.state)
        assertFalse(r.hub.links(2_000).phone)
        assertTrue(r.hub.bio(2_000).noSignal)
    }

    /** S0 에서 워치가 낡아 「워치 신호 없음」 안내가 떠 있던 베타 장면 — 짝 폐기 뒤에는 안내가 아니라 꺼짐 문구. */
    @Test fun staleWatchNoticeIsClearedWithThePair() {
        val r = Rig()
        r.arm(PARKED)
        r.hub.onReading(PARKED, 32_000)
        assertEquals(Phrase.SCREEN_WATCH_NO_SIGNAL.ko, r.hub.screen().line)
        r.hub.onPairLost(32_100)
        assertNull(r.hub.screen().line)
        assertEquals(FlowState.OFF, r.hub.snapshot.state)
    }

    /** 리뷰 중2 — 옛 짝의 보낼 함 · ack 가 새 짝 휴대폰에 가지 않는다(옛 사건의 보호자 문자 · 알람 · 119 작성 지시). */
    @Test fun pairLossDropsTheOldOutboxAndAcks() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK), commands = listOf(remoteShow("old-1")))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        assertTrue("경보 지시가 보낼 함에 있다", r.hub.pendingCommands.isNotEmpty())
        r.hub.onSyncFailure(4_000)
        r.hub.onPairLost(4_000)
        assertTrue("보낼 함을 비운다", r.hub.pendingCommands.isEmpty())
        val next = r.hub.buildRequest("new", 9, 0, 4_100)
        assertTrue("옛 짝 지시 id 의 ack 도 새 짝에 보내지 않는다", next.acks.isEmpty())
        assertTrue(next.commands.isEmpty())
        assertEquals("경보는 이어 간다", FlowState.S1, r.hub.snapshot.state)
    }

    /**
     * A-2(리뷰 11-08 2차를 대체) — 진행 중 사건의 보호자 사건 문자 지시는 짝 폐기 뒤에도 공용 보낼 함에 남아, 다시 짝지은 뒤
     * 휴대폰이 확인할 때까지 같은 지시 id 로 실린다. 다른 지시(테스트 요청 등) · ack 는 지금처럼 비운다.
     */
    @Test fun pairLossKeepsOnlyTheCurrentIncidentGuardianSms() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        for (t in 4_000L..36_000L step 2_000) { r.hub.onReading(MOVING, t); r.sync(t) }
        r.hub.onReading(MOVING, 37_000)
        r.sync(37_000, commands = listOf(remoteShow("old-1"))) // 받은 시각 37,100 = 첫 경보(2,100) + 35초 → S3 · 보호자 문자
        assertEquals(FlowState.S3, r.hub.snapshot.state)
        val eventId = r.hub.snapshot.facts.eventId
        r.hub.sendTestRequest(com.example.guardian.shared.protocol.TestAction.START, 37_200)

        r.hub.onPairLost(38_000)

        val kept = r.smsPending()
        assertEquals("보호자 사건 문자만 남는다 $kept", listOf(CommandType.SEND_GUARDIAN_SMS), kept.map { it.type })
        assertEquals(eventId, kept.single().payload["eventId"])
        assertEquals("사건 문자(계약 kind EVENT)", "EVENT", kept.single().payload["kind"])
        assertTrue("그 밖의 지시는 비운다", r.otherPending().isEmpty())
        val next = r.hub.buildRequest("new", 9, 0, 60_000)
        assertTrue("ack 는 비운다", next.acks.isEmpty())
        assertEquals("다시 짝지은 첫 요청부터 같은 지시 id 로 싣는다(A-2)", kept.map { it.id }, next.commands.map { it.id })
        r.sync(62_000)
        assertEquals("확인 전에는 다시 싣는다", kept.map { it.id }, r.lastRequest!!.commands.map { it.id })
        assertEquals("경보는 이어 간다", FlowState.S3, r.hub.snapshot.state)
    }

    /** 휴대폰 상태(peer) 없이 성공한 sync — 다시 짝지었지만 휴대폰은 아직 붙지 않았다. */
    private fun Rig.syncNoPeer(t: Long) {
        val req = hub.buildRequest("new", 1, 0, t)
        hub.onSyncSuccess(req, t, t + 100, CarSyncResponse(server(t + 50), null, null, emptyList()))
    }

    /** 경보 중 S3 까지 — 보호자 사건 문자 지시가 보낼 함에 쌓인다(받은 시각 37,100 = 첫 경보 2,100 + 35초). */
    private fun Rig.toS3WithEventSms() {
        arm(MOVING)
        sync(2_000, watch(WristVerdict.PULSE_WEAK))
        for (t in 4_000L..36_000L step 2_000) { hub.onReading(MOVING, t); sync(t) }
        hub.onReading(MOVING, 37_000)
        sync(37_000)
        assertEquals(FlowState.S3, hub.snapshot.state)
        assertTrue(hub.pendingCommands.any { it.type == CommandType.SEND_GUARDIAN_SMS && it.payload["kind"] == "EVENT" })
    }

    private fun Rig.smsPending() = hub.pendingCommands.filter { it.type == CommandType.SEND_GUARDIAN_SMS }
    private fun Rig.otherPending() = hub.pendingCommands.filter { it.type != CommandType.SEND_GUARDIAN_SMS }

    /** 코드 리뷰 11-09 차 하1 ① — 짝 폐기 때 주인 창을 바로 닫는다. 그 뒤 경보가 낸 119 작성 · 알람 지시가 보낼 함에 쌓이지 않는다. */
    @Test fun pairLossClosesOwnerWindowAtOnce() {
        val r = Rig()
        r.toS3WithEventSms()
        r.hub.onSyncFailure(39_100)
        r.hub.onPairLost(39_100)
        assertFalse("10초를 기다리지 않고 주인이 아니다", r.hub.carIsOwner(39_100))
        assertFalse(r.hub.links(39_100).server)
        // 정차 → S4(119 작성 · 응급실 검색) — 차 안 실행은 하지만 상대 지시는 쌓지 않는다
        for (t in 40_000L..48_000L step 2_000) r.hub.onReading(PARKED, t)
        assertEquals(FlowState.S4, r.hub.snapshot.state)
        assertTrue("옛 짝 창에서 지시가 쌓이지 않는다 ${r.otherPending().map { it.type }}", r.otherPending().isEmpty())
        assertEquals("문자 보낼 함에는 사건 문자만", listOf("EVENT"), r.smsPending().map { it.payload["kind"] })
    }

    /**
     * 코드 리뷰 11-09 차 하1 ②(A-2 에서도 같음) — 아직 한 번도 실리지 않은 사건 문자는 그 사건이 응답으로 끝나면 취소와 함께 버린다 —
     * 보호자가 받은 적이 없다. 다시 짝지은 휴대폰에는 그 사건의 문자가 하나도 가지 않는다.
     */
    @Test fun heldEventSmsIsDroppedWhenTheIncidentEndsByResponse() {
        val r = Rig()
        r.toS3WithEventSms()
        val eventId = r.hub.snapshot.facts.eventId
        r.hub.onSyncFailure(39_100)
        r.hub.onPairLost(39_100)
        for (t in 40_000L..100_000L step 2_000) r.hub.onReading(PARKED, t)
        assertEquals(FlowState.S4, r.hub.snapshot.state)
        assertTrue(r.hub.onCarButton(100_500, PARKED))
        assertEquals(FlowState.S2, r.hub.snapshot.state)
        assertTrue("실리기 전 취소 — 사건 문자 · 취소를 둘 다 버린다", r.smsPending().isEmpty())
        for (t in 102_000L..120_000L step 2_000) r.hub.onReading(PARKED, t)
        val before = r.sentCommands.size
        r.sync(122_000); r.sync(124_000)
        val sms = r.sentCommands.drop(before).filter { it.type == CommandType.SEND_GUARDIAN_SMS && it.payload["eventId"] == eventId }
        assertTrue("새 짝에 간 문자 $sms", sms.isEmpty())
    }

    /** 사건 문자가 실리기 전(보류 4초 안)에 응답 — 취소가 들어오는 순간 둘 다 버린다(A-2 보류, 만료 직전 응답의 두 통 막기). */
    @Test fun heldEventAndCancelBothPendingAreDropped() {
        val r = Rig()
        r.toS3WithEventSms()
        for (t in 38_000L..45_000L step 1_000) r.hub.onReading(PARKED, t)
        assertEquals(FlowState.S4, r.hub.snapshot.state)
        assertTrue(r.hub.onCarButton(46_000, PARKED))
        assertEquals(FlowState.S2, r.hub.snapshot.state)
        assertTrue("사건 · 취소 모두 버린다 ${r.smsPending()}", r.smsPending().isEmpty())
        r.hub.onPairLost(46_500)
        r.sync(48_000); r.sync(50_000)
        assertTrue(r.smsPending().isEmpty())
    }

    /** 사건 문자가 한 번 실린 뒤 응답 — 보호자가 받았을 수 있으니 취소도 남기고, 짝 폐기 뒤에도 사건 문자 → 취소 순서로 함께 싣는다(A-2). */
    @Test fun heldCancelAloneIsKeptForTheNewPair() {
        val r = Rig()
        r.toS3WithEventSms()
        r.sync(37_500) // 이 응답을 처리해 보류가 풀린다
        r.sync(39_000) // 사건 문자가 실린다
        assertTrue(r.lastRequest!!.commands.any { it.type == CommandType.SEND_GUARDIAN_SMS && it.payload["kind"] == "EVENT" })
        for (t in 40_000L..47_000L step 1_000) r.hub.onReading(PARKED, t)
        assertTrue(r.hub.onCarButton(48_000, PARKED))
        r.hub.onPairLost(48_500)
        assertEquals(listOf("EVENT", "CANCEL"), r.smsPending().map { it.payload["kind"] })
        r.sync(50_000)
        assertEquals("사건 문자가 취소보다 앞", listOf("EVENT", "CANCEL"), r.hub.buildRequest("new", 9, 0, 52_000).commands.map { it.payload["kind"] })
    }

    /** 다시 짝지은 뒤 휴대폰이 아직 안 붙었어도 사건 문자는 실리고(A-2), 그 뒤 응답하면 취소도 함께 싣는다 — 휴대폰이 사건 id 로 맞춘다. */
    @Test fun responseAfterRepairBeforePhoneCarriesEventThenCancel() {
        val r = Rig()
        r.toS3WithEventSms()
        r.hub.onPairLost(38_000)
        for (t in 40_000L..48_000L step 2_000) { r.hub.onReading(PARKED, t); r.syncNoPeer(t) }
        assertTrue(r.hub.carIsOwner(48_200))
        assertEquals(FlowState.S4, r.hub.snapshot.state)
        assertTrue(r.hub.onCarButton(48_500, PARKED))
        assertEquals(FlowState.S2, r.hub.snapshot.state)
        assertEquals("이미 실린 사건 문자와 그 취소", listOf("EVENT", "CANCEL"), r.smsPending().map { it.payload["kind"] })
    }

    /** 사람이 「휴대폰 연결 끊기」를 고르면 남긴 문자도 남김없이 버린다. */
    @Test fun unpairDropsHeldCommandsToo() {
        val r = Rig()
        r.toS3WithEventSms()
        r.hub.onPairLost(38_000)
        assertTrue(r.smsPending().isNotEmpty())
        r.hub.onUnpair(40_000)
        assertTrue(r.hub.pendingCommands.isEmpty())
    }

    /** 리뷰 11-08 하2 — 응답 뒤(S1R · S2)는 경보가 아니다: 짝 폐기 때 OFF 로 새로 시작해 옛 짝의 「응답 확인됨」이 10분 남지 않고, 시계 미확인도 되돌린다. */
    @Test fun restAfterResponseStartsOffOnPairLossAndRestoresClock() {
        val r = Rig()
        r.hub.onReading(MOVING, 0)
        val rest = FlowSnapshot(
            state = FlowState.S1R, sinceMs = 0, powerOn = true, consents = Consents(true, true, true), worn = true, watchSeenAtMs = 0,
            incidentActive = true, facts = IncidentFacts(firstAlertAtMs = 0, responseAtMs = 0, eventId = "event-L0"),
        )
        val rep = r.sync(0, watch(WristVerdict.NORMAL), peerFlow = FlowStatus.of(rest, Owner.PHONE))
        assertTrue(rep.adopted)
        assertEquals(FlowState.S1R, r.hub.snapshot.state)
        assertFalse("다른 기기의 로컬 시각이라 시계 미확인", r.hub.clockVerified)

        r.hub.onPairLost(2_000)

        assertEquals(FlowState.OFF, r.hub.snapshot.state)
        assertNull("옛 짝의 「응답 확인됨」이 남지 않는다", r.hub.screen().line)
        assertTrue("시계 미확인을 되돌린다", r.hub.clockVerified)
        r.hub.onReading(MOVING, 4_000)
        assertEquals(FlowState.OFF, r.hub.snapshot.state)
    }

    private fun remoteShow(id: String) = Command(
        id, CommandType.SHOW, Payload.Show("x", null).toJson(), 30_000, test = false, from = Party.PHONE, to = CommandTo.CAR,
    )

    @Test fun alarmContinuesThroughPairLoss() {
        val r = Rig()
        r.hub.onReading(MOVING, 0)
        r.syncAll(0, hrWatch(responseAt = 1_000))
        r.hub.onReading(MOVING, 2_000)
        r.syncAll(2_000, hrWatch(responseAt = 1_000, verdict = WristVerdict.PULSE_ABSENT))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        val line = r.hub.screen().line

        r.hub.onPairLost(4_000)

        assertEquals("경보 중이면 차 안 경보를 이어 간다", FlowState.S1, r.hub.snapshot.state)
        assertTrue(r.hub.snapshot.powerOn)
        assertEquals(line, r.hub.screen().line)
        assertEquals(line, CarScreen.alarmLine(r.hub.snapshot.state, r.hub.screen()))
        assertTrue(r.hub.bio(4_000).noSignal)
        assertFalse(r.hub.links(4_000).phone)
        assertNull(r.hub.carDisplay)

        // 새 짝의 워치가 처음 보내는 responseAt 은 기준일 뿐 응답이 아니다(옛 기준 1_000 과 비교하지 않는다)
        r.hub.onReading(MOVING, 6_000)
        r.syncAll(6_000, hrWatch(responseAt = 5_000, verdict = WristVerdict.PULSE_ABSENT))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        // 그 뒤 실제로 누르면 응답
        r.hub.onReading(MOVING, 8_000)
        r.syncAll(8_000, hrWatch(responseAt = 7_500, verdict = WristVerdict.PULSE_ABSENT))
        assertEquals(FlowState.S1R, r.hub.snapshot.state)
    }
}
