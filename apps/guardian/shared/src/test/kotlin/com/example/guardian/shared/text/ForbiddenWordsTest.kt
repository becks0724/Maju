// G1 금지어 정적 시험 — 문구 표 · 문자 조각 · 만들어진 문자 전부에 판정어 · 차량 제어 약속이 없는지 본다
package com.example.guardian.shared.text

import com.example.guardian.shared.report.AlertTimeline
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.report.DrivingInfo
import com.example.guardian.shared.report.LocationInfo
import com.example.guardian.shared.report.LocationSource
import com.example.guardian.shared.report.Profile
import com.example.guardian.shared.report.ReportInput
import com.example.guardian.shared.report.ReportResult
import com.example.guardian.shared.report.Reports
import com.example.guardian.shared.report.VehicleInfo
import com.example.guardian.shared.report.WatchFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ForbiddenWordsTest {
    private fun violations(text: String) = ForbiddenWords.violations(text)

    @Test fun phraseTableHasNoForbiddenWords() {
        for (p in Phrase.entries) {
            assertTrue("${p.name}: ${ForbiddenWords.violations(p)}", ForbiddenWords.violations(p).isEmpty())
        }
    }

    @Test fun exemptNoticeIsNegated() {
        assertTrue(Phrase.NOTICE_NOT_MEDICAL.ko.contains("아니며"))
        assertTrue(Phrase.NOTICE_NOT_MEDICAL.ko.contains("대신하지 않습니다"))
    }

    /** SmsText 에 선언된 문구 상수가 빠짐없이 all 에 들어 있어야 금지어 시험이 다 훑는다. */
    @Test fun smsTextAllCoversEveryConstant() {
        val declared = SmsText::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.name to it.get(null) as String }
        assertTrue(declared.size >= 40)
        for ((name, value) in declared) assertTrue("$name 가 all 에 없음", value in SmsText.all)
    }

    @Test fun smsFragmentsHaveNoForbiddenWords() {
        for (s in SmsText.all) assertTrue("$s: ${violations(s)}", violations(s).isEmpty())
    }

    @Test fun generatedMessagesHaveNoForbiddenWords() {
        val now = 1_793_000_000_000L
        for (e in listOf(true, false)) for (h in listOf(true, false)) for (l in listOf(true, false)) for (t in listOf(true, false))
        for (speed in listOf(50.0, null)) {
            val r = ReportInput(
                nowMs = now,
                profile = Profile("홍길동", LocalDate.of(1968, 5, 1), "김○○", "010-0000-0000", "흰색", "12가3456"),
                consents = Consents(e, h, l),
                location = LocationInfo(null, 37.4, 126.9, null, LocationSource.CAR_NAVI, measuredAtMs = now - 5_000),
                driving = DrivingInfo(stopped = false, speedKmh = speed, carStateMissing = true),
                noResponseSec = 35,
                timeline = AlertTimeline(now - 35_000, null, now),
                watch = WatchFacts(now - 60_000, now, 80, 30, pulseWeak = true, pulseLostAtMs = null, worn = false),
                vehicle = VehicleInfo(null, null, null),
                test = t,
            )
            val texts = listOf(
                Reports.full119(r), Reports.ultraShort119(r), Reports.guardianIncident(r),
                Reports.guardianCancel(r, now), Reports.guardianPowerOff(r, now), Reports.guardianAccident(r), Reports.guardianObserving(r),
            ).filterIsInstance<ReportResult.Ok>().map { it.text }
            for (x in texts) assertTrue("$x: ${violations(x)}", violations(x).isEmpty())
        }
    }

    /** 공개 목록이 시험 전용 사본이 아니라 main 소스이고, 예외는 고지 문장 하나뿐이다. */
    @Test fun publicListAndExemptRule() {
        assertTrue(ForbiddenWords.words.containsAll(listOf("실신", "진단", "자동으로 119", "부정맥")))
        assertEquals(setOf(Phrase.NOTICE_NOT_MEDICAL), ForbiddenWords.exemptPhrases)
        // 예외 문장이 들어 있어도 나머지 부분의 금지어는 잡는다
        assertTrue(ForbiddenWords.violations("안내 · ${Phrase.NOTICE_NOT_MEDICAL.ko}").isEmpty())
        assertEquals(listOf("실신"), ForbiddenWords.violations("${Phrase.NOTICE_NOT_MEDICAL.ko} 실신 확인"))
        assertEquals(listOf("진단"), ForbiddenWords.violations("진단 결과"))
        // 고지 문장을 지운 원문 그대로 검사하면 걸린다(예외는 문장 단위)
        assertTrue(ForbiddenWords.violations("응급 대응 진단").isNotEmpty())
    }

    /** 정차 뒤 무응답 화면은 앱이 구조를 요청하는 것처럼 읽히지 않게 사실만 쓴다(app-design 제안 · PM 채택 2026-10-30). */
    @Test fun s4ScreenStatesFactsOnly() {
        assertEquals("보호자에게 알림 · 가까운 응급실 안내", Phrase.SCREEN_S4.ko)
        for (p in Phrase.entries) assertTrue("${p.name} 가 구조 요청 중처럼 읽힘", !p.ko.contains("구조 요청 중") && !p.ko.contains("구조요청"))
    }

    @Test fun allowedFactsAreUsed() {
        assertTrue(SmsText.PULSE_NOT_FOUND == "맥박 신호 잡히지 않음")
        assertTrue(Phrase.SCREEN_S1.ko.contains("응답 확인 필요"))
        assertTrue(SmsText.NO_RESPONSE == "응답 없음")
    }
}
