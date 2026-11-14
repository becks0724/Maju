// 문자 양식 시험 — G9 예시 재현 · 동의 조합 8가지 · 첫 줄 좌표 · 테스트 접두 · 보호자 필수 · 만 나이 · 바이트 한도
package com.example.guardian.shared.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReportsTest {
    private fun kst(h: Int, m: Int, s: Int = 0) =
        LocalDateTime.of(2026, 10, 30, h, m, s).atZone(Reports.SEOUL).toInstant().toEpochMilli()

    private val profile = Profile(
        name = "홍길동", birthDate = LocalDate.of(1968, 5, 1),
        guardianName = "김○○", guardianPhone = "010-0000-0000", carColor = "흰색", carPlate = "12가3456",
    )
    private val all = Consents(emergencyInfo = true, health = true, location = true)

    private fun input(consents: Consents = all, test: Boolean = false, p: Profile = profile) = ReportInput(
        nowMs = kst(10, 42),
        profile = p,
        consents = consents,
        location = LocationInfo("서울 종로구 세종대로 172", 37.575903, 126.976901, 8.0, LocationSource.PHONE_GPS, measuredAtMs = kst(10, 42)),
        driving = DrivingInfo(stopped = true, stoppedAtMs = kst(10, 39)),
        noResponseSec = 80,
        timeline = AlertTimeline(kst(10, 37), kst(10, 39), kst(10, 41)),
        watch = WatchFacts(kst(10, 36), kst(10, 42), 92, 48, pulseWeak = true, pulseLostAtMs = kst(10, 41, 35), worn = true),
        vehicle = VehicleInfo("현대", "아반떼", 2027),
        test = test,
        guardianContacted = true,
    )

    private fun ok(r: ReportResult): String = (r as ReportResult.Ok).text

    @Test fun fullMatchesG9Example() {
        val expected = """
            [119 구조요청] 37.57590,126.97690 운전자 응답 없음
            위치: 서울 종로구 세종대로 172 부근(±8m) · 10:42
            상태: 차량 정차(10:39부터) · 응답 없음 1분 20초
            경보: 10:37 1차 경보 → 10:39 정차 → 10:41 무응답
            워치(10:36~10:42): 심박 92→48회/분, 10:41:35부터 맥박 신호 잡히지 않음, 착용 중
            운전자: 홍길동 · 만 58세
            차량: 현대 아반떼(2027) 흰색 12가3456
            보호자: 김○○ 010-0000-0000 (연락함)
            확인 전화: 이 번호 또는 보호자 · 운전자 무응답 확인 앱 자동 작성 · 의료 판정 아님
        """.trimIndent()
        assertEquals(expected, ok(Reports.full119(input())))
    }

    /** 좌표 끝 위치(첫 줄 기준). 좌표가 없으면 -1. */
    private fun coordEnd(text: String): Int {
        val first = text.lines().first()
        val i = first.indexOf("37.57590,126.97690")
        return if (i < 0) -1 else i + "37.57590,126.97690".length
    }

    /** 하-1 — 119 판 · 보호자 판 · 테스트 판 모두 좌표 끝이 첫 40자 안. */
    @Test fun coordinatesEndWithin40CharsEverywhere() {
        for (test in listOf(false, true)) {
            val r = input(test = test)
            for (t in listOf(Reports.full119(r), Reports.guardianIncident(r), Reports.guardianAccident(r), Reports.guardianObserving(r), Reports.ultraShort119(r))) {
                val end = coordEnd(ok(t))
                assertTrue("test=$test end=$end ${ok(t).lines().first()}", end in 1..40)
            }
        }
    }

    @Test fun eightConsentCombinations() {
        for (e in listOf(true, false)) for (h in listOf(true, false)) for (l in listOf(true, false)) {
            val c = Consents(e, h, l)
            val full = Reports.full119(input(c))
            val guardian = Reports.guardianIncident(input(c))
            if (!e) {
                assertEquals(ReportResult.Fail(ReportFailure.EMERGENCY_INFO_CONSENT_MISSING), full)
                assertEquals(ReportResult.Fail(ReportFailure.EMERGENCY_INFO_CONSENT_MISSING), guardian)
                assertEquals(ReportResult.Fail(ReportFailure.EMERGENCY_INFO_CONSENT_MISSING), Reports.ultraShort119(input(c)))
                continue
            }
            val lines = ok(full).lines()
            assertEquals("$c 워치 줄", h, lines.any { it.startsWith("워치(") })
            assertEquals("$c 첫 줄 좌표", l, lines[0].contains("37.57590,126.97690"))
            assertTrue("$c 위치 줄이 둘째", lines[1].startsWith("위치: "))
            if (!l) {
                assertEquals("[119 구조요청] 운전자 응답 없음", lines[0])
                assertTrue(lines[1].contains("직접 입력 필요"))
                assertFalse(ok(full).contains("세종대로"))
                assertEquals(ReportResult.Fail(ReportFailure.AUTO_SMS_NOT_ALLOWED), guardian)
            } else {
                assertTrue(ok(guardian).contains("37.57590,126.97690"))
                assertEquals(h, ok(guardian).contains("심박"))
            }
            assertTrue(lines.any { it.startsWith("운전자: 홍길동 · 만 58세") })
            assertTrue(lines.any { it.startsWith("보호자: 김○○ 010-0000-0000") })
            assertTrue(lines.last().startsWith("확인 전화: 이 번호 또는 보호자"))
        }
    }

    @Test fun guardianRequired() {
        for (p in listOf(profile.copy(guardianName = null), profile.copy(guardianPhone = " "))) {
            assertEquals(ReportResult.Fail(ReportFailure.GUARDIAN_MISSING), Reports.full119(input(p = p)))
            assertEquals(ReportResult.Fail(ReportFailure.GUARDIAN_MISSING), Reports.ultraShort119(input(p = p)))
            assertEquals(ReportResult.Fail(ReportFailure.GUARDIAN_MISSING), Reports.guardianIncident(input(p = p)))
        }
    }

    @Test fun testPrefixOnFirstLineKeepsCoordinates() {
        val t = ok(Reports.full119(input(test = true))).lines()
        assertEquals("[테스트] [119 구조요청] 37.57590,126.97690 운전자 응답 없음", t[0])
        assertEquals("실제 상황 아님", t[1])
        assertTrue(t[2].startsWith("위치: "))
        for (g in listOf(
            Reports.guardianIncident(input(test = true)), Reports.guardianCancel(input(test = true), kst(10, 43)),
            Reports.guardianAccident(input(test = true)), Reports.guardianObserving(input(test = true)),
        )) {
            assertTrue(ok(g).startsWith("[테스트] [앱 안내] "))
            assertEquals("실제 상황 아님", ok(g).lines()[1])
        }
        assertTrue(ok(Reports.ultraShort119(input(test = true))).startsWith("[테스트][119]37.57590,126.97690"))
        assertFalse(ok(Reports.full119(input())).contains("테스트"))
    }

    @Test fun ultraShortMatchesExampleAndFits80Bytes() {
        val s = ok(Reports.ultraShort119(input()))
        assertEquals("[119]37.57590,126.97690 운전자무응답 흰아반떼12가3456", s)
        assertEquals(53, Reports.eucKrBytes(s))
    }

    @Test fun ultraShortTrimsVehicleWhenOver80Bytes() {
        val long = input(test = true).let { it.copy(vehicle = it.vehicle.copy(model = "아주긴모델이름아주긴모델이름")) }
        val s = ok(Reports.ultraShort119(long))
        assertTrue(Reports.eucKrBytes(s) <= 80)
        assertTrue("번호는 남긴다", s.endsWith("12가3456"))
        assertFalse(s.contains("아주긴"))
    }

    /** 80byte 는 그대로, 81byte 가 되면 색 첫 글자부터 뺀다. */
    @Test fun ultraShort80And81ByteBoundary() {
        fun ultra(plate: String) = ok(Reports.ultraShort119(input(p = profile.copy(carPlate = plate))))
        val at80 = ultra("1".repeat(35))
        assertEquals(80, Reports.eucKrBytes(at80))
        assertTrue(at80.endsWith("흰아반떼" + "1".repeat(35)))
        val at81 = ultra("1".repeat(36))
        assertEquals(79, Reports.eucKrBytes(at81))
        assertTrue(at81.endsWith(" 아반떼" + "1".repeat(36)))
    }

    /** 하-2 — 동의는 있는데 위치를 못 잡았을 때와 동의가 없을 때 문구가 다르다. */
    @Test fun locationUnknownVersusNoConsent() {
        val unknown = ok(Reports.full119(input().copy(location = null))).lines()
        assertEquals("[119 구조요청] 운전자 응답 없음", unknown[0])
        assertEquals("위치: 위치 확인 안 됨 · 직접 입력 필요 · 10:42", unknown[1])
        val noConsent = ok(Reports.full119(input(all.copy(location = false)))).lines()
        assertEquals("위치: 위치 제공 동의 없음 · 직접 입력 필요 · 10:42", noConsent[1])
        assertTrue("동의 있으면 위치 없이도 보호자 자동 문자", Reports.guardianIncident(input().copy(location = null)) is ReportResult.Ok)
    }

    /** 공통 하4 — 위치 줄 시각은 잰 시각이고, 30초가 넘은 위치는 없는 위치와 같다(첫 줄 · 초단문 좌표도 없음). */
    @Test fun locationLineUsesMeasuredTimeAndDropsStale() {
        fun at(ms: Long) = input().let { it.copy(location = it.location!!.copy(measuredAtMs = ms)) }
        val fresh = ok(Reports.full119(at(kst(10, 41, 30)))).lines()
        assertEquals("[119 구조요청] 37.57590,126.97690 운전자 응답 없음", fresh[0])
        assertEquals("위치: 서울 종로구 세종대로 172 부근(±8m) · 10:41", fresh[1])
        val edge = ok(Reports.full119(at(kst(10, 42) - Reports.LOCATION_FRESH_MS))).lines()
        assertEquals("30초 정각은 쓴다", "위치: 서울 종로구 세종대로 172 부근(±8m) · 10:41", edge[1])
        val stale = at(kst(10, 42) - Reports.LOCATION_FRESH_MS - 1)
        val lines = ok(Reports.full119(stale)).lines()
        assertEquals("[119 구조요청] 운전자 응답 없음", lines[0])
        assertEquals("위치: 위치 확인 안 됨 · 직접 입력 필요 · 10:42", lines[1])
        assertFalse(ok(Reports.guardianIncident(stale)).contains("37.57590"))
        assertEquals("[119]운전자무응답 흰아반떼12가3456", ok(Reports.ultraShort119(stale)))
    }

    @Test fun ultraShortWithoutLocationConsent() {
        assertEquals("[119]운전자무응답 흰아반떼12가3456", ok(Reports.ultraShort119(input(all.copy(location = false)))))
    }

    @Test fun eucKrByteCount() {
        assertEquals(5, Reports.eucKrBytes("[119]"))
        assertEquals(4, Reports.eucKrBytes("가나"))
        assertEquals(2, Reports.eucKrBytes("·"))
    }

    @Test fun fullFitsOneLms() {
        val worst = input(test = true).let {
            it.copy(
                location = it.location!!.copy(address = "경부고속도로 서울 방향 ".repeat(5)),
                driving = it.driving.copy(carStateMissing = true),
            )
        }
        for (r in listOf(input(), worst)) {
            val text = ok(Reports.full119(r))
            assertTrue(text.length <= Reports.LMS_CHARS)
            assertTrue(Reports.eucKrBytes(text) <= Reports.LMS_BYTES)
        }
    }

    @Test fun manAgeUsesKoreanDate() {
        val birth = LocalDate.of(1968, 10, 30)
        // 한국 시각 10-30 00:30 = UTC 10-29 15:30 → 한국 날짜로 생일 당일
        val justAfterMidnight = LocalDateTime.of(2026, 10, 30, 0, 30).atZone(Reports.SEOUL).toInstant().toEpochMilli()
        assertEquals(58, Reports.manAge(birth, justAfterMidnight))
        assertEquals(57, Reports.manAge(birth, justAfterMidnight - 3_600_000))
    }

    @Test fun movingAndCarStateMissingLine() {
        val r = input().copy(driving = DrivingInfo(stopped = false, speedKmh = 61.6, carStateMissing = true), noResponseSec = 45)
        assertTrue(ok(Reports.full119(r)).lines()[2] == "상태: 차 상태 없음 · 휴대폰 기준 차량 주행 중 62km/h · 응답 없음 45초")
    }

    @Test fun guardianMessagesShape() {
        val inc = ok(Reports.guardianIncident(input())).lines()
        assertEquals("[앱 안내] 37.57590,126.97690 운전자 응답 없음", inc[0])
        assertFalse("119 신고로 오해할 표현 없음", inc[0].contains("119"))
        assertTrue("위급하면 119 에 전화하세요" in inc)
        assertEquals("운전자가 10:43에 응답했습니다. 앞선 안내는 취소합니다.", ok(Reports.guardianCancel(input(), kst(10, 43))).lines()[1])
        assertTrue(ok(Reports.guardianAccident(input())).contains("에어백 전개"))
        assertTrue(ok(Reports.guardianObserving(input())).contains("쉬는 중"))
    }

    /**
     * 「응답 확인 꺼짐」 문자(사용자 결정 2026-11-08) — 취소 문자와 같은 꼴. 누가 껐는지 · 운전자가 괜찮은지 단정하지 않고(G1),
     * 보호자에게 할 일을 시키지 않으며(「…세요」 없음), 위치 · 건강 값 · 개인 정보를 싣지 않는다(G2 최소).
     */
    @Test fun guardianPowerOffShape() {
        val expected = """
            [앱 안내] 10:42
            10:42에 휴대폰에서 응답 확인이 꺼졌습니다. 이후 운전자 상태는 앱으로 확인되지 않습니다.
            운전자 무응답 확인 앱 자동 작성 · 의료 판정 아님
        """.trimIndent()
        val text = ok(Reports.guardianPowerOff(input(), kst(10, 42)))
        assertEquals(expected, text)
        for (w in listOf("세요", "안전", "괜찮", "정상", "취소", "운전자가 껐", "운전자가 끄", "37.45", "홍길동", "010-", "심박", "12가3456")) {
            assertFalse("「$w」 없음: $text", text.contains(w))
        }
        val t = ok(Reports.guardianPowerOff(input(test = true), kst(10, 42))).lines()
        assertEquals("[테스트] [앱 안내] 10:42", t[0])
        assertEquals("실제 상황 아님", t[1])
        // 보호자 · 긴급 연락 정보 · 위치 동의가 없으면 만들지 않는다(보호자 문자 공통 검사)
        assertEquals(ReportResult.Fail(ReportFailure.GUARDIAN_MISSING), Reports.guardianPowerOff(input(p = profile.copy(guardianPhone = " ")), kst(10, 42)))
        assertEquals(ReportResult.Fail(ReportFailure.AUTO_SMS_NOT_ALLOWED), Reports.guardianPowerOff(input(all.copy(location = false)), kst(10, 42)))
    }

    @Test fun locationPick() {
        val a = LocationInfo(null, 1.0, 1.0, null, LocationSource.PHONE_GPS, 0)
        val b = LocationInfo(null, 2.0, 2.0, null, LocationSource.CAR_NAVI, 0)
        val c = LocationInfo(null, 3.0, 3.0, null, LocationSource.WATCH, 0)
        assertEquals(a, LocationInfo.pick(a, b, c))
        assertEquals(b, LocationInfo.pick(null, b, c))
        assertEquals(c, LocationInfo.pick(null, null, c))
    }

    @Test fun durationFormat() {
        assertEquals("45초", Reports.duration(45))
        assertEquals("2분", Reports.duration(120))
        assertEquals("1분 20초", Reports.duration(80))
    }
}
