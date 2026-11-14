// 경보와 안내를 가르는 화면 고르기 시험 — 진짜 경보(S1 · AP · S3 · S4 · S5)만 다른 화면을 덮고, 감시 · 응답 뒤 안내는 상태 한 줄로 내린다(베타 2일차 11-02 결함)
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarAlarmNoticeTest {
    private val phoneOnly = Links(phone = true, watch = WatchLink.NO_SIGNAL, server = true)
    private val none = Links.NONE
    private val both = Links(phone = true, watch = WatchLink.WORN, server = true)

    private fun view(line: String?, test: Boolean = false) = ScreenView(line, null, test)

    private fun page(state: FlowState, screen: ScreenView, paired: Boolean, code: String?, links: Links, setupOk: Boolean = true) =
        CarScreen.page(CarScreen.alarmLine(state, screen), true, paired, code, links, setupOk)

    // ── 베타 재현 ──

    @Test fun watchNoSignalNoticeInS0DoesNotCoverThePairingCode() {
        val notice = view(Phrase.SCREEN_WATCH_NO_SIGNAL.ko)
        assertNull(CarScreen.alarmLine(FlowState.S0, notice))
        assertEquals(CarPage.PAIRING, page(FlowState.S0, notice, paired = true, code = "123456", links = none))
        assertEquals(CarPage.PAIRING, page(FlowState.S0, notice, paired = false, code = null, links = none))
        assertEquals(CarPage.PAIRING, page(FlowState.S0, notice, paired = true, code = "123456", links = phoneOnly))
    }

    @Test fun realAlarmStillCoversThePairingCode() {
        for (s in listOf(FlowState.S1, FlowState.AP, FlowState.S3, FlowState.S4, FlowState.S5)) {
            val line = InCarExecutor.defaultLine(s)!!.ko
            assertEquals(line, CarScreen.alarmLine(s, view(line)))
            assertEquals("$s", CarPage.ALARM, page(s, view(line), paired = true, code = "123456", links = none))
            assertEquals("$s 주행", CarPage.ALARM, page(s, view(line), paired = false, code = null, links = none, setupOk = false))
        }
    }

    @Test fun testAlarmIsStillAnAlarm() {
        val line = Phrase.SCREEN_S1.ko
        assertEquals(CarPage.ALARM, page(FlowState.S1, view(line, test = true), paired = true, code = null, links = both))
    }

    @Test fun noticesInOffS0S1RS2AreNotAlarms() {
        val cases = listOf(
            FlowState.OFF to Phrase.SCREEN_CHECK_WEAR.ko,
            FlowState.S0 to Phrase.SCREEN_WATCH_NO_SIGNAL.ko,
            FlowState.S1R to Phrase.SCREEN_S1R.ko,
            FlowState.S1R to Phrase.SCREEN_WATCH_NO_SIGNAL.ko,
            FlowState.S2 to Phrase.SCREEN_S2.ko,
            FlowState.S2 to Phrase.SCREEN_WATCH_NO_SIGNAL.ko,
        )
        for ((s, line) in cases) {
            assertNull("$s", CarScreen.alarmLine(s, view(line)))
            assertEquals("$s", line, CarScreen.noticeLine(s, view(line)))
            assertEquals("$s", CarPage.PARKED, page(s, view(line), paired = true, code = null, links = both))
            assertEquals("$s", CarPage.DRIVING, page(s, view(line), paired = true, code = null, links = both, setupOk = false))
        }
        assertNull(CarScreen.noticeLine(FlowState.S1, view(Phrase.SCREEN_S1.ko)))
    }

    // ── 안내는 상태 한 줄 자리에 ──

    @Test fun s1rNoticeIsTheParkedStatusLine() {
        val v = CarScreen.idle(true, FlowState.S1R, true, true, both, setupOk = true, notice = Phrase.SCREEN_S1R.ko)
        assertEquals(Phrase.SCREEN_S1R.ko, v.line)
        assertEquals(3, v.rows.size)
    }

    /** 리뷰 중3 — 주행 중에는 워치 안내(「…확인하세요」)를 원인 문구(car-spec §7-1 주행 열)로 접고, 워치 한 줄은 되풀이하지 않는다. */
    @Test fun noticeIsTheDrivingLineAndTheWatchLineIsNotRepeated() {
        val v = CarScreen.idle(true, FlowState.S0, true, true, phoneOnly, setupOk = false, notice = Phrase.SCREEN_WATCH_NO_SIGNAL.ko)
        assertEquals(CarTexts.DRIVE_OFF_WATCH_WAIT, v.line)
        assertNull(v.driveBio)
        val wear = CarScreen.idle(true, FlowState.OFF, true, true, both.copy(watch = WatchLink.NOT_WORN), setupOk = false, notice = Phrase.SCREEN_CHECK_WEAR.ko)
        assertEquals(CarTexts.DRIVE_OFF_WEAR, wear.line)
        assertNull(wear.driveBio)
        assertEquals(emptyList<LinkRowView>(), v.rows)
        // 응답 뒤 안내(「쉬어 가세요」 · 「다시 출발하세요」)도 주행 중에는 행동 요구 없는 상태 문구로 접는다(PM 결정 2026-11-08)
        val s1r = CarScreen.idle(true, FlowState.S1R, true, true, both, setupOk = false, notice = Phrase.SCREEN_S1R.ko)
        assertEquals(CarTexts.DRIVE_S1R, s1r.line)
        assertEquals(CarTexts.DRIVE_BIO_NO_SIGNAL, s1r.driveBio)
        val s2 = CarScreen.idle(true, FlowState.S2, true, true, both, setupOk = false, notice = Phrase.SCREEN_S2.ko)
        assertEquals(CarTexts.DRIVE_S2, s2.line)
    }

    /** 정차 중에는 안내를 그대로 둔다(행동 요구는 정차 열에만, car-spec §7-1). */
    @Test fun parkedKeepsTheWatchNotices() {
        val v = CarScreen.idle(true, FlowState.S0, true, true, phoneOnly, setupOk = true, notice = Phrase.SCREEN_WATCH_NO_SIGNAL.ko)
        assertEquals(Phrase.SCREEN_WATCH_NO_SIGNAL.ko, v.line)
        val wear = CarScreen.idle(true, FlowState.OFF, true, true, both.copy(watch = WatchLink.NOT_WORN), setupOk = true, notice = Phrase.SCREEN_CHECK_WEAR.ko)
        assertEquals(Phrase.SCREEN_CHECK_WEAR.ko, wear.line)
        assertEquals(Phrase.SCREEN_S1R.ko, CarScreen.idle(true, FlowState.S1R, true, true, both, setupOk = true, notice = Phrase.SCREEN_S1R.ko).line)
        assertEquals(Phrase.SCREEN_S2.ko, CarScreen.idle(true, FlowState.S2, true, true, both, setupOk = true, notice = Phrase.SCREEN_S2.ko).line)
    }

    /** 허브에서 끝까지 — 주행 중 워치를 벗거나(착용 확인) 30초 끊기면(신호 없음) 화면 한 줄은 원인만. */
    @Test fun hubDrivingWatchNoticesFoldToCauses() {
        fun line(r: Rig, t: Long): String {
            val s = r.hub.snapshot
            return CarScreen.idle(
                r.hub.carIsOwner(t), s.state, s.powerOn, s.consents.all, r.hub.links(t), r.hub.setupOk(),
                notice = CarScreen.noticeLine(s.state, r.hub.screen()),
            ).line
        }
        val off = Rig()
        off.arm(MOVING)
        off.hub.onReading(MOVING, 2_000)
        off.sync(2_000, watch(WristVerdict.CHECK_WEAR))
        // 감시 중 미착용은 10초 이어져야 꺼짐 + 착용 확인(합동 점검 B-6)
        off.hub.onReading(MOVING, 12_000)
        off.sync(12_000, watch(WristVerdict.CHECK_WEAR))
        assertEquals(Phrase.SCREEN_CHECK_WEAR.ko, off.hub.screen().line)
        assertEquals(CarTexts.DRIVE_OFF_WEAR, line(off, 12_200))

        val stale = Rig()
        stale.arm(MOVING)
        for (t in 2_000L..32_000L step 2_000) { stale.hub.onReading(MOVING, t); stale.sync(t, null) }
        assertEquals(Phrase.SCREEN_WATCH_NO_SIGNAL.ko, stale.hub.screen().line)
        assertEquals(CarTexts.DRIVE_OFF_WATCH_WAIT, line(stale, 32_200))
    }

    @Test fun serverLostLineStaysFirst() {
        val v = CarScreen.idle(false, FlowState.S0, true, true, none, setupOk = true, notice = Phrase.SCREEN_WATCH_NO_SIGNAL.ko)
        assertEquals(CarTexts.STATUS_SERVER_LOST, v.line)
    }

    // ── 허브에서 끝까지 ──

    /** 짝이 풀려 워치 값이 끊긴 S0 — 허브가 「워치 신호 없음」 안내를 내도 화면은 번호 화면이다. */
    @Test fun hubS0WithStaleWatchShowsPairingNotAlarm() {
        val r = Rig()
        r.arm(PARKED)
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        r.hub.onReading(PARKED, 32_000)
        val screen = r.hub.screen()
        assertEquals(Phrase.SCREEN_WATCH_NO_SIGNAL.ko, screen.line)
        assertEquals(CarPage.PAIRING, CarScreen.page(CarScreen.alarmLine(r.hub.snapshot.state, screen), true, false, null, r.hub.links(32_000), true))
    }

    @Test fun hubS1AlarmCoversPairing() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_ABSENT))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        val screen = r.hub.screen()
        assertEquals(CarPage.ALARM, CarScreen.page(CarScreen.alarmLine(r.hub.snapshot.state, screen), true, false, null, Links.NONE, false))
    }
}
