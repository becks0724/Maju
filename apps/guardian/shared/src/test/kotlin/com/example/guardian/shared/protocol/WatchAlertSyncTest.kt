// 계약 §4.3 한 판 시험 — 경보 지시 사건 id(사고 전환 정지는 옛 id), Watch responseSeq · alerting 코덱, 워치 경보 맞추기(C-2), 응답 번호(B-5)
package com.example.guardian.shared.protocol

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.AlertTarget
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAlertSyncTest {
    private fun watch(alerting: WatchAlertStatus?, reported: Boolean = true, seq: Long? = null, at: Long? = null) =
        Watch(WristVerdict.NORMAL, 70, null, true, WatchSource.SENSOR_SDK, false, at, responseSeq = seq, alerting = alerting, alertingReported = reported)

    private fun roundTrip(w: Watch) = Watch.fromJson(JsonObj(Json.parseObject(Json.encode(w.toJson(healthConsent = true)))))

    // ── 코덱 ──

    @Test fun watchFieldsAreOptionalAndAlertingKeyMarksNewWatch() {
        val a = watch(WatchAlertStatus("event-1", FlowState.S1, 2), seq = 3)
        assertEquals(a, roundTrip(a))
        val quiet = watch(null)
        val q = roundTrip(quiet)
        assertTrue("새 워치 — 안 울림도 알림", q.alertingReported); assertNull(q.alerting)
        val old = roundTrip(watch(null, reported = false))
        assertFalse("옛 워치 — 키 없음", old.alertingReported)
        assertFalse(watch(null, reported = false).toJson(true).containsKey("alerting"))
        assertFalse(watch(null, reported = false).toJson(true).containsKey("responseSeq"))
        // 틀린 alerting 은 모름(옛 워치처럼), 값 전체는 살림
        val bad = Watch.fromJson(JsonObj(Json.parseObject("""{"verdict":"NORMAL","worn":true,"source":"SENSOR_SDK","test":false,"alerting":{"stage":"NOPE","level":1}}""")))
        assertFalse(bad.alertingReported); assertNull(bad.alerting)
    }

    @Test fun alertPayloadCarriesOptionalEventId() {
        var n = 0
        val cmds = DirectiveCommands.toCommands(
            Directive.AlertStart(setOf(AlertTarget.WATCH_SOUND, AlertTarget.PHONE_ALARM), FlowState.AP, 2, 20_000, false, "event-9"),
            Party.CAR, { "c${n++}" }, 30_000, null,
        )
        assertTrue(cmds.all { it.payload["eventId"] == "event-9" })
        assertEquals("event-9", (Payload.fromJson(CommandType.ALERT_START, cmds[0].payload) as Payload.AlertStart).eventId)
        val stop = DirectiveCommands.toCommands(Directive.AlertStop(false, "event-9"), Party.CAR, { "s" }, 30_000, null).single()
        assertEquals("event-9", (Payload.fromJson(CommandType.ALERT_STOP, stop.payload) as Payload.AlertStop).eventId)
        val old = DirectiveCommands.toCommands(Directive.AlertStop(false), Party.CAR, { "o" }, 30_000, null).single()
        assertFalse("없으면 싣지 않음", old.payload.containsKey("eventId"))
    }

    @Test fun flowMachineStampsEventIdAndAccidentStopUsesOldId() {
        val m = FlowMachine()
        m.handle(FlowInput.Vehicle(VehicleState(60.0, Gear.D), 0))
        m.handle(FlowInput.ConsentsChanged(Consents(true, true, true), 0)); m.handle(FlowInput.Power(true, 0)); m.handle(FlowInput.Wrist(WristVerdict.NORMAL, 0))
        val start = m.handle(FlowInput.Wrist(WristVerdict.PULSE_WEAK, 1_000)).filterIsInstance<Directive.AlertStart>().single()
        val e1 = m.snapshot().facts.eventId
        assertEquals(e1, start.eventId)
        val d = m.handle(FlowInput.Vehicle(VehicleState(60.0, Gear.D, airbagDeployed = true), 5_000))
        assertEquals(FlowState.S5, m.state)
        assertEquals("사고 전환 정지는 옛 사건 id", e1, d.filterIsInstance<Directive.AlertStop>().single().eventId)
        assertTrue(m.snapshot().facts.eventId!!.startsWith("accident-"))
    }

    // ── C-2 워치 경보 맞추기 ──

    private fun snap(state: FlowState, alerting: Boolean, stage: Int = 1, id: String? = "event-1") =
        FlowSnapshot(state = state, alerting = alerting, stage = stage, incidentActive = alerting, facts = IncidentFacts(eventId = id))

    private val st0 = WatchAlertReconcile.State()

    @Test fun startIsResentWhenWatchSilentOrStageDiffers_onceEvery10s() {
        val s1 = snap(FlowState.S1, true)
        val r = WatchAlertReconcile.step(s1, watch(null), true, st0, 1_000)
        val start = r.directives.single() as Directive.AlertStart
        assertEquals(setOf(AlertTarget.WATCH_VIBRATION), start.targets); assertEquals(1, start.level); assertEquals("event-1", start.eventId)
        assertTrue("10초 안 다시 안 냄", WatchAlertReconcile.step(s1, watch(null), true, r.state, 10_999).directives.isEmpty())
        assertEquals(1, WatchAlertReconcile.step(s1, watch(null), true, r.state, 11_000).directives.size)
        // 2단계(소리)인데 워치는 진동 — 소리 시작만, 휴대폰 알람은 다시 내지 않음
        val s2 = WatchAlertReconcile.step(snap(FlowState.S1, true, stage = 2), watch(WatchAlertStatus("event-1", FlowState.S1, 1)), true, st0, 0)
        assertEquals(setOf(AlertTarget.WATCH_SOUND), (s2.directives.single() as Directive.AlertStart).targets)
        // S3 · S4(stage 0 이지만 경보 중) — 소리 단계
        assertEquals(2, (WatchAlertReconcile.step(snap(FlowState.S4, true, stage = 0), watch(null), true, st0, 0).directives.single() as Directive.AlertStart).level)
        // 맞으면 아무것도 안 냄
        assertTrue(WatchAlertReconcile.step(s1, watch(WatchAlertStatus("event-1", FlowState.S1, 1)), true, st0, 0).directives.isEmpty())
        // 다른 사건 id 로 울림 — 내 사건으로 다시 시작
        assertEquals("event-1", (WatchAlertReconcile.step(s1, watch(WatchAlertStatus("event-0", FlowState.S1, 1)), true, st0, 0).directives.single() as Directive.AlertStart).eventId)
    }

    @Test fun stopIsResentWhenNotAlerting_knownAtOnce_unknownAfter10s() {
        val calm = snap(FlowState.S1R, false)
        val known = WatchAlertReconcile.step(calm, watch(WatchAlertStatus("event-1", FlowState.S1, 1)), true, st0, 0)
        assertEquals(Directive.AlertStop(false, "event-1"), known.directives.single())
        assertTrue(WatchAlertReconcile.step(calm, watch(WatchAlertStatus("event-1", FlowState.S1, 1)), true, known.state, 9_999).directives.isEmpty())
        // 모르는 사건 id — 10초 지켜본 뒤
        val unknown = watch(WatchAlertStatus("event-7", FlowState.S1, 1))
        val a = WatchAlertReconcile.step(calm, unknown, true, st0, 0)
        assertTrue(a.directives.isEmpty())
        val b = WatchAlertReconcile.step(calm, unknown, true, a.state, 9_999)
        assertTrue(b.directives.isEmpty())
        assertEquals(Directive.AlertStop(false, "event-7"), WatchAlertReconcile.step(calm, unknown, true, b.state, 10_000).directives.single())
        // 사고(S5)는 경보를 멈춘 상태 — 워치가 옛 사건 id 로 울리면 모르는 사건으로 보고 10초 뒤 그 id 로 정지
        val s5 = snap(FlowState.S5, false, id = "accident-2")
        val oldAlert = watch(WatchAlertStatus("event-1", FlowState.S1, 1))
        val w0 = WatchAlertReconcile.step(s5, oldAlert, true, st0, 0)
        assertTrue(w0.directives.isEmpty())
        assertEquals(Directive.AlertStop(false, "event-1"), WatchAlertReconcile.step(s5, oldAlert, true, w0.state, 10_000).directives.single())
    }

    @Test fun nothingForStaleOrOldWatch() {
        val s1 = snap(FlowState.S1, true)
        assertTrue(WatchAlertReconcile.step(s1, watch(null), watchFresh = false, st0, 0).directives.isEmpty())
        assertTrue(WatchAlertReconcile.step(s1, watch(null, reported = false), true, st0, 0).directives.isEmpty())
        assertTrue(WatchAlertReconcile.step(s1, null, true, st0, 0).directives.isEmpty())
    }

    // ── B-5 응답 번호 ──

    @Test fun responseSeqFirstIsBaseThenIncreaseIsResponse() {
        val t = WatchResponseTracker()
        assertNull("처음 본 번호는 기준", t.onValue(5, 1_000_000, 0))
        assertNull(t.onValue(5, 1_000_000, 1))
        assertEquals(FlowInput.Response(com.example.guardian.shared.flow.ResponseSource.WATCH_BUTTON, 2), t.onValue(6, 900_000, 2))
        assertNull("작은 번호 하나는 순서 뒤바뀐 옛 값 — 무시", t.onValue(1, 900_000, 3))
        assertEquals("벽시계와 무관", 4L, t.onValue(7, 0, 4)!!.atMs)
        assertNull("번호를 본 뒤에는 responseAt 을 보지 않음", t.onValue(null, 5_000_000, 5))
    }

    @Test fun nonOwnerStillFeedsTrackerSoOwnerChangeIsNotAResponse() {
        // 차 · 휴대폰이 각자 추적기를 쥔다 — 주인 아닐 때도 모든 워치 값을 넣으면, 주인이 되어 같은 번호를 봐도 응답이 아니다(be-qa 3차 (가))
        val car = WatchResponseTracker(); val phone = WatchResponseTracker()
        for (t in listOf(car, phone)) assertNull(t.onValue(5, null, 0))
        assertTrue("다른 주인(차)이 처리한 누름", car.onValue(6, null, 1) != null)
        phone.onValue(6, null, 1) // 휴대폰은 주인이 아니라 결과를 버린다 — 그래도 기준은 옮긴다
        assertNull("휴대폰이 주인이 된 뒤 같은 번호는 응답이 아님", phone.onValue(6, null, 2))
    }

    @Test fun newWatchWithoutSeqIsZeroSoFirstPressCounts() {
        val t = WatchResponseTracker()
        assertNull(t.onWatch(watch(null, seq = null, at = null), 0))
        assertEquals("첫 누름(1)은 응답", 1L, t.onWatch(watch(WatchAlertStatus("event-1", FlowState.AP, 1), seq = 1, at = 5), 1)!!.atMs)
        // 옛 워치(키 없음)는 responseAt 규칙 그대로
        val old = WatchResponseTracker()
        assertNull(old.onWatch(watch(null, reported = false, at = 1_000_000), 0))
        assertEquals(1L, old.onWatch(watch(null, reported = false, at = 1_050_000), 1)!!.atMs)
    }

    @Test fun outOfOrderSmallerSeqIsNotAResponse() {
        // be-qa 3차 — 중계 · Data Layer 두 길이 번갈아 골라지면 옛 번호가 다시 보인다. 10 → 9 → 10 은 응답이 아니다
        val t = WatchResponseTracker()
        assertNull(t.onValue(10, null, 0))
        assertNull(t.onValue(9, null, 1))
        assertNull("순서 뒤바뀜은 응답 아님", t.onValue(10, null, 2))
        assertEquals(3L, t.onValue(11, null, 3)!!.atMs)
    }

    @Test fun reinstallNeedsThreeSmallerValuesInARow() {
        // 재설치(번호가 처음부터) — 작은 번호가 연달아 3번 와야 기준을 옮긴다. 놓침이 거짓 응답보다 낫다
        val t = WatchResponseTracker()
        val got = listOf<Long>(10, 0, 0, 0, 1).mapIndexedNotNull { i, v -> t.onValue(v, null, i.toLong()) }
        assertEquals(listOf(4L), got.map { it.atMs })
    }

    @Test fun lowRunBoundaries() {
        // be-qa 권고 — 재설치 문턱(3)과 연속을 끊는 두 경우가 거짓 응답 쪽으로 새지 않게
        fun responses(vararg seq: Long): List<Long> {
            val t = WatchResponseTracker()
            return seq.toList().mapIndexedNotNull { i, v -> t.onValue(v, null, i.toLong())?.atMs }
        }
        assertEquals("작은 값 두 번은 재설치 아님", emptyList<Long>(), responses(10, 9, 9, 10))
        assertEquals("같은 번호는 작은 값 연속을 끊음", emptyList<Long>(), responses(10, 9, 10, 9, 9, 10))
        assertEquals("응답 뒤 작은 값 연속은 처음부터", listOf(2L), responses(10, 9, 11, 9, 9, 11))
        // be-qa · PM 비교 — 재설치 판정 중 누름을 흡수하지 않는다(첫 값을 임시 기준으로): 7 → 0 → 1 → 2 는 응답 둘
        assertEquals(listOf(2L, 3L), responses(7, 0, 1, 2))
        assertEquals("재설치 뒤 기준은 마지막 임시 기준", listOf(2L, 3L, 5L), responses(7, 0, 1, 2, 2, 3))
        assertEquals("3번째의 기준은 그 값이 아니라 임시 기준(6)", listOf(2L), responses(10, 5, 6, 3, 4))
    }

    @Test fun afterSeqSeenResponseAtIsIgnored() {
        val t = WatchResponseTracker()
        assertNull(t.onValue(5, null, 0))
        assertNull(t.onValue(null, 1_000_000, 1))
        assertNull("번호를 본 뒤 responseAt 변화는 보지 않음", t.onValue(null, 2_000_000, 2))
    }

    @Test fun unknownWaitRestartsWhenEventIdChanges() {
        // be-qa 3차 — 모르는 사건 A 를 9초 보다가 B 로 바뀌면 B 는 처음부터 10초 지켜본다
        val calm = snap(FlowState.S1R, false)
        val a = WatchAlertReconcile.step(calm, watch(WatchAlertStatus("event-A", FlowState.S1, 1)), true, st0, 0)
        val b = WatchAlertReconcile.step(calm, watch(WatchAlertStatus("event-B", FlowState.S1, 1)), true, a.state, 9_000)
        assertTrue(b.directives.isEmpty())
        assertTrue("B 는 1초만 봄 — 아직 끄지 않음", WatchAlertReconcile.step(calm, watch(WatchAlertStatus("event-B", FlowState.S1, 1)), true, b.state, 10_000).directives.isEmpty())
        assertEquals(Directive.AlertStop(false, "event-B"), WatchAlertReconcile.step(calm, watch(WatchAlertStatus("event-B", FlowState.S1, 1)), true, b.state, 19_000).directives.single())
    }

    @Test fun oldWatchUsesResponseAtRule() {
        val t = WatchResponseTracker()
        assertNull(t.onValue(null, 1_000_000, 0))
        assertEquals(1L, t.onValue(null, 1_050_000, 1)!!.atMs)
        // 1차 열(앱2-하6 전) — null, 1000000, 1000000, 1050000, 1000000, 1050000 의 실제 누름은 둘(번호로는 둘 다 잡힘)
        val s = WatchResponseTracker()
        val seqs = listOf<Long?>(null, 1, 1, 2, 2, 3)
        assertEquals(2, seqs.mapIndexedNotNull { i, v -> s.onValue(v, null, i.toLong()) }.size)
    }
}
