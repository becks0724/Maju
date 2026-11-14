// 프로토콜 시험 — 최소 JSON 왕복 · 메시지 모델 왕복 · 워치 직접 경로 · to 기본값 · 동의별 심박 · 스냅숏 주인 넘김 · 지시 변환
package com.example.guardian.shared.protocol

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.AmbientMode
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.GuardianSmsKind
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.ResponseSource
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.flow.shiftedBy
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolTest {
    private fun obj(text: String) = JsonObj(Json.parseObject(text))
    private fun <T> roundTrip(map: Map<String, Any?>, read: (JsonObj) -> T): T = read(obj(Json.encode(map)))

    private val watch = Watch(WristVerdict.PULSE_ABSENT, 48, 1_761_800_000_000, true, WatchSource.SIMULATION, true, null)
    private val vehicle = Vehicle(0.0, Gear.P, null, true, null, "현대", "아반떼", 2027, "서울 종로구 \"세종대로\" 123", 37.57590, 126.97690)
    private val loc = Loc(37.57590, 126.97690, 8.0, 0.0, 1_761_800_000_000, null)
    private val cmd = Command("7d4f0a9e-2f3b-4c55-9d62-0f1e6a1b2c3d", CommandType.ALERT_START, mapOf("level" to 1L), 30_000, false, Party.CAR, CommandTo.WATCH)

    // ── 최소 JSON ──

    @Test fun jsonRoundTripValues() {
        val v = linkedMapOf<String, Any?>(
            "s" to "한글 \"따옴표\" \\ / \n\t\u0001", "i" to 1_761_800_000_123L, "neg" to -5L, "d" to 37.57590, "whole" to 2.0,
            "t" to true, "f" to false, "n" to null, "list" to listOf(1L, "a", null, listOf<Any?>(), mapOf<String, Any?>()),
            "nested" to linkedMapOf<String, Any?>("x" to linkedMapOf<String, Any?>("y" to 1e-7)),
        )
        assertEquals(v, Json.parse(Json.encode(v)))
    }

    @Test fun jsonParsesEscapesAndWhitespace() {
        assertEquals(mapOf("a" to "가é\n", "b" to listOf(1L, 2.5, -3e2)), Json.parse(""" { "a" : "가é\n" , "b":[1, 2.5,-3e2] } """))
    }

    @Test fun jsonRejectsBadInput() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"\u0001\"", "nul", "{'a':1}", "-")) {
            try { Json.parse(bad); fail("통과하면 안 됨: $bad") } catch (_: JsonException) {}
        }
    }

    /** be-qa G — 짝 없는 서로게이트는 U+FFFD, 짝 있는 것은 그대로. */
    @Test fun jsonLoneSurrogateBecomesReplacement() {
        assertEquals("\"a\uFFFDb\"", Json.encode("a\uD800b"))
        assertEquals("\"\uFFFD\"", Json.encode("\uDC00"))
        assertEquals("\"\uFFFDx\"", Json.encode("\uD800x"))
        val emoji = "운전 \uD83D\uDE97"
        assertEquals(emoji, Json.parse(Json.encode(emoji)))
    }

    /** NaN · 무한은 null 로 내보내 sync 전체가 깨지지 않게 한다. */
    @Test fun jsonNonFiniteBecomesNull() {
        assertEquals("""{"a":null,"b":null,"c":null}""", Json.encode(linkedMapOf("a" to Double.NaN, "b" to Double.POSITIVE_INFINITY, "c" to Float.NEGATIVE_INFINITY)))
        val bad = Loc(Double.NaN, 1.0, Double.POSITIVE_INFINITY, null, 0, null)
        assertNull(Json.parseObject(Json.encode(bad.toJson()))["lat"])
        assertEquals("0.0", Json.encode(-0.0))
        assertEquals(1e20, Json.parse(Json.encode(1e20)))
    }

    /** 감사 중4 A 안 — 휴대폰 위치는 전선에 싣지 않는다. 휴대폰 요청 · 차가 받는 peer 어느 쪽에도 `phoneLocation` 키가 없다. */
    @Test fun phoneLocationIsNeverOnTheWire() {
        val req = PhoneSyncRequest("t", 1, 1, watch, null, healthConsent = true)
        assertTrue(!req.toJson(true).containsKey("phoneLocation"))
        assertTrue(!Json.parseObject(Json.encode(req.toJson(false))).containsKey("phoneLocation"))
        val peer = PhonePeer(1, 1, 1, null, null)
        assertTrue(!peer.toJson(true).containsKey("phoneLocation"))
    }

    /** 옛 휴대폰 · 옛 중계가 `phoneLocation` 을 보내도(맞든 틀리든) 오류 없이 무시하고 나머지를 읽는다(계약 §4.1 · §4.6 관용 해석). */
    @Test fun oldPhoneLocationKeyIsIgnored() {
        val locJson = Json.encode(loc.toJson())
        for (v in listOf(locJson, "null", """{"lat":"x"}""", "7")) {
            val r = PhoneSyncRequest.fromJson(obj("""{"token":"t","seq":2,"sentAt":3,"watch":null,"phoneLocation":$v,"flow":null,"commands":[],"acks":[]}"""))
            assertEquals(PhoneSyncRequest("t", 2, 3, null, null), r)
            val c = CarSyncResponse.fromJson(obj("""{"serverTime":5,"peer":{"seq":1,"sentAt":0,"receivedAt":4,"watch":null,"phoneLocation":$v,"flow":null,"responseAt":7},"watch":null,"commands":[]}"""))
            assertEquals(PhonePeer(1, 0, 4, null, null, 7), c.peer)
        }
    }

    /** 감사 하4 — 휴대폰 건강 동의를 매 sync 에 싣는다. null 이면 키 자체가 없다(옛 휴대폰과 같음). */
    @Test fun phoneRequestCarriesHealthConsent() {
        for (hc in listOf(true, false)) {
            val req = PhoneSyncRequest("t", 1, 1, null, null, healthConsent = hc)
            val map = Json.parseObject(Json.encode(req.toJson(hc)))
            assertEquals(hc, map["healthConsent"])
            assertEquals(req, PhoneSyncRequest.fromJson(JsonObj(map)))
        }
        val old = PhoneSyncRequest("t", 1, 1, null, null)
        assertTrue(!old.toJson(true).containsKey("healthConsent"))
        assertNull(PhoneSyncRequest.fromJson(obj("""{"token":"t","seq":1,"sentAt":1,"watch":null,"flow":null,"healthConsent":"no"}""")).healthConsent)
    }

    @Test fun jsonStrictNumbersAndEscapes() {
        for (bad in listOf(".5", "01", "-01", "1.", "1e", "1e+", "+1", "--1", "1e999", "-1e999", "[1e400]", "\"\\u-001\"", "\"\\u+041\"", "\"\\u12\"", "\"\\uZZZZ\"")) {
            try { Json.parse(bad); fail("통과하면 안 됨: $bad") } catch (_: JsonException) {}
        }
        assertEquals(0L, Json.parse("0"))
        assertEquals(-0.5, Json.parse("-0.5"))
        assertEquals(1.5e-3, Json.parse("1.5E-3"))
        assertEquals("A", Json.parse("\"\\u0041\""))
    }

    @Test fun jsonDepthLimit() {
        val ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)
        Json.parse(ok)
        for (bad in listOf("[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1), "[".repeat(100_000), "{\"a\":".repeat(100) + "1" + "}".repeat(100))) {
            try { Json.parse(bad); fail() } catch (_: JsonException) {}
        }
    }

    @Test fun jsonObjTypeChecks() {
        val o = obj("""{"n":1,"s":"x","d":1.5}""")
        assertEquals(1L, o.long("n"))
        assertEquals(1.0, o.double("n"), 0.0)
        for (f in listOf<() -> Unit>({ o.str("n") }, { o.long("s") }, { o.long("d") }, { o.str("missing") })) {
            try { f(); fail() } catch (_: JsonException) {}
        }
    }

    // ── 짝짓기 ──

    @Test fun pairJoinRoleDefaultsToPhone() {
        assertEquals(Role.PHONE, PairJoinRequest.fromJson(obj("""{"pairCode":"012345"}""")).role)
        assertEquals(PairJoinRequest("012345", Role.WATCH), roundTrip(PairJoinRequest("012345", Role.WATCH).toJson(), PairJoinRequest::fromJson))
        assertEquals("012345", PairJoinRequest.fromJson(obj("""{"pairCode":"012345","role":"WATCH"}""")).pairCode)
    }

    @Test fun pairJoinResponsePhoneOrWatch() {
        val phone = PairJoinResponse.fromJson(obj("""{"phoneToken":"aa","pairId":"p1"}"""))
        assertEquals(Role.PHONE, phone.role); assertEquals("aa", phone.token)
        val w = PairJoinResponse.fromJson(obj("""{"watchToken":"bb","pairId":"p1"}"""))
        assertEquals(Role.WATCH, w.role)
        assertEquals("""{"watchToken":"bb","pairId":"p1"}""", Json.encode(w.toJson()))
        assertEquals(PairStartResponse("012345", "c".repeat(32), 600), roundTrip(PairStartResponse("012345", "c".repeat(32), 600).toJson(), PairStartResponse::fromJson))
        assertEquals("PAIR_CODE_INVALID", ErrorResponse.fromJson(obj("""{"error":"PAIR_CODE_INVALID"}""")).error)
        assertEquals(PairEndRequest("t"), roundTrip(PairEndRequest("t").toJson(), PairEndRequest::fromJson))
    }

    // ── 워치 ──

    @Test fun watchOptionalFieldsOmittedEqualNull() {
        val plain = Json.encode(watch.toJson(healthConsent = true))
        assertTrue(!plain.contains("via") && !plain.contains("ageMs") && !plain.contains("receivedAt"))
        assertEquals(watch, Watch.fromJson(obj(plain)))
        val relayed = watch.copy(via = Via.WATCH, receivedAt = 1_761_800_000_500, ageMs = 1_200)
        assertEquals(relayed, roundTrip(relayed.toJson(true), Watch::fromJson))
        // 생략과 null 은 같다
        val withNulls = """{"verdict":"NORMAL","hr":null,"lastPulseAt":null,"worn":true,"source":"SENSOR_SDK","test":false,"responseAt":null,"via":null,"receivedAt":null,"ageMs":null}"""
        val omitted = """{"verdict":"NORMAL","worn":true,"source":"SENSOR_SDK","test":false}"""
        assertEquals(Watch.fromJson(obj(withNulls)), Watch.fromJson(obj(omitted)))
    }

    /** 2026-11-02 상 1 — `noSignal` 은 true 일 때만 싣고, 없으면(옛 판 JSON) false 로 읽는다. */
    @Test fun watchNoSignalIsOptionalAndDefaultsToFalse() {
        val plain = Json.encode(watch.toJson(healthConsent = true))
        assertTrue("false 는 싣지 않음", !plain.contains("noSignal"))
        assertFalse(Watch.fromJson(obj(plain)).noSignal)
        val lost = watch.copy(noSignal = true)
        val wire = Json.encode(lost.toJson(true))
        assertEquals(true, Json.parseObject(wire)["noSignal"])
        assertEquals(lost, Watch.fromJson(obj(wire)))
        // 옛 판 JSON(필드 없음) · 명시한 false · null 모두 false
        val legacy = """{"verdict":"NORMAL","hr":70,"lastPulseAt":null,"worn":true,"source":"HEALTH_SERVICES","test":false,"responseAt":null}"""
        assertFalse(Watch.fromJson(obj(legacy)).noSignal)
        assertFalse(Watch.fromJson(obj(legacy.dropLast(1) + ""","noSignal":false}""")).noSignal)
        assertFalse(Watch.fromJson(obj(legacy.dropLast(1) + ""","noSignal":null}""")).noSignal)
        // 중계가 붙이는 선택 필드와 함께 왕복한다
        val relayed = lost.copy(via = Via.PHONE, receivedAt = 1_761_800_000_500, ageMs = 3)
        assertEquals(relayed, roundTrip(relayed.toJson(true), Watch::fromJson))
        // 응답 · 요청 안에서도 그대로 전해진다
        val cr = CarSyncResponse.fromJson(obj(Json.encode(CarSyncResponse(1, null, relayed, emptyList()).toJson(true))))
        assertTrue(cr.watch!!.noSignal)
        val wr = WatchSyncRequest.fromJson(obj(Json.encode(WatchSyncRequest("t", 1, 1, lost, emptyList()).toJson(true))))
        assertTrue(wr.watch.noSignal)
    }

    @Test fun heartRateOnlyWithHealthConsent() {
        assertEquals(48L, Json.parseObject(Json.encode(watch.toJson(true)))["hr"])
        assertNull(Json.parseObject(Json.encode(watch.toJson(false)))["hr"])
        val req = PhoneSyncRequest("t", 1, 1, watch, null)
        assertNull((Json.parseObject(Json.encode(req.toJson(false)))["watch"] as Map<*, *>)["hr"])
        val res = CarSyncResponse(1, null, watch, emptyList())
        assertNull((Json.parseObject(Json.encode(res.toJson(false)))["watch"] as Map<*, *>)["hr"])
    }

    // ── 지시 ──

    @Test fun commandToDefaultsAndRoundTrip() {
        assertEquals(cmd, roundTrip(cmd.toJson(), Command::fromJson))
        assertEquals(CommandTo.PHONE, cmd.copy(to = null).resolvedTo)
        assertEquals(CommandTo.CAR, cmd.copy(from = Party.PHONE, to = null).resolvedTo)
        assertEquals(CommandTo.PHONE, cmd.copy(from = Party.WATCH, to = null).resolvedTo)
        assertEquals(CommandTo.ALL, cmd.copy(to = CommandTo.ALL).resolvedTo)
        val noPayload = Command.fromJson(obj("""{"id":"x","type":"ALERT_STOP","ttlMs":1000,"test":false,"from":"WATCH"}"""))
        assertEquals(emptyMap<String, Any?>(), noPayload.payload)
        assertNull(noPayload.to)
    }

    @Test fun responseCommandsCarryTo() {
        val text = """{"serverTime":5,"peer":null,"watch":null,"commands":[{"id":"a","type":"SPEAK","payload":{"text":"x","lang":"ko-KR"},"ttlMs":3000,"test":true,"from":"CAR","to":"PHONE"}]}"""
        val r = PhoneSyncResponse.fromJson(obj(text))
        assertEquals(CommandTo.PHONE, r.commands.single().to)
        assertTrue(r.commands.single().test)
        assertEquals(text, Json.encode(r.toJson(true)))
    }

    @Test fun allPayloadsRoundTrip() {
        val snap = FlowMachine().snapshot()
        val payloads = listOf(
            Payload.AlertStart(FlowState.S1, 1, true, false, false, 15_000), Payload.AlertStop(AlertStopReason.RESPONDED),
            Payload.Speak("안전한 곳에 차를 세우세요"), Payload.Show("쉬는 중", "괜찮아요"), Payload.Show("응답 확인 필요", null),
            Payload.Ambient(true, AmbientMode.PARKED_RESCUE), Payload.Ambient(false, null), Payload.OpenSearch("응급실"),
            Payload.Open119Compose("event-1", WireFacts(SnapshotClock.SERVER, 1, 2, 3, null, null, false), true, true),
            Payload.SendGuardianSms(GuardianSmsWireKind.EVENT, "event-1", WireFacts(SnapshotClock.LOCAL, firstAlertAt = 1, carStateMissing = true), false),
            Payload.TestScenario(Scenario.PULSE_LOST, 60_000), Payload.TestScenarioStop, Payload.FlowSnapshot(FlowStatus.of(snap, Owner.CAR)),
            Payload.TestRequest.START, Payload.TestRequest.STOP,
        )
        assertEquals(CommandType.entries.toSet(), payloads.map { it.type }.toSet())
        for (p in payloads) {
            val c = Command("id", p.type, p.toJson(), 1_000, false, Party.CAR, CommandTo.PHONE)
            val back = roundTrip(c.toJson(), Command::fromJson)
            assertEquals(p, Payload.fromJson(back.type, back.payload))
        }
    }

    /** 코드 리뷰 11-09 공통 하1 — 미착용 시작 시각도 넘김에 실리고 시계 변환을 따른다. 이전 판 스냅숏(필드 없음)은 null 로 읽는다. */
    @Test fun unwornSinceSurvivesHandoff() {
        val snap = FlowMachine().snapshot().copy(unwornSinceMs = 5_000)
        val back = roundTrip(SnapshotCodec.toJson(snap), SnapshotCodec::fromJson)
        assertEquals(snap, back)
        assertEquals(1_005_000L, snap.shiftedBy(1_000_000).unwornSinceMs)
        val old = SnapshotCodec.toJson(snap).also { it.remove("unwornSinceMs") }
        assertNull(roundTrip(old, SnapshotCodec::fromJson).unwornSinceMs)
    }

    /** 워치 테스트 멈춤(2026-10-30) — 휴대폰 → 워치, payload 빈 객체, 생략 · null payload 도 읽힌다. */
    @Test fun testScenarioStopWire() {
        val c = Command("ts-1", CommandType.TEST_SCENARIO_STOP, Payload.TestScenarioStop.toJson(), 30_000, true, Party.PHONE, CommandTo.WATCH)
        assertEquals(
            """{"id":"ts-1","type":"TEST_SCENARIO_STOP","payload":{},"ttlMs":30000,"test":true,"from":"PHONE","to":"WATCH"}""",
            Json.encode(c.toJson()),
        )
        val back = Command.fromJson(obj("""{"id":"ts-1","type":"TEST_SCENARIO_STOP","ttlMs":30000,"test":true,"from":"PHONE","to":"WATCH"}"""))
        assertEquals(Payload.TestScenarioStop, Payload.fromJson(back.type, back.payload))
        // 휴대폰이 보내므로 중계로 간다(자기 대상이 아님)
        assertEquals(emptyList<Command>() to listOf(c), DirectiveCommands.splitLocal(listOf(c), Party.PHONE))
    }

    /** 응답의 워치 직접 경로 나이 — 있으면 싣고 읽는다, null 이면 싣지 않는다(옛 응답 · 차 · 휴대폰 공통). */
    @Test fun watchDirectAgeMsWire() {
        val pr = PhoneSyncResponse(9, null, watch.copy(via = Via.PHONE, receivedAt = 9, ageMs = 0), emptyList(), watchDirectAgeMs = 30_001)
        assertEquals(30_001L, Json.parseObject(Json.encode(pr.toJson(true)))["watchDirectAgeMs"])
        assertEquals(pr, roundTrip(pr.toJson(true), PhoneSyncResponse::fromJson))
        assertTrue(!PhoneSyncResponse(9, null, null, emptyList()).toJson(true).containsKey("watchDirectAgeMs"))
        val cr = CarSyncResponse(9, null, null, emptyList(), watchDirectAgeMs = 0)
        assertEquals(cr, roundTrip(cr.toJson(true), CarSyncResponse::fromJson))
        assertNull(CarSyncResponse.fromJson(obj("""{"serverTime":1,"peer":null,"watch":null,"commands":[],"watchDirectAgeMs":null}""")).watchDirectAgeMs)
        assertNull(CarSyncResponse.fromJson(obj("""{"serverTime":1,"peer":null,"watch":null,"commands":[]}""")).watchDirectAgeMs)
    }

    /** 리뷰 중 7 — 상대 `peer.flow` 에 모르는 enum · 빠진 필드가 있으면 그 필드만 null(모름). peer 의 나머지 · watch · commands 는 읽는다. */
    @Test fun unparsablePeerFlowIsNullNotFatal() {
        val cmd = """{"id":"k1","type":"ALERT_STOP","payload":{"reason":"CLEARED"},"ttlMs":30000,"test":false,"from":"PHONE","to":"CAR"}"""
        val w = """{"verdict":"NORMAL","hr":null,"lastPulseAt":null,"worn":true,"source":"SENSOR_SDK","test":false,"responseAt":null,"via":"WATCH","receivedAt":1,"ageMs":0}"""
        val unknownState = """{"state":"S9","since":1,"owner":"PHONE","test":false,"snapshot":{"clock":"SERVER"}}"""
        val missingSnapshot = """{"state":"S1","since":1,"owner":"CAR","test":false}"""

        // 차가 받는 휴대폰 peer
        val cr = CarSyncResponse.fromJson(obj(
            """{"serverTime":5,"peer":{"seq":1,"sentAt":0,"receivedAt":4,"watch":$w,"phoneLocation":null,"flow":$unknownState,"responseAt":7,"carDisplay":{"hideHr":true}},"watch":$w,"commands":[$cmd]}""",
        ))
        val peer = cr.peer!!
        assertNull("흐름은 모름", peer.flow)
        assertEquals(7L, peer.responseAt)
        assertEquals(CarDisplay(true), peer.carDisplay)
        assertEquals(WristVerdict.NORMAL, peer.watch!!.verdict)
        assertEquals(WristVerdict.NORMAL, cr.watch!!.verdict)
        assertEquals(listOf("k1"), cr.commands.map { it.id })

        // 휴대폰이 받는 차 peer — 스냅숏이 빠져도 vehicle 은 읽는다
        val pr = PhoneSyncResponse.fromJson(obj(
            """{"serverTime":5,"peer":{"seq":1,"sentAt":0,"receivedAt":4,"vehicle":{"speedKmh":0.0,"gear":"P"},"flow":$missingSnapshot},"watch":null,"commands":[]}""",
        ))
        assertNull(pr.peer!!.flow)
        assertEquals(Gear.P, pr.peer!!.vehicle.gear)

        // 객체가 아닌 flow 도 모름
        assertNull(FlowStatus.fromJsonOrNull("x"))
        assertNull(FlowStatus.fromJsonOrNull(null))
    }

    /** 코드 리뷰 11-08 하3 — `peer.watch` · 최상위 `watch` 가 틀려도 그 필드만 null(모름), 옛 중계의 틀린 `peer.phoneLocation` 은 무시하고 응답의 나머지는 읽는다. */
    @Test fun unparsableWatchAndLocationAreNullNotFatal() {
        val cmd = """{"id":"k1","type":"ALERT_STOP","payload":{"reason":"CLEARED"},"ttlMs":30000,"test":false,"from":"PHONE","to":"CAR"}"""
        val unknownVerdict = """{"verdict":"PULSE_GONE","hr":null,"lastPulseAt":null,"worn":true,"source":"SENSOR_SDK","test":false,"responseAt":null}"""
        val missingWorn = """{"verdict":"NORMAL","hr":null,"lastPulseAt":null,"source":"SENSOR_SDK","test":false,"responseAt":null}"""
        val badLoc = """{"lat":"37.4","lon":126.8,"accuracyM":null,"speedKmh":null,"at":1,"address":null}"""

        val cr = CarSyncResponse.fromJson(obj(
            """{"serverTime":5,"peer":{"seq":1,"sentAt":0,"receivedAt":4,"watch":$unknownVerdict,"phoneLocation":$badLoc,"flow":null,"responseAt":7},"watch":$missingWorn,"commands":[$cmd]}""",
        ))
        val peer = cr.peer!!
        assertNull(peer.watch)
        assertEquals(7L, peer.responseAt)
        assertNull(cr.watch)
        assertEquals(listOf("k1"), cr.commands.map { it.id })

        val pr = PhoneSyncResponse.fromJson(obj(
            """{"serverTime":5,"peer":{"seq":1,"sentAt":0,"receivedAt":4,"vehicle":{"speedKmh":0.0,"gear":"P"},"flow":null},"watch":"x","commands":[$cmd]}""",
        ))
        assertNull(pr.watch)
        assertEquals(Gear.P, pr.peer!!.vehicle.gear)
        assertEquals(listOf("k1"), pr.commands.map { it.id })
    }

    /** 모르는 type · 형식이 틀린 지시는 그 지시만 건너뛰고 id 를 알린다(2026-10-30) — 나머지 지시 · peer · watch 는 정상. */
    @Test fun unknownCommandTypeIsSkippedNotFatal() {
        val known1 = """{"id":"k1","type":"ALERT_STOP","payload":{"reason":"CLEARED"},"ttlMs":30000,"test":false,"from":"CAR","to":"ALL"}"""
        val unknown = """{"id":"u1","type":"VIBRATE_PATTERN_V2","payload":{"x":1},"ttlMs":30000,"test":false,"from":"CAR","to":"WATCH"}"""
        val known2 = """{"id":"k2","type":"ALERT_START","payload":{"stage":"S1","level":1,"vibrate":true,"sound":false,"maxVolume":false,"durationMs":15000,"futureField":"무시"},"ttlMs":30000,"test":false,"from":"CAR","to":"WATCH"}"""
        val cmds = "[$known1,$unknown,$known2]"
        val w = """{"verdict":"NORMAL","hr":null,"lastPulseAt":null,"worn":true,"source":"SENSOR_SDK","test":false,"responseAt":null,"via":"WATCH","receivedAt":1,"ageMs":0}"""

        val wr = WatchSyncResponse.fromJson(obj("""{"serverTime":5,"commands":$cmds}"""))
        assertEquals(listOf("k1", "k2"), wr.commands.map { it.id })
        assertEquals(listOf("u1"), wr.skippedCommandIds)
        // 아는 type 의 payload 에 모르는 필드가 있어도 읽힌다(payload 해석은 받는 쪽 몫)
        assertEquals(15_000L, (Payload.fromJson(wr.commands[1].type, wr.commands[1].payload) as Payload.AlertStart).durationMs)

        val pr = PhoneSyncResponse.fromJson(obj("""{"serverTime":5,"peer":null,"watch":$w,"commands":$cmds,"watchDirectAgeMs":0}"""))
        assertEquals(listOf("k1", "k2"), pr.commands.map { it.id })
        assertEquals(listOf("u1"), pr.skippedCommandIds)
        assertEquals(Via.WATCH, pr.watch?.via)
        assertEquals(0L, pr.watchDirectAgeMs)

        val cr = CarSyncResponse.fromJson(obj("""{"serverTime":5,"peer":null,"watch":$w,"commands":$cmds}"""))
        assertEquals(listOf("k1", "k2"), cr.commands.map { it.id })
        assertEquals(listOf("u1"), cr.skippedCommandIds)
        // 건너뛴 id 는 직렬화하지 않는다
        assertTrue(!cr.toJson(true).containsKey("skippedCommandIds"))
    }

    /** 모르는 payload 모양(객체가 아님) · 모르는 to · 형이 틀린 필드도 같은 규칙. id 가 없거나 원소가 객체가 아니면 ack 할 수 없어 그냥 버린다. */
    @Test fun malformedCommandIsSkippedLikeUnknownType() {
        val good = """{"id":"g","type":"TEST_SCENARIO_STOP","payload":{},"ttlMs":30000,"test":true,"from":"PHONE","to":"WATCH"}"""
        val badPayload = """{"id":"p","type":"TEST_SCENARIO","payload":"PULSE_LOST","ttlMs":30000,"test":true,"from":"PHONE","to":"WATCH"}"""
        val badTo = """{"id":"t","type":"ALERT_STOP","payload":{},"ttlMs":30000,"test":true,"from":"PHONE","to":"GLASSES"}"""
        val badTtl = """{"id":"l","type":"ALERT_STOP","payload":{},"ttlMs":"30s","test":true,"from":"PHONE","to":"WATCH"}"""
        val noId = """{"type":"MYSTERY","payload":{},"ttlMs":1,"test":true,"from":"PHONE"}"""
        val r = WatchSyncResponse.fromJson(obj("""{"serverTime":5,"commands":[$badPayload,$good,$badTo,$badTtl,$noId,"문자열",null]}"""))
        assertEquals(listOf("g"), r.commands.map { it.id })
        assertEquals(listOf("p", "t", "l"), r.skippedCommandIds)
        assertEquals(emptyList<String>(), WatchSyncResponse.fromJson(obj("""{"serverTime":5,"commands":[$good]}""")).skippedCommandIds)
        // commands 자체가 배열이 아니면 응답 형식 오류 그대로(지시 하나의 문제가 아님)
        try { WatchSyncResponse.fromJson(obj("""{"serverTime":5,"commands":{}}""")); fail() } catch (_: JsonException) {}
    }

    @Test fun blueRedBlinkCannotBeBuilt() {
        try { Payload.Ambient(true, AmbientMode.BLUE_RED_BLINK); fail() } catch (_: IllegalArgumentException) {}
        try { Payload.fromJson(CommandType.AMBIENT, mapOf("on" to true, "mode" to "BLUE_RED_BLINK")); fail() } catch (_: IllegalArgumentException) {}
    }

    // ── sync ──

    @Test fun syncMessagesRoundTrip() {
        val flow = FlowStatus.of(FlowMachine().snapshot(), Owner.PHONE)
        val car = CarSyncRequest("t", 1_761_800_000_000, 1_761_800_000_000, vehicle, flow, listOf(cmd), listOf("a", "b"))
        assertEquals(car, roundTrip(car.toJson(), CarSyncRequest::fromJson))
        val carRes = CarSyncResponse(9, PhonePeer(3, 4, 5, null, null), watch.copy(via = Via.WATCH, receivedAt = 8, ageMs = 1), listOf(cmd))
        assertEquals(carRes, roundTrip(carRes.toJson(true), CarSyncResponse::fromJson))
        val phone = PhoneSyncRequest("t", 2, 2, null, null, emptyList(), emptyList())
        val phoneMap = phone.toJson(true)
        assertTrue(phoneMap.containsKey("watch") && phoneMap["watch"] == null)
        assertEquals(phone, roundTrip(phoneMap, PhoneSyncRequest::fromJson))
        val phoneRes = PhoneSyncResponse(9, CarPeer(3, 4, 5, vehicle, flow), watch.copy(via = Via.PHONE), emptyList())
        assertEquals(phoneRes, roundTrip(phoneRes.toJson(true), PhoneSyncResponse::fromJson))
        val w = WatchSyncRequest("wt", 7, 7, watch, listOf("x"))
        assertTrue(!w.toJson(true).containsKey("commands"))
        assertEquals(w, roundTrip(w.toJson(true), WatchSyncRequest::fromJson))
        val wr = WatchSyncResponse(10, listOf(cmd))
        assertEquals(wr, roundTrip(wr.toJson(), WatchSyncResponse::fromJson))
    }

    @Test fun seqStartsAtEpochAndIncreases() {
        val g = SeqGenerator(1_761_800_000_000)
        assertEquals(1_761_800_000_000, g.next())
        assertEquals(1_761_800_000_001, g.next())
    }

    // ── 주인 넘김(JSON 을 거쳐) ──

    @Test fun snapshotThroughJsonContinuesIdentically() {
        val all = Consents(true, true, true)
        val a = FlowMachine()
        listOf(
            FlowInput.Vehicle(VehicleState(60.0, Gear.D, false), 0), FlowInput.ConsentsChanged(all, 0), FlowInput.Power(true, 0),
            FlowInput.TestMode(true, 0), FlowInput.Wrist(WristVerdict.NORMAL, 0), FlowInput.Wrist(WristVerdict.PULSE_ABSENT, 1_000),
            FlowInput.Tick(16_000),
        ).forEach { a.handle(it) }
        val flow = FlowStatus.of(a.snapshot(), Owner.CAR)
        val received = roundTrip(flow.toJson(), FlowStatus::fromJson)
        assertEquals(flow, received)
        val b = FlowMachine.restore(received.snapshot)
        val next = listOf(
            FlowInput.Tick(36_000), FlowInput.Vehicle(VehicleState(0.0, Gear.P, null), 40_000),
            FlowInput.Response(ResponseSource.CAR_BUTTON, 41_000),
        )
        for (i in next) assertEquals(a.handle(i), b.handle(i))
        assertEquals(a.snapshot(), b.snapshot())
    }

    // ── 지시 변환 ──

    @Test fun directiveMapping() {
        var n = 0
        val id = { "id-${n++}" }
        val alert = DirectiveCommands.toCommands(
            Directive.AlertStart(setOf(com.example.guardian.shared.flow.AlertTarget.WATCH_SOUND, com.example.guardian.shared.flow.AlertTarget.PHONE_ALARM), FlowState.AP, 2, 20_000, true),
            Party.CAR, id, 30_000, null,
        )
        assertEquals(listOf(CommandTo.WATCH, CommandTo.PHONE), alert.map { it.to })
        assertTrue(alert.all { it.test && it.from == Party.CAR })

        val show = DirectiveCommands.toCommands(Directive.Show(Phrase.SCREEN_S1, false), Party.CAR, id, 30_000, null).single()
        assertNull("주행 중 화면에는 버튼 없음", Payload.fromJson(show.type, show.payload).let { (it as Payload.Show).button })
        val s2 = DirectiveCommands.toCommands(Directive.Show(Phrase.SCREEN_S2, false), Party.CAR, id, 30_000, null).single()
        assertEquals("괜찮아요", (Payload.fromJson(s2.type, s2.payload) as Payload.Show).button)
        val noSignal = DirectiveCommands.toCommands(Directive.Show(Phrase.SCREEN_WATCH_NO_SIGNAL, false), Party.CAR, id, 30_000, null)
        assertEquals(listOf(CommandTo.CAR, CommandTo.PHONE), noSignal.map { it.to })

        val facts = IncidentFacts(firstAlertAtMs = 1_000)
        val compose = DirectiveCommands.toCommands(Directive.Open119Compose(facts, previewOnly = false, test = true), Party.CAR, id, 1, null).single()
        assertEquals(true, compose.payload["previewOnly"])
        val sms = DirectiveCommands.toCommands(Directive.SendGuardianSms(GuardianSmsKind.INCIDENT, facts, false), Party.CAR, id, 1, null).single()
        assertEquals(
            Payload.SendGuardianSms(GuardianSmsWireKind.EVENT, "event-L1000", WireFacts(SnapshotClock.LOCAL, firstAlertAt = 1_000), false),
            Payload.fromJson(sms.type, sms.payload),
        )
        val withId = DirectiveCommands.toCommands(Directive.SendGuardianSms(GuardianSmsKind.INCIDENT, facts.copy(eventId = "event-777"), false), Party.CAR, id, 1, null).single()
        assertEquals("상태 기계가 만든 id 를 그대로", "event-777", withId.payload["eventId"])
        val stop = DirectiveCommands.toCommands(Directive.AlertStop(false), Party.CAR, id, 1, null).single()
        assertEquals(CommandTo.ALL, stop.to)
    }

    /** 워치 → 휴대폰 Data Layer 상수(계약 §4.7). 휴대폰 → 워치 경로는 없다. */
    @Test fun dataLayerConstants() {
        assertEquals("/guardian/watch", DataLayer.PATH_WATCH)
        assertEquals("guardian_watch", DataLayer.CAPABILITY_WATCH)
        assertEquals("guardian_phone", DataLayer.CAPABILITY_PHONE)
        assertEquals(1_000L, DataLayer.WATCH_INTERVAL_MS)
    }
}
