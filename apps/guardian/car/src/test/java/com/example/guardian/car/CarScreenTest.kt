// 차 화면 상태 시험 — 번호 화면 조건 · 연결 줄 세 개 · 꺼짐 원인 우선순위 · 주행 중 숨김, 그리고 sync 응답에서 연결 상태를 읽는 허브
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.Via
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarScreenTest {
    private val both = Links(phone = true, watch = WatchLink.WORN, server = true)
    private val phoneOnly = Links(phone = true, watch = WatchLink.NO_SIGNAL, server = true)

    // ── 번호 화면 ──

    @Test fun pairingShownWithoutPair() {
        assertTrue(CarScreen.showPairing(paired = false, pairCode = null, links = Links.NONE))
        assertTrue(CarScreen.showPairing(paired = false, pairCode = "123456", links = Links.NONE))
    }

    @Test fun pairingStaysWhilePhoneOnlyAttached() {
        assertTrue(CarScreen.showPairing(paired = true, pairCode = "123456", links = phoneOnly))
        assertEquals("휴대폰 연결됨 · 워치 기다리는 중", CarScreen.pairingLine(phoneOnly))
    }

    @Test fun pairingStaysWhileWatchOnlyAttached() {
        val watchOnly = Links(phone = false, watch = WatchLink.NOT_WORN, server = true)
        assertTrue(CarScreen.showPairing(paired = true, pairCode = "123456", links = watchOnly))
        assertEquals("휴대폰 기다리는 중 · 워치 연결됨", CarScreen.pairingLine(watchOnly))
    }

    @Test fun bothAttachedLeavesPairingEvenWithLiveCode() {
        assertFalse(CarScreen.showPairing(paired = true, pairCode = "123456", links = both))
        // 착용 안 함이어도 워치 신호가 있으면 붙은 것
        assertFalse(CarScreen.showPairing(paired = true, pairCode = "123456", links = both.copy(watch = WatchLink.NOT_WORN)))
    }

    /** 리뷰 11-08 의심 — 워치가 붙어 `noSignal` 만 보내는 동안 번호 화면이 「워치 기다리는 중」으로 남지 않는다. */
    @Test fun watchSendingNoSignalIsAttachedNotWaiting() {
        val noSignal = Links(phone = false, watch = WatchLink.NO_SIGNAL, server = true, watchSendingNoSignal = true)
        assertEquals("휴대폰 기다리는 중 · 워치 연결됨 · 신호 없음", CarScreen.pairingLine(noSignal))
        assertFalse("휴대폰 · 워치 둘 다 붙음", CarScreen.showPairing(paired = true, pairCode = "123456", links = noSignal.copy(phone = true)))
    }

    @Test fun hubSeesAWatchThatSendsOnlyNoSignal() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.sync(0, watch(WristVerdict.NORMAL, noSignal = true))
        val links = r.hub.links(100)
        assertEquals(WatchLink.NO_SIGNAL, links.watch)
        assertTrue(links.watchSendingNoSignal)
        assertEquals("휴대폰 연결됨 · 워치 연결됨 · 신호 없음", CarScreen.pairingLine(links))
        assertFalse("값이 30초 넘게 낡으면 붙은 것으로 보지 않는다", r.hub.links(30_101).watchSendingNoSignal)
    }

    @Test fun expiredCodeLeavesPairingWhateverIsAttached() {
        assertFalse(CarScreen.showPairing(paired = true, pairCode = null, links = Links.NONE))
        assertFalse(CarScreen.showPairing(paired = true, pairCode = null, links = phoneOnly))
    }

    // ── 연결 줄 ──

    @Test fun linkRowsAllConnected() {
        assertEquals(
            listOf(
                LinkRowView("휴대폰", true, "연결됨"),
                LinkRowView("워치", true, "연결됨 · Wi-Fi 직접"),
                LinkRowView("서버", true, "연결됨"),
            ),
            CarScreen.linkRows(both, Via.WATCH),
        )
    }

    @Test fun watchRowShowsTheRouteAndLeavesWearToTheCard() {
        // 착용은 워치 칸이 맡으므로 착용 안 함이어도 신호가 있으면 연결됨(car-spec 3-1)
        assertEquals(LinkRowView("워치", true, "연결됨 · 휴대폰 경유"), CarScreen.linkRows(both.copy(watch = WatchLink.NOT_WORN), Via.PHONE)[1])
        assertEquals(LinkRowView("워치", true, "연결됨"), CarScreen.linkRows(both, via = null)[1])
        assertEquals(LinkRowView("워치", false, "신호 없음"), CarScreen.linkRows(both.copy(watch = WatchLink.NO_SIGNAL), Via.WATCH)[1])
    }

    @Test fun linkRowsPhoneAndServerLost() {
        val rows = CarScreen.linkRows(Links.NONE)
        assertEquals(listOf("휴대폰", "워치", "서버"), rows.map { it.name })
        assertEquals(LinkRowView("휴대폰", false, "끊김"), rows[0])
        assertEquals(LinkRowView("서버", false, "끊김"), rows[2])
        // 서버는 붙고 휴대폰만 끊김
        val serverOnly = CarScreen.linkRows(Links(phone = false, watch = WatchLink.WORN, server = true))
        assertEquals(listOf(false, true, true), serverOnly.map { it.ok })
    }

    // ── 꺼짐 원인 ──

    @Test fun offReasonsInPriorityOrder() {
        // 1. 휴대폰 꺼짐이 다른 무엇보다 먼저
        assertEquals(CarTexts.STATUS_OFF, CarScreen.offLine(powerOn = false, consentsAll = false, watch = WatchLink.NO_SIGNAL))
        assertEquals("꺼짐 · 휴대폰 앱에서 켜세요", CarScreen.offLine(false, true, WatchLink.WORN))
        // 2. 동의 부족이 워치보다 먼저
        assertEquals("휴대폰 앱에서 동의가 필요합니다", CarScreen.offLine(true, false, WatchLink.NO_SIGNAL))
        assertEquals("휴대폰 앱에서 동의가 필요합니다", CarScreen.offLine(true, false, WatchLink.NOT_WORN))
        // 3. 워치 신호 없음이 착용보다 먼저
        assertEquals("워치 신호를 기다리는 중", CarScreen.offLine(true, true, WatchLink.NO_SIGNAL))
        // 4. 착용 안 함
        assertEquals("워치를 착용하세요", CarScreen.offLine(true, true, WatchLink.NOT_WORN))
        // 다 맞으면 다음 입력에 S0 — 확인 중
        assertEquals(CarTexts.STATUS_WATCHING, CarScreen.offLine(true, true, WatchLink.WORN))
    }

    @Test fun idleLineUsesOffReasonOnlyWhenOffAndLinked() {
        assertEquals("워치 신호를 기다리는 중", CarScreen.idle(true, FlowState.OFF, true, true, phoneOnly, setupOk = true).line)
        assertEquals(CarTexts.STATUS_WATCHING, CarScreen.idle(true, FlowState.S0, true, true, phoneOnly, setupOk = true).line)
        val lost = CarScreen.idle(false, FlowState.OFF, false, true, Links.NONE, setupOk = true)
        assertEquals("서버 연결 끊김 · 다시 연결하는 중", lost.line)
        assertEquals("끊긴 동안은 차 화면과 음성으로만 안내합니다", lost.sub)
    }

    // ── 정차 · 주행 ──

    @Test fun parkedShowsRowsAndSmallButton() {
        val v = CarScreen.idle(true, FlowState.S0, true, true, both, setupOk = true)
        assertEquals(3, v.rows.size)
        assertTrue(v.newCode)
        assertNull(v.code)
    }

    @Test fun drivingHidesRowsButtonAndCode() {
        val v = CarScreen.idle(true, FlowState.OFF, true, true, phoneOnly, setupOk = false, pairCode = "123456")
        assertEquals("확인 멈춤 · 워치 신호 없음", v.line)
        assertTrue(v.rows.isEmpty())
        assertFalse(v.newCode)
        assertNull(v.code)
    }

    @Test fun liveCodeWithBothAttachedReplacesTheButtonWhileParked() {
        val v = CarScreen.idle(true, FlowState.S0, true, true, both, setupOk = true, pairCode = "123456")
        assertEquals("123456", v.code)
        assertFalse(v.newCode)
    }

    // ── 허브가 sync 응답에서 읽는 연결 상태 ──

    @Test fun hubReadsPhoneWatchServerFromSync() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        assertEquals(Links.NONE, r.hub.links(0))
        r.sync(0, watch(WristVerdict.NORMAL, ageMs = 2_000), peerAgeMs = 1_000)
        assertEquals(Links(phone = true, watch = WatchLink.WORN, server = true), r.hub.links(100))
    }

    @Test fun hubWatchCheckWearIsNotWorn() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.sync(0, watch(WristVerdict.CHECK_WEAR))
        assertEquals(WatchLink.NOT_WORN, r.hub.links(100).watch)
        assertEquals(FlowState.OFF, r.hub.snapshot.state)
    }

    @Test fun hubPhoneOnFreshWatchMissingIsOffWaitingForWatch() {
        // 실측(11-01) 재현 — 휴대폰 켜짐 · 동의 전부인데 워치 신호가 없어 OFF
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.sync(0, watch = null)
        val links = r.hub.links(100)
        assertEquals(Links(phone = true, watch = WatchLink.NO_SIGNAL, server = true), links)
        val s = r.hub.snapshot
        assertEquals(FlowState.OFF, s.state)
        assertEquals("워치 신호를 기다리는 중", CarScreen.idle(true, s.state, s.powerOn, s.consents.all, links, true).line)
    }

    @Test fun hubAgesOutPhoneAfter10sAndWatchAfter30sWithLocalTime() {
        val r = Rig(skew = 5_000)
        r.hub.onReading(PARKED, 0)
        r.sync(0, watch(WristVerdict.NORMAL, ageMs = 10_000), peerAgeMs = 4_000)
        // 휴대폰 — 응답 때 이미 4초 + 받은 뒤 6초 = 10초까지 연결됨
        assertTrue(r.hub.links(6_100).phone)
        assertFalse(r.hub.links(6_101).phone)
        // 워치 — 응답 때 10초 + 받은 뒤 20초 = 30초까지 신호 있음
        assertEquals(WatchLink.WORN, r.hub.links(20_100).watch)
        assertEquals(WatchLink.NO_SIGNAL, r.hub.links(20_101).watch)
    }

    @Test fun hubStaleWatchAndStalePhoneAreNotConnected() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.sync(0, watch(WristVerdict.NORMAL, ageMs = 30_001), peerAgeMs = 10_001)
        val links = r.hub.links(100)
        assertFalse(links.phone)
        assertEquals(WatchLink.NO_SIGNAL, links.watch)
        assertTrue(links.server)
    }

    @Test fun hubServerLostAfter10sWithoutSync() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.sync(0, watch(WristVerdict.NORMAL))
        assertTrue(r.hub.links(10_100).server)
        assertFalse(r.hub.links(10_101).server)
    }

    // ── 차속 권한 이유(G-2) · 에어백 상세 안내(F-2) ──

    @Test fun speedReasonBeforeAskAndResultAfterDenial() {
        assertEquals("권한 창 직전 — 이유", CarTexts.SPEED_REASON, CarScreen.speedLine(granted = false, asked = false))
        assertEquals("거부 뒤 — 결과", CarTexts.SPEED_DENIED, CarScreen.speedLine(granted = false, asked = true))
        assertNull("받았으면 줄 없음", CarScreen.speedLine(granted = true, asked = true))
        assertNull(CarScreen.speedLine(granted = true, asked = false))
    }

    @Test fun permissionAndAirbagLinesStayOutOfDrivingAndPromiseNoSos() {
        for (t in listOf(CarTexts.SPEED_REASON, CarTexts.SPEED_DENIED, CarTexts.AIRBAG_UNREADABLE)) {
            assertFalse("주행 열에 두지 않음 $t", t in CarTexts.driving)
            assertTrue(t in CarTexts.all)
            for (w in listOf("SOS", "119", "자동", "구조", "출동")) assertFalse("G7 $t", w in t)
        }
    }

    // ── 보호자 문자 결과 줄(A-5) ──

    /** 확인되면 경보 화면 · 정차 화면 모두 「보호자에게 알림 보냄」, 보내지 않음은 정차 열에만, 결과 모름은 줄 없음. */
    @Test fun smsResultLineRules() {
        assertEquals(Phrase.SCREEN_SMS_CONFIRMED.ko, CarScreen.smsLine(SmsStatus.CONFIRMED, alarm = true))
        assertEquals(Phrase.SCREEN_SMS_CONFIRMED.ko, CarScreen.smsLine(SmsStatus.CONFIRMED, alarm = false))
        assertNull("경보 화면에는 실패 줄을 두지 않는다", CarScreen.smsLine(SmsStatus.NOT_SENT, alarm = true))
        assertEquals(Phrase.SCREEN_SMS_NOT_SENT.ko, CarScreen.smsLine(SmsStatus.NOT_SENT, alarm = false))
        assertNull(CarScreen.smsLine(null, alarm = false))
    }
}
