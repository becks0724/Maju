// 「에어백 사고는 늘 실제」(계약 §4.6) 휴대폰 시험 — 테스트 도중 실제 사고 지시가 오면 테스트를 끝내고 실제 사고로 보인다
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.DirectiveCommands
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccidentDuringTestTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private var carIds = 0
    private var relay = true
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "p${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { relay })

    /** 차가 돌리는 상태 기계 — 시험에서는 차 단조 시계 = 휴대폰 단조 시계, 보정값 고정. */
    private val car = FlowMachine()
    private val parked = VehicleState(0.0, Gear.P, true)

    private fun carHandle(i: FlowInput): List<Command> = car.handle(i).flatMap { d: Directive ->
        DirectiveCommands.toCommands(d, Party.CAR, { "car${carIds++}" }, 30_000, serverOffset)
    }.filter { it.resolvedTo == CommandTo.PHONE || it.resolvedTo == CommandTo.ALL }

    private fun carFlow() = FlowStatus.forSync(car.snapshot(), Owner.CAR, serverOffset)

    private fun peer(ageMs: Long = 0, flow: FlowStatus? = carFlow()) = CarPeer(
        seq = 1, sentAt = wall0, receivedAt = mono + serverOffset - ageMs,
        vehicle = Vehicle(speedKmh = 0.0, gear = Gear.P, parkingBrake = true), flow = flow,
    )

    private fun sync(p: CarPeer?, commands: List<Command> = emptyList()) {
        engine.buildSync("tok", mono)
        // 워치는 중계에 직접 붙어 있다(워치 테스트를 시작할 수 있게, 계약 §4.3)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, p, null, commands, watchDirectAgeMs = 0), mono, mono)
    }

    private fun watchNormal() = engine.onDataLayerWatch(
        Watch(WristVerdict.NORMAL, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = null), mono,
    )

    private fun watchSim() = engine.onDataLayerWatch(
        Watch(WristVerdict.NORMAL, 70, wall0, worn = true, source = WatchSource.SIMULATION, test = true, responseAt = null), mono,
    )

    private fun armCar(test: Boolean) {
        carHandle(FlowInput.ClockSync(serverOffset, mono))
        carHandle(FlowInput.Power(true, mono))
        carHandle(FlowInput.ConsentsChanged(Consents(true, true, true), mono))
        carHandle(FlowInput.Vehicle(parked, mono))
        if (test) carHandle(FlowInput.TestMode(true, mono))
        carHandle(FlowInput.Wrist(WristVerdict.NORMAL, mono))
    }

    /** 차가 주인, 휴대폰 시험(워치) 시작까지. */
    private fun carOwnerWithWatchTest() {
        watchNormal()
        sync(peer(flow = null))
        engine.tick(mono)
        assertEquals(Owner.CAR, engine.owner)
        assertEquals(TestStart.WATCH, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
        watchSim() // 워치가 시나리오를 받아 시뮬레이션 값(test=true)을 낸다 — 이때부터 테스트로 다룬다(C-1)
        assertTrue(engine.buildSync("tok", mono).flow!!.test) // 차가 휴대폰 설정으로 테스트 모드를 켠다
        armCar(test = true)
    }

    private fun accidentSms() = fx.sms.filter { it.first == GUARDIAN }

    /** 차가 주인 — 테스트 사건(P11T · P17 · P18) 도중 에어백. 실제 사고 문자는 보내고 테스트 흔적은 거둔다. */
    @Test fun carTestIncidentThenAirbagEndsPhoneTestAndSendsRealAccidentSms() {
        carOwnerWithWatchTest()
        // 차의 테스트 사건 — 정차 중 맥박 없음 → AP → S4(테스트 문자 확인 · 119 미리보기)
        repeat(60) {
            mono += 1_000
            watchSim()
            val cmds = carHandle(FlowInput.Wrist(WristVerdict.PULSE_ABSENT, mono)) + carHandle(FlowInput.Tick(mono))
            sync(peer(), cmds)
            engine.tick(mono)
        }
        assertEquals(FlowState.S4, car.state)
        assertTrue(engine.status(mono).flowTest)
        assertEquals(1, engine.executor.pending().size) // P17 테스트 문자 확인 대기
        assertTrue(engine.status(mono).has119) // P18 미리보기 다시 열기
        assertTrue(fx.previews.isNotEmpty())
        assertTrue(fx.sms.isEmpty())

        mono += 1_000
        val accident = carHandle(FlowInput.Vehicle(parked.copy(airbagDeployed = true), mono))
        assertTrue(accident.any { it.type == CommandType.ALERT_STOP && it.test })
        val stops = fx.alarmStops
        sync(peer(), accident)
        engine.tick(mono)

        val st = engine.status(mono)
        assertNull(engine.test)
        assertNull(st.test)
        assertFalse(st.flowTest) // 테스트 띠 없음
        assertEquals(FlowState.S5, st.shownState)
        assertFalse(st.has119) // 테스트 119 미리보기를 다시 열 수 없다
        assertTrue(engine.executor.pending().isEmpty()) // P17 을 거둔다
        assertEquals(1, fx.testScreensCleared)
        assertTrue(fx.alarmStops > stops)
        // 실제 사고 문자 한 통(테스트 표시 없음), 테스트 사건의 취소 문자는 없다
        assertEquals(1, accidentSms().size)
        assertFalse(accidentSms()[0].second.startsWith("[테스트]"))
        assertTrue(store.sent.any { it.startsWith("ACCIDENT:accident-") })
        assertFalse(store.sent.any { it.startsWith("CANCEL:") })
        assertFalse(Notice.SMS_SKIPPED_UNMARKED_TEST in fx.notices)
        assertTrue(fx.composed.isEmpty()) // 119 작성 화면은 열지 않는다
        // 차에 보내는 설정도 테스트가 아니고, 워치 시나리오는 TEST_SCENARIO_STOP 으로 멈춘다(계약 §4.6)
        val req = engine.buildSync("tok", mono)
        assertFalse(req.flow!!.test)
        val stop = req.commands.single { it.type == CommandType.TEST_SCENARIO_STOP }
        assertEquals(CommandTo.WATCH, stop.to)
        assertTrue(stop.test)
        // 사고 중에는 새 시험을 시작할 수 없다
        assertEquals(TestStart.BUSY, engine.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = mono))
        // 나중에 같은 지시가 다시 와도 두 번 보내지 않는다
        sync(peer(), accident)
        assertEquals(1, accidentSms().size)
    }

    /** 휴대폰 단독 시험(LocalTestRun)이 울리는 중 — 차의 실제 사고 지시만 와도(peer flow 없음) 시험을 멈추고 알람을 끈다. */
    @Test fun localTestRunStopsWhenRealAccidentCommandArrives() {
        sync(peer(flow = null))
        engine.tick(mono)
        assertEquals(TestStart.LOCAL, engine.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = mono))
        var guard = 0
        while (engine.status(mono).testFlowState !in PhoneEngine.ALARM_STATES && guard++ < 120) {
            mono += 1_000
            sync(peer(flow = null))
            engine.tick(mono)
        }
        assertTrue(engine.status(mono).testFlowState in PhoneEngine.ALARM_STATES)
        assertTrue(engine.status(mono).flowTest)

        armCar(test = false)
        mono += 1_000
        val accident = carHandle(FlowInput.Vehicle(parked.copy(airbagDeployed = true), mono))
        val stops = fx.alarmStops
        sync(peer(flow = null), accident)
        assertNull(engine.test)
        assertTrue(fx.alarmStops > stops)
        assertEquals(1, accidentSms().size)
        // 다음 주기에도 시험 상태 기계가 되살아나지 않는다
        mono += 2_000
        sync(peer(flow = null))
        engine.tick(mono)
        val st = engine.status(mono)
        assertNull(st.testFlowState)
        assertNull(st.test)
        assertFalse(st.flowTest)
    }

    /** 이미 실행한 실제 사고 문자 지시가 다시 와도(「한 번 이상」 전달) 그 뒤에 시작한 새 테스트를 끝내지 않는다(app-qa 참고). */
    @Test fun redeliveredAccidentSmsDoesNotEndLaterTest() {
        sync(peer(flow = null))
        engine.tick(mono)
        val p = Payload.SendGuardianSms(
            com.example.guardian.shared.protocol.GuardianSmsWireKind.ACCIDENT, "accident-L1",
            com.example.guardian.shared.protocol.WireFacts(com.example.guardian.shared.protocol.SnapshotClock.LOCAL, accidentAt = mono), test = false,
        )
        val c = Command("acc-1", p.type, p.toJson(), 30_000, false, Party.CAR, CommandTo.PHONE)
        sync(peer(flow = null), listOf(c))
        assertEquals(1, accidentSms().size)
        assertEquals(TestStart.LOCAL, engine.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = mono))
        mono += 1_000
        sync(peer(flow = null), listOf(c))
        assertTrue("이미 실행한 지시는 테스트를 끝내지 않는다", engine.test != null)
        assertEquals(0, fx.testScreensCleared)
        assertEquals(1, accidentSms().size)
    }

    /** 휴대폰이 주인이 되는 경우 — 차가 사고(S5)를 FLOW_SNAPSHOT 으로 넘기고 끊긴다. 넘겨받은 S5 에서 테스트를 끝낸다. */
    /** 허용 표 밖 — 휴대폰으로 온 FLOW_SNAPSHOT 지시는 넘겨받기 후보로 쓰지 않고 ack 만 한다. */
    @Test fun incomingFlowSnapshotIsIgnoredAndAcked() {
        watchNormal()
        sync(peer(flow = null))
        engine.tick(mono)
        mono += 1_000
        carHandle(FlowInput.ClockSync(serverOffset, mono)); carHandle(FlowInput.Power(true, mono))
        carHandle(FlowInput.ConsentsChanged(Consents(true, true, true), mono)); carHandle(FlowInput.Vehicle(parked.copy(airbagDeployed = true), mono))
        val handoff = Payload.FlowSnapshot(FlowStatus.forHandoff(car.snapshot(), Owner.CAR, serverOffset))
        sync(peer(ageMs = 20_000, flow = null), listOf(Command("snap-x", handoff.type, handoff.toJson(), 30_000, false, Party.CAR, CommandTo.PHONE)))
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        assertTrue("넘겨받지 않음", engine.status(mono).shownState != FlowState.S5)
        assertTrue(engine.buildSync("tok", mono).acks.contains("snap-x"))
    }

    @Test fun takeOverOfRealAccidentEndsWatchTest() {
        carOwnerWithWatchTest()
        mono += 1_000
        carHandle(FlowInput.Vehicle(parked.copy(airbagDeployed = true), mono))
        assertEquals(FlowState.S5, car.state)
        // 차의 마지막 sync 는 20초 전 — 그때 실린 S5 흐름(peer.flow)을 넘겨받는다. 휴대폰으로 오는 FLOW_SNAPSHOT 지시는 허용 표에 없어 받지 않는다
        sync(peer(ageMs = 20_000, flow = FlowStatus.forHandoff(car.snapshot(), Owner.CAR, serverOffset)))
        assertTrue(engine.test != null)
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        val st = engine.status(mono)
        assertEquals(FlowState.S5, st.shownState)
        assertNull(st.test)
        assertFalse(st.flowTest)
        val flow = engine.buildSync("tok", mono).flow!!
        assertEquals(Owner.PHONE, flow.owner)
        assertFalse(flow.test)
        // 주인이 된 뒤 주기를 더 돌려도 테스트 표시가 다시 켜지지 않는다
        repeat(5) { mono += 2_000; engine.onSyncError(); engine.onLocation(Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "주소"), mono); engine.tick(mono) }
        assertFalse(engine.status(mono).flowTest)
        assertEquals(FlowState.S5, engine.status(mono).shownState)
    }
}
