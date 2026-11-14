// 알림 · 홈 기록 문구 시험 — 상태 알림 우선순위(중6 · 하9 · C-7), 테스트 문자 알림 본문 상수(보안 9), 위치 제공 통보 · 보낸 문자 기록(보안 7 · D-8)
package com.example.guardian.phone.ui

import com.example.guardian.phone.core.GUARDIAN
import com.example.guardian.phone.core.Missing
import com.example.guardian.phone.core.PendingSms
import com.example.guardian.shared.protocol.GuardianSmsDedup
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.text.ForbiddenWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyTextsTest {
    private val newTexts = listOf(
        PhoneTexts.SERVICE_NOTIFY_BODY_NO_SMS, PhoneTexts.SERVICE_NOTIFY_BODY_NO_NOTIFY, PhoneTexts.SERVICE_NOTIFY_BODY_NO_FULL_SCREEN,
        PhoneTexts.SERVICE_NOTIFY_BODY_SERVER_LOST, PhoneTexts.SERVICE_NOTIFY_BODY_WATCH_ALERT, PhoneTexts.WATCH_ALERT_UNREACHABLE,
        PhoneTexts.TEST_SMS_ASK_BODY, PhoneTexts.LOCATION_SMS_SENT_TITLE, PhoneTexts.LOCATION_SMS_UNCONFIRMED_TITLE,
        PhoneTexts.SMS_HISTORY_HEAD, PhoneTexts.SMS_HISTORY_EMPTY,
    )

    /** 새 문구 — 금지어(G1) 없음, 콜론 끝 없음, 금지어 시험 목록에 들어 있음. 앱을 열라는 안내는 「정차한 뒤」(G5). */
    @Test fun newTextsFollowGuidelines() {
        for (s in newTexts) {
            assertTrue(s, s in PhoneTexts.all)
            assertTrue("$s: ${ForbiddenWords.violations(s)}", ForbiddenWords.violations(s).isEmpty())
            assertFalse(s, s.trimEnd().endsWith(":"))
            if (s.contains("열어") || s.contains("연결하세요")) assertTrue(s, s.contains("정차한 뒤"))
        }
    }

    @Test fun statusBodyPriority() {
        val body = PhoneTexts::serviceNotifyBody
        assertEquals(PhoneTexts.SERVICE_NOTIFY_BODY_NO_LOCATION, body(true, true, Missing.SMS, true, true))
        assertEquals(PhoneTexts.SERVICE_NOTIFY_BODY_NO_SMS, body(false, false, Missing.SMS, true, true))
        assertEquals(PhoneTexts.SERVICE_NOTIFY_BODY_UNPAIRED, body(false, false, null, true, true))
        assertEquals(PhoneTexts.SERVICE_NOTIFY_BODY_SERVER_LOST, body(false, true, null, true, true))
        assertEquals(PhoneTexts.SERVICE_NOTIFY_BODY_WATCH_ALERT, body(false, true, null, false, true))
        assertEquals(PhoneTexts.SERVICE_NOTIFY_BODY, body(false, true, null, false, false))
        // 하9 — 서버에 닿지 않으면 「보내는 중」이라고 하지 않는다
        assertFalse(PhoneTexts.SERVICE_NOTIFY_BODY_SERVER_LOST.contains("보내는 중"))
    }

    /** 감시 중 상태 알림이 말할 권한 — 문자 · 알림 · 전체 화면 알림만, 첫 것. */
    @Test fun serviceBlockedPicksBlockingPermission() {
        assertEquals(null, PhoneTexts.serviceBlocked(listOf(Missing.DND, Missing.BACKGROUND_LOCATION, Missing.PAIR)))
        assertEquals(Missing.NOTIFY, PhoneTexts.serviceBlocked(listOf(Missing.DND, Missing.NOTIFY, Missing.FULL_SCREEN)))
    }

    /** 보안 9 — 테스트 문자 알림 본문은 상수이고 이름 · 번호 · 차 번호 · 본문 전문을 담지 않는다. */
    @Test fun testSmsNotifyBodyIsConstant() {
        val pending = PendingSms("EVENT:e-1", GUARDIAN, "[테스트] 홍길동 12가3456 보호자 김보호 $GUARDIAN 위치 서울")
        val b = UiRules.testSmsNotifyBody(pending)
        assertEquals(PhoneTexts.TEST_SMS_ASK_BODY, b)
        for (w in listOf(GUARDIAN, "홍길동", "김보호", "12가3456", pending.body)) assertFalse(w, b.contains(w))
    }

    /** 위임 결정 3 — 「보호자 문자 다시 보내기」는 정지일 때만, 문구는 금지어 없음. */
    @Test fun resendButtonOnlyWhenStopped() {
        assertTrue(UiRules.showResendSms(canResend = true, stopped = true))
        assertFalse(UiRules.showResendSms(canResend = true, stopped = false))
        assertFalse(UiRules.showResendSms(canResend = false, stopped = true))
        assertTrue(PhoneTexts.RESEND_GUARDIAN_SMS in PhoneTexts.all)
        assertTrue(ForbiddenWords.violations(PhoneTexts.RESEND_GUARDIAN_SMS).isEmpty())
    }

    @Test fun locationNoticeHasTimeOnly() {
        assertEquals(PhoneTexts.LOCATION_SMS_SENT_TITLE to "10:42 · 위치 포함", UiRules.locationNotice(true, "10:42"))
        assertEquals(PhoneTexts.LOCATION_SMS_UNCONFIRMED_TITLE to "10:42 · 위치가 전달됐을 수 있습니다", UiRules.locationNotice(null, "10:42"))
    }

    @Test fun smsHistoryFromStoredRecords() {
        val now = 1_793_000_000_000L
        fun rec(kind: GuardianSmsWireKind, at: Long, sent: Boolean, test: Boolean = false) =
            GuardianSmsDedup.Record(kind, "e", Party.PHONE, null, null, test, now - at, sent)
        val lines = UiRules.smsHistory(
            listOf(
                rec(GuardianSmsWireKind.EVENT, 60_000, true),
                rec(GuardianSmsWireKind.CANCEL, 30_000, true),
                rec(GuardianSmsWireKind.ACCIDENT, 10_000, false),
                rec(GuardianSmsWireKind.EVENT, 20_000, true, test = true),
                rec(GuardianSmsWireKind.EVENT, 25L * 3_600_000, true), // 24시간 지난 것은 뺀다
            ),
            now,
        ) { "t${(now - it) / 1000}" }
        assertEquals(
            listOf(
                "t10 · ${PhoneTexts.SMS_KIND_ACCIDENT} · 보내지 않음",
                "t20 · [테스트] ${PhoneTexts.SMS_KIND_EVENT} · 보냄",
                "t30 · ${PhoneTexts.SMS_KIND_CANCEL} · 보냄",
                "t60 · ${PhoneTexts.SMS_KIND_EVENT} · 보냄",
            ),
            lines,
        )
        // 저장 기록은 본문에 위치가 들어갔는지 모른다 — 「위치 포함」을 말하지 않는다(app-qa 관문 2 조건 1)
        for (l in lines) { assertFalse(l.contains(GUARDIAN)); assertFalse(l.contains("위치 포함")) }
    }
}
