// 테스트 세션 종료 규칙 시험 — 시나리오 120초 뒤 흐름이 경보 중이 아니면 바로 끝남, 경보 중이면 응답 뒤 끝남, 응답 없으면 안전장치(7분), 차 flow 를 모르면 안전장치(PM 결정 2026-11-07)
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestSessionEndTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private var relay = false
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { relay })

    private val duration = LocalTestRun.DURATION_MS
    private val graceEnd = LocalTestRun.DURATION_MS + PhoneEngine.TEST_GRACE_MS

    private fun real(v: WristVerdict) = Watch(v, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = null)
    private fun sim(v: WristVerdict) = real(v).copy(source = WatchSource.SIMULATION, test = true)
    private fun loc() = Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "서울 종로구 세종대로 172")
    private fun feed(w: Watch) { engine.onDataLayerWatch(w, mono); engine.onLocation(loc(), mono) }
    private fun carFlow(state: FlowState, test: Boolean = true) =
        FlowStatus.of(FlowSnapshot(state = state, test = test, powerOn = true, consents = Consents(true, true, true)), Owner.CAR)

    /** 차가 1초 전에 중계에 닿았고(신선), 워치는 직접 경로. [flow] 가 null 이면 차가 flow 를 싣지 않은 것. */
    private fun carSync(flow: FlowStatus?) {
        engine.buildSync("tok", mono)
        val peer = CarPeer(1, wall0, mono + serverOffset - 1_000, Vehicle(speedKmh = 0.0, gear = Gear.P, make = "현대", model = "아반떼", year = 2027), flow)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, emptyList(), watchDirectAgeMs = 1_000), mono, mono)
    }

    /** 1초 간격으로 [each] 를 넣고 tick. */
    private fun advance(ms: Long, each: () -> Unit = {}) {
        val end = mono + ms
        while (mono < end) { mono += 1_000; each(); engine.tick(mono) }
    }

    private fun startWatchSessionPhoneOwner() {
        feed(real(WristVerdict.NORMAL))
        engine.buildSync("tok", mono)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, null, null, emptyList(), watchDirectAgeMs = 1_000), mono, mono)
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(TestStart.WATCH, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
    }

    private fun startWatchSessionCarOwner() {
        relay = true
        feed(real(WristVerdict.NORMAL))
        carSync(carFlow(FlowState.S0, test = false))
        engine.tick(mono)
        assertEquals(Owner.CAR, engine.owner)
        assertEquals(TestStart.WATCH, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
    }

    private fun startLocalSession(scenario: Scenario) {
        engine.onLocation(loc(), mono)
        engine.tick(mono)
        assertEquals(TestStart.LOCAL, engine.startTest(scenario, phoneOnly = true, now = mono))
    }

    // ── 휴대폰 주인 · 워치 시나리오 ──

    @Test fun phoneOwner_idleAt120s_endsAtOnce() {
        startWatchSessionPhoneOwner()
        advance(duration - 1_000) { feed(sim(WristVerdict.NORMAL)) }
        assertNotNull("120초 전에는 세션 유지", engine.test)
        advance(2_000) { feed(sim(WristVerdict.NORMAL)) }
        assertNull("120초가 지나고 S0 이면 바로 끝", engine.test)
        assertEquals(FlowState.S0, engine.status(mono).flowState)
        advance(1_000) { feed(real(WristVerdict.NORMAL)) }
        assertFalse("표시는 다음 틱(2초 주기)에 꺼진다", engine.status(mono).flowTest)
        assertFalse(engine.buildSync("tok", mono).flow!!.test)
    }

    /** 120초에 정차 관찰(S2)이면 세션이 바로 끝난다. 표시는 신선한 워치 값이 시뮬레이션인 동안 남고, 실제 값이 오는 다음 틱에 S0 · 꺼짐. */
    @Test fun phoneOwner_restingAt120s_endsAndMarkClearsWhenWatchReturnsReal() {
        startWatchSessionPhoneOwner()
        advance(3_000) { feed(sim(WristVerdict.PULSE_ABSENT)) }
        assertEquals(FlowState.AP, engine.status(mono).flowState)
        engine.phoneResponse(mono)
        assertEquals(FlowState.S2, engine.status(mono).flowState)
        advance(duration - 4_000) { feed(sim(WristVerdict.NORMAL)) }
        assertNotNull(engine.test)
        assertEquals(FlowState.S2, engine.status(mono).flowState)
        advance(2_000) { feed(sim(WristVerdict.NORMAL)) }
        assertNull("120초가 지나고 S2 면 바로 끝", engine.test)
        assertEquals("워치가 아직 시뮬레이션이면 표시 유지", FlowState.S2, engine.status(mono).flowState)
        assertTrue(engine.status(mono).flowTest)
        advance(1_000) { feed(real(WristVerdict.NORMAL)) }
        assertEquals("실제 값이 온 다음 틱에 S0", FlowState.S0, engine.status(mono).flowState)
        assertFalse(engine.status(mono).flowTest)
        assertFalse(engine.buildSync("tok", mono).flow!!.test)
    }

    @Test fun phoneOwner_alertingAt120s_waitsThenEndsAfterResponse() {
        startWatchSessionPhoneOwner()
        advance(100_000) { feed(sim(WristVerdict.NORMAL)) }
        advance(40_000) { feed(sim(WristVerdict.PULSE_ABSENT)) }
        assertEquals("무응답 시연 중", FlowState.S4, engine.status(mono).flowState)
        assertNotNull("경보 중이면 120초가 지나도 세션 유지", engine.test)
        engine.phoneResponse(mono)
        assertEquals(FlowState.S2, engine.status(mono).flowState)
        advance(1_000) { feed(sim(WristVerdict.NORMAL)) }
        assertNull("경보에서 벗어난 다음 틱에 끝", engine.test)
        advance(1_000) { feed(real(WristVerdict.NORMAL)) }
        assertEquals(FlowState.S0, engine.status(mono).flowState)
        assertFalse(engine.status(mono).flowTest)
    }

    @Test fun phoneOwner_noResponse_endsAtGrace() {
        startWatchSessionPhoneOwner()
        advance(3_000) { feed(sim(WristVerdict.PULSE_ABSENT)) }
        advance(graceEnd - 3_000) { feed(sim(WristVerdict.PULSE_ABSENT)) }
        assertEquals(FlowState.S4, engine.status(mono).flowState)
        assertNotNull("안전장치 시각까지는 유지", engine.test)
        advance(1_000) { feed(sim(WristVerdict.PULSE_ABSENT)) }
        assertNull("응답이 없으면 안전장치(7분)에 끝", engine.test)
    }

    // ── 차 주인 · 워치 시나리오(차 flow 로 판단) ──

    @Test fun carOwner_carFlowRestingAt120s_endsAtOnce() {
        startWatchSessionCarOwner()
        advance(duration - 1_000) { feed(sim(WristVerdict.NORMAL)); carSync(carFlow(FlowState.S2)) }
        assertNotNull(engine.test)
        assertTrue(engine.buildSync("tok", mono).flow!!.test)
        advance(2_000) { feed(sim(WristVerdict.NORMAL)); carSync(carFlow(FlowState.S2)) }
        assertNull("차 flow 가 S2 면 바로 끝", engine.test)
        assertFalse("다음 sync 의 flow.test 거짓 — 차가 표시를 끈다", engine.buildSync("tok", mono).flow!!.test)
    }

    @Test fun carOwner_carFlowAlertingAt120s_waitsThenEndsWhenCarFlowRests() {
        startWatchSessionCarOwner()
        advance(duration + 10_000) { feed(sim(WristVerdict.NORMAL)); carSync(carFlow(FlowState.S4)) }
        assertNotNull("차가 무응답 시연 중이면 유지", engine.test)
        advance(2_000) { feed(sim(WristVerdict.NORMAL)); carSync(carFlow(FlowState.S1R)) }
        assertNull("차 흐름이 경보에서 벗어난 다음 틱에 끝", engine.test)
    }

    @Test fun carOwner_carFlowMissing_endsOnlyAtGrace() {
        startWatchSessionCarOwner()
        advance(graceEnd) { feed(sim(WristVerdict.NORMAL)); carSync(null) }
        assertNotNull("차 flow 를 모르면 경보 중이 아니라고 단정하지 않는다", engine.test)
        advance(1_000) { feed(sim(WristVerdict.NORMAL)); carSync(null) }
        assertNull(engine.test)
    }

    // ── 휴대폰 단독(LOCAL) ──

    @Test fun local_idleAt120s_endsAtOnce() {
        startLocalSession(Scenario.NORMAL)
        advance(duration - 1_000) { engine.onLocation(loc(), mono) }
        assertNotNull(engine.test)
        assertEquals(FlowState.S0, engine.status(mono).testFlowState)
        advance(2_000) { engine.onLocation(loc(), mono) }
        assertNull(engine.test)
        assertFalse(engine.status(mono).flowTest)
    }

    @Test fun local_alertingAt120s_waitsThenEndsAfterResponse() {
        startLocalSession(Scenario.PULSE_LOST)
        advance(duration + 10_000) { engine.onLocation(loc(), mono) }
        assertEquals(FlowState.S4, engine.status(mono).testFlowState)
        assertNotNull(engine.test)
        engine.phoneResponse(mono)
        assertEquals(FlowState.S2, engine.status(mono).testFlowState)
        advance(1_000) { engine.onLocation(loc(), mono) }
        assertNull(engine.test)
        assertFalse(engine.status(mono).flowTest)
    }

    @Test fun local_noResponse_endsAtGrace() {
        startLocalSession(Scenario.PULSE_LOST)
        advance(graceEnd) { engine.onLocation(loc(), mono) }
        assertNotNull(engine.test)
        advance(1_000) { engine.onLocation(loc(), mono) }
        assertNull(engine.test)
    }
}
