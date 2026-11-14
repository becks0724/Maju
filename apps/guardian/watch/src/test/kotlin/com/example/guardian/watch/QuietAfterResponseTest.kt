// 응답 뒤 출력 멈춤 시험 — 응답 뒤 10초 연결 증거가 없으면 한 번 멈추고, 증거가 있거나 응답 전이면 멈추지 않으며, 새 지시면 처음으로(합동 점검 C-3)
package com.example.guardian.watch

import com.example.guardian.watch.core.QuietAfterResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietAfterResponseTest {
    @Test fun noEvidenceFor10sAfterResponseMutesOnce() {
        val q = QuietAfterResponse()
        q.onRespond(100_000)
        assertFalse(q.muteDue(109_999))
        assertTrue("10초 — 멈춤", q.muteDue(110_000))
        assertTrue(q.muted)
        assertFalse("한 번만", q.muteDue(111_000))
    }

    @Test fun evidenceAfterResponseKeepsOutputs() {
        for (kind in listOf("중계 sync", "Data Layer")) {
            val q = QuietAfterResponse()
            q.onRespond(100_000)
            q.onEvidence(sentAtMs = 105_000)
            assertFalse("$kind 증거가 있으면 ALERT_STOP 까지 그대로", q.muteDue(200_000))
        }
    }

    /** 응답 전에 보낸 요청의 성공은 응답이 닿았다는 증거가 아니다. */
    @Test fun evidenceSentBeforeResponseDoesNotCount() {
        val q = QuietAfterResponse()
        q.onRespond(100_000)
        q.onEvidence(sentAtMs = 99_000)
        assertTrue(q.muteDue(110_000))
    }

    @Test fun unansweredAlertIsNeverMuted() {
        val q = QuietAfterResponse()
        q.onEvidence(sentAtMs = 0)
        assertFalse(q.muteDue(600_000))
    }

    /** 새 경보 시작 · 정지가 오면 처음으로 — 다시 울리고, 새 응답 뒤에야 다시 잰다. */
    @Test fun newCommandResets() {
        val q = QuietAfterResponse()
        q.onRespond(100_000)
        assertTrue(q.muteDue(110_000))
        q.reset()
        assertFalse(q.muted)
        assertFalse("응답 전이라 멈추지 않음", q.muteDue(200_000))
        q.onRespond(200_000)
        assertTrue(q.muteDue(210_000))
    }

    /** 다시 누르면 그 누름부터 10초를 다시 잰다(앞 누름 뒤의 증거는 새 누름의 증거가 아님). */
    @Test fun secondPressRestartsWindow() {
        val q = QuietAfterResponse()
        q.onRespond(100_000)
        q.onEvidence(sentAtMs = 101_000)
        q.onRespond(105_000)
        assertFalse(q.muteDue(114_999))
        assertTrue(q.muteDue(115_000))
    }
}
