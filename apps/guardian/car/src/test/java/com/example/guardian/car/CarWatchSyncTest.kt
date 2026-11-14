// 차가 주인일 때 워치 경보 맞추기와 워치 응답 번호 시험 — 안 울리면 시작 · 경보 밖인데 울리면 정지(각 10초에 한 번), 옛 워치에는 다시 내지 않음, 응답 번호는 늘 보고 주인일 때만 넣음(계약 §4.3)
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchAlertStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWatchSyncTest {
    /** 새 워치 값 — `alerting` 키를 싣는다(울리지 않으면 null). */
    private fun newWatch(verdict: WristVerdict, alerting: WatchAlertStatus? = null, seq: Long? = null): Watch =
        watch(verdict).copy(alertingReported = true, alerting = alerting, responseSeq = seq)

    private fun Rig.sent(type: CommandType) = lastRequest!!.commands.filter { it.type == type && it.resolvedTo == CommandTo.WATCH }

    /** 주행 중 경보(S1, 진동 단계)까지. 사건 id 를 돌려준다. */
    private fun Rig.toAlert(): String {
        arm(MOVING)
        hub.onReading(MOVING, 2_000)
        sync(2_000, newWatch(WristVerdict.PULSE_WEAK))
        assertEquals(FlowState.S1, hub.snapshot.state)
        return hub.snapshot.facts.eventId!!
    }

    @Test fun ownerResendsStartWhenWatchIsSilentEvery10s() {
        val r = Rig()
        r.toAlert()
        r.sync(4_000, newWatch(WristVerdict.PULSE_WEAK)) // 첫 시작 지시가 실려 나간다
        val starts = ArrayList<Int>()
        // 2단계(소리)는 경보 시작 + 15초(17,100)에 상태 기계가 따로 낸다 — 그 전 구간만 본다
        for (t in 6_000L..16_000L step 2_000) {
            r.hub.onReading(MOVING, t)
            r.sync(t, newWatch(WristVerdict.PULSE_WEAK, alerting = null))
            starts += r.sent(CommandType.ALERT_START).size
        }
        val total = starts.sum()
        assertTrue("워치가 안 울리면 시작을 다시 낸다 $starts", total >= 1)
        assertTrue("10초에 한 번 — 10초 구간에 많아야 두 번 $starts", total <= 2)
    }

    @Test fun ownerStopsWatchRingingOutsideAlert() {
        val r = Rig()
        r.arm(MOVING)
        for (t in 2_000L..24_000L step 2_000) {
            r.hub.onReading(MOVING, t)
            r.sync(t, newWatch(WristVerdict.NORMAL, alerting = WatchAlertStatus("event-old", FlowState.S1, 1)))
        }
        // 정지는 대상 ALL(워치 · 휴대폰 알람) — 계약 §4.6
        val stops = r.sentCommands.filter { it.type == CommandType.ALERT_STOP && it.resolvedTo in setOf(CommandTo.WATCH, CommandTo.ALL) }
        assertTrue("모르는 사건이 울리면 10초 지켜본 뒤 그 사건 id 로 정지", stops.isNotEmpty())
        assertTrue(stops.all { it.payload["eventId"] == "event-old" })
    }

    @Test fun oldWatchWithoutAlertingKeyIsNotResent() {
        val r = Rig()
        r.arm(MOVING)
        r.hub.onReading(MOVING, 2_000)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        r.sync(4_000, watch(WristVerdict.PULSE_WEAK))
        val before = r.sentCommands.count { it.type == CommandType.ALERT_START }
        for (t in 6_000L..16_000L step 2_000) { r.hub.onReading(MOVING, t); r.sync(t, watch(WristVerdict.PULSE_WEAK)) } // 2단계 전
        assertEquals("옛 워치에는 다시 내지 않는다", before, r.sentCommands.count { it.type == CommandType.ALERT_START })
    }

    /** 10초 넘게 끊겼다 다시 붙은 첫 응답은 주인이 아니던 때의 값이다 — 경보 맞추기를 하지 않는다(다음 요청에 다시 낸 시작이 없어야 한다). */
    @Test fun notOwnerDoesNotResend() {
        val r = Rig()
        r.toAlert()
        for (t in 4_000L..16_000L step 2_000) { r.hub.onReading(MOVING, t); r.hub.onSyncFailure(t) }
        r.hub.onReading(MOVING, 18_000)
        r.sync(18_000, newWatch(WristVerdict.PULSE_WEAK, alerting = null))
        val next = r.hub.buildRequest("tok", 900, 0, 18_500)
        assertTrue("다시 붙은 첫 응답에서는 다시 내지 않는다 ${next.commands.map { it.type }}", next.commands.none { it.type == CommandType.ALERT_START })
    }

    /**
     * 독립 검토 중 — 차가 10초 넘게 끊긴 동안 앞 사건에 누른 번호가, 다시 붙은 첫 응답에서 지금 울리는 다른 사건(휴대폰이 쥔 사건)의
     * 응답으로 들어가 경보를 끄면 안 된다. 다시 붙은 첫 값은 기준만 옮긴다.
     */
    @Test fun pressSeenOnlyAfterReconnectDoesNotAnswerAnotherIncident() {
        val r = Rig()
        r.arm(MOVING)
        r.hub.onReading(MOVING, 2_000)
        r.sync(2_000, newWatch(WristVerdict.PULSE_WEAK, seq = 0))
        assertEquals(FlowState.S1, r.hub.snapshot.state)
        val mine = r.hub.snapshot.facts.eventId
        for (t in 4_000L..16_000L step 2_000) { r.hub.onReading(MOVING, t); r.hub.onSyncFailure(t) }
        // 끊긴 동안 휴대폰이 주인이 되어 앞 사건을 응답으로 끝내고, 그 뒤 새 사건(다른 id)이 울리는 중
        val phone = com.example.guardian.shared.flow.FlowMachine().apply {
            handle(com.example.guardian.shared.flow.FlowInput.ClockSync(0, 0))
            handle(com.example.guardian.shared.flow.FlowInput.Vehicle(com.example.guardian.shared.flow.VehicleState(60.0, com.example.guardian.shared.flow.Gear.D, false), 0))
            handle(com.example.guardian.shared.flow.FlowInput.ConsentsChanged(com.example.guardian.shared.report.Consents(true, true, true), 0))
            handle(com.example.guardian.shared.flow.FlowInput.Power(true, 0))
            handle(com.example.guardian.shared.flow.FlowInput.Wrist(WristVerdict.NORMAL, 0))
            handle(com.example.guardian.shared.flow.FlowInput.Wrist(WristVerdict.PULSE_WEAK, 15_000))
        }
        val phoneFlow = com.example.guardian.shared.protocol.FlowStatus.forHandoff(phone.snapshot(), com.example.guardian.shared.flow.Owner.PHONE, 0)
        assertTrue(phoneFlow.snapshot.facts.eventId != mine)
        r.hub.onReading(MOVING, 18_000)
        // 워치 번호는 끊긴 동안 앞 사건에 한 번 눌러 1 이 됐다
        assertTrue(r.sync(18_000, newWatch(WristVerdict.PULSE_WEAK, seq = 1), peerFlow = phoneFlow).adopted)
        assertEquals("휴대폰이 쥔 새 사건의 경보는 꺼지지 않는다", FlowState.S1, r.hub.snapshot.state)
        // 다시 붙은 뒤 정상 길의 누름은 응답
        r.hub.onReading(MOVING, 20_000)
        r.sync(20_000, newWatch(WristVerdict.PULSE_WEAK, seq = 2))
        assertTrue("다시 붙은 뒤 누름은 응답", r.hub.snapshot.state == FlowState.S1R || r.hub.snapshot.state == FlowState.S2)
    }

    /** 번호가 커지면 응답 — 워치 벽시계(responseAt)와 무관(앱2-하6). */
    @Test fun responseSeqIncreaseIsAnswerEvenIfWallClockWentBack() {
        val r = Rig()
        r.arm(PARKED)
        r.hub.onReading(PARKED, 2_000)
        r.sync(2_000, newWatch(WristVerdict.PULSE_WEAK, seq = 3).copy(responseAt = 900_000))
        assertTrue(r.hub.snapshot.state != FlowState.S0)
        r.hub.onReading(PARKED, 4_000)
        r.sync(4_000, newWatch(WristVerdict.PULSE_WEAK, seq = 4).copy(responseAt = 100))
        assertTrue("번호 4 > 3 — 응답", r.hub.snapshot.state == FlowState.S1R || r.hub.snapshot.state == FlowState.S2)
    }

    /** be-dev c614bd18 — 주인이 아닐 때 받은 누름(번호 +1)은 기준만 옮기고 넣지 않는다. 다음 내 경보에서 거짓 응답이 되지 않는다. */
    @Test fun pressSeenWhileNotOwnerIsNotAnAnswerLater() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, newWatch(WristVerdict.NORMAL, seq = 1))
        for (t in 4_000L..16_000L step 2_000) { r.hub.onReading(MOVING, t); r.hub.onSyncFailure(t) }
        assertTrue(!r.hub.carIsOwner(16_000))
        // 끊긴 동안 휴대폰이 처리한 누름으로 번호가 2 가 됐다 — 다시 붙은 응답에서 감시 중(S0)이라 응답이 될 경보가 없다
        r.hub.onReading(MOVING, 18_000)
        r.sync(18_000, newWatch(WristVerdict.NORMAL, seq = 2))
        r.hub.onReading(MOVING, 20_000)
        r.sync(20_000, newWatch(WristVerdict.PULSE_WEAK, seq = 2))
        assertEquals("같은 번호로 새 경보가 꺼지지 않는다", FlowState.S1, r.hub.snapshot.state)
    }
}
