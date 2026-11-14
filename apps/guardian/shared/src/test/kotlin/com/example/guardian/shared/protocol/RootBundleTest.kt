// 뿌리 묶음 공용 순수 함수 시험 — smsDone 범위 · 코덱(A-1), 차 보낼 함(A-2), 휴대폰 대조 · 방어 · 취소 뒤 사건 문자(A-3), 주인 · 넘겨받기(A-4), 문구(A-5)
package com.example.guardian.shared.protocol

import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Handover
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.Ownership
import com.example.guardian.shared.protocol.GuardianSmsDedup.Decision
import com.example.guardian.shared.protocol.GuardianSmsDedup.Record
import com.example.guardian.shared.protocol.GuardianSmsWireKind.ACCIDENT
import com.example.guardian.shared.protocol.GuardianSmsWireKind.CANCEL
import com.example.guardian.shared.protocol.GuardianSmsWireKind.EVENT
import com.example.guardian.shared.text.ForbiddenWords
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootBundleTest {
    private val wall = 1_793_000_000_000L

    // ── A-1 smsDone ──

    @Test fun smsDoneSelectsCurrentPairRealOpenOrRecent() {
        fun e(id: String, test: Boolean = false, start: Long = wall, settled: Long? = null) =
            SmsDone.Entry(SmsDone(id, EVENT, SmsDoneState.SENT), test, start, settled)
        val joined = wall - 1_000
        val got = SmsDone.select(
            listOf(
                e("open"), e("test", test = true), e("before-join", start = joined - 1), e("at-join", start = joined),
                e("ended-10min", settled = wall - SmsDone.WINDOW_MS), e("ended-old", settled = wall - SmsDone.WINDOW_MS - 1),
            ),
            nowWallMs = wall, pairJoinedAtWallMs = joined,
        )
        assertEquals("경계 — 시작 == 참여 시각이면 실음", listOf("open", "at-join", "ended-10min"), got.map { it.eventId })
        assertEquals(SmsDoneState.NOT_SENT, SmsDone.of(Record(CANCEL, "x", Party.PHONE, null, null, false, wall, sent = false)).done)
    }

    @Test fun smsDoneCodecIsOptionalAndTolerant() {
        val f = FlowStatus.of(FlowSnapshot(), Owner.PHONE)
        assertFalse("싣지 않으면 키도 없음", f.toJson().containsKey("smsDone"))
        val with = f.copy(smsDone = listOf(SmsDone("event-1", EVENT, SmsDoneState.PENDING)))
        val back = FlowStatus.fromJson(JsonObj(Json.parseObject(Json.encode(with.toJson()))))
        assertEquals(with.smsDone, back.smsDone)
        assertNull("옛 판 — 없음", FlowStatus.fromJson(JsonObj(Json.parseObject(Json.encode(f.toJson())))).smsDone)
        // 틀린 항목은 그 항목만 버린다
        val mixed = SmsDone.listFromJsonOrNull(listOf(mapOf("eventId" to "a", "kind" to "EVENT", "done" to "SENT"), mapOf("eventId" to "b", "kind" to "NOPE", "done" to "SENT")))
        assertEquals(listOf("a"), mixed!!.map { it.eventId })
        assertNull(SmsDone.listFromJsonOrNull("x"))
    }

    // ── A-2 보낼 함 ──

    private var ids = 0
    private fun sms(kind: GuardianSmsWireKind, id: String, test: Boolean = false) = Command(
        "c${ids++}", CommandType.SEND_GUARDIAN_SMS, Payload.SendGuardianSms(kind, id, WireFacts(SnapshotClock.SERVER), test).toJson(),
        30_000, test, Party.CAR, CommandTo.PHONE,
    )

    @Test fun eventIsHeldUntilNextResponseOrFourSeconds() {
        val ev = sms(EVENT, "event-1")
        var o = GuardianSmsOutbox().add(listOf(ev), 1_000)
        val (o1, first) = o.buildRequest(1_500, "event-1"); o = o1
        assertTrue("보류 — 다음 요청의 응답 전", first.isEmpty())
        o = o.onResponseHandled(o.lastRequestNo) // 보류 뒤 만든 요청의 응답을 처리
        val (o2, second) = o.buildRequest(2_000, "event-1"); o = o2
        assertEquals(listOf(ev.id), second.map { it.id })
        // 상한 4초 — 응답이 오지 않아도 풀린다
        val b = GuardianSmsOutbox().add(listOf(ev), 1_000)
        assertTrue(b.buildRequest(4_999, "event-1").second.isEmpty())
        assertEquals(1, b.buildRequest(5_000, "event-1").second.size)
    }

    @Test fun cancelBeforeCarriedDropsBoth_afterCarriedKeepsBothEventFirst() {
        val ev = sms(EVENT, "event-1"); val cancel = sms(CANCEL, "event-1")
        val dropped = GuardianSmsOutbox().add(listOf(ev), 0).add(listOf(cancel), 500)
        assertTrue(dropped.items.isEmpty())
        assertTrue(dropped.buildRequest(10_000, null).second.isEmpty())

        var o = GuardianSmsOutbox().add(listOf(ev), 0)
        o = o.buildRequest(4_000, "event-1").first // 실림
        o = o.add(listOf(cancel), 5_000)
        val (o2, carried) = o.buildRequest(6_000, null)
        assertEquals("같은 지시 id 로 다시, 사건 문자가 앞", listOf(ev.id, cancel.id), carried.map { it.id })
        // 확인되면 지움 — PENDING 은 남김
        val pending = o2.onSmsDone(listOf(SmsDone("event-1", EVENT, SmsDoneState.PENDING)))
        assertEquals(2, pending.items.size)
        val done = o2.onSmsDone(listOf(SmsDone("event-1", EVENT, SmsDoneState.SENT), SmsDone("event-1", CANCEL, SmsDoneState.NOT_SENT)))
        assertTrue(done.items.isEmpty())
        assertTrue(done.confirmed("event-1", listOf(SmsDone("event-1", EVENT, SmsDoneState.SENT))))
    }

    @Test fun keepsUntilTenMinutesAfterIncidentEnds_andIgnoresTestAndOthers() {
        val acc = sms(ACCIDENT, "accident-1")
        var o = GuardianSmsOutbox().add(listOf(acc, sms(EVENT, "event-9", test = true)), 0)
        assertEquals("테스트 문자는 넣지 않음", 1, o.items.size)
        assertEquals("사고 문자는 보류 없음", 1, o.buildRequest(0, "accident-1").second.size)
        o = o.buildRequest(1_000, null).first // 사건이 끝남(1초)
        assertEquals(1, o.buildRequest(1_000 + GuardianSmsOutbox.KEEP_AFTER_END_MS, null).second.size)
        assertTrue(o.buildRequest(1_001 + GuardianSmsOutbox.KEEP_AFTER_END_MS, null).first.items.isEmpty())
        // 같은 지시 id 를 두 번 넣어도 하나
        assertEquals(1, GuardianSmsOutbox().add(listOf(acc), 0).add(listOf(acc), 1).items.size)
    }

    // ── A-3 대조 · 방어 · 취소 뒤 사건 문자 ──

    private fun carSnap(state: FlowState, id: String = "event-5", sent: Boolean = true, response: Long? = null, test: Boolean = false) =
        FlowSnapshot(
            state = state, test = test, incidentActive = true, incidentSmsSent = sent, accidentSent = state == FlowState.S5,
            facts = IncidentFacts(firstAlertAtMs = 0, responseAtMs = response, eventId = id),
        )

    private fun rec(kind: GuardianSmsWireKind, id: String = "event-5", sent: Boolean = true) = Record(kind, id, Party.CAR, null, null, false, wall, sent)

    @Test fun reconcileSendsMissingEventAfter10sOrImmediatelyOnTakeover() {
        val s3 = carSnap(FlowState.S3)
        val r1 = CarSmsReconcile.step(s3, emptyList(), emptyMap(), 100_000, immediate = false)
        assertTrue(r1.actions.isEmpty())
        val r2 = CarSmsReconcile.step(s3, emptyList(), r1.waiting, 109_999, false)
        assertTrue(r2.actions.isEmpty())
        val r3 = CarSmsReconcile.step(s3, emptyList(), r2.waiting, 110_000, false)
        assertEquals(listOf(CarSmsReconcile.Action(EVENT, "event-5")), r3.actions)
        assertEquals(listOf(CarSmsReconcile.Action(EVENT, "event-5")), CarSmsReconcile.step(carSnap(FlowState.S4), emptyList(), emptyMap(), 0, true).actions)
        assertEquals(listOf(CarSmsReconcile.Action(ACCIDENT, "event-5")), CarSmsReconcile.step(carSnap(FlowState.S5), emptyList(), emptyMap(), 0, true).actions)
    }

    @Test fun reconcileDoesNothingWhenRecordedRespondedTestOrNotYetSent() {
        fun none(s: FlowSnapshot?, records: List<Record> = emptyList()) =
            assertTrue(CarSmsReconcile.step(s, records, emptyMap(), 0, immediate = true).actions.isEmpty())
        none(carSnap(FlowState.S3), listOf(rec(EVENT)))
        none(carSnap(FlowState.S3, response = 5))
        none(carSnap(FlowState.S3, test = true))
        none(carSnap(FlowState.S3, sent = false))
        none(carSnap(FlowState.S1R))
        none(carSnap(FlowState.S3), listOf(rec(CANCEL, sent = false))) // 취소가 먼저 닿음
        none(null)
    }

    @Test fun reconcileCancelsWhenSentAndCarHasResponse() {
        val s1r = carSnap(FlowState.S1R, response = 5)
        assertEquals(listOf(CarSmsReconcile.Action(CANCEL, "event-5")), CarSmsReconcile.step(s1r, listOf(rec(EVENT)), emptyMap(), 0, false).actions)
        assertTrue(CarSmsReconcile.step(s1r, listOf(rec(EVENT), rec(CANCEL)), emptyMap(), 0, false).actions.isEmpty())
    }

    @Test fun eventAfterSameIdCancelRecordIsNotSent_onlySameId() {
        val unsentCancel = Record(CANCEL, "event-5", Party.CAR, null, wall, false, wall, sent = false)
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(listOf(unsentCancel), Record(EVENT, "event-5", Party.CAR, null, null, false, wall)))
        assertEquals("다른 사건 id 는 막지 않음", Decision.SEND, GuardianSmsDedup.decide(listOf(unsentCancel), Record(EVENT, "event-6", Party.CAR, null, null, false, wall)))
    }

    @Test fun phoneGuardStaleAndBeforeJoin() {
        val now = 2_000_000_000_000L
        fun c(kind: GuardianSmsWireKind, id: String, joined: Long? = now - 60_000, seen: Set<String> = emptySet()) =
            PhoneSmsGuard.check(kind, id, now, joined, seen)
        assertEquals(PhoneSmsGuard.Verdict.ALLOW, c(EVENT, "event-${now - 30_000}"))
        assertEquals(PhoneSmsGuard.Verdict.ALLOW, c(EVENT, "event-${now - PhoneSmsGuard.STALE_MS}", joined = null))
        assertEquals(PhoneSmsGuard.Verdict.STALE, c(EVENT, "event-${now - PhoneSmsGuard.STALE_MS - 1}"))
        assertEquals(PhoneSmsGuard.Verdict.BEFORE_JOIN, c(EVENT, "event-${now - 90_000}"))
        assertEquals("본 사건이면 허용", PhoneSmsGuard.Verdict.ALLOW, c(EVENT, "event-${now - 90_000}", seen = setOf("event-${now - 90_000}")))
        assertEquals("시각 모르는 id 는 참여 전으로", PhoneSmsGuard.Verdict.BEFORE_JOIN, c(ACCIDENT, "accident-L123"))
        assertEquals("취소는 막지 않음", PhoneSmsGuard.Verdict.ALLOW, c(CANCEL, "event-L1"))
        // 시각을 읽을 수 없어도 지금 차의 진행 중 사건이면 허용(시동 뒤 데이터 없던 차의 사고), 다른 L id 는 여전히 참여 전
        assertEquals(PhoneSmsGuard.Verdict.ALLOW, PhoneSmsGuard.check(ACCIDENT, "accident-L9", now, now - 60_000, emptySet(), liveCarEventId = "accident-L9"))
        assertEquals(PhoneSmsGuard.Verdict.BEFORE_JOIN, PhoneSmsGuard.check(ACCIDENT, "accident-L8", now, now - 60_000, emptySet(), liveCarEventId = "accident-L9"))
        // sec-audit — 시각을 읽을 수 없는 id 는 차 흐름의 중계 시각 시작으로 참여 전을 가린다(진행 중이어도 참여 30분 전 사건은 막고, 넘겨받아 live 가 없어도 참여 뒤 사건은 보냄)
        assertEquals(PhoneSmsGuard.Verdict.BEFORE_JOIN, PhoneSmsGuard.check(ACCIDENT, "accident-L9", now, now - 60_000, emptySet(), liveCarEventId = "accident-L9", carFlowStartServerMs = now - 30 * 60_000))
        assertEquals(PhoneSmsGuard.Verdict.ALLOW, PhoneSmsGuard.check(ACCIDENT, "accident-L9", now, now - 60_000, emptySet(), liveCarEventId = null, carFlowStartServerMs = now - 10_000))
        assertEquals("경계 — 시작 == 참여 시각이면 허용", PhoneSmsGuard.Verdict.ALLOW, PhoneSmsGuard.check(ACCIDENT, "accident-L9", now, now - 60_000, emptySet(), carFlowStartServerMs = now - 60_000))
        // id 시각과 넘겨받은 시각이 둘 다 있으면 id 시각을 쓴다(be-qa)
        val joined = now - 60_000
        assertEquals(PhoneSmsGuard.Verdict.BEFORE_JOIN, PhoneSmsGuard.check(EVENT, "event-${joined - 1}", now, joined, emptySet(), carFlowStartServerMs = joined + 5_000))
        // 시각을 읽을 수 있는 참여 전 사건은 진행 중이어도 지금 규칙(sec-audit 기준 그대로)
        assertEquals(PhoneSmsGuard.Verdict.BEFORE_JOIN, PhoneSmsGuard.check(EVENT, "event-${now - 90_000}", now, now - 60_000, emptySet(), liveCarEventId = "event-${now - 90_000}"))
        assertEquals("경계 — 사건 시각 == 참여 시각이면 허용", PhoneSmsGuard.Verdict.ALLOW, c(EVENT, "event-${now - 60_000}"))
        assertEquals(123L, PhoneSmsGuard.eventServerMs("accident-123"))
        assertNull(PhoneSmsGuard.eventServerMs("event-L123"))
    }

    // ── A-4 주인 · 넘겨받기 ──

    private fun flow(state: FlowState, owner: Owner, id: String?, active: Boolean = id != null, test: Boolean = false) =
        FlowStatus.of(FlowSnapshot(state = state, test = test, incidentActive = active, facts = IncidentFacts(eventId = id)), owner)

    @Test fun ownerNeedsFreshAndClaim() {
        assertEquals(Owner.CAR, Ownership.decide(20_000, 10_000, carClaimsOwner = true))
        assertEquals("내려놓음", Owner.PHONE, Ownership.decide(20_000, 10_000, carClaimsOwner = false))
        assertEquals("낡음", Owner.PHONE, Ownership.decide(20_001, 10_000, carClaimsOwner = true))
    }

    @Test fun incidentLostOnlyWhenAlertingIdVanishesOrChanges() {
        val s1 = flow(FlowState.S1, Owner.CAR, "event-1")
        assertTrue(Handover.carIncidentLost(s1, flow(FlowState.OFF, Owner.PHONE, null)))
        assertTrue(Handover.carIncidentLost(s1, flow(FlowState.S1, Owner.CAR, "event-2")))
        assertFalse("같은 사건", Handover.carIncidentLost(s1, flow(FlowState.S3, Owner.CAR, "event-1")))
        assertFalse("정상 응답 — id 남음", Handover.carIncidentLost(s1, flow(FlowState.S1R, Owner.CAR, "event-1", active = false)))
        assertFalse("응답 뒤에서 사라짐은 아님", Handover.carIncidentLost(flow(FlowState.S1R, Owner.CAR, "event-1"), flow(FlowState.OFF, Owner.PHONE, null)))
        assertFalse("흐름 모름", Handover.carIncidentLost(s1, null))
        assertFalse("경보 중 에어백 — 사고 id 로 이어 감", Handover.carIncidentLost(flow(FlowState.S4, Owner.CAR, "event-1"), flow(FlowState.S5, Owner.CAR, "accident-2")))
        assertFalse("테스트", Handover.carIncidentLost(flow(FlowState.S1, Owner.CAR, "event-1", test = true), flow(FlowState.OFF, Owner.PHONE, null)))
    }

    @Test fun carAcceptsOnlyFreshRealPhoneIncidentWhenIdle() {
        val idle = FlowSnapshot(state = FlowState.S0)
        val phone = flow(FlowState.S3, Owner.PHONE, "event-1")
        assertTrue(Handover.carMayAccept(idle, phone, phoneFresh = true))
        assertFalse("묵은 흐름", Handover.carMayAccept(idle, phone, phoneFresh = false))
        assertFalse("내 사건 진행 중", Handover.carMayAccept(idle.copy(state = FlowState.S1, incidentActive = true), phone, true))
        assertFalse("휴대폰도 차가 주인이라고 봄", Handover.carMayAccept(idle, flow(FlowState.S3, Owner.CAR, "event-1"), true))
        assertFalse("테스트", Handover.carMayAccept(idle, flow(FlowState.S3, Owner.PHONE, "event-1", test = true), true))
        assertFalse("사건 아님", Handover.carMayAccept(idle, flow(FlowState.S0, Owner.PHONE, null), true))
    }

    @Test fun phoneHoldsLeaseUntilCarCarriesSameId_andCarStartGrace() {
        val mine = FlowSnapshot(state = FlowState.S3, incidentActive = true, facts = IncidentFacts(eventId = "event-1"))
        assertFalse(Handover.phoneMayRelease(mine, flow(FlowState.S0, Owner.CAR, null)))
        assertFalse(Handover.phoneMayRelease(mine, flow(FlowState.S3, Owner.PHONE, "event-1")))
        assertTrue(Handover.phoneMayRelease(mine, flow(FlowState.S3, Owner.CAR, "event-1")))
        assertTrue("사건 없음", Handover.phoneMayRelease(FlowSnapshot(state = FlowState.S0), null))
        assertFalse(Handover.carWristAllowed(0, 5_999, sawFreshPhoneFlow = false))
        assertTrue(Handover.carWristAllowed(0, 6_000, sawFreshPhoneFlow = false))
        assertTrue(Handover.carWristAllowed(0, 100, sawFreshPhoneFlow = true))
        // PM 결정 16 — 유예 중에도 나쁘지 않은 판정은 넣는다(S0 에 들어가 에어백 전이를 놓치지 않게), 나쁜 판정만 막는다
        for (v in listOf(com.example.guardian.shared.bio.WristVerdict.NORMAL, com.example.guardian.shared.bio.WristVerdict.CHECK_WEAR)) {
            assertTrue(v.name, Handover.carWristAllowed(0, 100, false, v))
        }
        for (v in listOf(com.example.guardian.shared.bio.WristVerdict.PULSE_WEAK, com.example.guardian.shared.bio.WristVerdict.PULSE_ABSENT)) {
            assertFalse(v.name, Handover.carWristAllowed(0, 5_999, false, v))
            assertTrue(v.name, Handover.carWristAllowed(0, 6_000, false, v))
            assertTrue(v.name, Handover.carWristAllowed(0, 100, true, v))
        }
    }

    // ── A-5 문구 ──

    @Test fun delayedPhrasesHaveNoCompletionFormOrForbiddenWords() {
        val voices = listOf(Phrase.VOICE_S3_SMS_DELAYED, Phrase.VOICE_S4_SMS_DELAYED, Phrase.VOICE_S5_SMS_DELAYED)
        for (p in voices + Phrase.SCREEN_SMS_CONFIRMED + Phrase.SCREEN_SMS_NOT_SENT) assertTrue(p.name, ForbiddenWords.violations(p.ko).isEmpty())
        for (p in voices) {
            assertTrue(p.name, p.ko.contains("늦어질 수 있습니다"))
            assertFalse("완료형 없음 ${p.name}", p.ko.contains("알렸습니다") || p.ko.contains("보냈습니다"))
        }
    }
}
