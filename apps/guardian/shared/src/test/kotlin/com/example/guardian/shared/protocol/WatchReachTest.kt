// 워치 지시 가능 판단 경계 — 직접 경로 30,000 / 30,001ms · 직접 값 없음 · 휴대폰 경유만 · 응답 뒤 지난 시간 더하기
package com.example.guardian.shared.protocol

import com.example.guardian.shared.bio.WristVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchReachTest {
    private val recv = 500_000L

    @Test fun directAge30000IsReachable_30001IsNot() {
        assertTrue(WatchReach.canCommandWatch(30_000, recv, recv))
        assertFalse(WatchReach.canCommandWatch(30_001, recv, recv))
        assertTrue(WatchReach.canCommandWatch(0, recv, recv))
    }

    @Test fun timeSinceResponseIsAdded() {
        assertTrue(WatchReach.canCommandWatch(29_000, recv, recv + 1_000))
        assertFalse(WatchReach.canCommandWatch(29_000, recv, recv + 1_001))
        assertEquals(30_001L, WatchReach.directAgeNow(29_000, recv, recv + 1_001))
        // 단조 시계가 응답 시각보다 앞이면(호출 순서 실수) 0 으로 본다
        assertEquals(29_000L, WatchReach.directAgeNow(29_000, recv, recv - 5_000))
    }

    @Test fun noDirectValueIsNotReachable() {
        assertFalse(WatchReach.canCommandWatch(null, recv, recv))
        assertNull(WatchReach.directAgeNow(null, recv, recv))
    }

    @Test fun phoneViaOnlyIsNotReachableEvenIfFresh() {
        // 중계 응답 — 고른 값은 방금 받은 휴대폰 경유(ageMs 0)지만 직접 값은 없다
        val text = """{"serverTime":9,"peer":null,"watch":{"verdict":"NORMAL","hr":null,"lastPulseAt":null,"worn":true,"source":"SENSOR_SDK","test":false,"responseAt":null,"via":"PHONE","receivedAt":9,"ageMs":0},"commands":[],"watchDirectAgeMs":null}"""
        val r = PhoneSyncResponse.fromJson(JsonObj(Json.parseObject(text)))
        assertEquals(Via.PHONE, r.watch?.via)
        assertEquals(WristVerdict.NORMAL, r.watch?.verdict)
        assertFalse(WatchReach.canCommandWatch(r.watchDirectAgeMs, recv, recv))
    }

    @Test fun directAliveWhileChosenIsPhoneVia() {
        val text = """{"serverTime":9,"peer":null,"watch":{"verdict":"NORMAL","hr":null,"lastPulseAt":null,"worn":true,"source":"SENSOR_SDK","test":false,"responseAt":null,"via":"PHONE","receivedAt":9,"ageMs":0},"commands":[],"watchDirectAgeMs":1500}"""
        val r = PhoneSyncResponse.fromJson(JsonObj(Json.parseObject(text)))
        assertTrue(WatchReach.canCommandWatch(r.watchDirectAgeMs, recv, recv))
    }
}
