// 차 음성 오디오 포커스 판단 시험 — 요청 · 변화 · 반납 로그(문구 이름만), 일시 손실 10초 복귀, 거부 3초 뒤 한 번 재요청, 지연 포커스, 영구 손실 뒤 새 요청(Pleos I-3 · I-7 · I-9)
package com.example.guardian.car

import com.example.guardian.car.FocusDecider.Act
import com.example.guardian.car.FocusDecider.Change
import com.example.guardian.car.FocusDecider.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusDeciderTest {
    private val s3 = "응답이 없어 보호자에게 알림을 보냅니다."
    private val s4 = "보호자에게 알림을 보냅니다. 괜찮으면 버튼을 누르세요."

    private fun List<Act>.speaks() = filterIsInstance<Act.Speak>().map { it.text }
    private fun List<Act>.logs() = filterIsInstance<Act.Log>().map { it.line }

    @Test fun grantedSpeaksAndLogsPhraseNameOnly() {
        val d = FocusDecider()
        val a = d.say(s3, "VOICE_S3_NO_RESPONSE", 0)
        assertEquals(listOf(Act.Request), a.filter { it == Act.Request })
        val b = d.onRequest(Result.GRANTED, 0)
        assertEquals(listOf(s3), b.speaks())
        val log = b.logs().single()
        assertTrue(log, "NAVIGATION_GUIDANCE" in log && "TRANSIENT" in log && "GRANTED" in log && "VOICE_S3_NO_RESPONSE" in log)
        assertFalse("문구 원문을 로그에 남기지 않는다", (a + b).logs().any { s3 in it })
        val end = d.onSpeechEnd("완료")
        assertTrue(end.any { it is Act.Abandon })
        assertTrue("반납 이유를 남긴다", end.logs().any { "반납" in it && "완료" in it })
    }

    @Test fun transientLossPausesAndResumesWithin10s() {
        val d = FocusDecider()
        d.say(s3, "S3", 0); d.onRequest(Result.GRANTED, 0)
        val lost = d.onChange(Change.LOSS_TRANSIENT, 1_000)
        assertTrue("일시 손실이면 멈춘다", Act.Stop in lost)
        assertFalse("포커스는 놓지 않는다(돌아올 수 있게)", lost.any { it is Act.Abandon })
        assertTrue("멈춘 TTS 의 중지 콜백으로 포커스를 놓지 않는다", d.onSpeechEnd("중지").none { it is Act.Abandon })
        val back = d.onChange(Change.GAIN, 10_000)
        assertEquals("10초 안에 돌아오면 다시 말한다", listOf(s3), back.speaks())
    }

    @Test fun transientLossLongerThan10sDropsLine() {
        val d = FocusDecider()
        d.say(s3, "S3", 0); d.onRequest(Result.GRANTED, 0)
        d.onChange(Change.LOSS_TRANSIENT, 1_000)
        val back = d.onChange(Change.GAIN, 11_001)
        assertTrue("10초가 넘으면 다시 말하지 않는다", back.speaks().isEmpty())
        assertTrue(back.any { it is Act.Abandon })
    }

    @Test fun failedRetriesOnceAfter3s() {
        val d = FocusDecider()
        d.say(s3, "S3", 0)
        val f = d.onRequest(Result.FAILED, 0)
        assertTrue(f.speaks().isEmpty())
        assertTrue(Act.RetryIn(3_000) in f)
        assertTrue("3초 전에는 묻지 않는다", d.onRetryTimer(2_999).isEmpty())
        assertEquals(listOf(Act.Request), d.onRetryTimer(3_000).filter { it == Act.Request })
        val f2 = d.onRequest(Result.FAILED, 3_000)
        assertTrue("두 번째 거부 뒤에는 더 묻지 않는다", f2.none { it is Act.RetryIn } && f2.speaks().isEmpty())
        assertTrue(d.onRetryTimer(6_000).isEmpty())
    }

    @Test fun retryGrantedSpeaksOnlyNewestLine() {
        val d = FocusDecider()
        d.say(s3, "S3", 0); d.onRequest(Result.FAILED, 0)
        assertTrue("기다리는 동안 새 줄은 바꿔 끼우기만", d.say(s4, "S4", 1_000).none { it == Act.Request })
        d.onRetryTimer(3_000)
        assertEquals("새 것만 말한다", listOf(s4), d.onRequest(Result.GRANTED, 3_000).speaks())
    }

    @Test fun delayedFocusSpeaksOnGain() {
        val d = FocusDecider()
        d.say(s3, "S3", 0)
        val w = d.onRequest(Result.DELAYED, 0)
        assertTrue("지연이면 아직 말하지 않는다", w.speaks().isEmpty())
        assertTrue("지연 중 새 줄은 다시 요청하지 않는다", d.say(s4, "S4", 500).none { it == Act.Request })
        assertEquals("얻었을 때 최신 줄을 말한다", listOf(s4), d.onChange(Change.GAIN, 2_000).speaks())
    }

    @Test fun permanentLossThenNextStepRequestsAgain() {
        val d = FocusDecider()
        d.say(s3, "S3", 0); d.onRequest(Result.GRANTED, 0)
        val lost = d.onChange(Change.LOSS, 1_000)
        assertTrue(Act.Stop in lost && lost.any { it is Act.Abandon })
        assertTrue("영구 손실 뒤 GAIN 이 와도 말하지 않는다", d.onChange(Change.GAIN, 2_000).speaks().isEmpty())
        assertTrue("다음 단계 음성은 새로 요청한다", Act.Request in d.say(s4, "S4", 30_000))
        assertEquals(listOf(s4), d.onRequest(Result.GRANTED, 30_000).speaks())
    }

    @Test fun canDuckIsLoggedOnly() {
        val d = FocusDecider()
        d.say(s3, "S3", 0); d.onRequest(Result.GRANTED, 0)
        val a = d.onChange(Change.CAN_DUCK, 500)
        assertTrue(a.none { it == Act.Stop || it is Act.Abandon || it is Act.Speak })
        assertTrue(a.logs().any { "CAN_DUCK" in it })
    }

    @Test fun speakingFlushesWithoutNewRequest() {
        val d = FocusDecider()
        d.say(s3, "S3", 0); d.onRequest(Result.GRANTED, 0)
        val a = d.say(s4, "S4", 1_000)
        assertEquals(listOf(s4), a.speaks())
        assertFalse(Act.Request in a)
    }

    /** app-qa 관문 2 필수 1 — 지연 포커스가 사건이 끝난 뒤(say 가 더 오지 않음) 낡은 경보 음성을 말하지 않는다. */
    @Test fun delayedGainAfterWindowDropsStaleLine() {
        val d = FocusDecider()
        d.say(s4, "S4", 0)
        d.onRequest(Result.DELAYED, 0)
        val late = d.onChange(Change.GAIN, 300_000)
        assertTrue("10초 넘어 얻으면 말하지 않는다", late.speaks().isEmpty())
        assertTrue("쥔 포커스는 놓는다", late.any { it is Act.Abandon })
    }

    @Test fun delayedGainWithinWindowOfLatestLineSpeaksIt() {
        val d = FocusDecider()
        d.say(s3, "S3", 0)
        d.onRequest(Result.DELAYED, 0)
        d.say(s4, "S4", 8_000)
        assertEquals("창은 마지막 줄 시각부터 센다", listOf(s4), d.onChange(Change.GAIN, 17_000).speaks())
    }
}
