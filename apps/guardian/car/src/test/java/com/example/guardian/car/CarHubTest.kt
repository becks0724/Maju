// 차 허브 시험 — 차량 · 워치 → 상태 기계 입력, 지시 분배(차 안 실행 · 상대 전송), 주인 10초 규칙, 넘겨받기 시계 보정
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.protocol.CarSyncResponse
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHubTest {
    private fun Rig.types(to: CommandTo) = hub.pendingCommands.filter { it.resolvedTo == to }.map { it.type }

    // ── 입력 매핑 ──

    @Test fun staysOffWithoutPhoneSettings() {
        val r = Rig()
        r.hub.onReading(MOVING, 0)
        r.sync(0, watch(WristVerdict.NORMAL), peerFlow = null)
        assertEquals("켬 · 동의는 휴대폰이 정한다", FlowState.OFF, r.hub.snapshot.state)
    }

    @Test fun phoneSettingsAndFreshWatchArmTheMachine() {
        val r = Rig()
        r.arm(MOVING)
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        assertEquals(Consents(true, true, true), r.hub.snapshot.consents)
        r.sync(2_000, watch(WristVerdict.NORMAL), peerFlow = settings(power = false))
        assertEquals("휴대폰에서 끄면 꺼짐", FlowState.OFF, r.hub.snapshot.state)
    }

    @Test fun weakPulseWhileDrivingAlertsInCarAndQueuesWatch() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        assertEquals("차 음성은 바로", listOf(Phrase.VOICE_S1_PULL_OVER.ko), r.ports.spoken)
        val screen = r.hub.screen()
        assertEquals(Phrase.SCREEN_S1.ko, screen.line)
        assertNull("주행 중 버튼 없음", screen.button)
        assertEquals(listOf(CommandType.ALERT_START), r.types(CommandTo.WATCH))
        assertTrue("차 몫은 중계로 보내지 않는다(자기 자신은 400)", r.hub.pendingCommands.none { it.resolvedTo == CommandTo.CAR })
        // 다음 요청에 실리고, 성공하면 보낼 함에서 빠진다
        r.sync(4_000, watch(WristVerdict.PULSE_WEAK))
        assertTrue(r.lastRequest!!.commands.any { it.type == CommandType.ALERT_START && it.from == Party.CAR })
        assertTrue(r.hub.pendingCommands.isEmpty())
    }

    @Test fun staleWatchIsNotUsed() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_ABSENT, ageMs = 30_001))
        assertEquals("ageMs 30초 초과는 판정에 쓰지 않음", FlowState.S0, r.hub.snapshot.state)
        r.sync(4_000, watch(WristVerdict.PULSE_ABSENT, ageMs = null))
        assertEquals("ageMs 없음도 낡은 값", FlowState.S0, r.hub.snapshot.state)
    }

    /** 신선도 경계 — 감시 진입(OFF → S0)은 신선한 워치 값이 있어야 한다. 30,000 은 신선, 30,001 은 낡음. */
    @Test fun watchAgeExactly30sIsStillFresh() {
        val fresh = Rig()
        fresh.hub.onReading(MOVING, 0)
        fresh.sync(0, watch(WristVerdict.NORMAL, ageMs = 30_000))
        assertEquals("30,000 은 아직 신선(초과만 낡음)", FlowState.S0, fresh.hub.snapshot.state)

        val stale = Rig()
        stale.hub.onReading(MOVING, 0)
        stale.sync(0, watch(WristVerdict.NORMAL, ageMs = 30_001))
        assertEquals(FlowState.OFF, stale.hub.snapshot.state)
    }

    /**
     * 2026-11-02 코드 리뷰 상 1 — 워치가 신호를 못 잡고 있다고 알린 값(`noSignal`)은 판정 입력으로 쓰지 않는다. 그 값이 2초마다
     * 계속 와도 마지막으로 넣은 값에서 30초가 지나면 상태 기계의 기존 「워치 신호 없음」 경로를 탄다. 신호가 돌아오면 바로 판정에 쓴다.
     */
    @Test fun noSignalWatchValueIsNotAVerdictAndFallsToNoSignalAfter30s() {
        val r = Rig()
        r.arm(MOVING) // 0ms 에 신선한 NORMAL — 상태 기계가 본 마지막 워치 값
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        for (t in 2_000L..30_000L step 2_000) { r.hub.onReading(MOVING, t); r.sync(t, watch(WristVerdict.PULSE_ABSENT, noSignal = true)) }
        assertEquals("신호 없음 값의 PULSE_ABSENT 는 사건이 아니다", FlowState.S0, r.hub.snapshot.state)
        assertEquals("판정도 그대로", WristVerdict.NORMAL, r.hub.snapshot.verdict)
        assertFalse("30초 전에는 아직 신호 없음 안내 아님", r.hub.snapshot.noSignalNotified)
        assertEquals("연결 줄도 아직 착용", WatchLink.WORN, r.hub.links(30_050).watch)
        // 워치 칸은 바로 신호 없음 — 그 값의 심박 · 판정을 보이지 않는다
        val bio = r.hub.bio(30_100)
        assertTrue(bio.noSignal)
        assertNull(bio.hr)
        // 마지막으로 넣은 값(0ms 보냄 → 100ms 받음)에서 30초 — 상태 기계가 「워치 신호 없음」을 띄운다
        r.hub.onReading(MOVING, 30_200)
        r.sync(30_200, watch(WristVerdict.NORMAL, noSignal = true))
        assertTrue(r.hub.snapshot.noSignalNotified)
        assertEquals(WatchLink.NO_SIGNAL, r.hub.links(30_300).watch)
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        // 신호가 돌아오면(noSignal 없음) 바로 판정에 쓴다 — 약해짐이면 경보
        r.hub.onReading(MOVING, 32_000)
        r.sync(32_000, watch(WristVerdict.NORMAL))
        assertFalse(r.hub.snapshot.noSignalNotified)
        assertEquals(WatchLink.WORN, r.hub.links(32_200).watch)
        r.hub.onReading(MOVING, 34_000)
        r.sync(34_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
    }

    /** 신호 없음 값이어도 워치 버튼 응답(`responseAt` 증가)은 그대로 응답이다(계약 §4.3). */
    @Test fun noSignalWatchValueStillCarriesTheButtonResponse() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        r.sync(4_000, watch(WristVerdict.PULSE_WEAK, responseAt = 1_761_800_000_000, noSignal = true))
        assertEquals(FlowState.S1R, r.hub.snapshot.state)
        assertTrue(r.types(CommandTo.ALL).contains(CommandType.ALERT_STOP))
    }

    @Test fun watchResponseTimeChangeIsAResponse() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        r.sync(4_000, watch(WristVerdict.PULSE_WEAK, responseAt = 1_761_800_000_000))
        assertEquals(FlowState.S1R, r.hub.snapshot.state)
        assertTrue(r.types(CommandTo.ALL).contains(CommandType.ALERT_STOP))
    }

    @Test fun oldWatchResponseDoesNotCancelANewAlert() {
        val r = Rig()
        r.hub.onReading(MOVING, 0)
        r.sync(0, watch(WristVerdict.NORMAL, responseAt = 5))
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK, responseAt = 5))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
    }

    /** 워치가 응답 시각을 sync 마다 다시 계산해 ±1ms 흔들려도 응답이 아니다 — 1초 넘는 증가만 응답(계약 §4.3). */
    @Test fun watchResponseJitterIsNotAResponseButAStepIs() {
        val base = 1_761_800_000_000L
        val r = Rig()
        r.hub.onReading(MOVING, 0)
        r.sync(0, watch(WristVerdict.NORMAL, responseAt = base))
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK, responseAt = base + 1))
        r.sync(4_000, watch(WristVerdict.PULSE_WEAK, responseAt = base))
        r.sync(6_000, watch(WristVerdict.PULSE_WEAK, responseAt = base - 1))
        assertEquals("±1ms 흔들림은 응답 아님", FlowState.S1, r.hub.snapshot.state)
        r.sync(8_000, watch(WristVerdict.PULSE_WEAK, responseAt = base + 1_500))
        assertEquals("1.5초 증가는 응답", FlowState.S1R, r.hub.snapshot.state)
    }

    @Test fun phoneScreenResponseCountsOnlyWhenStopped() {
        val base = 1_761_800_000_000L
        val driving = Rig()
        driving.arm(MOVING)
        driving.sync(2_000, watch(WristVerdict.PULSE_WEAK), peerResponseAt = base)
        driving.sync(4_000, watch(WristVerdict.PULSE_WEAK), peerResponseAt = base + 1_500)
        assertEquals("주행 중 휴대폰 화면 응답은 버린다(G5)", FlowState.S1, driving.hub.snapshot.state)

        val parked = Rig()
        parked.hub.onReading(PARKED, 0)
        parked.sync(0, watch(WristVerdict.NORMAL), peerResponseAt = base) // 처음 본 값은 기준
        parked.sync(2_000, watch(WristVerdict.PULSE_WEAK), peerResponseAt = base)
        assertEquals(FlowState.AP, parked.hub.snapshot.state)
        parked.sync(4_000, watch(WristVerdict.PULSE_WEAK), peerResponseAt = base + 1)
        assertEquals("흔들림은 응답 아님", FlowState.AP, parked.hub.snapshot.state)
        parked.sync(6_000, watch(WristVerdict.PULSE_WEAK), peerResponseAt = base + 1_500)
        assertEquals("정지 중 1.5초 증가는 응답", FlowState.S2, parked.hub.snapshot.state)
    }

    /** 휴대폰 테스트 모드가 워치에 TEST_SCENARIO 를 보내 워치 값이 test 이면, 그 판정이 연 사건의 지시는 모두 테스트다. */
    @Test fun watchTestScenarioMakesTheIncidentATest() {
        val r = Rig()
        r.hub.onReading(MOVING, 0)
        r.sync(0, watch(WristVerdict.NORMAL, test = true))
        for (t in 2_000L..40_000L step 2_000) { r.hub.onReading(MOVING, t); r.sync(t, watch(WristVerdict.PULSE_ABSENT, test = true)) }
        assertEquals(FlowState.S3, r.hub.snapshot.state)
        assertTrue(r.hub.snapshot.test)
        assertTrue(r.hub.screen().test)
        val all = r.sentCommands + r.hub.pendingCommands
        assertTrue(all.any { it.type == CommandType.SEND_GUARDIAN_SMS })
        assertTrue("모든 지시가 test=true", all.all { it.test })
        assertTrue("문자 지시 payload 도 test", all.filter { it.type == CommandType.SEND_GUARDIAN_SMS }.all { it.payload["test"] == true })
    }

    @Test fun staleWatchTestFlagDoesNotMarkTheFlow() {
        val r = Rig()
        r.hub.onReading(MOVING, 0)
        r.sync(0, watch(WristVerdict.NORMAL))
        r.sync(2_000, watch(WristVerdict.NORMAL, ageMs = 30_001, test = true))
        assertFalse(r.hub.snapshot.test)
    }

    // ── 정지 화면 · 버튼 · 검색 ──

    @Test fun stoppedAlertShowsOneButtonAndCarButtonResponds() {
        val r = Rig()
        r.arm(PARKED)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(FlowState.AP, r.hub.snapshot.state)
        assertEquals(Phrase.BUTTON_OK.ko, r.hub.screen().button)
        // 움직이기 시작하면 정지 확정이 바뀌기 전이라도 버튼은 바로 사라지고 누를 수 없다
        r.hub.onReading(MOVING, 2_500)
        assertNull(r.hub.screen().button)
        assertFalse(r.hub.onCarButton(2_600, MOVING))
        r.hub.onReading(PARKED, 3_000)
        assertTrue(r.hub.onCarButton(3_100, PARKED))
        assertEquals(FlowState.S2, r.hub.snapshot.state)
        assertEquals(Phrase.SCREEN_S2.ko, r.hub.screen().line)
    }

    @Test fun noResponseWhileParkedOpensSearchOnceAndSendsFactsOnlyToPhone() {
        val r = Rig()
        r.arm(PARKED)
        r.sync(2_000, watch(WristVerdict.PULSE_ABSENT))
        for (t in 4_000L..40_000L step 2_000) { r.hub.onReading(PARKED, t); r.sync(t, watch(WristVerdict.PULSE_ABSENT)) }
        assertEquals(FlowState.S4, r.hub.snapshot.state)
        assertEquals(listOf(Phrase.SEARCH_EMERGENCY_ROOM.ko), r.ports.searches)
        assertEquals(Phrase.SCREEN_S4.ko, r.hub.screen().line)
        // 보호자 문자는 확인까지 같은 지시 id 로 다시 실린다(A-2) — 지시 id 로 센다
        val all = (r.sentCommands + r.hub.pendingCommands).distinctBy { it.id }
        val sms = all.filter { it.type == CommandType.SEND_GUARDIAN_SMS }
        val compose = all.filter { it.type == CommandType.OPEN_119_COMPOSE }
        assertEquals("보호자 사건 문자 한 번", 1, sms.size)
        assertEquals("EVENT", sms.single().payload["kind"])
        assertEquals("119 작성 한 번", 1, compose.size)
        for (c in sms + compose) {
            assertEquals(CommandTo.PHONE, c.resolvedTo)
            val json = Json.encode(c.toJson())
            assertFalse("본문은 휴대폰이 조립 — body 없음: $json", json.contains("\"body\""))
            assertTrue("사건 id 와 사실을 싣는다", json.contains("\"eventId\":\"event-") && json.contains("firstAlertAt"))
        }
    }

    // ── 주행 중 UX 제한(CarUxRestrictions) ──

    @Test fun noUxValueMeansNoButtonEvenWhenStopped() {
        val r = Rig()
        val unknown = PARKED.copy(ux = null)
        r.arm(unknown)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(FlowState.AP, r.hub.snapshot.state)
        assertTrue(r.hub.stoppedNow())
        assertNull("UX 값 없음 = 제한됨 → 버튼 0", r.hub.screen().button)
        assertFalse(r.hub.onCarButton(2_200, unknown))
        r.hub.onReading(PARKED, 2_400)
        assertEquals("값이 오면 버튼", Phrase.BUTTON_OK.ko, r.hub.screen().button)
    }

    /** 출발 직후 — 버튼이 보인 뒤 UX 가 DO 요구로 바뀌고 다음 주기 전에 눌러도 응답이 아니다(G5). */
    @Test fun uxChangeBeforeNextTickClosesTheButtonAtOnce() {
        val r = Rig()
        r.arm(PARKED)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(Phrase.BUTTON_OK.ko, r.hub.screen().button)
        // 리스너가 DO 요구를 받아 바로 반영 — 화면 버튼이 곧바로 사라진다
        r.hub.onUx(UX_DRIVING)
        assertNull(r.hub.screen().button)
        // 누른 순간 다시 읽은 값(아직 속도 0 · 기어 P 지만 DO 요구)으로 확인 — 응답 아님
        assertFalse(r.hub.onCarButton(2_500, PARKED.copy(ux = UX_DRIVING)))
        assertEquals(FlowState.AP, r.hub.snapshot.state)
    }

    /** 리스너 반영이 늦어 허브가 아직 「정지 · 무제한」을 들고 있어도, 누른 순간의 최신 값이 DO 요구면 버린다. */
    @Test fun buttonIsRecheckedWithReadingAtPressTime() {
        val r = Rig()
        r.arm(PARKED)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(Phrase.BUTTON_OK.ko, r.hub.screen().button)
        assertFalse(r.hub.onCarButton(2_500, PARKED.copy(ux = UX_DRIVING)))
        assertFalse("움직이기 시작한 속도도 같다", r.hub.onCarButton(2_600, MOVING.copy(ux = UX_PARKED)))
        assertEquals(FlowState.AP, r.hub.snapshot.state)
    }

    @Test fun requiresDoBlocksButtonWhileStopped() {
        val r = Rig()
        r.arm(PARKED.copy(ux = UxGate(requiresDo = true, noSetup = false)))
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(FlowState.AP, r.hub.snapshot.state)
        assertNull("정지여도 DO 요구면 버튼 0", r.hub.screen().button)
        assertFalse(r.hub.onCarButton(2_200, PARKED.copy(ux = UxGate(requiresDo = true, noSetup = false))))
        assertEquals(FlowState.AP, r.hub.snapshot.state)
    }

    @Test fun searchNeedsUxClearance() {
        val r = Rig()
        val unknown = PARKED.copy(ux = null)
        r.arm(unknown)
        r.sync(2_000, watch(WristVerdict.PULSE_ABSENT))
        for (t in 4_000L..40_000L step 2_000) { r.hub.onReading(unknown, t); r.sync(t, watch(WristVerdict.PULSE_ABSENT)) }
        assertEquals(FlowState.S4, r.hub.snapshot.state)
        assertTrue("UX 값 없으면 응급실 검색 화면을 열지 않음", r.ports.searches.isEmpty())
    }

    @Test fun setupNeedsNoSetupBitClearAndParked() {
        val r = Rig()
        r.hub.onReading(PARKED, 0); assertTrue(r.hub.setupOk())
        r.hub.onReading(PARKED.copy(ux = UxGate(requiresDo = false, noSetup = true)), 1); assertFalse("NO_SETUP → 짝짓기 0", r.hub.setupOk())
        r.hub.onReading(PARKED.copy(ux = null), 2); assertFalse("UX 값 없음 = 제한됨", r.hub.setupOk())
        r.hub.onReading(MOVING.copy(ux = UX_PARKED), 3); assertFalse("UX 가 풀려도 주행이면 0", r.hub.setupOk())
        r.hub.onReading(CarReading(null, VehicleMapping.GEAR_PARK, ux = UX_PARKED), 4); assertTrue("속도 권한 전 기어 P", r.hub.setupOk())
    }

    @Test fun ambientStaysOffThroughAWholeIncident() {
        val r = Rig()
        r.arm(MOVING)
        for (t in 2_000L..40_000L step 2_000) { r.hub.onReading(MOVING, t); r.sync(t, watch(WristVerdict.PULSE_ABSENT)) }
        assertEquals(FlowState.S3, r.hub.snapshot.state)
        assertTrue("조명 호출 0", r.ports.ambient.isEmpty())
        assertTrue(r.hub.pendingCommands.none { it.type == CommandType.AMBIENT })
    }

    @Test fun testFlowShowsTestMark() {
        val r = Rig()
        r.hub.onReading(MOVING, 0)
        r.sync(0, watch(WristVerdict.NORMAL), peerFlow = settings(test = true, owner = Owner.PHONE))
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK), peerFlow = settings(test = true))
        assertTrue(r.hub.screen().test)
        assertTrue(r.hub.pendingCommands.all { it.test })
    }

    // ── 받은 지시 ──

    @Test fun remoteCommandsAreAckedAndForbiddenAmbientRejected() {
        val r = Rig()
        r.arm(MOVING)
        val blink = Command("b1", CommandType.AMBIENT, mapOf("on" to true, "mode" to "blue_red_blink"), 30_000, false, Party.PHONE, CommandTo.CAR)
        val speak = Command("s1", CommandType.SPEAK, mapOf("text" to "x", "lang" to "ko-KR"), 30_000, false, Party.PHONE, CommandTo.CAR)
        val rep = r.sync(2_000, watch(WristVerdict.NORMAL), commands = listOf(blink, speak))
        assertEquals(RemoteOutcome.REJECTED, rep.remote["b1"])
        assertEquals(RemoteOutcome.IGNORED_CAR_OWNER, rep.remote["s1"])
        assertTrue("휴대폰 지시는 실행하지 않음", r.ports.spoken.isEmpty() && r.ports.ambient.isEmpty())
        val again = r.sync(4_000, watch(WristVerdict.NORMAL), commands = listOf(blink))
        assertEquals(listOf("b1", "s1"), r.lastRequest!!.acks)
        assertEquals(RemoteOutcome.DUPLICATE, again.remote["b1"])
    }

    /** 계약 §4.6 — 모르는 type 은 그 지시만 건너뛰고 「지원하지 않음」으로 ack, 나머지는 그대로 처리. 다시 와도 한 번만. */
    @Test fun unknownTypeInResponseIsSkippedAndAckedOnce() {
        val r = Rig()
        r.arm(MOVING)
        val speak = """{"id":"s1","type":"SPEAK","payload":{"text":"x","lang":"ko-KR"},"ttlMs":30000,"test":false,"from":"PHONE","to":"CAR"}"""
        val future = """{"id":"u1","type":"FUTURE_THING","payload":{},"ttlMs":30000,"test":false,"from":"PHONE","to":"CAR"}"""
        val resp = CarSyncResponse.fromJson(JsonObj(Json.parseObject("""{"serverTime":1,"commands":[$future,$speak]}""")))
        assertEquals(listOf("s1"), resp.commands.map { it.id })
        val rep = r.sync(2_000, watch(WristVerdict.NORMAL), commands = resp.commands, skipped = resp.skippedCommandIds)
        assertEquals("아는 지시는 그대로 처리", RemoteOutcome.IGNORED_CAR_OWNER, rep.remote["s1"])
        assertEquals("모르는 지시는 지원하지 않음", RemoteOutcome.REJECTED, rep.remote["u1"])
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        // 같은 응답이 다시 온다(ack 전 재전송) — 다음 요청 acks 에 한 번씩만, 두 번째는 중복
        val again = r.sync(4_000, watch(WristVerdict.NORMAL), commands = resp.commands, skipped = resp.skippedCommandIds)
        assertEquals(listOf("s1", "u1"), r.lastRequest!!.acks)
        assertEquals(RemoteOutcome.DUPLICATE, again.remote["u1"])
        assertEquals(RemoteOutcome.DUPLICATE, again.remote["s1"])
        r.sync(6_000, watch(WristVerdict.NORMAL))
        assertEquals("재전송분도 다시 ack(중계 큐에서 지우게)", listOf("s1", "u1"), r.lastRequest!!.acks)
        r.sync(8_000, watch(WristVerdict.NORMAL))
        assertTrue("ack 가 나간 뒤에는 비움", r.lastRequest!!.acks.isEmpty())
    }

    /** TEST_SCENARIO_STOP 은 PHONE → WATCH 지시라 차 몫이 아니다 — 혹시 와도 실행 없이 ack 만. */
    @Test fun testScenarioStopIsNotCarsJobAndOnlyAcked() {
        val r = Rig()
        r.arm(MOVING)
        val stop = Command("ts1", CommandType.TEST_SCENARIO_STOP, emptyMap(), 30_000, true, Party.PHONE, CommandTo.WATCH)
        val rep = r.sync(2_000, watch(WristVerdict.NORMAL), commands = listOf(stop))
        assertEquals(RemoteOutcome.IGNORED_CAR_OWNER, rep.remote["ts1"])
        assertTrue("차 안 실행 없음", r.ports.spoken.isEmpty() && r.ports.ambient.isEmpty() && r.ports.searches.isEmpty())
        assertFalse("흐름 테스트 표시도 그대로", r.hub.snapshot.test)
        r.sync(4_000, watch(WristVerdict.NORMAL))
        assertEquals(listOf("ts1"), r.lastRequest!!.acks)
    }

    // ── 주인 ──

    @Test fun carIsOwnerOnlyWithin10sOfLastSync() {
        val r = Rig()
        assertEquals("첫 요청은 아직 주인 아님", Owner.PHONE, r.hub.buildRequest("t", 1, 0, 0).flow!!.owner)
        r.arm(MOVING)
        assertTrue(r.hub.carIsOwner(100 + 10_000))
        assertFalse(r.hub.carIsOwner(100 + 10_001))
        val req = r.hub.buildRequest("t", 2, 0, 2_000)
        assertEquals(Owner.CAR, req.flow!!.owner)
        assertEquals("보정값이 있으면 넘김용 중계 시각", SnapshotClock.SERVER, req.flow!!.clock)
    }

    @Test fun linkLostKeepsInCarAlertsButStopsQueueing() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertTrue(r.hub.pendingCommands.isNotEmpty())
        // 링크가 끊긴다 — 2초마다 실패
        for (t in 4_000L..40_000L step 2_000) { r.hub.onReading(MOVING, t); r.hub.onSyncFailure(t) }
        assertEquals(FlowState.S3, r.hub.snapshot.state)
        assertTrue("차 안 음성은 계속 — 주인이 아니라 「늦어질 수 있습니다」(A-5)", r.ports.spoken.contains(Phrase.VOICE_S3_SMS_DELAYED.ko))
        val pending = r.hub.pendingCommands
        assertTrue("S3 의 보호자 문자가 남아 있다(A-2) — 빈 목록이면 아래 단언이 헛돈다", pending.isNotEmpty())
        assertTrue("상대 지시는 쌓지 않음 — 실제 보호자 문자만 남김(A-2) $pending", pending.all { it.type == CommandType.SEND_GUARDIAN_SMS })
    }

    /** 리뷰 중 7 — 중계 200 인데 본문을 못 읽어도(상대 빌드 다름) 링크는 살아 있다: 주인 유지 · 차 상태 없음 아님 · 지시는 계속 실려 나간다. */
    @Test fun unparsableResponseKeepsLinkAliveAndQueueing() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        val sentTypes = ArrayList<CommandType>()
        for (t in 4_000L..40_000L step 2_000) {
            r.hub.onReading(MOVING, t)
            val req = r.hub.buildRequest("tok", 100 + t, 0, t)
            sentTypes += req.commands.map { it.type }
            r.hub.onSyncUnparsable(req, t)
            assertTrue("$t 에도 차가 주인", r.hub.carIsOwner(t))
            val ids = req.commands.map { it.id }.toSet()
            assertTrue("실어 보낸 지시는 지움 — 보호자 문자는 확인까지 남김(A-2)", r.hub.pendingCommands.none { it.id in ids && it.type != CommandType.SEND_GUARDIAN_SMS })
        }
        assertEquals(FlowState.S3, r.hub.snapshot.state)
        assertFalse("차 상태 없음으로 기록되지 않음", r.hub.snapshot.facts.carStateMissing)
        assertTrue("S3 의 보호자 문자 지시가 실려 나감", CommandType.SEND_GUARDIAN_SMS in sentTypes)
    }

    // ── 넘겨받기 ──

    /** 휴대폰이 주인으로 돌린 흐름(휴대폰 단조 = 중계 시계)을 차 시계 ±90초에서 넘겨받아 원래 시각대로 이어 간다. */
    @Test fun adoptsPhoneFlowOnReconnectAcrossClockSkew() {
        for (skew in listOf(90_000L, -90_000L)) {
            val phone = FlowMachine()
            phone.handle(FlowInput.ClockSync(0, 0))
            phone.handle(FlowInput.Vehicle(VehicleState(60.0, Gear.D, false), 0))
            phone.handle(FlowInput.ConsentsChanged(Consents(true, true, true), 0))
            phone.handle(FlowInput.Power(true, 0))
            phone.handle(FlowInput.Wrist(WristVerdict.NORMAL, 0))
            phone.handle(FlowInput.Wrist(WristVerdict.PULSE_WEAK, 1_000)) // 중계 1,000 에 첫 경보
            val phoneFlow = FlowStatus.forHandoff(phone.snapshot(), Owner.PHONE, 0)

            val r = Rig(skew)
            val t = 10_000 - skew // 차 시각 = 중계 10,000 − skew
            r.hub.onReading(MOVING, t)
            val rep = r.sync(t, watch(WristVerdict.PULSE_WEAK), peerFlow = phoneFlow)
            assertTrue("skew=$skew 넘겨받음", rep.adopted)
            assertTrue(rep.clockVerified)
            assertEquals(FlowState.S1, r.hub.snapshot.state)
            assertEquals("사건 id 그대로", "event-1000", r.hub.snapshot.facts.eventId)
            assertEquals("첫 경보는 차 시계로", 1_000 - skew, r.hub.snapshot.facts.firstAlertAtMs)
            // 첫 경보 + 35초(중계 36,000) 에 S3 — 차 시계로는 36,000 − skew
            r.hub.onReading(MOVING, 35_999 - skew)
            assertEquals(FlowState.S1, r.hub.snapshot.state)
            r.hub.onReading(MOVING, 36_000 - skew)
            assertEquals("skew=$skew", FlowState.S3, r.hub.snapshot.state)
        }
    }

    /** 11-02 남은 하 — 경보 상태 흐름을 넘겨받으면 그 단계 음성을 한 번 낸다. 다음 sync 에서 되풀이하지 않는다. */
    @Test fun adoptingAnAlarmSpeaksItsStageOnce() {
        val phone = FlowMachine()
        phone.handle(FlowInput.ClockSync(0, 0))
        phone.handle(FlowInput.Vehicle(VehicleState(60.0, Gear.D, false), 0))
        phone.handle(FlowInput.ConsentsChanged(Consents(true, true, true), 0))
        phone.handle(FlowInput.Power(true, 0))
        phone.handle(FlowInput.Wrist(WristVerdict.NORMAL, 0))
        phone.handle(FlowInput.Wrist(WristVerdict.PULSE_WEAK, 1_000))
        phone.handle(FlowInput.Tick(37_000)) // 무응답 — S3
        val phoneFlow = FlowStatus.forHandoff(phone.snapshot(), Owner.PHONE, 0)
        assertEquals(FlowState.S3, phoneFlow.state)

        val r = Rig()
        r.hub.onReading(MOVING, 40_000)
        assertTrue(r.sync(40_000, watch(WristVerdict.PULSE_WEAK), peerFlow = phoneFlow).adopted)
        assertEquals(listOf(Phrase.VOICE_S3_NO_RESPONSE.ko), r.ports.spoken)
        r.hub.onReading(MOVING, 42_000)
        r.sync(42_000, watch(WristVerdict.PULSE_WEAK), peerFlow = phoneFlow.copy(owner = Owner.CAR))
        assertEquals("되풀이하지 않는다", 1, r.ports.spoken.size)

        // 감시(S0) 흐름을 넘겨받으면 음성 없음
        val quiet = Rig()
        quiet.hub.onReading(MOVING, 0)
        val watching = FlowSnapshot(state = FlowState.S0, powerOn = true, consents = Consents(true, true, true), worn = true, watchSeenAtMs = 0)
        assertTrue(quiet.sync(0, watch(WristVerdict.NORMAL), peerFlow = FlowStatus.of(watching, Owner.PHONE)).adopted)
        assertTrue(quiet.ports.spoken.isEmpty())
    }

    /** 차가 이미 낸 단계 음성은 링크가 끊겼다 같은 사건 · 같은 단계를 다시 넘겨받아도 되풀이하지 않는다. */
    @Test fun readoptingTheSameStageDoesNotRepeatTheVoice() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(listOf(Phrase.VOICE_S1_PULL_OVER.ko), r.ports.spoken)
        for (t in 4_000L..14_000L step 2_000) { r.hub.onReading(MOVING, t); r.hub.onSyncFailure(t) }
        assertFalse(r.hub.carIsOwner(14_000))
        val phoneFlow = FlowStatus.forHandoff(r.hub.snapshot, Owner.PHONE, 0)
        r.hub.onReading(MOVING, 16_000)
        assertTrue(r.sync(16_000, watch(WristVerdict.PULSE_WEAK), peerFlow = phoneFlow).adopted)
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        assertEquals(1, r.ports.spoken.size)
    }

    @Test fun doesNotAdoptWhileAlreadyOwnerOrFromStalePhone() {
        val r = Rig()
        r.arm(MOVING)
        val phoneOwned = FlowStatus.of(FlowSnapshot(state = FlowState.S3, powerOn = true, consents = Consents(true, true, true)), Owner.PHONE)
        assertFalse(r.sync(2_000, watch(WristVerdict.NORMAL), peerFlow = phoneOwned).adopted)
        assertEquals(FlowState.S0, r.hub.snapshot.state)

        val fresh = Rig()
        fresh.hub.onReading(MOVING, 0)
        assertFalse("휴대폰 상태도 10초 넘게 낡음", fresh.sync(0, null, peerFlow = phoneOwned, peerAgeMs = 10_001).adopted)
    }

    /**
     * B-4 끝단(codex 재검증 1차 「상」) — 차가 읽은 시동 꺼짐(속도 · 기어 모름)이 toProtocol → toFlow → 상태 기계까지 가서
     * 5초 디바운스 뒤 정지로 보이고, 사건 중이면 정차 쪽 상태로 간다.
     */
    @Test fun ignitionOffReadingReachesTheMachineAsStopAfter5s() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        val off = CarReading(ignitionRaw = VehicleMapping.IGNITION_OFF, ux = UX_PARKED)
        r.hub.onReading(off, 4_000)
        assertEquals("Vehicle 에 실림", false, r.hub.vehicle().ignitionOn)
        assertFalse("시동 꺼짐 즉시는 정지 아님(디바운스)", r.hub.snapshot.stopped)
        r.hub.onReading(off, 8_000)
        assertFalse(r.hub.snapshot.stopped)
        r.hub.onReading(off, 9_000)
        r.hub.onReading(off, 10_000)
        assertTrue("5초 뒤 정지", r.hub.snapshot.stopped)
        assertTrue("정차 쪽 상태로 감", r.hub.snapshot.state != FlowState.S1)
    }

    @Test fun unknownIgnitionWithoutSpeedOrGearIsNotAStop() {
        val r = Rig()
        r.arm(MOVING)
        val unknown = CarReading(ux = UX_PARKED)
        for (t in 2_000L..20_000L step 2_000) r.hub.onReading(unknown, t)
        assertFalse("시동 모름 · 속도 모름 · 기어 모름은 정지 아님", r.hub.snapshot.stopped)
    }

    /** 「휴대폰 연결 끊기」의 확정 정지는 기어 P 만 — 기어를 모르고 속도 0 · 주차 브레이크여도 열지 않는다(사용자 결정 「완전 정지는 기어 P 또는 시동 꺼짐」 · 계약 §3.2). */
    @Test fun unpairNeedsGearPNotParkingBrake() {
        val brakeOnly = Rig()
        brakeOnly.hub.onReading(CarReading(speedMps = 0f, gearRaw = null, parkingBrake = true, ux = UX_PARKED), 0)
        assertTrue("짝짓기 문은 열림(기어 모름 · 속도 0)", brakeOnly.hub.setupOk())
        assertFalse("주차 브레이크만으로는 끊기 문을 열지 않는다", brakeOnly.hub.unpairOk())
        val gearP = Rig()
        gearP.hub.onReading(CarReading(speedMps = 0f, gearRaw = VehicleMapping.GEAR_PARK, parkingBrake = false, ux = UX_PARKED), 0)
        assertTrue(gearP.hub.unpairOk())
    }
}
