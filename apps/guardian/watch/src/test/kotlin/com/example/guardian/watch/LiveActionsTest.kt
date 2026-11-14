// 감시를 끈 뒤 도착한 sync 결과 시험 — 끄는 순간 기다리던 응답에 실린 경보 시작은 버리는지 본다(코드 리뷰 11-09 워치 하)
package com.example.guardian.watch

import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.AlertStopReason
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.watch.core.WatchAction
import com.example.guardian.watch.core.WatchEngine
import com.example.guardian.watch.service.liveActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveActionsTest {
    private val level2 = Payload.AlertStart(FlowState.S1, 2, vibrate = true, sound = true, maxVolume = true, durationMs = 20_000)
    private val actions = listOf(WatchAction.StartAlert("1", level2, false), WatchAction.StopAlert(AlertStopReason.CLEARED))
    private val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)

    @Test fun sameEngineKeepsActions() {
        assertEquals(actions, liveActions(current = e, from = e, actions = actions))
    }

    @Test fun alertStartAfterEndIsDropped() {
        // end() 가 engine 을 null 로 바꾼 뒤 기다리던 응답이 도착 — 진동 · 알람을 시작하지 않는다
        assertTrue(liveActions(current = null, from = e, actions = actions).isEmpty())
    }

    @Test fun resultFromOldEngineIsDroppedAfterRestart() {
        // 끄고 바로 다시 켠 경우 — 앞 엔진의 응답이 새 감시의 경보를 시작 · 멈추지 않는다
        val restarted = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        assertTrue(liveActions(current = restarted, from = e, actions = actions).isEmpty())
    }
}
