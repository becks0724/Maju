// 철회 pair/end 다시 시도 시험(감사 하2) — 실패하면 토큰만 남기고, 성공 · 401 · 24시간 지남에 지우며, 새 짝 토큰과 섞이지 않는다
package com.example.guardian.phone.core

import com.example.guardian.phone.core.PendingEnd.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingEndTest {
    private class Mem : PendingEndStore { override var pendingEnds: List<PendingEnd.Entry> = emptyList() }

    private val store = Mem()
    private val t0 = 1_761_800_000_000L
    private val calls = ArrayList<String>()
    private fun answer(r: Result): (String) -> Result = { calls += it; r }

    @Test fun failedEndSavesOnlyTheToken() {
        assertEquals(Result.FAILED, PendingEnd.attempt(store, "old", t0, answer(Result.FAILED)))
        assertEquals(listOf(PendingEnd.Entry("old", t0)), store.pendingEnds)
        // 저장 형식에는 토큰과 시각만 — 이름 · 번호 · 위치 · 건강 값이 들어갈 자리가 없다
        assertEquals("old $t0", PendingEnd.encode(store.pendingEnds))
    }

    @Test fun successOrGoneOnFirstTrySavesNothing() {
        PendingEnd.attempt(store, "a", t0, answer(Result.ENDED))
        PendingEnd.attempt(store, "b", t0, answer(Result.GONE))
        assertTrue(store.pendingEnds.isEmpty())
    }

    /**
     * 코드 리뷰 2026-11-09 하 — 끝낼 토큰을 호출이 실패한 뒤에야 저장해, 호출 도중 프로세스가 죽으면 다시 부를 수 없었다.
     * 호출하기 전에 이미 저장돼 있고, 성공 · 401 이면 그 토큰만 지운다(다른 끝낼 토큰은 그대로).
     */
    @Test fun tokenIsSavedBeforeCallAndRemovedOnlyOnSuccessOr401() {
        PendingEnd.add(store, "other", t0)
        for (r in listOf(Result.ENDED, Result.GONE)) {
            var savedDuringCall = false
            PendingEnd.attempt(store, "tok", t0 + 1) { savedDuringCall = store.pendingEnds.any { it.token == "tok" }; r }
            assertTrue("호출 도중(프로세스가 죽을 수 있는 때) 이미 저장돼 있다 — $r", savedDuringCall)
            assertEquals(listOf("other"), store.pendingEnds.map { it.token })
        }
        var savedDuringCall = false
        PendingEnd.attempt(store, "tok", t0 + 2) { savedDuringCall = store.pendingEnds.any { it.token == "tok" }; Result.FAILED }
        assertTrue(savedDuringCall)
        assertEquals(listOf("other", "tok"), store.pendingEnds.map { it.token })
    }

    @Test fun retryClearsOnSuccessAndOn401KeepsOnFailure() {
        PendingEnd.add(store, "a", t0)
        PendingEnd.add(store, "b", t0)
        PendingEnd.add(store, "c", t0)
        val results = mapOf("a" to Result.ENDED, "b" to Result.GONE, "c" to Result.FAILED)
        PendingEnd.retry(store, activeToken = null, nowWallMs = t0 + 60_000) { calls += it; results.getValue(it) }
        assertEquals(listOf("a", "b", "c"), calls)
        assertEquals(listOf(PendingEnd.Entry("c", t0)), store.pendingEnds) // 저장 시각은 처음 것 그대로(24시간 기준)
        calls.clear()
        PendingEnd.retry(store, null, t0 + 120_000, answer(Result.ENDED))
        assertEquals(listOf("c"), calls)
        assertTrue(store.pendingEnds.isEmpty())
    }

    @Test fun olderThan24hIsDroppedWithoutCalling() {
        PendingEnd.add(store, "old", t0)
        PendingEnd.add(store, "young", t0 + 1_000)
        PendingEnd.retry(store, null, t0 + PendingEnd.MAX_AGE_MS, answer(Result.FAILED))
        assertEquals(listOf("young"), calls)
        assertEquals(listOf("young"), store.pendingEnds.map { it.token })
    }

    /** 끝낼 토큰이 남은 채 새로 짝을 지어도 — 다시 시도는 옛 토큰만 부르고 새 토큰은 건드리지 않는다. 같은 토큰이면 부르지 않고 지운다. */
    @Test fun newPairTokenIsNeverEnded() {
        PendingEnd.attempt(store, "old", t0, answer(Result.FAILED))
        calls.clear()
        PendingEnd.retry(store, activeToken = "new", nowWallMs = t0 + 1_000, call = answer(Result.ENDED))
        assertEquals(listOf("old"), calls)
        assertTrue(store.pendingEnds.isEmpty())

        PendingEnd.add(store, "same", t0)
        calls.clear()
        PendingEnd.retry(store, activeToken = "same", nowWallMs = t0 + 1_000, call = answer(Result.ENDED))
        assertTrue(calls.isEmpty())
        assertTrue(store.pendingEnds.isEmpty())
    }

    @Test fun repeatedTokenKeptOnceAndListIsCapped() {
        PendingEnd.add(store, "a", t0)
        PendingEnd.add(store, "a", t0 + 5)
        assertEquals(listOf(PendingEnd.Entry("a", t0 + 5)), store.pendingEnds)
        repeat(20) { PendingEnd.add(store, "t$it", t0) }
        assertEquals(PendingEnd.MAX_ENTRIES, store.pendingEnds.size)
        assertEquals("t19", store.pendingEnds.last().token)
    }

    @Test fun decodeDropsBrokenLines() {
        assertEquals(listOf(PendingEnd.Entry("tok", 5)), PendingEnd.decode("tok 5\n\nbad\nx y\n a 1"))
        assertTrue(PendingEnd.decode(null).isEmpty())
        val list = listOf(PendingEnd.Entry("a", 1), PendingEnd.Entry("b", 2))
        assertEquals(list, PendingEnd.decode(PendingEnd.encode(list)))
    }

    /** 철회 순서는 그대로 — 저장을 지운 뒤 마지막 sync, 그 다음 짝 끝내기. 짝 끝내기가 실패하면 그 토큰만 끝낼 토큰으로 남는다. */
    @Test fun withdrawalThenFailedEndLeavesOnlyTheToken() {
        var cleared = false
        Withdrawal.end("tok-1", clear = { cleared = true }, lastSync = { _, after -> after() }) { t ->
            assertTrue(cleared)
            PendingEnd.attempt(store, t, t0, answer(Result.FAILED))
        }
        assertEquals(listOf("tok-1"), store.pendingEnds.map { it.token })
    }
}
