// 엔진 시험 — 시나리오 전환(SIMULATION · test) · 끝나면 실제로 복귀, 시나리오 중 실제 샘플 무시, 관문 모드 DRIVING 고정
package com.example.guardian.watch

import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.HrStatus
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.Simulator
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.watch.core.GateRunner
import com.example.guardian.watch.core.WatchAction
import com.example.guardian.watch.core.WatchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEngineTest {
    private val clocks = FakeClocks()

    private fun scenarioCommand(s: Scenario, durationMs: Long, id: String = "t-1") =
        Command(id, CommandType.TEST_SCENARIO, Payload.TestScenario(s, durationMs).toJson(), 30_000, true, Party.PHONE, CommandTo.WATCH)

    /** 1초마다 tick 하며 판정을 모은다. */
    private fun run(e: WatchEngine, seconds: Int): List<WristVerdict> = (1..seconds).map {
        clocks.monoMs += 1_000
        e.tick(clocks.monoMs).verdict
    }

    @Test fun testScenarioSwitchesToSimulationAndReturns() {
        val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        assertEquals(WatchSource.HEALTH_SERVICES, e.watch(clocks).source)
        assertFalse(e.watch(clocks).test)

        val actions = e.handle(listOf(scenarioCommand(Scenario.PULSE_LOST, 60_000)), clocks.monoMs)
        assertEquals(listOf(WatchAction.RunScenario(Scenario.PULSE_LOST, 60_000)), actions)
        assertEquals(Scenario.PULSE_LOST, e.scenario)
        assertEquals(WatchSource.SIMULATION, e.watch(clocks).source)
        assertTrue("시뮬레이션 값은 test 표시", e.watch(clocks).test)

        // 실제와 같은 관문을 지나 맥 신호 소실로 간다(사건 20초 + 15초 안)
        val verdicts = run(e, 59)
        assertTrue("소실 판정에 닿음 $verdicts", WristVerdict.PULSE_ABSENT in verdicts)
        assertTrue("소실 전에 약해짐", verdicts.indexOf(WristVerdict.PULSE_WEAK) in 0 until verdicts.indexOf(WristVerdict.PULSE_ABSENT))
        assertTrue(verdicts.indexOf(WristVerdict.PULSE_ABSENT) <= 35)
        assertEquals(WatchSource.SIMULATION, e.watch(clocks).source)

        // durationMs 가 지나면 실제 출처로 돌아가고 새 관문은 기준선부터(정상) 시작한다
        run(e, 2)
        assertNull(e.scenario)
        val w = e.watch(clocks)
        assertEquals(WatchSource.HEALTH_SERVICES, w.source)
        assertFalse(w.test)
        assertEquals(WristVerdict.NORMAL, w.verdict)
    }

    @Test fun realSamplesAreIgnoredDuringScenario() {
        val e = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false)
        e.startScenario(Scenario.NORMAL, 30_000, clocks.monoMs)
        // 시나리오 중 실제 센서가 「미착용」을 보내도 관문에 섞이지 않는다
        repeat(20) {
            clocks.monoMs += 1_000
            e.hr(HrSample(clocks.monoMs, 0, HrStatus.OFF_WRIST))
            assertEquals(WristVerdict.NORMAL, e.tick(clocks.monoMs).verdict)
        }
    }

    @Test fun realSamplesUsedAfterScenarioEnds() {
        val e = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false)
        e.startScenario(Scenario.NORMAL, 5_000, clocks.monoMs)
        run(e, 6)
        assertNull(e.scenario)
        clocks.monoMs += 1_000
        e.hr(HrSample(clocks.monoMs, 0, HrStatus.OFF_WRIST))
        val r = e.tick(clocks.monoMs)
        assertEquals(WristVerdict.CHECK_WEAR, r.verdict)
        assertFalse(e.watch(clocks).worn)
    }

    @Test fun stopButtonEndsScenarioAndReturnsToRealSource() {
        val e = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false)
        e.handle(listOf(scenarioCommand(Scenario.PULSE_LOST, 60_000)), clocks.monoMs)
        assertEquals(clocks.monoMs + 60_000, e.scenarioEndsAtMs)
        run(e, 10)
        e.stopScenario(clocks.monoMs)
        assertNull(e.scenario)
        assertNull(e.scenarioEndsAtMs)
        assertEquals(WatchSource.SENSOR_SDK, e.watch(clocks).source)
        assertFalse(e.watch(clocks).test)
    }

    @Test fun durationIsClampedSoTestCannotRunForever() {
        val e = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false)
        e.handle(listOf(scenarioCommand(Scenario.NORMAL, Long.MAX_VALUE)), clocks.monoMs)
        run(e, 601)
        assertNull(e.scenario)
    }

    @Test fun gateModeIsFixedToDriving() {
        assertTrue(GateRunner.GATE_DRIVING)
        // 차 진동(가속도 크기 잡음 0.15 m/s²)은 θ_park(0.05)보다 크고 θ_drive(0.35)보다 작다.
        // 주행 기준이라야 A2 손목 정지가 잡혀 PULSE_ABSENT 까지 간다 — 정차 기준이면 PULSE_WEAK 에 머문다
        val gate = GateRunner()
        val trace = Simulator(42).generate(Scenario.PULSE_LOST, eventAtMs = 45_000, carVibrationStd = 0.15)
        var t = 1_000L
        var last = WristVerdict.NORMAL
        var ip = 0; var ia = 0; var ih = 0
        while (t <= 90_000) {
            while (ip < trace.ppg.size && trace.ppg[ip].atMs <= t) gate.ppg(trace.ppg[ip++])
            while (ia < trace.accel.size && trace.accel[ia].atMs <= t) gate.accel(trace.accel[ia++])
            while (ih < trace.hr.size && trace.hr[ih].atMs <= t) gate.hr(trace.hr[ih++])
            last = gate.evaluate(t).verdict
            t += 1_000
        }
        assertEquals(WristVerdict.PULSE_ABSENT, last)
    }

    // ── 신호 없음(코드 리뷰 2026-11-02 상 1) ──

    /** 서비스가 넣은 신호 없음은 보낼 값에 실리고, 판정 · 응답은 그대로 간다. 시나리오 중에는 싣지 않고, 출처를 바꾸면 풀린다. */
    @Test fun noSignalRidesOnWatchValueButNotDuringScenario() {
        val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        assertFalse(e.watch(clocks).noSignal)
        e.noSignal = true
        run(e, 1)
        val w = e.watch(clocks)
        assertTrue(w.noSignal)
        assertEquals("판정 값은 그대로(받는 쪽이 쓰지 않는다)", WristVerdict.NORMAL, w.verdict)
        assertEquals(WatchSource.HEALTH_SERVICES, w.source)
        // 응답은 신호 없음이어도 실린다
        e.respond(clocks.monoMs, clocks.wall())
        assertEquals(clocks.wall(), e.watch(clocks).responseAt)
        assertTrue(e.watch(clocks).noSignal)
        // 시뮬레이션 값은 실제 출처 상태와 무관하다
        e.startScenario(Scenario.NORMAL, 5_000, clocks.monoMs)
        assertFalse(e.watch(clocks).noSignal)
        run(e, 6)
        assertNull(e.scenario)
        assertTrue("시나리오가 끝나면 다시 실제 출처 상태", e.watch(clocks).noSignal)
        // 서비스가 풀면 바로 false
        e.noSignal = false
        assertFalse(e.watch(clocks).noSignal)
        // 출처 전환(Sensor SDK → Health Services)은 새 출처 기준이라 풀린다
        e.noSignal = true
        e.useRealSource(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        assertFalse(e.watch(clocks).noSignal)
    }

    @Test fun noSignalIsOnTheWireOnlyWhenTrue() {
        val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        val plain = Json.encode(e.watch(clocks).toJson(healthConsent = true))
        assertFalse(plain.contains("noSignal"))
        e.noSignal = true
        val wire = Json.encode(e.watch(clocks).toJson(healthConsent = true))
        assertEquals(true, Json.parseObject(wire)["noSignal"])
        assertEquals(e.watch(clocks), Watch.fromJson(JsonObj(Json.parseObject(wire))))
    }

    @Test fun responseIsReportedAsWallClockAndKeepsAlert() {
        val e = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false)
        val start = Command(
            "a-1", CommandType.ALERT_START,
            Payload.AlertStart(com.example.guardian.shared.flow.FlowState.S1, 1, true, false, false, 15_000).toJson(),
            30_000, false, Party.CAR, CommandTo.WATCH,
        )
        e.handle(listOf(start), clocks.monoMs)
        clocks.monoMs += 3_000
        e.respond(clocks.monoMs, clocks.wall())
        assertEquals(clocks.wall(), e.watch(clocks).responseAt)
        // 응답해도 ALERT_STOP 전까지 경보는 남는다(계약 §4.6)
        assertEquals(clocks.monoMs, e.alert?.respondedAtMs)
    }
}
