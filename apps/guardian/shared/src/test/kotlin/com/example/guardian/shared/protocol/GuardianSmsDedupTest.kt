// 보호자 문자 같은 사건 묶기 — 같은 kind 59 / 60 / 61초 · 다른 kind · 같은 쪽 다른 id · CANCEL 묶음 · 취소 뒤 새 사건 · 시계 차이 ±90초
package com.example.guardian.shared.protocol

import com.example.guardian.shared.protocol.GuardianSmsDedup.Decision
import com.example.guardian.shared.protocol.GuardianSmsDedup.Record
import com.example.guardian.shared.protocol.GuardianSmsWireKind.ACCIDENT
import com.example.guardian.shared.protocol.GuardianSmsWireKind.CANCEL
import com.example.guardian.shared.protocol.GuardianSmsWireKind.EVENT
import com.example.guardian.shared.protocol.GuardianSmsWireKind.OBSERVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardianSmsDedupTest {
    private val t = 1_761_800_000_000L

    private fun phone(kind: GuardianSmsWireKind, id: String, anchor: Long?, response: Long? = null) = Record(kind, id, Party.PHONE, anchor, response, false, t)
    private fun car(kind: GuardianSmsWireKind, id: String, anchor: Long?, response: Long? = null) = Record(kind, id, Party.CAR, anchor, response, false, t)

    @Test fun sameIdIsAlwaysDuplicate() {
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(listOf(car(EVENT, "event-1", t)), car(EVENT, "event-1", null)))
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(listOf(car(EVENT, "event-1", t)), phone(EVENT, "event-1", t + 600_000)))
    }

    @Test fun otherOwnerSameKind59And60SecondsAreSameIncident_61IsNot() {
        val sent = listOf(phone(EVENT, "event-L1000", t))
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(sent, car(EVENT, "event-9", t + 59_000)))
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(sent, car(EVENT, "event-9", t + 60_000)))
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(sent, car(EVENT, "event-9", t + 61_000)))
        // 앞뒤 어느 쪽이어도 같다
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(sent, car(EVENT, "event-9", t - 59_000)))
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(sent, car(EVENT, "event-9", t - 61_000)))
    }

    @Test fun differentKindIsNotDuplicate() {
        val sent = listOf(phone(EVENT, "event-L1000", t))
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(sent, car(OBSERVE, "event-9", t + 1_000)))
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(sent, car(ACCIDENT, "accident-9", t + 1_000)))
        // ACCIDENT 끼리는 사고 시각으로 묶는다
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(listOf(phone(ACCIDENT, "accident-L1", t)), car(ACCIDENT, "accident-9", t + 1_000)))
    }

    @Test fun sameOwnerDifferentIdIsNewIncident() {
        // 한 주인은 한 사건에 kind 마다 한 번만 내므로 같은 쪽의 다른 id 는 새 사건이다(취소 없이 꺼짐 뒤 다시 시작 등)
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(listOf(car(EVENT, "event-1", t)), car(EVENT, "event-2", t + 5_000)))
    }

    @Test fun cancelGroupsWithEventSentByOtherOwner_once() {
        // 결함 3 의 흐름 — 휴대폰 EVENT(직접) → 차 EVENT(다시 붙은 뒤) → 차 CANCEL(휴대폰은 주인을 넘겨 자기 CANCEL 없음)
        val sent = ArrayList<Record>()
        fun send(r: Record): Decision = GuardianSmsDedup.decide(sent, r).also { if (it == Decision.SEND) sent += r }
        assertEquals(Decision.SEND, send(phone(EVENT, "event-L5000", t)))
        assertEquals(Decision.DUPLICATE, send(car(EVENT, "event-1761800001000", t + 1_000)))
        assertEquals(Decision.SEND, send(car(CANCEL, "event-1761800001000", t + 1_000, response = t + 50_000)))
        assertEquals(listOf(EVENT, CANCEL), sent.map { it.kind })
    }

    @Test fun phoneOwnCancelFirstThenCarCancelIsOne() {
        // 끊긴 동안 휴대폰 주인이 응답을 받아 자기 CANCEL 을 먼저 보냈다 → 다시 붙은 뒤 온 차 EVENT · CANCEL 은 묶인다
        val sent = ArrayList<Record>()
        fun send(r: Record): Decision = GuardianSmsDedup.decide(sent, r).also { if (it == Decision.SEND) sent += r }
        assertEquals(Decision.SEND, send(phone(EVENT, "event-L5000", t)))
        assertEquals(Decision.SEND, send(phone(CANCEL, "event-L5000", t, response = t + 49_500)))
        assertEquals(Decision.DUPLICATE, send(car(EVENT, "event-1761800001000", t + 1_000)))
        assertEquals(Decision.DUPLICATE, send(car(CANCEL, "event-1761800001000", t + 1_000, response = t + 50_000)))
        assertEquals(listOf(EVENT, CANCEL), sent.map { it.kind })
    }

    @Test fun carCancelFirstThenPhoneOwnCancelIsTwo() {
        // 반대 순서 — 휴대폰 자기 문자는 같은 id 만 막으므로 취소가 두 통 갈 수 있다(놓침보다 중복, PM 결정)
        val sent = listOf(phone(EVENT, "event-L5000", t), car(CANCEL, "event-9", t + 1_000, response = t + 50_000))
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(sent, phone(CANCEL, "event-L5000", t, response = t + 49_500)))
    }

    @Test fun cancelWithoutEventIsNotSent() {
        assertEquals(Decision.NO_EVENT_SENT, GuardianSmsDedup.decide(emptyList(), car(CANCEL, "event-1", t, response = t + 10_000)))
        // 다른 사건(61초)의 EVENT 만 있으면 보내지 않는다
        assertEquals(
            Decision.NO_EVENT_SENT,
            GuardianSmsDedup.decide(listOf(phone(EVENT, "event-L1", t)), car(CANCEL, "event-9", t + 61_000, response = t + 70_000)),
        )
        // 같은 id 의 EVENT 를 보냈으면 보낸다
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(listOf(car(EVENT, "event-1", t)), car(CANCEL, "event-1", t, response = t + 40_000)))
    }

    @Test fun newIncidentAfterCancelWithin60sIsSent() {
        // 휴대폰 사건이 응답으로 취소된 뒤(t+40s) 차가 새 사건(t+50s)을 열었다 — 60초 안이어도 새 사건이다
        val sent = listOf(phone(EVENT, "event-L1", t), phone(CANCEL, "event-L1", t, response = t + 40_000))
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(sent, car(EVENT, "event-9", t + 50_000)))
        val sent2 = sent + car(EVENT, "event-9", t + 50_000)
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(sent2, car(CANCEL, "event-9", t + 50_000, response = t + 80_000)))
        // 응답 시각이 뒤 사건 첫 경보와 같으면 끝난 것으로 본다(이하)
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(listOf(phone(EVENT, "a", t), phone(CANCEL, "a", t, t + 50_000)), car(EVENT, "b", t + 50_000)))
        // 응답 시각이 두 첫 경보 사이 (t+1000, t+2000] 밖(= t)이면 그 취소는 끼어 있지 않다 — 묶인다
        val cancelOutside = car(CANCEL, "z", t - 1_000, response = t)
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(listOf(phone(EVENT, "a", t + 1_000), cancelOutside), car(EVENT, "b", t + 2_000)))
        // 앞 첫 경보와 같은 응답 시각(t+1000)도 끼지 않은 것(초과)
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(listOf(phone(EVENT, "a", t + 1_000), car(CANCEL, "z", t, response = t + 1_000)), car(EVENT, "b", t + 2_000)))
    }

    /** 휴대폰 자기 사건(LOCAL · 같은 기기)과 차 지시(SERVER)를 같은 벽시계로 — 중계 시계와 휴대폰 벽시계 차이 ±90초에도 1초 차로 묶인다. */
    @Test fun clockSkew90sBothWays() {
        val mono = 3_000_000L         // 휴대폰 단조 지금
        val server = t + 120_000       // 중계 시계 지금
        val offset = server - mono     // 휴대폰 보정값(중계 − 단조)
        for (skew in listOf(-90_000L, 0L, 90_000L)) {
            val wall = server + skew   // 휴대폰 벽시계 지금(중계 시계와 skew 만큼 어긋남)
            // 휴대폰이 끊긴 동안 연 사건 — 보정값 없이 LOCAL, 첫 경보는 단조 mono − 100s
            val mine = Payload.SendGuardianSms(EVENT, "event-L2900000", WireFacts(SnapshotClock.LOCAL, firstAlertAt = mono - 100_000), false)
            // 차가 연 같은 사건 — SERVER, 첫 경보는 1초 뒤
            val carP = Payload.SendGuardianSms(EVENT, "event-${server - 99_000}", WireFacts(SnapshotClock.SERVER, firstAlertAt = server - 99_000), false)
            val a = GuardianSmsDedup.record(mine, Party.PHONE, offsetMs = null, nowLocalMs = mono, nowWallMs = wall, sameDevice = true)
            val b = GuardianSmsDedup.record(carP, Party.CAR, offsetMs = offset, nowLocalMs = mono, nowWallMs = wall, sameDevice = false)
            assertEquals("skew=$skew", wall - 100_000, a.anchorWallMs)
            assertEquals("skew=$skew", 1_000L, b.anchorWallMs!! - a.anchorWallMs!!)
            assertEquals("skew=$skew", Decision.DUPLICATE, GuardianSmsDedup.decide(listOf(a), b))
        }
    }

    @Test fun recordUsesAccidentTimeForAccidentAndKeepsResponse() {
        val mono = 1_000_000L
        val wall = t
        val p = Payload.SendGuardianSms(ACCIDENT, "accident-L1", WireFacts(SnapshotClock.LOCAL, firstAlertAt = mono - 50_000, accidentAt = mono - 10_000), false)
        assertEquals(wall - 10_000, GuardianSmsDedup.record(p, Party.PHONE, null, mono, wall, sameDevice = true).anchorWallMs)
        val c = Payload.SendGuardianSms(CANCEL, "event-L1", WireFacts(SnapshotClock.LOCAL, firstAlertAt = mono - 50_000, responseAt = mono - 5_000), false)
        val r = GuardianSmsDedup.record(c, Party.PHONE, null, mono, wall, sameDevice = true)
        assertEquals(wall - 50_000, r.anchorWallMs)
        assertEquals(wall - 5_000, r.responseWallMs)
    }

    @Test fun unverifiedClockGroupsByIdOnly() {
        // 다른 기기의 SERVER 사실인데 휴대폰 보정값이 없다 — 근사 시각이라 시각으로 묶지 않는다
        val p = Payload.SendGuardianSms(EVENT, "event-9", WireFacts(SnapshotClock.SERVER, firstAlertAt = t), false)
        val r = GuardianSmsDedup.record(p, Party.CAR, offsetMs = null, nowLocalMs = 1_000_000, nowWallMs = t, sameDevice = false)
        assertNull(r.anchorWallMs)
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(listOf(phone(EVENT, "event-L1", t)), r))
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(listOf(phone(EVENT, "event-9", t)), r))
    }

    // ── be-qa 불합격 재현(2026-10-30) — 진짜 사건 문자를 놓치던 경로 A · B · C ──

    /** A. 차 사건 A 의 CANCEL 이 늦게 오는 동안 휴대폰이 스스로 연 새 사건 B — B 의 EVENT · CANCEL 과 늦은 A CANCEL 이 모두 나간다. */
    @Test fun lateCancelDoesNotSwallowPhoneOwnNewIncident() {
        val sent = ArrayList<Record>()
        fun send(r: Record): Decision = GuardianSmsDedup.decide(sent, r).also { if (it == Decision.SEND) sent += r }
        assertEquals(Decision.SEND, send(car(EVENT, "event-A", t)))
        assertEquals("휴대폰 자기 새 사건 B", Decision.SEND, send(phone(EVENT, "event-LB", t + 30_000)))
        assertEquals("늦게 온 A 취소", Decision.SEND, send(car(CANCEL, "event-A", t, response = t + 10_000)))
        assertEquals("B 응답 시 취소", Decision.SEND, send(phone(CANCEL, "event-LB", t + 30_000, response = t + 70_000)))
        assertEquals(listOf("event-A", "event-LB", "event-A", "event-LB"), sent.map { it.eventId })
    }

    /** 휴대폰 자기 사건은 시각으로 막지 않는다 — 차 EVENT 가 먼저 오고 휴대폰 자기 EVENT 가 나중이면 두 통(놓침보다 중복). */
    @Test fun phoneOwnMessageIsBlockedOnlyBySameId() {
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(listOf(car(EVENT, "event-9", t)), phone(EVENT, "event-L1", t + 1_000)))
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(listOf(car(EVENT, "event-9", t)), phone(EVENT, "event-9", t + 1_000)))
        // 받은 차 지시끼리도 시각으로 묶지 않는다(같은 쪽 다른 id 는 새 사건)
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(listOf(car(EVENT, "event-1", t)), car(EVENT, "event-2", t + 1_000)))
    }

    /** B. 재시작 뒤에도 기록이 남으면(저장 · 복원) 앞서 보낸 EVENT 의 CANCEL 이 나간다. */
    @Test fun recordsSurviveRestartSoCancelIsSent() {
        val ev = car(EVENT, "event-A", t).copy(recordedAtWallMs = t + 35_000)
        val stored = listOf(ev).map(GuardianSmsDedup::encode)          // 재시작 전 Prefs 에 쓴 문자열
        val restored = stored.mapNotNull(GuardianSmsDedup::decode)      // 재시작 뒤 읽음
        assertEquals(listOf(ev), restored)
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(restored, car(CANCEL, "event-A", t, response = t + 10_000)))
        // 기록이 정말 비어 있으면(24시간 지남 · 모두 지우기) 보내지 않는다 — 규칙은 그대로
        assertEquals(Decision.NO_EVENT_SENT, GuardianSmsDedup.decide(emptyList(), car(CANCEL, "event-A", t, response = t + 10_000)))
    }

    @Test fun encodeDecodeRoundTripAndRejectsGarbage() {
        val all = listOf(
            Record(EVENT, "event-1761800000000", Party.CAR, t, null, false, t + 1),
            Record(CANCEL, "event-L5|\n이상한\nid", Party.PHONE, null, t + 9, true, t + 2),
            Record(ACCIDENT, "accident-L1", Party.PHONE, -5, null, false, 0),
        )
        for (r in all) {
            val s = GuardianSmsDedup.encode(r)
            assertEquals("한 줄", false, s.contains('\n'))
            assertEquals(r, GuardianSmsDedup.decode(s))
        }
        for (bad in listOf("", "v1|EVENT", "v9|EVENT|CAR|0|1|-|2|e", "v1|BOOM|CAR|0|1|-|2|e", "v1|EVENT|CAR|x|1|-|2|e", "v1|EVENT|CAR|0|a|-|2|e")) {
            assertNull(bad, GuardianSmsDedup.decode(bad))
        }
    }

    @Test fun pruneKeeps24HoursExactly() {
        val now = t + 100_000_000
        val keep = car(EVENT, "a", t).copy(recordedAtWallMs = now - GuardianSmsDedup.KEEP_MS)
        val drop = car(EVENT, "b", t).copy(recordedAtWallMs = now - GuardianSmsDedup.KEEP_MS - 1)
        val future = car(EVENT, "c", t).copy(recordedAtWallMs = now + 5_000) // 벽시계가 뒤로 감 — 버리지 않는다
        assertEquals(listOf(keep, future), GuardianSmsDedup.prune(listOf(keep, drop, future), now))
        assertEquals(24 * 60 * 60 * 1000L, GuardianSmsDedup.KEEP_MS)
    }

    /** C. 테스트 기록과 실제 기록은 절대 묶지 않는다(같은 id 여도). */
    @Test fun testAndRealNeverGroup() {
        val testEvent = phone(EVENT, "event-L100", t).copy(test = true)
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(listOf(testEvent), car(EVENT, "event-1761800040000", t + 40_000)))
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(listOf(testEvent), phone(EVENT, "event-L100", t)))
        assertEquals("실제 취소는 테스트 EVENT 로 묶이지 않는다", Decision.NO_EVENT_SENT,
            GuardianSmsDedup.decide(listOf(testEvent), car(CANCEL, "event-9", t + 1_000, response = t + 20_000)))
        // 테스트끼리는 같은 규칙
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(listOf(testEvent), car(EVENT, "event-9", t + 1_000).copy(test = true)))
        val p = Payload.SendGuardianSms(EVENT, "event-L1", WireFacts(SnapshotClock.LOCAL, firstAlertAt = 10), true)
        val rec = GuardianSmsDedup.record(p, Party.PHONE, null, 100, t, sameDevice = true)
        assertEquals(true, rec.test)
        assertEquals(t, rec.recordedAtWallMs)
    }

    // ── be-qa 4-2(2026-10-30) — 같은 사건이라 안 보낸 CANCEL 의 응답 시각도 「사이에 낀 취소」로 센다 ──

    /** 앱이 쓰는 두 함수로 한 사건 흐름을 돌린다(보내기는 늘 성공으로 본다). */
    private fun run(seq: List<Record>, now: Long = t + 100_000): Pair<List<Decision>, List<Record>> {
        var recs = emptyList<Record>()
        val out = ArrayList<Decision>()
        for (r in seq) {
            val o = GuardianSmsDedup.decideAndRecord(recs, r, now)
            out += o.decision
            recs = if (o.decision == Decision.SEND) GuardianSmsDedup.afterSend(o.records, r, sent = true) else o.records
        }
        return out to recs
    }

    @Test fun carNewIncidentAfterCarSawResponseFirstIsSent() {
        val (d, recs) = run(listOf(
            phone(EVENT, "event-LA", t),
            phone(CANCEL, "event-LA", t, response = t + 20_000),
            car(EVENT, "event-A", t + 1_000),
            car(CANCEL, "event-A", t + 1_000, response = t + 5_000),
            car(EVENT, "event-B", t + 6_000),
        ))
        assertEquals(listOf(Decision.SEND, Decision.SEND, Decision.DUPLICATE, Decision.DUPLICATE, Decision.SEND), d)
        // 차 CANCEL A 는 「보내지 않음」으로 남았다
        assertEquals(listOf(true, true, false, true), recs.map { it.sent })
        assertEquals(t + 5_000, recs[2].responseWallMs)
        // 같은 흐름에서 「보내지 않음」 기록이 없으면(옛 동작) 차 EVENT B 를 삼킨다 — 고치기 전 실패 재현
        assertEquals(Decision.DUPLICATE, GuardianSmsDedup.decide(recs.filter { it.sent }.dropLast(1), car(EVENT, "event-B", t + 6_000)))
    }

    @Test fun unsentCancelDoesNotCountAsSent() {
        val unsentCancel = car(CANCEL, "event-A", t, response = t + 5_000).copy(sent = false)
        // 보낸 EVENT 가 아니므로 이 사건의 CANCEL 은 여전히 NO_EVENT_SENT
        assertEquals(Decision.NO_EVENT_SENT, GuardianSmsDedup.decide(listOf(unsentCancel), car(CANCEL, "event-A", t, response = t + 5_000)))
        // 보내지 않은 CANCEL 은 같은 id CANCEL 의 중복 판단에 세지 않는다
        val ev = car(EVENT, "event-A", t)
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(listOf(ev, unsentCancel), car(CANCEL, "event-A", t, response = t + 5_000)))
        // 보내지 않은 EVENT(있을 수 없지만)도 세지 않는다
        assertEquals(Decision.SEND, GuardianSmsDedup.decide(listOf(ev.copy(sent = false)), car(EVENT, "event-A", t)))
    }

    @Test fun afterSendRecordsOnlyRealSendsAndUnsentCancels() {
        val ev = car(EVENT, "e", t)
        assertEquals(emptyList<Record>(), GuardianSmsDedup.afterSend(emptyList(), ev, sent = false))
        assertEquals(listOf(ev), GuardianSmsDedup.afterSend(emptyList(), ev, sent = true))
        val c = car(CANCEL, "e", t, response = t + 1)
        assertEquals(listOf(c.copy(sent = false)), GuardianSmsDedup.afterSend(emptyList(), c, sent = false))
        // 응답 시각 없는 CANCEL 은 「낀 취소」로 쓸 수 없어 남기지 않는다
        assertEquals(emptyList<Record>(), GuardianSmsDedup.afterSend(emptyList(), car(CANCEL, "e", t), sent = false))
        // NO_EVENT_SENT CANCEL 도 응답 시각을 남긴다
        val o = GuardianSmsDedup.decideAndRecord(emptyList(), c, t + 10)
        assertEquals(Decision.NO_EVENT_SENT, o.decision)
        assertEquals(listOf(c.copy(sent = false)), o.records)
        // decideAndRecord 는 24시간 지난 기록을 먼저 정리한다
        val old = car(EVENT, "e", t).copy(recordedAtWallMs = t - GuardianSmsDedup.KEEP_MS - 1)
        assertEquals(Decision.NO_EVENT_SENT, GuardianSmsDedup.decideAndRecord(listOf(old), c, t).decision)
    }

    @Test fun decodeOldV1LineAsSent_andV2RoundTripsSentFlag() {
        val v1 = "v1|EVENT|CAR|0|$t|-|${t + 5}|event-A"
        assertEquals(Record(EVENT, "event-A", Party.CAR, t, null, false, t + 5, sent = true), GuardianSmsDedup.decode(v1))
        val unsent = car(CANCEL, "event-A", t, response = t + 5_000).copy(sent = false)
        val line = GuardianSmsDedup.encode(unsent)
        assertEquals(true, line.startsWith("v2|"))
        assertEquals(unsent, GuardianSmsDedup.decode(line))
        for (bad in listOf("$line|x", line.dropLast(1) + "2", "v2|EVENT|CAR|0|$t|-|1|e", "v1|EVENT|CAR|0|$t|-|1|e|1")) {
            assertNull(bad, GuardianSmsDedup.decode(bad))
        }
    }
}
