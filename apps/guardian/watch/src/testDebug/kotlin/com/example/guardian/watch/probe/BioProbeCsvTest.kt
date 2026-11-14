// debug 전용 생체 기록 CSV 시험 — 파일별 머리글 · 행 열 수, IBI 목록 잇기, 값 없음, 쉼표 · 따옴표 · 줄바꿈 칸, 속도 행에 좌표 자리가 없는지
package com.example.guardian.watch.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BioProbeCsvTest {
    private fun cols(line: String) = line.split(",").size

    @Test fun everyStreamButMetaHasHeader() {
        for (s in BioStream.entries) {
            if (s == BioStream.META) assertNull(BioProbeCsv.header(s)) else assertTrue("$s 머리글", !BioProbeCsv.header(s).isNullOrEmpty())
        }
        assertEquals("sdk_ppg.csv", BioStream.SDK_PPG.file)
        assertEquals("meta.txt", BioStream.META.file)
    }

    @Test fun sdkPpgRowKeepsRawValuesAndAllThreeStatuses() {
        val row = BioProbeCsv.sdkPpg(1000L, 1_762_000_000_123L, 123456, -7, 0, 0, -3, 9)
        assertEquals("1000,1762000000123,123456,-7,0,0,-3,9", row)
        assertEquals(cols(BioProbeCsv.SDK_PPG_HEADER), cols(row))
        assertEquals("1000,1762000000123,,,,,,", BioProbeCsv.sdkPpg(1000L, 1_762_000_000_123L, null, null, null, null, null, null))
    }

    @Test fun sdkAccRowIsRawIntegers() {
        val row = BioProbeCsv.sdkAcc(5L, 6L, 4095, -4096, 1)
        assertEquals("5,6,4095,-4096,1", row)
        assertEquals(cols(BioProbeCsv.SDK_ACC_HEADER), cols(row))
        assertEquals("5,6,,,", BioProbeCsv.sdkAcc(5L, 6L, null, null, null))
    }

    @Test fun sdkHrRowJoinsIbiListsWithBarAndKeepsAllStatuses() {
        val row = BioProbeCsv.sdkHr(10L, 20L, 72, 1, listOf(830, 845, 0), listOf(0, 0, -1))
        assertEquals("10,20,72,1,830|845|0,0|0|-1", row)
        assertEquals(cols(BioProbeCsv.SDK_HR_HEADER), cols(row))
        // IBI 가 없거나 null 이어도 열 수는 같다
        assertEquals("10,20,0,-3,,", BioProbeCsv.sdkHr(10L, 20L, 0, -3, emptyList(), null))
        assertEquals(cols(BioProbeCsv.SDK_HR_HEADER), cols(BioProbeCsv.sdkHr(10L, 20L, null, null, null, null)))
    }

    @Test fun androidSensorRowKeepsFloatPrecision() {
        val row = BioProbeCsv.andSensor(100L, 99L, 0.123456f, -9.80665f, 1.0E-5f, 3)
        assertEquals("100,99,0.123456,-9.80665,1.0E-5,3", row)
        assertEquals(cols(BioProbeCsv.AND_SENSOR_HEADER), cols(row))
        assertEquals(BioProbeCsv.header(BioStream.AND_ACC), BioProbeCsv.header(BioStream.AND_GYRO))
    }

    @Test fun healthServicesRow() {
        assertEquals("7,8,71.0,ACCURACY_MEDIUM", BioProbeCsv.hsHr(7L, 8L, 71.0, "ACCURACY_MEDIUM"))
        assertEquals("7,8,0.0,", BioProbeCsv.hsHr(7L, 8L, 0.0, null))
        assertEquals(cols(BioProbeCsv.HS_HR_HEADER), cols(BioProbeCsv.hsHr(7L, 8L, 71.0, null)))
    }

    @Test fun speedRowHasNoCoordinateColumns() {
        val full = SpeedFix(recvMs = 123_456L, fixAgeMs = 12L, speedMps = 13.456f, speedAccMps = 0.5f, horizAccM = 3.94f)
        assertEquals("123456,12,13.456,0.5,3.94", BioProbeCsv.speed(full))
        assertEquals("200,0,,,", BioProbeCsv.speed(SpeedFix(200L, 0L, null, null, null)))
        assertEquals(cols(BioProbeCsv.SPEED_HEADER), cols(BioProbeCsv.speed(full)))
        val header = BioProbeCsv.SPEED_HEADER.lowercase()
        listOf("lat", "lon", "lng", "alt").forEach { assertFalse("속도 머리글에 $it", header.contains(it)) }
        // 위치 한 건을 옮긴 형식 자체에 좌표 필드가 없어야 CSV · 로그 어디에도 새어 나갈 수 없다(G3)
        // Compose 컴파일러가 넣는 정적 `$stable` 은 뺀다
        val fields = SpeedFix::class.java.declaredFields
            .filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name.lowercase() }
        listOf("lat", "lon", "lng", "alt").forEach { k -> assertTrue("SpeedFix 필드에 $k", fields.none { it.contains(k) }) }
        assertEquals(listOf("recvms", "fixagems", "speedmps", "speedaccmps", "horizaccm"), fields)
    }

    @Test fun stateRowFollowsCountedOrder() {
        val counts = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L)
        val row = BioProbeCsv.state(1000L, 1_762_000_000_000L, true, 83, counts, monitoring = true, ioErrors = 4L)
        assertEquals("1000,1762000000000,1,83,1,2,3,4,5,6,7,1,4", row)
        assertEquals(cols(BioProbeCsv.STATE_HEADER), cols(row))
        // 열 순서 — 앞 4열 · COUNTED 순서의 n_ 열 · monitoring · io_errors(맨 끝)
        val header = BioProbeCsv.STATE_HEADER.split(",")
        assertEquals(listOf("recv_ms", "wall_ms", "screen_on", "battery_pct"), header.take(4))
        assertEquals(BioStream.COUNTED.map { "n_" + it.file.removeSuffix(".csv") }, header.drop(4).dropLast(2))
        assertEquals(listOf("monitoring", "io_errors"), header.takeLast(2))
        assertEquals(
            "1000,1762000000000,0,,0,0,0,0,0,0,0,0,0",
            BioProbeCsv.state(1000L, 1_762_000_000_000L, false, null, BioStream.COUNTED.map { 0L }, monitoring = false, ioErrors = 0L),
        )
    }

    @Test fun stateRowRejectsWrongCountSize() {
        val ex = runCatching { BioProbeCsv.state(1L, 2L, true, 50, listOf(1L), monitoring = false, ioErrors = 0L) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test fun eventDetailWithCommaOrQuoteDoesNotBreakColumns() {
        val row = BioProbeCsv.event(5L, "sdk_connected", "trackers=[PPG_CONTINUOUS, HEART_RATE_CONTINUOUS] say \"hi\"\nnext")
        assertEquals("5,sdk_connected,\"trackers=[PPG_CONTINUOUS, HEART_RATE_CONTINUOUS] say \"\"hi\"\" next\"", row)
        // 따옴표 밖의 쉼표만 세면 세 열이다
        assertEquals(3, splitCsv(row).size)
        assertEquals("5,start,session=20261107-080000 hs=false", BioProbeCsv.event(5L, "start", "session=20261107-080000 hs=false"))
    }

    @Test fun cellQuotesOnlyWhenNeeded() {
        assertEquals("plain", BioProbeCsv.cell("plain"))
        assertEquals("\"a,b\"", BioProbeCsv.cell("a,b"))
        assertEquals("\"say \"\"x\"\"\"", BioProbeCsv.cell("say \"x\""))
        assertEquals("one two", BioProbeCsv.cell("one\r\ntwo"))
    }

    /** RFC 4180 식으로 따옴표 안의 쉼표를 건너뛰며 나눈다(시험용). */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
