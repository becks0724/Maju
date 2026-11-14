// 차 화면 설계(car-spec 2절 상태표) 시험 — 정차 · 주행 × 연결 · 숨김 · 설정 모름 · 신호 없음 · 동의 없음 · 측정값 없음 · 테스트 · 경보, 워치 칸 · 주행 한 줄 · 10초 묶음 · 테스트 입구 · 문구
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.TestAction
import com.example.guardian.shared.protocol.Via
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.text.ForbiddenWords
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarBioScreenTest {
    private val both = Links(phone = true, watch = WatchLink.WORN, server = true)

    private fun w(
        hr: Int? = 72, verdict: WristVerdict = WristVerdict.NORMAL, worn: Boolean = true,
        source: WatchSource = WatchSource.SENSOR_SDK, test: Boolean = false,
    ) = Watch(verdict, hr, null, worn, source, test, null, Via.WATCH, 0, 0)

    private fun shown(watch: Watch = w(), age: Long = 1_000) = CarScreen.bio(watch, age, healthConsent = true, hideHr = false)

    private fun parked(bio: BioView, state: FlowState = FlowState.S0, linkOk: Boolean = true, links: Links = both) =
        CarScreen.idle(linkOk, state, true, true, links, setupOk = true, bio = bio, test = CarScreen.testEntry(linkOk, links.phone, state, false, null, 0))

    private fun driving(bio: BioView, state: FlowState = FlowState.S0, linkOk: Boolean = true, links: Links = both, power: Boolean = true, consents: Boolean = true) =
        CarScreen.idle(linkOk, state, power, consents, links, setupOk = false, bio = bio)

    // ── 워치 칸 값 ──

    @Test fun shownValuesUseG1WordsAndShapesOnly() {
        val b = shown()
        assertEquals(72, b.hr)
        assertEquals("72", b.hrText)
        assertEquals(PulseView("●", "잡힘"), b.pulse)
        assertEquals("착용 중", b.wornText)
        assertEquals("방금", b.updated)
        assertFalse(b.noSignal)
        assertEquals(PulseView("◐", "약해짐"), CarScreen.pulse(WristVerdict.PULSE_WEAK))
        assertEquals(PulseView("○", "잡히지 않음"), CarScreen.pulse(WristVerdict.PULSE_ABSENT))
        assertEquals(PulseView("○", "확인할 수 없음 · 착용을 확인하세요"), CarScreen.pulse(WristVerdict.CHECK_WEAR))
        assertEquals("착용 확인 안 됨", shown(w(worn = false)).wornText)
    }

    @Test fun hiddenReplacesHeartRateAndPulseButKeepsWearAndUpdate() {
        val b = CarScreen.bio(w(), 6_000, healthConsent = true, hideHr = true)
        assertNull(b.hr)
        assertEquals("휴대폰에서 숨김", b.hrText)
        assertEquals(PulseView(null, "휴대폰에서 숨김"), b.pulse)
        assertEquals("착용 중", b.wornText)
        assertEquals("5초 전", b.updated)
    }

    @Test fun unknownSettingHidesWithCheckingText() {
        val b = CarScreen.bio(w(), 0, healthConsent = true, hideHr = null)
        assertNull(b.hr)
        assertEquals("휴대폰 설정 확인 중", b.hrText)
        assertEquals("휴대폰 설정 확인 중", b.pulse.text)
        assertNull(b.pulse.glyph)
    }

    @Test fun noConsentAndNoValueTexts() {
        val nc = CarScreen.bio(w(hr = null), 0, healthConsent = false, hideHr = false)
        assertEquals("동의 전이라 보이지 않음", nc.hrText)
        assertEquals("동의 전이라 보이지 않음", nc.pulse.text)
        // 동의가 없으면 심박 값이 실려 와도 보이지 않는다
        assertNull(CarScreen.bio(w(hr = 80), 0, healthConsent = false, hideHr = false).hr)
        val nv = CarScreen.bio(w(hr = null), 0, healthConsent = true, hideHr = false)
        assertEquals("측정값 없음", nv.hrText)
        assertEquals("잡힘", nv.pulse.text) // 맥박 신호 판정은 그대로
    }

    @Test fun signalOlderThan30sErasesTheOldNumber() {
        assertEquals(72, shown(age = 30_000).hr)
        val b = shown(age = 30_001)
        assertTrue(b.noSignal)
        assertNull(b.hr)
        assertEquals("—", b.hrText)
        assertEquals("—", b.pulse.text)
        assertEquals("—", b.wornText)
        assertEquals("30초 넘게 받지 못함", b.updated)
        assertEquals(BioView.NO_SIGNAL, CarScreen.bio(null, null, true, false))
    }

    @Test fun lastUpdateStepsBy5s() {
        assertEquals("방금", CarScreen.updated(0))
        assertEquals("방금", CarScreen.updated(4_999))
        assertEquals("5초 전", CarScreen.updated(5_000))
        assertEquals("5초 전", CarScreen.updated(9_999))
        assertEquals("10초 전", CarScreen.updated(10_000))
        assertEquals("25초 전", CarScreen.updated(29_999))
        assertEquals("30초 전", CarScreen.updated(30_000))
        assertEquals("30초 넘게 받지 못함", CarScreen.updated(30_001))
    }

    @Test fun testTagForTestOrSimulatedValues() {
        assertFalse(shown().testTag)
        assertTrue(shown(w(test = true)).testTag)
        assertTrue(shown(w(source = WatchSource.SIMULATION)).testTag)
    }

    // ── 상태표 — 정차 ──

    @Test fun parkedConnectedHasTwoPanes() {
        val v = parked(shown())
        assertEquals("확인 중", v.line)
        assertEquals(3, v.rows.size)
        assertEquals(72, v.bio?.hr)
        assertEquals(TestEntryView("테스트 시작", true, null, TestAction.START), v.test)
        assertNull(v.driveBio)
    }

    @Test fun parkedServerLostShowsServerCauseNoSignalCardAndBlockedEntry() {
        val links = Links(phone = false, watch = WatchLink.NO_SIGNAL, server = false)
        val v = parked(BioView.NO_SIGNAL, state = FlowState.OFF, linkOk = false, links = links)
        assertEquals(CarTexts.STATUS_SERVER_LOST, v.line)
        assertEquals(CarTexts.STATUS_SERVER_LOST_SUB, v.sub)
        assertEquals(LinkRowView("서버", false, "끊김"), v.rows[2])
        assertTrue(v.bio!!.noSignal)
        assertEquals(TestEntryView("테스트 시작", false, CarTexts.TEST_NEED_SERVER, TestAction.START), v.test)
    }

    @Test fun parkedOffKeepsCardAndDimEntryWithReason() {
        val v = parked(shown(w(worn = false)), state = FlowState.OFF, links = both.copy(watch = WatchLink.NOT_WORN))
        assertEquals("워치를 착용하세요", v.line)
        assertEquals("착용 확인 안 됨", v.bio?.wornText)
        assertEquals(false, v.test?.enabled)
        assertEquals(CarTexts.TEST_NEED_WATCHING, v.test?.reason)
    }

    @Test fun parkedHiddenAndNoSignalRows() {
        assertEquals("휴대폰에서 숨김", parked(CarScreen.bio(w(), 0, true, true)).bio?.hrText)
        val ns = parked(BioView.NO_SIGNAL, state = FlowState.OFF, links = both.copy(watch = WatchLink.NO_SIGNAL))
        assertEquals("워치 신호를 기다리는 중", ns.line)
        assertEquals(LinkRowView("워치", false, "신호 없음"), ns.rows[1])
    }

    @Test fun parkedTestRunningOffersStop() {
        val e = CarScreen.testEntry(true, true, FlowState.S0, test = true, pending = null, nowMs = 0)
        assertEquals(TestEntryView("테스트 멈추기", true, null, TestAction.STOP), e)
        // 경보 중(S1 등)이어도 테스트 중이면 멈출 수 있다
        assertTrue(CarScreen.testEntry(true, true, FlowState.S1, test = true, pending = null, nowMs = 0).enabled)
    }

    // ── 상태표 — 주행 ──

    @Test fun drivingShowsOnlyStatusAndWatchLine() {
        val v = driving(shown())
        assertEquals("확인 중", v.line)
        assertEquals("워치 착용 · 심박 72", v.driveBio)
        assertTrue(v.rows.isEmpty())
        assertNull(v.bio)
        assertNull(v.test)
        assertFalse(v.newCode)
        assertNull(v.sub)
    }

    @Test fun drivingColumnUsesCausesOnly() {
        assertEquals("서버 연결 끊김", driving(BioView.NO_SIGNAL, linkOk = false).line)
        assertEquals("꺼짐", driving(shown(), FlowState.OFF, power = false).line)
        assertEquals("꺼짐 · 동의 필요", driving(shown(), FlowState.OFF, consents = false).line)
        assertEquals("확인 멈춤 · 워치 착용 안 됨", driving(shown(w(worn = false)), FlowState.OFF, links = both.copy(watch = WatchLink.NOT_WORN)).line)
    }

    @Test fun drivingWatchLineByCase() {
        assertEquals("워치 착용", driving(CarScreen.bio(w(), 0, true, hideHr = true)).driveBio)
        assertEquals("워치 착용", driving(CarScreen.bio(w(), 0, true, hideHr = null)).driveBio)
        assertEquals("워치 착용", driving(CarScreen.bio(w(), 0, healthConsent = false, hideHr = false)).driveBio)
        assertEquals("워치 착용", driving(CarScreen.bio(w(hr = null), 0, true, false)).driveBio)
        assertEquals("워치 착용 안 됨", driving(shown(w(worn = false))).driveBio)
        assertEquals("워치 신호 없음", driving(BioView.NO_SIGNAL).driveBio)
        assertEquals("워치 신호 없음", driving(BioView.NO_SIGNAL, linkOk = false).driveBio)
        // 테스트 중에도 값은 그대로
        assertEquals("워치 착용 · 심박 72", driving(shown(w(test = true))).driveBio)
    }

    @Test fun drivingWatchLineSkippedWhenStatusIsAlreadyAboutTheWatch() {
        val v = driving(BioView.NO_SIGNAL, FlowState.OFF, links = both.copy(watch = WatchLink.NO_SIGNAL))
        assertEquals("확인 멈춤 · 워치 신호 없음", v.line)
        assertNull(v.driveBio)
        assertNull(driving(shown(w(worn = false)), FlowState.OFF, links = both.copy(watch = WatchLink.NOT_WORN)).driveBio)
    }

    // ── 경보 · 화면 고르기 ──

    @Test fun alarmCoversEverythingElse() {
        assertEquals(CarPage.ALARM, CarScreen.page("응답이 필요합니다", true, true, null, both, setupOk = true))
        assertEquals(CarPage.ALARM, CarScreen.page("응답이 필요합니다", false, false, null, Links.NONE, setupOk = false))
        assertEquals(CarPage.RELAY_MISSING, CarScreen.page(null, false, true, null, both, true))
        assertEquals(CarPage.PAIRING, CarScreen.page(null, true, false, null, both, true))
        assertEquals(CarPage.PARKED, CarScreen.page(null, true, true, null, both, true))
        assertEquals(CarPage.DRIVING, CarScreen.page(null, true, true, null, both, false))
    }

    // ── 10초 묶음 ──

    @Test fun throttleChangesDriveLineOncePer10s() {
        val t = BioThrottle()
        assertEquals("워치 착용 · 심박 72", t.show(1_000, "워치 착용 · 심박 72")) // 주행 첫 순간은 바로
        assertEquals("워치 착용 · 심박 72", t.show(3_000, "워치 착용 · 심박 75"))
        assertEquals("워치 착용 · 심박 72", t.show(10_999, "워치 착용 · 심박 76"))
        assertEquals("워치 착용 · 심박 77", t.show(11_000, "워치 착용 · 심박 77"))
        assertEquals("워치 착용 · 심박 77", t.show(12_000, "워치 신호 없음"))
        assertEquals("워치 신호 없음", t.show(21_000, "워치 신호 없음"))
    }

    @Test fun throttleResetsWhenParkedAndClearsAtOnce() {
        val t = BioThrottle()
        t.show(0, "워치 착용 · 심박 72")
        t.reset()
        assertEquals("워치 착용 · 심박 90", t.show(2_000, "워치 착용 · 심박 90"))
        // 상태 줄이 워치 원인이 되면 같은 말을 두 번 보이지 않게 바로 지운다
        assertNull(t.show(3_000, null))
        assertEquals("워치 착용", t.show(4_000, "워치 착용"))
    }

    // ── 테스트 입구 ──

    @Test fun entryConditionsInOrder() {
        assertEquals(CarTexts.TEST_NEED_SERVER, CarScreen.testEntry(false, false, FlowState.OFF, false, null, 0).reason)
        assertEquals(CarTexts.TEST_NEED_PHONE, CarScreen.testEntry(true, false, FlowState.OFF, false, null, 0).reason)
        assertEquals(CarTexts.TEST_NEED_WATCHING, CarScreen.testEntry(true, true, FlowState.OFF, false, null, 0).reason)
        assertEquals(CarTexts.TEST_NEED_WATCHING, CarScreen.testEntry(true, true, FlowState.S1, false, null, 0).reason)
        assertTrue(CarScreen.testEntry(true, true, FlowState.S0, false, null, 0).enabled)
    }

    @Test fun entryWaits15sThenSaysPhoneDidNotAnswer() {
        val p = TestPending(TestAction.START, 1_000)
        val waiting = CarScreen.testEntry(true, true, FlowState.S0, false, p, 15_999)
        assertEquals(TestEntryView("테스트 시작", false, CarTexts.TEST_WAITING, TestAction.START), waiting)
        val late = CarScreen.testEntry(true, true, FlowState.S0, false, p, 16_000)
        assertEquals(TestEntryView("테스트 시작", true, CarTexts.TEST_NO_ANSWER, TestAction.START), late)
        // 테스트가 켜졌으면 시작 요청은 끝났고 입구는 멈추기
        assertEquals(TestEntryView("테스트 멈추기", true, null, TestAction.STOP), CarScreen.testEntry(true, true, FlowState.S0, true, p, 2_000))
    }

    @Test fun confirmDialogFoldsWhenDrivingOrBlocked() {
        val parkedView = parked(shown())
        assertTrue(parkedView.confirmVisible(open = true))
        assertFalse(parkedView.confirmVisible(open = false))
        // 주행으로 바뀌면 입구가 없어 창도 걷힌다
        assertFalse(driving(shown()).confirmVisible(open = true))
        // 요청을 보낸 뒤(기다리는 중) · 테스트 중(멈추기)에는 시작 창을 그리지 않는다
        val waiting = parkedView.copy(test = CarScreen.testEntry(true, true, FlowState.S0, false, TestPending(TestAction.START, 0), 1))
        assertFalse(waiting.confirmVisible(open = true))
        val running = parkedView.copy(test = CarScreen.testEntry(true, true, FlowState.S0, true, null, 0))
        assertFalse(running.confirmVisible(open = true))
    }

    @Test fun confirmAllowedFallsAsSoonAsEntryCloses() {
        // 누른 프레임에 서버 끊김 · 경보 · 테스트 켜짐으로 바뀌면 「열 수 있음」이 바로 거짓 — 화면은 이 값이 거짓이 될 때 연 창 표시를 지운다
        val open = parked(shown())
        assertTrue(CarScreen.confirmAllowed(CarPage.PARKED, open))
        val serverLost = open.copy(test = CarScreen.testEntry(false, true, FlowState.S0, false, null, 0))
        assertFalse(CarScreen.confirmAllowed(CarPage.PARKED, serverLost))
        assertFalse(CarScreen.confirmAllowed(CarPage.ALARM, open))
        val running = open.copy(test = CarScreen.testEntry(true, true, FlowState.S0, true, null, 0))
        assertFalse(CarScreen.confirmAllowed(CarPage.PARKED, running))
        assertFalse(CarScreen.confirmAllowed(CarPage.DRIVING, driving(shown())))
    }

    // ── 문구 ──

    @Test fun drivingTextsAskNothingOfTheDriver() {
        // 「…세요」(하세요 · 가세요 · 출발하세요 · 켜세요)는 모두 행동 요구다(2026-11-08 넓힘)
        fun asks(t: String) = t.contains("세요") || t.contains("동의가 필요")
        for (t in CarTexts.driving) {
            assertFalse("주행 문구에 행동 요구: $t", asks(t))
        }
        // 주행 열 실제 출력도 같은지
        val lines = listOf(
            driving(BioView.NO_SIGNAL, linkOk = false), driving(shown(), FlowState.OFF, power = false),
            driving(shown(), FlowState.OFF, consents = false), driving(BioView.NO_SIGNAL, FlowState.OFF, links = both.copy(watch = WatchLink.NO_SIGNAL)),
            driving(shown(w(worn = false)), FlowState.OFF, links = both.copy(watch = WatchLink.NOT_WORN)), driving(shown()),
        ).flatMap { listOfNotNull(it.line, it.driveBio) }
        for (t in lines) assertTrue("주행 출력 $t", t in CarTexts.driving || t.startsWith("워치 착용 · 심박 "))
        for (t in lines) assertFalse(t, asks(t))
        // 상태 기계 안내(공통 Phrase)가 주행 열 한 줄 자리에 실제로 나올 때도(리뷰 중3). 경보가 아닌 상태(OFF · S0 · S1R · S2)의 SHOW 문구 전부 —
        // S2 는 상태 기계가 정지를 확정했어도 주행 열 판정(setupOk)이 거짓일 수 있다(UX 제한 · 기어 D 정지)
        val notices = listOf(
            FlowState.OFF to Phrase.SCREEN_CHECK_WEAR, FlowState.S0 to Phrase.SCREEN_WATCH_NO_SIGNAL,
            FlowState.S1R to Phrase.SCREEN_WATCH_NO_SIGNAL, FlowState.S1R to Phrase.SCREEN_S1R, FlowState.S1R to Phrase.SCREEN_CHECK_WEAR,
            FlowState.S2 to Phrase.SCREEN_WATCH_NO_SIGNAL, FlowState.S2 to Phrase.SCREEN_S2, FlowState.S2 to Phrase.SCREEN_CHECK_WEAR,
        )
        val watches = listOf(WatchLink.NO_SIGNAL, WatchLink.NOT_WORN, WatchLink.WORN)
        for ((state, p) in notices) for (wl in watches) {
            val v = CarScreen.idle(true, state, true, true, both.copy(watch = wl), setupOk = false, notice = p.ko)
            for (t in listOfNotNull(v.line, v.driveBio)) assertFalse("주행 출력 $state $wl $t", asks(t))
        }
        // 기본 문구(상태 기계 SHOW 없이 상태만 바뀐 때 — 넘겨받음 등)도 같다
        for (state in FlowState.values().filter { it !in CarScreen.ALARM_STATES }) {
            val line = InCarExecutor.defaultLine(state)?.ko ?: continue
            val v = CarScreen.idle(true, state, true, true, both, setupOk = false, notice = line)
            assertFalse("주행 기본 문구 $state ${v.line}", asks(v.line))
        }
    }

    @Test fun everyNewTextIsInAllAndClean() {
        val news = listOf(
            CarTexts.STATUS_SERVER_LOST, CarTexts.DRIVE_OFF_WEAR, CarTexts.BIO_PULSE_UNREADABLE, CarTexts.BIO_HIDDEN,
            CarTexts.TEST_NO_ANSWER, CarTexts.TEST_CONFIRM_SCENARIO, CarTexts.WATCH_LINK_VIA_PHONE, CarTexts.TEST_BAND,
        )
        for (t in news) assertTrue(t, t in CarTexts.all)
        for (t in CarTexts.all) assertTrue("$t: ${ForbiddenWords.violations(t)}", ForbiddenWords.violations(t).isEmpty())
        assertTrue(CarTexts.TEST_CONFIRM_SCENARIO.contains("약 2분"))
    }
}
