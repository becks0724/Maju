// 철회 pair/end 재시도 시험 — 끝낼 토큰 저장 · 실패 때 남김 · 성공 / 401 / 24시간 때 지움 · 새 짝 토큰과 섞이지 않음(보안 감사 하2)
package com.example.guardian.watch

import com.example.guardian.watch.net.HttpResponse
import com.example.guardian.watch.net.HttpTransport
import com.example.guardian.watch.net.RelayApi
import com.example.guardian.watch.service.PairEndRetry
import com.example.guardian.watch.service.PairStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

class PairEndRetryTest {
    private val prefs = MemoryPrefs()
    private val store = PairStore(prefs)
    private var wall = 1_793_000_000_000L

    /** 중계 흉내 — 매 호출에 [replies] 앞에서 하나씩 꺼내 답한다(없으면 200). 보낸 경로 · 본문을 남긴다. */
    private val replies = ArrayDeque<() -> HttpResponse>()
    private val sent = mutableListOf<Pair<String, String>>()
    private val api = RelayApi(HttpTransport { path, body -> sent += path to body; (replies.removeFirstOrNull() ?: { HttpResponse(200, "{}") })() })
    private val retry = PairEndRetry(store, api::end, { wall })

    private fun sentTokens() = sent.map { (path, body) ->
        assertEquals("/v1/pair/end", path)
        Regex(""""token":"([^"]+)"""").find(body)!!.groupValues[1]
    }

    @Test fun withdrawMovesTokenToEndPendingAndClearsPair() {
        store.savePair("tok-a", "pair-1")
        assertEquals("tok-a", store.moveTokenToEndPending(wall))
        assertNull("기기 짝은 바로 지운다", store.token)
        assertNull(store.pairId)
        assertEquals(listOf(PairStore.EndPending("tok-a", wall)), store.endPending)
        assertEquals("짝이 없으면 아무것도 안 한다", null, store.moveTokenToEndPending(wall))
        assertEquals(1, store.endPending.size)
    }

    @Test fun successRemovesPendingToken() {
        store.savePair("tok-a", "pair-1"); store.moveTokenToEndPending(wall)
        assertFalse("남은 것 없음", retry.runOnce())
        assertEquals(listOf("tok-a"), sentTokens())
        assertEquals(emptyList<PairStore.EndPending>(), store.endPending)
    }

    @Test fun unauthorizedRemovesPendingToken() {
        store.savePair("tok-a", "pair-1"); store.moveTokenToEndPending(wall)
        replies += { HttpResponse(401, "{\"error\":\"TOKEN_INVALID\"}") }
        assertFalse(retry.runOnce())
        assertEquals(emptyList<PairStore.EndPending>(), store.endPending)
    }

    /** 네트워크 · 5xx · 시간 초과는 남겨 두고 다음 기회(앱 실행 · 감시 시작 · 네트워크 복구)에 다시 부른다. */
    @Test fun failuresKeepTokenUntilALaterSuccess() {
        store.savePair("tok-a", "pair-1"); store.moveTokenToEndPending(wall)
        replies += { throw IOException("네트워크 없음") }
        replies += { HttpResponse(503, "") }
        replies += { throw SocketTimeoutException() }
        repeat(3) {
            wall += 60_000
            assertTrue("남김 #$it", retry.runOnce())
            assertEquals(listOf("tok-a"), store.endPending.map { p -> p.token })
        }
        wall += 60_000
        assertFalse(retry.runOnce())
        assertEquals(listOf("tok-a", "tok-a", "tok-a", "tok-a"), sentTokens())
        assertEquals(emptyList<PairStore.EndPending>(), store.endPending)
    }

    /** 저장한 지 24시간이면 중계에서도 짝이 사라졌다 — 부르지 않고 지운다. 그 직전까지는 부른다. */
    @Test fun after24HoursIsRemovedWithoutCalling() {
        store.savePair("tok-a", "pair-1"); store.moveTokenToEndPending(wall)
        replies += { HttpResponse(503, "") }
        wall += PairEndRetry.PAIR_TTL_MS - 1
        assertTrue(retry.runOnce())
        assertEquals(1, sent.size)
        wall += 1
        assertFalse(retry.runOnce())
        assertEquals("24시간째에는 부르지 않는다", 1, sent.size)
        assertEquals(emptyList<PairStore.EndPending>(), store.endPending)
    }

    /** 철회 뒤 다시 동의 · 짝을 맺어도 재시도는 끝낼 토큰만 부르고, 성공 · 401 · 실패 어느 쪽이든 새 짝 토큰은 그대로다. */
    @Test fun newPairTokenIsNeverSentOrCleared() {
        store.savePair("tok-a", "pair-1"); store.moveTokenToEndPending(wall)
        store.savePair("tok-b", "pair-2")
        replies += { HttpResponse(503, "") }
        replies += { HttpResponse(401, "{}") }
        assertTrue(retry.runOnce())
        assertFalse(retry.runOnce())
        assertEquals(listOf("tok-a", "tok-a"), sentTokens())
        assertEquals("tok-b", store.token)
        assertEquals("pair-2", store.pairId)
        assertEquals(emptyList<PairStore.EndPending>(), store.endPending)
    }

    /** 두 번 철회했고 둘 다 못 끝냈으면 둘 다 남고, 하나씩 따로 지운다. */
    @Test fun twoPendingTokensAreHandledSeparately() {
        store.savePair("tok-a", "pair-1"); store.moveTokenToEndPending(wall)
        wall += 1_000
        store.savePair("tok-b", "pair-2"); store.moveTokenToEndPending(wall)
        replies += { HttpResponse(200, "{}") } // a 성공
        replies += { HttpResponse(500, "") } // b 실패
        assertTrue(retry.runOnce())
        assertEquals(listOf(PairStore.EndPending("tok-b", wall)), store.endPending)
        assertFalse(retry.runOnce())
        assertEquals(listOf("tok-a", "tok-b", "tok-b"), sentTokens())
    }

    /** 끝낼 토큰은 토큰 · 시각만 남긴다(G2) — 저장 파일에 다른 값이 없다. */
    @Test fun pendingStoresOnlyTokenAndTime() {
        store.savePair("tok-a", "pair-1"); store.moveTokenToEndPending(wall)
        assertEquals(mapOf("endPendingTokens" to "$wall tok-a"), prefs.map)
    }

    /** 철회 화면이 끝낼 토큰으로 옮기는 함수를 쓰고(옛 「한 번만 부르고 잊기」 없음), 앱 실행 · 감시 시작이 재시도를 부르는지. */
    @Test fun wiredIntoWithdrawAppStartAndMonitorStart() {
        fun src(p: String) = File(p).also { assertTrue("시험은 :watch 모듈 폴더에서 돈다 — ${it.absolutePath}", it.isFile) }.readText()
        val main = src("src/main/kotlin/com/example/guardian/watch/ui/MainActivity.kt")
        val unpair = Regex("""private fun unpair\(\) \{[\s\S]*?\n {4}\}""").find(main)?.value
        assertTrue("unpair 없음", unpair != null)
        assertTrue(unpair, unpair!!.contains("moveTokenToEndPending("))
        assertTrue(unpair, unpair.contains("PairEnd.kick(this)"))
        assertFalse(unpair, unpair.contains("api.end("))
        val onCreate = Regex("""override fun onCreate\([\s\S]*?\n {4}\}""").find(main)!!.value
        assertTrue(onCreate, onCreate.contains("PairEnd.kick(this)"))
        val begin = Regex("""private fun begin\(\) \{[\s\S]*?\n {4}\}""").find(src("src/main/kotlin/com/example/guardian/watch/service/MonitorService.kt"))!!.value
        assertTrue(begin, begin.contains("PairEnd.kick(this)"))
        val manifest = src("src/main/AndroidManifest.xml")
        assertTrue("네트워크 복구 작업 선언", Regex("""\.service\.PairEndJob"[\s\S]*?BIND_JOB_SERVICE""").containsMatchIn(manifest))
    }
}
