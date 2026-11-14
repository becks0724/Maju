// 경보 화면 표시 판단 시험 — 2026-11-08 노트10 휴대폰 단독 테스트(B1) 결함 재현(응답 없이 끝난 경보 · 테스트 119 앱 버튼 · 문자 확인 · 문자 기록 · 꺼진 홈 부제)
package com.example.guardian.phone.ui

import com.example.guardian.phone.core.EngineStatus
import com.example.guardian.phone.core.FakeEffects
import com.example.guardian.phone.core.MemoryStore
import com.example.guardian.phone.core.PendingSms
import com.example.guardian.phone.core.PhoneEngine
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.Ownership
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRulesTest {
    private fun status(shown: FlowState?) = EngineStatus(
        owner = Owner.PHONE, carFresh = false, flowState = shown, stopped = true, watchVerdict = null, watchStale = true,
        clockUnverified = false, canRespond = false, has119 = false, test = null, testScenario = null, testFlowState = null,
        stage = 2, flowTest = false, noResponseSec = null, stoppedSinceWall = null, shownState = shown,
    )

    /** 코드 리뷰 2026-11-08 하2 — 서비스가 멈춰 상태가 지워지면(null) 경보 화면이 빈 화면으로 남았다. 경보를 봤어도 서비스가 멈췄으면 닫는다. */
    @Test fun serviceStoppedClosesAlarmScreen() {
        val last = status(FlowState.S4)
        assertNull("서비스 멈춤 — 닫는다", UiRules.alarmStatus(null, last, running = false, everAlarm = true))
        assertNull("경보를 본 적 없음 — 닫는다", UiRules.alarmStatus(null, null, running = true, everAlarm = false))
        assertNull(UiRules.alarmStatus(null, last, running = true, everAlarm = false))
        assertEquals("서비스가 돌고 경보를 봤다 — 직전 화면", last, UiRules.alarmStatus(null, last, running = true, everAlarm = true))
        val now = status(FlowState.S2)
        assertEquals("상태가 있으면 그 상태", now, UiRules.alarmStatus(now, last, running = true, everAlarm = true))
        // 엔진은 살아 있고 흐름 상태만 모르는 틈(shownState=null)은 상태가 있으므로 닫지 않는다 — heldState 가 직전 경보를 둔다
        val gap = status(null)
        assertEquals(gap, UiRules.alarmStatus(gap, last, running = true, everAlarm = true))
    }

    /** 코드 리뷰 2026-11-08 하4 — 전체 화면 경보 알림을 경보가 끝나면 거둔다(응답 · ALERT_STOP · 남은 알람 응답 · 사건 끝). 모름이면 그대로. */
    @Test fun alarmNoticeIsWithdrawnWhenAlarmEnds() {
        var shown = false
        shown = UiRules.alarmNotice(shown, FlowState.AP, stranded = false)
        assertTrue("경보 — 띄운다", shown)
        shown = UiRules.alarmNotice(shown, null, stranded = false)
        assertTrue("이어받기 전 틈 — 그대로", shown)
        for (end in listOf(FlowState.S1R, FlowState.S2, FlowState.S0, FlowState.OFF, FlowState.S5)) {
            assertFalse("$end — 거둔다", UiRules.alarmNotice(true, end, stranded = false))
        }
        assertTrue("남은 알람 — 둔다", UiRules.alarmNotice(false, FlowState.S0, stranded = true))
        assertFalse("남은 알람을 사람이 껐다 — 거둔다", UiRules.alarmNotice(true, FlowState.S0, stranded = false))
        assertFalse("처음부터 모름이면 띄우지 않는다", UiRules.alarmNotice(false, null, stranded = false))
    }

    /** 결함 1 — 응답 없이 경보가 끝났는데(테스트 멈춤 · 안전장치) 「응답 확인됨」을 보였다. 응답을 본 적이 없으면 화면을 닫는다. */
    @Test fun noResponseEndDoesNotShowResponded() {
        var responded = false
        for (st in listOf(FlowState.AP, FlowState.S4, FlowState.S0)) responded = UiRules.alarmResponded(responded, st)
        assertFalse(responded)
        assertEquals(ResultKind.CLOSE, UiRules.resultKind(FlowState.S0, responded))
        assertEquals(ResultKind.CLOSE, UiRules.resultKind(FlowState.OFF, responded))
        // 상태 모름(null)은 끝난 것이 아니다 — 닫지 않고 직전 화면을 둔다(app-qa 불합격 · PM 결정 2026-11-08). 「응답 확인됨」도 되살리지 않는다
        assertEquals(ResultKind.KEEP, UiRules.resultKind(null, responded))
        assertEquals(ResultKind.KEEP, UiRules.resultKind(null, true))
    }

    /**
     * app-qa 불합격 재현 — 차가 주인인 실제 경보(S4) 중 차 sync 가 10초 넘게 끊기면, 휴대폰이 다음 틱에 이어받기 전의 status() 가
     * shownState=null 을 낸다. 이때 경보 화면은 닫지 않고 직전 경보 화면(S4)을 그대로 둔다.
     */
    @Test fun carLinkGapBeforeTakeOverKeepsAlarmScreen() {
        val wall0 = 1_793_000_000_000L
        val mono0 = 1_000_000L
        var mono = mono0
        val serverOffset = 5_000_000_000L
        val engine = PhoneEngine(MemoryStore(), FakeEffects(), { "c" }, { wall0 + (mono - mono0) }, wall0, mono0, { true })
        val flow = FlowStatus.of(
            FlowSnapshot(state = FlowState.S4, test = false, powerOn = true, consents = Consents(true, true, true),
                facts = IncidentFacts(eventId = "event-car")),
            Owner.CAR,
        )
        engine.buildSync("tok", mono)
        val peer = CarPeer(1, wall0, mono + serverOffset, Vehicle(speedKmh = 0.0, gear = Gear.P), flow)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, emptyList()), mono, mono)
        engine.tick(mono)
        assertEquals(Owner.CAR, engine.owner)
        assertEquals(FlowState.S4, engine.status(mono).shownState)

        mono += Ownership.CAR_FRESH_MS + 1 // 틱 없이 — 이어받기 전
        val shown = engine.status(mono).shownState
        assertNull("이어받기 전 틈에는 상태를 모른다", shown)

        var held: FlowState? = null
        held = UiRules.heldState(held, FlowState.S4)
        held = UiRules.heldState(held, shown)
        assertEquals("직전 경보 화면(S4)을 그대로 둔다", FlowState.S4, held)
        assertTrue(held in PhoneEngine.ALARM_STATES)
        var responded = UiRules.alarmResponded(false, FlowState.S4)
        responded = UiRules.alarmResponded(responded, shown)
        assertTrue("닫지 않는다", UiRules.resultKind(shown, responded) != ResultKind.CLOSE)
    }

    /** 끝난 것이 확인된 상태(S0 · OFF)는 모름이 아니라 직전 화면을 두지 않는다. */
    @Test fun knownStateReplacesHeldState() {
        assertEquals(FlowState.S0, UiRules.heldState(FlowState.S4, FlowState.S0))
        assertEquals(FlowState.OFF, UiRules.heldState(FlowState.S4, FlowState.OFF))
        assertNull(UiRules.heldState(null, null))
    }

    @Test fun respondedStatesShowResponded() {
        assertEquals(ResultKind.RESPONDED, UiRules.resultKind(FlowState.S1R, false))
        assertEquals(ResultKind.RESPONDED, UiRules.resultKind(FlowState.S2, false))
        assertEquals(ResultKind.ACCIDENT, UiRules.resultKind(FlowState.S5, false))
    }

    /** 응답(S2)을 본 뒤 테스트가 끝나 S0 이 되어도 응답 결과는 사실이라 그대로 둔다. 새 경보가 오면 다시 처음부터. */
    @Test fun respondedIsRememberedUntilNextAlarm() {
        var responded = false
        for (st in listOf(FlowState.AP, FlowState.S2, FlowState.S0)) responded = UiRules.alarmResponded(responded, st)
        assertTrue(responded)
        assertEquals(ResultKind.RESPONDED, UiRules.resultKind(FlowState.S0, responded))
        responded = UiRules.alarmResponded(responded, FlowState.AP)
        assertFalse(responded)
    }

    /** 결함 3 — 테스트 무응답 화면에 「「119신고」 앱 열기」가 나왔다(테스트는 「119 는 미리보기만」). 실제 경보에서만 보인다. */
    @Test fun open119AppOnlyInRealAlarm() {
        assertFalse(UiRules.showOpen119App(flowTest = true))
        assertTrue(UiRules.showOpen119App(flowTest = false))
    }

    /** 결함 4 — P17 테스트 문자 확인이 홈 창 · 알림으로만 떠 무응답 경보 화면에 있는 사람은 못 봤다. 테스트 경보 화면에서도 같은 확인을 보인다. */
    @Test fun testSmsAskShownOnTestAlarm() {
        val p = PendingSms("EVENT:e1", "01000000001", "[테스트] 본문")
        assertEquals(p, UiRules.alarmSmsAsk(listOf(p), flowTest = true))
        assertNull(UiRules.alarmSmsAsk(emptyList(), flowTest = true))
        assertNull("실제 경보 화면에는 테스트 확인을 두지 않는다", UiRules.alarmSmsAsk(listOf(p), flowTest = false))
    }

    /** 결함 6 — 2회차 P14 에 1회차의 「▲ 보내지 못함」이 보였다(최근 10건 전체). 이번 사건 것만 보인다. */
    @Test fun resultLogShowsOnlyThisIncident() {
        val first = SmsLogItem("EVENT", 1L, sent = false, eventId = "event-1")
        val second = SmsLogItem("CANCEL", 2L, sent = true, eventId = "event-2")
        val log = listOf(first, second)
        assertEquals(emptyList<SmsLogItem>(), UiRules.incidentSmsLog(listOf(first), "event-2"))
        assertEquals(listOf(second), UiRules.incidentSmsLog(log, "event-2"))
        assertEquals(emptyList<SmsLogItem>(), UiRules.incidentSmsLog(log, null))
    }

    /** 경보 화면이 기억할 사건 — 경보 · 응답 · 사고 상태의 사건 id, 테스트가 끝나 S0 이 되면 앞 값 그대로. */
    @Test fun incidentIdFollowsAlarmAndKeepsAfterEnd() {
        var id: String? = null
        id = UiRules.incidentEventId(id, FlowState.AP, "event-1")
        id = UiRules.incidentEventId(id, FlowState.S2, "event-1")
        id = UiRules.incidentEventId(id, FlowState.S0, null)
        assertEquals("event-1", id)
        id = UiRules.incidentEventId(id, FlowState.S0, "other")
        assertEquals("S0 의 id 로 바꾸지 않는다", "event-1", id)
        id = UiRules.incidentEventId(id, FlowState.AP, "event-2")
        assertEquals("event-2", id)
    }

    /** 결함 7 — 꺼진 홈 부제 「켜려면 아래 항목을 먼저 마치세요」가 마칠 항목이 없어도 나왔다. 켤 수 있으면 켜기 안내. */
    @Test fun homeOffSubMatchesReadiness() {
        assertEquals(PhoneTexts.HOME_OFF_SUB, UiRules.homeOffSub(canTurnOn = false))
        assertEquals(PhoneTexts.HOME_OFF_SUB_READY, UiRules.homeOffSub(canTurnOn = true))
        assertTrue(PhoneTexts.HOME_OFF_SUB_READY in PhoneTexts.all)
    }
}
