// debug 전용 GPS 측정 CSV 시험 — 열 순서 · 빈 값 · 누계 · 요약, 위도 · 경도가 들어가지 않는지 본다
package com.example.guardian.watch.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsProbeCsvTest {
    private val full = ProbeFix(
        recvElapsedMs = 123_456L, provider = "gps", fixAgeMs = 12L,
        speedMps = 13.456f, speedAccMps = 0.5f, horizAccM = 3.94f,
        hasAltitude = true, satUsed = 14, satVisible = 31,
    )
    private val empty = ProbeFix(
        recvElapsedMs = 200L, provider = "fused", fixAgeMs = 0L,
        speedMps = null, speedAccMps = null, horizAccM = null,
        hasAltitude = false, satUsed = null, satVisible = null,
    )

    @Test fun rowFollowsHeaderOrder() {
        assertEquals("123456,gps,12,1,13.46,1,0.50,1,3.9,14,31,1", GpsProbeCsv.row(full))
        assertEquals(GpsProbeCsv.HEADER.split(",").size, GpsProbeCsv.row(full).split(",").size)
    }

    @Test fun missingValuesAreEmptyWithZeroFlags() {
        assertEquals("200,fused,0,0,,0,,0,,,,0", GpsProbeCsv.row(empty))
        assertEquals(GpsProbeCsv.HEADER.split(",").size, GpsProbeCsv.row(empty).split(",").size)
    }

    @Test fun noLatitudeOrLongitude() {
        val header = GpsProbeCsv.HEADER.lowercase()
        listOf("lat", "lon", "lng").forEach { assertFalse("머리글에 $it", header.contains(it)) }
        // 위치 한 건 자체에 좌표 필드가 없어야 CSV · 로그 어디에도 새어 나갈 수 없다
        val fields = ProbeFix::class.java.declaredFields.map { it.name.lowercase() }
        listOf("lat", "lon", "lng").forEach { k -> assertTrue("ProbeFix 필드에 $k", fields.none { it.contains(k) }) }
    }

    @Test fun statsTrackTtffAndCount() {
        val s0 = ProviderStats("gps", subscribedAtMs = 100_000L)
        assertNull(s0.ttffMs)
        val s2 = s0.with(full).with(full.copy(recvElapsedMs = 124_456L, speedMps = 1f))
        assertEquals(23_456L, s2.ttffMs)
        assertEquals(2, s2.count)
        assertEquals(1f, s2.last?.speedMps)
    }

    @Test fun summaryLine() {
        val gps = ProviderStats("gps", subscribedAtMs = 100_000L).with(full)
        val fused = ProviderStats("fused", subscribedAtMs = 100_000L)
        assertEquals(
            "SUMMARY elapsed_s=61 battery=80->78 | " +
                "gps: ttff_ms=23456 n=1 last_speed=13.46 last_speed_acc=0.50 last_h_acc=3.9 ; " +
                "fused: ttff_ms=- n=0 last_speed=- last_speed_acc=- last_h_acc=-",
            GpsProbeCsv.summary(61_500L, 80, 78, listOf(gps, fused)),
        )
        assertEquals("SUMMARY elapsed_s=0 battery=-->- | no_provider", GpsProbeCsv.summary(0L, null, null, emptyList()))
    }
}
