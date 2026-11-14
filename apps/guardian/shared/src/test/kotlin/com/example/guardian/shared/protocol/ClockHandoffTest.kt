// 주인 넘김 시계 보정 시험 — 차 시계 ±90초 · 시계 미확인 보수적 복원 · 사건 id 유지 · 단조 시계 · 표본 만료
package com.example.guardian.shared.protocol

import com.example.guardian.shared.bio.WristVerdict.NORMAL
import com.example.guardian.shared.bio.WristVerdict.PULSE_ABSENT
import com.example.guardian.shared.bio.WristVerdict.PULSE_WEAK
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.GuardianSmsKind
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.ResponseSource
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockHandoffTest {
    private val moving = VehicleState(60.0, Gear.D, false)
    private val parked = VehicleState(0.0, Gear.P, false)

    /** 중계 시각 기준 시험 — 휴대폰 단조 시계 = 중계 시계, 차 단조 시계 = 중계 + skew. */
    private class World(val skew: Long) {
        fun car(serverMs: Long) = serverMs + skew
        val carClock = ClockOffset().apply {
            // 왕복이 크고 틀린 표본(무시돼야 함) + 정상 표본
            add(sentLocalMs = car(5_000), receivedLocalMs = car(5_000) + 4_000, serverTimeMs = 40_000)
            add(sentLocalMs = car(10_000), receivedLocalMs = car(10_000) + 100, serverTimeMs = 10_050)
        }
        val phoneClock = ClockOffset().apply { add(11_000, 11_080, 11_040) }
        fun carOffset(serverNow: Long) = carClock.offsetAt(car(serverNow))!!
        fun phoneOffset(serverNow: Long) = phoneClock.offsetAt(serverNow)
    }

    private fun armCar(w: World, vehicle: VehicleState, withClock: Boolean = true): FlowMachine {
        val m = FlowMachine()
        if (withClock) m.handle(FlowInput.ClockSync(w.carOffset(10_200), w.car(0)))
        m.handle(FlowInput.Vehicle(vehicle, w.car(0)))
        m.handle(FlowInput.ConsentsChanged(Consents(true, true, true), w.car(0)))
        m.handle(FlowInput.Power(true, w.car(0)))
        m.handle(FlowInput.Wrist(NORMAL, w.car(0)))
        return m
    }

    /** 차 → JSON(중계 시각) → 휴대폰 복원. [serverNow] 는 넘기는 순간의 중계 시각. */
    private fun handoff(w: World, car: FlowMachine, serverNow: Long, phoneOffset: Long? = w.phoneOffset(serverNow)): Pair<FlowMachine, RestoredSnapshot> {
        val status = FlowStatus.forHandoff(car.snapshot(), Owner.PHONE, w.carOffset(serverNow))
        val json = Json.encode(status.toJson())
        assertTrue(json.contains("\"clock\":\"SERVER\""))
        val received = FlowStatus.fromJson(JsonObj(Json.parseObject(json)))
        val restored = received.restoreLocal(phoneOffset, nowLocalMs = serverNow)
        return FlowMachine.restore(restored.snapshot) to restored
    }

    @Test fun offsetUsesShortestRoundTrip() {
        for (skew in listOf(90_000L, -90_000L)) assertEquals(-skew, World(skew).carOffset(10_200))
        assertEquals(0L, World(0).phoneOffset(11_100))
    }

    @Test fun clockOffsetSampleRules() {
        val c = ClockOffset(window = 3)
        assertNull(c.offsetAt(0))
        assertTrue(c.add(0, 100, 5_050))        // 5,000 · 왕복 100
        assertTrue(c.add(1_000, 4_000, 8_500))  // 6,000 · 왕복 3,000
        assertFalse("왕복 5초 초과는 받지 않음", c.add(2_000, 7_001, 0))
        assertTrue("왕복 정확히 5초는 받음", c.add(2_000, 7_000, 9_500))
        assertFalse("음수 왕복은 받지 않음", c.add(5_000, 4_999, 0))
        assertEquals(5_000L, c.offsetAt(7_000))
        c.add(10_000, 10_500, 17_250); c.add(20_000, 20_400, 27_200) // 7,000 · 왕복 500 / 400
        assertEquals("창 밖으로 밀린 표본은 버림", 7_000L, c.offsetAt(20_400))
    }

    /** 하-B — 표본은 60초가 지나면 버린다. */
    @Test fun samplesExpireAfter60s() {
        val c = ClockOffset()
        c.add(1_000, 1_100, 10_050)
        assertEquals(9_000L, c.offsetAt(61_100))
        assertNull(c.offsetAt(61_101))
    }

    /** 하-B · be-qa N7 — 시계가 되돌아가면 옛 표본을 버린다(단조 시계를 쓰면 생기지 않지만 재부팅 등 대비). */
    @Test fun backwardClockDropsOldSamples() {
        val c = ClockOffset()
        c.add(1_000, 1_100, 10_050) // 9,000 · 왕복 100
        for (k in 0 until 7) c.add(100_000L + k * 2_000, 100_300L + k * 2_000, 169_150L + k * 2_000)
        assertEquals(69_000L, c.offsetAt(112_300))
        c.add(50_000, 50_300, 119_150) // 받은 시각이 되돌아감
        assertEquals("되돌아간 뒤 표본만", 69_000L, c.offsetAt(50_300))
        assertNull("옛 표본은 버려짐", c.offsetAt(112_300 + 60_001))
    }

    /** 벽시계가 뒤로 가도 단조 시계 기준 상태 기계 · 보정은 영향이 없다. */
    @Test fun wallClockJumpDoesNotAffectMonotonicFlow() {
        val mono = { serverMs: Long -> serverMs - 7_000 } // 단조 시계 = 중계 − 7초
        val c = ClockOffset()
        c.add(mono(10_000), mono(10_000) + 100, 10_050)
        // 이 사이 벽시계가 1시간 뒤로 갔다고 해도 단조 시각은 계속 늘어난다 — 계산에 벽시계를 쓰지 않는다
        c.add(mono(20_000), mono(20_000) + 100, 20_050)
        assertEquals(7_000L, c.offsetAt(mono(20_100)))
    }

    /** 35초 타이머 · 디바운스 — 넘긴 뒤에도 원래 사건 시각(중계 기준)대로 이어진다. */
    @Test fun timerAndDebounceContinueAcrossSkew() {
        for (skew in listOf(90_000L, -90_000L)) {
            val w = World(skew)
            val car = armCar(w, moving)
            car.handle(FlowInput.Wrist(PULSE_WEAK, w.car(1_000)))
            car.handle(FlowInput.Vehicle(parked, w.car(10_000))) // 디바운스 중에 넘긴다
            val (phone, r) = handoff(w, car, serverNow = 12_000)
            assertTrue(r.clockVerified)
            phone.handle(FlowInput.Tick(14_999)); assertEquals("skew=$skew", FlowState.S1, phone.state)
            phone.handle(FlowInput.Tick(15_000)); assertEquals("skew=$skew 디바운스 5초", FlowState.AP, phone.state)
            phone.handle(FlowInput.Tick(35_999)); assertEquals(FlowState.AP, phone.state)
            phone.handle(FlowInput.Tick(36_000)); assertEquals("skew=$skew 첫 경보 + 35초", FlowState.S4, phone.state)
            assertEquals(1_000L, phone.snapshot().facts.firstAlertAtMs)
            assertEquals(10_000L, phone.snapshot().facts.stoppedAtMs)
        }
    }

    /** 600초 정차 관찰도 원래 시각대로. */
    @Test fun observationContinuesAcrossSkew() {
        for (skew in listOf(90_000L, -90_000L)) {
            val w = World(skew)
            val car = armCar(w, parked)
            car.handle(FlowInput.Wrist(PULSE_ABSENT, w.car(1_000)))
            car.handle(FlowInput.Response(ResponseSource.CAR_BUTTON, w.car(2_000))); assertEquals(FlowState.S2, car.state)
            car.handle(FlowInput.Wrist(NORMAL, w.car(3_000)))
            val (phone, _) = handoff(w, car, serverNow = 12_000)
            phone.handle(FlowInput.Wrist(NORMAL, 601_999)); assertEquals("skew=$skew", FlowState.S2, phone.state)
            phone.handle(FlowInput.Wrist(NORMAL, 602_000)); assertEquals("skew=$skew", FlowState.S0, phone.state)
        }
    }

    /** 중-C · be-qa N5 — 사건 id 는 사건 시작 때 중계 시각으로 만들고 넘김 뒤에도 그대로. */
    @Test fun eventIdSurvivesHandoff() {
        for (skew in listOf(90_000L, -90_000L)) {
            val w = World(skew)
            val car = armCar(w, moving)
            val log = ArrayList<Directive>()
            log += car.handle(FlowInput.Wrist(PULSE_WEAK, w.car(1_000)))
            log += car.handle(FlowInput.Tick(w.car(36_000)))
            val (phone, _) = handoff(w, car, serverNow = 40_000)
            log += phone.handle(FlowInput.Response(ResponseSource.WATCH_BUTTON, 41_000))
            val ids = log.filterIsInstance<Directive.SendGuardianSms>().map { it.kind to DirectiveCommands.eventId(it) }
            assertEquals(listOf(GuardianSmsKind.INCIDENT to "event-1000", GuardianSmsKind.CANCEL to "event-1000"), ids)
        }
    }

    @Test fun eventIdWithoutClockIsLocalMarked() {
        val w = World(90_000)
        val car = armCar(w, moving, withClock = false)
        car.handle(FlowInput.Wrist(PULSE_WEAK, w.car(1_000)))
        assertEquals("event-L91000", car.snapshot().facts.eventId)
    }

    /** 하-A · be-qa N6 — 시계 표본 없이 넘겨받아도 예외 없이 「시계 미확인」으로 복원하고 타이머는 늦어지는 쪽. */
    @Test fun unverifiedClockRestoreIsConservative() {
        for (skew in listOf(90_000L, -90_000L)) {
            val w = World(skew)
            val car = armCar(w, moving)
            car.handle(FlowInput.Wrist(PULSE_WEAK, w.car(1_000)))
            val (phone, r) = handoff(w, car, serverNow = 20_000, phoneOffset = null)
            assertFalse(r.clockVerified)
            assertTrue(phone.snapshot().clockUnverified)
            // 실제로는 36,000 에 S3 이지만, 받은 시각(20,000)을 첫 경보로 다시 잡아 55,000 에 S3 — 앞당기지 않는다
            phone.handle(FlowInput.Tick(36_000)); assertEquals(FlowState.S1, phone.state)
            phone.handle(FlowInput.Tick(54_999)); assertEquals(FlowState.S1, phone.state)
            phone.handle(FlowInput.Tick(55_000)); assertEquals(FlowState.S3, phone.state)
            assertEquals("사건 id 는 그대로", "event-1000", phone.snapshot().facts.eventId)
        }
    }

    @Test fun localClockSnapshotRestoresAsIs() {
        val local = FlowStatus.of(FlowMachine().snapshot(), Owner.CAR)
        val json = Json.encode(local.toJson())
        assertTrue(json.contains("\"clock\":\"LOCAL\""))
        val back = FlowStatus.fromJson(JsonObj(Json.parseObject(json)))
        assertEquals(local, back)
        assertTrue("내가 보낸 LOCAL 은 그대로", back.restoreLocal(null, 0, sameDevice = true).clockVerified)
        assertEquals(local.snapshot, back.restoreLocal(null, 0, sameDevice = true).snapshot)
    }

    /** sync flow 규칙 하나 — 보정값이 있으면 SERVER, 없으면 LOCAL. 차는 받은 flow 를 restoreLocal 로 되돌린다(계약 §2 예시). */
    @Test fun syncFlowUsesServerClockWhenOffsetKnown() {
        for (skew in listOf(-90_000L, 90_000L)) {
            // 휴대폰이 주인(단조 = 중계 − 1,000,000)으로 S1 에 있다
            val phoneOffset = 1_000_000L
            val phone = FlowMachine()
            listOf(
                FlowInput.ClockSync(phoneOffset, 0), FlowInput.Vehicle(moving, 0), FlowInput.ConsentsChanged(Consents(true, true, true), 0),
                FlowInput.Power(true, 0), FlowInput.Wrist(NORMAL, 0), FlowInput.Wrist(PULSE_WEAK, 1_000),
            ).forEach { phone.handle(it) }
            val sent = FlowStatus.forSync(phone.snapshot(), Owner.PHONE, phoneOffset)
            assertEquals(SnapshotClock.SERVER, sent.clock)
            assertEquals(1_000 + phoneOffset, sent.since)
            // 차(단조 = 중계 + skew, 보정값 −skew)가 sync 응답의 peer.flow 를 받아 되돌린다
            val got = FlowStatus.fromJson(JsonObj(Json.parseObject(Json.encode(sent.toJson()))))
            val r = got.restoreLocal(offsetMs = -skew, nowLocalMs = 2_000 + phoneOffset + skew)
            assertTrue(r.clockVerified)
            assertEquals(1_000 + phoneOffset + skew, r.snapshot.facts.firstAlertAtMs)
            assertEquals(-skew, r.snapshot.serverOffsetMs)
        }
        assertEquals(SnapshotClock.LOCAL, FlowStatus.forSync(FlowMachine().snapshot(), Owner.CAR, null).clock)
    }

    /** 다른 기기의 LOCAL 스냅숏은 그대로 쓰지 않고 늦어지는 쪽으로 다시 잡는다. 설정(켬 · 동의 · 테스트)은 그대로 읽힌다. */
    @Test fun otherDeviceLocalSnapshotIsRebased() {
        val m = FlowMachine()
        listOf(
            FlowInput.Vehicle(moving, 0), FlowInput.ConsentsChanged(Consents(true, true, true), 0), FlowInput.TestMode(true, 0), FlowInput.Power(true, 0),
            FlowInput.Wrist(NORMAL, 0), FlowInput.Wrist(PULSE_WEAK, 900_000),
        ).forEach { m.handle(it) }
        val got = FlowStatus.fromJson(JsonObj(Json.parseObject(Json.encode(FlowStatus.forSync(m.snapshot(), Owner.CAR, null).toJson()))))
        val r = got.restoreLocal(offsetMs = 77, nowLocalMs = 5_000)
        assertFalse(r.clockVerified)
        assertEquals(5_000L, r.snapshot.facts.firstAlertAtMs)
        assertEquals(77L, r.snapshot.serverOffsetMs)
        assertTrue(r.snapshot.powerOn && r.snapshot.test)
        assertEquals(Consents(true, true, true), r.snapshot.consents)
    }
}
