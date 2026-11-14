// 차 허브 화면 연결 시험 — 차 → 휴대폰 TEST_REQUEST(정차만 · 새 id · 15초 무응답 · 결과는 흐름 test), carDisplay 숨김 설정(모르면 숨김), 워치 칸 나이 · 서버 끊김
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.CarDisplay
import com.example.guardian.shared.protocol.CarSyncResponse
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.PhonePeer
import com.example.guardian.shared.protocol.TestAction
import com.example.guardian.shared.protocol.Via
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHubDisplayTest {
    private fun hrWatch(hr: Int = 72, ageMs: Long = 0) =
        Watch(WristVerdict.NORMAL, hr, null, true, WatchSource.SENSOR_SDK, false, null, Via.WATCH, 0, ageMs)

    /** 휴대폰 상태에 carDisplay 를 실은 sync. */
    private fun Rig.syncDisplay(t: Long, display: CarDisplay?, watch: Watch? = hrWatch(), test: Boolean = false) {
        val req = hub.buildRequest("tok", 1, 0, t)
        sentCommands += req.commands
        val serverTime = server(t + 50)
        val peer = PhonePeer(1, 0, serverTime - 1_000, null, settings(test = test), null, display)
        hub.onSyncSuccess(req, t, t + 100, CarSyncResponse(serverTime, peer, watch, emptyList()))
    }

    private fun Rig.testRequests() = (sentCommands + hub.pendingCommands).filter { it.type == CommandType.TEST_REQUEST }

    // ── 숨김 설정 ──

    @Test fun displaySettingUnknownHidesThenFollowsPhone() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.syncDisplay(0, display = null)
        assertEquals("휴대폰 설정 확인 중", r.hub.bio(100).hrText)
        r.syncDisplay(2_000, CarDisplay(hideHr = false))
        assertEquals(72, r.hub.bio(2_100).hr)
        r.syncDisplay(4_000, CarDisplay(hideHr = true))
        assertEquals("휴대폰에서 숨김", r.hub.bio(4_100).hrText)
    }

    @Test fun displaySettingUnknownWhenPeerMissing() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.syncDisplay(0, CarDisplay(hideHr = false))
        // peer 가 없는 응답 — 모름으로 보고 숨긴다(계약 §4.1, PM 결정 2026-11-02)
        val req = r.hub.buildRequest("tok", 2, 0, 2_000)
        r.hub.onSyncSuccess(req, 2_000, 2_100, CarSyncResponse(r.server(2_050), null, hrWatch(80), emptyList()))
        assertNull(r.hub.carDisplay)
        assertNull(r.hub.bio(2_200).hr)
        assertEquals("휴대폰 설정 확인 중", r.hub.bio(2_200).hrText)
    }

    @Test fun displaySettingKeptWhenPeerIsStale() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.syncDisplay(0, CarDisplay(hideHr = false))
        // 휴대폰 상태가 오래됨(끊김) — 중계가 들고 있는 마지막 값을 그대로 쓴다
        val req = r.hub.buildRequest("tok", 3, 0, 4_000)
        val serverTime = r.server(4_050)
        val stale = PhonePeer(1, 0, serverTime - 60_000, null, settings(), null, CarDisplay(hideHr = false))
        r.hub.onSyncSuccess(req, 4_000, 4_100, CarSyncResponse(serverTime, stale, hrWatch(81), emptyList()))
        assertFalse(r.hub.links(4_200).phone)
        assertEquals(CarDisplay(false), r.hub.carDisplay)
        assertEquals(81, r.hub.bio(4_200).hr)
    }

    // ── 워치 칸 나이 ──

    @Test fun bioAgesWithLocalTimeAndClearsAfter30s() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.syncDisplay(0, CarDisplay(false), hrWatch(ageMs = 4_000))
        // 응답 때 4초 + 받은 뒤(100) 지난 시간
        assertEquals("방금", r.hub.bio(1_099).updated)
        assertEquals("5초 전", r.hub.bio(1_100).updated)
        // 서버가 끊기면(10초 넘게 sync 없음) 옛 값을 보이지 않는다
        assertEquals(72, r.hub.bio(10_100).hr)
        assertTrue(r.hub.bio(10_101).noSignal)
        assertEquals(Via.WATCH, r.hub.watchVia)
    }

    @Test fun staleWatchValueErasesTheNumber() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.syncDisplay(0, CarDisplay(false), hrWatch(ageMs = 30_001))
        val b = r.hub.bio(100)
        assertTrue(b.noSignal)
        assertNull(b.hr)
    }

    // ── 테스트 요청 ──

    @Test fun startRequestGoesToPhoneAsTestWithFixedScenario() {
        val r = Rig()
        r.arm(PARKED)
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        assertTrue(r.hub.requestTest(TestAction.START, 1_000))
        val c = r.testRequests().single()
        assertEquals(CommandTo.PHONE, c.resolvedTo)
        assertEquals(Party.CAR, c.from)
        assertTrue(c.test)
        assertEquals(mapOf("action" to "START", "scenario" to "PULSE_LOST", "durationMs" to 120_000L), c.payload)
        assertEquals(TestEntryView("테스트 시작", false, CarTexts.TEST_WAITING, TestAction.START), r.hub.testEntry(1_000))
        // 기다리는 동안 다시 눌러도 보내지 않는다
        assertFalse(r.hub.requestTest(TestAction.START, 2_000))
    }

    @Test fun noAnswerIn15sRevivesTheButtonAndNextPressHasANewId() {
        val r = Rig()
        r.arm(PARKED)
        r.hub.requestTest(TestAction.START, 1_000)
        r.syncDisplay(2_000, CarDisplay(false))
        r.syncDisplay(15_000, CarDisplay(false))
        r.hub.onReading(PARKED, 15_900)
        assertEquals(CarTexts.TEST_WAITING, r.hub.testEntry(15_999).reason)
        val late = r.hub.testEntry(16_000)
        assertTrue(late.enabled)
        assertEquals(CarTexts.TEST_NO_ANSWER, late.reason)
        assertTrue(r.hub.requestTest(TestAction.START, 16_000))
        val ids = r.testRequests().map { it.id }
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test fun phoneTestFlagStartsThenStopRequestEndsIt() {
        val r = Rig()
        r.arm(PARKED)
        r.hub.requestTest(TestAction.START, 1_000)
        r.syncDisplay(3_000, CarDisplay(false), test = true)
        assertTrue(r.hub.snapshot.test)
        assertNull(r.hub.testPending)
        assertEquals(TestEntryView("테스트 멈추기", true, null, TestAction.STOP), r.hub.testEntry(3_100))
        assertTrue(r.hub.requestTest(TestAction.STOP, 4_000))
        assertEquals(mapOf("action" to "STOP"), r.testRequests().last().payload)
        assertEquals(CarTexts.TEST_WAITING, r.hub.testEntry(4_000).reason)
        r.syncDisplay(6_000, CarDisplay(false), test = false)
        assertFalse(r.hub.snapshot.test)
        assertNull(r.hub.testPending)
        assertEquals(TestEntryView("테스트 시작", true, null, TestAction.START), r.hub.testEntry(6_100))
    }

    /**
     * 차 「테스트 시작」이 휴대폰 단독(휴대폰 안) 시험으로 떨어진 경우 — 휴대폰은 `flow.localTest` 만 참으로 보내고 `flow.test` 는 거짓이다.
     * 차는 요청을 풀고 「휴대폰이 답하지 않습니다」 대신 「휴대폰에서 테스트 중 · 차 화면은 실제 상태입니다」를 두며, 흐름은 실제 그대로다
     * (그동안 실제 나쁜 값이면 실제 경보). 휴대폰 시험이 끝나 거짓이 오면 안내도 사라진다(2026-11-07).
     */
    @Test fun phoneLocalTestReleasesRequestAndShowsNoticeWithoutTestMark() {
        val r = Rig()
        r.arm(PARKED)
        assertTrue(r.hub.requestTest(TestAction.START, 1_000))
        assertEquals(CarTexts.TEST_WAITING, r.hub.testEntry(1_000).reason)
        r.sync(3_000, watch(WristVerdict.NORMAL), peerFlow = settings(localTest = true))
        assertTrue(r.hub.phoneLocalTest)
        assertNull("요청이 풀린다", r.hub.testPending)
        assertFalse("차 흐름은 테스트가 아니다", r.hub.snapshot.test)
        assertFalse(r.hub.screen().test)
        assertEquals(TestEntryView(CarTexts.TEST_START, false, CarTexts.TEST_PHONE_LOCAL, TestAction.START), r.hub.testEntry(3_100))
        // 15초가 지나도 틀린 안내는 없다
        r.sync(17_000, watch(WristVerdict.NORMAL), peerFlow = settings(localTest = true))
        assertEquals(CarTexts.TEST_PHONE_LOCAL, r.hub.testEntry(17_100).reason)
        assertNotEquals(CarTexts.TEST_NO_ANSWER, r.hub.testEntry(17_100).reason)
        // 그동안 실제 나쁜 값 → 실제 경보(테스트 표시 없음)
        r.sync(19_000, watch(WristVerdict.PULSE_ABSENT), peerFlow = settings(localTest = true))
        assertEquals(FlowState.AP, r.hub.snapshot.state)
        assertFalse(r.hub.snapshot.test)
        // 휴대폰 시험이 끝나면(필드 거짓) 안내도 사라진다 — 경보 중이라 입구 사유는 「확인 중일 때만」
        r.sync(21_000, watch(WristVerdict.PULSE_ABSENT), peerFlow = settings())
        assertFalse(r.hub.phoneLocalTest)
        assertEquals(CarTexts.TEST_NEED_WATCHING, r.hub.testEntry(21_100).reason)
    }

    /** 휴대폰이 정말 답하지 않으면(필드 없음 · flow.test 거짓) 15초 뒤 「휴대폰이 답하지 않습니다」는 그대로다. */
    @Test fun silentPhoneStillShowsNoAnswerAfter15s() {
        val r = Rig()
        r.arm(PARKED)
        assertTrue(r.hub.requestTest(TestAction.START, 1_000))
        r.sync(3_000, watch(WristVerdict.NORMAL), peerFlow = settings())
        assertEquals(CarTexts.TEST_WAITING, r.hub.testEntry(3_100).reason)
        r.sync(17_000, watch(WristVerdict.NORMAL), peerFlow = settings())
        assertEquals(TestEntryView(CarTexts.TEST_START, true, CarTexts.TEST_NO_ANSWER, TestAction.START), r.hub.testEntry(17_100))
    }

    @Test fun drivingRefusesTestRequest() {
        val r = Rig()
        r.arm(PARKED)
        r.hub.onReading(MOVING, 1_000)
        assertFalse(r.hub.setupOk())
        assertFalse(r.hub.requestTest(TestAction.START, 1_000))
        assertTrue(r.testRequests().isEmpty())
    }

    @Test fun entryNeedsServerAndPhone() {
        val r = Rig()
        r.arm(PARKED)
        // 서버(차 주인) 10초 지남
        assertEquals(CarTexts.TEST_NEED_SERVER, r.hub.testEntry(10_101).reason)
        assertFalse(r.hub.requestTest(TestAction.START, 10_101))
        // 휴대폰 상태가 오래됨(10초 넘음)
        val r2 = Rig()
        r2.hub.onReading(PARKED, 0)
        r2.sync(0, watch(WristVerdict.NORMAL), peerAgeMs = 10_001)
        assertEquals(CarTexts.TEST_NEED_PHONE, r2.hub.testEntry(100).reason)
    }

    // ── 테스트 세션 종료 — 응답 뒤(S1R · S2)에도 띠 · 「테스트 멈추기」가 바로 사라진다(실주행 3 새 결함, 사용자 지시 2026-11-07) ──

    /** 워치 시나리오 테스트 → 주행 중 경보 → 워치 응답(휴식) → 세션 끝(휴대폰 test 꺼짐 · 워치 실제 값) → 표시 즉시 꺼짐 · S0 · 입구는 「테스트 시작」. */
    @Test fun sessionEndWhileRestingClearsTheMarkAtOnce() {
        val r = Rig()
        r.arm(PARKED)
        r.sync(2_000, watch(WristVerdict.NORMAL, test = true, responseAt = 1_000), peerFlow = settings(test = true))
        r.hub.onReading(MOVING, 3_000)
        r.hub.onReading(MOVING, 8_000) // 주행 판정 디바운스 5초
        r.sync(8_000, watch(WristVerdict.PULSE_ABSENT, test = true, responseAt = 1_000), peerFlow = settings(test = true))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        assertTrue(r.hub.snapshot.test)
        r.sync(10_000, watch(WristVerdict.PULSE_ABSENT, test = true, responseAt = 9_000), peerFlow = settings(test = true))
        assertEquals(FlowState.S1R, r.hub.snapshot.state)
        assertTrue(r.hub.screen().test)
        // 세션 끝 — 휴대폰 flow.test 거짓, 워치는 실제 값
        r.sync(12_000, watch(WristVerdict.NORMAL), peerFlow = settings(test = false))
        assertFalse("표시가 바로 꺼진다", r.hub.snapshot.test)
        assertEquals("휴식을 기다리지 않고 S0", FlowState.S0, r.hub.snapshot.state)
        assertFalse(r.hub.screen().test)
        assertEquals(TestEntryView("테스트 시작", true, null, TestAction.START), r.hub.testEntry(12_100))
    }

    /** 정차 관찰(S2) 중 세션이 끝난 줄 모르고 「테스트 멈추기」를 눌러도 — 다음 sync 의 flow.test 거짓으로 요청이 풀려 「휴대폰이 답하지 않습니다」가 뜨지 않는다. */
    @Test fun stopPressedAfterSessionAlreadyEndedDoesNotShowNoAnswer() {
        val r = Rig()
        r.arm(PARKED)
        r.sync(2_000, watch(WristVerdict.NORMAL, test = true, responseAt = 1_000), peerFlow = settings(test = true))
        r.sync(4_000, watch(WristVerdict.PULSE_ABSENT, test = true, responseAt = 1_000), peerFlow = settings(test = true))
        assertEquals(FlowState.AP, r.hub.snapshot.state)
        r.sync(6_000, watch(WristVerdict.PULSE_ABSENT, test = true, responseAt = 5_000), peerFlow = settings(test = true))
        assertEquals(FlowState.S2, r.hub.snapshot.state)
        r.hub.onReading(PARKED, 7_000)
        assertTrue(r.hub.requestTest(TestAction.STOP, 7_000))
        assertEquals(CarTexts.TEST_WAITING, r.hub.testEntry(7_000).reason)
        // 휴대폰은 세션이 이미 없다 — flow.test 거짓만 온다
        r.sync(9_000, watch(WristVerdict.NORMAL), peerFlow = settings(test = false))
        assertNull(r.hub.testPending)
        assertFalse(r.hub.snapshot.test)
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        r.sync(22_000, watch(WristVerdict.NORMAL), peerFlow = settings(test = false))
        r.hub.onReading(PARKED, 23_000)
        val late = r.hub.testEntry(23_000)
        assertNotEquals(CarTexts.TEST_NO_ANSWER, late.reason)
        assertEquals(TestEntryView("테스트 시작", true, null, TestAction.START), late)
    }
}
