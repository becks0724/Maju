// 차 → 휴대폰 이어받기에서 차가 켠 휴대폰 알람이 멈춤 없이 남는 경로 시험 — 경보가 살아 있으면 오래된 flow 도 이어 가고, 이어 갈 것이 없으면 경보 화면 「괜찮아요」로 끈다(사용자 결정 2026-11-08)
package com.example.guardian.phone.core

import com.example.guardian.phone.ui.ResultKind
import com.example.guardian.phone.ui.UiRules
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.protocol.AlertStopReason
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrandedAlarmTakeOverTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L

    private val engine = PhoneEngine(
        store = store, effects = fx, newId = { "c${ids++}" }, wallNow = { wall0 + (mono - mono0) },
        seqStartEpochMs = wall0, startMono = mono0, relayActive = { true },
    )

    /** 차가 돌리던 상태 기계 — 정차 중 맥박 없음 → AP → 경보 2 → S4. [test] 면 테스트 사건. */
    private fun carS4(test: Boolean = false): FlowStatus {
        val car = FlowMachine()
        var t = 50_000L
        car.handle(FlowInput.ClockSync(serverOffset + mono - t, t))
        car.handle(FlowInput.Power(true, t))
        car.handle(FlowInput.ConsentsChanged(Consents(true, true, true), t))
        if (test) car.handle(FlowInput.TestMode(true, t))
        repeat(10) { t += 1_000; car.handle(FlowInput.Vehicle(VehicleState(0.0, Gear.P, true), t)) }
        car.handle(FlowInput.Wrist(WristVerdict.NORMAL, t))
        repeat(60) { t += 1_000; car.handle(FlowInput.Wrist(WristVerdict.PULSE_ABSENT, t)); car.handle(FlowInput.Tick(t)) }
        assertEquals(FlowState.S4, car.state)
        assertTrue(car.snapshot().alerting)
        return FlowStatus.forHandoff(car.snapshot(), Owner.CAR, serverOffset + mono - t)
    }

    private fun alertStart(test: Boolean = false) = Command(
        "start-${ids++}", com.example.guardian.shared.protocol.CommandType.ALERT_START,
        Payload.AlertStart(FlowState.AP, 2, vibrate = true, sound = true, maxVolume = true, durationMs = 20_000).toJson(),
        30_000, test, Party.CAR, CommandTo.ALL,
    )

    private fun alertStop() = Command(
        "stop-${ids++}", com.example.guardian.shared.protocol.CommandType.ALERT_STOP,
        Payload.AlertStop(AlertStopReason.CLEARED).toJson(), 30_000, false, Party.CAR, CommandTo.ALL,
    )

    private fun carSync(flow: FlowStatus?, commands: List<Command> = emptyList()) {
        engine.buildSync("tok", mono)
        val peer = CarPeer(1, wall0, mono + serverOffset, Vehicle(speedKmh = 0.0, gear = Gear.P), flow)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, commands), mono, mono)
        engine.tick(mono)
        assertEquals(Owner.CAR, engine.owner)
    }

    private fun loc(speed: Double) = Loc(37.57590, 126.97690, 8.0, speed, wall0, "서울 종로구 세종대로 172")

    /** 차 flow 를 [gapMs] 동안 받지 못하다(휴대폰이 잠들어 틱도 없음) 이어받는다. */
    private fun takeOverAfter(gapMs: Long, speed: Double = 0.0) {
        mono += gapMs
        engine.onLocation(loc(speed), mono)
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
    }

    /** 경보 화면 판단을 AlarmActivity 처럼 거친다 — 경보로 시작해 지금 status 를 넣는다. */
    private fun screenCloses(): Boolean {
        val s = engine.status(mono)
        val held = UiRules.heldState(FlowState.S4, s.shownState)
        val view = UiRules.alarmView(held, s.strandedAlarm, s.stopped)
        if (view in PhoneEngine.ALARM_STATES) return false
        val responded = UiRules.alarmResponded(UiRules.alarmResponded(false, FlowState.S4), view) || s.alarmAnswered
        return UiRules.resultKind(view, responded) == ResultKind.CLOSE
    }

    /** (가) 차 주인 S4 경보 중 휴대폰 알람 켜짐 → 차 flow 를 61초 넘게 못 받은 뒤 이어받기 → 같은 사건을 이어 가고 경보 화면을 둔다. */
    @Test fun ringingAlarmContinuesOldCarFlow() {
        val flow = carS4()
        carSync(flow, listOf(alertStart()))
        assertEquals(listOf("AP"), fx.alarms)
        takeOverAfter(61_000)
        val s = engine.status(mono)
        assertTrue("사건을 이어 간다", s.flowState in PhoneEngine.ALARM_STATES)
        assertEquals("같은 사건", flow.snapshot.facts.eventId, s.eventId)
        assertEquals("알람을 끄지 않는다", 0, fx.alarmStops)
        assertFalse("경보 화면을 닫지 않는다", screenCloses())
    }

    /**
     * app-qa 조건(2차 1) — 시동을 끈 뒤 차 앱이 사라지고 주차장에서 GPS 가 비어도, 넘겨받은 정차 흐름은 GPS 가 실제 움직임을 보일 때까지 정차다.
     * 「괜찮아요」와 119 작성 화면이 5초 뒤 막히지 않는다.
     */
    @Test fun takenOverStoppedFlowStaysStoppedWithoutGps() {
        carSync(carS4(), listOf(alertStart()))
        mono += 11_000
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        repeat(90) { mono += 1_000; engine.onSyncError(mono); engine.tick(mono) } // GPS 없음 90초(복원 유예 60초보다 길게)
        var s = engine.status(mono)
        assertEquals(FlowState.S4, s.flowState)
        assertTrue("GPS 가 없는 동안 정차 유지", s.stopped)
        assertTrue("「괜찮아요」를 누를 수 있다", s.canRespond)
        // GPS 가 실제 움직임을 보이면 그때부터 주행으로 본다
        repeat(10) { mono += 1_000; engine.onLocation(loc(40.0), mono); engine.tick(mono) }
        s = engine.status(mono)
        assertFalse(s.stopped)
    }

    /**
     * H3(결정 24 · B-4) — 넘겨받은 정지는 GPS 가 실제 움직임(주행 속도)을 보일 때까지 유지한다. 정지 근처 속도(0.3)가 한 번 와도
     * 풀리지 않고, 그 뒤 GPS 가 90초 없어도 S4 그대로다(지하 주차장 출입구). 주행 속도가 오면 그때 풀린다.
     */
    @Test fun takenOverStop_slowGpsOnceThenNone_staysStopped() {
        carSync(carS4(), listOf(alertStart()))
        mono += 11_000
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        mono += 1_000; engine.onLocation(loc(0.3), mono); engine.tick(mono)
        repeat(90) { mono += 1_000; engine.onSyncError(mono); engine.tick(mono) }
        var s = engine.status(mono)
        assertEquals("정지 근처 속도 한 번으로는 풀리지 않는다", FlowState.S4, s.flowState)
        assertTrue(s.stopped)
        repeat(10) { mono += 1_000; engine.onLocation(loc(40.0), mono); engine.tick(mono) }
        s = engine.status(mono)
        assertFalse("주행 속도가 오면 풀린다", s.stopped)
    }

    /** (나) flow 없이 알람만 켜진 이어받기 — 경보 화면을 두고, 정차 중 「괜찮아요」로 알람을 끄고 응답으로 남긴다. 주행 중에는 버튼이 없다(G5). */
    @Test fun alarmWithoutFlowKeepsScreenAndOkStopsIt() {
        carSync(null, listOf(alertStart()))
        takeOverAfter(11_000, speed = 60.0)
        var s = engine.status(mono)
        assertTrue("상태 기계는 새로 시작", s.flowState == FlowState.OFF || s.flowState == FlowState.S0)
        assertTrue(s.strandedAlarm)
        assertEquals("주행 중이면 버튼 없는 주행 경보 화면", FlowState.S1, UiRules.alarmView(s.shownState, s.strandedAlarm, s.stopped))
        assertFalse("남은 알람은 차 버튼으로 꺼지지 않는다 — 「차 버튼으로도」 안내를 숨긴다", UiRules.showCarButtonHint(s.strandedAlarm))
        assertTrue("정상 주행 경보 화면은 그대로", UiRules.showCarButtonHint(false))
        assertFalse(screenCloses())
        engine.phoneResponse(mono)
        assertEquals("주행 중 휴대폰 응답은 받지 않는다", 0, fx.alarmStops)

        repeat(8) { mono += 1_000; engine.onLocation(loc(0.0), mono); engine.tick(mono) }
        s = engine.status(mono)
        assertTrue(s.stopped)
        assertEquals("정차 중이면 「괜찮아요」 화면", FlowState.AP, UiRules.alarmView(s.shownState, s.strandedAlarm, s.stopped))
        engine.phoneResponse(mono)
        assertEquals("알람을 끈다", 1, fx.alarmStops)
        s = engine.status(mono)
        assertFalse(s.strandedAlarm)
        assertTrue("응답으로 남긴다", s.alarmAnswered)
        assertEquals(ResultKind.RESPONDED, UiRules.resultKind(s.shownState, UiRules.alarmResponded(false, s.shownState) || s.alarmAnswered))
    }

    /** (나) 알람만 남았을 때 워치 버튼 응답도 알람을 끈다 — 주행 중에는 워치 버튼이 유일한 응답 수단이다. */
    @Test fun watchResponseStopsStrandedAlarm() {
        carSync(null, listOf(alertStart()))
        takeOverAfter(11_000, speed = 60.0)
        val w = { r: Long? ->
            com.example.guardian.shared.protocol.Watch(WristVerdict.NORMAL, 70, wall0, worn = true,
                source = com.example.guardian.shared.protocol.WatchSource.SENSOR_SDK, test = false, responseAt = r)
        }
        engine.onDataLayerWatch(w(wall0), mono); engine.tick(mono)
        mono += 2_000
        engine.onDataLayerWatch(w(wall0 + 5_000), mono); engine.tick(mono)
        assertEquals(1, fx.alarmStops)
        assertTrue(engine.status(mono).alarmAnswered)
        assertFalse(engine.status(mono).strandedAlarm)
    }

    /** (다) 알람이 꺼진 상태의 60초 넘은 flow 는 지금처럼 버린다 — 사람이 소리를 줄인 사건이라 되살리기(C-5)도 켜지 않는다. */
    @Test fun oldFlowWithoutAlarmIsDropped() {
        val flow = carS4()
        store.mutedEventId = flow.snapshot.facts.eventId
        carSync(flow)
        takeOverAfter(61_000)
        val s = engine.status(mono)
        assertTrue(s.flowState == FlowState.OFF || s.flowState == FlowState.S0)
        assertFalse(s.strandedAlarm)
    }

    /** (다) ALERT_STOP 으로 꺼진 알람도 울리는 중이 아니다. */
    @Test fun stoppedAlarmDoesNotKeepOldFlow() {
        val flow = carS4()
        carSync(flow, listOf(alertStart()))
        carSync(flow, listOf(alertStop()))
        takeOverAfter(61_000)
        assertTrue(engine.status(mono).flowState !in PhoneEngine.ALARM_STATES)
        assertFalse(engine.status(mono).strandedAlarm)
    }

    /** 차가 돌린 상태 기계의 비사건(S0) flow — [owner] 는 차가 그 flow 를 실을 때의 주인 표시. */
    private fun carS0(owner: Owner): FlowStatus {
        val car = FlowMachine()
        var t = 50_000L
        car.handle(FlowInput.ClockSync(serverOffset + mono - t, t))
        car.handle(FlowInput.Power(true, t))
        car.handle(FlowInput.ConsentsChanged(Consents(true, true, true), t))
        repeat(10) { t += 1_000; car.handle(FlowInput.Vehicle(VehicleState(0.0, Gear.P, true), t)) }
        car.handle(FlowInput.Wrist(WristVerdict.NORMAL, t))
        assertEquals(FlowState.S0, car.state)
        return FlowStatus.forHandoff(car.snapshot(), owner, serverOffset + mono - t)
    }

    /**
     * app-qa 재현 — 휴대폰이 S4 를 이어받아 알람이 울리는 중 차가 돌아와 첫 요청에 자기 옛 S0(owner=PHONE, 아직 넘겨받지 않음)을 싣는다.
     * A-4 — 휴대폰은 미해결 사건을 쥐고(주인 그대로) 「알람만 남음」을 만들지 않는다. 차가 같은 사건 id 를 owner CAR 로 실으면 그때 넘긴다.
     */
    @Test fun carReturnWithOldPhoneOwnedFlowIsNotStranded() {
        val flow = carS4()
        carSync(flow, listOf(alertStart()))
        takeOverAfter(61_000)
        assertTrue(engine.status(mono).flowState in PhoneEngine.ALARM_STATES)
        mono += 2_000
        carSyncAny(carS0(Owner.PHONE))
        var s = engine.status(mono)
        assertEquals("차가 넘겨받기 전에는 휴대폰이 주인", Owner.PHONE, engine.owner)
        assertFalse("차가 아직 넘겨받지 않은 옛 flow 로 「알람만 남음」을 만들지 않는다", s.strandedAlarm)
        assertEquals(0, fx.alarmStops)
        // 다음 주기에 차가 넘겨받은 S4(같은 사건 id) — 경보 화면 그대로, 알람도 그대로
        mono += 2_000
        carSync(flow)
        s = engine.status(mono)
        assertEquals(FlowState.S4, s.shownState)
        assertFalse(s.strandedAlarm)
        assertEquals(0, fx.alarmStops)
    }

    /**
     * 차가 주인인 flow(owner=CAR)는 그대로 주인 상태 — 경보 끝 flow 에 알람만 남으면 끌 수단을 남긴다(정상 차 주인 경로는 바뀌지 않음).
     * 경보 끝은 같은 사건 id 를 가진 비경보 흐름이다 — id 가 사라진 S0 은 차 재시작이라 휴대폰이 넘겨받는다(A-4, OwnerLeaseTest).
     */
    @Test fun carOwnedNonAlarmFlowStillStranded() {
        val s4 = carS4()
        carSync(s4, listOf(alertStart()))
        assertEquals(FlowState.S4, engine.status(mono).shownState)
        assertFalse(engine.status(mono).strandedAlarm)
        mono += 2_000
        val ended = FlowStatus.forHandoff(s4.snapshot.copy(state = FlowState.OFF, alerting = false), Owner.CAR, 0)
        carSync(ended)
        assertTrue(engine.status(mono).strandedAlarm)
        carSync(ended, listOf(alertStop()))
        assertFalse(engine.status(mono).strandedAlarm)
    }

    /** 주인 단정 없이 차 sync 한 번(차 흐름이 owner PHONE 일 때). */
    private fun carSyncAny(flow: FlowStatus?) {
        engine.buildSync("tok", mono)
        val peer = CarPeer(1, wall0, mono + serverOffset, Vehicle(speedKmh = 0.0, gear = Gear.P), flow)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, emptyList()), mono, mono)
        engine.tick(mono)
    }

    /** 테스트 흐름 · 테스트 알람은 지금 규칙 그대로(60초) — 실제 흐름만 해당한다. */
    @Test fun testAlarmKeepsOldRule() {
        carSync(carS4(test = true), listOf(alertStart(test = true)))
        takeOverAfter(61_000)
        val s = engine.status(mono)
        assertTrue(s.flowState !in PhoneEngine.ALARM_STATES)
        assertFalse(s.strandedAlarm)
    }
}
