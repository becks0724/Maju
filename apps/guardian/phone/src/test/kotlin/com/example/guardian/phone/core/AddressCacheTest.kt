// 주소 캐시 시험(11-02 남은 하) — 역지오코딩은 부른 스레드에서 돌지 않고, 주소가 아직 없거나 100m 넘게 움직였으면 주소 없이(좌표만) 돌려준다
package com.example.guardian.phone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executor

class AddressCacheTest {
    /** 일을 쥐고 있다가 시험이 돌리는 작업 스레드 대역. */
    private val held = ArrayList<Runnable>()
    private val worker = Executor { held += it }
    private val lookups = ArrayList<Pair<Double, Double>>()
    private var answer: String? = "서울 종로구 세종대로 172"

    /** 위도 차이 1e-3 ≈ 111m 로 본다(시험용 거리). */
    private val cache = AddressCache(worker, { a, b, c, d -> (Math.hypot(a - c, b - d) * 111_000).toFloat() }) { lat, lon ->
        lookups += lat to lon
        answer
    }

    private fun runHeld() { val r = held.toList(); held.clear(); r.forEach(Runnable::run) }

    @Test fun lookupDoesNotRunOnCallerThread() {
        assertNull("처음에는 주소 없이 바로 돌아온다(엔진을 막지 않는다)", cache.address(37.0, 127.0))
        assertEquals("부른 스레드에서는 묻지 않는다", 0, lookups.size)
        assertEquals(1, held.size)
        runHeld()
        assertEquals("서울 종로구 세종대로 172", cache.address(37.0001, 127.0))
        assertEquals(1, lookups.size)
    }

    @Test fun oneLookupAtATimeAndMovedFarIsCoordinatesOnly() {
        cache.address(37.0, 127.0)
        cache.address(37.0, 127.0)
        assertEquals("묻는 중이면 또 묻지 않는다", 1, held.size)
        runHeld()
        answer = "서울 중구 세종대로 110"
        assertNull("100m 넘게 움직였으면 옛 주소를 쓰지 않는다 — 좌표만", cache.address(37.002, 127.0))
        runHeld()
        assertEquals("서울 중구 세종대로 110", cache.address(37.002, 127.0))
    }

    @Test fun failedLookupIsCoordinatesOnlyAndNotRetriedNearby() {
        answer = null
        cache.address(37.0, 127.0)
        runHeld()
        assertNull(cache.address(37.0001, 127.0))
        assertEquals("100m 안에서는 다시 묻지 않는다(지금과 같다)", 0, held.size)
    }

    @Test fun lookupExceptionIsCoordinatesOnly() {
        val c = AddressCache(worker, { _, _, _, _ -> 0f }) { _, _ -> error("네트워크 없음") }
        c.address(37.0, 127.0)
        runHeld()
        assertNull(c.address(37.0, 127.0))
    }
}
