// 보내는 시각 고정 시험 — sync 사이에 벽시계-단조 차이가 1ms · 500ms 움직여도 responseAt · lastPulseAt 이 그대로이고, 새 응답 · 새 박동일 때만 바뀐다
package com.example.guardian.watch

import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.HrStatus
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.protocol.WatchSyncResponse
import com.example.guardian.watch.core.WatchEngine
import com.example.guardian.watch.net.HttpResponse
import com.example.guardian.watch.net.HttpTransport
import com.example.guardian.watch.net.RelayApi
import com.example.guardian.watch.net.SyncRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WallTimeStabilityTest {
    private val clocks = FakeClocks()

    /** 보낸 요청의 watch 객체를 모으는 가짜 중계(늘 200, 지시 없음). */
    private inner class Relay : HttpTransport {
        val watches = mutableListOf<Map<String, Any?>>()
        override fun post(path: String, body: String): HttpResponse {
            @Suppress("UNCHECKED_CAST")
            watches += Json.parseObject(body)["watch"] as Map<String, Any?>
            return HttpResponse(200, Json.encode(WatchSyncResponse(clocks.wall(), emptyList()).toJson()))
        }
    }

    private fun beat(e: WatchEngine) {
        e.hr(HrSample(clocks.monoMs, 72, HrStatus.OK, listOf(833)))
        e.tick(clocks.monoMs)
    }

    @Test fun responseAtStaysWhenClockSkewMoves() {
        val e = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false)
        val relay = Relay()
        val runner = SyncRunner(RelayApi(relay), e, clocks)
        e.respond(clocks.monoMs, clocks.wall())
        val sent = clocks.wall()

        runner.runOnce("tok", true)
        for (shift in listOf(1L, 500L, -1L, -500L, 1L)) {
            clocks.monoMs += 2_000
            clocks.wallMinusMono += shift // 두 시계를 따로 읽는 차이 · NTP 보정
            runner.runOnce("tok", true)
        }
        val values = relay.watches.map { it["responseAt"] }
        assertEquals("응답 하나 = 값 하나 $values", listOf(sent), values.distinct())

        // 새 응답일 때만 바뀐다
        clocks.monoMs += 2_000
        e.respond(clocks.monoMs, clocks.wall())
        runner.runOnce("tok", true)
        assertNotEquals(sent, relay.watches.last()["responseAt"])
        assertEquals(clocks.wall(), relay.watches.last()["responseAt"])
    }

    @Test fun lastPulseAtStaysForSameBeatAndMovesForNewBeat() {
        val e = WatchEngine(WatchSource.SENSOR_SDK, lowConfidence = false)
        beat(e)
        val first = e.watch(clocks).lastPulseAt
        for (shift in listOf(1L, 500L, -500L)) {
            clocks.wallMinusMono += shift
            assertEquals("같은 박동은 같은 값", first, e.watch(clocks).lastPulseAt)
        }
        clocks.monoMs += 1_000
        beat(e)
        assertEquals("새 박동은 그때의 벽시계", clocks.wall(), e.watch(clocks).lastPulseAt)
    }
}
