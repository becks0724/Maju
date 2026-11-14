// 경보 사건 id 규칙 시험 — 생산 지시 변환(DirectiveCommands)이 만든 지시를 JSON 왕복해 엔진에 넣는다. 정지한 사건의 시작은 버림 · 같은 사건 · 단계 · 강도는 다시 걸지 않음 · 정지는 같은 사건만 · 옛 지시는 지금 규칙, 응답 번호 저장 · 왕복 · 재시작(C-2 · B-5)
package com.example.guardian.watch

import com.example.guardian.shared.flow.AlertTarget
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.DirectiveCommands
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.protocol.WatchAlertStatus
import com.example.guardian.watch.core.WatchEngine
import com.example.guardian.watch.service.PairStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEventRulesTest {
    private val clocks = FakeClocks()
    private val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
    private var ids = 0

    /**
     * 주인(차)의 상태 기계 지시를 생산 변환([DirectiveCommands.toCommands])으로 중계 지시로 바꾸고, 중계를 지나는 것처럼 JSON 으로
     * 왕복한 뒤 워치 몫만 고른다. [eventId] 가 null 이면 사건 id 를 싣지 않는 옛 지시와 같은 모양이다(필드가 빠진 Payload).
     */
    private fun relayed(d: Directive): Command = DirectiveCommands.toCommands(d, Party.CAR, { "c-${++ids}" }, 30_000, offsetMs = null)
        .map { Command.fromJson(JsonObj(Json.parseObject(Json.encode(it.toJson())))) }
        .single { it.resolvedTo == CommandTo.WATCH || it.resolvedTo == CommandTo.ALL }

    private fun start(eventId: String?, stage: FlowState = FlowState.S1, level: Int = 1): Command {
        val target = if (level >= 2) AlertTarget.WATCH_SOUND else AlertTarget.WATCH_VIBRATION
        return relayed(Directive.AlertStart(setOf(target), stage, level, 15_000, test = false, eventId = eventId))
    }

    private fun stop(eventId: String?): Command = relayed(Directive.AlertStop(test = false, eventId = eventId))

    private fun handle(c: Command) = e.handle(listOf(c), clocks.monoMs)

    @Test fun stoppedEventStartIsDroppedAtAnyLevel() {
        assertEquals(1, handle(start("E")).size)
        assertEquals(1, handle(stop("E")).size)
        assertNull(e.alert)
        val late = start("E", level = 2) // 정지 뒤 늦게 온 같은 사건의 2단계
        assertTrue("실행하지 않음", handle(late).isEmpty())
        assertNull(e.alert)
        assertTrue("ack 는 함", late.id in e.commands.acks())
    }

    @Test fun sameEventStageLevelRingingIsNotRestarted() {
        handle(start("E"))
        val first = e.alert!!
        clocks.monoMs += 3_000
        assertTrue("같은 사건 · 단계 · 강도 — 다시 걸지 않음", handle(start("E")).isEmpty())
        assertEquals(first, e.alert)
        assertEquals("같은 사건의 2단계는 건다", 1, handle(start("E", level = 2)).size)
        assertEquals(2, e.alert!!.payload.level)
        assertEquals("단계가 바뀌어도 건다", 1, handle(start("E", stage = FlowState.AP, level = 2)).size)
    }

    @Test fun stopWithEventIdOnlyStopsSameEvent() {
        handle(start("E1"))
        assertTrue("다른 사건의 정지는 듣지 않음", handle(stop("E2")).isEmpty())
        assertNotNull(e.alert)
        assertEquals(1, handle(stop("E1")).size)
        assertNull(e.alert)
        assertTrue("정지만 받은 사건(E2)의 시작도 버림", handle(start("E2")).isEmpty())
    }

    @Test fun commandsWithoutEventIdKeepCurrentRules() {
        assertEquals(1, handle(start(null)).size)
        assertEquals("옛 지시는 같은 단계라도 다시 건다(지금 규칙)", 1, handle(start(null)).size)
        assertEquals(1, handle(stop(null)).size)
        assertNull(e.alert)
        // 사건 id 없이 울리는 경보는 사건 id 가 있는 정지로도 끈다(비교할 id 가 없음 — 끝없이 울리지 않게)
        handle(start(null))
        assertEquals(1, handle(stop("E")).size)
        assertNull(e.alert)
        // 사건 id 없는 정지는 사건 id 로 울리는 경보도 끈다(지금 규칙)
        handle(start("F"))
        assertEquals(1, handle(stop(null)).size)
        assertNull(e.alert)
    }

    @Test fun stoppedMemoryKeepsLatest16() {
        for (i in 1..17) handle(stop("E$i"))
        assertTrue("16개 안(E2~E17)은 기억", handle(start("E2")).isEmpty())
        assertTrue(handle(start("E17")).isEmpty())
        assertEquals("가장 오래된 하나(E1)는 잊음", 1, handle(start("E1")).size)
    }

    @Test fun alertingHoldsEventStageLevel() {
        assertNull(e.alerting)
        handle(start("E", FlowState.S1, 2))
        assertEquals(WatchAlertStatus("E", FlowState.S1, 2), e.alerting)
        handle(stop("E"))
        assertNull(e.alerting)
        handle(start(null, FlowState.AP, 1))
        assertEquals(WatchAlertStatus(null, FlowState.AP, 1), e.alerting)
    }

    // ── 응답 번호(B-5) ──

    @Test fun responseSeqIncrementsAndSurvivesRestart() {
        val prefs = MemoryPrefs()
        val store = PairStore(prefs)
        assertNull(store.responseSeq)
        assertEquals(1L, store.nextResponseSeq())
        assertEquals(2L, store.nextResponseSeq())
        // 프로세스가 다시 떠도(새 PairStore) 줄지 않는다
        val again = PairStore(prefs)
        assertEquals(2L, again.responseSeq)
        assertEquals(3L, again.nextResponseSeq())
    }

    @Test fun engineCarriesLatestResponseSeq() {
        assertNull(e.responseSeq)
        e.responseSeq = 7 // 감시 시작 때 저장값을 넣는다
        var next = 7L
        handle(start("E"))
        e.respond(clocks.monoMs, clocks.wall()) { ++next }
        assertEquals(8L, e.responseSeq)
        assertNotNull(e.alert!!.respondedAtMs)
    }

    /** 경보가 울리지 않을 때의 누름(메뉴 · 실수)은 번호를 올리지 않는다 — 늦게 보인 번호가 다음 경보를 끄지 않게(be-dev 제안). */
    @Test fun pressWithoutAlertDoesNotAdvanceResponseSeq() {
        e.responseSeq = 3
        var calls = 0
        e.respond(clocks.monoMs, clocks.wall()) { calls++; 4L }
        assertEquals("번호를 만들지도 않음", 0, calls)
        assertEquals(3L, e.responseSeq)
    }

    // ── 3차 배선 — 워치 값에 싣기 · 사고 전환 ──

    /** 새 워치는 `alerting` 키를 늘 싣는다(안 울리면 null) — 키가 있어야 주인이 새 워치로 보고 시작 · 정지를 다시 낸다. */
    @Test fun watchValueAlwaysCarriesAlertingKey() {
        val idle = e.watch(clocks)
        assertTrue(idle.alertingReported)
        assertNull(idle.alerting)
        val json = idle.toJson(healthConsent = true)
        assertTrue("안 울려도 키는 있음", json.containsKey("alerting"))
        assertNull(json["alerting"])

        handle(start("E", FlowState.S1, 2))
        assertEquals(WatchAlertStatus("E", FlowState.S1, 2), e.watch(clocks).alerting)
        // 받는 쪽 해석과 맞물림(JSON 왕복)
        val back = roundTrip(e.watch(clocks))
        assertTrue(back.alertingReported)
        assertEquals(WatchAlertStatus("E", FlowState.S1, 2), back.alerting)
    }

    /** 응답 번호를 값에 싣는다 — 경보 중 누름만 올린다(71c06fc6 규칙). */
    @Test fun watchValueCarriesResponseSeq() {
        assertNull(e.watch(clocks).responseSeq)
        assertTrue(!e.watch(clocks).toJson(healthConsent = true).containsKey("responseSeq"))
        e.responseSeq = 4
        handle(start("E"))
        e.respond(clocks.monoMs, clocks.wall()) { 5L }
        assertEquals(5L, e.watch(clocks).responseSeq)
        assertEquals(5L, e.watch(clocks).toJson(healthConsent = true)["responseSeq"])
    }

    /** 사고 전환 — 옛 사건 id 의 정지가 옛 경보를 끄고, 새 사건 id 의 시작은 울린다. 순서가 뒤바뀌어 와도 새 경보는 남는다. */
    @Test fun accidentTransitionOldStopNewStart() {
        handle(start("E1", FlowState.S1, 2))
        val both = e.handle(listOf(stop("E1"), start("A1", FlowState.S5, 2)), clocks.monoMs)
        assertEquals(2, both.size)
        assertEquals(WatchAlertStatus("A1", FlowState.S5, 2), e.alerting)

        // 뒤바뀐 도착 — 새 시작이 먼저, 옛 정지가 늦게
        handle(start("E2", FlowState.S1, 2))
        handle(start("A2", FlowState.S5, 2))
        assertTrue("옛 사건의 늦은 정지는 새 경보를 끄지 않음", handle(stop("E2")).isEmpty())
        assertEquals(WatchAlertStatus("A2", FlowState.S5, 2), e.alerting)
        assertTrue("정지한 옛 사건의 시작은 버림", handle(start("E2", FlowState.S1, 2)).isEmpty())
    }

    private fun roundTrip(w: Watch) = Watch.fromJson(JsonObj(Json.parseObject(Json.encode(w.toJson(healthConsent = true)))))

    /** 생산 지시에 사건 id 가 실려 오는지 — 옛 지시(사건 id 없음)는 payload 에 키가 없다. */
    @Test fun productionCommandsCarryEventIdOnlyWhenGiven() {
        assertEquals("E", start("E").payload["eventId"])
        assertEquals("E", stop("E").payload["eventId"])
        assertTrue(!start(null).payload.containsKey("eventId"))
        assertTrue(!stop(null).payload.containsKey("eventId"))
    }

    /** 워치 값 왕복 — 응답 번호와 울리는 경보, 안 울릴 때의 null 키(새 워치)를 받는 쪽이 그대로 읽는다. 키가 없을 때(옛 워치)의 구분은 공용 WatchAlertSyncTest 몫. */
    @Test fun watchValueRoundTripsResponseSeqAndAlerting() {
        val idle = roundTrip(e.watch(clocks))
        assertTrue("null 키 — 새 워치로 읽힘", idle.alertingReported)
        assertNull(idle.alerting)
        assertNull(idle.responseSeq)

        handle(start("E", FlowState.AP, 2))
        e.respond(clocks.monoMs, clocks.wall()) { 9L }
        val ringing = roundTrip(e.watch(clocks))
        assertEquals(9L, ringing.responseSeq)
        assertEquals(WatchAlertStatus("E", FlowState.AP, 2), ringing.alerting)
        assertTrue(ringing.alertingReported)
    }

    /** 워치 재시작 — 감시를 다시 켜면(서비스 begin 과 같이) 저장된 번호를 넣어, 값에 그대로 싣고 다음 경보 중 누름은 이어서 오른다. */
    @Test fun responseSeqContinuesAcrossWatchRestart() {
        val prefs = MemoryPrefs()
        val first = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        val store1 = PairStore(prefs)
        first.handle(listOf(start("E1")), clocks.monoMs)
        first.respond(clocks.monoMs, clocks.wall(), store1::nextResponseSeq)
        first.respond(clocks.monoMs, clocks.wall(), store1::nextResponseSeq)
        assertEquals(2L, roundTrip(first.watch(clocks)).responseSeq)

        // 프로세스가 죽고 다시 뜸 — 새 저장소 · 새 엔진, 저장값을 넣는다(MonitorService.begin)
        val store2 = PairStore(prefs)
        val second = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        second.responseSeq = store2.responseSeq
        assertEquals("누르기 전에도 줄지 않은 번호를 싣는다", 2L, roundTrip(second.watch(clocks)).responseSeq)
        second.respond(clocks.monoMs, clocks.wall(), store2::nextResponseSeq)
        assertEquals("경보 밖 누름은 그대로", 2L, second.responseSeq)
        second.handle(listOf(start("E2")), clocks.monoMs)
        second.respond(clocks.monoMs, clocks.wall(), store2::nextResponseSeq)
        assertEquals(3L, roundTrip(second.watch(clocks)).responseSeq)
    }
}
