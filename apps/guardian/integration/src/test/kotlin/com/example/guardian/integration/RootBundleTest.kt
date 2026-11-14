// 뿌리 묶음(문자 전달 확인 · 판정 주인) 통합 시험 — 레드 재현(응답만 유실 · 사건 중 짝 지우기 · 차 재시작 · 재시작 순간 신호 나쁨 · 만료 직전 응답 창)과 취소 뒤 늦은 사건 문자
package com.example.guardian.integration

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.CarSyncRequest
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.text.SmsText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 고치기 전에 실패하던 재현을 옮긴 시험(합동 점검 A-7). 차 · 휴대폰 연결(A-1~A-4)이 들어와 모두 켜져 있다.
 * 레드 원본 — 합동 점검 재현 묶음의 be-qa `NoOwner` · `Pair401`, app-qa `RedTeam*`(저장소 밖 작업 문서).
 */
class RootBundleTest : Stage() {
    private val cancelHead = SmsText.CANCEL_BODY.substringBefore("%")

    /** 감시(S0)부터 실제 맥박 소실 → 경보(주행 S1 · 정차 AP)까지. 경보 시작 가상 시각을 돌려준다. */
    private fun alert(t: Trio, driving: Boolean = false): Long {
        if (driving) { t.car.reading = CarDevice.driving(); t.phone.gpsKmh = 60.0 }
        armCar(t)
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
        world.runUntil(60_000, "차 경보") { t.car.state() == FlowState.S1 || t.car.state() == FlowState.AP }
        return world.elapsed
    }

    // ── 판2-1 · 차B-2 — 차 요청은 닿는데 응답만 유실(be-qa trio/NoOwner) ──

    private fun responseLost(gapMs: Long, delayMs: Long, thenDown: Boolean): Trio {
        val t = setup()
        alert(t)
        world.advance(delayMs)
        t.car.respLost = true
        world.advance(gapMs)
        t.car.respLost = false
        if (thenDown) t.car.linkUp = false
        world.advance(120_000)
        return t
    }

    @Test fun responseLost12sAcrossDeadline_oneEventSms() =
        assertEquals(1, events(responseLost(12_000, 24_000, thenDown = false)))

    @Test fun responseLost12sThenLinkDown_oneEventSms() =
        assertEquals(1, events(responseLost(12_000, 24_000, thenDown = true)))

    @Test fun responseLost60sFromAlertStart_oneEventSms() =
        assertEquals(1, events(responseLost(60_000, 0, thenDown = false)))

    // ── 차B-1 · A-6 — 사건 중 짝 지우기(401, be-qa r2world/Pair401 · app-qa Round2) ──

    private fun pairEndMidIncident(driving: Boolean, pressCarAfterMs: Long? = null): Trio {
        val t = setup()
        alert(t, driving)
        world.advance(5_000)
        endPair(t)
        if (pressCarAfterMs != null) { world.advance(pressCarAfterMs); t.car.pressOk() }
        world.advance(120_000)
        return t
    }

    @Test fun pairEndWhileDriving_oneEventSms() = assertEquals(1, events(pairEndMidIncident(driving = true)))

    @Test fun pairEndWhileParked_oneEventSms() = assertEquals(1, events(pairEndMidIncident(driving = false)))

    /** A-6 한계 — 401 뒤 차 화면 응답은 휴대폰에 닿지 않아 사건 문자가 나가고, 다시 짝지은 뒤 취소가 한 통 간다. */
    @Test fun pairEndThenCarResponse_cancelAfterRepair() {
        val t = pairEndMidIncident(driving = false, pressCarAfterMs = 10_000)
        assertEquals(1, events(t))
        repair(t)
        world.advance(60_000)
        assertEquals(1, events(t))
        assertEquals(1, cancels(t))
    }

    @Test fun pairEndAtS3WithoutDataLayer_oneEventSms() {
        val t = setup()
        t.car.reading = CarDevice.driving(); t.phone.gpsKmh = 60.0
        armCar(t)
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
        world.runUntil(80_000, "S3") { t.car.state() == FlowState.S3 }
        endPair(t)
        world.advance(90_000)
        assertEquals(1, events(t))
    }

    // ── 앱2-상1 · A-4 — 사건 중 차 앱 재시작(app-qa RedTeamCarRestartTest · RedTeamRound3Test) ──

    private fun carRestart(downMs: Long, signalRecovers: Boolean): Pair<Trio, CarDevice> {
        val t = setup()
        alert(t)
        world.advance(20_000)
        val c2 = restartCar(t, downMs)
        if (signalRecovers) t.watch.realSignal(Scenario.NORMAL, eventInMs = 0)
        world.advance(80_000)
        return t to c2
    }

    @Test fun carRestart3s_incidentKeepsOneEventSms() = assertEquals(1, events(carRestart(3_000, signalRecovers = true).first))

    @Test fun carRestart8s_incidentKeepsOneEventSms() = assertEquals(1, events(carRestart(8_000, signalRecovers = true).first))

    /** 빈틈이 10초를 넘으면 휴대폰이 먼저 주인이 되어 지금도 맞다(대조군, 켜 둠). */
    @Test fun carRestart14s_incidentKeepsOneEventSms() = assertEquals(1, events(carRestart(14_000, signalRecovers = true).first))

    /** 재시작 순간 신호가 계속 나쁨 — 다시 뜬 차가 휴대폰 흐름을 보기 전에 새 사건을 열면 두 주인이 서로 다른 사건 id 를 쥔다(app-qa 3차, 차 시작 유예 6초). */
    @Test fun carRestartWhileSignalBad_oneIncidentId() {
        for (down in listOf(3_000L, 8_000L)) for (phase in listOf(0L, 1_000L)) {
            val s = object : Stage() {}
            val t = s.setup()
            t.car.reading = CarDevice.driving(); t.phone.gpsKmh = 60.0
            s.armCar(t)
            t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
            s.world.runUntil(60_000, "S1") { t.car.state() == FlowState.S1 }
            s.world.advance(18_000 + phase)
            val e1 = t.car.hub.snapshot.facts.eventId
            t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 0)
            val c2 = s.restartCar(t, down, CarDevice.driving())
            s.world.advance(120_000)
            assertEquals("down=$down phase=$phase — 차가 같은 사건을 이어 감", e1, c2.hub.snapshot.facts.eventId)
            assertEquals("down=$down phase=$phase — 사건 문자 한 통", 1, s.events(t))
            s.g2Audit()
        }
        auditNeedsWire = false // 바깥 무대는 쓰지 않는다 — 안쪽 무대마다 G2 감시를 돌렸다
    }

    // ── 중8 — 만료 직전 응답이 사건 문자 → 취소 두 통이 되는 창(A-2 보류, app-qa Round2) ──

    @Test fun responseJustBeforeDeadline_noSmsPair() {
        for (d in listOf(250L, 500L, 1_000L, 1_500L, 2_000L, 2_500L, 3_000L, 4_000L, 5_000L)) {
            val s = object : Stage() {}
            val t = s.setup()
            t.car.reading = CarDevice.driving(); t.phone.gpsKmh = 60.0
            s.armCar(t)
            t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
            s.world.runUntil(60_000, "S1") { t.car.state() == FlowState.S1 }
            val expiry = t.car.hub.snapshot.alertStartMs - t.car.clock.mono() + s.world.elapsed + 35_000
            s.world.runUntil(60_000, "누름") { s.world.elapsed >= expiry - d }
            t.watch.pressOk()
            s.world.advance(20_000)
            assertEquals("d=$d 사건 문자", 0, s.events(t))
            assertEquals("d=$d 취소", 0, s.cancels(t))
            s.g2Audit()
        }
        auditNeedsWire = false // 바깥 무대는 쓰지 않는다 — 안쪽 무대마다 G2 감시를 돌렸다
    }

    // ── A-3 — 같은 사건 id 의 취소가 먼저 닿고 사건 문자가 늦게 다시 실려도 0통(공용 GuardianSmsDedup, 켜 둠) ──

    @Test fun lateEventAfterSameIdCancel_noSms() {
        val car = CarDevice(world)
        val phone = PhoneDevice(world)
        val code = car.pairStart()
        phone.join(code)
        phone.install()
        world.advance(6_000)
        val id = "event-${world.relayNow - 40_000}"
        val facts = WireFacts(SnapshotClock.SERVER, firstAlertAt = world.relayNow - 40_000, responseAt = world.relayNow - 1_000)
        var seq = 1L
        fun carSends(kind: GuardianSmsWireKind, cmdId: String) {
            val p = Payload.SendGuardianSms(kind, id, facts, test = false)
            val c = Command(cmdId, CommandType.SEND_GUARDIAN_SMS, p.toJson(), 120_000, false, Party.CAR, CommandTo.PHONE)
            val req = CarSyncRequest(car.token!!, seq++, car.clock.wall(), Vehicle(speedKmh = 0.0), null, listOf(c))
            world.relay.post("/v1/car/sync", Json.encode(req.toJson()))
        }
        carSends(GuardianSmsWireKind.CANCEL, "car-cancel")
        world.advance(6_000)
        carSends(GuardianSmsWireKind.EVENT, "car-event")
        world.advance(10_000)
        assertEquals("취소 뒤 늦게 온 사건 문자는 보내지 않는다", 0, phone.log.sms.size)
    }

    private fun Stage.events(t: Trio) = t.phone.log.sms.map { it.body }.count { it.startsWith(SmsText.HEADER_GUARDIAN) && !it.contains(cancelHead) }
    private fun Stage.cancels(t: Trio) = t.phone.log.sms.map { it.body }.count { it.startsWith(SmsText.HEADER_GUARDIAN) && it.contains(cancelHead) }

}
