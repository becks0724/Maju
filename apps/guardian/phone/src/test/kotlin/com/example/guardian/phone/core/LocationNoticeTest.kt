// 위치 제공 통보 시험(보안 7 · D-8) — 위치가 든 보호자 문자가 「보냄」 · 「확인 못 함」으로 정해질 때마다 본문 없는 통보를 한 번 낸다
package com.example.guardian.phone.core

import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.text.SmsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationNoticeTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private val bodies = FakeBodies()
    private val ex = CommandExecutor(store, fx, bodies)
    private val ctx = ExecContext(stopped = true, testSession = false)
    private var n = 0

    private fun cmd(kind: GuardianSmsWireKind, eventId: String = "e-1") =
        Payload.SendGuardianSms(kind, eventId, WireFacts(SnapshotClock.LOCAL), test = false).let {
            Command("id-${n++}", it.type, it.toJson(), 30_000, false, Party.CAR, CommandTo.PHONE)
        }

    @Test fun sentEvent_noticesOnce() {
        ex.execute(cmd(GuardianSmsWireKind.EVENT), ctx)
        assertEquals(listOf<Boolean?>(true), fx.locationNotices)
    }

    @Test fun accidentAndObserve_notice() {
        ex.execute(cmd(GuardianSmsWireKind.ACCIDENT, "e-2"), ctx)
        ex.execute(cmd(GuardianSmsWireKind.OBSERVE, "e-3"), ctx)
        assertEquals(2, fx.locationNotices.size)
    }

    @Test fun cancel_hasNoLocation_noNotice() {
        ex.execute(cmd(GuardianSmsWireKind.EVENT), ctx)
        ex.execute(cmd(GuardianSmsWireKind.CANCEL), ctx)
        assertEquals(1, fx.locationNotices.size)
    }

    @Test fun failed_noNotice() {
        fx.smsDelivery = false
        ex.execute(cmd(GuardianSmsWireKind.EVENT), ctx)
        assertTrue(fx.locationNotices.isEmpty())
    }

    /** app-qa 관문 2 조건 1 — 본문에 실제 위치가 없으면(동의 없음 · 위치 모름) 통보하지 않는다. */
    @Test fun bodyWithoutLocation_noNotice() {
        bodies.guardian["e-4"] = "[앱 안내] 본문\n${SmsText.LOCATION}: ${SmsText.LOCATION_NO_CONSENT} · 10:42"
        bodies.guardian["e-5"] = "[앱 안내] 본문\n${SmsText.LOCATION}: ${SmsText.LOCATION_UNKNOWN} · 10:42"
        ex.execute(cmd(GuardianSmsWireKind.EVENT, "e-4"), ctx)
        ex.execute(cmd(GuardianSmsWireKind.EVENT, "e-5"), ctx)
        assertEquals(2, fx.sms.size)
        assertTrue(fx.locationNotices.isEmpty())
    }

    /** 결과가 30초 안에 오지 않으면 「확인 못 함」 — 문자는 갔을 수 있으므로 통보한다. */
    @Test fun unconfirmed_notices() {
        fx.smsDelivery = null
        ex.execute(cmd(GuardianSmsWireKind.EVENT), ctx)
        ex.onTick(ctx.copy(nowLocal = CommandExecutor.SMS_RESULT_TIMEOUT_MS + 1))
        assertEquals(listOf<Boolean?>(null), fx.locationNotices)
    }
}
