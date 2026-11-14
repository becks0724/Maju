// 사건 사실 지시 시험 — 차 시계 ±90초 왕복 복원 · 보정값 없을 때 근사 · 자기 지시 · 개인정보 없음 · 자기 대상 분리
package com.example.guardian.shared.protocol

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentFactsWireTest {
    /** 차 단조 = 중계 + skew. 첫 경보 → 정차 → 35초 무응답(S4) 까지 차에서 돌린 지시. */
    private fun carIncident(skew: Long, withClock: Boolean): Pair<List<Directive>, Long?> {
        fun car(server: Long) = server + skew
        val clock = ClockOffset().apply { add(car(10_000), car(10_000) + 100, 10_050) }
        val offset = if (withClock) clock.offsetAt(car(10_200)) else null
        val m = FlowMachine()
        val log = ArrayList<Directive>()
        if (offset != null) m.handle(FlowInput.ClockSync(offset, car(10_200)))
        listOf(
            FlowInput.Vehicle(VehicleState(0.0, Gear.P, true), car(11_000)),
            FlowInput.ConsentsChanged(Consents(true, true, true), car(11_000)), FlowInput.Power(true, car(11_000)),
            FlowInput.Wrist(WristVerdict.NORMAL, car(11_000)), FlowInput.Wrist(WristVerdict.PULSE_ABSENT, car(20_000)),
            FlowInput.Tick(car(56_000)),
        ).forEach { log += m.handle(it) }
        return log to offset
    }

    private fun throughJson(c: Command): Command = Command.fromJson(JsonObj(Json.parseObject(Json.encode(c.toJson()))))

    @Test fun carSkewPlusMinus90sRestoresOnPhone() {
        val phoneOffset = 1_000_000L // 휴대폰 단조 = 중계 − 1,000,000
        for (skew in listOf(-90_000L, 0L, 90_000L)) {
            val (log, carOffset) = carIncident(skew, withClock = true)
            var n = 0
            val cmds = log.flatMap { DirectiveCommands.toCommands(it, Party.CAR, { "c${n++}" }, 60_000, carOffset) }.map(::throughJson)
            val sms = cmds.single { it.type == CommandType.SEND_GUARDIAN_SMS }
            val p = Payload.fromJson(sms.type, sms.payload) as Payload.SendGuardianSms
            assertEquals(SnapshotClock.SERVER, p.facts.clock)
            assertEquals("event-20000", p.eventId)
            val r = p.facts.toLocal(p.eventId, phoneOffset, nowLocalMs = 56_000 - phoneOffset, sameDevice = false)
            assertTrue(r.clockVerified)
            // 중계 20,000(첫 경보 · 정차) · 55,000(무응답) → 휴대폰 단조
            assertEquals("skew $skew", 20_000 - phoneOffset, r.facts.firstAlertAtMs)
            assertEquals(20_000 - phoneOffset, r.facts.stoppedAtMs)
            assertEquals(55_000 - phoneOffset, r.facts.noResponseAtMs)
            assertEquals("event-20000", r.facts.eventId)
            val compose = cmds.single { it.type == CommandType.OPEN_119_COMPOSE }
            val cp = Payload.fromJson(compose.type, compose.payload) as Payload.Open119Compose
            assertEquals(55_000 - phoneOffset, cp.facts.toLocal(cp.eventId, phoneOffset, 0, false).facts.noResponseAtMs)
            assertFalse(cp.previewOnly)
        }
    }

    @Test fun noCarClockSendsLocalAndPhoneRebasesConservatively() {
        val (log, carOffset) = carIncident(90_000, withClock = false)
        val d = log.filterIsInstance<Directive.SendGuardianSms>().single()
        val p = DirectiveCommands.toCommands(d, Party.CAR, { "x" }, 1, carOffset).single().let { Payload.fromJson(it.type, it.payload) } as Payload.SendGuardianSms
        assertEquals(SnapshotClock.LOCAL, p.facts.clock)
        assertTrue(p.eventId.startsWith("event-L"))
        // 다른 기기의 LOCAL 은 그대로 못 쓴다 — 가장 늦은 시각을 지금에 맞추고 간격은 지킨다
        val r = p.facts.toLocal(p.eventId, offsetMs = 5, nowLocalMs = 700, sameDevice = false)
        assertFalse(r.clockVerified)
        assertEquals(700L, r.facts.noResponseAtMs)
        assertEquals(700L - 35_000, r.facts.firstAlertAtMs)
        // 휴대폰 보정값이 없어도 SERVER 는 근사로
        val s = WireFacts(SnapshotClock.SERVER, firstAlertAt = 10, noResponseAt = 45).toLocal("e", null, 1_000, false)
        assertEquals(listOf(965L, 1_000L), listOf(s.facts.firstAlertAtMs, s.facts.noResponseAtMs))
        assertFalse(s.clockVerified)
    }

    @Test fun phoneOwnerSelfCommandIsExact() {
        val f = IncidentFacts(firstAlertAtMs = 100, noResponseAtMs = 135_100, carStateMissing = true, eventId = "event-L100")
        val local = WireFacts.of(f, null)
        assertEquals(f, local.toLocal("event-L100", null, 999_999, sameDevice = true).facts)
        val server = WireFacts.of(f, 40)
        assertEquals(f, server.toLocal("event-L100", 40, 999_999, sameDevice = true).facts)
        assertTrue(server.carStateMissing)
    }

    /** 문자 지시에는 본문 · 이름 · 번호가 없다(G2) — 키 목록을 고정한다. */
    @Test fun smsCommandsCarryNoPersonalInfo() {
        val (log, carOffset) = carIncident(0, withClock = true)
        val cmds = log.flatMap { DirectiveCommands.toCommands(it, Party.CAR, { "c" }, 1, carOffset) }
        val factKeys = setOf("clock", "firstAlertAt", "stoppedAt", "noResponseAt", "responseAt", "accidentAt", "carStateMissing")
        val sms = cmds.single { it.type == CommandType.SEND_GUARDIAN_SMS }
        assertEquals(factKeys + setOf("kind", "eventId", "test"), sms.payload.keys)
        val compose = cmds.single { it.type == CommandType.OPEN_119_COMPOSE }
        assertEquals(factKeys + setOf("eventId", "previewOnly", "test"), compose.payload.keys)
        val json = Json.encode(cmds.map { it.toJson() })
        for (w in listOf("body", "010-", "홍길동", "보호자:", "12가")) assertFalse(w, json.contains(w))
    }

    @Test fun testDirectiveIsAlwaysPreview() {
        val c = DirectiveCommands.toCommands(Directive.Open119Compose(IncidentFacts(firstAlertAtMs = 1), false, test = true), Party.PHONE, { "i" }, 1, null).single()
        val p = Payload.fromJson(c.type, c.payload) as Payload.Open119Compose
        assertTrue(p.previewOnly && p.test)
    }

    /** 보내는 쪽이 test=true · previewOnly=false 로 잘못 보내도 받는 쪽은 미리보기만(G4). */
    @Test fun receiverForcesPreviewForTest() {
        val json = """{"eventId":"e","clock":"SERVER","firstAlertAt":1,"stoppedAt":null,"noResponseAt":null,"responseAt":null,"accidentAt":null,"carStateMissing":false,"previewOnly":false,"test":true}"""
        val p = Payload.fromJson(CommandType.OPEN_119_COMPOSE, Json.parseObject(json)) as Payload.Open119Compose
        assertTrue(p.previewOnly)
        val real = Payload.fromJson(CommandType.OPEN_119_COMPOSE, Json.parseObject(json.replace("\"test\":true", "\"test\":false"))) as Payload.Open119Compose
        assertFalse(real.previewOnly)
    }

    @Test fun phoneOwnerSplitsSelfBoundCommands() {
        val (log, _) = carIncident(0, withClock = false)
        var n = 0
        val cmds = log.flatMap { DirectiveCommands.toCommands(it, Party.PHONE, { "p${n++}" }, 1, null) }
        val (local, remote) = DirectiveCommands.splitLocal(cmds, Party.PHONE)
        assertTrue(local.any { it.type == CommandType.SEND_GUARDIAN_SMS } && local.any { it.type == CommandType.OPEN_119_COMPOSE })
        assertTrue(local.all { it.resolvedTo == CommandTo.PHONE })
        assertTrue("중계로 가는 지시에 자기 대상 없음", remote.none { it.resolvedTo == CommandTo.PHONE })
        assertTrue(remote.any { it.resolvedTo == CommandTo.CAR })
    }
}
