// 지시 실행기 시험 — 중복 막기 · previewOnly · 정지 아닐 때 119 작성 안 열림 · 테스트 확인 · 동의 없을 때 · 119 로 보내는 경로 없음
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.AlertStopReason
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandExecutorTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private val bodies = FakeBodies()
    private val ex = CommandExecutor(store, fx, bodies)
    private val stopped = ExecContext(stopped = true, testSession = false)
    private val moving = ExecContext(stopped = false, testSession = false)
    private var n = 0

    private fun cmd(p: Payload, test: Boolean = false) =
        Command("id-${n++}", p.type, p.toJson(), 30_000, test, Party.CAR, CommandTo.PHONE)

    /** 지시에는 사건 사실만 — 본문은 [FakeBodies] 가 eventId 로 정한다. */
    private fun sms(kind: GuardianSmsWireKind = GuardianSmsWireKind.EVENT, eventId: String = "event-1", body: String = "[앱 안내] 본문"): Payload.SendGuardianSms {
        bodies.guardian[eventId] = body
        return Payload.SendGuardianSms(kind, eventId, WireFacts(SnapshotClock.LOCAL), test = false)
    }

    /** 119 지시 — [FakeBodies] 는 eventId 를 본문으로 돌려준다. */
    private fun o119(body: String, previewOnly: Boolean) =
        Payload.Open119Compose(eventId = body, facts = WireFacts(SnapshotClock.LOCAL), previewOnly = previewOnly, test = false)

    @Test fun sameCommandIdRunsOnce() {
        val c = cmd(Payload.AlertStart(FlowState.AP, 2, false, true, true, 20_000))
        assertTrue(ex.execute(c, stopped))
        assertTrue(ex.execute(c, stopped))
        assertEquals(1, fx.alarms.size)
    }

    @Test fun alertStartWithoutSoundDoesNotRingAndStopStops() {
        ex.execute(cmd(Payload.AlertStart(FlowState.S1, 1, true, false, false, 15_000)), moving)
        assertTrue(fx.alarms.isEmpty())
        ex.execute(cmd(Payload.AlertStop(AlertStopReason.CLEARED)), moving)
        assertEquals(1, fx.alarmStops)
    }

    @Test fun guardianSmsOncePerKindAndEvent() {
        assertTrue(ex.execute(cmd(sms()), moving))
        assertTrue(ex.execute(cmd(sms()), moving)) // 다른 지시 id, 같은 사건
        assertTrue(ex.execute(cmd(sms(GuardianSmsWireKind.CANCEL)), moving)) // 취소는 따로 한 번
        assertTrue(ex.execute(cmd(sms(GuardianSmsWireKind.CANCEL)), moving))
        assertTrue(ex.execute(cmd(sms(eventId = "event-2")), moving)) // 새 사건
        assertEquals(3, fx.sms.size)
        assertTrue(fx.sms.all { it.first == GUARDIAN })
        assertTrue(Notice.SMS_SENT in fx.notices)
        assertEquals(listOf("EVENT" to true, "CANCEL" to true, "EVENT" to true), fx.smsLog)
    }

    @Test fun smsFailureIsRetriedThenSentOnce() {
        fx.smsResult = false
        val c = cmd(sms())
        assertFalse(ex.execute(c, moving))
        fx.smsResult = true
        assertFalse(ex.execute(c, moving)) // 10초 전에는 다시 보내지 않는다(하5)
        assertTrue(ex.execute(c, moving.copy(nowLocal = CommandExecutor.SMS_RETRY_GAP_MS)))
        assertEquals(1, fx.sms.size)
    }

    @Test fun noSmsWithoutConsentOrPower() {
        store.registration = fullRegistration(consents = Consents(emergencyInfo = true, health = true, location = false))
        ex.execute(cmd(sms(eventId = "a")), moving)
        store.registration = fullRegistration(powerOn = false)
        ex.execute(cmd(sms(eventId = "b")), moving)
        store.registration = fullRegistration(consents = Consents(emergencyInfo = false, health = true, location = true))
        ex.execute(cmd(sms(eventId = "c")), moving)
        assertTrue(fx.sms.isEmpty())
        assertEquals(3, fx.notices.count { it == Notice.SMS_SKIPPED_NO_CONSENT })
    }

    @Test fun testSmsAsksFirstAndSendsOnlyToGuardian() {
        ex.execute(cmd(sms(body = "[테스트] [앱 안내] 본문"), test = true), stopped)
        assertTrue(fx.sms.isEmpty())
        assertEquals(1, fx.asked.size)
        ex.confirmTestSms(fx.asked[0].key, send = true)
        assertEquals(listOf(GUARDIAN to "[테스트] [앱 안내] 본문"), fx.sms)
        // 같은 사건은 다시 묻지 않는다
        ex.execute(cmd(sms(body = "[테스트] [앱 안내] 본문"), test = true), stopped)
        assertEquals(1, fx.asked.size)
    }

    /**
     * 코드 리뷰 2026-11-09 새로 찾은 것(b) — 확인 대화(P17)를 띄운 뒤 철회 · 끄기 · 보호자 번호 지움이 일어나도 「보내기」가 그대로 보냈다.
     * 보내기 직전에 켬 · 동의 · 보호자 번호를 다시 보고, 없으면 보내지 않고 대화를 닫는다(다시 묻지 않음).
     */
    @Test fun testSmsConfirmRechecksPowerConsentAndGuardian() {
        val changes = listOf<(Registration) -> Registration>(
            { it.copy(powerOn = false) },
            { it.copy(consents = it.consents.copy(emergencyInfo = false)) },
            { it.copy(consents = com.example.guardian.shared.report.Consents()) },
            { it.copy(guardianPhone = "") },
            { it.copy(guardianPhone = "01000000002") },
        )
        changes.forEachIndexed { i, change ->
            store.registration = fullRegistration()
            ex.execute(cmd(sms(eventId = "event-$i"), test = true), stopped)
            val p = fx.asked.last()
            store.registration = change(fullRegistration())
            ex.confirmTestSms(p.key, send = true)
            assertTrue("$i — 보내지 않는다: ${fx.sms}", fx.sms.isEmpty())
            assertTrue("$i — 대화를 닫는다", ex.pending().isEmpty())
        }
    }

    @Test fun declinedTestSmsIsNotSent() {
        ex.execute(cmd(sms(), test = true), stopped)
        ex.confirmTestSms(fx.asked[0].key, send = false)
        assertTrue(fx.sms.isEmpty())
    }

    @Test fun unmarkedSmsDuringTestSessionIsNotSent() {
        ex.execute(cmd(sms()), ExecContext(stopped = true, testSession = true))
        assertTrue(fx.sms.isEmpty())
        assertTrue(fx.asked.isEmpty())
        assertTrue(Notice.SMS_SKIPPED_UNMARKED_TEST in fx.notices)
    }

    /** 에어백 사고는 늘 실제(계약 §4.6) — 시험 중에도 ACCIDENT test=false 는 보내고, EVENT · CANCEL · OBSERVE 는 여전히 막는다. */
    @Test fun realAccidentSmsIsSentDuringTestSessionButOtherKindsAreNot() {
        val inTest = ExecContext(stopped = true, testSession = true)
        ex.execute(cmd(sms(GuardianSmsWireKind.EVENT, "event-9")), inTest)
        ex.execute(cmd(sms(GuardianSmsWireKind.CANCEL, "event-9")), inTest)
        ex.execute(cmd(sms(GuardianSmsWireKind.OBSERVE, "event-9")), inTest)
        assertTrue(fx.sms.isEmpty())
        assertEquals(3, fx.notices.count { it == Notice.SMS_SKIPPED_UNMARKED_TEST })
        ex.execute(cmd(sms(GuardianSmsWireKind.ACCIDENT, "accident-1", "[앱 안내] 사고")), inTest)
        assertEquals(listOf(GUARDIAN to "[앱 안내] 사고"), fx.sms)
        assertTrue(fx.asked.isEmpty()) // 실제 사고라 테스트 확인을 묻지 않는다
        assertEquals(listOf("ACCIDENT" to true), fx.smsLog)
        // 한 사건 한 번 · 동의 · 켬 방어는 그대로
        ex.execute(cmd(sms(GuardianSmsWireKind.ACCIDENT, "accident-1")), inTest)
        assertEquals(1, fx.sms.size)
        store.registration = fullRegistration(powerOn = false)
        ex.execute(cmd(sms(GuardianSmsWireKind.ACCIDENT, "accident-2")), inTest)
        assertEquals(1, fx.sms.size)
    }

    /** 테스트가 실제 사고로 끝나면 묻던 테스트 문자는 보내지 않고 다시 묻지 않으며, 테스트 119 미리보기 본문은 버린다. */
    @Test fun dropTestStateClearsPendingTestSmsAndPreviewOnly() {
        ex.execute(cmd(sms(), test = true), stopped)
        ex.execute(cmd(o119("미리보기", false), test = true), stopped)
        assertEquals(1, ex.pending().size)
        ex.dropTestState()
        assertTrue(ex.pending().isEmpty())
        assertTrue(ex.last119 == null)
        ex.execute(cmd(sms(), test = true), stopped)
        assertEquals(1, fx.asked.size)
        assertTrue(fx.sms.isEmpty())
        // 실제 119 본문은 남는다
        ex.execute(cmd(o119("실제", false)), stopped)
        ex.dropTestState()
        assertEquals("실제", ex.last119?.first?.full)
    }

    @Test fun compose119NeverOpensWhileMoving() {
        ex.execute(cmd(o119("[119 구조요청] 본문", false)), moving)
        assertTrue(fx.composed.isEmpty())
        assertTrue(fx.noResponses.isEmpty())
        assertTrue(Notice.COMPOSE_WAITING_STOP in fx.notices)
        assertFalse(ex.openCompose(moving))
        assertTrue(fx.composed.isEmpty())
        assertTrue(ex.openCompose(stopped))
        assertEquals(listOf("[119 구조요청] 본문"), fx.composed)
    }

    /** 주행 중에 받은 119 지시는 받아 두었다가 정지로 바뀌는 순간 P12 를 띄운다(app-qa 1차 4). */
    @Test fun composeReceivedWhileMovingShowsOnStop() {
        ex.execute(cmd(o119("본문", false)), moving)
        ex.onTick(moving)
        assertTrue(fx.noResponses.isEmpty())
        ex.onTick(stopped)
        assertEquals(listOf(Body119("본문", null)), fx.noResponses)
        ex.onTick(stopped) // 한 번만
        assertEquals(1, fx.noResponses.size)
        assertTrue(ex.openCompose(stopped))
        assertEquals(listOf("본문"), fx.composed)
    }

    @Test fun alertStopBeforeStopDropsWaitingCompose() {
        ex.execute(cmd(o119("본문", false)), moving)
        ex.execute(cmd(Payload.AlertStop(AlertStopReason.CLEARED)), moving)
        ex.onTick(stopped)
        assertTrue(fx.noResponses.isEmpty())
    }

    /** 지시를 받으면 P12 만 띄우고, 문자 앱은 사람이 버튼을 눌렀을 때만 연다 — 열면 알람 소리만 줄인다. */
    @Test fun compose119ShowsScreenAndOpensOnlyByHand() {
        ex.execute(cmd(o119("본문", false)), stopped)
        assertEquals(listOf(Body119("본문", null)), fx.noResponses)
        assertTrue(fx.composed.isEmpty())
        assertTrue(fx.previews.isEmpty())
        assertTrue(ex.openCompose(stopped))
        assertEquals(listOf("본문"), fx.composed)
        assertEquals(1, fx.mutes)
        assertEquals(0, fx.alarmStops)
    }

    @Test fun bodySourceDecidesText() {
        // 본문은 휴대폰이 만든다 — 조립 결과와 초단문 판이 그대로 P12 에 간다
        bodies.ultra = "초단문"
        ex.execute(cmd(o119("휴대폰이 만든 본문", false)), stopped)
        assertEquals(Body119("휴대폰이 만든 본문", "초단문"), fx.noResponses.single())
        // 조립 실패(보호자 미등록 등)면 보내지 않고 화면 · 기록에 남긴다
        bodies.fail = true
        assertTrue(ex.execute(cmd(sms(eventId = "event-9")), moving))
        assertTrue(fx.sms.isEmpty())
        assertTrue(Notice.SMS_SKIPPED_NO_GUARDIAN in fx.notices)
        assertEquals(listOf("EVENT" to false), fx.smsLog)
        ex.execute(cmd(o119("x", false)), stopped)
        assertTrue(Notice.REPORT_FAILED in fx.notices)
        assertEquals(1, fx.noResponses.size)
    }

    @Test fun previewOnlyTestAndSessionNeverOpenCompose() {
        ex.execute(cmd(o119("a", true)), stopped)
        ex.execute(cmd(o119("b", false), test = true), stopped)
        ex.execute(cmd(o119("c", false)), ExecContext(stopped = true, testSession = true))
        assertTrue(ex.openCompose(stopped)) // 미리보기 본문은 다시 열어도 미리보기
        assertTrue(fx.composed.isEmpty())
        assertTrue(fx.noResponses.isEmpty())
        assertEquals(listOf("a", "b", "c", "c"), fx.previews.map { it.full })
        assertTrue(fx.sms.isEmpty())
    }

    @Test fun malformedPayloadIsAckedWithoutEffect() {
        val bad = Command("bad", com.example.guardian.shared.protocol.CommandType.SEND_GUARDIAN_SMS, mapOf("kind" to "EVENT", "body" to "옛 형식"), 30_000, false, Party.CAR, CommandTo.PHONE)
        assertTrue(ex.execute(bad, stopped))
        assertTrue(fx.sms.isEmpty())
    }

    @Test fun showLineIsDisplayed() {
        ex.execute(cmd(Payload.Show("워치 신호 없음 · 워치 연결을 확인하세요", null)), moving)
        assertEquals(listOf("워치 신호 없음 · 워치 연결을 확인하세요"), fx.lines)
    }
}
