// 베타 대본 B1~B6 · 사고를 가상 시간으로 돌리는 세 기기 통합 시나리오 — 차 · 휴대폰 · 워치 앱 로직이 가짜 중계로 계약대로 맞물리는지 본다
package com.example.guardian.integration

import com.example.guardian.phone.core.TestStart
import com.example.guardian.phone.core.Withdrawal
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.text.Phrase
import com.example.guardian.shared.text.SmsText
import com.example.guardian.watch.core.WatchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioTest : Stage() {

    private val alarmStates = setOf(FlowState.S1, FlowState.AP, FlowState.S3, FlowState.S4)
    private val cancelHead = SmsText.CANCEL_BODY.substringBefore("%")

    /** 주행 중이면 늘 지켜야 할 것(G5 · G4) — 차 화면 버튼 0 · 응급실 검색 없음, 휴대폰 정지 아님(경보 화면 버튼 0) · 119 작성 · P12 없음. */
    private fun drivingInvariants(t: Trio) {
        world.check {
            if (t.car.reading.speedMps?.let { it > 0f } == true && t.car.hub.snapshot.stopped.not()) {
                val screen = t.car.hub.screen()
                assertNull("주행 중 차 화면 버튼(${world.elapsed / 1000}s ${t.car.state()})", screen.button)
                assertFalse("주행 중 휴대폰이 정지로 봄 — 경보 화면 「괜찮아요」가 보인다", t.phone.status().stopped)
                assertTrue("주행 중 119 작성 화면", t.phone.log.composeOpened.isEmpty())
            }
        }
    }

    private fun drive(t: Trio, kmh: Double = 60.0) { t.car.reading = CarDevice.driving(kmh); t.phone.gpsKmh = kmh }

    private fun park(t: Trio) { t.car.reading = CarDevice.parked(); t.phone.gpsKmh = 0.3 }

    private fun guardianSms(t: Trio) = t.phone.log.sms.map { it.body }

    // ── B1 ──

    /** B1 휴대폰 단독 테스트 — 중계 없음. 경보 1 → 2(알람) → 무응답, 확인 뒤 [테스트] 문자 한 통, 119 미리보기만, 휴대폰 밖으로 아무것도 안 나감. */
    @Test
    fun b1_phoneOnlyTest() {
        auditNeedsWire = false
        val p = PhoneDevice(world)
        p.install()
        world.advance(4_000)
        assertEquals(TestStart.LOCAL, p.engine.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = p.clock.mono()))
        world.runUntil(120_000, "휴대폰 시험 흐름 S4") { p.status().testFlowState == FlowState.S4 }
        world.advance(6_000)
        assertTrue("경보 2 알람", p.log.alarms.any { it.startsWith("start") && it.contains(" 2 ") })
        assertEquals("확인 대화 한 번", 1, p.log.asked.size)
        assertEquals("보호자 문자 한 통", 1, p.log.sms.size)
        assertEquals(Fixture.GUARDIAN_DIGITS, p.log.sms[0].to)
        assertTrue(p.log.sms[0].body.startsWith(SmsText.TEST_PREFIX))
        assertTrue("119 미리보기", p.log.previews.isNotEmpty())
        assertTrue("119 작성 화면 없음", p.log.composeOpened.isEmpty())
        assertTrue("119 미리보기는 다시 열어도 미리보기만", p.engine.openCompose(p.clock.mono()) && p.log.composeOpened.isEmpty())
        assertTrue("중계로 나간 것 없음", world.relay.wire.isEmpty())
    }

    // ── B2 ──

    /**
     * B2 워치 시나리오 · 차 없음(휴대폰 주인). 워치 경보 1 · 2, 휴대폰 알람, 휴대폰 GPS 로 정지(1 km/h 이하 5초) 뒤 S4,
     * 테스트 문자 한 통 · 「차 상태 없음」, 119 미리보기만.
     */
    @Test
    fun b2_watchScenarioNoCar() {
        val t = setup(car = false)
        armPhone(t)
        assertEquals(Owner.PHONE, t.phone.engine.owner)
        assertEquals(TestStart.WATCH, t.phone.engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = t.phone.clock.mono()))
        t.phone.gpsKmh = 15.0 // 시작한 뒤 움직이기 시작 — 경보는 주행(S1 → S3)으로
        world.runUntil(90_000, "휴대폰 흐름 S3") { t.phone.status().flowState == FlowState.S3 }
        val levels = t.watch.actions.mapNotNull { (it.second as? WatchAction.StartAlert)?.alert?.level }
        assertTrue("워치 경보 1 · 2 $levels", 1 in levels && 2 in levels)
        assertTrue("휴대폰 알람", t.phone.log.alarmOn)
        t.phone.gpsKmh = 0.8 // GPS 정지 흔들림(1 km/h 이하)
        val took = world.runUntil(20_000, "휴대폰 흐름 S4") { t.phone.status().flowState == FlowState.S4 }
        assertTrue("정지 디바운스 5초 이상($took ms)", took >= 5_000)
        world.advance(6_000)
        assertEquals("테스트 문자 한 통", 1, t.phone.log.sms.size)
        val body = t.phone.log.sms[0].body
        assertTrue(body.startsWith(SmsText.TEST_PREFIX))
        assertTrue("차 상태 없음 줄", body.contains(SmsText.CAR_STATE_MISSING))
        assertTrue("119 미리보기", t.phone.log.previews.isNotEmpty())
        assertTrue("119 작성 없음", t.phone.log.composeOpened.isEmpty())
    }

    // ── B3 ──

    /** 대본 B3 은 「D · 60 에서 워치 시나리오 시작」이지만 휴대폰은 정차 중에만 테스트를 시작한다(G5) — 대본을 고쳐야 한다. */
    @Test
    fun b3_testCannotStartWhileDriving() {
        val t = setup()
        armCar(t)
        drive(t)
        world.advance(8_000)
        assertEquals(TestStart.NOT_STOPPED, t.phone.engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = t.phone.clock.mono()))
    }

    /**
     * B3 차 주인 전체 흐름(테스트) — 정차 중 시작 → 곧 D · 60 → 주행 중 경보(차 버튼 0 · 휴대폰 경보 버튼 0) → 경보 2 에서 정차 →
     * 차 버튼 하나 → 무응답 S4 → 휴대폰이 조립한 보호자 문자 한 통(G9 순서) · 119 미리보기(사람이 눌러도 작성 화면 없음 · 테스트).
     */
    @Test
    fun b3_carOwnerFullFlow_test() {
        val t = setup()
        armCar(t)
        drivingInvariants(t)
        assertEquals(TestStart.WATCH, t.phone.engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = t.phone.clock.mono()))
        drive(t)
        world.runUntil(60_000, "차 S1(주행 중 경보)") { t.car.state() == FlowState.S1 }
        assertTrue("테스트 사건", t.car.hub.snapshot.test)
        assertTrue(t.car.ports.spoken.contains(Phrase.VOICE_S1_PULL_OVER.ko))
        world.runUntil(20_000, "경보 2") { t.car.hub.snapshot.stage == 2 }
        world.advance(3_000)
        assertTrue("휴대폰 알람", t.phone.log.alarmOn)
        park(t)
        world.runUntil(10_000, "차 AP") { t.car.state() == FlowState.AP }
        assertEquals("정차 뒤 차 버튼 하나", Phrase.BUTTON_OK.ko, t.car.hub.screen().button)
        world.runUntil(20_000, "차 S4") { t.car.state() == FlowState.S4 }
        world.advance(6_000)
        assertTrue("응급실 검색(정지 뒤)", t.car.ports.searches.contains(Phrase.SEARCH_EMERGENCY_ROOM.ko))
        assertEquals("보호자 문자 한 통", 1, t.phone.log.sms.size)
        assertGuardianBody(t.phone.log.sms[0].body, test = true)
        assertTrue("119 미리보기(테스트)", t.phone.log.previews.isNotEmpty())
        t.phone.engine.openCompose(t.phone.clock.mono())
        assertTrue("테스트는 작성 화면을 열지 않는다", t.phone.log.composeOpened.isEmpty())
        assertTrue("앰비언트 기본 꺼짐", t.car.ports.ambient.isEmpty())
    }

    /** B3 실제 사건(실제 센서가 맥박을 잃음) — 문자 자동 한 통(확인 대화 없음), 휴대폰 P12, 119 작성 화면은 사람이 누를 때만 · 정지 때만. */
    @Test
    fun b3_carOwnerFullFlow_real() {
        val t = setup()
        armCar(t)
        drivingInvariants(t)
        drive(t)
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 5_000)
        world.runUntil(60_000, "차 S1") { t.car.state() == FlowState.S1 }
        assertFalse("실제 사건", t.car.hub.snapshot.test)
        world.runUntil(20_000, "경보 2") { t.car.hub.snapshot.stage == 2 }
        park(t)
        world.runUntil(30_000, "차 S4") { t.car.state() == FlowState.S4 }
        world.advance(6_000)
        assertTrue("확인 대화 없음", t.phone.log.asked.isEmpty())
        assertEquals("보호자 문자 한 통", 1, t.phone.log.sms.size)
        assertGuardianBody(t.phone.log.sms[0].body, test = false)
        assertEquals("P12 한 번", 1, t.phone.log.noResponse.size)
        assertTrue("누르기 전 작성 화면 없음", t.phone.log.composeOpened.isEmpty())
        assertTrue(t.phone.engine.openCompose(t.phone.clock.mono()))
        assertEquals("누르면 작성 화면 한 번", 1, t.phone.log.composeOpened.size)
        assertTrue("작성 본문 머리", t.phone.log.composeOpened[0].startsWith(SmsText.HEADER_119))
        assertTrue("알람 소리만 줄임", t.phone.log.alarms.last() == "mute")
    }

    /**
     * 공통 하4 — 휴대폰 GPS 가 없으면 차 내비 위치로 문자 위치를 채운다. 잰 시각은 계약 `vehicle.naviAt`(중계 시각)으로 넘어가
     * 휴대폰이 30초 신선도를 본다 — 신선하면 좌표 · 주소, 30초가 넘었으면 「위치 확인 안 됨 · 직접 입력 필요」.
     */
    @Test
    fun freshNaviLocationFillsTheGuardianSms() {
        val lines = naviOnlySms(naviAgeMs = 4_000)
        assertTrue("내비 좌표 첫 줄: ${lines[0]}", lines[0].contains("37.56650,126.97800"))
        assertTrue("내비 주소 위치 줄: ${lines[1]}", lines[1].startsWith("${SmsText.LOCATION}: 서울 중구 세종대로 110 ${SmsText.LOCATION_NEAR} · "))
    }

    @Test
    fun staleNaviLocationIsNotUsed() {
        val lines = naviOnlySms(naviAgeMs = 40_000)
        assertEquals("${SmsText.HEADER_GUARDIAN} ${SmsText.NO_RESPONSE_DRIVER}", lines[0])
        assertTrue("낡은 내비 위치: ${lines[1]}", lines[1].startsWith("${SmsText.LOCATION}: ${SmsText.LOCATION_UNKNOWN} · "))
    }

    /** 휴대폰 GPS 없이(차 내비만) 실제 사건을 무응답까지 돌려 첫 보호자 문자 줄을 돌려준다. */
    private fun naviOnlySms(naviAgeMs: Long): List<String> {
        val t = setup()
        t.phone.gpsKmh = null
        t.car.naviAgeMs = naviAgeMs
        armCar(t)
        t.car.reading = CarDevice.driving()
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 5_000)
        world.runUntil(60_000, "차 S1") { t.car.state() == FlowState.S1 }
        world.runUntil(60_000, "보호자 문자") { t.phone.log.sms.isNotEmpty() }
        return t.phone.log.sms[0].body.lines()
    }

    /** G9 순서 — 첫 줄 머리 + 좌표(위치 맨 앞), 위치 줄, 상태 줄, …, 보호자 줄, 119 전화 안내. 등록 정보는 휴대폰 안에서만 붙는다. */
    private fun assertGuardianBody(body: String, test: Boolean) {
        val lines = body.lines()
        val first = if (test) "${SmsText.TEST_PREFIX} ${SmsText.HEADER_GUARDIAN} " else "${SmsText.HEADER_GUARDIAN} "
        assertTrue("첫 줄 머리 · 좌표: ${lines[0]}", lines[0].startsWith(first + "37.56700,126.97900"))
        var i = 1
        if (test) assertEquals(SmsText.TEST_NOTICE, lines[i++])
        assertTrue("위치 줄이 본문 맨 앞: ${lines[i]}", lines[i].startsWith("${SmsText.LOCATION}: "))
        assertTrue("상태 줄: ${lines[i + 1]}", lines[i + 1].startsWith("${SmsText.STATE}: "))
        val g = lines.indexOfFirst { it.startsWith("${SmsText.GUARDIAN}: ") }
        assertTrue("보호자 줄", g > i)
        assertEquals("${SmsText.GUARDIAN}: ${Fixture.GUARDIAN_NAME} ${Fixture.GUARDIAN_DIGITS}", lines[g])
        assertEquals(SmsText.GUARDIAN_CALL_119, lines[g + 1])
        assertTrue("운전자 줄", lines.any { it.startsWith("${SmsText.DRIVER}: ${Fixture.NAME}") })
        assertTrue("차량 줄 — 차 상태의 차종 + 휴대폰 등록 색 · 번호", lines.any { it.contains("그랜저") && it.contains(Fixture.PLATE) })
    }

    // ── B4 ──

    /**
     * B4 경보 중 워치 응답 → 세 기기 경보가 멈추고, 나쁜 신호가 이어지면 30초 뒤 새 사건으로 다시 경보(실제 재경보 규칙),
     * 이후 60초 동안 응답 시각 흔들림으로 경보가 저절로 꺼지지 않는다.
     * 테스트 시나리오는 응답 뒤 정상 신호가 되어(PM 결정 2026-11-08) 재경보가 나지 않으므로 실제 신호(실제 사건)로 본다.
     * 워치 값이 직접 경로와 휴대폰 Data Layer 두 길로 함께 오게 해 고르는 규칙도 같이 흔든다.
     */
    @Test
    fun b4_watchResponseStopsAll_noSelfClear() {
        val t = setup(dataLayer = true)
        armCar(t)
        drive(t)
        // 사건을 20초 뒤에 둬야 맥박 신호 잃음(PULSE_ABSENT)까지 가서 머문다 — 응답 뒤 나쁜 판정이 이어지는 실제 재경보 경로
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 20_000)
        world.runUntil(60_000, "차 경보 2") { t.car.state() == FlowState.S1 && t.car.hub.snapshot.stage == 2 }
        assertFalse("실제 사건", t.car.hub.snapshot.test)
        world.advance(3_000)
        assertTrue(t.watch.alerting && t.phone.log.alarmOn)
        val firstEvent = t.car.hub.snapshot.facts.eventId
        t.watch.pressOk()
        world.runUntil(6_000, "차 S1R") { t.car.state() == FlowState.S1R }
        world.runUntil(6_000, "워치 · 휴대폰 경보 멈춤") { !t.watch.alerting && !t.phone.log.alarmOn }
        assertTrue("차 음성 S1R", t.car.ports.spoken.contains(Phrase.VOICE_S1R_REST.ko))
        assertTrue("문자 전 응답 — 취소 문자 없음", guardianSms(t).none { it.contains(cancelHead) })
        // 나쁜 판정이 이어지면 30초 뒤 새 사건으로 다시 경보 — 그 뒤 60초 동안 저절로 꺼지지 않아야 한다
        world.runUntil(40_000, "새 사건 경보") { t.car.state() in alarmStates }
        val second = t.car.hub.snapshot.facts.eventId
        assertNotNull(second)
        assertTrue("새 사건 id", second != firstEvent)
        assertFalse("재경보도 실제 사건", t.car.hub.snapshot.test)
        repeat(60) {
            world.advance(1_000)
            assertTrue("응답 없이 경보가 꺼짐(${world.elapsed / 1000}s ${t.car.state()})", t.car.state() in alarmStates)
        }
    }

    /** B4' 정차 중 휴대폰 화면 응답(차 주인) → sync responseAt → 차 상태 기계가 PHONE_SCREEN 응답으로 받아 S2, 세 기기 경보 멈춤. */
    @Test
    fun b4b_phoneScreenResponseWhileParked() {
        val t = setup()
        armCar(t)
        assertEquals(TestStart.WATCH, t.phone.engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = t.phone.clock.mono()))
        world.runUntil(60_000, "차 AP 경보 2") { t.car.state() == FlowState.AP && t.car.hub.snapshot.stage == 2 }
        world.advance(3_000)
        assertTrue("휴대폰이 정지로 봄(「괜찮아요」 보임)", t.phone.status().stopped)
        t.phone.engine.phoneResponse(t.phone.clock.mono())
        assertTrue("응답 보냄 · 확인 중", t.phone.status().responsePending)
        world.runUntil(8_000, "차 S2") { t.car.state() == FlowState.S2 }
        world.runUntil(6_000, "경보 멈춤") { !t.watch.alerting && !t.phone.log.alarmOn }
        assertFalse("ALERT_STOP 뒤 확인 중 표시 거둠", t.phone.status().responsePending)
    }

    /** 주행 중 휴대폰 화면 응답은 차가 받지 않는다(G5) — 휴대폰이 아예 보내지 않는다. */
    @Test
    fun b4c_phoneScreenResponseIgnoredWhileDriving() {
        val t = setup()
        armCar(t)
        assertEquals(TestStart.WATCH, t.phone.engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = t.phone.clock.mono()))
        drive(t)
        world.runUntil(60_000, "차 S1") { t.car.state() == FlowState.S1 }
        t.phone.engine.phoneResponse(t.phone.clock.mono())
        assertNull(t.phone.engine.phoneResponseAtWall)
        world.advance(6_000)
        assertTrue(t.car.state() in alarmStates)
    }

    // ── B5 ──

    /**
     * B5 차 링크 끊김 — 경보 2 에서 차 끊김 → 10초 남짓 안 휴대폰이 주인, 같은 eventId 로 이어 감, 문자 중복 없음 →
     * 차 복귀 → 차가 휴대폰 흐름을 이어받음(같은 사건).
     */
    @Test
    fun b5_carLinkLostPhoneTakesOverAndBack() {
        val t = setup()
        armCar(t)
        assertEquals(TestStart.WATCH, t.phone.engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = t.phone.clock.mono()))
        drive(t)
        world.runUntil(60_000, "차 경보 2") { t.car.hub.snapshot.stage == 2 }
        val eventId = t.car.hub.snapshot.facts.eventId!!
        t.car.linkUp = false
        val took = world.runUntil(16_000, "휴대폰 주인") { t.phone.engine.owner == Owner.PHONE }
        assertTrue("10초 남짓($took ms)", took <= 14_000)
        assertTrue("휴대폰 홈 「차 상태 없음」", !t.phone.status().carFresh)
        assertTrue("휴대폰이 이어받은 흐름은 경보 중", t.phone.status().flowState in alarmStates)
        world.runUntil(40_000, "휴대폰 흐름 S3") { t.phone.status().flowState == FlowState.S3 }
        world.advance(4_000)
        assertEquals("문자 한 통", 1, t.phone.log.sms.size)
        assertEquals("같은 사건 id 로 보냄", setOf("EVENT:$eventId"), t.phone.store.smsKeys)
        t.car.linkUp = true
        world.runUntil(10_000, "차가 다시 주인") { t.phone.engine.owner == Owner.CAR && t.car.hub.carIsOwner(t.car.clock.mono()) }
        world.advance(4_000)
        assertTrue("차가 휴대폰 흐름을 이어받음", t.car.reports.any { it.adopted })
        assertEquals("같은 사건", eventId, t.car.hub.snapshot.facts.eventId)
        assertTrue("이어받은 상태", t.car.state() in alarmStates)
        world.advance(20_000)
        assertEquals("문자 중복 없음", 1, t.phone.log.sms.size)
    }

    // ── B6 ──

    /** B6 주행 중 조작 금지 — D · 60 감시 중 · 경보 중 내내 차 버튼 0 · 차 설정 문 닫힘, 휴대폰 정지 아님 · 테스트 시작 거부 · 철회 줄 숨김, 119 작성 없음. */
    @Test
    fun b6_drivingLocksControls() {
        val t = setup()
        armCar(t)
        drivingInvariants(t)
        drive(t)
        world.advance(8_000)
        assertFalse("차 짝짓기 · 새 번호 문 닫힘", t.car.hub.setupOk())
        assertFalse("철회 줄 숨김", Withdrawal.visible(t.phone.status()))
        assertEquals(TestStart.NOT_STOPPED, t.phone.engine.startTest(Scenario.PULSE_LOST, false, t.phone.clock.mono()))
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 2_000)
        world.runUntil(60_000, "차 S3") { t.car.state() == FlowState.S3 }
        world.advance(20_000)
        assertFalse(t.car.pressOk())
        assertTrue("주행 중 응급실 검색 없음", t.car.ports.searches.isEmpty())
        assertTrue("주행 중 P12 없음", t.phone.log.noResponse.isEmpty())
        assertTrue("주행 중 119 미리보기도 없음", t.phone.log.previews.isEmpty())
    }

    // ── 사고 ──

    /** 사고 ① 실제 에어백(감시 중 · 주행) → 실제 사고 문자 한 통(휴대폰 조립, [테스트] 없음 · 확인 대화 없음). */
    @Test
    fun accident_real() {
        val t = setup()
        armCar(t)
        drive(t)
        world.advance(6_000)
        t.car.reading = t.car.reading.copy(airbagDeployed = true)
        world.runUntil(6_000, "차 S5") { t.car.state() == FlowState.S5 }
        world.advance(6_000)
        assertTrue(t.phone.log.asked.isEmpty())
        assertEquals("사고 문자 한 통", 1, t.phone.log.sms.size)
        val body = t.phone.log.sms[0].body
        assertFalse(body.startsWith(SmsText.TEST_PREFIX))
        assertTrue(body.contains(SmsText.ACCIDENT_BODY))
        assertTrue(t.phone.store.smsKeys.single().startsWith("ACCIDENT:accident-"))
    }

    /**
     * 사고 ② 워치 테스트 사건 도중 에어백 → 테스트 경보 정리 뒤 새 eventId 의 실제 사고 문자 한 통, 테스트 취소 문자 없음.
     * 휴대폰의 「테스트 진행 중 테스트 표시 없는 문자 차단」은 ACCIDENT 를 빼고 막는다(80e26ac).
     */
    @Test
    fun accident_duringTestIsReal() {
        val t = setup()
        armCar(t)
        assertEquals(TestStart.WATCH, t.phone.engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = t.phone.clock.mono()))
        drive(t)
        world.runUntil(60_000, "차 경보 2") { t.car.hub.snapshot.stage == 2 }
        world.advance(3_000)
        val testEvent = t.car.hub.snapshot.facts.eventId
        t.car.reading = t.car.reading.copy(airbagDeployed = true)
        world.runUntil(6_000, "차 S5") { t.car.state() == FlowState.S5 }
        assertFalse("사고는 실제", t.car.hub.snapshot.test)
        world.runUntil(8_000, "테스트 경보 정리") { !t.watch.alerting && !t.phone.log.alarmOn }
        world.advance(6_000)
        assertTrue("테스트 취소 문자 없음", guardianSms(t).none { it.contains(cancelHead) })
        val accident = t.phone.log.sms.filter { it.body.contains(SmsText.ACCIDENT_BODY) }
        assertEquals("실제 사고 문자 한 통", 1, accident.size)
        assertFalse(accident[0].body.startsWith(SmsText.TEST_PREFIX))
        val key = t.phone.store.smsKeys.single { it.startsWith("ACCIDENT:") }
        assertTrue("새 사건 id", key.substringAfter(':') != testEvent)
    }

    /** 사고 ③ 사고(S5) 뒤 휴대폰에서 전원 끔 → 켬 → 새 에어백(false → true). [gapMs] 는 다시 S0 이 된 뒤 두 번째 전개까지 기다리는 시간. */
    private fun secondAccidentAfterPowerCycle(gapMs: Long): Trio {
        val t = setup()
        armCar(t)
        t.car.reading = t.car.reading.copy(airbagDeployed = true)
        world.runUntil(6_000, "차 S5") { t.car.state() == FlowState.S5 }
        world.advance(4_000)
        powerCycle(t)
        t.car.reading = t.car.reading.copy(airbagDeployed = false)
        world.advance(4_000)
        world.runUntil(20_000, "다시 S0") { t.car.state() == FlowState.S0 }
        world.advance(gapMs)
        t.car.reading = t.car.reading.copy(airbagDeployed = true)
        world.runUntil(6_000, "차 S5 두 번째") { t.car.state() == FlowState.S5 }
        world.advance(6_000)
        return t
    }

    /**
     * 사고 ③-1 — 첫 사고 문자 뒤 10분 안(40초 사이)의 두 번째 전개는 사건 id 가 달라 새 사고 문자로 나간다. 사고 칸은 일반 칸과 같은 값
     * (10분 3통 · 24시간 10통)을 따로 세므로 두 통이 모두 나가고 상한에 막힌 문자는 없다(PM 결정 27).
     */
    @Test
    fun accident_secondDeploymentWithin10Min_isSent() {
        val t = secondAccidentAfterPowerCycle(0)
        assertEquals("사고 문자 두 통", 2, t.phone.log.sms.count { it.body.contains(SmsText.ACCIDENT_BODY) })
        assertFalse("상한으로 보내지 않은 사고 문자 없음", ("ACCIDENT" to false) in t.phone.log.smsResults)
    }

    /** 사고 ③-2 — 10분이 지난 뒤의 두 번째 전개도 새 사고 문자로 나간다. 결정 27 뒤로는 ③-1 과 기대가 같고, 사고 칸이 실제로 막는 넷째 문자는 휴대폰 단위 시험이 지킨다. */
    @Test
    fun accident_secondDeploymentAfter10Min_isSent() {
        val t = secondAccidentAfterPowerCycle(10 * 60_000L)
        assertEquals("사고 문자 두 통", 2, t.phone.log.sms.count { it.body.contains(SmsText.ACCIDENT_BODY) })
    }

    /** 사고 ④ 에어백이 계속 true 인 채 전원을 껐다 켜면 문자는 한 건뿐(OFF 중 전개 · 지난 사고는 다시 알리지 않음, PM 확인). */
    @Test
    fun accident_powerCycleWithAirbagStillOn_noSecondSms() {
        val t = setup()
        armCar(t)
        t.car.reading = t.car.reading.copy(airbagDeployed = true)
        world.runUntil(6_000, "차 S5") { t.car.state() == FlowState.S5 }
        world.advance(4_000)
        powerCycle(t)
        world.advance(30_000)
        assertEquals("사고 문자 한 통", 1, t.phone.log.sms.count { it.body.contains(SmsText.ACCIDENT_BODY) })
    }

    /**
     * 사고 ⑤ 앞선 사고로 에어백이 true 로 남은 차 앱이 다시 뜸 · 휴대폰이 주인(차 링크가 끊긴 동안 휴대폰이 감시) — 차가 중계에 싣는
     * 첫 에어백 값이 true 면 휴대폰 상태 기계(에어백 true 를 본 적 없음)가 새 전이로 보고 사고를 연다. 차는 false 를 읽기 전의 true 를
     * 모름으로 실어 사고 문자가 나가지 않는다(독립 검토 상의 휴대폰 쪽 뿌리).
     */
    @Test
    fun accident_restartedCarWithAirbagStillOnWhilePhoneOwns_noSms() {
        val t = setup()
        t.car.linkUp = false
        t.phone.gpsKmh = 0.0
        armPhone(t)
        // 새 프로세스의 첫 읽기부터 true(앞선 사고로 남은 값)
        val c2 = restartCar(t, 1_000, t.car.reading.copy(airbagDeployed = true))
        world.advance(30_000)
        assertEquals("사고 문자 없음", 0, t.phone.log.sms.count { it.body.contains(SmsText.ACCIDENT_BODY) })
        assertTrue("다시 뜬 차도 사고를 열지 않음", c2.state() != FlowState.S5)
    }

    /** 휴대폰에서 기능을 껐다가(꺼짐 스냅숏이 차로) 다시 켠다. */
    private fun powerCycle(t: Trio) {
        t.phone.store.reg = t.phone.store.reg.copy(powerOn = false)
        world.runUntil(8_000, "차 OFF") { t.car.state() == FlowState.OFF }
        t.phone.store.reg = t.phone.store.reg.copy(powerOn = true)
    }
}
