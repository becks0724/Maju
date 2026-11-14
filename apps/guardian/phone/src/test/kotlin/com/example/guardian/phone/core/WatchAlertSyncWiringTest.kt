// 계약 §4.3 휴대폰 주인 쪽 연결 시험(C-2 · B-5) — 워치 경보가 내 흐름과 어긋나면 시작 · 정지를 다시 내고(각 10초에 한 번), 워치 응답은 응답 번호로 한 번만 받는다
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchAlertStatus
import com.example.guardian.shared.protocol.WatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAlertSyncWiringTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { false })

    private fun w(v: WristVerdict, alerting: WatchAlertStatus? = null, seq: Long? = null, responseAt: Long? = null) =
        Watch(v, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = responseAt, responseSeq = seq, alerting = alerting, alertingReported = true)

    private fun loc(speed: Double) = Loc(37.57590, 126.97690, 8.0, speed, wall0, "주소")
    private fun step(watch: Watch, speed: Double = 60.0) { mono += 1_000; engine.onDataLayerWatch(watch, mono); engine.onLocation(loc(speed), mono); engine.tick(mono) }

    /** 다음 sync 에 실릴 워치 몫 지시(보내고 나면 비운다). */
    private fun watchCmds(): List<Pair<CommandType, Payload>> {
        val req = engine.buildSync("tok", mono)
        engine.onSyncResponse(PhoneSyncResponse(mono + 5_000_000_000L, null, null, emptyList()), mono, mono)
        return req.commands.filter { it.to == CommandTo.WATCH || it.to == CommandTo.ALL }.map { it.type to Payload.fromJson(it.type, it.payload) }
    }

    /** 주행 중 맥박 없음 — S1(경보 1) 까지. */
    /** [seq] 는 그동안 워치가 싣는 응답 번호(누름 없음) — 기준이 된다. */
    private fun toS1(seq: Long? = null): String {
        repeat(3) { step(w(WristVerdict.NORMAL, seq = seq)) }
        repeat(3) { step(w(WristVerdict.PULSE_ABSENT, seq = seq)) }
        val st = engine.status(mono)
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(FlowState.S1, st.flowState)
        watchCmds() // 상태 기계가 처음 낸 시작은 비운다
        return st.eventId!!
    }

    @Test fun watchNotRinging_startIsReissuedEvery10s() {
        toS1() // S1 에 들어선 틱에 워치가 안 울려 이미 한 번 다시 냈다(그 지시는 비웠다)
        repeat(5) { step(w(WristVerdict.PULSE_ABSENT, alerting = null)) }
        assertTrue("10초 안에는 다시 내지 않는다", watchCmds().none { it.first == CommandType.ALERT_START })
        repeat(5) { step(w(WristVerdict.PULSE_ABSENT, alerting = null)) }
        val start = watchCmds().filter { it.first == CommandType.ALERT_START }
        assertEquals("10초가 지나면 워치에 시작을 다시 낸다", 1, start.size)
    }

    @Test fun watchRingingSameIncident_noReissue() {
        val id = toS1()
        repeat(12) { step(w(WristVerdict.PULSE_ABSENT, alerting = WatchAlertStatus(id, FlowState.S1, 1))) }
        assertTrue(watchCmds().none { it.first == CommandType.ALERT_START })
    }

    @Test fun watchRingingWhileNotAlerting_stopWithItsEventId() {
        repeat(5) { step(w(WristVerdict.NORMAL)) }
        assertEquals(FlowState.S0, engine.status(mono).flowState)
        watchCmds()
        repeat(11) { step(w(WristVerdict.NORMAL, alerting = WatchAlertStatus("event-old", FlowState.S1, 1))) }
        val stop = watchCmds().filter { it.first == CommandType.ALERT_STOP }
        assertEquals("모르는 사건은 10초 지켜본 뒤 정지 한 번", 1, stop.size)
        assertEquals("event-old", (stop.single().second as Payload.AlertStop).eventId)
    }

    /** 응답 번호 한 개 넣기(울리는 중 · 정차 아님). */
    private fun seq(id: String, n: Long?) = step(w(WristVerdict.PULSE_ABSENT, alerting = WatchAlertStatus(id, FlowState.S1, 1), seq = n))
    private fun state() = engine.status(mono).flowState

    /**
     * app-qa 3차 조건 1 — 워치 앱을 다시 설치해 번호가 다시 시작해도 그 뒤 응답을 받는다. 공용 규칙(be-dev 2043b55a)은 작은 번호가
     * 연달아 REINSTALL_RUN(3)번 오면 재설치로 보고 기준을 옮긴다 — 그 뒤 누름은 응답.
     */
    @Test fun responseSeqRestartOnWatch_afterThreeLowValues_laterPressCounts() {
        assertEquals(3, com.example.guardian.shared.protocol.WatchResponseTracker.REINSTALL_RUN)
        val id = toS1(seq = 7)
        repeat(3) { seq(id, 1) }
        assertEquals("다시 시작한 번호는 기준만", FlowState.S1, state())
        seq(id, 2)
        assertEquals("그 뒤 누름은 응답", FlowState.S1R, state())
    }

    /** 작은 번호가 한두 번만 오면 늦게 온 옛 값으로 보고 무시한다 — 기준은 그대로라 그보다 큰 누름만 응답. */
    @Test fun oneOrTwoLowValuesAreIgnored() {
        val id = toS1(seq = 7)
        seq(id, 1); seq(id, 1)
        seq(id, 7)
        seq(id, 2)
        assertEquals("옛 값 · 기준보다 작은 값은 응답이 아니다", FlowState.S1, state())
        seq(id, 8)
        assertEquals(FlowState.S1R, state())
    }

    /** app-qa 가 재현한 길(7 → 번호 없음 → 1 → 2 · 2 · 2 → 3 · 3 · 3)이 새 규칙에서도 닫혀 있다 — 재설치 뒤 워치 응답을 계속 버리지 않는다. */
    @Test fun appQaReproPathIsClosed() {
        val id = toS1(seq = 7)
        seq(id, null); seq(id, 1)
        repeat(3) { seq(id, 2) }
        repeat(3) { seq(id, 3) }
        assertEquals(FlowState.S1R, state())
    }

    /** 응답 번호 — 늘면 응답 하나, 두 길로 같은 번호가 와도 한 번. */
    @Test fun responseSeqCountsOnce() {
        val id = toS1(seq = 4)
        seq(id, 4)
        assertEquals(FlowState.S1, state())
        seq(id, 5)
        assertEquals("번호가 늘면 응답", FlowState.S1R, state())
        // 나쁜 판정이 이어져 다시 경보 상태가 된다(재경보) — 그동안 워치 번호는 5 그대로
        var n = 0
        while (state() !in PhoneEngine.ALARM_STATES && n++ < 90) seq(engine.status(mono).eventId ?: id, 5)
        assertTrue("다시 경보 — ${state()}", state() in PhoneEngine.ALARM_STATES)
        // 같은 번호 5 가 중계 경로로 늦게 와도(두 길) 응답으로 세지 않는다 — 경보 상태 그대로
        repeat(3) {
            engine.buildSync("tok", mono)
            engine.onSyncResponse(PhoneSyncResponse(mono + 5_000_000_000L, null, w(WristVerdict.PULSE_ABSENT, seq = 5).copy(ageMs = 0), emptyList()), mono, mono)
            seq(engine.status(mono).eventId!!, 5)
        }
        assertTrue("같은 번호는 한 번만 — ${state()}", state() in PhoneEngine.ALARM_STATES)
        seq(engine.status(mono).eventId!!, 6)
        assertTrue("새 누름은 응답 — ${state()}", state() !in PhoneEngine.ALARM_STATES)
    }
}
