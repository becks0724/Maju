// 휴대폰 화면 문구 G1 금지어 시험 — :shared text/ForbiddenWords 공개 목록으로 앱 화면 문구를 훑는다
package com.example.guardian.phone.ui

import com.example.guardian.shared.text.ForbiddenWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTextsTest {
    @Test fun phoneTextsHaveNoForbiddenWords() {
        assertTrue(PhoneTexts.all.size >= 100)
        // 차 화면 문구(2026-11-01)도 훑는다
        assertTrue(PhoneTexts.all.containsAll(listOf(PhoneTexts.CONSENT_CHANGED, PhoneTexts.CONSENT_CAR_DISPLAY, PhoneTexts.HIDE_HR_ON_CAR_SUB)))
        for (s in PhoneTexts.all) {
            val hit = ForbiddenWords.violations(s)
            assertTrue("$s: $hit", hit.isEmpty())
        }
    }

    /** 상담 권유 문장은 사용자 결정 ⑥ 그대로이고 금지어 시험 대상(all)에 들어 있다. */
    @Test fun consultLineIsExactAndScanned() {
        assertTrue(PhoneTexts.RESULT_CONSULT == "몸 상태가 걱정되면 의료진과 상담해 보세요")
        assertTrue(PhoneTexts.RESULT_CONSULT in PhoneTexts.all)
        assertTrue(ForbiddenWords.violations(PhoneTexts.RESULT_CONSULT).isEmpty())
    }

    /** 판 3 문구 — 금지어 · 콜론 끝 없음, ② 항목에 응답 확인 상태, ③ 에 중계 서버 · 주소 찾기 · 24시간, 휴대폰 위치가 차로 간다는 말 없음. */
    @Test fun consentV3TextsMatchFacts() {
        val texts = PhoneTexts.CONSENT_BODY_2 + PhoneTexts.CONSENT_BODY_3 +
            listOf(PhoneTexts.CONSENT_CHANGED, PhoneTexts.CONSENT_CHANGED_FROM_1, PhoneTexts.PERM_BG_LOCATION_ASK, PhoneTexts.SERVICE_NOTIFY_BODY)
        for (s in texts) {
            assertTrue(s, s in PhoneTexts.all)
            assertTrue("$s: ${ForbiddenWords.violations(s)}", ForbiddenWords.violations(s).isEmpty())
            assertFalse(s, s.trimEnd().endsWith(":"))
        }
        assertTrue(PhoneTexts.CONSENT_BODY_2[0].contains("응답 확인이 필요한 상태"))
        assertTrue(PhoneTexts.CONSENT_BODY_3[1].contains("중계 서버"))
        assertTrue(PhoneTexts.CONSENT_BODY_3[1].contains("주소 찾기"))
        assertTrue(PhoneTexts.CONSENT_BODY_3[3].contains("24시간"))
        // 검증 2차 지적 — ② 보관에도 중계 지움 시점, ③ 항목에 문자에 실리는 정확도 · 속도 · 잰 시각, ③ 목적에 주행과 정차 판단
        assertTrue(PhoneTexts.CONSENT_BODY_2[3].contains("24시간"))
        for (w in listOf("정확도", "속도", "잰 시각")) assertTrue(w, PhoneTexts.CONSENT_BODY_3[0].contains(w))
        assertTrue(PhoneTexts.CONSENT_BODY_3[2].contains("주행과 정차 판단"))
        // 검증 2차 하4 — 짝이 없으면 「차 앱에 보내는 중」이라고 하지 않는다. 위치가 막힌 안내가 먼저다
        assertEquals(PhoneTexts.SERVICE_NOTIFY_BODY, PhoneTexts.serviceNotifyBody(locationBlocked = false, paired = true))
        assertEquals(PhoneTexts.SERVICE_NOTIFY_BODY_UNPAIRED, PhoneTexts.serviceNotifyBody(locationBlocked = false, paired = false))
        assertEquals(PhoneTexts.SERVICE_NOTIFY_BODY_NO_LOCATION, PhoneTexts.serviceNotifyBody(locationBlocked = true, paired = false))
        assertFalse(PhoneTexts.SERVICE_NOTIFY_BODY_UNPAIRED.contains("보내는 중"))
        assertTrue(ForbiddenWords.violations(PhoneTexts.SERVICE_NOTIFY_BODY_UNPAIRED).isEmpty())
        assertFalse(PhoneTexts.PERM_BG_LOCATION_ASK.contains("차 앱"))
        assertFalse(PhoneTexts.SERVICE_NOTIFY_BODY.contains("위치"))
    }

    @Test fun testBandJoinsSharedWords() {
        assertTrue(PhoneTexts.TEST_BAND == "테스트 · 실제 상황 아님")
    }
}
