// 보호자 문자 다시 보내기 시험(위임 결정 3) — 보내지 못한 사건 문자를 사건이 진행 중인 동안 60초마다(상한 10분) 다시 보내고, 응답 · 꺼짐이면 멈춘다
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentSmsResendTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { false })

    private fun watch(v: WristVerdict) = Watch(v, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = null)
    private fun loc() = Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "주소")
    private fun advance(ms: Long, v: WristVerdict = WristVerdict.PULSE_ABSENT) {
        val end = mono + ms
        while (mono < end) { mono += 1_000; engine.onDataLayerWatch(watch(v), mono); engine.onLocation(loc(), mono); engine.tick(mono) }
    }

    /** 정차 중 무응답 S4 까지 — 사건 문자는 보내지 못함(결과 실패). */
    private fun toS4Failing() {
        fx.smsDelivery = false
        advance(3_000, WristVerdict.NORMAL)
        advance(45_000)
        assertEquals(FlowState.S4, engine.status(mono).flowState)
        assertTrue(fx.sms.isNotEmpty())
    }

    @Test fun failedEventIsResentEvery60sWhileOngoing_untilSent() {
        toS4Failing()
        advance(40_000) // 지시 ttl(30초)이 지나도
        val afterTtl = fx.sms.size
        advance(65_000)
        assertTrue("ttl 뒤에도 60초 간격으로 다시 보낸다", fx.sms.size > afterTtl)
        assertTrue(engine.status(mono).canResendSms)
        fx.smsDelivery = true
        advance(65_000)
        val sentAt = fx.sms.size
        advance(130_000)
        assertEquals("보낸 뒤에는 멈춘다", sentAt, fx.sms.size)
        assertFalse(engine.status(mono).canResendSms)
    }

    @Test fun responseStopsResend() {
        toS4Failing()
        engine.phoneResponse(mono)
        assertEquals(FlowState.S2, engine.status(mono).flowState)
        val n = fx.sms.size
        advance(200_000, WristVerdict.NORMAL)
        assertEquals("응답 뒤에는 다시 보내지 않는다(취소 문자도 사건 문자를 못 보냈으니 없음)", n, fx.sms.size)
    }

    @Test fun resendCapsAt10Minutes() {
        toS4Failing()
        advance(CommandExecutor.RESEND_MAX_MS + 60_000)
        val n = fx.sms.size
        advance(180_000)
        assertEquals(n, fx.sms.size)
    }

    /** 「보호자 문자 다시 보내기」 — 정지일 때 간격을 기다리지 않고 지금 보낸다. */
    @Test fun manualResendWhenStopped() {
        toS4Failing()
        advance(35_000)
        fx.smsDelivery = true
        val n = fx.sms.size
        assertTrue(engine.resendGuardianSms(mono))
        assertEquals(n + 1, fx.sms.size)
        assertFalse(engine.status(mono).canResendSms)
    }
}
