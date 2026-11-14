// 화면 잠금용 이동 판단(주행 · 정차 · 모름)과 「정차 중입니다」 확인 시험 — 실내 잠금 결함(11-01 B0) 재현 · 테스트 시작 조건 · 경보 응답 판단 불변
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private var relay = false
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L

    private val engine = PhoneEngine(
        store = store, effects = fx, newId = { "c${ids++}" }, wallNow = { wall0 + (mono - mono0) },
        seqStartEpochMs = wall0, startMono = mono0, relayActive = { relay },
    )

    private fun loc(speed: Double) = Loc(37.57590, 126.97690, 8.0, speed, wall0, "서울 종로구 세종대로 172")

    private fun watch(v: WristVerdict) =
        Watch(v, 70, wall0, worn = v != WristVerdict.CHECK_WEAR, source = WatchSource.SENSOR_SDK, test = false, responseAt = null)

    private fun syncCar(speed: Double, gear: Gear) {
        val peer = CarPeer(
            seq = 1, sentAt = wall0, receivedAt = mono + serverOffset,
            vehicle = Vehicle(speedKmh = speed, gear = gear, make = "현대", model = "아반떼", year = 2027), flow = null,
        )
        engine.buildSync("tok", mono)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, emptyList()), mono, mono)
    }

    private fun advance(ms: Long, step: Long = 1_000, each: () -> Unit = {}) {
        val end = mono + ms
        while (mono < end) { mono += step; each(); engine.tick(mono) }
    }

    private fun motion() = engine.status(mono).motion
    private fun start() = engine.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = mono)

    // ── 세 가지 판단(순수 함수) ──

    @Test fun rulesCombineCarAndGps() {
        // 차 연결 있음 → 차 판단(GPS 유무와 상관없음)
        assertEquals(Motion.STOPPED, MotionRules.motion(carKnown = true, gpsKnown = false, stopped = true))
        assertEquals(Motion.DRIVING, MotionRules.motion(carKnown = true, gpsKnown = true, stopped = false))
        // 차 없음 + GPS 있음 → GPS 판단
        assertEquals(Motion.STOPPED, MotionRules.motion(carKnown = false, gpsKnown = true, stopped = true))
        assertEquals(Motion.DRIVING, MotionRules.motion(carKnown = false, gpsKnown = true, stopped = false))
        // 둘 다 없음 → 모름
        assertEquals(Motion.UNKNOWN, MotionRules.motion(carKnown = false, gpsKnown = false, stopped = false))
    }

    @Test fun rulesConfirmAndStart() {
        assertFalse(MotionRules.canStartTest(Motion.UNKNOWN, confirmed = false))
        assertTrue(MotionRules.canStartTest(Motion.UNKNOWN, confirmed = true))
        assertTrue(MotionRules.canStartTest(Motion.STOPPED, confirmed = false))
        assertFalse(MotionRules.canStartTest(Motion.DRIVING, confirmed = true))
        assertTrue(MotionRules.confirmValid(0, Motion.UNKNOWN, MotionRules.SELF_CONFIRM_MS))
        assertFalse(MotionRules.confirmValid(0, Motion.UNKNOWN, MotionRules.SELF_CONFIRM_MS + 1))
        assertFalse(MotionRules.confirmValid(0, Motion.DRIVING, 1))
        assertFalse(MotionRules.confirmValid(0, Motion.STOPPED, 1))
        assertFalse(MotionRules.confirmValid(null, Motion.UNKNOWN, 1))
    }

    // ── 엔진의 세 가지 판단 ──

    @Test fun carFreshUsesCarJudgement() {
        relay = true
        syncCar(50.0, Gear.D)
        engine.tick(mono)
        assertEquals(Motion.DRIVING, motion())
        syncCar(0.0, Gear.P)
        engine.tick(mono)
        assertEquals(Motion.STOPPED, motion())
    }

    @Test fun noCarWithGpsUsesGpsJudgement() {
        engine.onLocation(loc(60.0), mono)
        engine.tick(mono)
        assertEquals(Motion.DRIVING, motion())
        advance(6_000) { engine.onLocation(loc(0.0), mono) }
        assertEquals(Motion.STOPPED, motion())
    }

    // ── 모름 — 실내 잠금 결함(11-01 B0) ──

    @Test fun noCarNoGpsIsUnknownNotLockedAndNeedsConfirm() {
        engine.tick(mono)
        val s = engine.status(mono)
        assertEquals(Motion.UNKNOWN, s.motion)
        assertFalse(s.motion == Motion.DRIVING) // 화면 잠금은 DRIVING 일 때만
        assertTrue(Withdrawal.visible(s)) // 동의 철회 줄이 보인다
        assertFalse(s.stopConfirmed)
        assertEquals(TestStart.NOT_STOPPED, start())
    }

    @Test fun gpsGoingStaleTurnsUnknownAfterHold() {
        engine.onLocation(loc(60.0), mono)
        engine.tick(mono)
        assertEquals(Motion.DRIVING, motion())
        // GPS 가 끊겨도 2분은 주행 유지, 그 뒤 모름
        advance(PhoneEngine.LOC_FRESH_MS + 1_000)
        assertEquals(Motion.DRIVING, motion())
        advance(MotionRules.DRIVING_HOLD_MS)
        assertEquals(Motion.UNKNOWN, motion())
    }

    @Test fun confirmThenStartAllowed() {
        engine.tick(mono)
        assertTrue(engine.confirmStopped(mono))
        assertTrue(engine.status(mono).stopConfirmed)
        assertEquals(TestStart.LOCAL, start())
    }

    // ── 확인이 풀리는 세 조건 ──

    @Test fun confirmReleasedWhenDriving() {
        engine.tick(mono)
        engine.confirmStopped(mono)
        engine.onLocation(loc(60.0), mono)
        engine.tick(mono)
        assertEquals(Motion.DRIVING, motion())
        assertFalse(engine.status(mono).stopConfirmed)
        // GPS 가 다시 끊겨 (주행 유지 2분 뒤) 모름으로 돌아와도 확인은 되살아나지 않는다
        advance(PhoneEngine.LOC_FRESH_MS + MotionRules.DRIVING_HOLD_MS + 1_000)
        assertEquals(Motion.UNKNOWN, motion())
        assertFalse(engine.status(mono).stopConfirmed)
        assertEquals(TestStart.NOT_STOPPED, start())
    }

    @Test fun confirmReleasedWhenStoppedConfirmed() {
        engine.tick(mono)
        engine.confirmStopped(mono)
        advance(6_000) { engine.onLocation(loc(0.0), mono) }
        assertEquals(Motion.STOPPED, motion())
        assertFalse(engine.status(mono).stopConfirmed)
        advance(PhoneEngine.LOC_FRESH_MS + 1_000)
        assertEquals(Motion.UNKNOWN, motion())
        assertFalse(engine.status(mono).stopConfirmed)
    }

    @Test fun confirmReleasedAfterTenMinutes() {
        engine.tick(mono)
        engine.confirmStopped(mono)
        advance(MotionRules.SELF_CONFIRM_MS - 1_000, step = 2_000)
        assertTrue(engine.status(mono).stopConfirmed)
        advance(2_000)
        assertFalse(engine.status(mono).stopConfirmed)
        assertEquals(TestStart.NOT_STOPPED, start())
    }

    // ── 주행이면 확인이 있어도 거부 ──

    @Test fun drivingRefusesEvenWithConfirm() {
        engine.tick(mono)
        engine.confirmStopped(mono)
        // tick 없이 곧바로 주행이 보여도 시작 검사가 판단을 다시 본다
        engine.onLocation(loc(60.0), mono)
        assertEquals(TestStart.NOT_STOPPED, start())
        // 주행 중에는 확인 자체를 받지 않는다
        assertFalse(engine.confirmStopped(mono))
        assertEquals(TestStart.NOT_STOPPED, start())
    }

    @Test fun carDrivingRefusesConfirm() {
        relay = true
        syncCar(50.0, Gear.D)
        engine.tick(mono)
        assertFalse(engine.confirmStopped(mono))
        assertEquals(TestStart.NOT_STOPPED, start())
    }

    // ── 주행 유지 2분(G5, 2026-11-01) ──

    @Test fun rulesDrivingHold() {
        assertTrue(MotionRules.drivingHeld(0, MotionRules.DRIVING_HOLD_MS))
        assertFalse(MotionRules.drivingHeld(0, MotionRules.DRIVING_HOLD_MS + 1))
        assertFalse(MotionRules.drivingHeld(null, 1))
        // 유지는 「모름 → 주행」만 바꾼다
        assertEquals(Motion.DRIVING, MotionRules.motion(carKnown = false, gpsKnown = false, stopped = false, drivingHeld = true))
        assertEquals(Motion.STOPPED, MotionRules.motion(carKnown = false, gpsKnown = true, stopped = true, drivingHeld = true))
        assertEquals(Motion.STOPPED, MotionRules.motion(carKnown = true, gpsKnown = false, stopped = true, drivingHeld = true))
    }

    /** 터널 · 지하 — GPS 주행 뒤 위치가 끊겨도 120초 안은 주행, 지나면 모름. */
    @Test fun gpsDrivingHeldFor120s() {
        engine.onLocation(loc(60.0), mono)
        // 마지막 주행 판단 = 위치가 아직 신선한 마지막 tick(받은 뒤 10초)
        val lastDriving = mono + PhoneEngine.LOC_FRESH_MS
        advance(PhoneEngine.LOC_FRESH_MS + MotionRules.DRIVING_HOLD_MS - 1_000)
        assertEquals(Motion.DRIVING, motion())
        mono = lastDriving + MotionRules.DRIVING_HOLD_MS
        engine.tick(mono)
        assertEquals(Motion.DRIVING, motion())
        mono += 1
        engine.tick(mono)
        assertEquals(Motion.UNKNOWN, motion())
    }

    /** NETWORK 로 바뀌어 속도만 빠진 경우(좌표는 계속 옴)도 같은 유지. */
    @Test fun speedDroppingKeepsDrivingHold() {
        engine.onLocation(loc(60.0), mono)
        engine.tick(mono)
        advance(60_000) { engine.onLocation(Loc(37.57590, 126.97690, 30.0, null, wall0, null), mono) }
        assertEquals(Motion.DRIVING, motion())
        advance(MotionRules.DRIVING_HOLD_MS) { engine.onLocation(Loc(37.57590, 126.97690, 30.0, null, wall0, null), mono) }
        assertEquals(Motion.UNKNOWN, motion())
    }

    /** 차가 주행을 알린 뒤 차 연결이 끊겨도 유지. */
    @Test fun carDrivingHeldAfterLinkLost() {
        relay = true
        syncCar(50.0, Gear.D)
        engine.tick(mono)
        assertEquals(Motion.DRIVING, motion())
        advance(60_000, step = 2_000)
        assertFalse(engine.status(mono).carFresh)
        assertEquals(Motion.DRIVING, motion())
        advance(MotionRules.DRIVING_HOLD_MS, step = 2_000)
        assertEquals(Motion.UNKNOWN, motion())
    }

    /** GPS 정차 판단이 유지를 끊는다 — 그 뒤 GPS 가 끊기면 곧바로 모름. */
    @Test fun gpsStopBreaksHold() {
        engine.onLocation(loc(60.0), mono)
        engine.tick(mono)
        advance(6_000) { engine.onLocation(loc(0.0), mono) }
        assertEquals(Motion.STOPPED, motion())
        advance(PhoneEngine.LOC_FRESH_MS + 1_000)
        assertEquals(Motion.UNKNOWN, motion())
    }

    /** 차 정차 판단도 유지를 끊는다. */
    @Test fun carStopBreaksHold() {
        relay = true
        syncCar(50.0, Gear.D)
        engine.tick(mono)
        syncCar(0.0, Gear.P)
        engine.tick(mono)
        assertEquals(Motion.STOPPED, motion())
        advance(60_000, step = 2_000)
        assertFalse(engine.status(mono).carFresh)
        assertEquals(Motion.UNKNOWN, motion())
    }

    /** 유지 중에는 「정차 중입니다」를 받지 않고 테스트도 거부한다 — 유지가 끝나면 다시 받는다. */
    @Test fun holdRefusesConfirmAndTest() {
        engine.onLocation(loc(60.0), mono)
        engine.tick(mono)
        advance(PhoneEngine.LOC_FRESH_MS + 1_000)
        assertEquals(Motion.DRIVING, motion())
        assertFalse(engine.confirmStopped(mono))
        assertFalse(engine.status(mono).stopConfirmed)
        assertEquals(TestStart.NOT_STOPPED, start())
        advance(MotionRules.DRIVING_HOLD_MS)
        assertEquals(Motion.UNKNOWN, motion())
        assertTrue(engine.confirmStopped(mono))
        assertEquals(TestStart.LOCAL, start())
    }

    // ── 경보 응답 판단 불변 ──

    /** 주행 유지 중의 실제 경보 — 화면은 주행(잠금)이어도 stopped · canRespond 는 유지 전과 같이 false, 119 작성도 열지 않는다. */
    @Test fun holdDoesNotChangeAlarmResponse() {
        engine.onLocation(loc(60.0), mono)
        engine.tick(mono)
        advance(60_000) { engine.onDataLayerWatch(watch(WristVerdict.PULSE_ABSENT), mono) }
        val s = engine.status(mono)
        assertTrue("경보 상태가 아님: ${s.shownState}", s.shownState in PhoneEngine.ALARM_STATES)
        assertEquals(Motion.DRIVING, s.motion)
        assertFalse(s.stopped)
        assertFalse(s.canRespond)
        assertFalse(engine.openCompose(mono))
    }

    /** 모름 상태의 실제 경보 — 확인을 눌러도 stopped · canRespond 는 그대로 false, 휴대폰 「괜찮아요」는 받지 않는다. */
    @Test fun unknownAlarmKeepsPhoneResponseClosed() {
        engine.tick(mono)
        assertTrue(engine.confirmStopped(mono))
        advance(60_000) { engine.onDataLayerWatch(watch(WristVerdict.PULSE_ABSENT), mono) }
        val s = engine.status(mono)
        assertTrue("경보 상태가 아님: ${s.shownState}", s.shownState in PhoneEngine.ALARM_STATES)
        assertEquals(Motion.UNKNOWN, s.motion)
        assertFalse(s.stopped)
        assertFalse(s.canRespond)
        val before = s.shownState
        engine.phoneResponse(mono)
        engine.tick(mono)
        assertEquals(before, engine.status(mono).shownState)
        assertFalse(engine.openCompose(mono)) // 119 작성 화면도 정지 판정 그대로(정지 아님 → 열지 않음)
    }

    // ── 속도 두 갈래 — 정확도 거르기는 화면 잠금에만(사용자 결정 2026-11-01, app-qa 위험 의견) ──

    /** 정확도 나쁜 정지 속도 — 0.2 km/h ± 1.5 m/s(실내 · 지하 주차장). 거르기 전 속도와 화면 잠금용(거른) 속도를 함께 넣는다. */
    private fun feedBadStop() {
        val raw = 0.2
        engine.onLocation(loc(raw), mono, GpsSpeed.usableKmh(raw / 3.6, 1.5))
    }

    @Test fun badAccuracyStopIsUnknownForLockButStoppedForAlarm() {
        feedBadStop()
        engine.tick(mono)
        advance(6_000) { feedBadStop() }
        val s = engine.status(mono)
        assertEquals(Motion.UNKNOWN, s.motion) // 화면 잠금 — 오차 큰 정지는 정차 증거가 아니다
        assertTrue(s.stopped) // 경보 흐름 — 거르기 전 속도로 디바운스 뒤 정지(7fb88eb 와 같음)
    }

    @Test fun badAccuracyStopKeepsDrivingHoldForLock() {
        engine.onLocation(loc(60.0), mono)
        engine.tick(mono)
        advance(6_000) { feedBadStop() }
        val s = engine.status(mono)
        assertEquals(Motion.DRIVING, s.motion) // 주행 유지 중 — 잠금은 풀리지 않는다
        assertTrue(s.stopped)
    }

    /** 차 연결 없음 + 정확도 나쁜 정지 중 실제 경보 — S4 · 휴대폰 「괜찮아요」 · 119 작성 화면이 열린다(예비 경로 막힘 없음). */
    @Test fun badAccuracyStopStillOpensAlarmResponse() {
        feedBadStop()
        engine.tick(mono)
        advance(60_000) { feedBadStop(); engine.onDataLayerWatch(watch(WristVerdict.PULSE_ABSENT), mono) }
        val s = engine.status(mono)
        assertEquals(com.example.guardian.shared.flow.FlowState.S4, s.shownState)
        assertEquals(Motion.UNKNOWN, s.motion)
        assertTrue(s.stopped)
        assertTrue(s.canRespond)
        assertTrue(engine.openCompose(mono))
        assertEquals(1, fx.composed.size)
    }

    /** 정확도 좋은 GPS 표본(오차 0.5 m/s) — 화면 잠금용 거른 값도 그대로. */
    private fun feedGood(kmh: Double) = engine.onLocation(loc(kmh), mono, GpsSpeed.usableKmh(kmh / 3.6, 0.5))

    /** 주행 중 오차 큰 저속이 디바운스로 정지를 확정한 뒤 좋은 주행 표본이 와도 잠금은 STOPPED 로 풀리지 않는다(app-qa 재현). */
    @Test fun goodDrivingAfterBadStopNeverUnlocks() {
        advance(20_000) { feedGood(60.0) }
        assertEquals(Motion.DRIVING, motion())
        advance(8_000) { feedBadStop() }
        assertTrue(engine.status(mono).stopped) // 경보 흐름은 정지 확정(7fb88eb 와 같음)
        repeat(6) {
            mono += 1_000; feedGood(50.0); engine.tick(mono)
            assertEquals("t=${mono - mono0}", Motion.DRIVING, motion())
        }
    }

    /** 실제 정지 15초 뒤 출발 — 출발 직후 디바운스가 주행으로 돌아오는 동안에도 잠금은 곧바로 DRIVING. */
    @Test fun departureAfterStopLocksImmediately() {
        advance(15_000) { feedGood(0.0) }
        assertEquals(Motion.STOPPED, motion())
        repeat(6) {
            mono += 1_000; feedGood(30.0); engine.tick(mono)
            assertEquals("t=${mono - mono0}", Motion.DRIVING, motion())
        }
    }

    /** 정확도 나쁜 정지 중 119 작성 본문 — 거르기 전 속도 · 정지 판정을 쓴다(「차량 정차」, 「주행 중」 아님). */
    @Test fun badAccuracyStopComposeBodyUsesUnfilteredStop() {
        feedBadStop()
        engine.tick(mono)
        advance(60_000) { feedBadStop(); engine.onDataLayerWatch(watch(WristVerdict.PULSE_ABSENT), mono) }
        assertTrue(engine.openCompose(mono))
        val body = fx.composed.single()
        assertTrue(body, body.contains(com.example.guardian.shared.text.SmsText.STOPPED))
        assertFalse(body, body.contains(com.example.guardian.shared.text.SmsText.DRIVING))
    }
}
