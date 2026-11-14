// 원격 문자 상한 시험(D-6, 사용자 확정 11-14) — 차 지시 · 대조 길의 보호자 문자는 10분 3통 · 24시간 10통(사고 문자는 같은 값의 사고 칸에서 따로), 사건 id 단위, 저장 · 벽시계 되돌림에도 유지
package com.example.guardian.phone.core

import com.example.guardian.phone.ui.PhoneTexts
import com.example.guardian.phone.ui.UiRules
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.WireFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSmsLimitTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ex = CommandExecutor(store, fx, FakeBodies())
    private var n = 0
    private var wall = 1_793_000_000_000L
    private var mono = 0L
    private fun ctx() = ExecContext(stopped = true, testSession = false, nowLocal = mono, nowWall = wall)

    private fun cmd(kind: GuardianSmsWireKind, eventId: String, from: Party = Party.CAR) =
        Payload.SendGuardianSms(kind, eventId, WireFacts(SnapshotClock.LOCAL), test = false).let {
            Command("id-${n++}", it.type, it.toJson(), 30_000, false, from, CommandTo.PHONE)
        }

    private fun send(kind: GuardianSmsWireKind, eventId: String, from: Party = Party.CAR) = ex.execute(cmd(kind, eventId, from), ctx())
    private fun later(ms: Long) { wall += ms; mono += ms }

    @Test fun fourthIncidentIn10MinIsNotSent_recordedAndShown() {
        for (i in 1..4) { send(GuardianSmsWireKind.EVENT, "e-$i"); later(60_000) }
        assertEquals(3, fx.sms.size)
        assertTrue("보내지 않음 기록", store.smsRecords.any { it.eventId == "e-4" && !it.sent })
        assertTrue(Notice.SMS_SKIPPED_LIMIT in fx.notices)
        val lines = UiRules.smsHistory(store.smsRecords, wall, RemoteSmsLimit.decode(store.remoteSmsLimit).blockedKeys) { "t" }
        assertTrue(lines.toString(), lines.any { it.contains(PhoneTexts.SMS_HISTORY_LIMITED_MARK) })
    }

    /** 결정 22 — 문자 수로 센다. 같은 사건 id 의 사건 · 정차 관찰도 각각 한 통이다(sec-audit ②). */
    @Test fun messagesAreCounted_notIncidents() {
        send(GuardianSmsWireKind.EVENT, "e-1")
        send(GuardianSmsWireKind.OBSERVE, "e-1")
        send(GuardianSmsWireKind.EVENT, "e-2")
        send(GuardianSmsWireKind.EVENT, "e-3")
        assertEquals(3, fx.sms.size)
    }

    /** sec-audit ② — id 넷에 사건 · 정차 관찰 · 사고를 섞어 1분 안에 보내도 일반 3통 + 사고 3통(각 칸의 10분 값). */
    @Test fun mixedKindsPerId_capped() {
        for (i in 1..4) for (k in listOf(GuardianSmsWireKind.EVENT, GuardianSmsWireKind.OBSERVE, GuardianSmsWireKind.ACCIDENT)) { send(k, "x-$i"); later(5_000) }
        assertEquals(6, fx.sms.size)
        assertEquals(3, store.smsRecords.count { it.sent && it.kind == GuardianSmsWireKind.ACCIDENT })
    }

    /** sec-audit ③ — 사건 문자로 센 id 에 사고를 얹어도 「같은 문자」가 아니라 사고 칸(10분 3통)에 따로 세여 걸린다. */
    @Test fun accidentOnCountedId_stillAccidentBucket() {
        for (id in listOf("a", "b", "c", "d")) { send(GuardianSmsWireKind.EVENT, id); send(GuardianSmsWireKind.ACCIDENT, id) }
        assertEquals(3, store.smsRecords.count { it.sent && it.kind == GuardianSmsWireKind.ACCIDENT })
        assertEquals(3, store.smsRecords.count { it.sent && it.kind == GuardianSmsWireKind.EVENT })
        assertFalse("넷째 사고는 막힘", store.smsRecords.any { it.eventId == "d" && it.kind == GuardianSmsWireKind.ACCIDENT && it.sent })
    }

    /** 결정 22 ③ — 일반 칸이 차도(10분 3통 뒤 · 하루 10통 뒤) 진짜 사고 문자는 나간다. */
    @Test fun accidentNotBlockedByGeneralBucket() {
        for (i in 1..3) send(GuardianSmsWireKind.EVENT, "e-$i")
        send(GuardianSmsWireKind.ACCIDENT, "accident-1")
        assertEquals(4, fx.sms.size)
        var k = 0
        repeat(4) { later(600_001); repeat(3) { send(GuardianSmsWireKind.EVENT, "d-${k++}") } }
        later(600_001)
        send(GuardianSmsWireKind.EVENT, "over")
        assertFalse("하루 10통 뒤 일반 문자는 막힘", store.smsRecords.any { it.eventId == "over" && it.sent })
        send(GuardianSmsWireKind.ACCIDENT, "accident-2")
        assertTrue("사고는 나감", store.smsRecords.any { it.eventId == "accident-2" && it.sent })
    }

    /** app-qa 상 — 보내지 못한 문자는 상한 칸을 먹지 않는다. 실패 3통 뒤 새 사건 문자는 나간다. */
    @Test fun failedSendsDoNotConsumeLimit() {
        fx.smsDelivery = false
        for (id in listOf("a", "b", "c")) { send(GuardianSmsWireKind.EVENT, id); later(1_000) }
        fx.smsDelivery = true
        send(GuardianSmsWireKind.EVENT, "d")
        assertTrue("d 는 나감", store.smsRecords.any { it.eventId == "d" && it.sent })
        assertFalse(Notice.SMS_SKIPPED_LIMIT in fx.notices)
    }

    /** sec-audit — 실패 10개 뒤 신호가 돌아와도 자동 다시 보내기로 나가는 문자는 상한 안이다(일반 3통 · 사고 3통). */
    @Test fun autoResendRespectsLimit() {
        val ongoing = ExecContext(stopped = true, testSession = false, incidentOngoing = { true })
        fun ctx2() = ongoing.copy(nowLocal = mono, nowWall = wall)
        fun cmd120(kind: GuardianSmsWireKind, id: String) = Payload.SendGuardianSms(kind, id, WireFacts(SnapshotClock.LOCAL), test = false).let {
            Command("id-${n++}", it.type, it.toJson(), 120_000, false, Party.CAR, CommandTo.PHONE)
        }
        fx.smsDelivery = false
        for (i in 1..10) { ex.execute(cmd120(GuardianSmsWireKind.EVENT, "e-$i"), ctx2()); later(1_000) }
        for (i in 1..5) { ex.execute(cmd120(GuardianSmsWireKind.ACCIDENT, "accident-$i"), ctx2()); later(1_000) }
        fx.smsDelivery = true
        repeat(180) { later(1_000); ex.onTick(ctx2()) }
        assertTrue("일반 3통 이하", store.smsRecords.count { it.sent && it.kind == GuardianSmsWireKind.EVENT } <= 3)
        assertTrue("사고 3통 이하", store.smsRecords.count { it.sent && it.kind == GuardianSmsWireKind.ACCIDENT } <= 3)
    }

    /** 칸이 없어 건너뛴 자동 다시 보내기는 버리지 않는다 — 칸이 풀린 뒤(10분 지나) 다음 차례에 나간다(놓치지 않음). */
    @Test fun skippedResendGoesOutAfterWindow() {
        val ongoing = ExecContext(stopped = true, testSession = false, incidentOngoing = { true })
        fun ctx2() = ongoing.copy(nowLocal = mono, nowWall = wall)
        fun cmd120(id: String) = Payload.SendGuardianSms(GuardianSmsWireKind.EVENT, id, WireFacts(SnapshotClock.LOCAL), test = false).let {
            Command("id-${n++}", it.type, it.toJson(), 120_000, false, Party.CAR, CommandTo.PHONE)
        }
        for (i in 1..2) ex.execute(cmd120("e-$i"), ctx2()) // 0분 — 칸 둘
        later(60_000)
        fx.smsDelivery = false
        ex.execute(cmd120("late"), ctx2()) // 1분 — 보내지 못함(칸에서 빠짐)
        fx.smsDelivery = true
        later(60_000)
        ex.execute(cmd120("e-3"), ctx2()) // 2분 — 남은 칸을 채움
        assertEquals(3, store.smsRecords.count { it.sent })
        repeat(470) { later(1_000); ex.onTick(ctx2()) } // 9분 50초까지 — 칸이 없어 차례마다 건너뜀
        assertFalse("칸이 없는 동안은 건너뜀", store.smsRecords.any { it.eventId == "late" && it.sent })
        repeat(60) { later(1_000); ex.onTick(ctx2()) } // 10분을 넘겨 앞 칸 둘이 풀린 뒤 · 다시 보내기 상한(1분 + 10분) 안
        assertTrue("칸이 풀린 뒤 나감", store.smsRecords.any { it.eventId == "late" && it.sent })
    }

    /** 보내는 중(결과 기다림)은 세어 동시 다발을 막는다. */
    @Test fun waitingSendsCount() {
        fx.smsDelivery = null
        for (id in listOf("a", "b", "c", "d")) send(GuardianSmsWireKind.EVENT, id)
        assertEquals(3, fx.sms.size)
    }

    /** 정상 사고 한 건(사건 → 정차 관찰 → 사고 → 취소)은 막히지 않는다. */
    @Test fun normalIncidentChainPasses() {
        send(GuardianSmsWireKind.EVENT, "event-9")
        send(GuardianSmsWireKind.OBSERVE, "event-9")
        send(GuardianSmsWireKind.ACCIDENT, "accident-9")
        send(GuardianSmsWireKind.CANCEL, "event-9")
        assertEquals(4, fx.sms.size)
    }

    @Test fun cancelIsNotCounted() {
        for (i in 1..3) send(GuardianSmsWireKind.EVENT, "e-$i")
        send(GuardianSmsWireKind.CANCEL, "e-1")
        assertEquals(4, fx.sms.size)
    }

    @Test fun afterTenMinutesAllowedAgain_dayCapTen() {
        for (i in 1..3) send(GuardianSmsWireKind.EVENT, "a-$i")
        later(600_001)
        send(GuardianSmsWireKind.EVENT, "b-1")
        assertEquals(4, fx.sms.size)
        var k = 0
        repeat(5) { later(600_001); repeat(3) { send(GuardianSmsWireKind.EVENT, "c-${k++}") } }
        assertEquals("24시간 10통", 10, fx.sms.size)
    }

    private fun accidentSent(id: String) = store.smsRecords.any { it.kind == GuardianSmsWireKind.ACCIDENT && it.eventId == id && it.sent }

    /** 결정 27 — 사건 id 가 다른 사고 문자 둘이 40초 간격이면 둘 다 나간다(서로 다른 에어백 전개를 놓치지 않음). */
    @Test fun twoDistinctAccidents40sApart_bothSent() {
        send(GuardianSmsWireKind.ACCIDENT, "accident-1")
        later(40_000)
        send(GuardianSmsWireKind.ACCIDENT, "accident-2")
        assertTrue(accidentSent("accident-1") && accidentSent("accident-2"))
        assertFalse(Notice.SMS_SKIPPED_LIMIT in fx.notices)
    }

    /** 결정 27 — 사고 칸 10분 3통. 넷째 서로 다른 사고 문자는 막히고, 첫 문자 뒤 10분 정각까지는 막히며 그 1ms 뒤에 나간다. */
    @Test fun accidentShortCap_threePer10Min_boundary() {
        for (i in 1..3) send(GuardianSmsWireKind.ACCIDENT, "a-$i")
        send(GuardianSmsWireKind.ACCIDENT, "a-4")
        assertEquals(3, fx.sms.size)
        assertFalse("넷째는 막힘", accidentSent("a-4"))
        later(600_000)
        send(GuardianSmsWireKind.ACCIDENT, "a-5")
        assertFalse("10분 정각은 아직 창 안", accidentSent("a-5"))
        later(1)
        send(GuardianSmsWireKind.ACCIDENT, "a-6")
        assertTrue("10분 1ms 뒤는 나감", accidentSent("a-6"))
    }

    /** 결정 27 — 사고 칸 24시간 10통. 열한째는 막히고, 첫 문자 뒤 24시간 정각까지는 막히며 그 1ms 뒤에 나간다. */
    @Test fun accidentDayCap_tenPer24h_boundary() {
        send(GuardianSmsWireKind.ACCIDENT, "d-0")
        var k = 1
        repeat(3) { later(600_001); repeat(3) { send(GuardianSmsWireKind.ACCIDENT, "d-${k++}") } }
        assertEquals(10, fx.sms.size)
        later(600_001)
        send(GuardianSmsWireKind.ACCIDENT, "over-1")
        assertFalse("열한째는 막힘", accidentSent("over-1"))
        later(RemoteSmsLimit.DAY_MS - 4 * 600_001L)
        send(GuardianSmsWireKind.ACCIDENT, "over-2")
        assertFalse("24시간 정각은 아직 창 안", accidentSent("over-2"))
        later(1)
        send(GuardianSmsWireKind.ACCIDENT, "over-3")
        assertTrue("24시간 1ms 뒤는 나감", accidentSent("over-3"))
    }

    /** 결정 22 — 같은 사고 문자(종류 · 사건 id 같음)의 다시 보내기 · 다시 싣기는 칸을 더 쓰지 않는다. */
    @Test fun sameAccidentResend_countedOnce() {
        val a = GuardianSmsWireKind.ACCIDENT
        var st = RemoteSmsLimit.State()
        repeat(5) { st = RemoteSmsLimit.record(st, a, "acc-1", wall) }
        assertEquals(1, st.sent.size)
        st = RemoteSmsLimit.record(st, a, "acc-2", wall)
        assertTrue("셋째 칸이 남음", RemoteSmsLimit.allows(st, a, "acc-3", wall))
        st = RemoteSmsLimit.record(st, a, "acc-3", wall)
        assertFalse(RemoteSmsLimit.allows(st, a, "acc-4", wall))
        assertTrue("센 문자의 다시 보내기는 칸이 차도 됨", RemoteSmsLimit.allows(st, a, "acc-1", wall))
    }

    /** 결정 22 · 27 — 사고 칸이 차도 일반 문자는 막히지 않는다. */
    @Test fun generalNotBlockedByFullAccidentBucket() {
        for (i in 1..4) send(GuardianSmsWireKind.ACCIDENT, "a-$i")
        send(GuardianSmsWireKind.EVENT, "e-1")
        assertTrue(store.smsRecords.any { it.eventId == "e-1" && it.sent })
        assertEquals(4, fx.sms.size)
    }

    /** 사고 칸 셈은 저장 · 복원 뒤에도, 벽시계를 되돌려도 유지된다. */
    @Test fun accidentCountSurvivesRestartAndWallClockBack() {
        for (i in 1..3) send(GuardianSmsWireKind.ACCIDENT, "a-$i")
        assertEquals(3, RemoteSmsLimit.decode(RemoteSmsLimit.encode(RemoteSmsLimit.decode(store.remoteSmsLimit))).sent.size)
        ex = CommandExecutor(store, fx, FakeBodies())
        send(GuardianSmsWireKind.ACCIDENT, "a-4")
        assertFalse("다시 띄운 뒤에도 넷째는 막힘", accidentSent("a-4"))
        wall -= 3_600_000
        send(GuardianSmsWireKind.ACCIDENT, "a-5")
        assertFalse("벽시계를 되돌려도 막힘", accidentSent("a-5"))
        assertEquals(3, fx.sms.size)
    }

    @Test fun phoneOwnIncidentsAreNotCounted() {
        for (i in 1..5) send(GuardianSmsWireKind.EVENT, "p-$i", from = Party.PHONE)
        assertEquals(5, fx.sms.size)
        for (i in 1..3) send(GuardianSmsWireKind.EVENT, "c-$i")
        assertEquals("휴대폰 사건은 원격 계수에 들어가지 않는다", 8, fx.sms.size)
    }

    @Test fun countSurvivesRestart() {
        for (i in 1..3) send(GuardianSmsWireKind.EVENT, "e-$i")
        ex = CommandExecutor(store, fx, FakeBodies())
        send(GuardianSmsWireKind.EVENT, "e-4")
        assertEquals(3, fx.sms.size)
    }

    /** 벽시계를 되돌려도 상한이 풀리지 않는다. */
    @Test fun wallClockBackDoesNotReset() {
        for (i in 1..3) send(GuardianSmsWireKind.EVENT, "e-$i")
        wall -= 3_600_000
        send(GuardianSmsWireKind.EVENT, "e-4")
        assertEquals(3, fx.sms.size)
        assertFalse(store.smsRecords.any { it.eventId == "e-4" && it.sent })
    }
}
