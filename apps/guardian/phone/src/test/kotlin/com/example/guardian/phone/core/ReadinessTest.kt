// 켜기 조건 시험 — 알림 · 전체 화면 알림 · 문자 · 위치는 켜기를 막고, 방해 금지 접근 · 차와 연결은 표시만 한다
package com.example.guardian.phone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
    private val all = PermState(location = true, sms = true, notify = true, fullScreen = true, dnd = true, backgroundLocation = true)

    @Test fun allSetCanTurnOn() {
        assertTrue(Readiness.canTurnOn(fullRegistration(), all))
        assertTrue(Readiness.missing(fullRegistration(), all, paired = true, relayConfigured = true).isEmpty())
    }

    @Test fun notifyFullScreenSmsLocationBlock() {
        for (p in listOf(all.copy(notify = false), all.copy(fullScreen = false), all.copy(sms = false), all.copy(location = false))) {
            assertFalse(p.toString(), Readiness.canTurnOn(fullRegistration(), p))
        }
        assertEquals(listOf(Missing.NOTIFY), Readiness.missing(fullRegistration(), all.copy(notify = false), true, true))
        assertEquals(listOf(Missing.FULL_SCREEN), Readiness.missing(fullRegistration(), all.copy(fullScreen = false), true, true))
    }

    @Test fun dndAndPairOnlyShown() {
        val m = Readiness.missing(fullRegistration(), all.copy(dnd = false), paired = false, relayConfigured = true)
        assertEquals(listOf(Missing.DND, Missing.PAIR), m)
        assertTrue(m.none { it.blocking })
        assertTrue(Readiness.canTurnOn(fullRegistration(), all.copy(dnd = false)))
    }

    /** 위치 「항상 허용」은 표시만 — 켜기 · 다시 켜기를 막지 않고, 앞쪽 위치가 없으면 위치만 보인다. */
    @Test fun backgroundLocationOnlyShown() {
        val m = Readiness.missing(fullRegistration(), all.copy(backgroundLocation = false), paired = true, relayConfigured = true)
        assertEquals(listOf(Missing.BACKGROUND_LOCATION), m)
        assertFalse(Missing.BACKGROUND_LOCATION.blocking)
        assertTrue(Readiness.canTurnOn(fullRegistration(), all.copy(backgroundLocation = false)))
        assertEquals(listOf(Missing.LOCATION), Readiness.missing(fullRegistration(), all.copy(location = false, backgroundLocation = false), true, true))
    }

    /** 홈 — 꺼짐 · 돌지 않음이면 전부, 감시가 돌고 있으면 켜기를 막는 것(중6)과 「항상 허용」만(방해 금지 · 차와 연결은 그대로 숨김). */
    @Test fun homeShowsBlockingAndBackgroundLocationWhileRunning() {
        val all = listOf(Missing.LOCATION, Missing.DND, Missing.BACKGROUND_LOCATION, Missing.PAIR)
        assertEquals(all, Readiness.homeShown(all, powerOn = false, running = false))
        assertEquals(all, Readiness.homeShown(all, powerOn = true, running = false))
        assertEquals(all, Readiness.homeShown(all, powerOn = false, running = true))
        assertEquals(listOf(Missing.LOCATION, Missing.BACKGROUND_LOCATION), Readiness.homeShown(all, powerOn = true, running = true))
        assertEquals(emptyList<Missing>(), Readiness.homeShown(listOf(Missing.DND, Missing.PAIR), powerOn = true, running = true))
        // 감시 중 문자 · 알림 · 전체 화면 알림을 끄면 홈에 보인다(중6)
        val off = listOf(Missing.SMS, Missing.NOTIFY, Missing.FULL_SCREEN, Missing.DND)
        assertEquals(listOf(Missing.SMS, Missing.NOTIFY, Missing.FULL_SCREEN), Readiness.homeShown(off, powerOn = true, running = true))
    }

    @Test fun registrationGapsBlock() {
        val m = Readiness.missing(Registration(), all, paired = true, relayConfigured = false)
        assertEquals(listOf(Missing.PROFILE, Missing.GUARDIAN, Missing.CONSENT), m)
        assertFalse(Readiness.canTurnOn(Registration(), all))
    }
}
