// 차 화면 표시 설정(carDisplay)과 차 → 휴대폰 테스트 요청(TEST_REQUEST) 직렬화 시험 — 계약 §4.1 · §4.6(2026-11-01)
package com.example.guardian.shared.protocol

import com.example.guardian.shared.bio.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CarDisplayTestRequestTest {
    private fun obj(text: String) = JsonObj(Json.parseObject(text))

    // ── carDisplay ──

    @Test fun phoneRequestCarriesCarDisplay() {
        val req = PhoneSyncRequest("t", 1, 1, null, null, carDisplay = CarDisplay(hideHr = true))
        val map = req.toJson(healthConsent = true)
        assertEquals(mapOf("hideHr" to true), map["carDisplay"])
        assertEquals(req, PhoneSyncRequest.fromJson(obj(Json.encode(map))))
        // 값이 없으면 null 로 싣는다(필드는 늘 있다)
        val none = PhoneSyncRequest("t", 1, 1, null, null).toJson(true)
        assertTrue(none.containsKey("carDisplay") && none["carDisplay"] == null)
    }

    @Test fun phonePeerCarDisplayRoundTrip() {
        val peer = PhonePeer(3, 4, 5, null, null, carDisplay = CarDisplay(false))
        val res = CarSyncResponse(9, peer, null, emptyList())
        assertEquals(res, CarSyncResponse.fromJson(obj(Json.encode(res.toJson(true)))))
    }

    /** 차는 모르면 숨긴다 — 필드 없음 · null · 형식 틀림 모두 「모름」이고, 응답 전체는 그대로 읽힌다. */
    @Test fun unknownCarDisplayMeansHidden() {
        val base = """"seq":3,"sentAt":4,"receivedAt":5,"watch":null,"phoneLocation":null,"flow":null,"responseAt":null"""
        val cases = listOf("", ""","carDisplay":null""", ""","carDisplay":{}""", ""","carDisplay":{"hideHr":"no"}""", ""","carDisplay":true""", ""","carDisplay":[1]""")
        for (cd in cases) {
            val r = CarSyncResponse.fromJson(obj("""{"serverTime":9,"peer":{$base$cd},"watch":null,"commands":[]}"""))
            assertNull(cd, r.peer!!.carDisplay)
            assertTrue(cd, CarDisplay.hidden(r.peer!!.carDisplay))
        }
        assertTrue(CarDisplay.hidden(null))
        assertTrue(CarDisplay.hidden(CarDisplay(true)))
        assertFalse(CarDisplay.hidden(CarDisplay(false)))
        val shown = CarSyncResponse.fromJson(obj("""{"serverTime":9,"peer":{$base,"carDisplay":{"hideHr":false}},"watch":null,"commands":[]}"""))
        assertEquals(CarDisplay(false), shown.peer!!.carDisplay)
    }

    // ── TEST_REQUEST ──

    @Test fun testRequestWire() {
        val start = Command("tr-1", CommandType.TEST_REQUEST, Payload.TestRequest.START.toJson(), 30_000, true, Party.CAR, CommandTo.PHONE)
        assertEquals(
            """{"id":"tr-1","type":"TEST_REQUEST","payload":{"action":"START","scenario":"PULSE_LOST","durationMs":120000},"ttlMs":30000,"test":true,"from":"CAR","to":"PHONE"}""",
            Json.encode(start.toJson()),
        )
        assertEquals("""{"action":"STOP"}""", Json.encode(Payload.TestRequest.STOP.toJson()))
        // 차가 보내므로 중계로 간다
        assertEquals(emptyList<Command>() to listOf(start), DirectiveCommands.splitLocal(listOf(start), Party.CAR))
        val back = PhoneSyncResponse.fromJson(obj("""{"serverTime":1,"peer":null,"watch":null,"commands":[${Json.encode(start.toJson())}]}"""))
        assertEquals(Payload.TestRequest.START, Payload.fromJson(back.commands.single().type, back.commands.single().payload))
        assertEquals(emptyList<String>(), back.skippedCommandIds)
    }

    @Test fun testRequestStopIgnoresExtraFields() {
        assertEquals(Payload.TestRequest.STOP, Payload.fromJson(CommandType.TEST_REQUEST, mapOf("action" to "STOP", "scenario" to "NORMAL")))
    }

    /** 고정값이 아닌 시작 · 모르는 action · 빠진 필드는 형식 오류 — 받는 쪽은 실행하지 않고 ack 한다(§4.6). */
    @Test fun testRequestMalformed() {
        val bad = listOf(
            mapOf("action" to "START", "scenario" to "NOT_WORN", "durationMs" to 120_000L),
            mapOf("action" to "START", "scenario" to "PULSE_LOST", "durationMs" to 60_000L),
            mapOf("action" to "START", "scenario" to "PULSE_LOST"),
            mapOf("action" to "START", "durationMs" to 120_000L),
            mapOf("action" to "PAUSE"),
            mapOf("action" to "start"),
            mapOf<String, Any?>(),
        )
        for (p in bad) {
            try { Payload.fromJson(CommandType.TEST_REQUEST, p); fail("통과하면 안 됨: $p") } catch (_: RuntimeException) {}
        }
        try { Payload.TestRequest(TestAction.START, Scenario.NORMAL, 120_000); fail() } catch (_: IllegalArgumentException) {}
        try { Payload.TestRequest(TestAction.STOP, Scenario.PULSE_LOST, 120_000); fail() } catch (_: IllegalArgumentException) {}
    }

    /** 새 type 옆에 모르는 type 이 섞여도 그 지시만 건너뛴다(§4.6). */
    @Test fun testRequestBesideUnknownType() {
        val tr = """{"id":"tr","type":"TEST_REQUEST","payload":{"action":"STOP"},"ttlMs":30000,"test":true,"from":"CAR","to":"PHONE"}"""
        val unknown = """{"id":"u","type":"TEST_REQUEST_V2","payload":{},"ttlMs":30000,"test":true,"from":"CAR","to":"PHONE"}"""
        val r = PhoneSyncResponse.fromJson(obj("""{"serverTime":1,"peer":null,"watch":null,"commands":[$tr,$unknown]}"""))
        assertEquals(listOf("tr"), r.commands.map { it.id })
        assertEquals(listOf("u"), r.skippedCommandIds)
    }
}
