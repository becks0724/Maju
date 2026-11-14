// 워치 테스트 경계(C-1, 앱2-중2) 시험 — 신선한 워치 값이 test=true 일 때만 테스트로 다루고, 워치가 멈추거나 시나리오가 닿지 않으면 세션을 끝낸다
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTestBoundaryTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { false })

    private fun real(v: WristVerdict) = Watch(v, 70, wall0, worn = true, source = WatchSource.HEALTH_SERVICES, test = false, responseAt = null)
    private fun sim(v: WristVerdict) = real(v).copy(source = WatchSource.SIMULATION, test = true)
    private fun loc() = Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "주소")
    private fun feed(w: Watch) { engine.onDataLayerWatch(w, mono); engine.onLocation(loc(), mono) }
    private fun advance(ms: Long, each: () -> Unit = {}) { val end = mono + ms; while (mono < end) { mono += 1_000; each(); engine.tick(mono) } }

    private fun startWatchSessionPhoneOwner() {
        feed(real(WristVerdict.NORMAL))
        engine.buildSync("tok", mono)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, null, null, emptyList(), watchDirectAgeMs = 1_000), mono, mono)
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(TestStart.WATCH, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
    }

    private fun scenarioStops() = engine.buildSync("tok", mono).commands.count { it.type == CommandType.TEST_SCENARIO_STOP }

    /** 레드 RT1 뒤집음 — 워치 「멈추기」 뒤 실제 값만 오면 세션이 끝나고, 그 뒤 실제 무응답은 실제로 처리된다. */
    @Test fun watchStopped_realIncidentIsReal() {
        startWatchSessionPhoneOwner()
        advance(10_000) { feed(sim(WristVerdict.NORMAL)) }
        advance(12_000) { feed(real(WristVerdict.NORMAL)) }
        assertNull("test=false 10초 넘게 — 세션 끝", engine.test)
        advance(45_000) { feed(real(WristVerdict.PULSE_ABSENT)) }
        val st = engine.status(mono)
        assertEquals(FlowState.S4, st.flowState)
        assertFalse("실제 값이 연 사건은 실제", st.flowTest)
        assertEquals("실제 보호자 문자 1통", 1, fx.sms.size)
        assertTrue("테스트 확인으로 가지 않음", fx.asked.isEmpty())
        assertTrue("119 미리보기 아님", fx.previews.isEmpty() && fx.noResponses.isNotEmpty())
    }

    /** 레드 RT1 그대로의 순서 — 세션이 남아 있는 10초 창 안에 실제 무응답이 시작돼도 실제 문자 1통 · 테스트 처리 아님. */
    @Test fun watchStopped_incidentStartsInsideWindow_stillReal() {
        startWatchSessionPhoneOwner()
        advance(10_000) { feed(sim(WristVerdict.NORMAL)) }
        advance(10_000) { feed(real(WristVerdict.NORMAL)) }
        advance(45_000) { feed(real(WristVerdict.PULSE_ABSENT)) }
        val st = engine.status(mono)
        assertNull(engine.test)
        assertEquals(FlowState.S4, st.flowState)
        assertFalse(st.flowTest)
        assertEquals(1, fx.sms.size)
        assertTrue(fx.asked.isEmpty())
        assertTrue(fx.previews.isEmpty() && fx.noResponses.isNotEmpty())
    }

    /** 레드 RT2 뒤집음(B-7) — 무응답 시연을 아무도 응답하지 않아 안전장치(7분)로 끝나면 테스트 흐름을 거두고, 그 뒤 실제 값은 실제 사건을 연다. */
    @Test fun graceEnd_withdrawsTestFlow_realSignalsOpenRealIncident() {
        startWatchSessionPhoneOwner()
        val graceEnd = LocalTestRun.DURATION_MS + PhoneEngine.TEST_GRACE_MS
        advance(graceEnd + 1_000) { feed(sim(WristVerdict.PULSE_ABSENT)) }
        assertNull(engine.test)
        advance(5_000) { feed(real(WristVerdict.NORMAL)) }
        assertEquals("테스트 흐름을 거뒀다", FlowState.S0, engine.status(mono).flowState)
        assertTrue("테스트 알람을 멈췄다", fx.alarmStops > 0)
        advance(60_000) { feed(real(WristVerdict.PULSE_ABSENT)) }
        val st = engine.status(mono)
        assertTrue(st.flowState in PhoneEngine.ALARM_STATES)
        assertFalse("실제 사건", st.flowTest)
        assertEquals("실제 보호자 문자", 1, fx.sms.size)
    }

    /** C-1 로 세션이 끝날 때도(워치 「멈추기」) 경보 중인 테스트 흐름을 거둔다. */
    @Test fun watchStoppedDuringTestAlarm_withdrawsTestFlow() {
        startWatchSessionPhoneOwner()
        advance(40_000) { feed(sim(WristVerdict.PULSE_ABSENT)) }
        assertTrue(engine.status(mono).flowState in PhoneEngine.ALARM_STATES)
        advance(12_000) { feed(real(WristVerdict.NORMAL)) }
        assertNull(engine.test)
        assertEquals(FlowState.S0, engine.status(mono).flowState)
    }

    /** 10초 창 — test=false 가 10초 이하면 세션 유지. */
    @Test fun shortRealGap_keepsSession() {
        startWatchSessionPhoneOwner()
        advance(5_000) { feed(sim(WristVerdict.NORMAL)) }
        advance(9_000) { feed(real(WristVerdict.NORMAL)) }
        assertNotNull(engine.test)
        advance(5_000) { feed(sim(WristVerdict.NORMAL)) }
        assertNotNull(engine.test)
    }

    /** 세는 시작점은 첫 test=true 뒤 — 시나리오가 늦게(15초 안) 닿으면 그 전 실제 값으로 끝내지 않는다. */
    @Test fun lateScenario_within15s_keepsSession() {
        startWatchSessionPhoneOwner()
        advance(14_000) { feed(real(WristVerdict.NORMAL)) }
        assertNotNull(engine.test)
        advance(20_000) { feed(sim(WristVerdict.NORMAL)) }
        assertNotNull(engine.test)
    }

    /** 시작 뒤 15초 안에 test=true 를 한 번도 못 보면 끝내고 워치 시나리오 멈춤을 보낸다. */
    @Test fun scenarioNeverArrives_endsAfter15s() {
        startWatchSessionPhoneOwner()
        advance(15_000) { feed(real(WristVerdict.NORMAL)) }
        assertNotNull(engine.test)
        advance(1_000) { feed(real(WristVerdict.NORMAL)) }
        assertNull(engine.test)
        assertEquals(1, scenarioStops())
    }

    /** 값이 아예 오지 않으면(신선하지 않음) 10초 규칙은 세지 않는다. */
    @Test fun noFreshValues_after_firstSim_keepsSession() {
        startWatchSessionPhoneOwner()
        advance(3_000) { feed(sim(WristVerdict.NORMAL)) }
        advance(20_000)
        assertNotNull(engine.test)
    }

    /** 세션 중이라도 신선한 값이 test=false 인 동안은 차로 보내는 flow 를 테스트로 표시하지 않는다. */
    @Test fun syncFlowTest_onlyWithFreshSimValue() {
        startWatchSessionPhoneOwner()
        advance(2_000) { feed(real(WristVerdict.NORMAL)) }
        assertFalse("아직 시뮬레이션 값 없음", engine.buildSync("tok", mono).flow!!.test)
        advance(2_000) { feed(sim(WristVerdict.NORMAL)) }
        assertTrue(engine.buildSync("tok", mono).flow!!.test)
    }
}
