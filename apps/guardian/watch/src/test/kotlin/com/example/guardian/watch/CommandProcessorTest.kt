// 지시 처리 시험 — 경보 시작 · 끝, 테스트 시나리오 멈춤, 같은 id 는 한 번만 실행하되 다시 ack, 틀린 payload · 워치 몫이 아닌 지시는 ack 만
package com.example.guardian.watch

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.AlertStopReason
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.watch.core.CommandProcessor
import com.example.guardian.watch.core.WatchAction
import com.example.guardian.watch.core.WatchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandProcessorTest {
    private val level1 = Payload.AlertStart(FlowState.S1, 1, vibrate = true, sound = false, maxVolume = false, durationMs = 15_000)
    private val level2 = Payload.AlertStart(FlowState.S1, 2, vibrate = true, sound = true, maxVolume = true, durationMs = 20_000)

    /** 보낸 쪽 기본값은 그 지시를 정상으로 보내는 쪽이다 — 테스트 시나리오는 휴대폰, 그 밖은 차(D-1). */
    private fun cmd(
        id: String, p: Payload, to: CommandTo? = CommandTo.WATCH, test: Boolean = false,
        from: Party = if (p is Payload.TestScenario || p is Payload.TestScenarioStop) Party.PHONE else Party.CAR,
    ) = Command(id, p.type, p.toJson(), 30_000, test, from, to)

    @Test fun alertStartAndStop() {
        val c = CommandProcessor()
        val a = c.accept(listOf(cmd("1", level1), cmd("2", level2)))
        assertEquals(listOf(WatchAction.StartAlert("1", level1, false), WatchAction.StartAlert("2", level2, false)), a)
        val s = c.accept(listOf(cmd("3", Payload.AlertStop(AlertStopReason.CLEARED), CommandTo.ALL)))
        assertEquals(listOf(WatchAction.StopAlert(AlertStopReason.CLEARED)), s)
        assertEquals(listOf("1", "2", "3"), c.acks())
    }

    @Test fun duplicateIdRunsOnceButIsAckedAgain() {
        val c = CommandProcessor()
        assertEquals(1, c.accept(listOf(cmd("x", level1))).size)
        c.confirm(c.acks())
        assertTrue(c.acks().isEmpty())
        // ack 가 중계에 닿기 전에 같은 지시가 다시 옴 — 실행하지 않고 ack 만 다시 싣는다
        assertTrue(c.accept(listOf(cmd("x", level1))).isEmpty())
        assertEquals(listOf("x"), c.acks())
    }

    @Test fun ackIsKeptUntilConfirmed() {
        val c = CommandProcessor()
        c.accept(listOf(cmd("a", level1)))
        val sent = c.acks()
        c.accept(listOf(cmd("b", level1))) // 보내는 사이에 새 지시가 옴
        c.confirm(sent)
        assertEquals(listOf("b"), c.acks())
    }

    @Test fun badPayloadAndOtherTypesAreAckedNotRun() {
        val c = CommandProcessor()
        val bad = Command("bad", CommandType.ALERT_START, mapOf("stage" to "S1"), 30_000, false, Party.CAR, CommandTo.WATCH)
        val speak = cmd("speak", Payload.Speak("안내"), CommandTo.CAR)
        val show = cmd("show", Payload.Show("한 줄", null))
        assertTrue(c.accept(listOf(bad, speak, show)).isEmpty())
        assertEquals(listOf("bad", "speak", "show"), c.acks())
    }

    @Test fun engineTracksAlertUntilStop() {
        val clocks = FakeClocks()
        val e = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false)
        e.handle(listOf(cmd("1", level1, test = true)), clocks.monoMs)
        assertNotNull(e.alert)
        assertTrue(e.alert!!.test)
        // durationMs(15초)가 지나도 스스로 끄지 않는다
        repeat(30) { clocks.monoMs += 1_000; e.tick(clocks.monoMs) }
        assertNotNull(e.alert)
        e.handle(listOf(cmd("2", Payload.AlertStop(AlertStopReason.CLEARED), CommandTo.ALL)), clocks.monoMs)
        assertNull(e.alert)
    }

    // ── TEST_SCENARIO_STOP(계약 §4.6) ──

    private val scenario = Payload.TestScenario(Scenario.LOOSE, 60_000)

    @Test fun stopDuringScenarioReturnsToRealSource() {
        val clocks = FakeClocks()
        val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        e.handle(listOf(cmd("run", scenario, test = true)), clocks.monoMs)
        repeat(5) { clocks.monoMs += 1_000; e.tick(clocks.monoMs) }
        assertEquals(WatchSource.SIMULATION, e.watch(clocks).source)
        assertTrue(e.watch(clocks).test)

        val a = e.handle(listOf(cmd("stop", Payload.TestScenarioStop, test = true)), clocks.monoMs)
        assertEquals(listOf(WatchAction.StopScenario), a)
        assertNull(e.scenario)
        val w = e.watch(clocks)
        assertEquals(WatchSource.HEALTH_SERVICES, w.source)
        assertFalse(w.test)
        assertEquals(listOf("run", "stop"), e.commands.acks())
        // 다음 tick 에도 실제 출처 그대로(시뮬레이션 샘플이 다시 들어오지 않는다)
        clocks.monoMs += 1_000; e.tick(clocks.monoMs)
        assertEquals(WatchSource.HEALTH_SERVICES, e.watch(clocks).source)
        assertFalse(e.watch(clocks).test)
    }

    @Test fun stopWithoutScenarioOnlyAcks() {
        val clocks = FakeClocks()
        val e = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false)
        e.tick(clocks.monoMs)
        val before = e.watch(clocks)
        e.handle(listOf(cmd("stop", Payload.TestScenarioStop, test = true)), clocks.monoMs)
        assertNull(e.scenario)
        assertNull(e.alert)
        assertEquals(before, e.watch(clocks))
        assertEquals(listOf("stop"), e.commands.acks())
    }

    @Test fun sameStopIdRunsOnce() {
        val clocks = FakeClocks()
        val e = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false)
        e.handle(listOf(cmd("run-1", scenario, test = true)), clocks.monoMs)
        e.handle(listOf(cmd("stop", Payload.TestScenarioStop, test = true)), clocks.monoMs)
        assertNull(e.scenario)
        e.commands.confirm(e.commands.acks())
        // 새 시나리오가 도는 중에 같은 멈춤 지시가 다시 와도(ack 가 닿기 전 재전송) 새 시나리오를 끄지 않는다
        e.handle(listOf(cmd("run-2", scenario, test = true)), clocks.monoMs)
        assertTrue(e.handle(listOf(cmd("stop", Payload.TestScenarioStop, test = true)), clocks.monoMs).isEmpty())
        assertEquals(Scenario.LOOSE, e.scenario)
        assertEquals(WatchSource.SIMULATION, e.watch(clocks).source)
        assertEquals(listOf("run-2", "stop"), e.commands.acks())
    }

    @Test fun skippedIdsAreAckedNotRun() {
        val c = CommandProcessor()
        c.ackOnly(listOf("future-1"))
        assertEquals(listOf("future-1"), c.acks())
        c.confirm(listOf("future-1"))
        assertTrue(c.acks().isEmpty())
    }

    // ── 보낸 쪽 검사(합동 점검 D-1) ──

    /** 경보 시작 · 정지는 차 · 휴대폰에서 온 것만 실행한다. 워치가 보낸 것(중계를 거쳐 되돌아온 것 포함)은 ack 만. */
    @Test fun alertCommandsRunOnlyFromCarOrPhone() {
        val c = CommandProcessor()
        val a = c.accept(
            listOf(
                cmd("car", level1, from = Party.CAR),
                cmd("phone", level2, from = Party.PHONE),
                cmd("watch-start", level1, from = Party.WATCH),
                cmd("watch-stop", Payload.AlertStop(AlertStopReason.CLEARED), CommandTo.ALL, from = Party.WATCH),
            ),
        )
        assertEquals(listOf(WatchAction.StartAlert("car", level1, false), WatchAction.StartAlert("phone", level2, false)), a)
        assertEquals(listOf("car", "phone", "watch-start", "watch-stop"), c.acks())
        assertEquals(
            listOf(WatchAction.StopAlert(AlertStopReason.CLEARED)),
            c.accept(listOf(cmd("phone-stop", Payload.AlertStop(AlertStopReason.CLEARED), CommandTo.ALL, from = Party.PHONE))),
        )
    }

    /** 테스트 시나리오 · 시나리오 멈춤은 휴대폰에서 온 것만 실행한다. 차 · 워치가 보낸 것은 ack 만. */
    @Test fun scenarioCommandsRunOnlyFromPhone() {
        val c = CommandProcessor()
        val ignored = listOf(
            cmd("car-run", scenario, test = true, from = Party.CAR),
            cmd("watch-run", scenario, test = true, from = Party.WATCH),
            cmd("car-stop", Payload.TestScenarioStop, test = true, from = Party.CAR),
            cmd("watch-stop", Payload.TestScenarioStop, test = true, from = Party.WATCH),
        )
        assertTrue(c.accept(ignored).isEmpty())
        assertEquals(listOf("car-run", "watch-run", "car-stop", "watch-stop"), c.acks())
        val run = c.accept(listOf(cmd("phone-run", scenario, test = true), cmd("phone-stop", Payload.TestScenarioStop, test = true)))
        assertEquals(listOf(WatchAction.RunScenario(Scenario.LOOSE, 60_000), WatchAction.StopScenario), run)
    }

    /** 차가 보낸 시나리오는 엔진에 닿아도 시뮬레이션을 켜지 않는다(실제 판정이 테스트로 바뀌지 않음). */
    @Test fun scenarioFromCarDoesNotStartSimulation() {
        val clocks = FakeClocks()
        val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        assertTrue(e.handle(listOf(cmd("car-run", scenario, test = true, from = Party.CAR)), clocks.monoMs).isEmpty())
        assertNull(e.scenario)
        assertFalse(e.watch(clocks).test)
        assertEquals(listOf("car-run"), e.commands.acks())
    }
}
