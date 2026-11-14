// 휴대폰 단독 테스트(LOCAL) B1 결함 재현 — 응답 뒤 30초 재경보, 테스트 알림 남음(2026-11-08 노트10 실측)
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.Loc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTestB1Test {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { false })

    private fun loc() = Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "서울 종로구 세종대로 172")

    private fun advance(ms: Long, each: () -> Unit = {}) {
        val end = mono + ms
        while (mono < end) { mono += 1_000; engine.onLocation(loc(), mono); each(); engine.tick(mono) }
    }

    private fun start(scenario: Scenario = Scenario.PULSE_LOST) {
        engine.onLocation(loc(), mono)
        engine.tick(mono)
        assertEquals(TestStart.LOCAL, engine.startTest(scenario, phoneOnly = true, now = mono))
    }

    private fun testState() = engine.status(mono).testFlowState

    /** 결함 2 — 2회차 실측: 경보 1(27초)에 「괜찮아요」, 32초 뒤 같은 시나리오가 다시 경보. 응답 뒤에는 시뮬레이션 신호가 정상이라 재경보가 없고 120초 뒤 끝난다. */
    @Test fun respondedLocalTestDoesNotRealertAndEndsAt120s() {
        val startMono = mono
        start()
        while (testState() != FlowState.AP) advance(1_000)
        engine.phoneResponse(mono)
        assertEquals(FlowState.S2, testState())
        val realerts = ArrayList<FlowState>()
        while (mono - startMono <= LocalTestRun.DURATION_MS) {
            advance(1_000)
            testState()?.takeIf { it in PhoneEngine.ALARM_STATES }?.let { realerts += it }
        }
        assertTrue("응답 뒤 재경보 없음: $realerts", realerts.isEmpty())
        advance(2_000)
        assertNull("시작 120초 뒤 세션이 끝난다", engine.test)
        assertFalse("테스트 띠가 꺼진다", engine.status(mono).flowTest)
    }

    /** 응답하지 않으면 그대로 무응답(S4)까지 간다 — 시연 흐름은 바꾸지 않는다. */
    @Test fun unansweredLocalTestStillReachesNoResponse() {
        start()
        advance(90_000)
        assertEquals(FlowState.S4, testState())
        assertNotNull(engine.test)
    }

    /** 결함 5 — 답한 뒤에도 「보호자에게 테스트 문자를 보낼까요?」 알림이 남았다. 답하면 그 알림을 거둔다. */
    @Test fun answeringTestSmsClearsItsNotification() {
        start()
        advance(90_000)
        val p = fx.asked.single()
        assertEquals(0, fx.testSmsAskCleared)
        engine.confirmTestSms(p.key, send = false)
        assertEquals(1, fx.testSmsAskCleared)
    }

    /** 결함 5 — 테스트가 끝난 뒤(멈추기 · 120초 · 안전장치) 「119 미리보기」 · 테스트 문자 알림이 남았다. 끝나면 테스트 흔적을 거둔다. */
    @Test fun endingTestClearsTestNotifications() {
        start()
        advance(90_000)
        assertEquals(0, fx.testScreensCleared)
        engine.stopTest()
        assertEquals(1, fx.testScreensCleared)
    }

    /** 결함 6 — 문자 결과 기록에 사건 id 가 실려, 화면이 지금 보이는 사건(status.eventId) 것만 고를 수 있다. */
    @Test fun smsResultCarriesShownIncidentId() {
        start()
        advance(90_000)
        val shown = engine.status(mono).eventId
        assertNotNull(shown)
        engine.confirmTestSms(fx.asked.single().key, send = true)
        assertEquals(listOf(shown), fx.smsEvents)
    }

    /** PM 결정 2 — 테스트가 끝나면 답하지 않은 테스트 문자 확인을 「보내지 않음」으로 거두고 다시 묻지 않는다(dropTestState 와 같은 처리). */
    @Test fun endingTestDropsUnansweredTestSmsAsk() {
        start()
        advance(90_000)
        val p = fx.asked.single()
        assertEquals(1, engine.executor.pending().size)
        engine.stopTest()
        assertTrue("홈 창 · 경보 화면에서 사라진다", engine.executor.pending().isEmpty())
        assertTrue("다시 묻지 않는다", store.isSmsSent(p.key))
        assertTrue("보내지 않음으로 기록", store.smsRecords.none { it.sent })
        assertTrue(fx.sms.isEmpty())
    }

    @Test fun safetyTimeoutDropsUnansweredTestSmsAsk() {
        start()
        advance(LocalTestRun.DURATION_MS + PhoneEngine.TEST_GRACE_MS + 2_000)
        assertNull(engine.test)
        assertEquals(1, fx.asked.size)
        assertTrue(engine.executor.pending().isEmpty())
    }

    @Test fun safetyTimeoutClearsTestNotifications() {
        start()
        advance(LocalTestRun.DURATION_MS + PhoneEngine.TEST_GRACE_MS + 2_000)
        assertNull(engine.test)
        assertEquals(1, fx.testScreensCleared)
    }
}
