// server/guardian-relay/relay.py 의 의미를 옮긴 인메모리 가짜 중계 — 가상 시계로 돌고, 지나간 JSON 을 모두 기록한다(G2 감시)
package com.example.guardian.integration

import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonException
import kotlin.random.Random

/**
 * 계약 contracts/guardian/protocol.md · relay.py(RelayStore)와 같은 규칙.
 * - 짝짓기 — 6자리 코드는 10분 · 역할(PHONE · WATCH)마다 한 번, 토큰은 역할에 묶임, 짝은 24시간.
 *   role 생략 · null = PHONE. pair/code 의 새 코드로는 참여 중인 역할도 다시 붙고 옛 토큰은 401(2026-11-08).
 *   참여 역할 없고 살아 있는 코드도 없는 짝 · 한 번도 참여한 적 없이 첫 코드가 만료된 짝은 지운다.
 * - sync — 쪽마다 최신 상태 1건(seq 가 1 ~ 1,000 줄면 덮지 않음, 1,000 넘게 줄면 재시작으로 보고 덮음),
 *   차 · 휴대폰 응답의 peer, 중계가 고른 watch(via · receivedAt · ageMs),
 *   워치 직접 경로 나이 watchDirectAgeMs(직접 값이 없으면 null),
 *   지시 큐(받는 쪽마다 같은 id 한 번 · ttl · ack · 256개 상한 · `to` 분배 · ALL · 자기 대상 400 · BLUE_RED_BLINK 400), serverTime.
 * - 휴대폰 위치 — 옛 휴대폰이 보낸 `phoneLocation` 은 형식만 보고 저장 · 응답하지 않는다(감사 중4 A 안, 계약 §8).
 * - 건강 동의 — 휴대폰 `healthConsent`(bool · null · 생략, 그 밖은 400)는 짝마다 최근 1건만 중계 안에 둔다(peer 로 내리지 않음,
 *   seq 가 줄어 덮지 않는 늦은 요청은 바꾸지 않음). false 면 워치 직접 기록은 받은 시각만 남기고 `watch` 값은 null
 *   (`watchDirectAgeMs` 는 계속 계산), 휴대폰이 실은 `watch` 도 보관하지 않는다 — 차 · 휴대폰 응답의 `watch` · `peer.watch` 가 모두 null.
 *   false 를 받는 순간 이미 보관한 워치 직접 값도 지우고, true 로 돌아와도 되살리지 않는다(감사 하4, 계약 §5 · §8, relay.py 3차).
 *
 * relay.py 와 다른 점(HTTP 층을 두지 않아서) — 64KB 413 · 405 · 소켓 10초 · 짝 없는 서로게이트 · NaN 검사는 하지 않는다.
 * JSON 해석은 :shared Json 을 쓴다(정수는 Long, 실수는 Double). 코드 · 토큰은 시드 고정 난수다(재현용).
 */
class FakeRelay(private val clock: () -> Long, seed: Long = 7) {
    data class Reply(val status: Int, val body: String)

    /** 지나간 요청 · 응답 하나. [path] 는 경로, 본문은 원문 그대로. */
    data class WireEntry(val atMs: Long, val path: String, val request: String, val status: Int, val response: String)

    val wire = ArrayList<WireEntry>()

    private val rnd = Random(seed)

    private class ApiError(val status: Int, val code: String) : RuntimeException(code)

    private class Code(val pairId: String, val expiresAt: Long, val used: MutableSet<String>)

    private class QItem(val cmd: Map<String, Any?>, val expiresAt: Long)

    private class Pair(val pairId: String, carToken: String, createdAt: Long) {
        val tokens = linkedMapOf<String, String?>("CAR" to carToken, "PHONE" to null, "WATCH" to null)
        val expiresAt = createdAt + PAIR_TTL_MS
        val firstCodeUntil = createdAt + PAIR_CODE_TTL_MS
        var everJoined = false
        val state = linkedMapOf<String, MutableMap<String, Any?>?>("CAR" to null, "PHONE" to null, "WATCH" to null)
        val lastSeq = linkedMapOf<String, Long?>("CAR" to null, "PHONE" to null, "WATCH" to null)
        val queue = ROLES.associateWith { ArrayList<QItem>() }
        val seenIds = ROLES.associateWith { LinkedHashMap<String, Long>() }

        fun chosenWatch(now: Long): Map<String, Any?>? {
            var best: Triple<Long, String, Map<*, *>>? = null
            for (via in listOf("WATCH", "PHONE")) {
                val st = state[via] ?: continue
                val w = st["watch"] as? Map<*, *> ?: continue
                val at = st["receivedAt"] as Long
                if (best == null || at > best.first) best = Triple(at, via, w)
            }
            val b = best ?: return null
            @Suppress("UNCHECKED_CAST")
            return LinkedHashMap(b.third as Map<String, Any?>).apply {
                put("via", b.second); put("receivedAt", b.first); put("ageMs", now - b.first)
            }
        }

        /** relay.py watch_direct_age — 워치가 직접 보낸 마지막 값의 나이. 고른 값이 via=PHONE 이어도 따로 낸다. */
        fun watchDirectAge(now: Long): Long? = (state["WATCH"]?.get("receivedAt") as Long?)?.let { now - it }

        /** 휴대폰이 마지막으로 실은 건강 동의(없음 · null = 옛 휴대폰). */
        var healthConsent: Boolean? = null
        fun healthDenied(): Boolean = healthConsent == false
    }

    private val codes = LinkedHashMap<String, Code>()
    private val pairs = LinkedHashMap<String, Pair>()
    private val tokens = HashMap<String, kotlin.Pair<String, String>>()

    /** 경로 하나를 처리한다. 오류는 `{"error":코드}` 로 답한다. 모든 왕복을 [wire] 에 남긴다. */
    fun post(path: String, body: String): Reply {
        val reply = try {
            val b = parseBody(body)
            val out: Map<String, Any?> = when (path) {
                "/v1/pair/start" -> pairStart()
                "/v1/pair/code" -> pairCode(b)
                "/v1/pair/join" -> pairJoin(b)
                "/v1/pair/end" -> pairEnd(b)
                "/v1/car/sync" -> sync("CAR", b)
                "/v1/phone/sync" -> sync("PHONE", b)
                "/v1/watch/sync" -> sync("WATCH", b)
                else -> throw ApiError(404, "NOT_FOUND")
            }
            Reply(200, Json.encode(out))
        } catch (e: ApiError) {
            Reply(e.status, Json.encode(mapOf("error" to e.code)))
        }
        wire += WireEntry(clock(), path, body, reply.status, reply.body)
        return reply
    }

    // ── 도움 ──

    private fun bad(cond: Boolean) { if (cond) throw ApiError(400, "BAD_REQUEST") }

    // :shared Json 은 Long 범위 밖 정수를 Double 로 읽으므로 relay.py 의 Long 범위 400 과 같다
    private fun isInt(v: Any?) = v is Long || v is Int

    private fun objOrNull(v: Any?) = v == null || v is Map<*, *>

    private fun parseBody(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()
        return try { Json.parseObject(raw) } catch (e: JsonException) { throw ApiError(400, "BAD_REQUEST") }
    }

    private fun hex(n: Int) = (1..n).joinToString("") { "0123456789abcdef"[rnd.nextInt(16)].toString() }

    private fun purge(now: Long) {
        codes.entries.removeAll { it.value.expiresAt <= now }
        pairs.values.filter { it.expiresAt <= now }.sortedBy { it.expiresAt }.forEach { dropPair(it.pairId) }
        val live = codes.values.map { it.pairId }.toSet()
        pairs.values.filter { p ->
            JOIN_ROLES.all { p.tokens[it] == null } && (p.pairId !in live || (!p.everJoined && p.firstCodeUntil <= now))
        }.map { it.pairId }.forEach { dropPair(it) }
    }

    private fun dropCodesOf(pid: String) { codes.entries.removeAll { it.value.pairId == pid } }

    private fun dropPair(pid: String) {
        val p = pairs.remove(pid) ?: return
        p.tokens.values.filterNotNull().forEach { tokens.remove(it) }
        dropCodesOf(pid)
    }

    private fun auth(token: Any?, role: String? = null): kotlin.Pair<Pair, String> {
        if (token !is String) throw ApiError(401, "TOKEN_INVALID")
        val hit = tokens[token] ?: throw ApiError(401, "TOKEN_INVALID")
        if (role != null && hit.second != role) throw ApiError(401, "TOKEN_INVALID")
        return pairs[hit.first]!! to hit.second
    }

    private fun newCode(): String {
        while (true) {
            val c = "%06d".format(rnd.nextInt(1_000_000))
            if (c !in codes) return c
        }
    }

    // ── 짝짓기 ──

    private fun pairStart(): Map<String, Any?> {
        val now = clock()
        purge(now)
        if (pairs.size >= MAX_PAIRS) throw ApiError(503, "CAPACITY")
        val pid = hex(16)
        val carToken = hex(32)
        pairs[pid] = Pair(pid, carToken, now)
        tokens[carToken] = pid to "CAR"
        val code = newCode()
        codes[code] = Code(pid, now + PAIR_CODE_TTL_MS, HashSet())
        return linkedMapOf("pairCode" to code, "carToken" to carToken, "expiresInSec" to PAIR_CODE_TTL_MS / 1000)
    }

    private fun pairCode(b: Map<String, Any?>): Map<String, Any?> {
        val now = clock()
        purge(now)
        val (pair, _) = auth(b["token"], "CAR")
        dropCodesOf(pair.pairId)
        val code = newCode()
        codes[code] = Code(pair.pairId, now + PAIR_CODE_TTL_MS, HashSet())
        return linkedMapOf("pairCode" to code, "expiresInSec" to PAIR_CODE_TTL_MS / 1000)
    }

    private fun pairJoin(b: Map<String, Any?>): Map<String, Any?> {
        val code = b["pairCode"]
        val role = b["role"] ?: "PHONE" // 생략 · null = PHONE(relay.py 2026-11-08)
        bad(role !in JOIN_ROLES)
        role as String
        val now = clock()
        purge(now)
        val hit = (code as? String)?.let { codes[it] }
        val pair = hit?.let { pairs[it.pairId] }
        if (pair == null || role in hit.used) throw ApiError(404, "PAIR_CODE_INVALID")
        hit.used += role
        if (hit.used.containsAll(JOIN_ROLES)) codes.remove(code)
        // 이미 참여한 역할이 다시 붙으면 옛 토큰을 지우고 seq 기준을 비운다
        pair.tokens[role]?.let { tokens.remove(it) }
        pair.lastSeq[role] = null
        pair.everJoined = true
        val token = hex(32)
        pair.tokens[role] = token
        tokens[token] = pair.pairId to role
        return linkedMapOf((if (role == "PHONE") "phoneToken" else "watchToken") to token, "pairId" to pair.pairId)
    }

    private fun pairEnd(b: Map<String, Any?>): Map<String, Any?> {
        purge(clock())
        val (pair, role) = auth(b["token"])
        if (role == "WATCH") {
            pair.tokens["WATCH"]?.let { tokens.remove(it) }
            pair.tokens["WATCH"] = null
            pair.state["WATCH"] = null; pair.lastSeq["WATCH"] = null
            pair.queue.getValue("WATCH").clear()
        } else {
            dropPair(pair.pairId)
        }
        return emptyMap()
    }

    // ── sync ──

    private fun sync(role: String, b: Map<String, Any?>): Map<String, Any?> {
        val stateKey = STATE_KEY.getValue(role)
        bad(!isInt(b["seq"]) || !isInt(b["sentAt"]))
        if (role == "PHONE") {
            bad(!objOrNull(b["watch"]))
            bad(!objOrNull(b["phoneLocation"])) // 옛 휴대폰 빌드 호환 — 검사만 하고 저장하지 않는다
            bad(b["healthConsent"] != null && b["healthConsent"] !is Boolean)
            bad(b["responseAt"] != null && !isInt(b["responseAt"]))
            bad(!objOrNull(b["carDisplay"]))
        } else {
            bad(b[stateKey] !is Map<*, *>)
        }
        if (role != "WATCH") bad(!objOrNull(b["flow"]))
        val commands = b["commands"] ?: emptyList<Any?>()
        val acks = b["acks"] ?: emptyList<Any?>()
        bad(commands !is List<*> || commands.size > MAX_COMMANDS_PER_SYNC)
        bad(acks !is List<*> || !acks.all { it is String })
        commands as List<*>; acks as List<*>
        for (c in commands) {
            bad(c !is Map<*, *>)
            c as Map<*, *>
            val id = c["id"]
            bad(id !is String || id.isEmpty() || id.length > 64)
            bad(c["type"] !in COMMAND_TYPES)
            bad(!objOrNull(c["payload"]))
            val mode = (c["payload"] as? Map<*, *>)?.get("mode")
            bad(c["type"] == "AMBIENT" && mode is String && mode.uppercase() == "BLUE_RED_BLINK")
            val ttl = c["ttlMs"]
            bad(!isInt(ttl) || (ttl as Number).toLong() !in 1..MAX_COMMAND_TTL_MS)
            bad(c["test"] !is Boolean)
            bad(c["from"] != role)
            val to = c["to"]
            bad(to != null && (to !is String || to !in TARGETS || to == role))
            // 허용 표(D-1, 중계 relay.py COMMAND_ROLES 와 같음) — 기본 대상까지 풀어서 본다. 시험용 지시는 test:true 만
            bad(((to as String?) ?: DEFAULT_TO.getValue(role)) !in COMMAND_ROLES.getValue(role)[c["type"]].orEmpty())
            bad(c["type"] in TEST_ONLY_TYPES && c["test"] != true)
        }

        val now = clock()
        purge(now)
        val (pair, _) = auth(b["token"], role)
        val seq = (b["seq"] as Number).toLong()

        val last = pair.lastSeq[role]
        if (last == null || seq >= last || last - seq > SEQ_RESTART_GAP) {
            pair.lastSeq[role] = seq
            if (role == "PHONE") {
                pair.healthConsent = b["healthConsent"] as Boolean?
                // false 를 받는 순간 이미 보관한 워치 직접 값도 지운다(받은 시각은 남긴다)
                if (pair.healthDenied()) pair.state["WATCH"]?.put("watch", null)
            }
            val keepWatch = role == "CAR" || !pair.healthDenied()
            val st = linkedMapOf<String, Any?>("seq" to seq, "sentAt" to b["sentAt"], "receivedAt" to now, stateKey to if (keepWatch) b[stateKey] else null)
            if (role != "WATCH") st["flow"] = b["flow"]
            if (role == "PHONE") {
                st["responseAt"] = b["responseAt"]
                st["carDisplay"] = b["carDisplay"]
            }
            pair.state[role] = st
        }

        val ackSet = acks.map { it as String }.toSet()
        val mine = pair.queue.getValue(role)
        mine.removeAll { it.cmd["id"] in ackSet || it.expiresAt <= now }
        for (seen in pair.seenIds.values) seen.entries.removeAll { it.value <= now }

        for (c in commands) {
            c as Map<*, *>
            val to = (c["to"] as String?) ?: DEFAULT_TO.getValue(role)
            val payload = c["payload"]
            val ttl = (c["ttlMs"] as Number).toLong()
            val cmd = linkedMapOf<String, Any?>(
                "id" to c["id"], "type" to c["type"], "payload" to (payload ?: emptyMap<String, Any?>()),
                "ttlMs" to ttl, "test" to c["test"], "from" to role, "to" to to,
            )
            val targets = if (to == "ALL") ROLES.filter { it != role } else listOf(to)
            for (target in targets) {
                val id = c["id"] as String
                val seen = pair.seenIds.getValue(target)
                if (id in seen) continue
                seen[id] = now + ttl
                val q = pair.queue.getValue(target)
                q += QItem(cmd, now + ttl)
                while (q.size > MAX_QUEUE) q.removeAt(0)
            }
        }

        val out = linkedMapOf<String, Any?>("serverTime" to now)
        when (role) {
            "CAR" -> {
                out["peer"] = pair.state["PHONE"]
                out["watch"] = pair.chosenWatch(now)
                out["watchDirectAgeMs"] = pair.watchDirectAge(now)
            }
            "PHONE" -> { out["peer"] = pair.state["CAR"]; out["watch"] = pair.chosenWatch(now); out["watchDirectAgeMs"] = pair.watchDirectAge(now) }
        }
        out["commands"] = mine.map { it.cmd }
        return out
    }

    /** 시험용 — 지금 그 쪽 큐에 남은 지시 수. */
    fun queued(role: String): Int = pairs.values.sumOf { it.queue.getValue(role).size }

    companion object {
        const val PAIR_CODE_TTL_MS = 10 * 60 * 1000L
        const val PAIR_TTL_MS = 24 * 60 * 60 * 1000L
        const val MAX_COMMAND_TTL_MS = 120 * 1000L // 중계 D-3(2026-11-14)
        const val MAX_QUEUE = 256
        const val MAX_COMMANDS_PER_SYNC = 32
        const val MAX_PAIRS = 64
        const val SEQ_RESTART_GAP = 1000L
        val ROLES = listOf("CAR", "PHONE", "WATCH")
        val JOIN_ROLES = listOf("PHONE", "WATCH")
        val DEFAULT_TO = mapOf("CAR" to "PHONE", "PHONE" to "CAR", "WATCH" to "PHONE")
        val TARGETS = setOf("PHONE", "CAR", "WATCH", "ALL")
        val STATE_KEY = mapOf("CAR" to "vehicle", "PHONE" to "watch", "WATCH" to "watch")
        val COMMAND_TYPES = setOf(
            "ALERT_START", "ALERT_STOP", "SPEAK", "SHOW", "AMBIENT", "OPEN_SEARCH",
            "OPEN_119_COMPOSE", "SEND_GUARDIAN_SMS", "TEST_SCENARIO", "TEST_SCENARIO_STOP", "FLOW_SNAPSHOT",
            "TEST_REQUEST",
        )
        /** 지시 허용 표 — 공용 원본 `contracts/guardian/command-roles.json`(FakeRelayRolesTest 가 대조). */
        val COMMAND_ROLES: Map<String, Map<String, Set<String>>> = mapOf(
            "CAR" to mapOf(
                "ALERT_START" to setOf("WATCH", "PHONE"), "ALERT_STOP" to setOf("ALL", "WATCH", "PHONE"), "SHOW" to setOf("PHONE"),
                "OPEN_119_COMPOSE" to setOf("PHONE"), "SEND_GUARDIAN_SMS" to setOf("PHONE"), "TEST_REQUEST" to setOf("PHONE"),
            ),
            "PHONE" to mapOf(
                "ALERT_START" to setOf("WATCH"), "ALERT_STOP" to setOf("ALL", "WATCH", "CAR"), "SPEAK" to setOf("CAR"), "SHOW" to setOf("CAR"),
                "AMBIENT" to setOf("CAR"), "OPEN_SEARCH" to setOf("CAR"), "FLOW_SNAPSHOT" to setOf("CAR"),
                "TEST_SCENARIO" to setOf("WATCH"), "TEST_SCENARIO_STOP" to setOf("WATCH"),
            ),
            "WATCH" to emptyMap(),
        )
        val TEST_ONLY_TYPES = setOf("TEST_REQUEST", "TEST_SCENARIO", "TEST_SCENARIO_STOP")
    }
}
