// debug 전용 생체 기록 — 필드 읽기 보조(키 없음 → null · 사건 1회)와 1회 사건 도구 시험(AAR 없이 돈다)
package com.example.guardian.watch.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdkFieldReaderTest {
    private val events = mutableListOf<Pair<String, String>>()
    private val once = OnceEvents { k, d -> events += k to d }
    private val reader = SdkFieldReader(once)

    @Test fun missingKeyGivesNullAndOneEventPerKey() {
        // DataPoint.getValue 는 키가 없으면 NPE 를 던진다 — 같은 모양으로 흉내 낸다
        val map = mapOf("GREEN" to 1234)
        fun get(key: String): Int = map[key]!!
        assertNull(reader.read("PpgSet.PPG_IR") { get("IR") })
        assertNull(reader.read("PpgSet.PPG_IR") { get("IR") })
        assertNull(reader.read("PpgSet.PPG_IR") { get("IR") })
        assertEquals(1234, reader.read("PpgSet.PPG_GREEN") { get("GREEN") })
        assertNull(reader.read("PpgSet.PPG_RED") { get("RED") })
        assertEquals(
            listOf(
                SdkFieldReader.KIND_KEY_MISSING to "PpgSet.PPG_IR NullPointerException",
                SdkFieldReader.KIND_KEY_MISSING to "PpgSet.PPG_RED NullPointerException",
            ),
            events,
        )
    }

    @Test fun otherRuntimeExceptionsAlsoBecomeNull() {
        assertNull(reader.read("HeartRateSet.IBI_LIST") { throw IllegalStateException("boom") })
        assertEquals(listOf(SdkFieldReader.KIND_KEY_MISSING to "HeartRateSet.IBI_LIST IllegalStateException"), events)
    }

    @Test fun onceEventsEmitEachKindDetailPairOnlyOnce() {
        once.once("sdk_point_error", "PPG_CONTINUOUS NullPointerException")
        once.once("sdk_point_error", "PPG_CONTINUOUS NullPointerException")
        once.once("sdk_point_error", "HEART_RATE_CONTINUOUS NullPointerException")
        once.once("sdk_key_missing", "PPG_CONTINUOUS NullPointerException") // 종류가 다르면 따로
        assertEquals(3, events.size)
        assertEquals("HEART_RATE_CONTINUOUS NullPointerException", events[1].second)
        assertEquals("sdk_key_missing", events[2].first)
    }
}
