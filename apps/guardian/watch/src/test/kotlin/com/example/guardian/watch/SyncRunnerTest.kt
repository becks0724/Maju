// 동기화 시험 — 워치 sync 요청 직렬화(계약 필드만 · 원신호 없음 · 동의 없으면 hr null), 지시 처리 뒤 ack 왕복, 401 · 실패, 짝 참여 role WATCH
package com.example.guardian.watch

import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.HrStatus
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.SeqGenerator
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.protocol.WatchSyncResponse
import com.example.guardian.watch.core.WatchAction
import com.example.guardian.watch.core.WatchEngine
import com.example.guardian.watch.net.HttpResponse
import com.example.guardian.watch.net.HttpTransport
import com.example.guardian.watch.net.RelayApi
import com.example.guardian.watch.net.RelayResult
import com.example.guardian.watch.net.SyncRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncRunnerTest {
    private val clocks = FakeClocks()

    /** 보낸 요청을 기록하고 정해 둔 응답을 차례로 돌려주는 가짜 전송. */
    private class FakeHttp(val replies: ArrayDeque<() -> HttpResponse>) : HttpTransport {
        val sent = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun post(path: String, body: String): HttpResponse {
            sent += path to Json.parseObject(body)
            return replies.removeFirst()()
        }
    }

    private fun ok(commands: List<Command> = emptyList()) = HttpResponse(200, Json.encode(WatchSyncResponse(clocks.wall(), commands).toJson()))

    private fun engineWithPulse(): WatchEngine = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false).also { e ->
        e.hr(HrSample(clocks.monoMs, 72, HrStatus.OK, listOf(833)))
        e.tick(clocks.monoMs)
    }

    @Test fun requestCarriesOnlyContractFields() {
        val e = engineWithPulse()
        val http = FakeHttp(ArrayDeque(listOf({ ok() })))
        SyncRunner(RelayApi(http), e, clocks, SeqGenerator(500)).runOnce("tok", healthConsent = true)

        val (path, body) = http.sent.single()
        assertEquals("/v1/watch/sync", path)
        assertEquals(setOf("token", "seq", "sentAt", "watch", "acks"), body.keys)
        assertEquals("tok", body["token"])
        assertEquals(500L, body["seq"])
        assertEquals(clocks.wall(), body["sentAt"])
        @Suppress("UNCHECKED_CAST") val w = body["watch"] as Map<String, Any?>
        // 계약 §4.3 의 일곱 필드 + 늘 싣는 alerting(안 울리면 null) — PPG · 가속도 원신호는 없다(G2). via · receivedAt · ageMs 는 응답 전용.
        // responseSeq · noSignal 은 값이 있을 때만 싣는다
        assertEquals(setOf("verdict", "hr", "lastPulseAt", "worn", "source", "test", "responseAt", "alerting"), w.keys)
        assertNull(w["alerting"])
        assertEquals("NORMAL", w["verdict"])
        assertEquals(72L, w["hr"])
        assertEquals("SENSOR_SDK", w["source"])
        assertEquals(false, w["test"])
        assertEquals(true, w["worn"])
        assertNull(w["responseAt"])
    }

    @Test fun hrIsNullWithoutHealthConsent() {
        val http = FakeHttp(ArrayDeque(listOf({ ok() })))
        SyncRunner(RelayApi(http), engineWithPulse(), clocks).runOnce("tok", healthConsent = false)
        @Suppress("UNCHECKED_CAST") val w = http.sent.single().second["watch"] as Map<String, Any?>
        assertTrue(w.containsKey("hr"))
        assertNull(w["hr"])
    }

    @Test fun commandsAreHandledAndAckedOnNextSync() {
        val e = engineWithPulse()
        val alert = Command(
            "c-1", com.example.guardian.shared.protocol.CommandType.ALERT_START,
            Payload.AlertStart(FlowState.S1, 1, true, false, false, 15_000).toJson(), 30_000, false, Party.CAR, CommandTo.WATCH,
        )
        val scenario = Command(
            "c-2", com.example.guardian.shared.protocol.CommandType.TEST_SCENARIO,
            Payload.TestScenario(Scenario.LOOSE, 60_000).toJson(), 30_000, true, Party.PHONE, CommandTo.WATCH,
        )
        val http = FakeHttp(ArrayDeque(listOf({ ok(listOf(alert, scenario)) }, { ok(listOf(alert)) }, { ok() })))
        val runner = SyncRunner(RelayApi(http), e, clocks)

        val first = runner.runOnce("tok", true) as SyncRunner.Outcome.Ok
        assertEquals(2, first.actions.size)
        assertTrue(first.actions[0] is WatchAction.StartAlert)
        assertEquals(WatchAction.RunScenario(Scenario.LOOSE, 60_000), first.actions[1])
        assertTrue("ack 가 남아 곧바로 한 번 더", first.acksLeft)

        // 둘째 sync 에 ack 를 싣는다. 중계가 같은 지시를 다시 줘도 다시 실행하지 않는다
        val second = runner.runOnce("tok", true) as SyncRunner.Outcome.Ok
        assertEquals(listOf("c-1", "c-2"), http.sent[1].second["acks"])
        assertTrue(second.actions.isEmpty())
        // 다시 온 c-1 은 셋째 sync 에 다시 ack, 그 뒤 비어 있다
        runner.runOnce("tok", true)
        assertEquals(listOf("c-1"), http.sent[2].second["acks"])
        @Suppress("UNCHECKED_CAST") val w = http.sent[2].second["watch"] as Map<String, Any?>
        assertEquals("SIMULATION", w["source"])
        assertEquals(true, w["test"])
    }

    @Test fun failedSyncKeepsAcks() {
        val e = engineWithPulse()
        val alert = Command(
            "k-1", com.example.guardian.shared.protocol.CommandType.ALERT_STOP,
            Payload.AlertStop(com.example.guardian.shared.protocol.AlertStopReason.CLEARED).toJson(), 30_000, false, Party.CAR, CommandTo.ALL,
        )
        val http = FakeHttp(ArrayDeque(listOf({ ok(listOf(alert)) }, { throw IOException("끊김") }, { HttpResponse(500, "{\"error\":\"INTERNAL\"}") }, { ok() })))
        val runner = SyncRunner(RelayApi(http), e, clocks)
        runner.runOnce("tok", true)
        assertTrue(runner.runOnce("tok", true) is SyncRunner.Outcome.Failed)
        assertTrue(runner.runOnce("tok", true) is SyncRunner.Outcome.Failed)
        runner.runOnce("tok", true)
        assertEquals(listOf("k-1"), http.sent[3].second["acks"])
        assertEquals(emptyList<String>(), e.commands.acks())
    }

    @Test fun unauthorizedIsReported() {
        val http = FakeHttp(ArrayDeque(listOf({ HttpResponse(401, "{\"error\":\"TOKEN_INVALID\"}") })))
        assertEquals(SyncRunner.Outcome.Unauthorized, SyncRunner(RelayApi(http), engineWithPulse(), clocks).runOnce("tok", true))
    }

    @Test fun lastPulseAndResponseAreWallClock() {
        val e = engineWithPulse()
        clocks.monoMs += 2_000
        e.respond(clocks.monoMs, clocks.wall())
        val w = e.watch(clocks)
        assertEquals(clocks.wall(), w.responseAt)
        assertEquals(clocks.wall() - 2_000, w.lastPulseAt)
    }

    @Test fun joinSendsRoleWatchAndMapsInvalidCode() {
        val http = FakeHttp(
            ArrayDeque(
                listOf(
                    { HttpResponse(200, "{\"watchToken\":\"${"a".repeat(32)}\",\"pairId\":\"${"b".repeat(16)}\"}") },
                    { HttpResponse(404, "{\"error\":\"PAIR_CODE_INVALID\"}") },
                    { HttpResponse(200, "{\"phoneToken\":\"${"c".repeat(32)}\",\"pairId\":\"${"d".repeat(16)}\"}") },
                ),
            ),
        )
        val api = RelayApi(http)
        val ok = api.join("012345") as RelayResult.Ok
        assertEquals("a".repeat(32), ok.value.token)
        assertEquals("/v1/pair/join", http.sent[0].first)
        assertEquals(mapOf("pairCode" to "012345", "role" to "WATCH"), http.sent[0].second)
        assertEquals(RelayResult.InvalidCode, api.join("999999"))
        assertTrue("휴대폰 토큰이 오면 받지 않는다", api.join("111111") is RelayResult.Failed)
    }

    @Test fun unknownTypeIsSkippedAndAckedOnNextSync() {
        val e = engineWithPulse()
        val alert = Command(
            "c-1", com.example.guardian.shared.protocol.CommandType.ALERT_START,
            Payload.AlertStart(FlowState.S1, 1, true, false, false, 15_000).toJson(), 30_000, false, Party.CAR, CommandTo.WATCH,
        )
        // 이 빌드가 모르는 type 의 지시(앱 빌드가 섞인 경우, 계약 §4.6)
        val future = LinkedHashMap(alert.toJson()).apply { put("id", "u-1"); put("type", "FUTURE_COMMAND"); put("payload", emptyMap<String, Any?>()) }
        val mixed = linkedMapOf<String, Any?>("serverTime" to clocks.wall(), "commands" to listOf(future, alert.toJson()))
        val onlyUnknown = linkedMapOf<String, Any?>("serverTime" to clocks.wall(), "commands" to listOf(LinkedHashMap(future).apply { put("id", "u-2") }))
        val http = FakeHttp(
            ArrayDeque(
                listOf(
                    { HttpResponse(200, Json.encode(mixed)) },
                    { HttpResponse(200, Json.encode(onlyUnknown)) },
                    { ok() },
                    { ok() },
                ),
            ),
        )
        val runner = SyncRunner(RelayApi(http), e, clocks)

        val first = runner.runOnce("tok", true) as SyncRunner.Outcome.Ok
        assertEquals(1, first.actions.size)
        assertTrue("아는 지시는 실행", first.actions[0] is WatchAction.StartAlert)
        assertTrue(first.acksLeft)

        val second = runner.runOnce("tok", true) as SyncRunner.Outcome.Ok
        assertEquals(setOf("c-1", "u-1"), (http.sent[1].second["acks"] as List<*>).toSet()) // ack 순서는 뜻이 없다
        assertTrue(second.actions.isEmpty())
        assertTrue("모르는 지시만 와도 곧바로 ack 하러 한 번 더", second.acksLeft)

        runner.runOnce("tok", true)
        assertEquals(listOf("u-2"), http.sent[2].second["acks"])
        runner.runOnce("tok", true)
        assertEquals(emptyList<String>(), http.sent[3].second["acks"])
    }
}
