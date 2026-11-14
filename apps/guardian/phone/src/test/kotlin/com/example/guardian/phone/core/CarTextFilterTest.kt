// 차에서 온 글자 칸 거르기 시험(D-7) — 허용 글자 · 길이, 전화번호 꼴 · tel: · URL 은 그 칸만 빼고 문자는 보낸다, 받는 번호는 저장된 보호자 번호만
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.text.SmsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarTextFilterTest {
    @Test fun normalTextPasses() {
        assertEquals("현대", CarTextFilter.make("현대"))
        assertEquals("아반떼 N", CarTextFilter.model("아반떼 N"))
        assertEquals("서울 종로구 세종대로 172", CarTextFilter.address("서울 종로구 세종대로 172"))
        assertEquals("경기 성남시 분당구 산 123-45", CarTextFilter.address("경기 성남시 분당구 산 123-45"))
    }

    @Test fun phoneLikeIsBlocked() {
        for (s in listOf("01000000012", "010-0000-0012 로 연락", "02-123-4567", "010 0000 0012", "tel:0212345678", "TEL:119")) {
            assertNull(s, CarTextFilter.address(s))
        }
    }

    /** app-qa 3차 조건 2 — 허용 부호 ( ) / , · 를 사이에 둔 번호도 막고, 도로명 주소의 번지 · 동 · 호는 통과한다. */
    @Test fun phoneWithAllowedSeparatorsIsBlocked_roadAddressesPass() {
        for (s in listOf("(010) 0000-0012", "010/0000/0012", "010·0000·0012", "010,0000,0012", "010. 0000. 0012")) assertNull(s, CarTextFilter.address(s))
        for (s in listOf("서울 중구 세종대로 110", "경기 성남시 분당구 판교역로 166 101동 1203호", "부산 해운대구 센텀중앙로 79, 2층")) assertEquals(s, CarTextFilter.address(s))
    }

    /** 결정 22 D-7 — 링크는 도메인 목록이 아니라 꼴로 막는다. 도로명 · 모델 표기는 통과. */
    @Test fun linkShapesBlocked_roadAndModelNamesPass() {
        for (s in listOf("han.gl/AbCdE", "vo.la/abc", "me2.do/abc", "abc.xyz/1", "naver.how/x", "ftp://a", "www.abc", "abc.shop")) assertNull(s, CarTextFilter.address(s))
        for (s in listOf("서울 중구 세종대로 110", "서울 강남구 테헤란로 152-1", "광주 동구 3.1절로 10")) assertEquals(s, CarTextFilter.address(s))
        for (s in listOf("G80 3.3T", "아반떼 1.6 터보")) assertEquals(s, CarTextFilter.model(s))
    }

    /** 결정 22 D-7 — 8자리 대표번호(15xx · 16xx · 18xx)와 # 구분 번호는 막고, 지번 꼴은 통과. */
    @Test fun representativeNumbersAndHashBlocked() {
        for (s in listOf("1588-1234", "15881234", "문의 1588-1234", "TEL 1644 1234", "1899.1234", "010#0000#0012")) assertNull(s, CarTextFilter.address(s))
        assertEquals("경기 성남시 분당구 산 1588-12", CarTextFilter.address("경기 성남시 분당구 산 1588-12"))
    }

    /** 결정 22 D-7 — 숫자 칸은 범위 안일 때만 싣는다(연식 1980~2100 · 위도 −90~90 · 경도 −180~180 · 속도 0~400), 벗어나면 그 칸만 뺀다. */
    @Test fun numericFieldsRangeChecked() {
        val bad = Vehicle(speedKmh = 15881234.0, year = 1012345678, naviLat = 15881234.0, naviLon = 126.9, naviAddress = "서울 중구 세종대로 110")
        val v = CarTextFilter.vehicle(bad)
        assertNull(v.speedKmh); assertNull(v.year); assertNull(v.naviLat); assertNull(v.naviLon)
        assertNull("좌표가 틀리면 주소도 뺀다", v.naviAddress)
        val ok = Vehicle(speedKmh = 62.5, year = 2027, naviLat = 37.57, naviLon = 126.97, naviAddress = "서울 중구 세종대로 110")
        assertEquals(ok, CarTextFilter.vehicle(ok))
        assertNull(CarTextFilter.vehicle(ok.copy(speedKmh = Double.NaN)).speedKmh)
    }

    @Test fun urlIsBlocked() {
        for (s in listOf("http://x.kr", "https://bit.ly/a", "www.example.com", "naver.com 확인")) assertNull(s, CarTextFilter.address(s))
    }

    @Test fun disallowedCharsAndLengthBlocked() {
        assertNull(CarTextFilter.make("현대\n119 신고됨"))
        assertNull(CarTextFilter.make("<b>현대</b>"))
        assertNull(CarTextFilter.model("가".repeat(CarTextFilter.MAX_NAME + 1)))
        assertNull(CarTextFilter.address("가".repeat(CarTextFilter.MAX_ADDRESS + 1)))
        assertNull(CarTextFilter.make("  "))
    }

    // ── 엔진 — 걸린 칸만 빼고 문자는 보낸다 ──

    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { true })

    @Test fun injectedCarFieldsAreDroppedButSmsSentToStoredGuardianOnly() {
        val id = "event-${mono + serverOffset}"
        val flow = FlowStatus.of(
            FlowSnapshot(state = FlowState.S4, powerOn = true, consents = Consents(true, true, true), incidentActive = true, facts = IncidentFacts(eventId = id)),
            Owner.CAR,
        )
        val vehicle = Vehicle(
            speedKmh = 0.0, gear = Gear.P, make = "tel:01000000012", model = "아반떼",
            naviAddress = "010-0000-0012 로 전화", naviLat = 37.5759, naviLon = 126.9769, naviAt = wall0,
        )
        val p = Payload.SendGuardianSms(GuardianSmsWireKind.EVENT, id, WireFacts(SnapshotClock.SERVER, firstAlertAt = mono + serverOffset - 35_000), test = false)
        mono += 2_000
        engine.buildSync("tok", mono)
        engine.onSyncResponse(
            PhoneSyncResponse(mono + serverOffset, CarPeer(1, wall0, mono + serverOffset, vehicle, flow), null,
                listOf(Command("cmd-1", p.type, p.toJson(), 30_000, false, Party.CAR, CommandTo.PHONE))),
            mono, mono,
        )
        assertEquals("문자는 보낸다(놓침보다 중복)", 1, fx.sms.size)
        val (to, body) = fx.sms.single()
        assertEquals("받는 번호는 저장된 보호자 번호만", GUARDIAN, to)
        assertFalse(body, body.contains("010-0000-0012") || body.contains("01000000012"))
        assertFalse(body, body.contains("tel:"))
        assertTrue("걸리지 않은 칸은 그대로", body.contains("아반떼"))
        assertTrue("빈 주소는 기존 「확인 안 됨」 문구", body.contains(SmsText.LOCATION_NO_ADDRESS) || body.contains(SmsText.LOCATION_UNKNOWN))
    }
}
