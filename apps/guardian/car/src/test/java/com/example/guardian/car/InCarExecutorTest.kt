// 차 안 지시 실행기 시험 — 주행 중 버튼 없음 · 정지 뒤만 검색 · 앰비언트 기본 꺼짐 · BLUE_RED_BLINK 거부 · 금지어
package com.example.guardian.car

import com.example.guardian.shared.flow.AmbientMode
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.text.ForbiddenWords
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InCarExecutorTest {
    private val ports = FakePorts()
    private val ex = InCarExecutor(ports)

    @Test fun speakGoesToVoice() {
        assertEquals(Outcome.SPOKEN, ex.execute(Payload.Speak(Phrase.VOICE_S1_PULL_OVER.ko), FlowState.S1, false))
        assertEquals(listOf(Phrase.VOICE_S1_PULL_OVER.ko), ports.spoken)
    }

    @Test fun drivingScreenHasNoButtonEvenIfPayloadHasOne() {
        ex.execute(Payload.Show(Phrase.SCREEN_AP.ko, Phrase.BUTTON_OK.ko), FlowState.AP, touchOk = false)
        val snap = FlowSnapshot(state = FlowState.AP)
        assertNull("주행 중 터치 0", InCarExecutor.screen(snap, ex.lastShow, touchOk = false).button)
        assertEquals(Phrase.BUTTON_OK.ko, InCarExecutor.screen(snap, ex.lastShow, touchOk = true).button)
    }

    @Test fun onlyStoppedScreensGetTheButton() {
        for (s in FlowState.entries) {
            val v = InCarExecutor.screen(FlowSnapshot(state = s), null, touchOk = true)
            val expected = s in setOf(FlowState.AP, FlowState.S2, FlowState.S4)
            assertEquals("$s", expected, v.button == Phrase.BUTTON_OK.ko)
            assertEquals("$s 버튼은 하나뿐", if (expected) Phrase.BUTTON_OK.ko else null, v.button)
        }
        // S1 SHOW 에 버튼이 실려 와도 정지 화면이 아니면 없다
        ex.execute(Payload.Show(Phrase.SCREEN_S1.ko, Phrase.BUTTON_OK.ko), FlowState.S1, true)
        assertNull(InCarExecutor.screen(FlowSnapshot(state = FlowState.S1), ex.lastShow, true).button)
    }

    @Test fun screenFallsBackToStateLineWhenStateChanged() {
        ex.execute(Payload.Show(Phrase.SCREEN_S1.ko, null), FlowState.S1, false)
        assertEquals(Phrase.SCREEN_S3.ko, InCarExecutor.screen(FlowSnapshot(state = FlowState.S3), ex.lastShow, false).line)
        assertNull("감시 중에는 경보 줄 없음", InCarExecutor.screen(FlowSnapshot(state = FlowState.S0), ex.lastShow, false).line)
    }

    @Test fun noticesClearWhenConditionResolves() {
        ex.execute(Payload.Show(Phrase.SCREEN_WATCH_NO_SIGNAL.ko, null), FlowState.S0, false)
        assertEquals(Phrase.SCREEN_WATCH_NO_SIGNAL.ko,
            InCarExecutor.screen(FlowSnapshot(state = FlowState.S0, noSignalNotified = true), ex.lastShow, false).line)
        assertNull(InCarExecutor.screen(FlowSnapshot(state = FlowState.S0, noSignalNotified = false), ex.lastShow, false).line)
    }

    @Test fun testFlowIsMarked() {
        assertTrue(InCarExecutor.screen(FlowSnapshot(state = FlowState.S1, test = true), null, false).test)
        assertFalse(InCarExecutor.screen(FlowSnapshot(state = FlowState.S1), null, false).test)
    }

    @Test fun searchOpensOnlyWhenStopped() {
        assertEquals(Outcome.SEARCH_REFUSED_DRIVING, ex.execute(Payload.OpenSearch("응급실"), FlowState.S4, touchOk = false))
        assertTrue(ports.searches.isEmpty())
        assertEquals(Outcome.SEARCH_OPENED, ex.execute(Payload.OpenSearch("응급실"), FlowState.S4, touchOk = true))
        assertEquals(listOf("응급실"), ports.searches)
    }

    @Test fun ambientIsOffByDefault() {
        assertFalse("앰비언트 기본 꺼짐 상수", AmbientPolicy.ENABLED)
        for (m in listOf(AmbientMode.INTERIOR_ALERT, AmbientMode.PARKED_RESCUE, null)) {
            assertEquals(Outcome.AMBIENT_DISABLED, ex.ambient(m))
        }
        assertEquals(Outcome.AMBIENT_DISABLED, ex.execute(Payload.Ambient(true, AmbientMode.INTERIOR_ALERT), FlowState.S1, false))
        assertTrue("조명 호출 0", ports.ambient.isEmpty())
    }

    @Test fun blueRedBlinkIsRejectedEvenWhenEnabled() {
        assertEquals(AmbientDecision.Rejected, AmbientPolicy.decide(AmbientMode.BLUE_RED_BLINK, enabled = true))
        assertEquals(AmbientDecision.Rejected, AmbientPolicy.decide(AmbientMode.BLUE_RED_BLINK, enabled = false))
        val on = InCarExecutor(ports, ambientEnabled = true)
        assertEquals(Outcome.REJECTED, on.ambient(AmbientMode.BLUE_RED_BLINK))
        assertTrue(ports.ambient.isEmpty())
    }

    @Test fun enabledAmbientIsAmberOnceOrWhiteMax() {
        val on = InCarExecutor(ports, ambientEnabled = true)
        on.ambient(AmbientMode.INTERIOR_ALERT)
        on.ambient(AmbientMode.PARKED_RESCUE)
        on.ambient(null)
        assertEquals(
            listOf(
                AmbientDecision.Apply(AmbientPolicy.AMBER, maxBrightness = false),
                AmbientDecision.Apply(AmbientPolicy.WHITE, maxBrightness = true),
                AmbientDecision.Off,
            ),
            ports.ambient,
        )
    }

    @Test fun remoteForbiddenAmbientIsNotParsed() {
        for (mode in listOf("BLUE_RED_BLINK", "blue_red_blink", "Blue_Red_Blink")) {
            val c = Command("x", CommandType.AMBIENT, mapOf("on" to true, "mode" to mode), 30_000, false, Party.PHONE, CommandTo.CAR)
            assertNull(mode, ex.parseRemote(c))
        }
        val broken = Command("y", CommandType.SPEAK, mapOf("lang" to "ko-KR"), 30_000, false, Party.PHONE, CommandTo.CAR)
        assertNull("text 없음", ex.parseRemote(broken))
    }

    @Test fun carTextsHaveNoForbiddenWords() {
        for (t in CarTexts.all) assertTrue("$t: ${ForbiddenWords.violations(t)}", ForbiddenWords.violations(t).isEmpty())
    }
}
