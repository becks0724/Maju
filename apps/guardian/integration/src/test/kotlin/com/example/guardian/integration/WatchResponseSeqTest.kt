// 워치 응답 번호 통합 시험(합동 점검 B-5) — 경보 밖 누름은 다음 경보를 끄지 않음, 경보 중 누름은 값이 한 번 유실돼도 응답, 워치 벽시계가 뒤로 가도 응답
package com.example.guardian.integration

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 무대 워치는 새 워치처럼 `responseSeq`(경보 중 누름만) · `alerting` 을 싣는다([WatchDevice.newWatchFields]).
 * 주인 쪽 `WatchResponseTracker` 배선(차 · 휴대폰 3차)이 들어와 모두 켜져 있다.
 */
class WatchResponseSeqTest : Stage() {
    private fun parkedAlert(t: Trio) {
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
        world.runUntil(60_000, "AP") { t.car.state() == FlowState.AP }
        world.runUntil(6_000, "워치 울림") { t.watch.alerting }
    }

    @Test fun pressOutsideAlertDoesNotAnswerNextAlert() {
        val t = setup()
        armCar(t)
        world.advance(4_000)
        t.watch.pressOk() // 경보 밖 누름 — 번호는 오르지 않는다
        assertEquals(null, t.watch.responseSeq)
        world.advance(4_000)
        parkedAlert(t)
        world.advance(8_000)
        assertEquals("경보 밖 누름으로 경보가 꺼지지 않음", FlowState.AP, t.car.state())
    }

    @Test fun pressDuringAlertIsAnswerEvenIfOneValueIsLost() {
        val t = setup()
        armCar(t)
        parkedAlert(t)
        t.watch.linkUp = false // 누른 직후 sync 하나가 닿지 않음
        t.watch.pressOk()
        world.advance(1_500)
        t.watch.linkUp = true // 다음 주기에 같은 값이 다시 실린다
        world.runUntil(8_000, "응답 뒤 S2") { t.car.state() == FlowState.S2 }
        assertEquals(1L, t.watch.responseSeq)
    }

    /** 앱2-하6 — 한 번 누른 뒤 워치 벽시계가 뒤로 가면 다음 누름은 `responseAt` 이 작아져 옛 규칙으로는 응답이 아니다. 번호로는 응답이다. */
    @Test fun pressAfterWatchWallClockWentBackIsAnswer() {
        val t = setup()
        armCar(t)
        world.advance(4_000)
        t.watch.pressOk() // 경보 밖 누름 — responseAt 기준만 생긴다(번호는 그대로)
        world.advance(4_000)
        t.watch.clock.wallShiftMs = -5 * 60_000L
        parkedAlert(t)
        t.watch.pressOk()
        world.runUntil(8_000, "응답 뒤 S2") { t.car.state() == FlowState.S2 }
        assertTrue(t.watch.responseSeq == 1L)
    }

}
