// 워치 응답 시험(11-02 남은 하) — 공통 ResponseChange 규칙(증가만 · 기준은 마지막 값)대로, 시계가 뒤로 보정된 뒤의 응답을 놓치지 않고 두 경로가 번갈아도 거짓 응답이 없다
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Via
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchResponseTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { false })

    private fun watch(v: WristVerdict, r: Long?) = Watch(v, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = r)
    private fun moving() = engine.onLocation(Loc(37.57590, 126.97690, 8.0, 60.0, wall0, null), mono)

    private fun dl(v: WristVerdict, r: Long?) { engine.onDataLayerWatch(watch(v, r), mono); moving() }

    private fun relay(v: WristVerdict, r: Long?) {
        engine.buildSync("tok", mono)
        val w = watch(v, r).copy(via = Via.WATCH, receivedAt = mono + serverOffset, ageMs = 0)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, null, w, emptyList()), mono, mono)
        moving()
    }

    private fun state() = engine.status(mono).flowState

    private fun until(limitMs: Long, done: () -> Boolean, each: () -> Unit) {
        val end = mono + limitMs
        while (!done()) { check(mono < end) { "상태가 오지 않음: ${state()}" }; mono += 1_000; each(); engine.tick(mono) }
    }

    private fun alarm() = state() in PhoneEngine.ALARM_STATES

    /** 응답 → 쉬는 중 → S0 → 새 경보까지. 응답 시각은 [r] 그대로 둔다. */
    private fun answerAndRealert(r: Long, feed: (WristVerdict, Long?) -> Unit) {
        until(5_000, { state() == FlowState.S1R }) { feed(WristVerdict.PULSE_WEAK, r) }
        until(900_000, { state() == FlowState.S0 }) { feed(WristVerdict.NORMAL, r) }
        until(120_000, ::alarm) { feed(WristVerdict.PULSE_WEAK, r) }
    }

    @Test fun responseAfterWatchClockMovedBackIsNotMissed() {
        dl(WristVerdict.NORMAL, null)
        engine.tick(mono)
        until(120_000, ::alarm) { dl(WristVerdict.PULSE_WEAK, null) }
        val r1 = wall0 + (mono - mono0)
        answerAndRealert(r1, ::dl)
        // 워치 벽시계가 15분 뒤로 보정됐다 — 그 뒤 첫 누름은 옛 값보다 작다(계약 §4.3 「작아지면 무시, 기준은 마지막 값」)
        val r2 = wall0 + (mono - mono0) - 900_000
        assertTrue(r2 < r1)
        mono += 1_000; dl(WristVerdict.PULSE_WEAK, r2); engine.tick(mono)
        assertTrue("작아진 값은 응답이 아니다(계약): ${state()}", alarm())
        // 다음 누름 — 보정 전 값(r1)보다 작지만 마지막 값(r2)보다 커졌다 → 응답
        val r3 = r2 + 10_000
        assertTrue(r3 < r1)
        mono += 1_000; dl(WristVerdict.PULSE_WEAK, r3); engine.tick(mono)
        assertEquals("시계가 뒤로 간 뒤의 새 응답을 받는다", FlowState.S1R, state())
    }

    /** app-qa 하1 — 중계로만 오던 중 Data Layer 가 처음 붙으며 새 누름을 싣고 중계도 곧 같은 값을 싣는다. 첫(기준) 값이 다른 경로의 응답을 막지 않는다. */
    @Test fun firstDataLayerValueCarryingNewPressIsNotLost() {
        val r0 = wall0 - 60_000
        relay(WristVerdict.NORMAL, r0); engine.tick(mono)
        until(120_000, ::alarm) { relay(WristVerdict.PULSE_WEAK, r0) }
        val r1 = wall0 + (mono - mono0)
        mono += 1_000; dl(WristVerdict.PULSE_WEAK, r1); engine.tick(mono)
        mono += 1_000; relay(WristVerdict.PULSE_WEAK, r1); engine.tick(mono)
        mono += 1_000; relay(WristVerdict.PULSE_WEAK, r1); engine.tick(mono)
        assertEquals("새 누름 r1 은 응답이다", FlowState.S1R, state())
    }

    // ── 경로가 늦게 붙을 때(app-qa 재검증 하1) ──

    private fun step(f: () -> Unit) { mono += 1_000; f(); engine.tick(mono) }

    /** 중계로만 오다 Data Layer 첫 값이 새 누름 r1, 이어 중계도 r1. */
    @Test fun dlFirstNewPress() {
        val r0 = wall0 - 60_000
        relay(WristVerdict.NORMAL, r0); engine.tick(mono)
        until(300_000, ::alarm) { relay(WristVerdict.PULSE_WEAK, r0) }
        val r1 = wall0 + (mono - mono0)
        step { dl(WristVerdict.PULSE_WEAK, r1) }
        step { relay(WristVerdict.PULSE_WEAK, r1) }
        assertEquals(FlowState.S1R, state())
    }

    /** 위와 같되 중계가 한 틱 늦게 r1 을 싣는다(Data Layer 1초 · 중계 2초 주기라 보통 순서). */
    @Test fun dlFirstNewPress_relayOneTickLate() {
        val r0 = wall0 - 60_000
        relay(WristVerdict.NORMAL, r0); engine.tick(mono)
        until(300_000, ::alarm) { relay(WristVerdict.PULSE_WEAK, r0) }
        val r1 = wall0 + (mono - mono0)
        step { dl(WristVerdict.PULSE_WEAK, r1) }
        step { dl(WristVerdict.PULSE_WEAK, r1) }
        step { relay(WristVerdict.PULSE_WEAK, r1) }
        step { relay(WristVerdict.PULSE_WEAK, r1) }
        assertEquals(FlowState.S1R, state())
    }

    /** 반대 조합 — Data Layer 로만 오다 중계 첫 값이 새 누름 r1, Data Layer 도 r1. */
    @Test fun relayFirstNewPress() {
        val r0 = wall0 - 60_000
        dl(WristVerdict.NORMAL, r0); engine.tick(mono)
        until(300_000, ::alarm) { dl(WristVerdict.PULSE_WEAK, r0) }
        val r1 = wall0 + (mono - mono0)
        step { relay(WristVerdict.PULSE_WEAK, r1) }
        step { dl(WristVerdict.PULSE_WEAK, r1) }
        assertEquals(FlowState.S1R, state())
    }

    /** 중계 첫 값이 새 누름인데 Data Layer 는 그 뒤 오지 않는다(끊김). */
    @Test fun relayFirstNewPressDlGone() {
        val r0 = wall0 - 60_000
        dl(WristVerdict.NORMAL, r0); engine.tick(mono)
        until(300_000, ::alarm) { dl(WristVerdict.PULSE_WEAK, r0) }
        val r1 = wall0 + (mono - mono0)
        step { relay(WristVerdict.PULSE_WEAK, r1) }
        step { relay(WristVerdict.PULSE_WEAK, r1) }
        assertEquals(FlowState.S1R, state())
    }

    /** Data Layer 첫 값 r0(옛 누름), 중계 첫 값은 더 옛 값 rX. 경보 중 중계가 r0 을 늦게 실어 와도 새 누름이 아니다. */
    @Test fun staleRelayCatchesUpToOldPress_noFalseResponse() {
        val rX = wall0 - 600_000
        val r0 = wall0 - 60_000
        dl(WristVerdict.NORMAL, r0); relay(WristVerdict.NORMAL, rX); engine.tick(mono)
        until(300_000, ::alarm) { dl(WristVerdict.PULSE_WEAK, r0); relay(WristVerdict.PULSE_WEAK, rX) }
        step { dl(WristVerdict.PULSE_WEAK, r0); relay(WristVerdict.PULSE_WEAK, r0) }
        step { dl(WristVerdict.PULSE_WEAK, r0); relay(WristVerdict.PULSE_WEAK, r0) }
        assertTrue("옛 누름으로 경보가 꺼지면 안 된다: ${state()}", alarm())
    }

    /** 중계 경유 값이 옛 응답에 머물고 Data Layer 가 새 응답을 가진 채 번갈아 와도 같은 누름을 거듭 응답으로 보지 않는다. */
    @Test fun alternatingPathsDoNotMakeFalseResponses() {
        val r0 = wall0 - 60_000 // 시작 전 응답 — 기준
        relay(WristVerdict.NORMAL, r0); dl(WristVerdict.NORMAL, r0)
        engine.tick(mono)
        var flip = false
        val alt = { v: WristVerdict, r: Long? -> flip = !flip; if (flip) relay(v, r0) else dl(v, r) }
        until(120_000, ::alarm) { alt(WristVerdict.PULSE_WEAK, r0) }
        val r1 = wall0 + (mono - mono0)
        answerAndRealert(r1, alt)
        // 새 경보 — 중계 r0 · Data Layer r1 이 번갈아 와도 응답이 아니다
        repeat(10) { mono += 1_000; alt(WristVerdict.PULSE_WEAK, r1); engine.tick(mono) }
        assertTrue("거짓 응답이 경보를 끄지 않는다: ${state()}", alarm())
        // 중계가 늦게 같은 누름(r1)을 실어 와도 다시 응답이 아니다
        mono += 1_000; relay(WristVerdict.PULSE_WEAK, r1); engine.tick(mono)
        assertTrue(alarm())
    }
}
