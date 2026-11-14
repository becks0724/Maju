// 차 화면 준비 시험 — 차 → 휴대폰 TEST_REQUEST(홈 테스트 시작 · 멈추기와 같은 길 · 같은 검사), carDisplay 싣기, 동의 문구 판 올림(2026-11-01)
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CarDisplay
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.bio.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarScreenTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { true })

    private fun peer(speed: Double = 0.0, gear: Gear = Gear.P, ageMs: Long = 0, flow: FlowStatus? = null) = CarPeer(
        seq = 1, sentAt = wall0, receivedAt = mono + serverOffset - ageMs,
        vehicle = Vehicle(speedKmh = speed, gear = gear, make = "현대", model = "아반떼", year = 2027), flow = flow,
    )

    private fun request(id: String, p: Payload, from: Party = Party.CAR) =
        Command(id, CommandType.TEST_REQUEST, p.toJson(), 30_000, true, from, CommandTo.PHONE)

    /** 지금까지 sync 요청에 실어 보낸 ack. */
    private val sentAcks = ArrayList<String>()

    /** 한 번 sync — 차 peer 와 받은 지시. 워치 직접 경로 나이를 주면 워치에 지시가 닿는다. */
    private fun sync(peer: CarPeer?, commands: List<Command> = emptyList(), directAge: Long? = null) {
        sentAcks += engine.buildSync("tok", mono).acks
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, commands, watchDirectAgeMs = directAge), mono, mono)
        engine.tick(mono)
    }

    private fun next() = engine.buildSync("tok", mono)

    // ── TEST_REQUEST ──

    /**
     * 차 정차 + 워치 직접 연결 없음 → 휴대폰 단독 + 안내(홈과 같음), ack. 휴대폰 안 시험은 차에 테스트로 알리지 않는다 —
     * 시뮬레이션이 휴대폰 밖으로 나가지 않아 차는 실제 워치 값을 계속 실제로 다뤄야 한다(2026-11-07, 계약 §4.6 「차에서 테스트 요청」).
     */
    @Test fun carStartWhileParkedRunsLikeHomeStart() {
        sync(peer())
        assertEquals(Owner.CAR, engine.owner)
        sync(peer(), listOf(request("tr-1", Payload.TestRequest.START)))
        assertEquals(TestMode.LOCAL, engine.test?.mode)
        assertEquals(Scenario.PULSE_LOST, engine.test?.scenario)
        assertEquals(listOf(Notice.TEST_PHONE_ONLY_NO_DIRECT_WATCH), fx.notices)
        val req = next()
        assertTrue(req.acks.contains("tr-1"))
        assertFalse("휴대폰 안 시험은 차가 보는 flow.test 를 켜지 않는다", req.flow!!.test)
        assertEquals(Owner.CAR, req.flow!!.owner)
    }

    /** 워치 직접 경로가 있으면 워치 시나리오(TEST_SCENARIO to WATCH). 멈춤 요청은 테스트 멈추기와 같다(TEST_SCENARIO_STOP). */
    @Test fun carStartWithDirectWatchThenStop() {
        engine.onDataLayerWatch(Watch(WristVerdict.NORMAL, 70, wall0, true, WatchSource.SENSOR_SDK, false, null), mono)
        sync(peer(), directAge = 0)
        sync(peer(), listOf(request("tr-1", Payload.TestRequest.START)), directAge = 0)
        assertEquals(TestMode.WATCH, engine.test?.mode)
        val out = next()
        val scenario = out.commands.single { it.type == CommandType.TEST_SCENARIO }
        assertEquals(CommandTo.WATCH, scenario.to)
        assertTrue(fx.notices.isEmpty())
        sync(peer(), listOf(request("tr-2", Payload.TestRequest.STOP)), directAge = 0)
        assertNull(engine.test)
        val after = next()
        assertTrue(after.commands.any { it.type == CommandType.TEST_SCENARIO_STOP && it.to == CommandTo.WATCH })
        assertFalse("멈춘 뒤 flow.test 거짓", after.flow!!.test)
        assertTrue(after.acks.contains("tr-2"))
    }

    /** 휴대폰에서 시작한 테스트도 차 멈춤 요청으로 멈춘다. 테스트가 없을 때 멈춤은 아무것도 하지 않는다. */
    @Test fun carStopEndsPhoneStartedTestAndIsHarmlessWhenIdle() {
        sync(peer())
        sync(peer(), listOf(request("tr-0", Payload.TestRequest.STOP)))
        assertNull(engine.test)
        assertEquals(TestStart.LOCAL, engine.startTest(Scenario.NOT_WORN, phoneOnly = true, now = mono))
        sync(peer(), listOf(request("tr-1", Payload.TestRequest.STOP)))
        assertNull(engine.test)
    }

    /** P16 「차 화면에서 시작함」 표시 — 차 요청으로 시작한 테스트만 참, 휴대폰에서 시작하면 거짓(표시만). */
    @Test fun statusMarksCarStartedTest() {
        sync(peer())
        sync(peer(), listOf(request("tr-1", Payload.TestRequest.START)))
        assertTrue(engine.status(mono).testFromCar)
        sync(peer(), listOf(request("tr-2", Payload.TestRequest.STOP)))
        assertFalse(engine.status(mono).testFromCar)
        assertEquals(TestStart.LOCAL, engine.startTest(Scenario.NOT_WORN, phoneOnly = true, now = mono))
        assertFalse(engine.status(mono).testFromCar)
    }

    /** 거부(주행 중 · 사건 중 · 준비 안 됨)는 따로 답하지 않고 ack 만 한다 — 테스트는 켜지지 않는다. */
    @Test fun carStartRefusedWhileDriving() {
        sync(peer(speed = 60.0, gear = Gear.D))
        sync(peer(speed = 60.0, gear = Gear.D), listOf(request("tr-1", Payload.TestRequest.START)))
        assertNull(engine.test)
        val req = next()
        assertTrue(req.acks.contains("tr-1"))
        assertFalse(req.flow!!.test)
    }

    @Test fun carStartRefusedDuringIncident() {
        val s1 = FlowStatus.of(FlowMachine().snapshot().copy(state = FlowState.S1), Owner.CAR)
        sync(peer(flow = s1))
        sync(peer(flow = s1), listOf(request("tr-1", Payload.TestRequest.START)))
        assertNull(engine.test)
        assertTrue(next().acks.contains("tr-1"))
    }

    @Test fun carStartRefusedWhenNotReady() {
        store.registration = fullRegistration(powerOn = false)
        sync(peer())
        sync(peer(), listOf(request("tr-1", Payload.TestRequest.START)))
        assertNull(engine.test)
        assertTrue(next().acks.contains("tr-1"))
    }

    /**
     * 「모름」 — 차 링크가 끊긴 오래된 시작 요청은 차의 정차 보장을 믿지 않는다(기존 Motion 규칙). 확인(「정차 중입니다」)이 있을 때만 시작한다.
     * 차 링크가 살아 있으면 이동 판단은 늘 차 값이라 「모름」이 되지 않는다(위 시험들).
     */
    @Test fun unknownMotionNeedsSelfConfirmEvenForCarRequest() {
        sync(peer(ageMs = 20_000), listOf(request("tr-1", Payload.TestRequest.START)))
        assertEquals(Motion.UNKNOWN, engine.status(mono).motion)
        assertNull(engine.test)
        assertTrue(engine.confirmStopped(mono))
        sync(peer(ageMs = 20_000), listOf(request("tr-2", Payload.TestRequest.START)))
        assertEquals(TestMode.LOCAL, engine.test?.mode)
    }

    /** 같은 id 는 한 번만, 이미 테스트 중이면 다시 시작하지 않는다, 차가 아닌 쪽 · 형식 틀린 요청은 실행하지 않고 ack. */
    @Test fun duplicatesRunningForeignAndMalformed() {
        sync(peer())
        sync(peer(), listOf(request("tr-1", Payload.TestRequest.START)))
        val first = engine.test!!
        sync(peer(), listOf(request("tr-2", Payload.TestRequest.START)))
        assertTrue("이미 테스트 중이면 다시 시작하지 않는다", engine.test === first)
        engine.stopTest()
        sync(peer(), listOf(request("tr-1", Payload.TestRequest.START)))
        assertNull("같은 id 다시 옴", engine.test)
        sync(peer(), listOf(request("tr-3", Payload.TestRequest.START, from = Party.WATCH)))
        assertNull("차가 아닌 쪽", engine.test)
        val bad = Command("tr-4", CommandType.TEST_REQUEST, mapOf("action" to "START", "scenario" to "NOT_WORN", "durationMs" to 120_000L), 30_000, true, Party.CAR, CommandTo.PHONE)
        sync(peer(), listOf(bad))
        assertNull("형식 틀림", engine.test)
        sentAcks += next().acks
        assertEquals("같은 id 는 받을 때마다 ack", 2, sentAcks.count { it == "tr-1" })
        assertTrue(sentAcks.containsAll(listOf("tr-2", "tr-3", "tr-4")))
    }

    /** 차 요청의 시나리오 길이는 홈 테스트 길이와 같다(PM 결정 2026-11-01 — 검증된 경보 흐름을 그대로). */
    @Test fun requestDurationMatchesHomeTest() {
        assertEquals(LocalTestRun.DURATION_MS, Payload.TestRequest.START_DURATION_MS)
        assertEquals(LocalTestRun.DURATION_MS, Payload.TestRequest.START.durationMs)
    }

    // ── carDisplay ──

    @Test fun syncCarriesHideHrSetting() {
        assertEquals(CarDisplay(hideHr = false), next().carDisplay)
        store.registration = fullRegistration().copy(hideHrOnCar = true)
        assertEquals(CarDisplay(hideHr = true), next().carDisplay)
        // 꺼짐이어도 짝지어진 동안은 싣는다(설정 스냅숏과 같이)
        store.registration = fullRegistration(powerOn = false).copy(hideHrOnCar = true)
        assertEquals(CarDisplay(hideHr = true), next().carDisplay)
    }

    // ── 동의 문구 판 ──

    /** 처음 설정 · 판 2 동의 사용자는 영향 없음, 판 1 로 ② 에 동의한 사용자만 차 화면 표시가 막힌다(동의 3종 자체는 그대로). */
    @Test fun outdatedConsentBlocksOnlyCarDisplay() {
        assertTrue(ConsentVersion.outdated(1, healthAgreed = true))
        assertFalse(ConsentVersion.carDisplayConsented(1, healthAgreed = true))
        assertFalse(ConsentVersion.outdated(ConsentVersion.CURRENT, healthAgreed = true))
        assertTrue(ConsentVersion.carDisplayConsented(ConsentVersion.CURRENT, healthAgreed = true))
        // 판 3 — 판 2 사용자도 다시 동의를 묻지만, 새로 막는 것은 없다(차 화면 표시 그대로)
        assertEquals(3, ConsentVersion.CURRENT)
        assertTrue(ConsentVersion.outdated(2, healthAgreed = true))
        assertTrue(ConsentVersion.carDisplayConsented(2, healthAgreed = true))
        assertFalse("② 에 동의한 적이 없으면 다시 묻지 않는다(처음 설정에서 판 2 로 묻는다)", ConsentVersion.outdated(1, healthAgreed = false))
        assertTrue(ConsentVersion.carDisplayConsented(1, healthAgreed = false))
        // 숨김 강제 — 동의 전이면 스위치와 상관없이 숨김, 동의 뒤에는 스위치 값
        assertTrue(fullRegistration().copy(carDisplayConsented = false, hideHrOnCar = false).hideHrForCar)
        assertFalse(fullRegistration().copy(carDisplayConsented = true, hideHrOnCar = false).hideHrForCar)
        assertTrue(fullRegistration().copy(carDisplayConsented = true, hideHrOnCar = true).hideHrForCar)
    }

    /**
     * 판 1 사용자가 업데이트한 뒤(재동의 전) — 켬 유지 · 워치 값 전송 · 경보 흐름은 그대로이고 차에는 늘 숨김을 보낸다.
     * 다시 동의하면 숨기기 스위치 값을 따른다.
     */
    @Test fun outdatedUserKeepsMonitoringButCarHides() {
        store.registration = fullRegistration().copy(carDisplayConsented = false, hideHrOnCar = false)
        assertTrue(store.registration.powerOn && store.registration.consents.all)
        val gone = Watch(WristVerdict.PULSE_ABSENT, null, wall0, true, WatchSource.SENSOR_SDK, false, null)
        var sawWatch = false
        var alarmed = false
        repeat(120) {
            mono += 1_000
            engine.onDataLayerWatch(gone, mono)
            engine.onLocation(com.example.guardian.shared.protocol.Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "주소"), mono)
            engine.tick(mono)
            val req = engine.buildSync("tok", mono)
            if (req.watch != null) sawWatch = true
            assertEquals("재동의 전에는 늘 숨김", CarDisplay(hideHr = true), req.carDisplay)
            engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, null, null, emptyList()), mono, mono)
            engine.tick(mono)
            if (engine.status(mono).flowState in PhoneEngine.ALARM_STATES) alarmed = true
        }
        assertEquals(Owner.PHONE, engine.owner)
        assertTrue("워치 값 전송", sawWatch)
        assertTrue("경보 흐름 동작", alarmed)
        // 다시 동의 → 스위치 값(끔 = 표시)
        store.registration = store.registration.copy(carDisplayConsented = true)
        assertEquals(CarDisplay(hideHr = false), next().carDisplay)
        store.registration = store.registration.copy(hideHrOnCar = true)
        assertEquals(CarDisplay(hideHr = true), next().carDisplay)
    }

    /** test:false 인 TEST_REQUEST 는 계약(늘 test:true) 밖이라 실행하지 않고 ack 한다. */
    @Test fun nonTestFlaggedRequestIgnored() {
        sync(peer())
        val notTest = Command("tr-9", CommandType.TEST_REQUEST, Payload.TestRequest.START.toJson(), 30_000, false, Party.CAR, CommandTo.PHONE)
        sync(peer(), listOf(notTest))
        assertNull(engine.test)
        assertTrue(next().acks.contains("tr-9"))
    }
}
