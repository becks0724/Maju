// 끄기 · 철회 주기 끝의 서비스 판단 시험(코드 리뷰 2026-11-09 하1 · 하) — 다시 켠 요청은 멈추지 않고, 철회였으면 멈출 때 저장을 한 번 더 지운다
package com.example.guardian.phone.core

import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.protocol.SnapshotClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStopTest {
    // ── 하(의심) — 마지막 sync 도중 다시 켠 요청까지 멈추지 않는다 ──

    @Test fun powerOffStopsWithLatestStartId() {
        val s = ServiceStop()
        s.onStart(1); s.onStart(2)
        assertEquals(2, s.stopId { false })
    }

    @Test fun turnedBackOnDuringLastSyncIsNotStopped() {
        val s = ServiceStop()
        s.onStart(1)
        assertNull("마지막 sync 도중 다시 켰다 — 멈추지 않는다", s.stopId { true })
    }

    /** 켬을 읽은 뒤 새 시작이 와도 — 앞서 잡은 id 로 멈추므로 stopSelf(startId) 가 새 시작을 멈추지 않는다. */
    @Test fun startArrivingAfterPowerCheckKeepsOldId() {
        val s = ServiceStop()
        s.onStart(1)
        val id = s.stopId { s.onStart(2); false }
        assertEquals(1, id)
    }

    // ── 하1 — 철회 뒤 마지막 sync 를 기다리는 사이 실행기가 저장을 다시 쓴다 ──

    private class ClearableStore : PhoneStore {
        override var registration: Registration = fullRegistration()
        val sent = LinkedHashSet<String>()
        val done = HashSet<String>()
        override fun isSmsSent(key: String) = key in sent
        override fun markSmsSent(key: String) { sent += key }
        override var smsRecords: List<com.example.guardian.shared.protocol.GuardianSmsDedup.Record> = emptyList()
        override fun isCommandDone(id: String) = id in done
        override fun markCommandDone(id: String) { done += id }
        override var savedFlow: SavedFlow? = null
        fun clearAll() { registration = Registration(); sent.clear(); done.clear(); smsRecords = emptyList(); savedFlow = null }
        val empty get() = sent.isEmpty() && done.isEmpty() && smsRecords.isEmpty()
    }

    private val wall0 = 1_793_000_000_000L
    private fun smsCmd(id: String) = Command(
        id, CommandType.SEND_GUARDIAN_SMS,
        Payload.SendGuardianSms(GuardianSmsWireKind.EVENT, "event-1", WireFacts(SnapshotClock.SERVER, firstAlertAt = wall0), test = false).toJson(),
        30_000, false, Party.CAR, CommandTo.PHONE,
    )

    /** 재현 — 문자 결과를 기다리는 중에 철회(모두 지우기)하면, 늦게 온 결과가 지운 저장에 문자 기록 · 지시 id 를 다시 쓴다. */
    @Test fun executorRewritesStoreAfterWithdrawalUntilClearedOnStop() {
        val store = ClearableStore()
        val fx = FakeEffects().apply { smsDelivery = null }
        val ex = CommandExecutor(store, fx, FakeBodies())
        val ctx = ExecContext(stopped = true, testSession = false, nowLocal = 1_000, nowWall = wall0)
        assertFalse("결과를 기다린다", ex.execute(smsCmd("car-1"), ctx))

        val stop = ServiceStop()
        store.clearAll(); stop.onWithdraw() // 철회 — 저장을 바로 지우고 마지막 sync 를 맡긴다
        fx.heldResults.single()(true) // 기다림(최대 6초) 사이 결과가 온다
        ex.execute(smsCmd("car-1"), ctx) // 지시가 다시 오면 ack 하며 지시 id 를 남긴다
        assertFalse("고치기 전 — 지운 저장에 다시 썼다", store.empty)

        stop.onStopped(powerOnNow = false) { store.clearAll() }
        assertTrue("멈출 때 한 번 더 지워 남지 않는다(G2)", store.empty)
    }

    /** 코드 리뷰 2026-11-09 새로 찾은 것(c) — 철회가 화면 문자 기록을 비운 뒤, 기다림 중 늦게 온 결과가 다시 들어갔다. 철회 표시가 있으면 넣지 않는다. */
    @Test fun lateSmsResultIsNotLoggedAfterWithdrawal() {
        val s = ServiceStop()
        assertTrue("평소에는 넣는다", s.acceptsSmsLog())
        s.onWithdraw()
        assertFalse("철회 뒤에는 넣지 않는다", s.acceptsSmsLog())
        s.stopId { true } // 그사이 다시 켜 이어 가면 다시 넣는다
        assertTrue(s.acceptsSmsLog())
    }

    @Test fun plainPowerOffKeepsStore() {
        var cleared = false
        ServiceStop().onStopped(powerOnNow = false) { cleared = true }
        assertFalse("끄기는 짝 · 등록 정보 · 문자 기록을 남긴다", cleared)
    }

    /** 철회 뒤 그사이 다시 등록해 켰으면(멈추지 않음) 새 등록을 지우지 않는다. */
    @Test fun turnedBackOnAfterWithdrawalIsNotCleared() {
        val s = ServiceStop()
        s.onStart(1); s.onWithdraw()
        assertNull(s.stopId { true })
        var cleared = false
        s.onStopped(powerOnNow = true) { cleared = true }
        assertFalse(cleared)
        assertFalse("다시 켜 이어 가면 철회 표시를 거둔다", s.withdrew)
    }
}
