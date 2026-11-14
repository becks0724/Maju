// 보호자 문자 다시 보내기 규칙 고정 시험(위임 결정 3 · G5, app-qa 2차 조건) — 간격 60초 · 상한 10분 · 취소 · 꺼짐 · 동의 빠짐 · 확인 못 함 · 사고 문자 · 주행 중 수동 금지
package com.example.guardian.phone.core

import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResendRulesTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private val ex = CommandExecutor(store, fx, FakeBodies())
    private var n = 0
    private val wall0 = 1_793_000_000_000L

    private fun ctx(t: Long, stopped: Boolean = true) =
        ExecContext(stopped = stopped, testSession = false, nowLocal = t, nowWall = wall0 + t, incidentOngoing = { true })

    private fun cmd(kind: GuardianSmsWireKind, id: String = "e-1") =
        Payload.SendGuardianSms(kind, id, WireFacts(SnapshotClock.LOCAL), test = false).let {
            Command("id-${n++}", it.type, it.toJson(), 30_000, false, Party.CAR, CommandTo.PHONE)
        }

    /** 0초에 보내지 못한 사건 문자 하나. */
    private fun failAt0(kind: GuardianSmsWireKind = GuardianSmsWireKind.EVENT) {
        fx.smsDelivery = false
        ex.execute(cmd(kind), ctx(0))
        assertEquals(1, fx.sms.size)
    }

    private fun tickTo(from: Long, to: Long) { var t = from; while (t <= to) { ex.onTick(ctx(t)); t += 1_000 } }

    @Test fun gapIs60Seconds() {
        assertEquals(60_000L, CommandExecutor.RESEND_GAP_MS)
        failAt0()
        tickTo(1_000, 59_000)
        assertEquals("59초에는 다시 보내지 않는다", 1, fx.sms.size)
        tickTo(60_000, 61_000)
        assertEquals("60초가 지나면 다시 보낸다", 2, fx.sms.size)
    }

    @Test fun capIs10Minutes_boundary() {
        assertEquals(600_000L, CommandExecutor.RESEND_MAX_MS)
        failAt0()
        tickTo(1_000, 600_000)
        assertEquals("10분까지 60초마다 — 첫 시도 + 10번", 11, fx.sms.size)
        tickTo(601_000, 900_000)
        assertEquals("10분이 넘으면 다시 보내지 않는다", 11, fx.sms.size)
    }

    /** 같은 사건 취소 지시를 받은 뒤 결과가 실패로 오면 다시 보내지 않는다. */
    @Test fun cancelSeenStopsResend() {
        fx.smsDelivery = null
        ex.execute(cmd(GuardianSmsWireKind.EVENT), ctx(0))
        ex.execute(cmd(GuardianSmsWireKind.CANCEL), ctx(1_000)) // 결과 기다리는 중 — 취소는 기다리지만 사건 id 를 기억한다
        fx.heldResults.single()(false)
        tickTo(2_000, 200_000)
        assertEquals(1, fx.sms.size)
    }

    @Test fun powerOffStopsResend() {
        failAt0()
        store.registration = fullRegistration(powerOn = false)
        tickTo(1_000, 200_000)
        assertEquals(1, fx.sms.size)
    }

    @Test fun consentWithdrawnStopsResend() {
        failAt0()
        store.registration = fullRegistration(Consents(emergencyInfo = true, health = true, location = false))
        tickTo(1_000, 200_000)
        assertEquals(1, fx.sms.size)
    }

    /** 「확인 못 함」(결과 30초 없음)은 갔을 수 있으므로 다시 보내지 않는다 — 같은 사건 두 통을 막는다. */
    @Test fun unconfirmedIsNotResent() {
        fx.smsDelivery = null
        ex.execute(cmd(GuardianSmsWireKind.EVENT), ctx(0))
        tickTo(1_000, 300_000)
        assertEquals(1, fx.sms.size)
    }

    @Test fun accidentIsResentToo() {
        failAt0(GuardianSmsWireKind.ACCIDENT)
        tickTo(1_000, 61_000)
        assertEquals(2, fx.sms.size)
    }

    /** G5 — 주행 중(정지 아님)에는 수동 「다시 보내기」를 받지 않는다. */
    @Test fun manualResendRefusedWhileDriving() {
        failAt0()
        fx.smsDelivery = true
        assertFalse(ex.resendNow("e-1", ctx(5_000, stopped = false)))
        assertEquals(1, fx.sms.size)
        assertEquals(true, ex.resendNow("e-1", ctx(6_000, stopped = true)))
        assertEquals(2, fx.sms.size)
    }
}
