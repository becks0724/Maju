// 코드 리뷰 상 2 재현 — 차가 주인으로 경보 중 링크가 끊긴 직후 S4(보호자 문자 · 119 작성 지시)가 나면, 복구 뒤 그 문자가 결국 휴대폰에서 한 번 나가는지 본다
package com.example.guardian.integration

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.text.SmsText
import com.example.guardian.shared.protocol.CommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 리뷰 지적(2026-11-02 상 2) — 차는 링크가 10초 넘게 끊기면 보낼 함을 비우고, 상태 기계는 사건당 문자 · 119 작성을 한 번만 내므로
 * 끊기기 직전 S4 에서 낸 휴대폰 몫 지시는 차에서 다시 나오지 않는다. 그 손실을 휴대폰 넘겨받기가 메우는지가 쟁점이다.
 *
 * 두 경우 모두 — 정차 중 실제 사건(AP) · 경보 2 → 마지막 차 sync 성공 직후 끊김 → 약 5초 뒤 차 S4 → 12초 뒤 복구.
 *
 * 결과(2026-11-02) — 손실은 재현되지 않았다. 차 쪽 지시는 실제로 사라지지만, 휴대폰은 중계에 닿든 못 닿든 차 상태가 10초 낡으면 주인이 되어
 * 마지막으로 받은 차 흐름(경보 중 · 문자 안 보냄)을 이어 받고, 그 35초 타이머가 이미 지나 있어 넘겨받는 즉시 S4 → 문자를 자기 손으로 보낸다.
 * 중계는 차 흐름(peer)과 지시 큐를 같은 응답에 함께 주므로 「문자 보냄 표시가 든 흐름만 받고 지시는 못 받는」 조합도 없다.
 * 이 시험은 그 메우는 길을 고정한다 — 차 쪽 보낼 함 비우기 규칙은 그대로 둔다.
 */
class LinkLossTest : Stage() {

    private fun eventSms(t: Trio) = t.phone.log.sms.filter { it.body.startsWith(SmsText.HEADER_GUARDIAN) }

    /** 정차 · 실제 맥박 상실로 AP 경보 2 까지 가서, 「S4 약 5초 전」에 해당하는 마지막 차 sync 시각(가상 ms)까지 돌린다. */
    private fun toJustBeforeS4(t: Trio): Long {
        armCar(t)
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
        world.runUntil(60_000, "차 AP 경보 2") { t.car.state() == FlowState.AP && t.car.hub.snapshot.stage == 2 }
        val alertElapsed = t.car.hub.snapshot.alertStartMs - CAR_MONO_BASE
        val expiry = alertElapsed + 35_000
        val cutAt = (expiry - 5_000) / 2_000 * 2_000 // 차 sync 주기(2초 · 위상 0)에 맞춘 마지막 성공 시각
        world.runUntil(20_000, "끊기 직전 차 sync") { world.elapsed >= cutAt }
        assertEquals("아직 경보 중(S4 전)", FlowState.AP, t.car.state())
        return expiry
    }

    /** 끊긴 동안 차 혼자 S4 로 가고, 지시는 보낼 함에 쌓였다가 10초가 지나며 비워진다(리뷰가 확인한 사실). */
    private fun assertCarLostItsCommands(t: Trio, expiry: Long) {
        world.runUntil(expiry - world.elapsed + 2_000, "차 S4(끊긴 채)") { t.car.state() == FlowState.S4 }
        assertTrue("차는 끊긴 직후(주인 10초 안)라 휴대폰 몫 지시를 쌓는다", t.car.hub.pendingCommands.isNotEmpty())
        assertTrue("차 상태 기계는 사건 문자를 낸 것으로 기록", t.car.hub.snapshot.incidentSmsSent && t.car.hub.snapshot.composeOpened)
    }

    /** (ㄱ) 차 · 휴대폰 둘 다 끊김 → 12초 뒤 둘 다 복구. */
    @Test
    fun bothLinksDrop12s_guardianSmsStillSentOnce() {
        val t = setup()
        val expiry = toJustBeforeS4(t)
        val cutAt = world.elapsed
        val eventId = t.car.hub.snapshot.facts.eventId!!
        t.car.linkUp = false
        t.phone.linkUp = false
        assertCarLostItsCommands(t, expiry)
        world.runUntil(cutAt + 12_000 - world.elapsed, "12초") { world.elapsed >= cutAt + 12_000 }
        // A-2 — 10초 넘게 끊기면 휴대폰 몫 지시는 비우고, 실제 보호자 문자만 확인될 때까지 남긴다(휴대폰 사건 id 중복 막기가 한 통으로 지킨다)
        assertEquals("문자 지시만 남는다", setOf(CommandType.SEND_GUARDIAN_SMS), t.car.hub.pendingCommands.map { it.type }.toSet())
        // 메우는 길 — 휴대폰은 중계에 못 닿아도 차 상태가 10초 낡으면 주인이 되어, 마지막으로 받은 차 흐름(경보 중)을 이어 받는다.
        // 그 흐름의 35초 타이머는 이미 지나 있어 넘겨받는 즉시 S4 → 문자를 자기 손으로 보낸다(복구 전).
        assertEquals("복구 전에 휴대폰이 스스로 보냈다", 1, eventSms(t).size)
        assertEquals(FlowState.S4, t.phone.status().flowState)
        t.car.linkUp = true
        t.phone.linkUp = true
        world.runUntil(40_000, "보호자 사건 문자") { eventSms(t).isNotEmpty() }
        world.advance(30_000)
        assertEquals("문자는 한 통", 1, eventSms(t).size)
        assertEquals("같은 사건 id", setOf("EVENT:$eventId"), t.phone.store.smsKeys)
        assertEquals("P12(정차 뒤 무응답 화면) 한 번", 1, t.phone.log.noResponse.size)
        assertTrue("119 작성 화면은 사람이 누르기 전엔 없음(G4)", t.phone.log.composeOpened.isEmpty())
    }

    /** (ㄴ) 차만 끊김, 휴대폰은 살아 있음 → 12초 뒤 차 복구. */
    @Test
    fun carLinkDrops12s_guardianSmsStillSentOnce() {
        val t = setup()
        val expiry = toJustBeforeS4(t)
        val cutAt = world.elapsed
        val eventId = t.car.hub.snapshot.facts.eventId!!
        t.car.linkUp = false
        assertCarLostItsCommands(t, expiry)
        world.runUntil(cutAt + 12_000 - world.elapsed, "12초") { world.elapsed >= cutAt + 12_000 }
        // A-2 — 10초 넘게 끊기면 휴대폰 몫 지시는 비우고, 실제 보호자 문자만 확인될 때까지 남긴다(휴대폰 사건 id 중복 막기가 한 통으로 지킨다)
        assertEquals("문자 지시만 남는다", setOf(CommandType.SEND_GUARDIAN_SMS), t.car.hub.pendingCommands.map { it.type }.toSet())
        assertEquals("복구 전에 휴대폰이 넘겨받아 스스로 보냈다", 1, eventSms(t).size)
        t.car.linkUp = true
        world.runUntil(40_000, "보호자 사건 문자") { eventSms(t).isNotEmpty() }
        world.advance(30_000)
        assertEquals("문자는 한 통", 1, eventSms(t).size)
        assertEquals("같은 사건 id", setOf("EVENT:$eventId"), t.phone.store.smsKeys)
        assertEquals("P12 한 번", 1, t.phone.log.noResponse.size)
    }

    private companion object {
        /** [CarDevice] 기본 단조 시계 기준(mono = 기준 + 가상 경과). */
        const val CAR_MONO_BASE = 5_000_000L
    }
}
