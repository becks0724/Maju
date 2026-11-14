// 가짜 중계가 계약(relay.py)과 같은 규칙으로 도는지 — 짝 코드 역할마다 한 번 · 자기 대상 400 · ALL 분배 · ack · TTL · 고른 워치 · seq · 워치 직접 나이 · 워치 테스트 멈춤
package com.example.guardian.integration

import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FakeRelayTest {
    private var now = 1_000_000L
    private val relay = FakeRelay({ now })

    private fun post(path: String, body: Map<String, Any?>) = relay.post(path, Json.encode(body))
    private fun obj(r: FakeRelay.Reply) = JsonObj(Json.parseObject(r.body))

    private fun pair(): Triple<String, String, String> {
        val start = obj(post("/v1/pair/start", emptyMap()))
        val code = start.str("pairCode")
        val phone = obj(post("/v1/pair/join", mapOf("pairCode" to code, "role" to "PHONE"))).str("phoneToken")
        val watch = obj(post("/v1/pair/join", mapOf("pairCode" to code, "role" to "WATCH"))).str("watchToken")
        return Triple(start.str("carToken"), phone, watch)
    }

    private val watchState = mapOf("verdict" to "NORMAL", "hr" to null, "lastPulseAt" to null, "worn" to true, "source" to "SIMULATION", "test" to true, "responseAt" to null)

    private fun cmd(id: String, from: String, to: String?, type: String = "SHOW", ttl: Long = 30_000, payload: Map<String, Any?> = emptyMap()) =
        mapOf("id" to id, "type" to type, "payload" to payload, "ttlMs" to ttl, "test" to false, "from" to from, "to" to to)

    private fun carSync(tok: String, seq: Long = 1, commands: List<Any?> = emptyList(), acks: List<String> = emptyList()) =
        post("/v1/car/sync", mapOf("token" to tok, "seq" to seq, "sentAt" to 0L, "vehicle" to emptyMap<String, Any?>(), "flow" to null, "commands" to commands, "acks" to acks))

    private fun phoneSync(tok: String, seq: Long = 1, watch: Any? = null, commands: List<Any?> = emptyList(), acks: List<String> = emptyList()) =
        post("/v1/phone/sync", mapOf("token" to tok, "seq" to seq, "sentAt" to 0L, "watch" to watch, "flow" to null, "commands" to commands, "acks" to acks))

    @Test
    fun pairCodeOncePerRole() {
        val start = obj(post("/v1/pair/start", emptyMap()))
        val code = start.str("pairCode")
        assertEquals(200, post("/v1/pair/join", mapOf("pairCode" to code)).status)
        assertEquals(404, post("/v1/pair/join", mapOf("pairCode" to code, "role" to "PHONE")).status)
        assertEquals(200, post("/v1/pair/join", mapOf("pairCode" to code, "role" to "WATCH")).status)
        assertEquals("두 역할이 쓰면 코드는 없어진다", 404, post("/v1/pair/join", mapOf("pairCode" to code, "role" to "WATCH")).status)
        assertEquals(400, post("/v1/pair/join", mapOf("pairCode" to code, "role" to "CAR")).status)
    }

    @Test
    fun roleNullIsPhone() {
        val code = obj(post("/v1/pair/start", emptyMap())).str("pairCode")
        assertNotNull(obj(post("/v1/pair/join", mapOf("pairCode" to code, "role" to null))).map["phoneToken"])
    }

    @Test
    fun rejoinWithNewCodeReplacesTokenAndResetsSeq() {
        val (car, phone, _) = pair()
        assertEquals(200, phoneSync(phone, seq = 5_000_000).status)
        val code = obj(post("/v1/pair/code", mapOf("token" to car))).str("pairCode")
        val phone2 = obj(post("/v1/pair/join", mapOf("pairCode" to code))).str("phoneToken")
        assertEquals("옛 토큰은 401", 401, phoneSync(phone, seq = 5_000_001).status)
        assertEquals(200, phoneSync(phone2, seq = 4_999_999).status)
        assertEquals("새 토큰 첫 sync 는 seq 와 관계없이 덮는다", 4_999_999L, obj(carSync(car)).obj("peer").long("seq"))
        assertEquals("코드는 역할마다 한 번", 404, post("/v1/pair/join", mapOf("pairCode" to code)).status)
    }

    @Test
    fun seqSmallRegressionIgnoredLargeIsRestart() {
        val (car, phone, _) = pair()
        carSync(car, seq = 10_000)
        carSync(car, seq = 9_000)
        assertEquals(10_000L, obj(phoneSync(phone)).obj("peer").long("seq"))
        carSync(car, seq = 8_999)
        assertEquals(8_999L, obj(phoneSync(phone, seq = 2)).obj("peer").long("seq"))
    }

    @Test
    fun longOutOfRangeIs400() {
        val (car, _, _) = pair()
        val r = relay.post("/v1/car/sync", """{"token":"$car","seq":9223372036854775808,"sentAt":0,"vehicle":{}}""")
        assertEquals(400, r.status)
        assertEquals(200, relay.post("/v1/car/sync", """{"token":"$car","seq":9223372036854775807,"sentAt":-9223372036854775808,"vehicle":{}}""").status)
    }

    @Test
    fun neverJoinedPairDroppedAtFirstCodeExpiryDespitePairCode() {
        val car = obj(post("/v1/pair/start", emptyMap())).str("carToken")
        now += FakeRelay.PAIR_CODE_TTL_MS - 1
        assertEquals(200, post("/v1/pair/code", mapOf("token" to car)).status)
        now += 1
        assertEquals(401, carSync(car).status)
    }

    @Test
    fun selfTargetAndForbiddenAmbientAre400AndQueueNothing() {
        val (car, phone, _) = pair()
        assertEquals(400, phoneSync(phone, commands = listOf(cmd("a", "PHONE", "CAR"), cmd("b", "PHONE", "PHONE"))).status)
        assertEquals(400, carSync(car, commands = listOf(cmd("c", "CAR", "CAR", "AMBIENT", payload = mapOf("on" to true, "mode" to "blue_red_blink")))).status)
        assertEquals(0, relay.queued("CAR") + relay.queued("PHONE") + relay.queued("WATCH"))
    }

    @Test
    fun allGoesToOthersAndAckIsPerSide() {
        val (car, phone, watch) = pair()
        carSync(car, commands = listOf(cmd("s", "CAR", "ALL", "ALERT_STOP", payload = mapOf("reason" to "CLEARED"))))
        assertEquals(0, relay.queued("CAR"))
        assertEquals(1, relay.queued("PHONE"))
        assertEquals(1, relay.queued("WATCH"))
        phoneSync(phone, acks = listOf("s"))
        assertEquals(0, relay.queued("PHONE"))
        val w = obj(post("/v1/watch/sync", mapOf("token" to watch, "seq" to 1L, "sentAt" to 0L, "watch" to watchState, "acks" to emptyList<String>())))
        assertEquals("ack 전까지 다시 온다", "s", JsonObj(@Suppress("UNCHECKED_CAST") (w.list("commands")[0] as Map<String, Any?>)).str("id"))
        assertEquals("같은 id 재전송은 한 번만", 200, carSync(car, seq = 2, commands = listOf(cmd("s", "CAR", "ALL", "ALERT_STOP", payload = mapOf("reason" to "CLEARED")))).status)
        assertEquals(0, relay.queued("PHONE"))
    }

    @Test
    fun ttlDropsCommand() {
        val (car, phone, _) = pair()
        carSync(car, commands = listOf(cmd("t", "CAR", null, ttl = 5_000)))
        now += 5_000
        assertEquals(0, obj(phoneSync(phone)).list("commands").size)
    }

    @Test
    fun chosenWatchPrefersLaterAndDirectOnTie() {
        val (car, phone, watch) = pair()
        post("/v1/watch/sync", mapOf("token" to watch, "seq" to 1L, "sentAt" to 0L, "watch" to watchState))
        phoneSync(phone, watch = watchState)
        now += 3_000
        val w = obj(carSync(car)).obj("watch")
        assertEquals("같은 시각이면 직접", "WATCH", w.str("via"))
        assertEquals(3_000L, w.long("ageMs"))
        phoneSync(phone, seq = 2, watch = watchState)
        assertEquals("더 늦게 받은 휴대폰 경유", "PHONE", obj(carSync(car, seq = 2)).obj("watch").str("via"))
    }

    @Test
    fun lowerSeqDoesNotOverwrite() {
        val (car, phone, _) = pair()
        phoneSync(phone, seq = 10, watch = watchState)
        phoneSync(phone, seq = 9, watch = null)
        val peer = obj(carSync(car)).obj("peer")
        assertEquals(10L, peer.long("seq"))
        assertNotNull("줄어든 seq 의 null 이 앞선 경유 값을 덮지 않는다", peer.objOrNull("watch"))
    }

    /** 감사 중4 — 옛 휴대폰이 보낸 phoneLocation 은 받되 저장 · 차 peer 로 내리지 않는다. 객체 · null 이 아니면 400(옛 검사 유지). */
    @Test
    fun oldPhoneLocationIsAcceptedButNotStored() {
        val (car, phone, _) = pair()
        val loc = mapOf("lat" to 37.5, "lon" to 127.0, "accuracyM" to 8.0, "speedKmh" to 0.0, "at" to 1L, "address" to "어딘가")
        val body = mapOf("token" to phone, "seq" to 1L, "sentAt" to 0L, "watch" to null, "phoneLocation" to loc, "flow" to null)
        assertEquals(200, post("/v1/phone/sync", body).status)
        assertEquals(false, obj(carSync(car)).obj("peer").map.containsKey("phoneLocation"))
        assertEquals(400, post("/v1/phone/sync", body + ("phoneLocation" to 7L) + ("seq" to 2L)).status)
    }

    /**
     * 감사 하4(relay.py 3차와 같은 규칙) — 휴대폰 healthConsent false 면 차 · 휴대폰 응답의 watch · peer.watch 가 null, 워치 직접 기록은 받은 시각만 남아
     * watchDirectAgeMs 는 계속 나온다. false 를 받으면 보관한 값도 지우고 true 로 돌아와도 되살리지 않는다. peer 에 healthConsent 키는 없다.
     */
    @Test
    fun phoneHealthConsentFalseHidesWatchFromCar() {
        val (car, phone, watch) = pair()
        fun ps(seq: Long, hc: Any?, w: Any? = null) =
            post("/v1/phone/sync", mapOf("token" to phone, "seq" to seq, "sentAt" to 0L, "watch" to w, "flow" to null, "healthConsent" to hc))
        watchSync(watch)
        assertNotNull("받은 적 없음(옛 휴대폰)이면 예전과 같다", obj(carSync(car)).objOrNull("watch"))
        assertEquals(200, ps(1, false, w = watchState).status)
        val c = obj(carSync(car, seq = 2))
        assertNull("false 면 차 응답 watch 는 null", c.map["watch"])
        assertNull("휴대폰이 실은 watch 도 보관하지 않는다", c.obj("peer").map["watch"])
        assertEquals(false, c.obj("peer").map.containsKey("healthConsent"))
        now += 1_000
        assertEquals("워치 sync 는 200", 200, watchSync(watch, seq = 2).status)
        val p = obj(ps(2, false))
        assertNull(p.map["watch"])
        assertEquals("받은 시각은 남아 직접 나이를 계산한다", 0L, p.long("watchDirectAgeMs"))
        assertEquals(400, ps(3, "no").status)
        assertEquals(200, ps(4, true).status)
        assertNull("true 로 돌아와도 지운 값은 되살리지 않는다", obj(carSync(car, seq = 3)).map["watch"])
        assertEquals("늦은(줄어든 seq) 요청은 동의 값을 바꾸지 않는다", 200, ps(3, false).status)
        now += 1_000
        watchSync(watch, seq = 3)
        assertEquals(0L, obj(carSync(car, seq = 4)).obj("watch").long("ageMs"))
    }

    private fun watchSync(tok: String, seq: Long = 1, commands: List<Any?> = emptyList()) =
        post("/v1/watch/sync", mapOf("token" to tok, "seq" to seq, "sentAt" to 0L, "watch" to watchState, "acks" to emptyList<String>(), "commands" to commands))

    @Test
    fun watchDirectAgeEvenWhenPhoneViaIsNewer() {
        val (car, phone, watch) = pair()
        assertNull("직접 값이 없으면 null(휴대폰 경유만)", obj(phoneSync(phone, watch = watchState)).map["watchDirectAgeMs"])
        assertEquals(true, obj(phoneSync(phone, seq = 2, watch = watchState)).map.containsKey("watchDirectAgeMs"))
        watchSync(watch)
        now += 1_000
        val r = obj(phoneSync(phone, seq = 3, watch = watchState))
        assertEquals("PHONE", r.obj("watch").str("via"))
        assertEquals(1_000L, r.long("watchDirectAgeMs"))
        now += 29_001
        assertEquals(30_001L, obj(carSync(car)).long("watchDirectAgeMs"))
        assertEquals("워치 응답에는 없다", setOf("serverTime", "commands"), obj(watchSync(watch, seq = 2)).map.keys)
        post("/v1/pair/end", mapOf("token" to watch))
        assertNull(obj(phoneSync(phone, seq = 4)).map["watchDirectAgeMs"])
    }

    @Test
    fun testScenarioStopGoesPhoneToWatchOnly() {
        val (car, phone, watch) = pair()
        val stop = cmd("ts1", "PHONE", "WATCH", "TEST_SCENARIO_STOP") + ("test" to true) // 시험용 지시는 test:true 만(허용 표 D-1)
        assertEquals(200, phoneSync(phone, commands = listOf(stop)).status)
        assertEquals(0, relay.queued("CAR"))
        assertEquals("TEST_SCENARIO_STOP", JsonObj(@Suppress("UNCHECKED_CAST") (obj(watchSync(watch)).list("commands")[0] as Map<String, Any?>)).str("type"))
        assertEquals("자기 대상은 type 과 무관하게 400", 400, phoneSync(phone, seq = 2, commands = listOf(cmd("ts2", "PHONE", "PHONE", "TEST_SCENARIO_STOP"))).status)
        assertEquals(400, watchSync(watch, seq = 2, commands = listOf(cmd("ts3", "WATCH", "WATCH", "TEST_SCENARIO_STOP"))).status)
        assertEquals(200, carSync(car).status)
    }
}
