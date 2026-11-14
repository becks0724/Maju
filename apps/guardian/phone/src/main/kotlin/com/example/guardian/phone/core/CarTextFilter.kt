// 차에서 온 글자 칸(차종 · 내비 주소)을 보호자 문자 본문에 넣기 전에 거르는 규칙(D-7) — 허용 글자 · 길이, 전화번호 꼴 · tel: · URL 막기
package com.example.guardian.phone.core

import com.example.guardian.shared.protocol.Vehicle

/**
 * 차 토큰을 가진 쪽이 차종 · 주소 칸에 번호나 링크를 실어 보호자 문자로 퍼뜨리지 못하게 한다(합동 점검 D-7).
 * 걸리면 그 칸만 null 로 빼고 문자는 그대로 보낸다 — 본문은 빈 칸을 기존 「확인 안 됨」 계열 문구로 채운다(놓침보다 중복).
 * 보호자 줄의 번호는 휴대폰에 저장된 보호자 번호라 여기서 거르지 않는다. 받는 번호도 늘 저장된 보호자 번호다(CommandExecutor).
 */
object CarTextFilter {
    /** 차 제조사 · 모델 이름 최대 길이 [추정]. */
    const val MAX_NAME = 20
    /** 내비 주소 최대 길이 — 도로명 주소 한 줄을 넉넉히 [추정]. */
    const val MAX_ADDRESS = 80

    /** 한글 · 영문 · 숫자 · 공백과 주소에 쓰는 몇 가지 부호만. 줄바꿈 · 꺾쇠 · 콜론 등은 받지 않는다. */
    private val ALLOWED = Regex("^[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 .,·()\\-/#]+$")
    /** 링크 꼴(결정 22) — 「://」 · 「www.」 · 「글자.글자/」 · 「글자.영문 2~6자」. 점 뒤가 숫자인 표기(「G80 3.3T」 · 「3.1절로」)는 통과. */
    private val URL = Regex("(?i)(://|www\\.|[a-z0-9]\\.[a-z0-9]+/|[a-z0-9]\\.[a-z]{2,6}(?![a-z]))")
    private val HYPHEN_NUMBER = Regex("\\d{2,4}-\\d{3,4}-\\d{4}")
    /** 8자리 대표번호 — 15xx · 16xx · 18xx 뒤 4자리(구분 부호 섞임 포함). 지번 「1588-12」 는 통과. */
    private val REPRESENTATIVE = Regex("(?<!\\d)1[568]\\d{2}[ .,·()\\-/#]*\\d{4}(?!\\d)")
    private val NUMBER_RUN = Regex("[\\d(][\\d .,·()\\-/#]*")

    fun make(s: String?): String? = clean(s, MAX_NAME)
    fun model(s: String?): String? = clean(s, MAX_NAME)
    fun address(s: String?): String? = clean(s, MAX_ADDRESS)

    /**
     * 차 상태의 본문에 들어가는 칸을 거른 사본 — 글자 칸 셋과 숫자 칸(결정 22: 연식 1980~2100 · 속도 0~400 · 위도 −90~90 · 경도 −180~180).
     * 숫자 칸이 범위 밖이면 그 칸만 뺀다. 좌표가 틀리면 같은 내비 위치의 주소도 함께 뺀다.
     */
    fun vehicle(v: Vehicle): Vehicle {
        val coordOk = v.naviLat.inRange(-90.0, 90.0) && v.naviLon.inRange(-180.0, 180.0)
        return v.copy(
            make = make(v.make), model = model(v.model),
            year = v.year?.takeIf { it in 1980..2100 },
            speedKmh = v.speedKmh?.takeIf { it.inRange(0.0, 400.0) },
            naviLat = v.naviLat.takeIf { coordOk }, naviLon = v.naviLon.takeIf { coordOk },
            naviAddress = if (coordOk) address(v.naviAddress) else null,
        )
    }

    private fun Double?.inRange(lo: Double, hi: Double) = this == null || (!isNaN() && this in lo..hi)

    private fun clean(s: String?, max: Int): String? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.length > max || !ALLOWED.matches(t)) return null
        if (t.contains("tel:", ignoreCase = true) || URL.containsMatchIn(t) || HYPHEN_NUMBER.containsMatchIn(t) || REPRESENTATIVE.containsMatchIn(t)) return null
        // 숫자와 구분 부호(공백 · . , · ( ) - /)만으로 이어진 덩어리에 숫자가 9자리 이상이면 전화번호 꼴로 본다(app-qa 3차 조건 2) —
        // 「(010) 0000-0012」 · 「010/0000/0012」 은 막고, 글자가 사이에 끼는 도로명 · 지번(「166 101동 1203호」 · 「123-45」)은 통과
        if (NUMBER_RUN.findAll(t).any { m -> m.value.count(Char::isDigit) >= 9 }) return null
        return t
    }
}
