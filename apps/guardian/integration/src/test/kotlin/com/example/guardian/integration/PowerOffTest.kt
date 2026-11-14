// 코드 리뷰 2026-11-08 중 1 재현 — 휴대폰이 주인인 실제 사건(알람 중)에 휴대폰에서 끄면, 알람이 바로 멈추고 마지막 휴대폰 flow 가 OFF 라 다시 붙은 차가 경보를 이어받지 않는지 본다
package com.example.guardian.integration

import com.example.guardian.phone.core.Withdrawal
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.PhoneSyncRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerOffTest : Stage() {

    private val alarmStates = setOf(FlowState.S1, FlowState.AP, FlowState.S3, FlowState.S4)

    /** 중계로 나간 마지막 휴대폰 sync 의 flow. */
    private fun lastPhoneFlow() = world.relay.wire.last { it.path == "/v1/phone/sync" }.request
        .let { PhoneSyncRequest.fromJson(JsonObj(Json.parseObject(it))).flow!! }

    @Test
    fun phoneOwnerAlarm_powerOff_alarmStopsAtOnceAndCarDoesNotTakeOverAlarm() {
        val t = setup()
        armCar(t)
        // 차 링크가 끊겨 휴대폰이 주인이 되고, 정차 중 실제 맥박 상실로 휴대폰 상태 기계가 경보(알람)를 낸다
        t.car.linkUp = false
        world.runUntil(16_000, "휴대폰 주인") { t.phone.engine.owner == Owner.PHONE }
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
        world.runUntil(60_000, "휴대폰 알람") { t.phone.log.alarmOn }
        assertTrue(t.phone.status().flowState in alarmStates)
        val smsBefore = t.phone.log.sms.size

        // 정차 중 홈 「끄기」 — 다음 주기 한 번(2초) 안에 알람이 멈추고, 마지막 flow 는 꺼짐 · OFF
        t.phone.store.reg = t.phone.store.reg.copy(powerOn = false)
        world.advance(2_000)
        assertFalse("끄기 뒤 sync 왕복을 기다리지 않고 알람이 멈춘다", t.phone.log.alarmOn)
        val flow = lastPhoneFlow()
        assertEquals("마지막 휴대폰 flow 는 OFF", FlowState.OFF, flow.state)
        assertFalse(flow.snapshot.powerOn)

        // 10초 안에 차가 다시 붙어도 경보를 이어받지 않는다
        t.car.linkUp = true
        repeat(80) {
            world.advance(250)
            assertFalse("차가 경보 흐름을 이어받았다(${world.elapsed / 1000}s ${t.car.state()})", t.car.state() in alarmStates)
        }
        assertEquals(FlowState.OFF, t.car.state())
        assertFalse("휴대폰 알람은 다시 켜지지 않는다", t.phone.log.alarmOn)
        assertEquals("끈 뒤 보호자 문자 없음", smsBefore, t.phone.log.sms.size)
    }

    /**
     * 1차 휴대폰 [미검증] 재현 — 차가 주인인 실제 경보 중(정차) 휴대폰에서 동의를 철회한다. 토큰을 먼저 지우면 마지막 sync 가 건너뛰어지고
     * 짝 끝내기(중계 상태 즉시 삭제)만 가서, 짝을 잃은 차는 사건 중이라 경보를 이어 갔다. 이제 잡아 둔 토큰으로 마지막 sync(동의 없음 · 꺼짐)를
     * 보내고 차가 그것을 읽은 뒤에 짝을 끝낸다 — 차 경보가 꺼지고, 휴대폰 저장의 토큰 · 동의는 철회 즉시 지워져 있다(G2).
     */
    @Test
    fun carOwnerAlarm_consentWithdrawal_carReadsOffBeforePairEnds() {
        val t = setup()
        armCar(t)
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
        world.runUntil(60_000, "차 경보") { t.car.state() in alarmStates }

        t.phone.withdraw()
        assertEquals("토큰은 철회 즉시 지운다(G2)", null, t.phone.token)
        assertFalse(t.phone.store.registration.consents.emergencyInfo)
        world.runUntil(Withdrawal.CAR_READ_WAIT_MS + 4_000, "짝 끝남") { t.car.token == null }
        assertEquals("차가 휴대폰의 동의 없음 · 꺼짐을 읽고 경보를 끝냈다(${t.car.state()})", FlowState.OFF, t.car.state())
        world.advance(20_000)
        assertFalse("짝을 잃은 뒤에도 차가 경보를 이어 가지 않는다(${t.car.state()})", t.car.state() in alarmStates)
    }
}
