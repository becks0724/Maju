// 워치 테스트 시나리오 응답 시험 — 어느 기기에서 응답하든 ALERT_STOP 뒤 시뮬레이션이 정상 신호로 바뀌어 재경보가 없고, 120초에 끝난다
package com.example.guardian.watch

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.ResponseSource
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.protocol.AlertStopReason
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.report.Consents
import com.example.guardian.watch.core.WatchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioResponseTest {
    private val clocks = FakeClocks()
    private var ids = 0

    private fun command(type: CommandType, payload: Payload) =
        Command("c-${++ids}", type, payload.toJson(), 30_000, true, Party.PHONE, CommandTo.ALL)

    /** 흐름 기계가 낸 경보 지시를 중계 지시로 바꾼다(휴대폰 · 차가 하는 일과 같은 모양). */
    private fun toCommands(ds: List<Directive>): List<Command> = ds.mapNotNull { d ->
        when (d) {
            is Directive.AlertStart -> command(
                CommandType.ALERT_START,
                Payload.AlertStart(d.stage, d.level, d.level == 1, d.level == 2, d.level == 2, d.durationMs),
            )
            is Directive.AlertStop -> command(CommandType.ALERT_STOP, Payload.AlertStop(AlertStopReason.CLEARED))
            else -> null
        }
    }

    private class Run(val alertStarts: List<Long>, val respondedAt: Long?, val scenarioEndedAt: Long?, val finalState: FlowState)

    /**
     * WATCH 세션 120초(PULSE_LOST)를 주행 중으로 돌린다. 워치 판정은 매 초 흐름 기계에 들어가고, 흐름의 경보 지시는
     * 2초마다(sync 주기) 워치에 닿는다. 첫 경보 3초 뒤 [respond] 로 응답한다.
     */
    private fun run(respond: (WatchEngine, Long) -> Unit): Run {
        val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        val flow = FlowMachine()
        val t0 = clocks.monoMs
        flow.handle(FlowInput.Power(true, t0))
        flow.handle(FlowInput.ConsentsChanged(Consents(emergencyInfo = true, health = true, location = true), t0))
        flow.handle(FlowInput.Vehicle(VehicleState(speedKmh = 60.0, gear = Gear.D), t0))
        flow.handle(FlowInput.Wrist(WristVerdict.NORMAL, t0))
        flow.handle(FlowInput.TestMode(true, t0))
        assertEquals(FlowState.S0, flow.state)
        e.handle(listOf(command(CommandType.TEST_SCENARIO, Payload.TestScenario(Scenario.PULSE_LOST, 120_000))), t0)

        val queue = ArrayList<Command>()
        val starts = ArrayList<Long>()
        var respondedAt: Long? = null
        var endedAt: Long? = null
        for (sec in 1..150) {
            clocks.monoMs += 1_000
            val now = clocks.monoMs
            val v = e.tick(now).verdict
            if (endedAt == null && e.scenario == null) endedAt = now - t0
            val ds = ArrayList<Directive>()
            ds += flow.handle(FlowInput.Wrist(v, now))
            val first = starts.firstOrNull()
            if (respondedAt == null && first != null && now - t0 - first >= 3_000) {
                respondedAt = now - t0
                respond(e, now)
                ds += flow.handle(FlowInput.Response(ResponseSource.CAR_BUTTON, now))
            }
            ds.filterIsInstance<Directive.AlertStart>().filter { it.level == 1 }.forEach { starts += now - t0 }
            queue += toCommands(ds)
            if (sec % 2 == 0) { e.handle(queue.toList(), now); queue.clear() }
        }
        return Run(starts, respondedAt, endedAt, flow.state)
    }

    private fun assertNoRealert(r: Run) {
        assertTrue("첫 경보가 났다", r.alertStarts.isNotEmpty())
        assertNotNull("응답했다", r.respondedAt)
        assertEquals("응답 뒤 재경보가 없다 — 경보 1 시각 ${r.alertStarts}, 응답 ${r.respondedAt}", 1, r.alertStarts.size)
        assertEquals("시나리오는 120초에 끝난다", 120_000L, r.scenarioEndedAt)
    }

    /** 차 화면 「괜찮아요」 — 워치 자기 버튼이 아니어도 흐름의 ALERT_STOP 으로 안다. */
    @Test fun responseFromOtherDeviceStopsRealert() {
        val r = run { _, _ -> }
        assertNoRealert(r)
        assertEquals(FlowState.S1R, r.finalState)
    }

    /** 워치 버튼 응답도 같은 길(흐름 → ALERT_STOP)로 정상 신호가 된다. */
    @Test fun watchButtonResponseStopsRealert() {
        val r = run { e, now -> e.respond(now, clocks.wall()) }
        assertNoRealert(r)
    }

    /** 응답 뒤에도 시나리오 표시 · 끝 시각 · 테스트 표시는 그대로다(휴대폰 세션 120초와 맞춤). 판정은 곧 정상이다. */
    @Test fun calmedScenarioKeepsLabelEndAndTestFlag() {
        val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        e.handle(listOf(command(CommandType.TEST_SCENARIO, Payload.TestScenario(Scenario.PULSE_LOST, 120_000))), clocks.monoMs)
        val ends = e.scenarioEndsAtMs
        repeat(40) { clocks.monoMs += 1_000; e.tick(clocks.monoMs) }
        assertTrue("사건 뒤 나쁜 판정", e.reading.verdict != WristVerdict.NORMAL)
        e.handle(listOf(command(CommandType.ALERT_START, Payload.AlertStart(FlowState.S1, 1, true, false, false, 15_000))), clocks.monoMs)
        clocks.monoMs += 2_000
        e.handle(listOf(command(CommandType.ALERT_STOP, Payload.AlertStop(AlertStopReason.CLEARED))), clocks.monoMs)
        clocks.monoMs += 1_000
        assertEquals(WristVerdict.NORMAL, e.tick(clocks.monoMs).verdict)
        assertEquals(Scenario.PULSE_LOST, e.scenario)
        assertEquals(ends, e.scenarioEndsAtMs)
        assertEquals(WatchSource.SIMULATION, e.watch(clocks).source)
        assertTrue(e.watch(clocks).test)
    }

    /** 이 시나리오에서 울린 경보가 없는데 온 ALERT_STOP(앞 사건이 늦게 닿음)은 시나리오를 바꾸지 않는다. */
    @Test fun alertStopWithoutScenarioAlertKeepsScenario() {
        val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        e.handle(listOf(command(CommandType.TEST_SCENARIO, Payload.TestScenario(Scenario.PULSE_LOST, 120_000))), clocks.monoMs)
        e.handle(listOf(command(CommandType.ALERT_STOP, Payload.AlertStop(AlertStopReason.CLEARED))), clocks.monoMs)
        val verdicts = (1..40).map { clocks.monoMs += 1_000; e.tick(clocks.monoMs).verdict }
        assertTrue("시나리오대로 나빠진다 $verdicts", WristVerdict.PULSE_ABSENT in verdicts)
        assertNull(e.alert)
    }
}
