// 엔진 시험 — 판정 주인 선택 · 넘김(계약 §6), sync 직렬화(:shared 모델), 휴대폰 주인 흐름, 테스트 모드에서 119 보내기 경로 없음, 동의 없을 때
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhoneSyncRequest
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.Via
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneEngineTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private var relay = false
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0

    /** 중계 시계 = 단조 + 이 값(시험에서는 고정). */
    private val serverOffset = 5_000_000_000L

    private val engine = PhoneEngine(
        store = store, effects = fx, newId = { "c${ids++}" }, wallNow = { wall0 + (mono - mono0) },
        seqStartEpochMs = wall0, startMono = mono0, relayActive = { relay },
    )

    private fun watch(v: WristVerdict, responseAt: Long? = null, hr: Int? = 70) =
        Watch(v, hr, wall0, worn = v != WristVerdict.CHECK_WEAR, source = WatchSource.SENSOR_SDK, test = false, responseAt = responseAt)

    private fun stoppedLoc() = Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "서울 종로구 세종대로 172")

    private fun carPeer(ageMs: Long, flow: FlowStatus? = null, speed: Double = 0.0, gear: Gear = Gear.P) = CarPeer(
        seq = 1, sentAt = wall0, receivedAt = mono + serverOffset - ageMs,
        vehicle = Vehicle(speedKmh = speed, gear = gear, make = "현대", model = "아반떼", year = 2027), flow = flow,
    )

    /** 지연 0 으로 sync 한 번. */
    private fun sync(peer: CarPeer?, relayWatch: Watch? = null, commands: List<com.example.guardian.shared.protocol.Command> = emptyList()): PhoneSyncRequest {
        val req = engine.buildSync("tok", mono)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, relayWatch, commands), mono, mono)
        return req
    }

    private fun advance(ms: Long, step: Long = 1_000, each: () -> Unit = {}) {
        val end = mono + ms
        while (mono < end) { mono += step; each(); engine.tick(mono) }
    }

    // ── 판정 주인 ──

    @Test fun withoutRelayPhoneIsOwnerAtOnce() {
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
    }

    @Test fun withRelayWaitsForFirstSyncThenCarFreshIsOwner() {
        relay = true
        engine.tick(mono)
        assertNull(engine.owner)
        sync(carPeer(ageMs = 1_000))
        engine.tick(mono)
        assertEquals(Owner.CAR, engine.owner)
        // 차가 주인이어도 설정 스냅숏(켬 · 동의 · 테스트)을 늘 싣고, owner 는 PHONE 이 아니다
        val flow = engine.buildSync("tok", mono).flow!!
        assertEquals(Owner.CAR, flow.owner)
        assertEquals(FlowState.OFF, flow.state)
        assertTrue(flow.snapshot.powerOn)
        assertTrue(flow.snapshot.consents.all)
        assertFalse(flow.test)
    }

    @Test fun powerOffSnapshotStillSentWhilePaired() {
        relay = true
        store.registration = fullRegistration(powerOn = false)
        val flow = engine.buildSync("tok", mono).flow!!
        assertFalse(flow.snapshot.powerOn)
        assertEquals(Owner.CAR, flow.owner)
    }

    /** 차가 주인일 때 휴대폰 「괜찮아요」 — 누른 순간 벽시계를 한 번만 잡아 sync responseAt 에 그대로 싣는다. */
    @Test fun phoneResponseToCarIsCapturedOnce() {
        relay = true
        sync(carPeer(ageMs = 0, speed = 50.0, gear = Gear.D))
        engine.tick(mono)
        assertFalse(engine.status(mono).stopped) // 주행 중 — 화면에 버튼이 없다(AlarmActivity 는 stopped 일 때만 그린다)
        engine.phoneResponse(mono)
        assertNull(engine.phoneResponseAtWall)
        assertNull(engine.buildSync("tok", mono).responseAt) // 누르기 전 null
        sync(carPeer(ageMs = 0))
        engine.tick(mono)
        engine.phoneResponse(mono)
        val first = wall0 + (mono - mono0)
        assertEquals(first, engine.buildSync("tok", mono).responseAt)
        assertTrue(engine.status(mono).responsePending)
        mono += 4_000
        assertEquals(first, engine.buildSync("tok", mono).responseAt) // sync 마다 다시 계산하지 않는다
        mono += 3_000
        engine.phoneResponse(mono) // 다시 누르면 새 값
        assertEquals(first + 7_000, engine.buildSync("tok", mono).responseAt)
        // ALERT_STOP 이 오면 「응답 보냄 · 확인 중」을 거둔다
        val stop = com.example.guardian.shared.protocol.Command(
            "stop-1", CommandType.ALERT_STOP, Payload.AlertStop(com.example.guardian.shared.protocol.AlertStopReason.CLEARED).toJson(),
            30_000, false, com.example.guardian.shared.protocol.Party.CAR, CommandTo.ALL,
        )
        sync(carPeer(ageMs = 0), commands = listOf(stop))
        assertFalse(engine.status(mono).responsePending)
    }

    /** 차 상태 없음 — 휴대폰 GPS 0.6km/h 가 5초 이어지면 정지(출처 PHONE_GPS, 0 만 정지로 보지 않는다). */
    @Test fun gpsSlowCrawlCountsAsStopped() {
        engine.onLocation(stoppedLoc().copy(speedKmh = 30.0), mono)
        engine.tick(mono)
        assertFalse(engine.status(mono).stopped)
        advance(6_000) { engine.onLocation(stoppedLoc().copy(speedKmh = 0.6), mono) }
        assertTrue(engine.status(mono).stopped)
    }

    @Test fun staleCarOrNoPeerMakesPhoneOwner() {
        relay = true
        sync(carPeer(ageMs = 10_001))
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        val e2 = PhoneEngine(store, fx, { "x" }, { wall0 }, wall0, mono, { true })
        e2.onSyncResponse(PhoneSyncResponse(mono + serverOffset, null, null, emptyList()), mono, mono)
        e2.tick(mono)
        assertEquals(Owner.PHONE, e2.owner)
    }

    @Test fun relayOutageHandsOwnershipToPhoneAfter10s() {
        relay = true
        sync(carPeer(ageMs = 0))
        engine.tick(mono)
        assertEquals(Owner.CAR, engine.owner)
        advance(9_000) { engine.onSyncError() }
        assertEquals(Owner.CAR, engine.owner)
        advance(2_000) { engine.onSyncError() }
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(true, engine.status(mono).owner == Owner.PHONE && !engine.status(mono).carFresh)
    }

    @Test fun takeOverContinuesCarFlowWithSameEventId() {
        relay = true
        // 차가 돌리던 상태 기계 — 차 단조 시계(임의)로 S1 사건 진행 중
        val car = FlowMachine()
        val carNow = 50_000L
        car.handle(FlowInput.ClockSync(serverOffset + mono - carNow, carNow)) // 차 보정값: 중계 − 차 단조
        car.handle(FlowInput.Power(true, carNow))
        car.handle(FlowInput.ConsentsChanged(Consents(true, true, true), carNow))
        car.handle(FlowInput.Vehicle(VehicleState(80.0, Gear.D, false), carNow))
        car.handle(FlowInput.Wrist(WristVerdict.NORMAL, carNow))
        car.handle(FlowInput.Wrist(WristVerdict.PULSE_ABSENT, carNow))
        assertEquals(FlowState.S1, car.state)
        val eventId = car.snapshot().facts.eventId!!
        val handoff = FlowStatus.forHandoff(car.snapshot(), Owner.CAR, serverOffset + mono - carNow)

        sync(carPeer(ageMs = 0, flow = handoff, speed = 80.0, gear = Gear.D))
        engine.tick(mono)
        assertEquals(Owner.CAR, engine.owner)
        // 차가 끊긴다 — 11초 뒤 휴대폰이 넘겨받아 같은 사건을 이어 간다
        advance(11_000) { engine.onSyncError() }
        assertEquals(Owner.PHONE, engine.owner)
        val st = engine.status(mono)
        assertTrue(st.flowState in setOf(FlowState.S1, FlowState.S3))
        // 이어 가는 사건의 id 는 그대로 — 보호자 문자 eventId 로 확인
        store.registration = fullRegistration()
        advance(40_000) { engine.onSyncError(); engine.onLocation(stoppedLoc().copy(speedKmh = 70.0), mono) }
        assertTrue(store.sent.any { it == "EVENT:$eventId" })
    }

    @Test fun handOverSendsServerClockSnapshotToCar() {
        relay = true
        sync(carPeer(ageMs = 20_000))
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        engine.onLocation(stoppedLoc().copy(speedKmh = 80.0), mono)
        engine.onDataLayerWatch(watch(WristVerdict.NORMAL), mono)
        engine.tick(mono)
        mono += 1_000
        engine.onDataLayerWatch(watch(WristVerdict.PULSE_ABSENT), mono)
        engine.tick(mono)
        assertEquals(FlowState.S1, engine.status(mono).flowState)
        val whileOwner = sync(carPeer(ageMs = 20_000))
        assertEquals(SnapshotClock.SERVER, whileOwner.flow!!.clock) // 주인일 때 sync 의 flow 는 중계 시각
        // 차가 돌아온다 — 미해결 사건이라 차가 같은 사건 id 를 owner CAR 로 실을 때까지 쥔다(A-4 사건 임대)
        sync(carPeer(ageMs = 0))
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        val id = engine.status(mono).eventId
        val carTook = FlowStatus.of(FlowSnapshot(state = FlowState.S1, powerOn = true, consents = Consents(true, true, true), facts = IncidentFacts(eventId = id)), Owner.CAR)
        sync(carPeer(ageMs = 0, flow = carTook))
        engine.tick(mono)
        assertEquals(Owner.CAR, engine.owner)
        val req = engine.buildSync("tok", mono)
        val snap = req.commands.single { it.type == CommandType.FLOW_SNAPSHOT }
        assertEquals(CommandTo.CAR, snap.to)
        val flow = (Payload.fromJson(snap.type, snap.payload) as Payload.FlowSnapshot).flow
        assertEquals(SnapshotClock.SERVER, flow.clock)
        assertEquals(FlowState.S1, flow.state)
        assertEquals(Owner.CAR, req.flow!!.owner)
    }

    // ── sync 직렬화 ──

    @Test fun syncRequestRoundTripsThroughSharedModel() {
        relay = true
        engine.onDataLayerWatch(watch(WristVerdict.NORMAL, hr = 72), mono)
        engine.onLocation(stoppedLoc(), mono)
        val c = com.example.guardian.shared.protocol.Command(
            "car-1", CommandType.SHOW, Payload.Show("워치 신호 없음 · 워치 연결을 확인하세요", null).toJson(), 30_000, false,
            com.example.guardian.shared.protocol.Party.CAR, CommandTo.PHONE,
        )
        val first = sync(carPeer(ageMs = 0), commands = listOf(c))
        val req = engine.buildSync("tok", mono)
        assertTrue(req.seq > first.seq)
        assertEquals(listOf("car-1"), req.acks)
        assertNull(req.watch) // 같은 Data Layer 값은 한 번만 싣는다
        assertEquals(72, first.watch!!.hr)
        val text = Json.encode(req.toJson(healthConsent = true))
        val back = PhoneSyncRequest.fromJson(JsonObj(Json.parseObject(text)))
        assertEquals(req, back)
        // 위치 동의가 있어도 휴대폰 위치는 싣지 않는다(감사 중4 A 안) · 건강 동의는 싣는다(감사 하4)
        val wire = Json.parseObject(text)
        assertTrue(!wire.containsKey("phoneLocation"))
        assertEquals(true, wire["healthConsent"])
        assertEquals(true, back.healthConsent)
    }

    @Test fun noHealthConsentSendsNoWatchAndNoLocationConsentSendsNoLocation() {
        store.registration = fullRegistration(consents = Consents(emergencyInfo = true, health = false, location = false), powerOn = false)
        engine.onDataLayerWatch(watch(WristVerdict.NORMAL, hr = 72), mono)
        engine.onLocation(stoppedLoc(), mono)
        val req = engine.buildSync("tok", mono)
        assertNull(req.watch)
        assertEquals(false, req.healthConsent)
        val json = Json.parseObject(Json.encode(req.toJson(healthConsent = false)))
        assertNull(json["watch"])
        assertTrue(!json.containsKey("phoneLocation"))
        // 건강 동의 false 를 실어 중계가 워치 직접 값을 차에 내리지 않게 한다(감사 하4 · 계약 §8)
        assertEquals(false, json["healthConsent"])
    }

    /** 감사 중4 A 안 — 위치는 중계 요청에 없지만 보호자 문자 · 119 작성 본문의 위치 줄은 로컬 위치로 그대로 만든다. */
    @Test fun locationStaysInSmsButNotInSync() {
        val feed = { v: WristVerdict -> engine.onDataLayerWatch(watch(v), mono); engine.onLocation(stoppedLoc(), mono) }
        feed(WristVerdict.NORMAL)
        engine.tick(mono)
        advance(1_000) { feed(WristVerdict.PULSE_ABSENT) }
        advance(36_000) { feed(WristVerdict.PULSE_ABSENT) }
        assertTrue(fx.sms[0].second.startsWith("[앱 안내] 37.57590,126.97690"))
        assertTrue(fx.noResponses.single().full.startsWith("[119 구조요청] 37.57590,126.97690"))
        val wire = Json.encode(engine.buildSync("tok", mono).toJson(healthConsent = true))
        assertTrue(!wire.contains("phoneLocation"))
        assertTrue(!wire.contains("37.57590"))
    }

    @Test fun relayWatchStaleAfter30sIsTreatedAsNoSignal() {
        relay = true
        val w = watch(WristVerdict.NORMAL).copy(via = Via.WATCH, receivedAt = mono + serverOffset - 29_000, ageMs = 29_000)
        sync(carPeer(ageMs = 0), relayWatch = w)
        engine.tick(mono)
        assertFalse(engine.status(mono).watchStale)
        mono += 2_000
        assertTrue(engine.status(mono).watchStale)
    }

    // ── 휴대폰 주인 전체 흐름 ──

    /** 차 상태 없음 · 정차 · 워치 맥박 신호 잡히지 않음 → AP → 35초 무응답 → S4: 보호자 문자 1회 + 119 작성(정지). */
    @Test fun phoneOwnerRunsFlowToComposeWhenStopped() {
        val feed = { v: WristVerdict -> engine.onDataLayerWatch(watch(v), mono); engine.onLocation(stoppedLoc(), mono) }
        feed(WristVerdict.NORMAL)
        engine.tick(mono)
        assertEquals(FlowState.S0, engine.status(mono).flowState)
        advance(1_000) { feed(WristVerdict.PULSE_ABSENT) }
        assertEquals(FlowState.AP, engine.status(mono).flowState)
        advance(16_000) { feed(WristVerdict.PULSE_ABSENT) }
        assertEquals(listOf("AP"), fx.alarms) // 경보 2(소리)에서 휴대폰 알람
        advance(20_000) { feed(WristVerdict.PULSE_ABSENT) }
        assertEquals(FlowState.S4, engine.status(mono).flowState)
        assertEquals(1, fx.sms.size)
        assertEquals(GUARDIAN, fx.sms[0].first)
        assertTrue(fx.sms[0].second.startsWith("[앱 안내] 37.57590,126.97690"))
        assertTrue(fx.sms[0].second.contains("차 상태 없음"))
        // 지시는 P12 만 띄운다 — 문자 앱은 사람이 버튼을 누를 때
        assertTrue(fx.composed.isEmpty())
        val b = fx.noResponses.single()
        assertTrue(b.full.startsWith("[119 구조요청] 37.57590,126.97690"))
        assertTrue(b.full.contains("(연락함)"))
        assertTrue(b.ultra!!.startsWith("[119]37.57590,126.97690"))
        assertEquals(FlowState.S4, engine.status(mono).shownState)
        assertTrue(engine.status(mono).noResponseSec!! >= 35)
        assertTrue(engine.openCompose(mono))
        assertEquals(listOf(b.full), fx.composed)
        assertEquals(1, fx.mutes)
        // 워치 · 차 몫 지시는 다음 sync 로
        relay = true
        val out = engine.buildSync("tok", mono).commands
        assertTrue(out.any { it.type == CommandType.ALERT_START && it.to == CommandTo.WATCH })
        assertTrue(out.none { it.type == CommandType.OPEN_119_COMPOSE || it.type == CommandType.SEND_GUARDIAN_SMS })
        assertTrue(out.none { it.resolvedTo == CommandTo.PHONE }) // 자기 대상 지시는 중계로 보내지 않는다(splitLocal)
        // 문자 지시에는 등록 정보가 없다(G2) — 이름 · 보호자 번호가 중계로 나가지 않는다
        val wire = out.joinToString { Json.encode(it.toJson()) }
        assertFalse(wire.contains(GUARDIAN) || wire.contains("홍길동"))
        // 계속 돌아도 같은 사건 문자는 다시 보내지 않는다
        advance(30_000) { feed(WristVerdict.PULSE_ABSENT) }
        assertEquals(1, fx.sms.size)
        assertEquals(1, fx.noResponses.size)
    }

    @Test fun phoneOwnerDrivingNeverOpensCompose() {
        val feed = { v: WristVerdict -> engine.onDataLayerWatch(watch(v), mono); engine.onLocation(stoppedLoc().copy(speedKmh = 60.0), mono) }
        feed(WristVerdict.NORMAL)
        engine.tick(mono)
        advance(60_000) { feed(WristVerdict.PULSE_ABSENT) }
        assertEquals(FlowState.S3, engine.status(mono).flowState)
        assertEquals(1, fx.sms.size) // 주행 중 무응답 — 보호자 문자는 보낸다
        assertTrue(fx.composed.isEmpty())
        assertTrue(fx.noResponses.isEmpty())
        assertTrue(fx.previews.isEmpty())
    }

    /**
     * 2026-11-02 코드 리뷰 상 1 — 워치가 신호를 못 잡고 있다고 알린 값(`noSignal`, Data Layer 경로)은 판정 입력으로 쓰지 않고,
     * 마지막으로 넣은 값에서 30초가 지나면 기존 「워치 신호 없음」 경로를 탄다. 응답(responseAt 증가)은 그대로 응답이다.
     */
    @Test fun noSignalWatchValueIsNotAVerdictButResponseStillCounts() {
        val feed = { v: WristVerdict, r: Long?, ns: Boolean ->
            engine.onDataLayerWatch(watch(v, responseAt = r).copy(noSignal = ns), mono); engine.onLocation(stoppedLoc().copy(speedKmh = 60.0), mono)
        }
        feed(WristVerdict.NORMAL, null, false)
        engine.tick(mono)
        assertEquals(FlowState.S0, engine.status(mono).flowState)
        assertFalse(engine.status(mono).watchStale)
        advance(20_000) { feed(WristVerdict.PULSE_ABSENT, null, true) }
        assertEquals("신호 없음 값의 PULSE_ABSENT 는 사건이 아니다", FlowState.S0, engine.status(mono).flowState)
        assertTrue("화면은 바로 신호 없음(옛 판정을 보이지 않는다)", engine.status(mono).watchStale)
        assertTrue(fx.sms.isEmpty() && fx.alarms.isEmpty())
        // 마지막으로 넣은 값(시작)에서 30초 — 상태 기계의 「워치 신호 없음」 안내(기존 경로)
        advance(12_000) { feed(WristVerdict.PULSE_ABSENT, null, true) }
        assertTrue(fx.lines.toString(), fx.lines.contains(com.example.guardian.shared.text.Phrase.SCREEN_WATCH_NO_SIGNAL.ko))
        assertEquals(FlowState.S0, engine.status(mono).flowState)
        // 신호가 돌아오면 바로 판정에 쓴다 — 약해짐이면 경보
        advance(2_000) { feed(WristVerdict.NORMAL, null, false) }
        assertFalse(engine.status(mono).watchStale)
        advance(4_000) { feed(WristVerdict.PULSE_WEAK, null, false) }
        assertEquals(FlowState.S1, engine.status(mono).flowState)
        // 경보 중 워치가 다시 신호를 잃어도 버튼 응답은 그대로 응답이다
        advance(1_000) { feed(WristVerdict.PULSE_WEAK, wall0 + (mono - mono0), true) }
        assertEquals(FlowState.S1R, engine.status(mono).flowState)
    }

    /** 중계 경유로 온 noSignal 값도 화면에서는 신호 없음이다(차가 주인일 때 휴대폰은 판정을 돌리지 않는다). */
    @Test fun relayedNoSignalWatchShowsAsNoSignal() {
        relay = true
        val w = watch(WristVerdict.NORMAL).copy(via = Via.WATCH, receivedAt = mono + serverOffset, ageMs = 0, noSignal = true)
        sync(carPeer(ageMs = 0), relayWatch = w)
        engine.tick(mono)
        assertEquals(Owner.CAR, engine.owner)
        assertTrue(engine.status(mono).watchStale)
        sync(carPeer(ageMs = 0), relayWatch = w.copy(noSignal = false))
        assertFalse(engine.status(mono).watchStale)
    }

    @Test fun watchResponseCancelsAndPhoneButtonOnlyWhenStopped() {
        val feed = { v: WristVerdict, r: Long? -> engine.onDataLayerWatch(watch(v, responseAt = r), mono); engine.onLocation(stoppedLoc().copy(speedKmh = 60.0), mono) }
        feed(WristVerdict.NORMAL, null)
        engine.tick(mono)
        advance(40_000) { feed(WristVerdict.PULSE_WEAK, null) }
        assertEquals(FlowState.S3, engine.status(mono).flowState)
        engine.phoneResponse(mono) // 주행 중 휴대폰 화면 응답은 받지 않는다(G5)
        assertEquals(FlowState.S3, engine.status(mono).flowState)
        assertFalse(engine.status(mono).canRespond)
        advance(1_000) { feed(WristVerdict.PULSE_WEAK, wall0 + 41_000) }
        assertEquals(FlowState.S1R, engine.status(mono).flowState)
        assertEquals(2, fx.sms.size) // 사건 + 취소
        assertTrue(fx.sms[1].second.contains("취소"))
    }

    // ── 동의 없을 때 ──

    @Test fun withoutConsentsFlowStaysOffAndNothingIsSent() {
        store.registration = fullRegistration(consents = Consents(emergencyInfo = true, health = true, location = false), powerOn = false)
        val feed = { v: WristVerdict -> engine.onDataLayerWatch(watch(v), mono); engine.onLocation(stoppedLoc(), mono) }
        feed(WristVerdict.NORMAL)
        engine.tick(mono)
        advance(60_000) { feed(WristVerdict.PULSE_ABSENT) }
        assertEquals(FlowState.OFF, engine.status(mono).flowState)
        assertTrue(fx.sms.isEmpty() && fx.composed.isEmpty() && fx.alarms.isEmpty())
        assertEquals(TestStart.NEED_SETUP, engine.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = mono))
    }

    // ── 테스트 모드 ──

    /** 워치 없음 → 휴대폰 안 시뮬레이션. 보호자 문자는 확인 뒤 보호자에게만, 119 는 미리보기만(작성 화면 경로 없음). */
    @Test fun localTestModeAsksBeforeGuardianSmsAndOnlyPreviews119() {
        engine.onLocation(stoppedLoc(), mono)
        engine.tick(mono)
        assertEquals(TestStart.LOCAL, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
        advance(LocalTestRun.DURATION_MS)
        assertNotNull(engine.status(mono).testFlowState)
        assertEquals(FlowState.S4, engine.status(mono).testFlowState)
        assertEquals(1, fx.asked.size)
        val p = fx.asked[0]
        assertEquals(GUARDIAN, p.to)
        assertTrue(p.body.startsWith("[테스트] [앱 안내]"))
        assertTrue(p.body.lines()[1] == "실제 상황 아님")
        assertTrue(fx.sms.isEmpty()) // 확인 전에는 보내지 않는다
        engine.confirmTestSms(p.key, send = true)
        assertEquals(listOf(GUARDIAN), fx.sms.map { it.first })
        assertTrue(fx.composed.isEmpty())
        assertTrue(fx.noResponses.isEmpty())
        assertEquals(1, fx.previews.size)
        assertTrue(fx.previews[0].full.startsWith("[테스트] [119 구조요청]"))
        assertTrue(fx.previews[0].ultra!!.startsWith("[테스트][119]"))
        assertFalse(engine.openCompose(mono) && fx.composed.isNotEmpty()) // 다시 열어도 미리보기만
        assertTrue(fx.composed.isEmpty())
        assertTrue(engine.status(mono).flowTest)
        // 휴대폰 밖(차 · 워치 · 중계)으로 시험 지시를 보내지 않는다
        relay = true
        assertTrue(engine.buildSync("tok", mono).commands.isEmpty())
        assertFalse(fx.sms.any { it.first.contains("119") })
    }

    /** 워치가 중계에 직접 붙어 있으면(응답 watchDirectAgeMs 30초 이하, 계약 §4.3) 워치로 TEST_SCENARIO 를 보낸다. */
    @Test fun directWatchSendsTestScenarioToWatch() {
        engine.onDataLayerWatch(watch(WristVerdict.NORMAL), mono)
        engine.onLocation(stoppedLoc(), mono)
        engine.buildSync("tok", mono)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, null, null, emptyList(), watchDirectAgeMs = 1_000), mono, mono)
        engine.tick(mono)
        assertTrue(engine.status(mono).watchDirect)
        assertEquals(TestStart.WATCH, engine.startTest(Scenario.AMPLITUDE_DROP, phoneOnly = false, now = mono))
        relay = true
        val c = engine.buildSync("tok", mono).commands.single()
        assertEquals(CommandType.TEST_SCENARIO, c.type)
        assertEquals(CommandTo.WATCH, c.to)
        assertTrue(c.test)
        assertEquals("AMPLITUDE_DROP", c.payload["scenario"])
        // 워치 지시는 중계로만 — Data Layer 로 건네는 경로는 없다(PM 지시)
    }

    @Test fun testNeedsStopAndNoIncident() {
        engine.onLocation(stoppedLoc().copy(speedKmh = 60.0), mono)
        engine.tick(mono)
        assertEquals(TestStart.NOT_STOPPED, engine.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = mono))
        // 정차 · 사건 진행 중이면 BUSY
        val feed = { v: WristVerdict -> engine.onDataLayerWatch(watch(v), mono); engine.onLocation(stoppedLoc(), mono) }
        advance(6_000) { feed(WristVerdict.NORMAL) } // 정지 판정 디바운스 5초
        advance(2_000) { feed(WristVerdict.PULSE_ABSENT) }
        assertEquals(TestStart.BUSY, engine.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = mono))
        // 워치가 살아 있어도 「휴대폰만」 이면 휴대폰 안 시뮬레이션
        val e2 = PhoneEngine(store, FakeEffects(), { "y" }, { wall0 }, wall0, mono, { false })
        e2.onDataLayerWatch(watch(WristVerdict.NORMAL), mono)
        e2.onLocation(stoppedLoc(), mono)
        e2.tick(mono)
        assertEquals(TestStart.LOCAL, e2.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = mono))
    }

    // ── 테스트 세션 종료(휴대폰 주인) — 응답 뒤(S1R · S2)에서도 표시가 바로 꺼진다(실주행 3 새 결함, 사용자 지시 2026-11-07) ──

    /**
     * 워치 테스트 세션을 S2 에서 끝내면 — 워치가 아직 시뮬레이션 값을 보내는 동안은 테스트로 남고(시뮬레이션 판정으로 실제 경보를 열지 않게),
     * 실제 값으로 돌아오면 바로 S0 · flow.test 거짓. 그 뒤 진짜 나쁜 신호는 테스트의 정차 관찰 · 재경보 타이머 없이 바로 실제 경보다.
     */
    @Test fun phoneOwnerSessionEndInRestClearsFlowTestAndRealAlertIsNotDelayed() {
        val sim = { v: WristVerdict -> watch(v).copy(source = WatchSource.SIMULATION, test = true) }
        val feed = { w: Watch -> engine.onDataLayerWatch(w, mono); engine.onLocation(stoppedLoc(), mono) }
        feed(watch(WristVerdict.NORMAL))
        engine.buildSync("tok", mono)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, null, null, emptyList(), watchDirectAgeMs = 1_000), mono, mono)
        engine.tick(mono)
        assertEquals(TestStart.WATCH, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
        advance(3_000) { feed(sim(WristVerdict.PULSE_ABSENT)) }
        assertEquals(FlowState.AP, engine.status(mono).flowState)
        assertTrue(engine.buildSync("tok", mono).flow!!.test)
        engine.phoneResponse(mono)
        assertEquals(FlowState.S2, engine.status(mono).flowState)
        engine.stopTest()
        assertNull(engine.test)
        advance(2_000) { feed(sim(WristVerdict.PULSE_ABSENT)) }
        assertEquals("워치가 아직 시뮬레이션이면 테스트로 남는다", FlowState.S2, engine.status(mono).flowState)
        assertTrue(engine.buildSync("tok", mono).flow!!.test)
        advance(2_000) { feed(watch(WristVerdict.NORMAL)) }
        assertEquals("워치가 실제 값이면 바로 S0", FlowState.S0, engine.status(mono).flowState)
        assertFalse(engine.status(mono).flowTest)
        assertFalse(engine.buildSync("tok", mono).flow!!.test)
        advance(2_000) { feed(watch(WristVerdict.PULSE_ABSENT)) }
        assertEquals("진짜 나쁜 신호는 바로 실제 경보", FlowState.AP, engine.status(mono).flowState)
        assertFalse(engine.buildSync("tok", mono).flow!!.test)
    }

    /** 세션이 이미 없을 때 차의 「테스트 멈추기」(TEST_REQUEST STOP) — 예외 없이 ack 하고 flow.test 는 거짓이다(계약 §4.6 「진행 중인 테스트가 없으면 아무것도 하지 않는다」). */
    @Test fun stopRequestWithoutSessionIsAckedAndFlowTestIsFalse() {
        relay = true
        engine.onDataLayerWatch(watch(WristVerdict.NORMAL), mono)
        engine.onLocation(stoppedLoc(), mono)
        sync(carPeer(ageMs = 1_000))
        engine.tick(mono)
        assertEquals(Owner.CAR, engine.owner)
        assertNull(engine.test)
        val stop = com.example.guardian.shared.protocol.Command(
            "car-stop-1", CommandType.TEST_REQUEST, Payload.TestRequest.STOP.toJson(), 30_000, true,
            com.example.guardian.shared.protocol.Party.CAR, CommandTo.PHONE,
        )
        sync(carPeer(ageMs = 1_000), commands = listOf(stop))
        val req = engine.buildSync("tok", mono)
        assertTrue("받았다고 ack", "car-stop-1" in req.acks)
        assertFalse(req.flow!!.test)
        assertNull(engine.test)
    }
}
