// 휴대폰 주인 경로의 보호자 문자 재시도 시험(코드 리뷰 2026-11-08 하5) — 보내지 못한 문자를 매 틱이 아니라 10초 간격으로 다시 보내고, 안내 · 기록은 첫 실패 때 한 번
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSmsRetryTest {
    private val store = MemoryStore()
    private val fx = FakeEffects().apply { smsDelivery = false } // 보내기 요청은 받지만 결과는 늘 보내지 못함
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private var mono = 1_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + mono }, wall0, mono, { false })

    private fun feed(v: WristVerdict) {
        engine.onDataLayerWatch(Watch(v, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = null), mono)
        engine.onLocation(Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "서울 종로구 세종대로 172"), mono)
    }

    @Test fun phoneOwnerFailedSmsIsRetriedEvery10sAndNoticedOnce() {
        feed(WristVerdict.NORMAL)
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        // 1초 틱으로 S4(사건 문자 지시)까지 간 뒤 30초 더 — 지시 ttl(30초) 동안 매 틱 localRetry 가 같은 지시를 부른다
        var firstSmsAt: Long? = null
        repeat(80) {
            mono += 1_000; feed(WristVerdict.PULSE_ABSENT); engine.tick(mono)
            if (firstSmsAt == null && fx.sms.isNotEmpty()) firstSmsAt = mono
        }
        assertEquals(FlowState.S4, engine.status(mono).flowState)
        assertTrue("사건 문자를 보내려 했다", firstSmsAt != null)
        // localRetry 는 ttl 정각까지 부른다 — 0 · 10 · 20 · 30초 네 번(고치기 전에는 매 틱 31번)
        assertEquals("ttl(30초) 동안 10초 간격", 4, fx.sms.size)
        assertEquals("「보내지 못함」 안내는 한 번", 1, fx.notices.count { it == Notice.SMS_FAILED })
        assertEquals("문자 기록도 한 줄", listOf("EVENT" to false), fx.smsLog)
    }
}
