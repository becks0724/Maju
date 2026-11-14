// 경보 시작 순서 시험 — 최대 음량 · 재생이 예외를 내도 경보 상태 싣기 · 경보 화면 띄우기가 이어지는지 본다(코드 리뷰 11-08 중4)
package com.example.guardian.watch

import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.watch.service.Alerter
import com.example.guardian.watch.service.startAlertSteps
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertStartStepsTest {
    private val level2 = Payload.AlertStart(FlowState.AP, level = 2, vibrate = true, sound = true, maxVolume = true, durationMs = 30_000)

    /** 가짜 오디오 — 진동 · 음량 · 재생 중 [failing] 에 든 것은 SecurityException 을 던진다(방해 금지 등). */
    private fun run(failing: Set<String>, accelFails: Boolean = false): List<String> {
        val calls = mutableListOf<String>()
        fun out(name: String) { if (name in failing) throw SecurityException(name) else calls += name }
        startAlertSteps(
            outputs = { Alerter.startOutputs(level2, vibrate = { out("진동") }, raiseVolume = { out("음량") }, play = { out("소리") }) },
            accel = { if (accelFails) throw IllegalStateException("accel") else calls += "손목" },
            publish = { calls += "상태" },
            show = { calls += "화면" },
        )
        return calls
    }

    @Test fun volumeFailureStillPlaysAndShowsAlert() {
        assertEquals(listOf("진동", "소리", "손목", "상태", "화면"), run(setOf("음량")))
    }

    @Test fun playbackFailureStillShowsAlert() {
        assertEquals(listOf("진동", "음량", "손목", "상태", "화면"), run(setOf("소리")))
    }

    @Test fun allOutputsFailingStillShowsAlert() {
        assertEquals(listOf("상태", "화면"), run(setOf("진동", "음량", "소리"), accelFails = true))
    }

    @Test fun outputsThrowingAtTopStillShowsAlert() {
        val calls = mutableListOf<String>()
        startAlertSteps(
            outputs = { throw SecurityException("dnd") },
            accel = { calls += "손목" },
            publish = { calls += "상태" },
            show = { calls += "화면" },
        )
        assertEquals(listOf("손목", "상태", "화면"), calls)
    }

    @Test fun noFailureKeepsOrder() {
        assertEquals(listOf("진동", "음량", "소리", "손목", "상태", "화면"), run(emptySet()))
    }
}
