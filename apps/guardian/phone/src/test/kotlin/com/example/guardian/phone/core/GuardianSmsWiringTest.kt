// 보호자 문자 묶기 연결 시험 — 실행기가 :shared GuardianSmsDedup(decideAndRecord · afterSend)으로 같은 사건 문자를 막고, 기록을 저장소에 남기는지(계약 §6.2)
package com.example.guardian.phone.core

import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.GuardianSmsWireKind.ACCIDENT
import com.example.guardian.shared.protocol.GuardianSmsWireKind.CANCEL
import com.example.guardian.shared.protocol.GuardianSmsWireKind.EVENT
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.WireFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianSmsWiringTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private val bodies = FakeBodies()
    private var ex = CommandExecutor(store, fx, bodies)
    private var n = 0

    /** 휴대폰 시계 — 단조 1,000,000 일 때 벽시계 wall0, 중계 시계 = 단조 + offset. */
    private val wall0 = 1_793_000_000_000L
    private val mono = 1_000_000L
    private val offset = 5_000_000_000L
    private val relay0 = mono + offset
    private val ctx = ExecContext(stopped = true, testSession = false, offsetMs = offset, nowLocal = mono, nowWall = wall0)

    private fun sms(kind: GuardianSmsWireKind, eventId: String, from: Party, firstAlertAt: Long?, responseAt: Long? = null, test: Boolean = false): Command {
        val facts = WireFacts(SnapshotClock.SERVER, firstAlertAt = firstAlertAt, responseAt = responseAt, accidentAt = if (kind == ACCIDENT) firstAlertAt else null)
        val p = Payload.SendGuardianSms(kind, eventId, facts, test)
        return Command("id-${n++}", p.type, p.toJson(), 30_000, test, from, CommandTo.PHONE)
    }

    /** 휴대폰만 끊겨 두 주인 — 휴대폰 자기 EVENT 를 보낸 뒤 다시 붙어 받은 차 EVENT(사건 id 1초 어긋남)는 보내지 않고 ack, 차 CANCEL 은 한 번. */
    @Test fun carEventAfterPhoneOwnEventIsSameIncident() {
        assertTrue(ex.execute(sms(EVENT, "event-L1", Party.PHONE, relay0 - 30_000), ctx))
        assertTrue(ex.execute(sms(EVENT, "event-2", Party.CAR, relay0 - 29_000), ctx))
        assertEquals(1, fx.sms.size)
        assertTrue(ex.execute(sms(CANCEL, "event-2", Party.CAR, relay0 - 29_000, responseAt = relay0 - 5_000), ctx))
        assertEquals(2, fx.sms.size)
        assertEquals(listOf("EVENT" to true, "CANCEL" to true), fx.smsLog)
        assertEquals(listOf(true, true), store.smsRecords.map { it.sent })
    }

    /** EVENT 를 보내지 않은 사건의 CANCEL 은 보내지 않고 ack — 응답 시각과 함께 「보내지 않음」으로 남긴다. */
    @Test fun cancelWithoutEventIsNotSentButRecordedUnsent() {
        assertTrue(ex.execute(sms(CANCEL, "event-9", Party.CAR, relay0 - 30_000, responseAt = relay0 - 1_000), ctx))
        assertTrue(fx.sms.isEmpty())
        val r = store.smsRecords.single()
        assertFalse(r.sent)
        assertEquals(CANCEL, r.kind)
        assertEquals(wall0 - 1_000, r.responseWallMs) // 휴대폰 벽시계로 옮긴 응답 시각
    }

    /** 앱을 다시 켜도(새 실행기 · 같은 저장소) 보낸 기록이 남아 취소 문자가 빠지지 않는다(be-qa 재현 B). */
    @Test fun recordsSurviveRestart() {
        ex.execute(sms(EVENT, "event-3", Party.CAR, relay0 - 30_000), ctx)
        ex = CommandExecutor(store, fx, bodies)
        ex.execute(sms(CANCEL, "event-3", Party.CAR, relay0 - 30_000, responseAt = relay0), ctx)
        assertEquals(2, fx.sms.size)
        // 같은 사건 · 같은 kind 는 재시작 뒤에도 다시 보내지 않는다
        ex = CommandExecutor(store, fx, bodies)
        ex.execute(sms(EVENT, "event-3", Party.CAR, relay0 - 30_000), ctx)
        assertEquals(2, fx.sms.size)
    }

    /** 기록은 24시간 — 지난 기록은 decideAndRecord 가 정리한다. */
    @Test fun oldRecordsArePruned() {
        ex.execute(sms(EVENT, "event-4", Party.CAR, relay0 - 30_000), ctx)
        val later = ctx.copy(nowWall = wall0 + 24 * 60 * 60 * 1000L + 1)
        ex.execute(sms(EVENT, "event-5", Party.CAR, relay0), later)
        assertEquals(listOf("event-5"), store.smsRecords.map { it.eventId })
    }

    /** 보내기 실패는 「보냈다」로 남기지 않는다 — 다시 시도해 한 번 보낸다. */
    @Test fun failedSendIsNotRecordedAsSent() {
        fx.smsResult = false
        val c = sms(EVENT, "event-6", Party.CAR, relay0)
        assertFalse(ex.execute(c, ctx))
        assertTrue(store.smsRecords.isEmpty())
        fx.smsResult = true
        assertTrue(ex.execute(c, ctx.copy(nowLocal = ctx.nowLocal + CommandExecutor.SMS_RETRY_GAP_MS))) // 10초 간격 뒤(하5)
        assertEquals(1, store.smsRecords.count { it.sent })
    }

    /** 테스트 문자는 확인 결과로 기록 — 거절한 테스트 EVENT 의 테스트 CANCEL 은 묻지도 보내지도 않는다. */
    @Test fun declinedTestEventMeansNoTestCancel() {
        ex.execute(sms(EVENT, "event-7", Party.CAR, relay0, test = true), ctx)
        assertEquals(1, fx.asked.size)
        ex.confirmTestSms(fx.asked[0].key, send = false)
        assertTrue(store.smsRecords.isEmpty())
        ex.execute(sms(CANCEL, "event-7", Party.CAR, relay0, responseAt = relay0 + 1_000, test = true), ctx)
        assertEquals(1, fx.asked.size)
        assertTrue(fx.sms.isEmpty())
        assertFalse(store.smsRecords.single().sent)
    }

    /** 테스트 기록과 실제 기록은 묶지 않는다(be-qa 재현 C) — 같은 사건 id 라도 실제 문자는 나간다. */
    @Test fun testAndRealAreNeverMerged() {
        ex.execute(sms(EVENT, "event-8", Party.PHONE, relay0, test = true), ctx)
        ex.confirmTestSms(fx.asked.single().key, send = true)
        assertTrue(store.smsRecords.single().let { it.sent && it.test })
        ex.execute(sms(EVENT, "event-8b", Party.CAR, relay0 + 1_000), ctx)
        assertEquals(2, fx.sms.size)
    }

    /** ACCIDENT 라도 test=true 로 오면 사람에게 먼저 묻는다(테스트 문자 규칙, app-qa 참고). */
    @Test fun testMarkedAccidentAsksFirst() {
        ex.execute(sms(ACCIDENT, "accident-t", Party.CAR, relay0, test = true), ctx.copy(testSession = true))
        assertTrue(fx.sms.isEmpty())
        assertEquals(1, fx.asked.size)
        ex.confirmTestSms(fx.asked[0].key, send = true)
        assertEquals(1, fx.sms.size)
        assertTrue(store.smsRecords.single().let { it.sent && it.test && it.kind == ACCIDENT })
    }
}
