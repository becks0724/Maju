// 차 화면 테스트 입구 통합 시험 — 하네스가 차 대신 TEST_REQUEST 를 보내면 휴대폰이 테스트를 시작 · 멈추고, 차가 flow.test 변화로 15초 안에 안다(계약 §4.6, 2026-11-01)
package com.example.guardian.integration

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.protocol.CarSyncRequest
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.phone.core.TestMode
import com.example.guardian.watch.core.WatchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarTestRequestTest : Stage() {

    /**
     * 차 앱 화면은 아직 없어서(app-android 다음 단계) 하네스가 차 토큰으로 지시만 흉내 낸다. seq 0 이라 차의 최신 상태는 덮지 않는다.
     * 같은 응답에 실린 차 몫 지시는 ack 하지 않으므로 차 루프가 다음 sync 에 그대로 받는다.
     */
    private fun carSends(t: Trio, id: String, p: Payload.TestRequest) {
        val c = Command(id, CommandType.TEST_REQUEST, p.toJson(), 30_000, true, Party.CAR, CommandTo.PHONE)
        val req = CarSyncRequest(t.car.token!!, 0, t.car.clock.wall(), Vehicle(speedKmh = 0.0, gear = Gear.P), null, listOf(c))
        assertEquals(200, world.relay.post("/v1/car/sync", Json.encode(req.toJson())).status)
    }

    /** 차가 마지막으로 받은 휴대폰 peer 의 carDisplay(중계 응답 원문). */
    private fun lastCarDisplay(): Any? {
        val last = world.relay.wire.last { it.path == "/v1/car/sync" && it.status == 200 }
        return JsonObj(Json.parseObject(last.response)).objOrNull("peer")?.map?.get("carDisplay")
    }

    @Test
    fun carTestRequestStartsAndStopsPhoneTest() {
        val t = setup(car = true, watchDirect = true)
        armCar(t)
        world.advance(4_000) // 워치 직접 경로가 휴대폰 응답에 보일 때까지

        // 휴대폰 설정이 차 peer 까지 간다 — 기본은 표시(hideHr false)
        assertEquals(mapOf("hideHr" to false), lastCarDisplay())

        carSends(t, "car-tr-1", Payload.TestRequest.START)
        val took = world.runUntil(15_000, "차가 flow.test 로 테스트 시작을 안다") { t.car.hub.snapshot.test }
        assertTrue("15초 안(${took}ms)", took <= 15_000)
        assertEquals("워치 직접 경로 — 워치 시나리오", TestMode.WATCH, t.phone.engine.test?.mode)
        world.runUntil(10_000, "워치가 시나리오를 받는다") { t.watch.actions.any { it.second is WatchAction.RunScenario } }
        assertEquals(0, world.relay.queued("PHONE"))

        carSends(t, "car-tr-2", Payload.TestRequest.STOP)
        world.runUntil(15_000, "차가 flow.test 로 테스트 멈춤을 안다") { !t.car.hub.snapshot.test }
        assertEquals(null, t.phone.engine.test)
        world.runUntil(10_000, "워치 시나리오 멈춤") { t.watch.actions.any { it.second == WatchAction.StopScenario } }
    }

    /**
     * 워치 직접 경로가 없어 차의 「테스트 시작」이 휴대폰 단독(휴대폰 안) 시험으로 떨어진 경우(2026-11-07) — 휴대폰은 `flow.localTest` 만 참,
     * `flow.test` 는 거짓. 차는 15초가 지나도 「휴대폰이 답하지 않습니다」를 보이지 않고 「휴대폰에서 테스트 중 · 차 화면은 실제 상태입니다」를 두며,
     * 차 흐름은 실제라 그동안 실제 나쁜 값이면 실제 경보가 열린다. 휴대폰 세션이 끝나면 안내가 사라진다.
     */
    @Test
    fun carStartFallingToPhoneLocalTestShowsNoticeNotNoAnswer() {
        // 워치는 휴대폰 Data Layer 로만(직접 경로 없음) — 휴대폰은 워치에 지시할 수 없어 휴대폰 단독 시험으로 떨어진다
        val t = setup(car = true, watchDirect = false, dataLayer = true)
        armCar(t)
        world.advance(4_000)
        // 차 화면 코드(CarHubScreen)는 이 모듈이 묶지 않아 허브 요청 · 허브 사실로 본다. 입구 문구는 차 단위 시험(CarHubDisplayTest)이 본다
        t.car.hub.sendTestRequest(com.example.guardian.shared.protocol.TestAction.START, t.car.clock.mono())
        world.runUntil(15_000, "휴대폰 단독 시험 시작") { t.phone.engine.test?.mode == TestMode.LOCAL }
        val took = world.runUntil(15_000, "차가 flow.localTest 로 안다") { t.car.hub.phoneLocalTest }
        assertTrue("15초 안(${took}ms)", took <= 15_000)
        assertNull("요청이 풀린다 — 「휴대폰이 답하지 않습니다」가 뜰 요청이 없다", t.car.hub.testPending)
        assertFalse("차 흐름은 실제", t.car.hub.snapshot.test)
        world.advance(20_000)
        assertNull("20초 뒤에도 풀린 채", t.car.hub.testPending)
        assertTrue(t.car.hub.phoneLocalTest)
        assertFalse(t.car.hub.snapshot.test)
        // 그동안 실제 나쁜 값 → 차의 실제 경보(테스트 표시 없음). 휴대폰 안 시험은 실제 사건이 보이면 끝난다
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
        world.runUntil(60_000, "실제 경보") { t.car.state() in alarmStates }
        assertFalse("실제 사건", t.car.hub.snapshot.test)
        world.runUntil(10_000, "휴대폰 단독 시험 종료 · 안내 사라짐") { t.phone.engine.test == null && !t.car.hub.phoneLocalTest }
    }

    private val alarmStates = setOf(FlowState.S1, FlowState.AP, FlowState.S3, FlowState.S4)
    private fun drive(t: Trio) { t.car.reading = CarDevice.driving(); t.phone.gpsKmh = 60.0 }
    private fun park(t: Trio) { t.car.reading = CarDevice.parked(); t.phone.gpsKmh = 0.3 }

    /**
     * 실주행 3 시험 A(2026-11-02) 흐름 — 차에서 테스트 시작 → 주행 중 경보 → 워치 응답 → 휴식 → 정차 → 응답 뒤 정차 관찰(S2)에서
     * 차 「테스트 멈추기」로 세션 종료 → S2 에서도 표시가 바로 꺼지고 S0 → 진짜 나쁜 신호 → 실제 경보가 정차 관찰 · 재경보 간격에 밀리지 않는다
     * (실주행 3 새 결함, 사용자 지시 2026-11-07).
     * 테스트에서 사람이 응답하면 워치 시뮬레이션이 남은 시간 정상 신호가 되어(PM 결정 2026-11-08) 세션 안 재경보는 나지 않는다 —
     * 시험 A 의 「세션 안 재경보 → 재응답」 단계 대신 응답 뒤 정상 판정 · 경보 없음을 본다.
     */
    @Test
    fun trialA_sessionEndInRestClearsMarkAndRealAlertIsNotDelayed() {
        val t = setup(car = true, watchDirect = true)
        armCar(t)
        world.advance(4_000)
        carSends(t, "car-tr-a1", Payload.TestRequest.START)
        world.runUntil(15_000, "차가 테스트 시작을 안다") { t.car.hub.snapshot.test }
        val startedAt = world.elapsed
        drive(t)
        world.runUntil(60_000, "주행 중 테스트 경보 S1") { t.car.state() == FlowState.S1 }
        assertTrue(t.car.hub.snapshot.test)
        val testEvent = t.car.hub.snapshot.facts.eventId
        // 시험 A 처럼 맥박을 잃은 판정(PULSE_ABSENT)에서 응답한다
        world.runUntil(40_000, "맥박 신호 잃음 판정") { t.car.hub.snapshot.verdict == WristVerdict.PULSE_ABSENT }
        assertTrue(t.car.state() in alarmStates)
        t.watch.pressOk()
        world.runUntil(6_000, "휴식 S1R") { t.car.state() == FlowState.S1R }
        world.runUntil(10_000, "응답 뒤 시뮬레이션 정상 판정") { t.car.hub.snapshot.verdict == WristVerdict.NORMAL }
        park(t)
        world.runUntil(10_000, "정차 관찰 S2") { t.car.state() == FlowState.S2 }
        // 재경보 간격(30초)이 지나도 세션 안 재경보가 없다
        repeat(35) {
            world.advance(1_000)
            assertEquals("응답 뒤 재경보 없음(${world.elapsed / 1000}s)", FlowState.S2, t.car.state())
        }
        assertTrue(t.car.hub.snapshot.test && t.car.hub.screen().test)
        assertTrue("시나리오 120초 전 — 멈춤 요청으로 끝내는 길을 본다", world.elapsed - startedAt < 120_000)

        carSends(t, "car-tr-a2", Payload.TestRequest.STOP)
        world.runUntil(15_000, "세션 종료 뒤 차 표시 꺼짐") { !t.car.hub.snapshot.test }
        assertNull(t.phone.engine.test)
        assertEquals("S2 에서 S0 으로 깨끗이", FlowState.S0, t.car.state())
        assertFalse(t.car.hub.screen().test)
        world.runUntil(4_000, "휴대폰 화면의 테스트 표시도 다음 sync 에 꺼짐") { !t.phone.status().flowTest }
        world.runUntil(10_000, "워치가 실제 센서로") { t.watch.engine.scenario == null }
        world.advance(4_000)
        assertEquals(FlowState.S0, t.car.state())

        // 진짜 나쁜 신호 — 테스트의 정차 관찰(10분) · 재경보 간격(30초)에 밀리지 않고 실제 경보
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 5_000)
        val realTook = world.runUntil(60_000, "실제 경보") { t.car.state() in alarmStates }
        assertFalse("실제 사건", t.car.hub.snapshot.test)
        assertNotEquals(testEvent, t.car.hub.snapshot.facts.eventId)
        assertTrue("재경보 간격(30초)보다 빨리 — ${realTook}ms", realTook < 30_000)
    }

    /**
     * 멈춤 요청 없이 두면 — 시나리오 120초가 지났을 때 흐름이 경보 중이 아니면(여기서는 응답 뒤 정차 관찰 S2) 휴대폰 세션이 바로 끝나고
     * 차 띠 · 「테스트 멈추기」가 몇 초 안에 사라진다(7분 안전장치를 기다리지 않음, PM 결정 2026-11-07). 120초 안의 시험 A 흐름은 그대로 테스트다.
     * 응답 뒤에는 워치 시뮬레이션이 정상 신호라(PM 결정 2026-11-08) 첫 응답부터 세션이 끝날 때까지 재경보 없이 S2 에 머문다.
     */
    @Test
    fun scenarioEndAt120sEndsSessionAndClearsCarMarkWithoutStopRequest() {
        val t = setup(car = true, watchDirect = true)
        armCar(t)
        world.advance(4_000)
        carSends(t, "car-tr-e1", Payload.TestRequest.START)
        world.runUntil(15_000, "차가 테스트 시작을 안다") { t.car.hub.snapshot.test }
        val startedAt = world.elapsed
        drive(t)
        world.runUntil(60_000, "주행 중 테스트 경보 S1") { t.car.state() == FlowState.S1 }
        world.runUntil(40_000, "맥박 신호 잃음 판정") { t.car.hub.snapshot.verdict == WristVerdict.PULSE_ABSENT }
        t.watch.pressOk()
        world.runUntil(6_000, "휴식 S1R") { t.car.state() == FlowState.S1R }
        park(t)
        world.runUntil(10_000, "정차 관찰 S2") { t.car.state() == FlowState.S2 }
        assertTrue("120초 안에는 세션 · 표시 유지", world.elapsed - startedAt < 120_000 && t.phone.engine.test != null)

        // 120초까지 두면 — 재경보 없이(S2 그대로) 멈춤 요청 없이 세션이 끝나고 차 표시가 꺼진다
        world.runUntil(140_000, "차 표시 꺼짐") {
            assertFalse("응답 뒤 재경보 없음(${world.elapsed / 1000}s ${t.car.state()})", t.car.state() in alarmStates)
            !t.car.hub.snapshot.test
        }
        val clearedAt = world.elapsed
        assertNull(t.phone.engine.test)
        assertEquals(FlowState.S0, t.car.state())
        assertFalse(t.car.hub.screen().test)
        assertTrue("120초 뒤 몇 초 안(${clearedAt - startedAt}ms)", clearedAt - startedAt in 120_000..130_000)
        world.runUntil(4_000, "휴대폰 화면도 꺼짐") { !t.phone.status().flowTest }
        world.advance(10_000)
        assertEquals("되살아나지 않는다", FlowState.S0, t.car.state())
        assertFalse(t.car.hub.snapshot.test)
    }
}
