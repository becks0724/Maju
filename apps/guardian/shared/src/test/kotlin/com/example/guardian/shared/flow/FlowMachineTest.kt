// 상태 기계 시험 — 전이표 각 행 · 타이머 경계 · 사건별 문자 1회 + 취소 1회 · 디바운스 · 주인 넘김 · 테스트 표시 · be-qa 재현
package com.example.guardian.shared.flow

import com.example.guardian.shared.bio.WristVerdict.CHECK_WEAR
import com.example.guardian.shared.bio.WristVerdict.NORMAL
import com.example.guardian.shared.bio.WristVerdict.PULSE_ABSENT
import com.example.guardian.shared.bio.WristVerdict.PULSE_WEAK
import com.example.guardian.shared.flow.FlowState.AP
import com.example.guardian.shared.flow.FlowState.OFF
import com.example.guardian.shared.flow.FlowState.S0
import com.example.guardian.shared.flow.FlowState.S1
import com.example.guardian.shared.flow.FlowState.S1R
import com.example.guardian.shared.flow.FlowState.S2
import com.example.guardian.shared.flow.FlowState.S3
import com.example.guardian.shared.flow.FlowState.S4
import com.example.guardian.shared.flow.FlowState.S5
import com.example.guardian.shared.protocol.DirectiveCommands
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowMachineTest {
    private val all = Consents(emergencyInfo = true, health = true, location = true)
    private val moving = VehicleState(speedKmh = 60.0, gear = Gear.D, parkingBrake = false)
    private val parked = VehicleState(speedKmh = 0.0, gear = Gear.P, parkingBrake = false)

    private class H(val m: FlowMachine = FlowMachine()) {
        val log = ArrayList<Directive>()
        fun go(i: FlowInput): List<Directive> = m.handle(i).also { log += it }
        val state get() = m.state
        fun sms(kind: GuardianSmsKind) = log.filterIsInstance<Directive.SendGuardianSms>().count { it.kind == kind }
        fun smsKinds() = log.filterIsInstance<Directive.SendGuardianSms>().map { it.kind }
        fun compose() = log.count { it is Directive.Open119Compose }
        /** 차량 입력 뒤 디바운스(5초)가 차도록 같은 값을 한 번 더 넣는다. */
        fun settle(v: VehicleState, at: Long) { go(FlowInput.Vehicle(v, at)); go(FlowInput.Vehicle(v, at + 5_000)) }
    }

    private fun armed(vehicle: VehicleState = moving, test: Boolean = false, params: FlowParams = FlowParams()): H {
        val h = H(FlowMachine(params))
        h.go(FlowInput.Vehicle(vehicle, 0))
        if (test) h.go(FlowInput.TestMode(true, 0))
        h.go(FlowInput.ConsentsChanged(all, 0))
        h.go(FlowInput.Power(true, 0))
        h.go(FlowInput.Wrist(NORMAL, 0))
        assertEquals(S0, h.state)
        return h
    }

    private fun H.toS1(at: Long = 1_000) { go(FlowInput.Wrist(PULSE_WEAK, at)); assertEquals(S1, state) }
    private fun H.toAP(at: Long = 1_000) { go(FlowInput.Wrist(PULSE_ABSENT, at)); assertEquals(AP, state) }

    // ── OFF ──

    @Test fun offNeedsAllConsentsPowerAndWorn() {
        val h = H()
        h.go(FlowInput.Power(true, 0)); h.go(FlowInput.Wrist(NORMAL, 0))
        assertEquals(OFF, h.state)
        h.go(FlowInput.ConsentsChanged(Consents(true, true, false), 0))
        assertEquals(OFF, h.state)
        h.go(FlowInput.ConsentsChanged(all, 0))
        assertEquals(S0, h.state)
    }

    @Test fun offWhenNotWorn() {
        val h = H()
        h.go(FlowInput.ConsentsChanged(all, 0)); h.go(FlowInput.Power(true, 0))
        h.go(FlowInput.Wrist(CHECK_WEAR, 0))
        assertEquals(OFF, h.state)
    }

    @Test fun s0CheckWearGoesOffWithWearNotice() {
        val h = armed()
        h.go(FlowInput.Wrist(CHECK_WEAR, 5_000))
        assertEquals("감시 중 미착용은 10초 이어져야 꺼짐(B-6)", S0, h.state)
        val d = h.go(FlowInput.Wrist(CHECK_WEAR, 15_000))
        assertEquals(OFF, h.state)
        assertTrue(Directive.Show(Phrase.SCREEN_CHECK_WEAR, false) in d)
    }

    @Test fun powerOffAndConsentWithdrawStopAlerts() {
        val h = armed(); h.toS1()
        assertTrue(h.go(FlowInput.Power(false, 2_000)).any { it is Directive.AlertStop })
        assertEquals(OFF, h.state)
        val h2 = armed(); h2.toS1()
        h2.go(FlowInput.ConsentsChanged(all.copy(health = false), 2_000))
        assertEquals(OFF, h2.state)
    }

    // ── S0 ──

    @Test fun s0WeakWhileDrivingGoesS1WithAlert1() {
        val h = armed()
        val d = h.go(FlowInput.Wrist(PULSE_WEAK, 1_000))
        assertEquals(S1, h.state)
        assertTrue(Directive.AlertStart(setOf(AlertTarget.WATCH_VIBRATION), S1, 1, 15_000, false) in d.map { if (it is Directive.AlertStart) it.copy(eventId = null) else it })
        assertTrue(Directive.Speak(Phrase.VOICE_S1_PULL_OVER, false) in d)
        assertTrue(Directive.Show(Phrase.SCREEN_S1, false) in d)
        assertFalse("앰비언트 기본 꺼짐", d.any { it is Directive.Ambient })
    }

    @Test fun s0AbsentWhileStoppedGoesAP() { armed(parked).toAP() }

    @Test fun stoppedNeedsPark() {
        assertTrue(VehicleState(2.0, Gear.P, false).stopped())
        assertFalse("주차 브레이크만으로는 정지 아님(B-4)", VehicleState(3.0, Gear.D, true).stopped())
        assertFalse(VehicleState(3.1, Gear.P, true).stopped())
        assertFalse(VehicleState(0.0, Gear.D, false).stopped())
        assertFalse("기어 모름 — 서행(1km/h)은 정지 아님", VehicleState(1.0, null, null).stopped())
        assertTrue("기어 모름 — 속도 0 만 정지", VehicleState(0.0, null, null).stopped())
        assertTrue("속도 모름 + 기어 P — 정지(B-4)", VehicleState(null, Gear.P, true).stopped())
        assertFalse("속도 모름 + 기어 D — 주행으로", VehicleState(null, Gear.D, true).stopped())
    }

    /** 경계 — 속도 0 · 1 · 3 · 4 × 기어 null · UNKNOWN · P · D(주차 브레이크 null). 기어를 모르면 0 만 정지(app-qa 참고 · 2026-10-30). */
    @Test fun stoppedBoundaryWhenGearUnknown() {
        val expected = mapOf(
            null to listOf(true, false, false, false),
            Gear.UNKNOWN to listOf(true, false, false, false),
            Gear.P to listOf(true, true, true, false),
            Gear.D to listOf(false, false, false, false),
        )
        for ((gear, want) in expected) for ((i, speed) in listOf(0.0, 1.0, 3.0, 4.0).withIndex()) {
            assertEquals("gear=$gear speed=$speed", want[i], VehicleState(speed, gear, null).stopped())
        }
        // 주차 브레이크는 보지 않는다 — 기어를 모르면 브레이크 값과 무관하게 속도 0 만 정지(B-4)
        assertFalse(VehicleState(3.0, null, true).stopped())
        assertTrue(VehicleState(0.0, null, false).stopped())
    }

    /** 휴대폰 GPS 출처 · 기어 · 브레이크 모름 — 1.0km/h 이하만 정지 후보(PM 결정 2026-10-30). 차 출처는 0 만. */
    @Test fun gpsStopToleranceBoundary() {
        fun gps(v: Double) = VehicleState(v, null, null, speedSource = SpeedSource.PHONE_GPS).stopped()
        for (v in listOf(0.0, 0.6, 1.0)) assertTrue("GPS $v", gps(v))
        for (v in listOf(1.1, 3.0)) assertFalse("GPS $v", gps(v))
        assertFalse("차 출처 · 기어 모름 0.6 은 주행", VehicleState(0.6, null, null, speedSource = SpeedSource.CAR).stopped())
        assertFalse("기본 출처는 차", VehicleState(0.6, null, null).stopped())
        // 기어를 알면 출처와 무관하게 기존 규칙
        assertTrue(VehicleState(3.0, Gear.P, null, speedSource = SpeedSource.PHONE_GPS).stopped())
        assertFalse(VehicleState(0.0, Gear.D, false, speedSource = SpeedSource.PHONE_GPS).stopped())
        assertEquals(1.0, VehicleState.GPS_STOP_KMH, 0.0)
    }

    /** GPS 1km/h 이하가 5초 이어져야 정지(AP). 4,999ms 는 아직 S1. */
    @Test fun gpsStopDebounceBoundary() {
        val gpsMove = VehicleState(30.0, null, null, speedSource = SpeedSource.PHONE_GPS)
        val h = armed(gpsMove); h.toS1(1_000)
        h.go(FlowInput.Vehicle(VehicleState(0.6, null, null, speedSource = SpeedSource.PHONE_GPS), 10_000))
        h.go(FlowInput.Vehicle(VehicleState(1.0, null, null, speedSource = SpeedSource.PHONE_GPS), 14_999)); assertEquals(S1, h.state)
        h.go(FlowInput.Vehicle(VehicleState(0.3, null, null, speedSource = SpeedSource.PHONE_GPS), 15_000)); assertEquals(AP, h.state)
        // 차 출처 0.6 은 같은 시간이 지나도 주행
        val c = armed(VehicleState(30.0, null, null)); c.toS1(1_000)
        c.go(FlowInput.Vehicle(VehicleState(0.6, null, null), 10_000))
        c.go(FlowInput.Vehicle(VehicleState(0.6, null, null), 20_000)); assertEquals(S1, c.state)
    }

    /** 기어를 못 읽는 차(또는 휴대폰 GPS)에서 정체 서행 1~3km/h 가 이어져도 정지 화면(AP)으로 가지 않는다. 0 이 5초 이어지면 간다. */
    @Test fun creepingWithoutGearIsNotStopped() {
        val h = armed(VehicleState(60.0, null, null))
        h.toS1()
        for (t in 2_000L..12_000L step 1_000) h.go(FlowInput.Vehicle(VehicleState(if (t % 2 == 0L) 1.0 else 3.0, null, null), t))
        assertEquals(S1, h.state)
        h.go(FlowInput.Vehicle(VehicleState(0.0, null, null), 13_000))
        h.go(FlowInput.Vehicle(VehicleState(0.0, null, null), 17_999))
        assertEquals("0 이 5초 안 됨", S1, h.state)
        h.go(FlowInput.Vehicle(VehicleState(0.0, null, null), 18_000))
        assertEquals(AP, h.state)
    }

    // ── 타이머 ──

    @Test fun s1Alert2AtExactly15sAndS3AtExactly35s() {
        val h = armed(); h.toS1(1_000)
        assertTrue(h.go(FlowInput.Tick(15_999)).isEmpty())
        val d2 = h.go(FlowInput.Tick(16_000))
        assertTrue(Directive.AlertStart(setOf(AlertTarget.WATCH_SOUND, AlertTarget.PHONE_ALARM), S1, 2, 20_000, false) in d2.map { if (it is Directive.AlertStart) it.copy(eventId = null) else it })
        h.go(FlowInput.Tick(35_999)); assertEquals(S1, h.state)
        h.go(FlowInput.Tick(36_000))
        assertEquals(S3, h.state)
        assertEquals(1, h.sms(GuardianSmsKind.INCIDENT))
        assertEquals(36_000L, h.m.snapshot().facts.noResponseAtMs)
    }

    @Test fun lateTickRunsBothStagesInOrder() {
        val h = armed(); h.toS1(0)
        val d = h.go(FlowInput.Tick(40_000))
        assertEquals(S3, h.state)
        assertTrue(d.indexOfFirst { it is Directive.AlertStart } in 0 until d.indexOfFirst { it is Directive.SendGuardianSms })
    }

    /** ALERT_START durationMs 는 단계 표시용 — 경보는 AlertStop 이 올 때까지 계속된다. */
    @Test fun alertContinuesPastDurationUntilStop() {
        val h = armed(); h.toS1(0)
        for (t in 1_000L..120_000L step 1_000) h.go(FlowInput.Tick(t))
        assertFalse(h.log.any { it is Directive.AlertStop })
        assertTrue(h.m.snapshot().alerting)
        assertTrue(h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 121_000)).any { it is Directive.AlertStop })
    }

    // ── S1 ──

    @Test fun s1ResponseGoesS1RWithoutCancelBecauseNoSmsYet() {
        val h = armed(); h.toS1()
        val d = h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 5_000))
        assertEquals(S1R, h.state)
        assertTrue(d.any { it is Directive.AlertStop })
        assertTrue(Directive.Speak(Phrase.VOICE_S1R_REST, false) in d)
        assertEquals(0, h.sms(GuardianSmsKind.CANCEL))
    }

    @Test fun phoneScreenResponseIgnoredWhileDriving() {
        val h = armed(); h.toS1()
        h.go(FlowInput.Response(ResponseSource.PHONE_SCREEN, 5_000))
        assertEquals(S1, h.state)
    }

    /** 상-1 — S1 에서 응답 없이 정지하면 AP 로 가되 첫 경보부터 35초에 S4. 판정이 NORMAL 로 돌아와도 멈추지 않는다. */
    @Test fun s1StopWithoutResponseGoesAPKeepingTimerEvenIfVerdictNormal() {
        val h = armed(); h.toS1(1_000)
        h.settle(parked, 10_000)
        assertEquals(AP, h.state)
        assertEquals(1_000L, h.m.snapshot().alertStartMs)
        assertFalse(h.log.any { it is Directive.AlertStop })
        var t = 15_000L
        while (t < 35_000) { t += 1_000; h.go(FlowInput.Wrist(NORMAL, t)) }
        assertEquals(AP, h.state)
        h.go(FlowInput.Wrist(NORMAL, 36_000))
        assertEquals(S4, h.state)
        assertEquals(1, h.sms(GuardianSmsKind.INCIDENT))
        assertEquals(1, h.compose())
        assertEquals(10_000L, h.m.snapshot().facts.stoppedAtMs)
    }

    /** be-qa A — S1 → 정지 → 워치 신호 끊김(Tick 만)이어도 S4 로 간다. */
    @Test fun qaA_watchLostAfterStopStillEscalates() {
        val h = armed(); h.go(FlowInput.Wrist(PULSE_ABSENT, 1_000))
        h.go(FlowInput.Vehicle(parked, 10_000))
        var t = 10_000L
        while (t < 3_600_000) { t += 2_000; h.go(FlowInput.Tick(t)) }
        assertEquals(S4, h.state)
        assertTrue(h.m.snapshot().alerting)
        assertEquals(listOf(GuardianSmsKind.INCIDENT), h.smsKinds())
        assertEquals(1, h.compose())
    }

    /** be-qa A2 — 정지 뒤 판정이 계속 NORMAL 이어도 응답이 없으면 S4. */
    @Test fun qaA2_normalVerdictAfterStopStillEscalates() {
        val h = armed(); h.toS1(1_000)
        h.go(FlowInput.Vehicle(parked, 10_000))
        var t = 10_000L
        while (t < 3_600_000) { t += 1_000; h.go(FlowInput.Wrist(NORMAL, t)) }
        assertEquals(S4, h.state)
        assertEquals(listOf(GuardianSmsKind.INCIDENT), h.smsKinds())
    }

    // ── 중-1 디바운스 · S1 ↔ AP ──

    @Test fun apToS1AndBackKeepsTimerAndStage() {
        val h = armed(parked); h.toAP(1_000)
        h.go(FlowInput.Tick(16_000)); assertEquals(2, h.m.snapshot().stage)
        h.settle(moving, 18_000)
        assertEquals(S1, h.state)
        assertEquals(2, h.m.snapshot().stage)
        assertEquals(1_000L, h.m.snapshot().alertStartMs)
        assertEquals("새 AlertStart 없이 이어 감", 2, h.log.count { it is Directive.AlertStart })
        h.go(FlowInput.Tick(36_000))
        assertEquals("주행 중 만료 → S3", S3, h.state)
        assertEquals(0, h.compose())
    }

    @Test fun stopDebounceBoundary() {
        val h = armed(); h.toS1(1_000)
        h.go(FlowInput.Vehicle(parked, 10_000))
        h.go(FlowInput.Tick(14_999)); assertEquals(S1, h.state)
        h.go(FlowInput.Tick(15_000)); assertEquals(AP, h.state)
    }

    /** be-qa C — GPS 속도가 정지(0) · 주행(4km/h) 사이에서 흔들려도 정지가 유지되고 첫 경보부터 35초에 S4. 기어를 모르면 0 만 정지(2026-10-30). */
    @Test fun qaC_gpsSpeedFlapDoesNotDelayEscalation() {
        val h = armed(VehicleState(0.0, null, null)); h.toAP(1_000)
        val gpsStop = VehicleState(0.0, null, null); val gpsMove = VehicleState(4.0, null, null)
        var t = 1_000L
        while (t < 600_000) {
            t += 3_000; h.go(FlowInput.Vehicle(gpsMove, t))
            t += 2_000; h.go(FlowInput.Vehicle(gpsStop, t))
            h.go(FlowInput.Wrist(PULSE_ABSENT, t))
        }
        assertEquals(S4, h.state)
        assertEquals(1, h.sms(GuardianSmsKind.INCIDENT))
        assertEquals(1, h.compose())
        assertFalse(h.log.any { it is Directive.Speak && it.phrase == Phrase.VOICE_S1_PULL_OVER })
    }

    /** be-qa C 변형 — 출처 PHONE_GPS 로 1.0(정지 후보) ↔ 4.0(주행) 을 오가도 정지가 유지되고 첫 경보부터 35초에 S4. */
    @Test fun qaC_gpsSourceFlapAtToleranceDoesNotDelayEscalation() {
        val gpsStop = VehicleState(1.0, null, null, speedSource = SpeedSource.PHONE_GPS)
        val gpsMove = VehicleState(4.0, null, null, speedSource = SpeedSource.PHONE_GPS)
        val h = armed(gpsStop); h.toAP(1_000)
        var t = 1_000L
        while (t < 600_000) {
            t += 3_000; h.go(FlowInput.Vehicle(gpsMove, t))
            t += 2_000; h.go(FlowInput.Vehicle(gpsStop, t))
            h.go(FlowInput.Wrist(PULSE_ABSENT, t))
        }
        assertEquals(S4, h.state)
        assertEquals(1, h.sms(GuardianSmsKind.INCIDENT))
        assertEquals(1, h.compose())
        assertFalse(h.log.any { it is Directive.Speak && it.phrase == Phrase.VOICE_S1_PULL_OVER })
    }

    /** be-qa C2 — 속도 null 한 번은 직전 판정 유지, 계속 null 이면 주행으로 본다. */
    @Test fun qaC2_nullSpeedOnceKeepsPrevious() {
        val h = armed(parked); h.toAP(1_000)
        val unknown = VehicleState(null, null, null)
        h.go(FlowInput.Vehicle(unknown, 20_000))
        h.go(FlowInput.Tick(30_000))
        assertEquals(AP, h.state)
        assertEquals(1_000L, h.m.snapshot().alertStartMs)
        h.go(FlowInput.Vehicle(unknown, 31_000)) // 두 번째 null → 주행으로 보고 디바운스 시작
        h.go(FlowInput.Tick(35_999)); assertEquals(AP, h.state)
        h.go(FlowInput.Tick(36_000))
        assertEquals("만료 때 주행 판정 → S3, 119 작성 없음", S3, h.state)
        assertEquals(0, h.compose())
    }

    // ── 중-2 S4 ↔ S3 ──

    /** be-qa D — S4 에서 움직이면 S3, 앰비언트는 INTERIOR_ALERT 로. */
    @Test fun qaD_s4MovingGoesS3() {
        val h = armed(parked, params = FlowParams(ambientEnabled = true)); h.toAP(1_000)
        h.go(FlowInput.Tick(40_000)); assertEquals(S4, h.state)
        h.go(FlowInput.Vehicle(moving, 50_000))
        h.go(FlowInput.Tick(60_000))
        assertEquals(S3, h.state)
        assertEquals(AmbientMode.INTERIOR_ALERT, h.m.snapshot().ambientMode)
        assertEquals(1, h.compose())
        assertEquals(1, h.sms(GuardianSmsKind.INCIDENT))
        // 다시 서면 S4 — 같은 사건이라 119 작성 · 문자 모두 다시 자동으로 내지 않는다(중-A)
        h.settle(parked, 70_000)
        assertEquals(S4, h.state)
        assertEquals(1, h.sms(GuardianSmsKind.INCIDENT))
        assertEquals(1, h.compose())
        assertEquals(1, h.log.count { it is Directive.OpenSearch })
    }

    /** be-qa H — AP 만료 시각에 주행 입력이 오면 119 작성 없이 S3. */
    @Test fun qaH_movingInputAtApExpiryGoesS3WithoutCompose() {
        val h = armed(parked); h.toAP(1_000)
        val d = h.go(FlowInput.Vehicle(VehicleState(40.0, Gear.D, false), 36_500))
        assertEquals(S3, h.state)
        assertFalse(d.any { it is Directive.Open119Compose })
        assertEquals(1, h.sms(GuardianSmsKind.INCIDENT))
    }

    @Test fun s3StopGoesS4WithoutSecondSms() {
        val h = armed(); h.toS1(0); h.go(FlowInput.Tick(35_000)); assertEquals(S3, h.state)
        h.settle(parked, 40_000)
        assertEquals(S4, h.state)
        assertEquals(1, h.sms(GuardianSmsKind.INCIDENT))
        assertEquals(1, h.compose())
    }

    @Test fun s3ResponseGoesS1RAndCancels() {
        val h = armed(); h.toS1(0); h.go(FlowInput.Tick(35_000))
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 40_000))
        assertEquals(S1R, h.state)
        assertEquals(1, h.sms(GuardianSmsKind.CANCEL))
    }

    @Test fun no119ComposeWhileMoving() {
        val h = armed(); h.toS1(0); h.go(FlowInput.Tick(35_000))
        assertEquals(0, h.compose())
    }

    // ── 중-3 새 사건 ──

    /** be-qa B — 취소 뒤 다시 나빠지면 새 사건: 새 사건 문자 · 새 eventId. */
    @Test fun qaB_retriggerAfterCancelIsNewIncident() {
        val h = armed(); h.toS1(1_000)
        h.go(FlowInput.Tick(36_000))
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 40_000))
        h.go(FlowInput.Wrist(PULSE_ABSENT, 60_000)); assertEquals(S1, h.state)
        h.go(FlowInput.Tick(100_000)); assertEquals(S3, h.state)
        h.settle(parked, 110_000); assertEquals(S4, h.state)
        assertEquals(listOf(GuardianSmsKind.INCIDENT, GuardianSmsKind.CANCEL, GuardianSmsKind.INCIDENT), h.smsKinds())
        val ids = h.log.filterIsInstance<Directive.SendGuardianSms>().map { DirectiveCommands.eventId(it) }
        assertEquals(listOf("event-L1000", "event-L1000", "event-L60000"), ids)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 120_000))
        assertEquals(2, h.sms(GuardianSmsKind.CANCEL))
    }

    @Test fun s4ResponseCancelsOnceAndGoesS2() {
        val h = armed(parked); h.toAP(0); h.go(FlowInput.Tick(35_000)); assertEquals(S4, h.state)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 40_000))
        assertEquals(S2, h.state)
        assertEquals(1, h.sms(GuardianSmsKind.CANCEL))
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 41_000))
        assertEquals("같은 사건 취소는 한 번", 1, h.sms(GuardianSmsKind.CANCEL))
    }

    // ── S1R · 중-4 ──

    @Test fun s1rStopGoesS2() {
        val h = armed(); h.toS1(); h.go(FlowInput.Response(ResponseSource.WATCH_MOTION, 2_000))
        h.settle(parked, 3_000)
        assertEquals(S2, h.state)
    }

    @Test fun s1rRestBoundaryTenMinutes() {
        val h = armed(); h.toS1(); h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000))
        h.go(FlowInput.Wrist(NORMAL, 3_000))
        h.go(FlowInput.Wrist(NORMAL, 2_000 + 599_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(NORMAL, 2_000 + 600_000)); assertEquals(S0, h.state)
    }

    @Test fun s1rWeakFor30sRealerts() {
        val h = armed(); h.toS1(); h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000))
        h.go(FlowInput.Wrist(NORMAL, 3_000)); h.go(FlowInput.Wrist(NORMAL, 13_000)) // NORMAL 10초 — 응답 때의 나쁜 판정 시작 시각을 지움(B-2)
        h.go(FlowInput.Wrist(PULSE_WEAK, 15_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 44_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 45_000)); assertEquals(S1, h.state)
    }

    @Test fun s1rWeakInterruptedByNormalResets() {
        // NORMAL 이 10초 이어져야 나쁜 판정 시작 시각을 지운다(B-2) — 한 번의 NORMAL 은 지우지 않는다(RedBlueFlowTest)
        val h = armed(); h.toS1(); h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000))
        h.go(FlowInput.Wrist(NORMAL, 3_000)); h.go(FlowInput.Wrist(NORMAL, 13_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 15_000))
        h.go(FlowInput.Wrist(NORMAL, 20_000)); h.go(FlowInput.Wrist(NORMAL, 30_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 32_000))
        h.go(FlowInput.Tick(61_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 62_000)); assertEquals(S1, h.state)
    }

    @Test fun s1rAbsentRealertsImmediately() {
        val h = armed(); h.toS1(); h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000))
        h.go(FlowInput.Wrist(PULSE_ABSENT, 4_000)); assertEquals(S1, h.state)
    }

    @Test fun s1rWeakAtResponseCountsFromResponse() {
        val h = armed(); h.toS1(1_000) // 판정 PULSE_WEAK 인 채 응답
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 31_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 32_000)); assertEquals(S1, h.state)
    }

    /** 리뷰 중 1 (가) — 응답 뒤 워치가 끊기면(30초) 재경보를 멈추고 「워치 신호 없음」을 한 번 낸다. 다시 신선한 나쁜 판정이 오면 그때부터 간격을 센다. */
    @Test fun s1rStaleWatchStopsRealertNoticesOnceAndRecountsWhenFresh() {
        val h = armed(); h.toS1(1_000) // 마지막 워치 값 1,000 · 판정 PULSE_WEAK
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000)) // weakSince = 2,000 → 재경보 예정 32,000
        assertTrue(h.go(FlowInput.Tick(31_000)).isEmpty()) // 경과 30,000 — 아직 신선
        val d = h.go(FlowInput.Tick(31_001)) // 경과 30,001 — 낡음
        assertEquals(S1R, h.state)
        assertTrue(Directive.Show(Phrase.SCREEN_WATCH_NO_SIGNAL, false) in d)
        assertNull("지속 시각을 비움", h.m.snapshot().weakSinceMs)
        assertTrue("예정 시각에 재경보 없음", h.go(FlowInput.Tick(32_000)).isEmpty()); assertEquals(S1R, h.state)
        assertTrue("안내는 한 번", h.go(FlowInput.Tick(60_000)).isEmpty())
        assertEquals(1, h.log.count { it is Directive.Show && it.phrase == Phrase.SCREEN_WATCH_NO_SIGNAL })
        // 다시 신선 + 나쁨 → 그 시각부터 30초
        h.go(FlowInput.Wrist(PULSE_WEAK, 100_000))
        assertEquals(100_000L, h.m.snapshot().weakSinceMs)
        h.go(FlowInput.Wrist(PULSE_WEAK, 129_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 130_000)); assertEquals(S1, h.state)
    }

    @Test fun s1rStaleWatchStillRestsToS0AfterTenMinutes() {
        val h = armed(); h.toS1(1_000); h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000))
        h.go(FlowInput.Tick(2_000 + 599_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Tick(2_000 + 600_000)); assertEquals(S0, h.state)
        assertEquals("재경보 없음", 1, h.log.count { it is Directive.AlertStart })
    }

    @Test fun s2StaleWatchStopsRealertAndRecountsWhenFresh() {
        val h = armed(parked); h.toAP(0) // PULSE_ABSENT · 워치 값 0
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 1_000)); assertEquals(S2, h.state) // weakSince = 1,000 → 예정 31,000
        val d = h.go(FlowInput.Tick(30_001))
        assertTrue(Directive.Show(Phrase.SCREEN_WATCH_NO_SIGNAL, false) in d)
        h.go(FlowInput.Tick(31_000)); assertEquals("낡은 채 재경보 없음", S2, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 40_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 69_999)); assertEquals(S2, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 70_000)); assertEquals(AP, h.state)
        assertEquals(1, h.log.count { it is Directive.Show && it.phrase == Phrase.SCREEN_WATCH_NO_SIGNAL })
    }

    // ── AP · S2 ──

    @Test fun apStage2AndS4AtBoundaries() {
        val h = armed(parked); h.toAP(1_000)
        assertTrue(h.go(FlowInput.Tick(15_999)).isEmpty())
        val d2 = h.go(FlowInput.Tick(16_000))
        assertTrue(Directive.Show(Phrase.SCREEN_AP, false) in d2)
        assertTrue(Directive.Speak(Phrase.VOICE_AP_CHECK, false) in d2)
        h.go(FlowInput.Tick(35_999)); assertEquals(AP, h.state)
        val d4 = h.go(FlowInput.Tick(36_000))
        assertEquals(S4, h.state)
        assertEquals(1, h.sms(GuardianSmsKind.INCIDENT))
        assertTrue(d4.any { it is Directive.Open119Compose && !it.previewOnly })
        assertTrue(Directive.OpenSearch(Phrase.SEARCH_EMERGENCY_ROOM, false) in d4)
    }

    @Test fun apResponseGoesS2() {
        val h = armed(parked); h.toAP()
        h.go(FlowInput.Response(ResponseSource.PHONE_SCREEN, 5_000))
        assertEquals(S2, h.state)
        assertEquals(5_000L, h.m.snapshot().facts.responseAtMs)
        assertFalse(h.m.snapshot().alerting)
    }

    // ── 공통 하1(코드 리뷰 2026-11-08, PM 결정) — 응답 뒤 미착용은 OFF + 착용 확인, S2 만료는 미착용 · 낡은 판정에서도 ──
    // 11-09 리뷰 공통 하1(PM 결정 (a)) — 응답 뒤 미착용은 10초 이어질 때만 OFF. 느슨한 착용의 깜박임 한 번으로 재경보 간격 · 휴식을 건너뛰지 않는다

    @Test fun s1rCheckWearGoesOffWithWearNotice() {
        val h = armed(); h.toS1(1_000)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(CHECK_WEAR, 5_000)); assertEquals("미착용 시작 — 아직 응답 뒤", S1R, h.state)
        h.go(FlowInput.Wrist(CHECK_WEAR, 14_999)); assertEquals(S1R, h.state)
        val d = h.go(FlowInput.Wrist(CHECK_WEAR, 15_000))
        assertEquals("10초 이어지면 꺼짐", OFF, h.state)
        assertTrue(Directive.Show(Phrase.SCREEN_CHECK_WEAR, false) in d)
        assertFalse(h.m.snapshot().incidentActive)
        assertEquals("새 경보 없음", 1, h.log.count { it is Directive.AlertStart })
        // 다시 차면 감시로
        h.go(FlowInput.Wrist(NORMAL, 17_000)); assertEquals(S0, h.state)
    }

    @Test fun s2CheckWearGoesOffWithWearNotice() {
        val h = armed(parked); h.toAP(0)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 1_000)); assertEquals(S2, h.state)
        h.go(FlowInput.Wrist(CHECK_WEAR, 3_000)); assertEquals(S2, h.state)
        val d = h.go(FlowInput.Tick(13_000))
        assertEquals("틱으로도 10초 지속을 본다", OFF, h.state)
        assertTrue(Directive.Show(Phrase.SCREEN_CHECK_WEAR, false) in d)
    }

    /** 리뷰어 재현(ReviewFlowReproTest) — S1R 에서 약해짐이 이어지는 중 미착용 한 번이 끼어도 재경보 단계(2분) · 휴식을 지킨다. */
    @Test fun checkWearBlipInS1RKeepsRealertInterval() {
        val h = armed()
        h.toS1(1_000); h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 32_000)); assertEquals("30초 지속 재경보", S1, h.state)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 33_000)); assertEquals(S1R, h.state)
        assertEquals(1, h.m.snapshot().realertLevel)
        h.go(FlowInput.Wrist(PULSE_WEAK, 40_000))
        h.go(FlowInput.Wrist(CHECK_WEAR, 41_000)); assertEquals("깜박임 한 번은 꺼짐 아님", S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 42_000)); assertEquals("42초에 새 사건이 열리면 안 됨", S1R, h.state)
        for (t in 52_000L..142_000L step 10_000) { h.go(FlowInput.Wrist(PULSE_WEAK, t)); assertEquals(S1R, h.state) }
        h.go(FlowInput.Wrist(PULSE_WEAK, 152_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 153_000)); assertEquals("33초 + 2분에 재경보", S1, h.state)
        assertEquals(3, h.log.count { it is Directive.AlertStart && it.level == 1 })
    }

    /** 미착용이 응답 전(경보 중)부터 이어졌으면 응답 뒤 다음 미착용 판정에서 바로 꺼진다 — 지속 시각은 판정 기준이다. */
    @Test fun checkWearSinceBeforeResponseCountsTowardOff() {
        val h = armed(); h.toS1(1_000)
        h.go(FlowInput.Wrist(CHECK_WEAR, 2_000)); assertEquals("경보 중 미착용은 경보를 멈추지 않음", S1, h.state)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 11_000)); assertEquals("아직 9초", S1R, h.state)
        h.go(FlowInput.Wrist(CHECK_WEAR, 12_500)); assertEquals("경보 중부터 10.5초", OFF, h.state)
    }

    /** S2 에서 워치가 끊긴 채(마지막 판정 PULSE_WEAK) 10분이 지나면 관찰을 끝낸다 — 「쉬는 중」이 끝없이 남지 않게. */
    @Test fun s2ObservationEndsWithStaleWatch() {
        val h = armed(parked); h.toAP(0)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 1_000)); assertEquals(S2, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 2_000))
        h.go(FlowInput.Tick(32_001)); assertEquals("낡음 — 재경보 없음", S2, h.state)
        h.go(FlowInput.Tick(600_999)); assertEquals(S2, h.state)
        h.go(FlowInput.Tick(601_000)); assertEquals(S0, h.state)
        assertEquals("재경보 없음", 1, h.log.count { it is Directive.AlertStart })
    }

    /** 신선한 약해짐이 이어지는 S2 는 만료로 끝나지 않는다 — 재경보 규칙(30초 → 새 사건)이 그대로 맡는다. */
    @Test fun s2FreshWeakStillRealertsNotExpires() {
        val h = armed(parked); h.toAP(0)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 1_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 30_999)); assertEquals(S2, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 31_000)); assertEquals(AP, h.state)
    }

    @Test fun s2ObservationTenMinutesWithNormal() {
        val h = armed(parked); h.toAP(0)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 1_000))
        h.go(FlowInput.Wrist(NORMAL, 2_000))
        h.go(FlowInput.Wrist(NORMAL, 600_999)); assertEquals(S2, h.state)
        h.go(FlowInput.Wrist(NORMAL, 601_000)); assertEquals(S0, h.state)
    }

    @Test fun s2NewAlertVerdictIsNewIncidentInAP() {
        val h = armed(parked); h.toAP(0)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 1_000))
        h.go(FlowInput.Wrist(NORMAL, 2_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 3_000))
        assertEquals("약해짐은 30초를 기다린다", S2, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 33_000))
        assertEquals(AP, h.state)
        assertEquals(33_000L, h.m.snapshot().facts.firstAlertAtMs)
    }

    /** 리뷰 중 2 — S2 에서 출발하면 S0 가 아니라 S1R(응답 뒤 휴식)로 가고, 10분 휴식은 출발 시각부터 센다. (옛 기대값 「바로 S0」을 고쳤다.) */
    @Test fun s2MovingGoesS1RThenRestsToS0() {
        val h = armed(parked); h.toAP(0)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 1_000))
        h.go(FlowInput.Wrist(NORMAL, 2_000))
        val d = h.go(FlowInput.Vehicle(moving, 3_000)) + h.go(FlowInput.Vehicle(moving, 8_000))
        assertEquals(S1R, h.state)
        assertTrue(Directive.Show(Phrase.SCREEN_S1R, false) in d)
        assertFalse("출발 때 음성 없음", d.any { it is Directive.Speak })
        assertEquals("응답 시각은 그대로", 1_000L, h.m.snapshot().facts.responseAtMs)
        h.go(FlowInput.Tick(8_000 + 599_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Tick(8_000 + 600_000)); assertEquals(S0, h.state)
    }

    /** 리뷰 중 2 — S2 에서 약한 판정 중 출발해도 바로 새 사건이 아니다. 재경보는 S2 에서 시작한 지속 시각 + 간격에만. */
    @Test fun s2MovingWithWeakVerdictKeepsRealertInterval() {
        val h = armed(parked); h.toAP(0)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 1_000)); assertEquals(S2, h.state) // weakSince = 1,000
        h.go(FlowInput.Wrist(PULSE_WEAK, 2_000))
        h.settle(moving, 3_000) // 8,000 에 출발 확정
        assertEquals(S1R, h.state)
        assertEquals("새 사건 없음", 1, h.log.count { it is Directive.AlertStart })
        assertEquals(1_000L, h.m.snapshot().weakSinceMs)
        h.go(FlowInput.Wrist(PULSE_WEAK, 30_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 31_000)); assertEquals("지속 30초에 재경보(주행 중 → S1)", S1, h.state)
        assertEquals(1, h.m.snapshot().realertLevel)
    }

    // ── 2차 — 중-A 119 작성 한 번 · 중-B S2 재경보 ──

    /** be-qa N1 — S4 ↔ S3 를 여러 번 오가도 119 작성 · 응급실 검색은 사건마다 한 번. 경보는 멈추지 않는다. */
    @Test fun qaN1_s4s3OscillationOpensComposeOnce() {
        val h = armed(parked, params = FlowParams(ambientEnabled = true)); h.toAP(1_000)
        var t = 1_000L
        while (t < 36_000) { t += 2_000; h.go(FlowInput.Vehicle(parked, t)) }
        assertEquals(S4, h.state)
        val states = mutableListOf<FlowState>()
        repeat(4) {
            repeat(4) { t += 2_000; h.go(FlowInput.Vehicle(moving, t)) }; states += h.state
            repeat(4) { t += 2_000; h.go(FlowInput.Vehicle(parked, t)) }; states += h.state
        }
        assertEquals(List(4) { listOf(S3, S4) }.flatten(), states)
        assertEquals(1, h.compose())
        assertEquals(1, h.log.count { it is Directive.OpenSearch })
        assertEquals(listOf(GuardianSmsKind.INCIDENT), h.smsKinds())
        assertTrue(h.m.snapshot().alerting)
        assertFalse(h.log.any { it is Directive.AlertStop })
    }

    @Test fun newIncidentOpensComposeAgain() {
        val h = armed(parked); h.toAP(1_000); h.go(FlowInput.Tick(36_000))
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 40_000))
        h.go(FlowInput.Wrist(NORMAL, 41_000)); h.go(FlowInput.Wrist(PULSE_ABSENT, 42_000)); assertEquals(AP, h.state)
        h.go(FlowInput.Tick(77_000)); assertEquals(S4, h.state)
        assertEquals(2, h.compose())
    }

    /** be-qa N2 — 응답한 뒤 S2 에서 PULSE_ABSENT 가 이어지면 30초에 새 사건으로 AP. */
    @Test fun qaN2_s2PersistentAbsentRealertsAfter30s() {
        val h = armed(parked); h.toAP(1_000)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 5_000)); assertEquals(S2, h.state)
        h.go(FlowInput.Wrist(PULSE_ABSENT, 34_999)); assertEquals(S2, h.state)
        h.go(FlowInput.Wrist(PULSE_ABSENT, 35_000)); assertEquals(AP, h.state)
        assertEquals(35_000L, h.m.snapshot().facts.firstAlertAtMs)
    }

    /** be-qa N2b — S1R 에서 약해짐이 시작된 뒤 정차해 S2 로 가도 30초는 이어서 센다. */
    @Test fun qaN2b_weakSinceCarriesFromS1RToS2() {
        val h = armed(); h.toS1(1_000)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 3_000)) // S1R, 약해짐 3,000 부터
        h.settle(parked, 10_000); assertEquals(S2, h.state)
        assertEquals(3_000L, h.m.snapshot().weakSinceMs)
        h.go(FlowInput.Wrist(PULSE_WEAK, 32_999)); assertEquals(S2, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 33_000)); assertEquals(AP, h.state)
    }

    @Test fun s2AbsentWorsenRealertsImmediately() {
        val h = armed(parked); h.toAP(1_000)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 5_000))
        h.go(FlowInput.Wrist(NORMAL, 6_000))
        h.go(FlowInput.Wrist(PULSE_ABSENT, 7_000)); assertEquals(AP, h.state)
    }

    /** be-qa N4 — 응답 · 전원 · 동의 입력 없이는 주행 중 경보가 멈추지 않는다(무작위 300회). */
    @Test fun qaN4_noAlertStopWithoutResponse() {
        val rnd = java.util.Random(7)
        repeat(300) {
            val h = armed(); h.go(FlowInput.Wrist(PULSE_ABSENT, 1_000))
            var t = 1_000L
            repeat(150) {
                t += rnd.nextInt(4_000)
                val i = when (rnd.nextInt(5)) {
                    0 -> FlowInput.Wrist(listOf(NORMAL, PULSE_WEAK, PULSE_ABSENT, CHECK_WEAR)[rnd.nextInt(4)], t, ageMs = if (rnd.nextBoolean()) 0 else 40_000)
                    1 -> FlowInput.Vehicle(listOf(moving, parked, VehicleState(null, null, null))[rnd.nextInt(3)], t)
                    3 -> FlowInput.Link(if (rnd.nextBoolean()) t else null, t)
                    else -> FlowInput.Tick(t)
                }
                assertFalse(h.go(i).any { it is Directive.AlertStop })
            }
            assertTrue(h.compose() <= 1)
        }
    }

    // ── 3차 — 재경보 간격 30초 → 2분 → 5분 ──

    /** be-qa 재현 — 판정이 굳은 채 매번 3초 뒤 응답. 예전 규칙(30초 고정)은 10분에 19회였고 이제 4회다. */
    @Test fun realertIntervalGrowsWhileVerdictStuck() {
        val h = H(FlowMachine())
        h.go(FlowInput.Vehicle(moving, 0)); h.go(FlowInput.ConsentsChanged(all, 0)); h.go(FlowInput.Power(true, 0))
        h.go(FlowInput.Wrist(NORMAL, 0))
        val alertStarts = ArrayList<Long>()
        var t = 0L
        var respondAt = Long.MAX_VALUE
        while (t < 601_000) {
            t += 1_000
            val d = h.go(FlowInput.Wrist(PULSE_WEAK, t))
            if (d.any { it is Directive.AlertStart && it.level == 1 }) { alertStarts += t; respondAt = t + 3_000 }
            if (t == respondAt) h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, t))
        }
        // 1,000 첫 경보 → 4,000 응답 → +30초 34,000 → 37,000 응답 → +2분 157,000 → 160,000 응답 → +5분 460,000 → 다음은 763,000
        assertEquals(listOf(1_000L, 34_000L, 157_000L, 460_000L), alertStarts)
        assertEquals(2, h.m.snapshot().realertLevel)
    }

    @Test fun realertIntervalBoundaries() {
        val h = armed(); h.toS1(1_000)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 4_000)) // 약해짐 4,000 부터, 단계 0(30초)
        h.go(FlowInput.Wrist(PULSE_WEAK, 33_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 34_000)); assertEquals(S1, h.state); assertEquals(1, h.m.snapshot().realertLevel)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 37_000)) // 단계 1(2분)
        h.go(FlowInput.Wrist(PULSE_WEAK, 156_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 157_000)); assertEquals(S1, h.state); assertEquals(2, h.m.snapshot().realertLevel)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 160_000)) // 단계 2(5분)
        h.go(FlowInput.Wrist(PULSE_WEAK, 459_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 460_000)); assertEquals(S1, h.state); assertEquals("5분 유지", 2, h.m.snapshot().realertLevel)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 463_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 762_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 763_000)); assertEquals(S1, h.state)
    }

    @Test fun realertLevelResetsAfter30sNormal() {
        val h = armed(); h.toS1(1_000)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 4_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 34_000)); h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 37_000))
        assertEquals(1, h.m.snapshot().realertLevel)
        h.go(FlowInput.Wrist(NORMAL, 40_000))
        h.go(FlowInput.Wrist(NORMAL, 69_999)); assertEquals(1, h.m.snapshot().realertLevel)
        h.go(FlowInput.Wrist(PULSE_WEAK, 69_999)) // 끊기면 다시 센다
        h.go(FlowInput.Wrist(NORMAL, 70_000)); h.go(FlowInput.Wrist(NORMAL, 99_999)); assertEquals(1, h.m.snapshot().realertLevel)
        h.go(FlowInput.Wrist(NORMAL, 100_000)); assertEquals(0, h.m.snapshot().realertLevel)
        h.go(FlowInput.Wrist(PULSE_WEAK, 101_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 130_999)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 131_000)); assertEquals("다시 30초", S1, h.state)
    }

    @Test fun absentWorsenIgnoresRealertLevel() {
        val h = armed(); h.toS1(1_000)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 4_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 34_000)); h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 37_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 40_000))
        h.go(FlowInput.Wrist(PULSE_ABSENT, 41_000)); assertEquals("나빠지면 즉시", S1, h.state)
    }

    @Test fun realertLevelSurvivesHandoff() {
        val a = armed(); a.toS1(1_000)
        a.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 4_000))
        a.go(FlowInput.Wrist(PULSE_WEAK, 34_000)); a.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 37_000))
        val b = H(FlowMachine.restore(a.m.snapshot()))
        assertEquals(1, b.m.snapshot().realertLevel)
        b.go(FlowInput.Wrist(PULSE_WEAK, 156_999)); assertEquals(S1R, b.state)
        b.go(FlowInput.Wrist(PULSE_WEAK, 157_000)); assertEquals(S1, b.state)
    }

    // ── S5 · 하-3 ──

    @Test fun airbagGoesS5AndExitResetsForNextAccident() {
        val h = armed(); h.toS1()
        assertTrue(h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 2_000)).any { it is Directive.AlertStop })
        assertEquals(S5, h.state)
        h.go(FlowInput.Wrist(PULSE_ABSENT, 2_500)); assertEquals("S5 는 손목 판정으로 움직이지 않음", S5, h.state)
        h.go(FlowInput.Wrist(NORMAL, 2_800))
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 3_000)); assertEquals(S0, h.state)
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = false), 3_500))
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 4_000))
        assertEquals(2, h.sms(GuardianSmsKind.ACCIDENT))
        val ids = h.log.filterIsInstance<Directive.SendGuardianSms>().map { DirectiveCommands.eventId(it) }
        assertEquals(listOf("accident-L2000", "accident-L4000"), ids)
    }

    /** 리뷰 중 3 — 에어백 null 은 「모름」. true → null → true 는 같은 사고(문자 1통), true → false → true 는 지금처럼 새 사고(2통). */
    @Test fun airbagNullIsUnknownAndKeepsPreviousFact() {
        val h = armed(); h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 2_000)); assertEquals(S5, h.state)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 3_000)); assertEquals(S0, h.state)
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = null), 3_500))
        assertTrue("모름은 직전 사실 유지", h.m.snapshot().airbag)
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 4_000))
        assertEquals("같은 사고 — S5 로 다시 가지 않음", S0, h.state)
        assertEquals(1, h.sms(GuardianSmsKind.ACCIDENT))
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = false), 4_500))
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 5_000))
        assertEquals("명시적 false 뒤 true 는 새 사고", S5, h.state)
        assertEquals(2, h.sms(GuardianSmsKind.ACCIDENT))
    }

    @Test fun airbagFromS0UsesFreshFacts() {
        val h = armed(); h.toS1(1_000); h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000))
        h.go(FlowInput.Wrist(NORMAL, 3_000)); h.go(FlowInput.Wrist(NORMAL, 602_000)); assertEquals(S0, h.state)
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 610_000))
        val f = h.log.filterIsInstance<Directive.SendGuardianSms>().single { it.kind == GuardianSmsKind.ACCIDENT }.facts
        assertNull(f.firstAlertAtMs)
        assertEquals(610_000L, f.accidentAtMs)
    }

    @Test fun airbagIgnoredWhenOff() {
        val h = H()
        h.go(FlowInput.Vehicle(parked.copy(airbagDeployed = true), 0))
        assertEquals(OFF, h.state)
    }

    // ── 테스트 표시 ──

    @Test fun testModeMarksEveryDirectiveAndPreviewOnly119() {
        val h = armed(parked, test = true); h.toAP(0); h.go(FlowInput.Tick(35_000))
        assertTrue(h.log.isNotEmpty() && h.log.all { it.test })
        assertTrue(h.log.filterIsInstance<Directive.Open119Compose>().single().previewOnly)
    }

    // ── 에어백은 늘 실제 사고(PM 결정 2026-10-30) ──

    @Test fun airbagInTestS0IsRealAccident() {
        val h = armed(test = true)
        val out = h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 2_000))
        assertEquals(S5, h.state)
        assertTrue(out.isNotEmpty() && out.none { it.test })
        val sms = out.filterIsInstance<Directive.SendGuardianSms>().single()
        assertEquals(GuardianSmsKind.ACCIDENT, sms.kind)
        assertEquals("accident-L2000", DirectiveCommands.eventId(sms))
        assertFalse(h.m.snapshot().test)
    }

    @Test fun airbagDuringTestIncidentEndsItAndStartsRealAccident() {
        fun s1(h: H) = h.toS1(1_000)
        fun s3(h: H) { h.toS1(1_000); h.go(FlowInput.Tick(36_000)); assertEquals(S3, h.state) }
        fun s4(h: H) { h.toAP(1_000); h.go(FlowInput.Tick(36_000)); assertEquals(S4, h.state) }
        for ((name, setup) in listOf<Pair<String, (H) -> Unit>>(
            "S1" to ::s1, "S3" to ::s3, "S4" to ::s4,
        )) {
            val h = if (name == "S4") armed(parked, test = true) else armed(test = true)
            setup(h)
            assertTrue(name, h.log.all { it.test })
            val v = if (name == "S4") parked else moving
            val out = h.go(FlowInput.Vehicle(v.copy(airbagDeployed = true), 40_000))
            assertEquals(name, S5, h.state)
            // 테스트 경보 정리(테스트 표시) 뒤 실제 사고 지시만
            val stop = out.indexOfFirst { it is Directive.AlertStop }
            assertTrue(name, stop >= 0 && out[stop].test)
            val rest = out.drop(stop + 1)
            assertTrue(name, rest.isNotEmpty() && rest.none { it.test })
            val sms = rest.filterIsInstance<Directive.SendGuardianSms>().single()
            assertEquals(name, GuardianSmsKind.ACCIDENT, sms.kind)
            assertEquals(name, "accident-L40000", DirectiveCommands.eventId(sms))
            // 테스트 사건의 사실을 실제 사고에 섞지 않는다
            assertNull(name, sms.facts.firstAlertAtMs)
            assertNull(name, sms.facts.noResponseAtMs)
            assertEquals(name, 40_000L, sms.facts.accidentAtMs)
            assertFalse(name, h.m.snapshot().test)
        }
    }

    @Test fun testModeIgnoredDuringRealAccidentUntilItEnds() {
        val h = armed(test = true)
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 2_000))
        assertTrue(h.go(FlowInput.TestMode(true, 3_000)).none { it.test })
        assertFalse(h.m.snapshot().test)
        h.go(FlowInput.Tick(60_000)); assertEquals(S5, h.state)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 61_000)); assertEquals(S0, h.state)
        assertTrue(h.log.filter { it is Directive.SendGuardianSms }.none { it.test })
        h.go(FlowInput.TestMode(true, 62_000))
        assertTrue("사고가 끝난 뒤에는 다시 켤 수 있다", h.m.snapshot().test)
    }

    @Test fun realS3AirbagCarriesFactsAndKeepsIncident() {
        val h = armed(); h.toS1(1_000); h.go(FlowInput.Tick(36_000)); assertEquals(S3, h.state)
        val out = h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 40_000))
        assertTrue(out.none { it.test })
        val f = out.filterIsInstance<Directive.SendGuardianSms>().single().facts
        assertEquals(1_000L, f.firstAlertAtMs); assertEquals(36_000L, f.noResponseAtMs); assertEquals(40_000L, f.accidentAtMs)
        assertEquals("accident-L40000", f.eventId)
        assertTrue(h.m.snapshot().incidentActive)
    }

    @Test fun realS1RAirbagCarriesResponse() {
        val h = armed(); h.toS1(1_000); h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000)); assertEquals(S1R, h.state)
        val f = h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 5_000))
            .filterIsInstance<Directive.SendGuardianSms>().single().facts
        assertEquals(1_000L, f.firstAlertAtMs); assertEquals(2_000L, f.responseAtMs)
    }

    @Test fun testS1RAirbagHasNoAlertStopNoCancelAllReal() {
        val h = armed(test = true); h.toS1(1_000); h.go(FlowInput.Tick(36_000)); assertEquals(S3, h.state)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 37_000)); assertEquals(S1R, h.state)
        assertTrue(h.log.all { it.test })
        val out = h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 40_000))
        assertEquals(S5, h.state)
        assertTrue(out.isNotEmpty() && out.none { it.test })
        assertTrue(out.none { it is Directive.AlertStop })
        assertTrue(out.none { it is Directive.SendGuardianSms && it.kind == GuardianSmsKind.CANCEL })
        val f = out.filterIsInstance<Directive.SendGuardianSms>().single().facts
        assertNull(f.firstAlertAtMs); assertNull(f.noResponseAtMs); assertNull(f.responseAtMs); assertNull(f.stoppedAtMs)
        assertEquals("accident-L40000", f.eventId)
        assertFalse(h.m.snapshot().test); assertFalse(h.m.snapshot().incidentActive)
    }

    @Test fun testS2WithAmbientAirbagTurnsAmbientOffAsTestThenAllReal() {
        val h = armed(parked, test = true, params = FlowParams(ambientEnabled = true))
        h.toAP(1_000); h.go(FlowInput.Tick(36_000)); assertEquals(S4, h.state)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 37_000)); assertEquals(S2, h.state)
        assertTrue(h.log.all { it.test })
        val out = h.go(FlowInput.Vehicle(parked.copy(airbagDeployed = true), 40_000))
        assertEquals(S5, h.state)
        assertEquals(Directive.Ambient(null, true), out.first())
        assertTrue(out.none { it is Directive.AlertStop })
        val rest = out.drop(1)
        assertTrue(rest.isNotEmpty() && rest.none { it.test })
        val f = rest.filterIsInstance<Directive.SendGuardianSms>().single().facts
        assertNull(f.firstAlertAtMs); assertNull(f.responseAtMs); assertNull(f.stoppedAtMs)
    }

    @Test fun powerCycleAfterAccidentAllowsNextAccidentSms() {
        val h = armed(); h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 2_000)); assertEquals(S5, h.state)
        h.go(FlowInput.Power(false, 3_000)); h.go(FlowInput.Power(true, 4_000)); h.go(FlowInput.Wrist(NORMAL, 4_000))
        assertEquals(S0, h.state)
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = false), 5_000))
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 6_000)); assertEquals(S5, h.state)
        assertEquals("전원 껐다 켠 뒤 새 사고(false → true)는 새 문자", 2, h.sms(GuardianSmsKind.ACCIDENT))
    }

    @Test fun powerCycleWhileAirbagStaysDeployedSendsNoSecondSms() {
        val h = armed(); h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 2_000)); assertEquals(S5, h.state)
        h.go(FlowInput.Power(false, 3_000)); h.go(FlowInput.Power(true, 4_000)); h.go(FlowInput.Wrist(NORMAL, 4_000))
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 5_000))
        h.go(FlowInput.Vehicle(moving.copy(airbagDeployed = true), 6_000))
        assertEquals("에어백이 계속 true 면 같은 사고 — 문자 한 건", 1, h.sms(GuardianSmsKind.ACCIDENT))
        assertEquals(S0, h.state)
    }

    @Test fun testModeCannotFlipMidIncident() {
        val h = armed(); h.toS1()
        h.go(FlowInput.TestMode(true, 2_000))
        assertFalse(h.m.snapshot().test)
    }

    // ── 테스트 세션 종료 — 응답 뒤(S1R · S2)에서도 표시가 바로 꺼진다(실주행 3 새 결함, 사용자 지시 2026-11-07) ──

    @Test fun testOffDuringTestS1RReturnsToS0AndNextRealAlertIsImmediate() {
        val h = armed(test = true); h.toS1()
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 5_000)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(NORMAL, 6_000))
        h.go(FlowInput.TestMode(false, 7_000))
        assertEquals("세션이 끝나면 휴식을 기다리지 않고 S0", S0, h.state)
        assertFalse(h.m.snapshot().test)
        assertFalse(h.m.snapshot().incidentActive)
        // 테스트의 10분 휴식이 실제 경보를 늦추지 않는다 — 다음 나쁜 판정은 바로 새 실제 사건
        val d = h.go(FlowInput.Wrist(PULSE_WEAK, 8_000))
        assertEquals(S1, h.state)
        assertFalse(d.filterIsInstance<Directive.AlertStart>().single().test)
    }

    @Test fun testOffDuringTestS2ReturnsToS0() {
        val h = armed(vehicle = parked, test = true); h.toAP()
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 5_000)); assertEquals(S2, h.state)
        h.go(FlowInput.TestMode(false, 6_000))
        assertEquals(S0, h.state)
        assertFalse(h.m.snapshot().test)
        // 정차 관찰 10분 · 재경보 30초가 남지 않는다 — 바로 다음 PULSE_ABSENT 는 새 실제 사건
        val d = h.go(FlowInput.Wrist(PULSE_ABSENT, 7_000))
        assertEquals(AP, h.state)
        assertFalse(d.filterIsInstance<Directive.AlertStart>().single().test)
    }

    /** 세션이 끝날 때 남아 있던 판정은 시뮬레이션 값일 수 있다 — 그것으로 실제 경보를 열지 않고, 다음에 들어오는 실제 판정이 정한다. */
    @Test fun testOffDoesNotAlertFromCarriedSimulatedVerdict() {
        val h = armed(test = true)
        h.go(FlowInput.Wrist(PULSE_ABSENT, 1_000)); assertEquals(S1, h.state)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 5_000)); assertEquals(S1R, h.state)
        val d = h.go(FlowInput.TestMode(false, 6_000))
        assertEquals(S0, h.state)
        assertTrue("남은 시뮬레이션 판정으로 경보를 열지 않는다", d.none { it is Directive.AlertStart })
        h.go(FlowInput.Wrist(NORMAL, 7_000)); assertEquals(S0, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 8_000)); assertEquals(S1, h.state)
        assertFalse(h.m.snapshot().test)
    }

    /** 세션 안 재경보는 테스트로 나오고, 세션이 끝나면 테스트에서 올라간 재경보 단계가 실제 사건으로 넘어오지 않는다. */
    @Test fun testOffResetsRealertLevelSoRealRealertIsNot2Minutes() {
        val h = armed(test = true); h.toS1()
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 5_000)); assertEquals(S1R, h.state)
        val d = h.go(FlowInput.Wrist(PULSE_WEAK, 35_000)) // 신선한 나쁜 판정 30초 지속 → 재경보(단계 0 → 1)
        assertEquals(S1, h.state)
        assertTrue("세션 안 재경보는 테스트", d.filterIsInstance<Directive.AlertStart>().single().test)
        assertEquals(1, h.m.snapshot().realertLevel)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 36_000)); assertEquals(S1R, h.state)
        h.go(FlowInput.TestMode(false, 37_000))
        assertEquals(S0, h.state)
        assertEquals(0, h.m.snapshot().realertLevel)
        // 실제 사건 — 응답 뒤 나쁜 판정이 이어지면 2분이 아니라 30초에 다시 경보
        h.go(FlowInput.Wrist(PULSE_WEAK, 38_000)); assertEquals(S1, h.state)
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 39_000)); assertEquals(S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 68_000)); assertEquals("30초 전에는 재경보 없음", S1R, h.state)
        h.go(FlowInput.Wrist(PULSE_WEAK, 69_000))
        assertEquals(S1, h.state)
        assertFalse(h.m.snapshot().test)
    }

    /** 실제 사건의 휴식은 테스트 종료로 지워지지 않고, 휴식 중 테스트 켜기는 여전히 받지 않는다. */
    @Test fun testOffIsNoOpInRealRestAndTestOnStillIgnoredInRest() {
        val h = armed(); h.toS1()
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 5_000)); assertEquals(S1R, h.state)
        h.go(FlowInput.TestMode(false, 6_000))
        assertEquals(S1R, h.state)
        h.go(FlowInput.TestMode(true, 7_000))
        assertEquals(S1R, h.state)
        assertFalse(h.m.snapshot().test)
        val t = armed(test = true); t.toS1()
        t.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 5_000)); assertEquals(S1R, t.state)
        t.go(FlowInput.TestMode(true, 6_000))
        assertEquals("켜기는 상태를 바꾸지 않는다", S1R, t.state)
        assertTrue(t.m.snapshot().test)
    }

    // ── 주인 넘김 ──

    @Test fun ownershipBoundary() {
        assertEquals(Owner.CAR, Ownership.decide(20_000, 10_000))
        assertEquals(Owner.PHONE, Ownership.decide(20_001, 10_000))
        assertEquals(Owner.PHONE, Ownership.decide(20_000, null))
    }

    @Test fun linkMarksCarStateMissingInFacts() {
        val h = armed()
        h.go(FlowInput.Link(carLastSyncAtMs = 0, atMs = 11_000))
        h.go(FlowInput.Wrist(PULSE_WEAK, 11_000))
        assertTrue(h.m.snapshot().facts.carStateMissing)
    }

    @Test fun snapshotRestoreContinuesIdentically() {
        val inputs = listOf<FlowInput>(
            FlowInput.Tick(16_000), FlowInput.Vehicle(parked, 20_000), FlowInput.Tick(24_000), FlowInput.Tick(36_000),
            FlowInput.Response(ResponseSource.CAR_BUTTON, 50_000), FlowInput.Wrist(NORMAL, 51_000), FlowInput.Wrist(NORMAL, 700_000),
        )
        val a = armed(); a.toS1(1_000)
        a.go(FlowInput.Vehicle(parked, 12_000)) // 디바운스 중에 넘긴다
        val b = H(FlowMachine.restore(a.m.snapshot()))
        for (i in inputs) assertEquals(a.go(i), b.go(i))
        assertEquals(a.m.snapshot(), b.m.snapshot())
        assertEquals(S0, b.state)
    }

    // ── 앰비언트 ──

    @Test fun ambientInteriorWhileDrivingParkedRescueAfterStop() {
        val h = armed(params = FlowParams(ambientEnabled = true))
        assertTrue(Directive.Ambient(AmbientMode.INTERIOR_ALERT, false) in h.go(FlowInput.Wrist(PULSE_WEAK, 1_000)))
        h.go(FlowInput.Tick(36_000)); assertEquals(S3, h.state)
        h.settle(parked, 37_000); assertEquals(S4, h.state)
        assertEquals(AmbientMode.PARKED_RESCUE, h.m.snapshot().ambientMode)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 43_000)); assertEquals(S2, h.state)
        h.go(FlowInput.Wrist(NORMAL, 43_500)); h.go(FlowInput.Wrist(NORMAL, 643_000))
        assertEquals(S0, h.state)
        assertEquals(Directive.Ambient(null, false), h.log.filterIsInstance<Directive.Ambient>().last())
    }

    @Test fun ambientOffByDefault() {
        val h = armed(); h.toS1(0); h.go(FlowInput.Tick(36_000)); h.settle(parked, 37_000)
        assertFalse(h.log.any { it is Directive.Ambient })
    }

    /** 법률 검토 전 금지 모드는 어떤 경로에서도 나오지 않는다(설계 §13 · G6). */
    @Test fun neverBlueRedBlink() {
        val inputs = listOf<(Long) -> FlowInput>(
            { FlowInput.Wrist(PULSE_WEAK, it) }, { FlowInput.Wrist(PULSE_ABSENT, it) }, { FlowInput.Wrist(NORMAL, it) },
            { FlowInput.Vehicle(parked, it) }, { FlowInput.Vehicle(moving, it) }, { FlowInput.Tick(it) },
            { FlowInput.Response(ResponseSource.CAR_BUTTON, it) }, { FlowInput.Vehicle(parked.copy(airbagDeployed = true), it) },
            { FlowInput.Vehicle(VehicleState(null, null, null), it) },
        )
        val rnd = java.util.Random(3)
        repeat(50) {
            val h = armed(params = FlowParams(ambientEnabled = true))
            var t = 1_000L
            repeat(200) {
                t += rnd.nextInt(20_000)
                val d = h.go(inputs[rnd.nextInt(inputs.size)](t))
                if (d.any { it is Directive.Open119Compose }) assertTrue("119 작성은 안정 정지에서만", h.m.snapshot().stopped && h.m.snapshot().stopPendingSinceMs == null)
            }
            assertFalse(h.log.any { it is Directive.Ambient && it.mode == AmbientMode.BLUE_RED_BLINK })
        }
    }

    // ── 워치 신선도 ──

    @Test fun staleWatchInS0ShowsNoticeOnceAndStays() {
        val h = armed()
        assertTrue(h.go(FlowInput.Tick(30_000)).isEmpty())
        val d = h.go(FlowInput.Tick(30_001))
        assertEquals(S0, h.state)
        assertTrue(Directive.Show(Phrase.SCREEN_WATCH_NO_SIGNAL, false) in d)
        assertTrue("안내는 한 번", h.go(FlowInput.Tick(40_000)).isEmpty())
        h.go(FlowInput.Wrist(NORMAL, 41_000))
        assertTrue(Directive.Show(Phrase.SCREEN_WATCH_NO_SIGNAL, false) in h.go(FlowInput.Tick(71_001)))
    }

    @Test fun staleVerdictIsNotUsedAsInput() {
        val h = armed()
        h.go(FlowInput.Wrist(PULSE_ABSENT, atMs = 50_000, ageMs = 30_001))
        assertEquals(S0, h.state)
        assertFalse(h.log.any { it is Directive.AlertStart })
        h.go(FlowInput.Wrist(PULSE_ABSENT, atMs = 50_000, ageMs = 30_000))
        assertEquals("경계 30초는 신선", S1, h.state)
    }

    @Test fun olderWatchValueIgnored() {
        val h = armed()
        h.go(FlowInput.Wrist(NORMAL, atMs = 10_000, ageMs = 0))
        h.go(FlowInput.Wrist(PULSE_ABSENT, atMs = 10_500, ageMs = 1_500))
        assertEquals(S0, h.state)
    }

    @Test fun offNeedsFreshWatch() {
        val h = H()
        h.go(FlowInput.ConsentsChanged(all, 0)); h.go(FlowInput.Power(true, 0))
        h.go(FlowInput.Wrist(NORMAL, atMs = 40_000, ageMs = 35_000))
        assertEquals(OFF, h.state)
    }

    @Test fun freshnessBothPaths() {
        assertEquals(31_000L, WatchFreshness.viaRelay(20_000, responseReceivedAtLocalMs = 100_000, nowLocalMs = 111_000))
        assertTrue(WatchFreshness.isStale(WatchFreshness.viaRelay(20_000, 100_000, 111_000)))
        assertFalse(WatchFreshness.isStale(WatchFreshness.viaRelay(20_000, 100_000, 110_000)))
        assertFalse(WatchFreshness.isStale(WatchFreshness.viaDataLayer(receivedAtLocalMs = 5_000, nowLocalMs = 35_000)))
        assertTrue(WatchFreshness.isStale(WatchFreshness.viaDataLayer(5_000, 35_001)))
    }

    @Test fun staleWatchDuringAlertKeepsAlerting() {
        val h = armed(); h.toS1(1_000)
        h.go(FlowInput.Tick(36_000))
        assertEquals("경보 중에는 신호가 끊겨도 계속", S3, h.state)
    }

    // ── 2026-11-08 코드 리뷰 2차 ──

    /** 11-02 남은 하(PM 결정 응답 우선) — 만료와 같은 ms 의 응답은 응답을 먼저 반영한다. INCIDENT 와 CANCEL 이 한 번에 나가지 않는다. */
    @Test fun responseAtSameMsAsExpiryWinsOverNoResponse() {
        val h = armed(); h.toS1(1_000)
        h.go(FlowInput.Tick(35_999))
        h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 36_000))
        assertEquals(S1R, h.state)
        assertEquals(emptyList<GuardianSmsKind>(), h.smsKinds())
        // 경보 2 시작과 같은 ms 의 응답도 소리 경보를 열지 않는다
        val h2 = armed(); h2.toS1(1_000)
        h2.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 16_000))
        assertEquals(S1R, h2.state)
        assertFalse(h2.log.any { it is Directive.AlertStart && it.level == 2 })
        // 받지 않는 응답(주행 중 휴대폰 화면)이면 같은 ms 라도 만료는 그대로 난다
        val h3 = armed(); h3.toS1(1_000)
        h3.go(FlowInput.Response(ResponseSource.PHONE_SCREEN, 36_000))
        assertEquals(S3, h3.state)
        assertEquals(listOf(GuardianSmsKind.INCIDENT), h3.smsKinds())
    }

    /** 11-02 남은 하(PM 결정) — 사건 도중 첫 차량 입력은 stopped 만 바꾸지 않고 그 값으로 정차 전이를 낸다. */
    @Test fun firstVehicleInputDuringIncidentTransitions() {
        val h = H()
        h.go(FlowInput.ConsentsChanged(all, 0)); h.go(FlowInput.Power(true, 0)); h.go(FlowInput.Wrist(NORMAL, 0))
        h.go(FlowInput.Wrist(PULSE_WEAK, 1_000)); assertEquals("차량 입력 없음 = 주행으로 봄", S1, h.state)
        h.go(FlowInput.Vehicle(parked, 2_000))
        assertEquals(AP, h.state)
        assertEquals(2_000L, h.m.snapshot().facts.stoppedAtMs)
        assertEquals("경보 타이머는 이어 간다", 1_000L, h.m.snapshot().alertStartMs)
        // PM 결정 — 속도만으로 정지(기어 · 주차 브레이크 모름)인 첫 입력은 5초 디바운스: 무응답 S3 에서 5초 전에 S4(119 작성)로 가지 않는다
        val speedOnly = VehicleState(speedKmh = 0.0)
        val g = H()
        g.go(FlowInput.ConsentsChanged(all, 0)); g.go(FlowInput.Power(true, 0)); g.go(FlowInput.Wrist(NORMAL, 0))
        g.go(FlowInput.Wrist(PULSE_WEAK, 1_000)); g.go(FlowInput.Tick(36_000)); assertEquals(S3, g.state)
        g.go(FlowInput.Vehicle(speedOnly, 37_000)); assertEquals("속도만 — 즉시 아님", S3, g.state)
        g.go(FlowInput.Vehicle(speedOnly, 41_999)); assertEquals(S3, g.state)
        assertEquals("5초 전에는 119 작성 없음", 0, g.compose())
        g.go(FlowInput.Vehicle(speedOnly, 42_000)); assertEquals("5초 뒤", S4, g.state)
        // 기어 P 첫 입력은 즉시
        val p = H()
        p.go(FlowInput.ConsentsChanged(all, 0)); p.go(FlowInput.Power(true, 0)); p.go(FlowInput.Wrist(NORMAL, 0))
        p.go(FlowInput.Wrist(PULSE_WEAK, 1_000)); p.go(FlowInput.Tick(36_000))
        p.go(FlowInput.Vehicle(parked, 37_000)); assertEquals("기어 P — 즉시", S4, p.state)
        // 원신호 없이 정지가 실려 온 스냅숏(S4)의 첫 주행 입력은 디바운스를 거친다 — 복원한 정차가 한 입력으로 뒤집히지 않게
        val restored = H(FlowMachine(initial = h.m.snapshot().copy(state = S4, rawStopped = null, stopped = true, stopPendingSinceMs = null)))
        restored.go(FlowInput.Vehicle(moving, 10_000)); assertEquals(S4, restored.state)
        restored.go(FlowInput.Vehicle(moving, 15_000)); assertEquals(S3, restored.state)
    }

    /**
     * 사건 도중 첫 차량 입력이 **주차 브레이크**(기어 P 아님)여도 확정 정지가 아니다(사용자 결정 2026-11-13 「완전 정지는 기어 P 또는 시동 꺼짐」,
     * 합동 점검 B-4 — 리뷰 3차 2026-11-08 의 즉시 전이를 뺌). 기어 N 이면 정지 아님, 기어를 모르면 속도 0 의 5초 디바운스.
     */
    @Test fun firstVehicleInputWithParkingBrakeIsNotConfirmedStop() {
        for (gear in listOf(Gear.N, null)) {
            val brake = VehicleState(speedKmh = 0.0, gear = gear, parkingBrake = true)
            val h = H()
            h.go(FlowInput.ConsentsChanged(all, 0)); h.go(FlowInput.Power(true, 0)); h.go(FlowInput.Wrist(NORMAL, 0))
            h.go(FlowInput.Wrist(PULSE_WEAK, 1_000)); h.go(FlowInput.Tick(36_000)); assertEquals(S3, h.state)
            h.go(FlowInput.Vehicle(brake, 37_000))
            assertEquals("주차 브레이크(기어 $gear) — 즉시 전이 없음", S3, h.state)
            assertEquals(0, h.compose())
            h.go(FlowInput.Vehicle(brake, 42_000))
            assertEquals("기어 N 은 정지 아님, 기어 모름은 속도 0 이 5초 이어져 정지", if (gear == null) S4 else S3, h.state)
        }
        // 대조 — 주차 브레이크가 풀린(false) 속도 0 · 기어 N 은 확정 정지가 아니다(정지 아님 → S3 그대로)
        val n = H()
        n.go(FlowInput.ConsentsChanged(all, 0)); n.go(FlowInput.Power(true, 0)); n.go(FlowInput.Wrist(NORMAL, 0))
        n.go(FlowInput.Wrist(PULSE_WEAK, 1_000)); n.go(FlowInput.Tick(36_000))
        n.go(FlowInput.Vehicle(VehicleState(speedKmh = 0.0, gear = Gear.N, parkingBrake = false), 37_000))
        assertEquals(S3, n.state)
    }

    /** 응답 뒤 「워치 신호 없음」을 낸 채 S0 으로 돌아가면 S0 에서도 다시 안내한다(안내 표시 값이 남지 않게). */
    @Test fun noSignalNoticeAgainAfterRestEndsInS0() {
        val h = armed(); h.toS1(1_000); h.go(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2_000))
        h.go(FlowInput.Tick(31_001))
        val d = h.go(FlowInput.Tick(2_000 + 600_000))
        assertEquals(S0, h.state)
        assertTrue(Directive.Show(Phrase.SCREEN_WATCH_NO_SIGNAL, false) in d)
        assertEquals(2, h.log.count { it is Directive.Show && it.phrase == Phrase.SCREEN_WATCH_NO_SIGNAL })
        // S2 만료로 S0 에 가도 같다
        val h2 = armed(parked); h2.toAP(0); h2.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 1_000))
        h2.go(FlowInput.Tick(30_001))
        assertTrue(Directive.Show(Phrase.SCREEN_WATCH_NO_SIGNAL, false) in h2.go(FlowInput.Tick(601_000)))
        assertEquals(S0, h2.state)
    }

    @Test fun observingSmsOptional() {
        val h = armed(parked, params = FlowParams(sendObservingSms = true)); h.toAP(0)
        h.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 1_000))
        assertEquals(1, h.sms(GuardianSmsKind.OBSERVING))
        val h2 = armed(parked); h2.toAP(0); h2.go(FlowInput.Response(ResponseSource.CAR_BUTTON, 1_000))
        assertEquals(0, h2.sms(GuardianSmsKind.OBSERVING))
    }
}
