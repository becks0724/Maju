// 휴대폰 문자 방어 · 원격 상한 통합 시험 — 다시 짝지은 다른 휴대폰은 옛 사건 문자 0통, 같은 휴대폰은 1통(결정 23), 차에서 온 사건 문자 10분 넷째부터 막힘(D-6)
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

class SmsDefenseTest : Stage() {
    private val cancelHead = SmsText.CANCEL_BODY.substringBefore("%")
    private fun events(p: PhoneDevice) = p.log.sms.map { it.body }.count { it.startsWith(SmsText.HEADER_GUARDIAN) && !it.contains(cancelHead) }

    /** 주행 S3(차가 사건 문자를 냄)까지 가되 [phoneSees] 가 거짓이면 그 휴대폰은 처음부터 중계에 닿지 않아 사건을 보지 못한다. */
    private fun toS3WithPhone(t: Trio, phoneSees: Boolean, phoneCutAtS1: Boolean = false) {
        t.car.reading = CarDevice.driving(); t.phone.gpsKmh = 60.0
        armCar(t)
        if (!phoneSees) t.phone.linkUp = false
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
        world.runUntil(60_000, "S1") { t.car.state() == FlowState.S1 }
        world.advance(4_000)
        if (phoneCutAtS1) t.phone.linkUp = false // 흐름은 봤지만 사건 문자 지시는 받지 못함
        world.runUntil(60_000, "S3") { t.car.state() == FlowState.S3 }
    }

    /** 방어 2(참여 전 사건) — 다른 휴대폰이 다시 짝지으면 차 보낼 함이 다시 실은 옛 사건 문자를 보내지 않는다(남의 보호자에게 남의 위치, 되돌릴 수 없음). */
    @Test fun repairedOtherPhone_doesNotSendOldIncidentSms() {
        val t = setup()
        toS3WithPhone(t, phoneSees = false)
        world.advance(3_000)
        endPair(t)
        world.advance(6_000)
        val other = PhoneDevice(world, monoBase = 987_654_321, wallSkewMs = 12_000)
        val code = t.car.pairStart()
        other.join(code)
        t.watch.join(code)
        other.install()
        world.advance(60_000)
        assertEquals("다른 휴대폰 — 참여 전 사건 문자 0통", 0, events(other))
        assertEquals("옛 휴대폰도 보지 못함", 0, events(t.phone))
    }

    /** 결정 23 — 같은 휴대폰이 사건 도중 다시 짝지으면(참여 전에 그 흐름을 봄) 다시 실린 사건 문자는 보낸다(한 통). */
    @Test fun repairedSamePhone_sendsIncidentSmsOnce() {
        val t = setup()
        toS3WithPhone(t, phoneSees = true, phoneCutAtS1 = true)
        world.advance(3_000)
        endPair(t)
        world.advance(6_000)
        t.phone.linkUp = true
        repair(t)
        world.advance(60_000)
        assertEquals("같은 휴대폰 — 한 통", 1, events(t.phone))
    }

    /** D-6 — 차에서 온 사건 문자는 사건 id 마다 세어 10분 3통, 넷째부터 「보내지 않음(문자 상한)」. */
    @Test fun carEventSms_fourthWithin10MinIsLimited() {
        val car = CarDevice(world)
        val phone = PhoneDevice(world)
        val code = car.pairStart()
        phone.join(code)
        phone.install()
        world.advance(6_000) // 첫 응답으로 참여 시각이 잡힌다
        var seq = 1L
        for (i in 0 until 4) {
            val start = world.relayNow - 5_000 // 참여 뒤에 시작한 사건(참여 전이면 방어 2 가 먼저 막는다)
            val p = Payload.SendGuardianSms(GuardianSmsWireKind.EVENT, "event-$start", WireFacts(SnapshotClock.SERVER, firstAlertAt = start, noResponseAt = start + 35_000), test = false)
            val c = Command("car-ev-$i", CommandType.SEND_GUARDIAN_SMS, p.toJson(), 30_000, false, Party.CAR, CommandTo.PHONE)
            world.relay.post("/v1/car/sync", Json.encode(CarSyncRequest(car.token!!, seq++, car.clock.wall(), Vehicle(speedKmh = 60.0), null, listOf(c)).toJson()))
            world.advance(10_000)
        }
        world.advance(10_000)
        assertEquals("10분 3통", 3, events(phone))
        assertEquals("넷째는 상한으로 보내지 않음", 1, phone.log.smsResults.count { it == ("EVENT" to false) })
    }
}
