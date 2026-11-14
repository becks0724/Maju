// 동의 철회 · 기록 지우기 시험 — 철회 뒤 등록 정보 · 토큰이 남지 않고 짝을 끝내며, 주행 중에는 줄이 보이지 않는다
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WithdrawalTest {
    /** Prefs 와 같은 모양의 가짜 — 등록 정보 · 토큰 · 문자 기록을 한곳에 둔다. */
    private class FakeReset : ResetStore {
        var registration: Registration? = fullRegistration()
        override var token: String? = "tok-1"
        val smsSent = mutableSetOf("EVENT:event-1")
        override fun clearAll() { registration = null; token = null; smsSent.clear() }
    }

    private fun status(stopped: Boolean) = EngineStatus(
        owner = Owner.CAR, carFresh = true, flowState = FlowState.S0, stopped = stopped, watchVerdict = null, watchStale = true,
        clockUnverified = false, canRespond = false, has119 = false, test = null, testScenario = null, testFlowState = null,
        stage = 0, flowTest = false, noResponseSec = null, stoppedSinceWall = null, shownState = FlowState.S0,
    )

    @Test fun withdrawClearsEverythingAndEndsPair() {
        val store = FakeReset()
        val order = ArrayList<String>()
        val ended = ArrayList<String>()
        Withdrawal.run(store, lastSync = { _, after -> order += "sync"; after() }, endPair = { order += "end"; ended += it })
        assertNull(store.registration)
        assertNull(store.token)
        assertTrue(store.smsSent.isEmpty())
        assertEquals(listOf("tok-1"), ended) // 지우기 전에 잡아 둔 토큰으로 짝을 끝낸다
        assertEquals(listOf("sync", "end"), order)
    }

    /**
     * 1차 [미검증] — 철회가 토큰을 먼저 지워 서비스의 마지막 sync(OFF)가 토큰 없이 건너뛰어졌다. 마지막 sync 는 지우기 전에 잡아 둔 토큰으로 가고,
     * 짝 끝내기는 마지막 sync 가 끝난 뒤에만 간다. 기기 안 저장(토큰 포함)은 마지막 sync 전에 이미 지워져 있다(G2).
     */
    @Test fun lastSyncUsesCapturedTokenAndPairEndsOnlyAfterIt() {
        val store = FakeReset()
        var syncedWith: String? = null
        var clearedBeforeSync = false
        var pending: (() -> Unit)? = null
        val ended = ArrayList<String>()
        Withdrawal.run(store, lastSync = { t, after -> syncedWith = t; clearedBeforeSync = store.registration == null && store.token == null; pending = after }, endPair = { ended += it })
        assertEquals("마지막 sync 는 잡아 둔 토큰으로 — 차에 OFF 가 간다", "tok-1", syncedWith)
        assertTrue("기기 안 저장은 바로 지운다(G2)", clearedBeforeSync)
        assertTrue("마지막 sync 가 끝나기 전에는 짝을 끝내지 않는다", ended.isEmpty())
        pending!!()
        assertEquals(listOf("tok-1"), ended)
    }

    /** 짝 끝내는 순서 자체 — 지우기 → 마지막 sync → 짝 끝내기. 동의 거부도 [Withdrawal.run](저장 모두 지우기)으로 이 순서를 탄다(PM 결정 2026-11-09). */
    @Test fun consentWithdrawalSameOrder() {
        val order = ArrayList<String>()
        Withdrawal.end("tok-2", clear = { order += "clear" }, lastSync = { t, after -> order += "sync:$t"; after() }, endPair = { order += "end:$it" })
        assertEquals(listOf("clear", "sync:tok-2", "end:tok-2"), order)
    }

    @Test fun withdrawWithoutPairStillClears() {
        val store = FakeReset().apply { token = null }
        var ended = false
        var synced = false
        Withdrawal.run(store, lastSync = { _, after -> synced = true; after() }, endPair = { ended = true })
        assertNull(store.registration)
        assertTrue("감시를 멈추는 마지막 주기는 그대로 돈다", synced)
        assertFalse(ended)
    }

    // ── 마지막 sync 기다리기 — 차가 내 꺼짐을 읽은 뒤에 짝을 끝낸다 ──

    private fun resp(serverTime: Long, carAt: Long?) = PhoneSyncResponse(
        serverTime, carAt?.let { CarPeer(seq = 1, sentAt = 0, receivedAt = it, vehicle = Vehicle(speedKmh = 0.0), flow = null) }, null, emptyList(),
    )

    @Test fun waitsUntilCarSyncedAfterLastSync() {
        var ended = 0
        val w = LastSyncWait("tok") { ended++ }
        assertFalse("차가 아직 내 마지막 sync 뒤에 붙지 않았다", w.done(resp(10_000, 9_000), 0))
        assertFalse(w.done(resp(12_000, 10_000), 2_000)) // 같은 시각 — 순서를 모른다
        assertTrue("차가 그 뒤에 붙었다 — 내 OFF 를 읽었다", w.done(resp(14_000, 11_500), 4_000))
        w.finish(); w.finish()
        assertEquals("짝 끝내기는 한 번만", 1, ended)
    }

    @Test fun doesNotWaitWithoutLiveCarOrOnFailureOrTimeout() {
        assertTrue("차 없음", LastSyncWait("tok") {}.done(resp(10_000, null), 0))
        assertTrue("차가 10초 넘게 낡음", LastSyncWait("tok") {}.done(resp(30_000, 10_000), 0))
        assertTrue("sync 실패", LastSyncWait("tok") {}.done(null, 0))
        assertTrue("토큰 없음", LastSyncWait(null) {}.done(resp(10_000, 9_000), 0))
        val w = LastSyncWait("tok") {}
        assertFalse(w.done(resp(10_000, 9_000), 0))
        assertTrue("최대 대기가 지났다", w.done(resp(10_000 + Withdrawal.CAR_READ_WAIT_MS, 9_000), Withdrawal.CAR_READ_WAIT_MS))
    }

    @Test fun rowHiddenWhileDriving() {
        assertFalse(Withdrawal.visible(status(stopped = false)))
        assertTrue(Withdrawal.visible(status(stopped = true)))
        assertTrue(Withdrawal.visible(null)) // 감시가 꺼져 있으면(정지 여부 모름) 보인다
        assertTrue(Withdrawal.visible(status(stopped = false).copy(motion = Motion.UNKNOWN))) // 차 · GPS 모두 없으면(모름) 보인다
    }
}
