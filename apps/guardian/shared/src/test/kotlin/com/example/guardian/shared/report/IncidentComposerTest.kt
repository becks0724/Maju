// 휴대폰 본문 조립 시험 — 종류별 본문 · G9 순서 · 위치 맨 앞 · 보호자 필수 · 동의 조합 · 테스트 접두 · 미리보기 · 차 상태 없음
package com.example.guardian.shared.report

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.text.ForbiddenWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class IncidentComposerTest {
    private fun kst(h: Int, m: Int, s: Int = 0) =
        LocalDateTime.of(2026, 10, 30, h, m, s).atZone(Reports.SEOUL).toInstant().toEpochMilli()

    /** 휴대폰 단조 500,000 = 10:42, 중계 = 단조 + OFFSET. */
    private val nowLocal = 500_000L
    private val offset = 1_000_000L
    private fun local(h: Int, m: Int, s: Int = 0) = nowLocal - (kst(10, 42) - kst(h, m, s))
    private fun server(h: Int, m: Int, s: Int = 0) = local(h, m, s) + offset

    private val profile = Profile("홍길동", LocalDate.of(1968, 5, 1), "김○○", "010-0000-0000", "흰색", "12가3456")
    private val all = Consents(true, true, true)
    private val parked = Vehicle(0.0, Gear.P, true, false, false, "현대", "아반떼", 2027, "서울 종로구 세종대로 175", 37.4, 126.8, naviAt = server(10, 42))
    private val watch = Watch(WristVerdict.PULSE_ABSENT, 48, kst(10, 41, 35), true, WatchSource.SENSOR_SDK, false, null, ageMs = 0)
    private val phoneLoc = Loc(37.575903, 126.976901, 8.0, 0.0, kst(10, 42), "서울 종로구 세종대로 172")

    private fun ctx(
        consents: Consents = all, vehicle: Vehicle? = parked, w: Watch? = watch, loc: Loc? = phoneLoc, p: Profile = profile, contacted: Boolean = false,
    ) = IncidentComposer.PhoneContext(p, consents, vehicle, w, loc, nowLocal, kst(10, 42), contacted, serverOffsetMs = offset)

    private val facts = WireFacts(SnapshotClock.SERVER, server(10, 37), server(10, 39), server(10, 41), null, null, false)
    private fun sms(kind: GuardianSmsWireKind, f: WireFacts = facts, test: Boolean = false) = Payload.SendGuardianSms(kind, "event-1", f, test)
    private fun ok(r: ReportResult) = (r as ReportResult.Ok).text
    private fun guardian(kind: GuardianSmsWireKind, c: IncidentComposer.PhoneContext = ctx(), f: WireFacts = facts, test: Boolean = false) =
        IncidentComposer.guardianSms(sms(kind, f, test), Party.CAR, offset, c)

    @Test fun incidentFromCarFactsMatchesG9() {
        val expected = """
            [앱 안내] 37.57590,126.97690 운전자 응답 없음
            위치: 서울 종로구 세종대로 172 부근(±8m) · 10:42
            상태: 차량 정차(10:39부터) · 응답 없음 5분
            경보: 10:37 1차 경보 → 10:39 정차 → 10:41 무응답
            워치(10:37~10:42): 심박 48회/분, 10:41:35부터 맥박 신호 잡히지 않음, 착용 중
            운전자: 홍길동 · 만 58세
            차량: 현대 아반떼(2027) 흰색 12가3456
            보호자: 김○○ 010-0000-0000
            위급하면 119 에 전화하세요
            운전자 무응답 확인 앱 자동 작성 · 의료 판정 아님
        """.trimIndent()
        assertEquals(expected, ok(guardian(GuardianSmsWireKind.EVENT)))
    }

    @Test fun full119UsesSameFactsAndPreviewFlagPassesThrough() {
        val p = Payload.Open119Compose("event-1", facts, previewOnly = false, test = false)
        val text = ok(IncidentComposer.compose119(p, Party.CAR, offset, ctx(contacted = true)))
        val lines = text.lines()
        assertEquals("[119 구조요청] 37.57590,126.97690 운전자 응답 없음", lines[0])
        // G9 순서 — 위치 · 상태 · 경보 · 워치 · 운전자 · 차량 · 보호자 · 꼬리
        val heads = lines.drop(1).map { it.substringBefore(":").substringBefore("(") }
        assertEquals(listOf("위치", "상태", "경보", "워치", "운전자", "차량", "보호자", "확인 전화"), heads)
        assertTrue(lines[7].endsWith("(연락함)"))
        val test = ok(IncidentComposer.compose119(p.copy(previewOnly = true, test = true), Party.CAR, offset, ctx()))
        assertTrue(test.startsWith("[테스트] [119 구조요청] 37.57590,126.97690"))
        assertEquals("실제 상황 아님", test.lines()[1])
    }

    @Test fun eachKindPicksItsForm() {
        val cancel = ok(guardian(GuardianSmsWireKind.CANCEL, f = facts.copy(responseAt = server(10, 43))))
        assertTrue(cancel, cancel.contains("운전자가 10:43에 응답했습니다"))
        val accident = ok(guardian(GuardianSmsWireKind.ACCIDENT, f = facts.copy(accidentAt = server(10, 42))))
        assertTrue(accident.contains("에어백 전개 신호"))
        assertTrue(accident.lines()[0].startsWith("[앱 안내] 37.57590,126.97690"))
        val observe = ok(guardian(GuardianSmsWireKind.OBSERVE))
        assertTrue(observe.contains("쉬는 중"))
        // 취소 시각이 없으면 지금 시각
        assertTrue(ok(guardian(GuardianSmsWireKind.CANCEL)).contains("10:42에 응답"))
    }

    @Test fun guardianMissingFails() {
        for (p in listOf(profile.copy(guardianName = null), profile.copy(guardianPhone = " "))) {
            for (k in GuardianSmsWireKind.entries) assertEquals(ReportResult.Fail(ReportFailure.GUARDIAN_MISSING), guardian(k, ctx(p = p)))
            val r = IncidentComposer.compose119(Payload.Open119Compose("e", facts, false, false), Party.CAR, offset, ctx(p = p))
            assertEquals(ReportResult.Fail(ReportFailure.GUARDIAN_MISSING), r)
        }
    }

    @Test fun consentCombinations() {
        for (e in listOf(true, false)) for (h in listOf(true, false)) for (l in listOf(true, false)) {
            val c = ctx(consents = Consents(e, h, l))
            val g = guardian(GuardianSmsWireKind.EVENT, c)
            val f = IncidentComposer.compose119(Payload.Open119Compose("e", facts, false, false), Party.CAR, offset, c)
            when {
                !e -> { assertEquals(ReportResult.Fail(ReportFailure.EMERGENCY_INFO_CONSENT_MISSING), g); assertEquals(ReportResult.Fail(ReportFailure.EMERGENCY_INFO_CONSENT_MISSING), f) }
                !l -> {
                    assertEquals("위치 동의 없으면 자동 문자 없음", ReportResult.Fail(ReportFailure.AUTO_SMS_NOT_ALLOWED), g)
                    val t = ok(f)
                    assertFalse(t.lines()[0].contains("37."))
                    assertTrue(t.lines()[1].startsWith("위치: 위치 제공 동의 없음"))
                }
                else -> {
                    val t = ok(g)
                    assertEquals("워치 줄은 건강 동의가 있을 때만", h, t.contains("워치("))
                    assertTrue(t.lines()[0].contains("37.57590,126.97690"))
                }
            }
            for (r in listOf(g, f)) if (r is ReportResult.Ok) assertTrue(ForbiddenWords.violations(r.text).isEmpty())
        }
    }

    @Test fun testPrefixOnGuardianSms() {
        val t = ok(guardian(GuardianSmsWireKind.EVENT, test = true))
        assertTrue(t.startsWith("[테스트] [앱 안내] 37.57590,126.97690"))
        assertEquals("실제 상황 아님", t.lines()[1])
    }

    @Test fun carStateMissingUsesPhoneSpeedAndNaviFallback() {
        // 휴대폰 주인 — 차 상태 없음, 휴대폰 GPS 속도
        val moving = phoneLoc.copy(speedKmh = 62.4)
        val t = ok(guardian(GuardianSmsWireKind.EVENT, ctx(vehicle = null, loc = moving), f = facts.copy(carStateMissing = true)))
        assertTrue(t, t.contains("상태: 차 상태 없음 · 휴대폰 기준 차량 주행 중 62km/h"))
        assertTrue(t.contains("차량: 차종 확인 안 됨 흰색 12가3456"))
        // 휴대폰 위치가 없으면 차 내비 위치
        val navi = ok(guardian(GuardianSmsWireKind.EVENT, ctx(loc = null)))
        assertTrue(navi, navi.lines()[0].contains("37.40000,126.80000"))
        assertTrue(navi.lines()[1].startsWith("위치: 서울 종로구 세종대로 175 부근 ·"))
    }

    /** 코드 리뷰 11-09 공통 하2(G9) — 속도를 모르면 「주행 중」이라고 쓰지 않고 모른다는 사실을 쓴다. */
    @Test fun unknownSpeedIsNotWrittenAsDriving() {
        val cases = listOf(
            "차 없음 · 휴대폰 위치 없음" to ctx(vehicle = null, loc = null),
            "차 없음 · 휴대폰 속도 없음" to ctx(vehicle = null, loc = phoneLoc.copy(speedKmh = null)),
            // 속도 null + 기어 P 는 정지로 본다(B-4) — 속도를 모르는 경우는 기어 D 로 본다
            "차 있음 · 속도 null · 기어 D" to ctx(vehicle = parked.copy(speedKmh = null, gear = Gear.D)),
        )
        for ((tag, c) in cases) {
            val p = Payload.Open119Compose("e", facts, previewOnly = false, test = false)
            val texts = listOf(ok(IncidentComposer.compose119(p, Party.CAR, offset, c))) +
                listOf(guardian(GuardianSmsWireKind.EVENT, c)).filterIsInstance<ReportResult.Ok>().map { it.text }
            for (t in texts) {
                val state = t.lines().first { it.startsWith("상태:") }
                assertFalse("$tag: $state", state.contains("주행 중"))
                assertTrue("$tag: $state", state.contains("속도 확인 안 됨"))
                assertTrue(ForbiddenWords.violations(t).isEmpty())
            }
        }
    }

    // ── 공통 하4 — 위치 줄 시각은 잰 시각, 30초 넘은 위치(휴대폰 GPS · 차 내비)는 쓰지 않는다 ──

    @Test fun phoneLocationLineShowsMeasuredTime() {
        val t = ok(guardian(GuardianSmsWireKind.EVENT, ctx(loc = phoneLoc.copy(at = kst(10, 41, 40)))))
        assertEquals("위치: 서울 종로구 세종대로 172 부근(±8m) · 10:41", t.lines()[1])
    }

    @Test fun stalePhoneLocationFallsBackToFreshNavi() {
        val oldPhone = phoneLoc.copy(at = kst(10, 41, 29)) // 31초 전
        val t = ok(guardian(GuardianSmsWireKind.EVENT, ctx(loc = oldPhone, vehicle = parked.copy(naviAt = server(10, 41, 50)))))
        assertTrue(t, t.lines()[0].contains("37.40000,126.80000"))
        assertEquals("위치: 서울 종로구 세종대로 175 부근 · 10:41", t.lines()[1])
    }

    @Test fun staleOrUntimedNaviIsNotUsed() {
        val noLoc = listOf(
            parked.copy(naviAt = server(10, 41, 29)), // 31초 전
            parked.copy(naviAt = null), // 잰 시각 모름(옛 차 빌드)
        )
        for (v in noLoc) {
            val t = ok(guardian(GuardianSmsWireKind.EVENT, ctx(loc = null, vehicle = v)))
            assertEquals("[앱 안내] 운전자 응답 없음", t.lines()[0])
            assertEquals("위치: 위치 확인 안 됨 · 직접 입력 필요 · 10:42", t.lines()[1])
        }
        // 휴대폰 시계 보정값이 없으면 내비 시각을 되돌릴 수 없다
        val c = ctx(loc = null).copy(serverOffsetMs = null)
        assertTrue(ok(guardian(GuardianSmsWireKind.EVENT, c)).lines()[1].startsWith("위치: 위치 확인 안 됨"))
        // 119 작성 · 초단문도 같은 규칙
        val p = Payload.Open119Compose("event-1", facts, false, false)
        assertFalse(ok(IncidentComposer.compose119(p, Party.CAR, offset, ctx(loc = null, vehicle = noLoc[0]))).contains("37.4"))
        val ultra = Reports.ultraShort119(IncidentComposer.input(facts.toLocal("e", offset, nowLocal, false).facts, false, ctx(loc = null, vehicle = noLoc[0])))
        assertFalse(ok(ultra).contains("37.4"))
    }

    @Test fun staleOrWeakWatch() {
        assertFalse("30초 넘은 워치 값은 줄 없음", ok(guardian(GuardianSmsWireKind.EVENT, ctx(w = watch.copy(ageMs = 30_001)))).contains("워치("))
        assertTrue(ok(guardian(GuardianSmsWireKind.EVENT, ctx(w = watch.copy(ageMs = 30_000)))).contains("워치("))
        assertFalse("나이를 모르는 값은 오래된 값", ok(guardian(GuardianSmsWireKind.EVENT, ctx(w = watch.copy(ageMs = null)))).contains("워치("))
        val weak = ok(guardian(GuardianSmsWireKind.EVENT, ctx(w = watch.copy(verdict = WristVerdict.PULSE_WEAK))))
        assertTrue(weak.contains("맥박 신호 약해짐"))
        val wear = ok(guardian(GuardianSmsWireKind.EVENT, ctx(w = watch.copy(verdict = WristVerdict.CHECK_WEAR))))
        assertTrue(wear.contains("착용 확인 안 됨"))
    }

    /** 2026-11-02 상 1 — 워치가 신호를 못 잡고 있다고 알린 값(noSignal)은 신선해도 워치 줄을 넣지 않는다. 보호자 문자 · 119 작성 둘 다. */
    @Test fun noSignalWatchValueHasNoWatchLine() {
        val lost = ok(guardian(GuardianSmsWireKind.EVENT, ctx(w = watch.copy(noSignal = true))))
        assertFalse("noSignal 은 줄 없음", lost.contains("워치("))
        assertFalse(lost.contains("심박 48"))
        val kept = ok(guardian(GuardianSmsWireKind.EVENT, ctx(w = watch.copy(noSignal = false))))
        assertTrue(kept.contains("워치(10:37~10:42): 심박 48회/분"))
        val p = Payload.Open119Compose("event-1", facts, false, false)
        assertFalse(ok(IncidentComposer.compose119(p, Party.CAR, offset, ctx(w = watch.copy(noSignal = true)))).contains("워치("))
        assertTrue(ok(IncidentComposer.compose119(p, Party.CAR, offset, ctx())).contains("워치("))
    }

    @Test fun phoneOwnerSelfCommandUsesLocalFacts() {
        val localFacts = WireFacts(SnapshotClock.LOCAL, local(10, 37), local(10, 39), local(10, 41), null, null, true)
        val t = ok(IncidentComposer.guardianSms(sms(GuardianSmsWireKind.EVENT, localFacts), Party.PHONE, null, ctx(vehicle = null)))
        assertTrue(t.contains("경보: 10:37 1차 경보 → 10:39 정차 → 10:41 무응답"))
        assertTrue(t.contains("차 상태 없음 · 휴대폰 기준 차량 정차(10:39부터)"))
    }
}
