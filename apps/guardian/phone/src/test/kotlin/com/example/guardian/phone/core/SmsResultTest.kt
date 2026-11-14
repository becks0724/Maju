// 보호자 문자 전송 결과 시험(코드 리뷰 중 5) — 결과 콜백을 가짜로 넣어 성공 · 일부 실패 · 시간 초과의 기록 · 재전송 · 화면 문구를 본다
package com.example.guardian.phone.core

import com.example.guardian.phone.ui.PhoneTexts
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.WireFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsResultTest {
    private val store = MemoryStore()
    private val fx = FakeEffects().apply { smsDelivery = null } // 결과는 시험이 직접 돌려준다
    private val ex = CommandExecutor(store, fx, FakeBodies())
    private var n = 0

    private fun at(now: Long) = ExecContext(stopped = false, testSession = false, nowLocal = now, nowWall = 1_000_000 + now)

    private fun cmd(eventId: String = "event-1", kind: GuardianSmsWireKind = GuardianSmsWireKind.EVENT, test: Boolean = false): Command {
        val p = Payload.SendGuardianSms(kind, eventId, WireFacts(SnapshotClock.LOCAL), test = test)
        return Command("id-${n++}", p.type, p.toJson(), 30_000, test, Party.CAR, CommandTo.PHONE)
    }

    private fun sentRecords() = store.smsRecords.filter { it.sent }

    @Test fun allPartsOkThenAckAndNoResend() {
        val c = cmd()
        assertFalse(ex.execute(c, at(0))) // 결과 전에는 ack 하지 않는다
        assertTrue(fx.smsLog.isEmpty())
        assertEquals(1, sentRecords().size) // 기다리는 동안 같은 사건 문자를 막는 기록
        assertFalse(ex.execute(c, at(2_000))) // 다시 와도 또 보내지 않는다
        assertEquals(1, fx.sms.size)

        fx.heldResults.single()(true)
        assertEquals(listOf("EVENT" to true), fx.smsLog)
        assertEquals(Notice.SMS_SENT, fx.notices.last())
        assertTrue(store.isSmsSent("EVENT:event-1"))
        assertTrue(ex.execute(c, at(4_000))) // 이제 ack
        assertTrue(ex.execute(cmd(), at(6_000))) // 같은 사건의 다른 지시 — 중복
        assertEquals(1, fx.sms.size)
    }

    @Test fun failedResultMarksNotSentAndResendsOnRedelivery() {
        val c = cmd()
        assertFalse(ex.execute(c, at(0)))
        fx.heldResults.removeAt(0)(false)
        assertEquals(listOf("EVENT" to false), fx.smsLog)
        assertEquals(Notice.SMS_FAILED, fx.notices.last())
        assertTrue(sentRecords().isEmpty()) // 「보내지 않음」으로 고쳐졌다
        assertFalse(store.isSmsSent("EVENT:event-1"))

        assertFalse(ex.execute(c, at(2_000))) // 다시 온 지시 — 실패를 본 때부터 10초 전에는 다시 보내지 않는다(하5)
        assertEquals(1, fx.sms.size)
        assertFalse(ex.execute(c, at(12_000))) // 10초 뒤 다시 보낸다
        assertEquals(2, fx.sms.size)
        fx.heldResults.removeAt(0)(true)
        assertTrue(ex.execute(c, at(14_000)))
        assertEquals(listOf("EVENT" to false, "EVENT" to true), fx.smsLog)
        assertEquals(1, sentRecords().size)
    }

    @Test fun cancelFailureRetriesAfterEventSent() {
        fx.smsDelivery = true
        assertTrue(ex.execute(cmd(), at(0)))
        fx.smsDelivery = null
        val cancel = cmd(kind = GuardianSmsWireKind.CANCEL)
        assertFalse(ex.execute(cancel, at(1_000)))
        fx.heldResults.removeAt(0)(false)
        assertFalse(ex.execute(cancel, at(3_000))) // 10초 전에는 기다린다(하5)
        assertEquals(2, fx.sms.size)
        assertFalse(ex.execute(cancel, at(13_000))) // 취소도 다시 보낸다(사건 문자 기록이 남아 있으므로)
        assertEquals(3, fx.sms.size)
    }

    @Test fun timeoutIsUnconfirmedAndNeverResent() {
        val c = cmd()
        assertFalse(ex.execute(c, at(1_000)))
        ex.onTick(at(1_000 + CommandExecutor.SMS_RESULT_TIMEOUT_MS - 1))
        assertTrue(fx.smsLog.isEmpty())
        ex.onTick(at(1_000 + CommandExecutor.SMS_RESULT_TIMEOUT_MS))
        assertEquals(listOf("EVENT" to null), fx.smsLog)
        assertEquals(Notice.SMS_UNCONFIRMED, fx.notices.last())
        assertEquals(1, sentRecords().size) // 보낸 기록은 남긴다 — 중복 문자가 더 나쁘다

        assertTrue(ex.execute(c, at(40_000))) // ack, 다시 보내지 않는다
        assertTrue(ex.execute(cmd(), at(42_000)))
        assertEquals(1, fx.sms.size)
        fx.heldResults.single()(false) // 늦게 온 결과는 버린다
        assertEquals(listOf("EVENT" to null), fx.smsLog)
        assertEquals(1, sentRecords().size)
    }

    @Test fun requestRejectedIsFailedImmediately() {
        fx.smsResult = false
        assertFalse(ex.execute(cmd(), at(0)))
        assertEquals(listOf("EVENT" to false), fx.smsLog)
        assertTrue(sentRecords().isEmpty())
    }

    @Test fun testSmsFailureIsNotRetriedAndTimeoutUsesTick() {
        val c = cmd(eventId = "t-1", test = true)
        assertTrue(ex.execute(c, at(0)))
        ex.confirmTestSms("EVENT:t-1", send = true)
        fx.heldResults.removeAt(0)(false)
        assertEquals(listOf("EVENT" to false), fx.smsLog)
        assertTrue(ex.execute(cmd(eventId = "t-1", test = true), at(2_000))) // 다시 묻지도 보내지도 않는다
        assertEquals(1, fx.sms.size)

        // 시작 시각이 없는 테스트 문자는 첫 주기에서 기다림을 시작한다
        ex.execute(cmd(eventId = "t-2", test = true), at(3_000))
        ex.confirmTestSms("EVENT:t-2", send = true)
        ex.onTick(at(10_000))
        ex.onTick(at(10_000 + CommandExecutor.SMS_RESULT_TIMEOUT_MS))
        assertEquals("EVENT" to null, fx.smsLog.last())
    }

    // ── 사건 문자와 취소 문자의 순서(app-qa 중, PM 결정 2026-11-02) ──

    private val cancel get() = cmd(kind = GuardianSmsWireKind.CANCEL)

    @Test fun repro_cancelWhileEventWaitingThenEventFails_guardianGetsNothingAfterCancel() {
        val ev = cmd()
        assertFalse(ex.execute(ev, at(0)))
        val c = cancel
        assertFalse(ex.execute(c, at(1_000)))
        fx.heldResults.removeAt(0)(false)
        assertTrue(ex.execute(ev, at(2_000))) // 재전달 — 다시 보내지 않고 ack
        assertTrue(ex.execute(c, at(2_000)))
        assertEquals(1, fx.sms.size) // 취소 뒤 「사건 + 위치」가 가지 않는다
    }

    @Test fun rule1_cancelWaitsWhileEventWaiting() {
        assertFalse(ex.execute(cmd(), at(0)))
        val c = cancel
        assertFalse(ex.execute(c, at(1_000))) // 보내지도 ack 하지도 않는다
        assertFalse(ex.execute(c, at(3_000))) // 다시 와도 또 기다린다
        assertEquals(1, fx.sms.size)
        assertTrue(fx.heldResults.size == 1)
    }

    @Test fun rule2_cancelSentAfterEventSentOrUnconfirmed() {
        // 보냄
        val ev = cmd()
        assertFalse(ex.execute(ev, at(0)))
        val c = cancel
        assertFalse(ex.execute(c, at(1_000)))
        fx.heldResults.removeAt(0)(true)
        assertFalse(ex.execute(c, at(2_000))) // 이제 취소를 보낸다(결과 기다림)
        assertEquals(2, fx.sms.size)
        fx.heldResults.removeAt(0)(true)
        assertTrue(ex.execute(c, at(4_000)))
        // 확인 못 함(갔을 수 있음)
        val ev2 = cmd(eventId = "event-2")
        assertFalse(ex.execute(ev2, at(10_000)))
        ex.onTick(at(10_000 + CommandExecutor.SMS_RESULT_TIMEOUT_MS))
        assertFalse(ex.execute(cmd(eventId = "event-2", kind = GuardianSmsWireKind.CANCEL), at(41_000)))
        assertEquals(4, fx.sms.size)
        assertTrue(fx.sms.last().second.isNotEmpty())
    }

    @Test fun rule3_eventFailedThenCancel_neitherSentAndNoticeShown() {
        val ev = cmd()
        assertFalse(ex.execute(ev, at(0)))
        fx.heldResults.removeAt(0)(false)
        assertTrue(ex.execute(cancel, at(1_000))) // 보내지 않고 ack
        assertEquals(Notice.SMS_SKIPPED_RESPONDED, fx.notices.last())
        assertEquals("운전자가 응답해 보호자 문자를 보내지 않았습니다", PhoneTexts.notice(Notice.SMS_SKIPPED_RESPONDED))
        assertTrue(ex.execute(ev, at(2_000))) // 사건 문자 재전달도 ack, 다시 보내지 않는다
        assertEquals(1, fx.sms.size)
        assertTrue(sentRecords().isEmpty())
    }

    @Test fun rule4_eventAfterCancelHandledIsNotSent() {
        // 예외 실패 경로(요청 거절) — 사건 문자를 못 보낸 뒤 취소가 처리되면 그 뒤 재전달되는 사건 문자는 보내지 않는다
        fx.smsResult = false
        val ev = cmd()
        assertFalse(ex.execute(ev, at(0)))
        fx.smsResult = true
        assertTrue(ex.execute(cancel, at(1_000)))
        assertTrue(ex.execute(ev, at(2_000)))
        assertTrue(fx.sms.isEmpty())
        // 사건 문자 없이 취소가 먼저 온 사건(NO_EVENT_SENT)도 같다
        assertTrue(ex.execute(cmd(eventId = "event-3", kind = GuardianSmsWireKind.CANCEL), at(3_000)))
        assertTrue(ex.execute(cmd(eventId = "event-3"), at(4_000)))
        assertTrue(fx.sms.isEmpty())
    }

    @Test fun settledEntriesAreDroppedAfterTtl() {
        // 보내지 못한 사건 문자가 다시 오지 않은 채 ttl 이 지나면 항목을 지운다 — 그 뒤 같은 사건 취소는 규칙 3 이 아니라 기본 판단(사건 문자 없음)
        assertFalse(ex.execute(cmd(eventId = "e-a"), at(0)))
        fx.heldResults.removeAt(0)(false)
        ex.onTick(at(1_000)) // 결과가 난 시각을 적는다
        ex.onTick(at(1_000 + 30_001))
        assertTrue(ex.execute(cmd(eventId = "e-a", kind = GuardianSmsWireKind.CANCEL), at(32_000)))
        assertFalse(Notice.SMS_SKIPPED_RESPONDED in fx.notices)
        // 대조 — ttl 안이면 남아 있어 규칙 3 이 탄다
        assertFalse(ex.execute(cmd(eventId = "e-b"), at(40_000)))
        fx.heldResults.removeAt(0)(false)
        ex.onTick(at(41_000))
        ex.onTick(at(41_000 + 30_000))
        assertTrue(ex.execute(cmd(eventId = "e-b", kind = GuardianSmsWireKind.CANCEL), at(71_500)))
        assertEquals(Notice.SMS_SKIPPED_RESPONDED, fx.notices.last())
        assertEquals(2, fx.sms.size)
    }

    /**
     * 코드 리뷰 2026-11-08 하5 — 휴대폰 주인 경로(localRetry)는 같은 지시를 매 틱 다시 부른다. 보내지 못한 문자는 실패 뒤 10초가 지나야
     * 다시 보내고, 「보내지 못함」 안내 · 문자 기록은 같은 문자의 첫 실패 때 한 번만 낸다(PM 결정).
     */
    @Test fun failedResendIsSpacedAndNoticedOnce() {
        fx.smsDelivery = false
        val c = cmd()
        for (t in 0L until 30_000L step 1_000L) assertFalse(ex.execute(c, at(t)))
        assertEquals("0 · 10 · 20초에만 보낸다", 3, fx.sms.size)
        assertEquals(1, fx.notices.count { it == Notice.SMS_FAILED })
        assertEquals(listOf("EVENT" to false), fx.smsLog)
        assertTrue(sentRecords().isEmpty())
        // 다시 보낸 문자가 가면 그 결과는 기록 · 안내한다
        fx.smsDelivery = true
        assertTrue(ex.execute(c, at(30_000)))
        assertEquals(4, fx.sms.size)
        assertEquals(listOf("EVENT" to false, "EVENT" to true), fx.smsLog)
        assertEquals(Notice.SMS_SENT, fx.notices.last())
    }

    /** 결과가 늦게 온 실패도 같다 — 실패를 처음 본 때부터 10초. */
    @Test fun lateFailureAlsoWaitsGap() {
        val c = cmd()
        assertFalse(ex.execute(c, at(0)))
        fx.heldResults.removeAt(0)(false)
        assertFalse(ex.execute(c, at(5_000)))
        assertFalse(ex.execute(c, at(14_000)))
        assertEquals(1, fx.sms.size)
        assertFalse(ex.execute(c, at(15_000)))
        assertEquals(2, fx.sms.size)
        fx.heldResults.removeAt(0)(false)
        assertEquals("두 번째 실패는 안내 · 기록하지 않는다", 1, fx.notices.count { it == Notice.SMS_FAILED })
        assertEquals(listOf("EVENT" to false), fx.smsLog)
    }

    @Test fun screenTexts() {
        assertEquals("보내지 못함", PhoneTexts.GUARDIAN_FAILED)
        assertEquals("보냈는지 확인 못 함", PhoneTexts.GUARDIAN_UNCONFIRMED)
        assertTrue(PhoneTexts.notice(Notice.SMS_FAILED).contains("신호"))
        assertTrue(PhoneTexts.notice(Notice.SMS_UNCONFIRMED).contains("확인하지 못했습니다"))
    }

    @Test fun partsAllOkOrAnyFail() {
        val p = SmsParts()
        p.expect("a", 3)
        assertNull(p.onPart("a", true))
        assertNull(p.onPart("a", true))
        assertEquals(true, p.onPart("a", true))
        p.expect("b", 3)
        assertNull(p.onPart("b", true))
        assertEquals(false, p.onPart("b", false)) // 일부 실패면 바로 실패
        assertNull(p.onPart("b", true)) // 그 뒤 조각은 버린다
        assertNull(p.onPart("모름", true))
        p.expect("c", 1)
        p.forget("c")
        assertNull(p.onPart("c", true))
    }
}
