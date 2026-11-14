// 끄기 시험(코드 리뷰 2026-11-08 중 1) — 휴대폰 주인 사건 중 끄거나 동의를 철회하면 엔진이 상태 기계를 OFF 로 만들어 알람을 바로 멈추고, 마지막 sync 의 flow 가 OFF 다
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.AlertStopReason
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutdownTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private var mono = 1_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + mono }, wall0, mono, { false })

    private fun real(v: WristVerdict) = Watch(v, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = null)
    private fun feed(v: WristVerdict) {
        engine.onDataLayerWatch(real(v), mono)
        engine.onLocation(Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "서울 종로구 세종대로 172"), mono)
    }

    /** 휴대폰 주인(중계 없음) — 정차 중 맥박 없음 40초 → S4 · 알람 켜짐. */
    private fun toAlarm() {
        feed(WristVerdict.NORMAL)
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        repeat(40) { mono += 1_000; feed(WristVerdict.PULSE_ABSENT); engine.tick(mono) }
        assertEquals(FlowState.S4, engine.status(mono).flowState)
        assertTrue("경보 알람이 켜졌다", fx.alarms.isNotEmpty())
        assertNotNull(store.savedFlow)
    }

    private fun assertOffAndQuiet(stopsBefore: Int) {
        assertTrue("sync 왕복 없이 알람을 바로 멈춘다", fx.alarmStops > stopsBefore)
        val req = engine.buildSync("tok", mono)
        val flow = req.flow!!
        assertEquals(Owner.PHONE, flow.owner)
        assertEquals("마지막 flow 는 경보 중이 아니다 — 차가 이어받을 경보가 없다", FlowState.OFF, flow.state)
        assertFalse(flow.snapshot.powerOn)
        // 꺼짐 · 동의 없음의 마지막 스냅숏은 설정만 — 건강 판정 · 사건 사실 · 사건 id 를 싣지 않는다(app-qa 하2, G2)
        val snap = flow.snapshot
        assertEquals(com.example.guardian.shared.flow.IncidentFacts(), snap.facts)
        assertEquals(WristVerdict.NORMAL, snap.verdict)
        assertFalse(snap.worn)
        assertTrue("차 · 워치 몫 경보 멈춤도 마지막 sync 에 싣는다", req.commands.any { it.type == CommandType.ALERT_STOP })
        assertNull("저장한 사건을 지운다(G2)", store.savedFlow)
    }

    @Test fun phoneOwnerPowerOff_stopsAlarmAndLastFlowIsOff() {
        toAlarm()
        val stops = fx.alarmStops
        store.registration = fullRegistration(powerOn = false)
        engine.shutdown(mono)
        assertOffAndQuiet(stops)
    }

    /**
     * 코드 리뷰 2026-11-09 하 — 끄기 · 철회의 마지막 sync 를 기다리는 몇 초 동안 경보 화면 · 전체 화면 알림이 남았다(끄기 경로가 publish 를 부르지 않음).
     * 끈 직후 상태로 화면 판단을 하면 경보를 거둔다 — 서비스는 shutdown 뒤 publish 를 부른다(서비스 배선 자체는 기기에서만 보인다).
     */
    @Test fun alarmScreenClearsRightAfterShutdown() {
        toAlarm()
        val before = engine.status(mono)
        assertTrue(com.example.guardian.phone.ui.UiRules.alarmNotice(false, before.shownState, before.strandedAlarm))
        store.registration = fullRegistration(powerOn = false)
        engine.shutdown(mono)
        val after = engine.status(mono)
        assertFalse("끈 직후 상태로 경보 알림을 거둔다", com.example.guardian.phone.ui.UiRules.alarmNotice(true, after.shownState, after.strandedAlarm))
    }

    /**
     * 코드 리뷰 2026-11-09 새로 찾은 것(a) — 테스트 문자 확인(P17)을 묻는 중에 끄거나 철회하면, 보호자 번호 · 본문이 담긴 묻던 문자가
     * 엔진에 남아 publish 가 처음 설정 화면 위에 대화를 다시 띄웠다. 끄기(shutdown)는 테스트 상태를 버린다.
     */
    @Test fun shutdownDropsPendingTestSms() {
        val p = Payload.SendGuardianSms(com.example.guardian.shared.protocol.GuardianSmsWireKind.EVENT, "event-t", WireFacts(SnapshotClock.LOCAL), test = true)
        engine.executor.execute(Command("t-1", p.type, p.toJson(), 30_000, true, Party.CAR, CommandTo.PHONE), ExecContext(stopped = true, testSession = false))
        assertEquals("묻는 중", 1, engine.executor.pending().size)
        store.registration = fullRegistration(consents = Consents(), powerOn = false)
        engine.shutdown(mono)
        assertTrue("끄기 · 철회 뒤 묻던 테스트 문자가 남지 않는다", engine.executor.pending().isEmpty())
        assertEquals("묻는 알림도 거둔다", 1, fx.testSmsAskCleared)
        assertTrue(fx.sms.isEmpty())
    }

    @Test fun phoneOwnerConsentWithdrawal_stopsAlarmAndLastFlowIsOff() {
        toAlarm()
        val stops = fx.alarmStops
        store.registration = fullRegistration(consents = Consents(), powerOn = false)
        engine.shutdown(mono)
        assertOffAndQuiet(stops)
    }

    /** 경보 1 · 2 중(S1 · AP)에서 멈춘다 — 무응답 문턱 전. */
    private fun toEarlyAlarm() {
        feed(WristVerdict.NORMAL)
        engine.tick(mono)
        while (engine.status(mono).flowState !in setOf(FlowState.S1, FlowState.AP)) { mono += 1_000; feed(WristVerdict.PULSE_ABSENT); engine.tick(mono) }
        assertTrue(fx.sms.isEmpty())
    }

    /**
     * 1차 app-qa 참고 — 마지막 틱 뒤 무응답 문턱을 넘긴 시각에 끄면, 상태 기계가 꺼짐보다 먼저 시계 · 링크 입력의 타이머로
     * S3 · S4 에 한 번 들어가 보호자 문자 · 119 지시 · 안내를 낸 뒤 OFF 가 됐다. 끄기는 이미 처리한 시각의 꺼짐을 먼저 넣는다.
     */
    @Test fun powerOffPastNoResponseThresholdDoesNotEnterNoResponseFirst() {
        toEarlyAlarm()
        val lines = fx.lines.size
        mono += 120_000 // 마지막 틱 뒤 무응답 문턱을 넘겼다(틱 없음)
        store.registration = fullRegistration(powerOn = false)
        engine.shutdown(mono)
        assertTrue("보호자 문자를 보내지 않는다: ${fx.sms}", fx.sms.isEmpty())
        assertTrue("119 지시를 내지 않는다", fx.noResponses.isEmpty() && fx.composed.isEmpty())
        assertEquals("무응답 안내를 띄우지 않는다: ${fx.lines}", lines, fx.lines.size)
        assertOffAndQuiet(0)
    }

    @Test fun withdrawalPastNoResponseThresholdShowsNoConsentNotice() {
        toEarlyAlarm()
        mono += 120_000
        store.registration = fullRegistration(consents = Consents(), powerOn = false)
        engine.shutdown(mono)
        assertTrue("「동의 없음으로 보내지 않음」 기록이 없다: ${fx.smsLog}", fx.smsLog.isEmpty())
        assertTrue(fx.notices.toString(), fx.notices.isEmpty())
        assertTrue(fx.noResponses.isEmpty())
        assertOffAndQuiet(0)
    }

    /**
     * app-qa 하3 — 철회 · 끄기 뒤 마지막 sync 들의 응답에 차가 앞서 낸 지시가 실려 와도 경보 멈춤만 실행한다.
     * 소리 경보 · 119 작성 지시는 실행하지 않고 ack 만 한다.
     */
    @Test fun afterWithdrawalOnlyAlertStopRunsOthersAreAckedOnly() {
        store.registration = fullRegistration(consents = Consents(), powerOn = false)
        val start = Command("car-1", CommandType.ALERT_START,
            Payload.AlertStart(FlowState.AP, 2, vibrate = false, sound = true, maxVolume = true, durationMs = 20_000).toJson(), 30_000, false, Party.CAR, CommandTo.ALL)
        val facts = WireFacts(SnapshotClock.SERVER, firstAlertAt = wall0, stoppedAt = wall0, noResponseAt = wall0)
        val open119 = Command("car-2", CommandType.OPEN_119_COMPOSE,
            Payload.Open119Compose("event-1", facts, previewOnly = false, test = false).toJson(), 30_000, false, Party.CAR, CommandTo.PHONE)
        val stop = Command("car-3", CommandType.ALERT_STOP, Payload.AlertStop(AlertStopReason.CLEARED).toJson(), 30_000, false, Party.CAR, CommandTo.ALL)
        engine.buildSync("tok", mono)
        engine.onSyncResponse(PhoneSyncResponse(mono + 5_000_000_000L, null, null, listOf(start, open119, stop)), mono, mono)
        assertTrue("철회 뒤 알람을 켜지 않는다: ${fx.alarms}", fx.alarms.isEmpty())
        assertTrue("철회 뒤 119 화면을 띄우지 않는다", fx.noResponses.isEmpty() && fx.composed.isEmpty())
        assertTrue("경보 멈춤은 실행한다", fx.alarmStops > 0)
        assertEquals("모두 ack 한다", listOf("car-1", "car-2", "car-3"), engine.buildSync("tok", mono).acks)
    }

    /** 주인이 정해지기 전(상태 기계 없음)에는 할 일이 없다 — 설정 스냅숏(owner CAR)만 나간다. */
    @Test fun withoutMachineShutdownDoesNothing() {
        store.registration = fullRegistration(powerOn = false)
        engine.shutdown(mono)
        assertEquals(0, fx.alarmStops)
        val flow = engine.buildSync("tok", mono).flow!!
        assertEquals(Owner.CAR, flow.owner)
        assertFalse(flow.snapshot.powerOn)
    }
}
