// 합동 점검(2026-11-13) 판정 묶음 재현 — B-2 재경보 · B-4 정지 판정 · B-6 감시 중 미착용 · B-7 테스트 흐름 거두기(be-qa r2flow · r2gear)
package com.example.guardian.shared.flow

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.bio.WristVerdict.CHECK_WEAR
import com.example.guardian.shared.bio.WristVerdict.NORMAL
import com.example.guardian.shared.bio.WristVerdict.PULSE_ABSENT
import com.example.guardian.shared.bio.WristVerdict.PULSE_WEAK
import com.example.guardian.shared.flow.FlowState.AP
import com.example.guardian.shared.flow.FlowState.OFF
import com.example.guardian.shared.flow.FlowState.S0
import com.example.guardian.shared.flow.FlowState.S1
import com.example.guardian.shared.flow.FlowState.S1R
import com.example.guardian.shared.flow.FlowState.S3
import com.example.guardian.shared.flow.FlowState.S4
import com.example.guardian.shared.flow.FlowState.S5
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedBlueFlowTest {
    private val all = Consents(emergencyInfo = true, health = true, location = true)
    private val moving = VehicleState(speedKmh = 60.0, gear = Gear.D, parkingBrake = false)
    private val parked = VehicleState(speedKmh = 0.0, gear = Gear.P, parkingBrake = false)

    private class H(val m: FlowMachine = FlowMachine()) {
        val log = ArrayList<Directive>()
        fun go(i: FlowInput): List<Directive> = m.handle(i).also { log += it }
        val state get() = m.state
        fun alerts() = log.count { it is Directive.AlertStart }
    }

    private fun armed(vehicle: VehicleState? = moving, test: Boolean = false): H {
        val h = H()
        vehicle?.let { h.go(FlowInput.Vehicle(it, 0)) }
        if (test) h.go(FlowInput.TestMode(true, 0))
        h.go(FlowInput.ConsentsChanged(all, 0)); h.go(FlowInput.Power(true, 0)); h.go(FlowInput.Wrist(NORMAL, 0))
        assertEquals(S0, h.state)
        return h
    }

    // ── B-2 재경보 — NORMAL 10초 이어져야 지움, 마지막 NORMAL 뒤 나쁜 판정 8초 이어져야 재경보(두 경로 모두) ──

    /** 주행 중 WEAK → 4초에 응답(S1R). 그 뒤 2초마다 [after](응답 뒤 경과) 판정을 넣고, S1R 뒤 첫 재경보의 응답 뒤 경과(ms)를 돌려준다. */
    private fun realertAfter(endMs: Long = 590_000, after: (Long) -> WristVerdict): Long? {
        val h = armed()
        h.go(FlowInput.Wrist(PULSE_WEAK, 1_000)); assertEquals(S1, h.state)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 4_000)); assertEquals(S1R, h.state)
        var t = 4_000L
        while (t - 4_000 < endMs) {
            t += 2_000
            val out = h.go(FlowInput.Vehicle(moving, t)) + h.go(FlowInput.Wrist(after(t - 4_000), t)) + h.go(FlowInput.Tick(t))
            if (out.any { it is Directive.AlertStart }) return t - 4_000
        }
        return null
    }

    @Test fun weakWithNormalBlipEvery10sStillRealertsAt30s() {
        // 1차 재현 — WEAK 2초마다 + 10초마다 NORMAL 한 번. NORMAL 한 번이 시작 시각을 지워 600초 동안 재경보가 없었다
        val e = realertAfter { e -> if (e % 10_000 == 0L) NORMAL else PULSE_WEAK }
        assertTrue("30초 간격 뒤 마지막 NORMAL 8초에 재경보 — $e", e != null && e in 30_000..40_000)
    }

    @Test fun sustainedWeakRealertsAt30s() {
        assertEquals(30_000L, realertAfter { PULSE_WEAK })
    }

    @Test fun singleWeakAfter8sNormalDoesNotRealert() {
        // 레드 반례 — WEAK 26초 → NORMAL 8초 → WEAK 한 번 → NORMAL. 그 한 번에 곧바로 재경보하던 구멍
        assertNull(realertAfter { e -> if (e <= 26_000) PULSE_WEAK else if (e <= 34_000) NORMAL else if (e == 36_000L) PULSE_WEAK else NORMAL })
    }

    @Test fun mostlyNormalWithOneWeakEvery12sDoesNotRealert() {
        // NORMAL 10초 · WEAK 한 번 교대 — 휴식이 끝날 때까지 재경보 없음
        assertNull(realertAfter { e -> if ((e / 2_000) % 6 == 5L) PULSE_WEAK else NORMAL })
    }

    @Test fun realRecoveryDoesNotRealert() {
        assertNull(realertAfter { e -> if (e <= 20_000) PULSE_WEAK else NORMAL })
    }

    @Test fun tickPathAlsoWaits8sAfterLastNormal() {
        // 재경보 두 경로 중 타이머(realertDue) 경로 — 판정 없이 시간만 가도 마지막 NORMAL 뒤 8초가 차야 한다
        val h = armed()
        h.go(FlowInput.Wrist(PULSE_WEAK, 1_000))
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 4_000)) // 시작 시각 4초
        h.go(FlowInput.Wrist(NORMAL, 28_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 30_000))
        h.go(FlowInput.Tick(34_000)); assertEquals("간격 30초는 찼지만 마지막 NORMAL 뒤 6초", S1R, h.state)
        h.go(FlowInput.Tick(35_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Tick(36_000)); assertEquals("마지막 NORMAL 뒤 8초", S1, h.state)
    }

    @Test fun normalFor10sClearsWeakStart() {
        val h = armed()
        h.go(FlowInput.Wrist(PULSE_WEAK, 1_000))
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 4_000))
        h.go(FlowInput.Wrist(NORMAL, 10_000))
        h.go(FlowInput.Wrist(NORMAL, 19_999)); assertEquals(4_000L, h.m.snapshot().weakSinceMs)
        h.go(FlowInput.Wrist(NORMAL, 20_000)); assertNull(h.m.snapshot().weakSinceMs)
    }

    // ── B-4 정지 판정 — 속도 모름 + 기어 P = 정지, 시동 꺼짐 = 5초 지켜본 뒤 정지, 주차 브레이크만으로는 정지 아님 ──

    @Test fun stoppedRuleWithIgnitionAndWithoutParkingBrake() {
        assertTrue("속도 모름 + 기어 P", VehicleState(null, Gear.P).stopped())
        assertTrue("속도 모름 + 시동 꺼짐", VehicleState(null, Gear.D, ignitionOn = false).stopped())
        assertTrue("속도 모름 + 시동 꺼짐(기어 모름)", VehicleState(null, null, ignitionOn = false).stopped())
        assertFalse("속도 모름 + 시동 켜짐 + 기어 D", VehicleState(null, Gear.D, ignitionOn = true).stopped())
        assertFalse("속도 모름 + 시동 모름", VehicleState(null, Gear.D).stopped())
        assertFalse("주차 브레이크만 — 속도 모름", VehicleState(null, Gear.N, parkingBrake = true).stopped())
        assertFalse("주차 브레이크 + 3km/h(기어 D)", VehicleState(3.0, Gear.D, parkingBrake = true).stopped())
        assertFalse("주차 브레이크 + 0km/h(기어 N)", VehicleState(0.0, Gear.N, parkingBrake = true).stopped())
        assertTrue("기어 P + 3km/h 는 그대로", VehicleState(3.0, Gear.P).stopped())
        assertFalse("속도를 알면 시동 꺼짐만으로 정지 아님", VehicleState(10.0, Gear.D, ignitionOn = false).stopped())
    }

    private fun H.toS3() {
        go(FlowInput.Wrist(PULSE_WEAK, 1_000)); go(FlowInput.Tick(36_000)); assertEquals(S3, state)
    }

    @Test fun gearPWithNullSpeedReachesS4() {
        // be-qa r2gear — 속도 null + 기어 P 에서 S3 에 머물러 119 작성이 열리지 않았다
        val h = armed(); h.toS3()
        val p = VehicleState(null, Gear.P)
        h.go(FlowInput.Vehicle(p, 37_000)); h.go(FlowInput.Vehicle(p, 41_999)); assertEquals(S3, h.state)
        h.go(FlowInput.Vehicle(p, 42_000)); assertEquals("5초 디바운스 뒤 정지", S4, h.state)
        // 사건 중 첫 차량 입력이면 기어 P 는 확정 정지 — 바로
        val f = armed(vehicle = null); f.toS3()
        f.go(FlowInput.Vehicle(p, 37_000)); assertEquals(S4, f.state)
    }

    @Test fun ignitionOffWithNullSpeedWaits5s() {
        val off = VehicleState(null, null, ignitionOn = false)
        val h = armed(); h.toS3()
        h.go(FlowInput.Vehicle(off, 37_000)); h.go(FlowInput.Vehicle(off, 41_999)); assertEquals(S3, h.state)
        h.go(FlowInput.Vehicle(off, 42_000)); assertEquals(S4, h.state)
        // 사건 중 첫 입력이어도 시동 꺼짐은 확정 정지가 아니다 — 5초 지켜본다
        val f = armed(vehicle = null); f.toS3()
        f.go(FlowInput.Vehicle(off, 37_000)); assertEquals(S3, f.state)
        f.go(FlowInput.Vehicle(off, 42_000)); assertEquals(S4, f.state)
    }

    @Test fun nullSpeedWithKnownNonParkGearDoesNotKeepStopped() {
        // be-qa 지적 — 정지 → 경보 → (속도 null · 기어 D) 한 번 뒤 입력이 없으면 직전 정지를 유지해 S4 · 119 작성까지 갔다
        val h = armed(parked)
        h.go(FlowInput.Wrist(PULSE_WEAK, 1_000)); assertEquals(AP, h.state)
        h.go(FlowInput.Vehicle(VehicleState(null, Gear.D), 2_000))
        h.go(FlowInput.Tick(7_000)); assertEquals("기어 D 를 알면 정지를 유지하지 않음 — 5초 디바운스 뒤 주행", S1, h.state)
        h.go(FlowInput.Tick(36_000))
        assertEquals(S3, h.state)
        assertTrue(h.log.none { it is Directive.Open119Compose })
        // 기어도 모르면 지금처럼 한 번은 직전 정지를 유지한다
        val u = armed(parked)
        u.go(FlowInput.Wrist(PULSE_WEAK, 1_000))
        u.go(FlowInput.Vehicle(VehicleState(null, null), 2_000))
        u.go(FlowInput.Tick(7_000)); assertEquals(AP, u.state)
    }

    /**
     * PM 결정 24 — 상태 기계는 GPS 속도 없음을 정지로 보지 않는다(G5 「모름을 정지로 보지 않는다」 2026-11-01). 휴대폰이 스스로 GPS 로 본 정지 뒤
     * GPS 가 끊기면(터널 · 지하) 주행으로 돌아가, 그 상태의 경보는 S3 · 119 작성 없음이다. 넘겨받은 정지를 GPS 가 움직일 때까지 유지하는 것(B-4 끝 문장)은
     * 휴대폰 엔진 holdStopUntilMove 가 Vehicle 입력을 넣지 않는 것으로 맡는다(휴대폰 StrandedAlarmTakeOverTest.takenOverStoppedFlowStaysStoppedWithoutGps).
     */
    @Test fun ownGpsStopThenGpsLostIsNotHeldAsStop() {
        val gpsStill = VehicleState(0.3, speedSource = SpeedSource.PHONE_GPS)
        val gpsNone = VehicleState(null, speedSource = SpeedSource.PHONE_GPS)
        val h = armed(vehicle = null)
        h.go(FlowInput.Vehicle(gpsStill, 0)); h.go(FlowInput.Vehicle(gpsStill, 5_000))
        assertTrue("GPS 로 정지 확정", h.m.snapshot().stopped)
        for (t in 6_000L..20_000L step 1_000) h.go(FlowInput.Vehicle(gpsNone, t))
        assertFalse("GPS 끊김 — 정지 유지 안 함", h.m.snapshot().stopped)
        h.go(FlowInput.Wrist(PULSE_WEAK, 21_000)); assertEquals(S1, h.state)
        for (t in 22_000L..57_000L step 1_000) { h.go(FlowInput.Vehicle(gpsNone, t)); h.go(FlowInput.Tick(t)) }
        assertEquals("주행 중 경보 — S4 아님", S3, h.state)
        assertTrue("119 작성이 열리지 않음", h.log.none { it is Directive.Open119Compose })
    }

    @Test fun gpsMotionReleasesStopAndCarSourceUnknownIsMoving() {
        fun s4(): H {
            val car = armed(parked)
            car.go(FlowInput.Wrist(PULSE_WEAK, 1_000)); car.go(FlowInput.Tick(36_000)); assertEquals(S4, car.state)
            return H(FlowMachine.restore(car.m.snapshot().copy(rawStopped = null, stopPendingSinceMs = null, nullSpeedCount = 0)))
        }
        val m = s4()
        val gpsMoving = VehicleState(30.0, speedSource = SpeedSource.PHONE_GPS)
        m.go(FlowInput.Vehicle(gpsMoving, 37_000)); m.go(FlowInput.Vehicle(gpsMoving, 41_999)); assertEquals(S4, m.state)
        m.go(FlowInput.Vehicle(gpsMoving, 42_000)); assertEquals(S3, m.state)
        val c = s4()
        for (t in 37_000L..45_000L step 1_000) c.go(FlowInput.Vehicle(VehicleState(null, null), t))
        assertEquals("차 출처 — 속도를 계속 모르면 주행", S3, c.state)
    }

    @Test fun parkingBrakeAloneNeverStops() {
        for (v in listOf(VehicleState(0.0, Gear.N, parkingBrake = true), VehicleState(null, Gear.D, parkingBrake = true))) {
            val h = armed(); h.toS3()
            for (t in 37_000L..50_000L step 1_000) h.go(FlowInput.Vehicle(v, t))
            assertEquals("주차 브레이크만 $v", S3, h.state)
            val f = armed(vehicle = null); f.toS3()
            f.go(FlowInput.Vehicle(v, 37_000)); assertEquals("사건 중 첫 입력 주차 브레이크 $v — 즉시 전이 없음", S3, f.state)
        }
    }

    // ── B-6 감시 중(S0) 미착용 — 10초 이어져야 꺼짐 ──

    @Test fun s0UnwornNeeds10sToGoOff() {
        val h = armed()
        h.go(FlowInput.Wrist(CHECK_WEAR, 5_000)); assertEquals(S0, h.state)
        h.go(FlowInput.Wrist(CHECK_WEAR, 14_999)); assertEquals(S0, h.state)
        val d = h.go(FlowInput.Tick(15_000))
        assertEquals(OFF, h.state)
        assertTrue(Directive.Show(Phrase.SCREEN_CHECK_WEAR, false) in d)
        assertEquals("10초 동안 미착용 판정으로 사건이 열리지 않음", 0, h.alerts())
    }

    @Test fun s0UnwornFlickerKeepsMonitoring() {
        val h = armed()
        for (t in 1_000L..120_000L step 1_000) h.go(FlowInput.Wrist(if ((t / 1_000) % 10 == 0L) NORMAL else CHECK_WEAR, t))
        assertEquals("미착용 9초 · 정상 1초 깜빡임 — 감시 유지", S0, h.state)
        assertEquals(0, h.alerts())
        assertFalse(h.m.snapshot().incidentActive)
    }

    @Test fun restEndIntoS0WithShortUnwornWaits() {
        // enterS0 의 즉시 꺼짐도 같은 규칙 — 휴식이 끝날 때 미착용이 10초가 안 됐으면 S0 에서 마저 센다
        val h = armed()
        h.go(FlowInput.Wrist(PULSE_WEAK, 1_000))
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(NORMAL, 3_000))
        h.go(FlowInput.Wrist(CHECK_WEAR, 599_000))
        h.go(FlowInput.Tick(602_000)); assertEquals(S0, h.state)
        h.go(FlowInput.Tick(608_999)); assertEquals(S0, h.state)
        h.go(FlowInput.Tick(609_000)); assertEquals(OFF, h.state)
    }

    @Test fun unwornOffIsOneValueForS0AndAfterResponse() {
        assertEquals(10_000L, FlowParams().unwornOffMs)
    }

    // ── B-7 테스트 흐름 거두기 — test 흐름은 경보 상태에서도 WithdrawTest 로 S0, 실제 사건 · S5 는 어떤 경우에도 거두지 않음 ──

    @Test fun testFlowInAlertStatesIsWithdrawnToS0() {
        for (target in listOf(S1, AP, S3, S4)) {
            val h = armed(vehicle = if (target == AP || target == S4) parked else moving, test = true)
            h.go(FlowInput.Wrist(PULSE_WEAK, 1_000))
            if (target == S3 || target == S4) h.go(FlowInput.Tick(36_000))
            assertEquals(target, h.state)
            val at = if (target == S1 || target == AP) 10_000L else 40_000L
            // 앱이 매 주기 넣는 테스트 표시 끄기(워치 값 없음 포함)로는 경보 중 테스트 사건이 거둬지지 않는다
            h.go(FlowInput.TestMode(false, at - 1)); assertEquals(target, h.state)
            val d = h.go(FlowInput.WithdrawTest(at))
            assertEquals("테스트 $target 거두기", S0, h.state)
            assertTrue("테스트 경보를 멈춤", d.any { it is Directive.AlertStop && it.test })
            val snap = h.m.snapshot()
            assertFalse(snap.test); assertFalse(snap.incidentActive); assertFalse(snap.alerting)
            // 거둔 뒤 실제 나쁜 판정은 바로 실제 사건
            h.go(FlowInput.Wrist(NORMAL, at + 1_000))
            val n = h.go(FlowInput.Wrist(PULSE_WEAK, at + 2_000))
            assertFalse(n.filterIsInstance<Directive.AlertStart>().single().test)
        }
    }

    @Test fun realIncidentIsNeverWithdrawn() {
        for (target in listOf(S1, AP, S3, S4)) {
            val h = armed(vehicle = if (target == AP || target == S4) parked else moving)
            h.go(FlowInput.Wrist(PULSE_WEAK, 1_000))
            if (target == S3 || target == S4) h.go(FlowInput.Tick(36_000))
            assertEquals(target, h.state)
            val d = h.go(FlowInput.WithdrawTest(if (target == S1 || target == AP) 10_000L else 40_000L))
            assertEquals("실제 $target 는 거두지 않음", target, h.state)
            assertTrue(d.none { it is Directive.AlertStop })
            assertTrue(h.m.snapshot().incidentActive)
        }
    }

    @Test fun s5IsNeverWithdrawnEvenFromTestSession() {
        val h = armed(test = true)
        h.go(FlowInput.Wrist(PULSE_WEAK, 1_000))
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 2_000)); assertEquals(S5, h.state)
        h.go(FlowInput.WithdrawTest(3_000)); assertEquals(S5, h.state)
        h.go(FlowInput.TestMode(false, 3_500)); assertEquals(S5, h.state)
        h.go(FlowInput.TestMode(true, 4_000)); assertEquals(S5, h.state)
        assertFalse(h.m.snapshot().test)
    }
}
