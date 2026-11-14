// 중계 불통 때 주기 시험(코드 리뷰 중 6) — 연속 네트워크 오류 중 주기당 sync 횟수 · 연결 제한(읽기 제한은 그대로), 성공 뒤 원래대로
package com.example.guardian.phone.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPacingTest {
    private val pacing = SyncPacing()
    private val usedTimeouts = ArrayList<Int>()
    private var ticks = 0

    /** 한 주기를 돌린다 — [results] 를 차례로 sync 결과로 돌려준다. 보낼 것은 늘 있다고 둔다. */
    private fun cycle(vararg results: RelayResult<*>?): Int {
        val q = ArrayDeque(results.toList())
        return pacing.cycle({ t -> usedTimeouts += t; q.removeFirst() }, { ticks++ }, { true })
    }

    private val down = RelayResult.NetworkError("SocketTimeoutException")
    private val ok = RelayResult.Ok(Unit)

    @Test fun normalCycleSyncsTwiceWithNormalTimeout() {
        assertEquals(2, cycle(ok, ok))
        assertEquals(listOf(RelayClient.TIMEOUT_MS, RelayClient.TIMEOUT_MS), usedTimeouts)
        assertEquals(2, ticks)
    }

    @Test fun networkErrorCutsToOneSyncAndShortTimeout() {
        // 첫 실패 — 이번 주기의 두 번째 sync 를 하지 않는다(첫 sync 는 아직 원래 제한)
        assertEquals(1, cycle(down))
        assertEquals(listOf(RelayClient.TIMEOUT_MS), usedTimeouts)
        assertTrue(pacing.failing)
        // 연속 실패 중 — 주기당 한 번, 짧은 제한
        repeat(3) { assertEquals(1, cycle(down)) }
        assertEquals(List(3) { RelayClient.FAILING_CONNECT_TIMEOUT_MS }, usedTimeouts.drop(1))
        assertTrue(RelayClient.FAILING_CONNECT_TIMEOUT_MS < 2_000) // 2초 주기 안
    }

    @Test fun recoversAfterSuccess() {
        cycle(down)
        // 다시 닿은 주기 — 짧은 제한으로 성공하면 같은 주기의 두 번째 sync 부터 원래대로
        usedTimeouts.clear()
        assertEquals(2, cycle(ok, ok))
        assertEquals(listOf(RelayClient.FAILING_CONNECT_TIMEOUT_MS, RelayClient.TIMEOUT_MS), usedTimeouts)
        assertFalse(pacing.failing)
    }

    @Test fun httpErrorIsNotNetworkError() {
        // 중계가 답한 오류(4xx · 5xx)는 빨리 오므로 줄이지 않는다
        assertEquals(2, cycle(RelayResult.Failed(500, null), ok))
        assertFalse(pacing.failing)
    }

    @Test fun notSentKeepsState() {
        cycle(down)
        // 짝 없음 · 중계 주소 없음(null) — 상태를 바꾸지 않는다
        assertEquals(1, cycle(null))
        assertTrue(pacing.failing)
    }

    @Test fun onlyConnectTimeoutShrinks() {
        // 읽기 제한은 줄이지 않는다 — 늘 느린 망에서 실패 상태를 못 벗어나는 일을 막는다(PM 2026-11-02)
        assertEquals(5_000, RelayClient.TIMEOUT_MS)
        assertEquals(1_500, RelayClient.FAILING_CONNECT_TIMEOUT_MS)
        cycle(down)
        assertEquals(RelayClient.FAILING_CONNECT_TIMEOUT_MS, pacing.connectTimeoutMs)
        cycle(ok, ok)
        assertEquals(RelayClient.TIMEOUT_MS, pacing.connectTimeoutMs)
    }

    @Test fun nothingToSendSyncsOnce() {
        assertEquals(1, pacing.cycle({ ok }, { ticks++ }, { false }))
        assertEquals(1, ticks)
    }
}
