// 워치 화면 문구 G1 시험 — 워치 전용 문구 · 경보 배지 · 한 줄 · 시나리오 이름에 판정어 · 질병 이름 · 차량 제어 약속이 없는지 본다
package com.example.guardian.watch

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.text.ForbiddenWords
import com.example.guardian.watch.text.WatchText
import com.example.guardian.watch.text.WatchTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTextTest {
    /** :shared 공개 금지어 목록(ForbiddenWords)으로 검사한다 — 고지 문장 예외 규칙도 같다. */
    private fun check(text: String) = ForbiddenWords.violations(text).let { assertTrue("$text: $it", it.isEmpty()) }

    @Test fun watchOnlyPhrasesAreClean() = WatchText.entries.forEach { check(it.ko) }

    @Test fun derivedTextsAreClean() {
        WatchSource.entries.forEach { check(WatchTexts.source(it)) }
        Scenario.entries.forEach { check(WatchTexts.scenario(it)) }
        for (s in FlowState.entries) {
            check(WatchTexts.alertLine(s))
            for (level in 1..2) check(WatchTexts.alertBadge(s, level))
        }
    }

    @Test fun alertTextsFollowSpec() {
        // W06 · W07 은 SCREEN_S1 앞부분, W09 는 SCREEN_S3 를 배지 · 줄로 나눈다(spec §5-9)
        assertEquals("응답 확인 필요", WatchTexts.alertLine(FlowState.S1))
        assertEquals("응답 확인 필요", WatchTexts.alertLine(FlowState.AP))
        assertEquals("경보 1 · 진동", WatchTexts.alertBadge(FlowState.S1, 1))
        assertEquals("경보 2 · 소리", WatchTexts.alertBadge(FlowState.AP, 2))
        assertEquals("응답 없음", WatchTexts.alertBadge(FlowState.S4, 2))
        assertEquals("보호자에게 알림", WatchTexts.alertLine(FlowState.S3))
        assertEquals("맥박 신호 잡히지 않음", WatchTexts.scenario(Scenario.PULSE_LOST))
        assertEquals("테스트 모드", WatchTexts.source(WatchSource.SIMULATION))
        // spec 6-3 바꿈(PM 결정 11-02) — 출처 문구에 「판정」 낱말을 쓰지 않는다(G1 판정어 취지)
        assertEquals("심박만 · 맥박 신호 정보 적음", WatchTexts.source(WatchSource.HEALTH_SERVICES))
    }

    @Test fun ongoingStatusIsDriving() {
        // 사용자 지시 11-08 「운동중이 아닌 운전중으로 표시」 — 상태 글자, 알림 제목은 그대로
        assertEquals("운전 중", WatchText.ONGOING_DRIVING.ko)
        assertEquals("응답 확인 켜짐", WatchText.NOTIFICATION_MONITOR.ko)
    }
}
