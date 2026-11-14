// 워치 「멈추기」 순서 시험 — 시나리오를 끝낸 뒤 바로 중계 sync 와 Data Layer 보내기를 한 번씩 하고, 둘 다 test=false 값을 읽는다(합동 점검 C-1)
package com.example.guardian.watch

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.watch.core.WatchEngine
import com.example.guardian.watch.service.stopTestSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopTestStepsTest {
    @Test fun stopSendsFreshTestFalseRightAway() {
        val clocks = FakeClocks()
        val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        e.startScenario(Scenario.PULSE_LOST, 120_000, clocks.monoMs)
        repeat(5) { clocks.monoMs += 1_000; e.tick(clocks.monoMs) }
        assertTrue(e.watch(clocks).test)

        val calls = mutableListOf<String>()
        stopTestSteps(
            e, clocks.monoMs,
            sync = { calls += "sync test=${e.watch(clocks).test}" },
            publish = { calls += "dl test=${e.watch(clocks).test}" },
        )
        assertNull(e.scenario)
        assertEquals(listOf("sync test=false", "dl test=false"), calls)
    }

    /** 시나리오가 없을 때 「멈추기」도 보내기는 한다 — 휴대폰이 늦게 본 test=true 를 바로 덮게. */
    @Test fun stopWithoutScenarioStillSends() {
        val clocks = FakeClocks()
        val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        val calls = mutableListOf<String>()
        stopTestSteps(e, clocks.monoMs, sync = { calls += "sync" }, publish = { calls += "dl" })
        assertEquals(listOf("sync", "dl"), calls)
    }
}
