// 대본 밖 모듈 경계 시험 — 통합 시험이 찾은 맞물림 결함을 PM 결정(2026-10-30)대로 고친 기대 동작으로 고정한다
package com.example.guardian.integration

import com.example.guardian.phone.core.Notice
import com.example.guardian.phone.core.TestMode
import com.example.guardian.phone.core.TestStart
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamTest : Stage() {

    /**
     * 워치가 중계에 직접 붙지 않고 Data Layer 로만 휴대폰에 붙은 경우. 워치 지시는 직접 경로로만 닿으므로(계약 §1 · §4.3 지시 가능 규칙 · §4.7, PM 결정)
     * 휴대폰은 워치에 TEST_SCENARIO 를 보내지 않고 테스트를 휴대폰 단독(LOCAL)으로 돌리며 「휴대폰만으로 테스트」 안내를 띄운다.
     */
    @Test
    fun dataLayerOnlyWatch_phoneOnlyTestWithNotice() {
        val t = setup(watchDirect = false, dataLayer = true)
        armCar(t)
        assertFalse("홈 「워치 직접 연결 없음」", t.phone.status().watchDirect)
        assertEquals(TestStart.LOCAL, t.phone.engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = t.phone.clock.mono()))
        assertEquals(TestMode.LOCAL, t.phone.status().test)
        assertTrue(Notice.TEST_PHONE_ONLY_NO_DIRECT_WATCH in t.phone.log.notices)
        world.advance(10_000)
        assertNull("워치 시나리오는 시작되지 않는다", t.watch.engine.scenario)
        assertEquals("워치로 간 지시가 없다", 0, world.relay.queued("WATCH"))
        assertTrue("휴대폰 단독 테스트가 돈다", t.phone.status().testFlowState != null)
    }

    /**
     * 휴대폰만 중계가 끊기고 차는 붙어 있는 동안 사건이 생긴 경우. 휴대폰은 차가 10초 넘게 안 보이니 주인이 되고(계약 §6) 차는 자기 링크가 살아 있어
     * 계속 주인이다 — 두 상태 기계가 따로 사건을 열어 사건 id 가 1초쯤 어긋난다. 휴대폰이 보내기 직전에 :shared GuardianSmsDedup 으로 같은 사건을 묶어
     * 다시 붙은 뒤 받은 차의 문자 지시는 보내지 않고 ack 한다(계약 §6.2).
     */
    @Test
    fun phoneLinkLostOnly_singleGuardianSms() {
        val t = setup(dataLayer = true)
        armCar(t)
        t.phone.linkUp = false
        t.car.reading = CarDevice.driving(); t.phone.gpsKmh = 60.0
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
        world.runUntil(90_000, "차 S3") { t.car.state() == FlowState.S3 }
        world.advance(2_000)
        t.phone.linkUp = true
        world.advance(20_000)
        assertEquals("한 사건에 보호자 문자 한 통 — ${t.phone.store.smsKeys}", 1, t.phone.log.sms.size)
        assertEquals("차의 문자 지시는 ack 되어 큐에 남지 않는다", 0, world.relay.queued("PHONE"))
    }
}
