// 처음 설정 등록 정보(운전자 · 차량 · 보호자 · 동의 3종 · 켬/끔)와 보호자 번호 규칙 — 안드로이드 의존 없음
package com.example.guardian.phone.core

import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.report.Profile
import java.time.LocalDate

/** 기능은 기본 꺼짐(G1 opt-in). 동의 3종이 모두 있어야 켤 수 있다(G9 · PM 결정). */
data class Registration(
    val name: String = "",
    val birthDate: LocalDate? = null,
    val carColor: String = "",
    val carPlate: String = "",
    val guardianName: String = "",
    val guardianPhone: String = "",
    val consents: Consents = Consents(),
    val powerOn: Boolean = false,
    /** 「차 화면에 심박 숨기기」 — 기본 꺼짐(= 차에 표시). 매 sync 의 `carDisplay.hideHr` 로 간다(계약 §4.1, 2026-11-01). */
    val hideHrOnCar: Boolean = false,
    /**
     * 차 화면 표시 동의가 있는가 — 동의 문구 판 2(② 에 「연결한 차 화면」)로 동의했거나 처음부터 판 2 로 설정했다.
     * 거짓(판 1 동의, 재동의 전)이면 감시 · 워치 값 · 문자 · 119 는 그대로 두고 차 화면 표시만 막는다([ConsentVersion]).
     */
    val carDisplayConsented: Boolean = true,
) {
    /** 차에 보낼 숨김 — 사용자 스위치, 또는 차 화면 표시 동의 전이면 늘 숨김(스위치 값과 따로 강제). */
    val hideHrForCar: Boolean get() = hideHrOnCar || !carDisplayConsented

    /** 이름 · 생년월일 · 차량 색상 · 번호 · 보호자 이름 · 연락처가 다 있음(보호자는 필수 — 사용자 지시). */
    val complete: Boolean
        get() = name.isNotBlank() && birthDate != null && carColor.isNotBlank() && carPlate.isNotBlank() &&
            guardianName.isNotBlank() && PhoneNumbers.isGuardianNumber(guardianPhone)

    val canTurnOn: Boolean get() = complete && consents.all

    fun profile(): Profile? = if (!complete) null else Profile(
        name = name.trim(), birthDate = birthDate!!, guardianName = guardianName.trim(),
        guardianPhone = PhoneNumbers.digits(guardianPhone), carColor = carColor.trim(), carPlate = carPlate.trim(),
    )
}

/**
 * 동의 문구 판(2026-11-01). 판 2 는 동의 ②(건강 관련)의 받는 곳에 「연결한 차 화면(표시만 · 저장하지 않음)」을 더했다(G2 받는 곳 · 목적 고지).
 * 판 1 로 ② 에 동의한 사용자의 이전 동의는 원래 목적(감시 · 보호자 · 119)을 덮으므로 **감시 · 워치 값 · 문자 · 119 는 그대로 동작**하고,
 * 동의가 없는 새 목적 「차 화면 표시」만 다시 동의(P04)할 때까지 막는다(PM 판단 2026-11-01 — 업데이트로 안전 감시가 조용히 꺼지면 놓침).
 * 재동의가 법적으로 꼭 필요한지는 [법률 검토 필요].
 *
 * 판 3(2026-11-13 사용자 결정 (다))은 문구를 사실대로 고쳤다 — ② 항목에 「응답 확인이 필요한 상태」 · 보관에 중계 서버 지움 시점,
 * ③ 항목에 정확도 · 속도 · 잰 시각과 차 내비 위치, 받는 곳에 중계 서버와 주소 찾기, 목적에 주행과 정차 판단, 보관에 중계 서버 지움 시점. 흐름 자체는 바뀌지 않았으므로 **판 3 때문에 새로 막는 것은 없다** — 판 2 사용자는 다시 동의하기
 * 전에도 그대로 동작하고, 판 1 사용자는 판 2 와 같이 차 화면 표시만 막힌다([CAR_DISPLAY_SINCE]).
 */
object ConsentVersion {
    const val CURRENT = 3

    /** 차 화면 표시를 알린 첫 판. 이보다 낮은 판의 ② 동의는 차 화면 표시를 덮지 않는다. */
    const val CAR_DISPLAY_SINCE = 2

    /** 저장된 판이 낮은데 ② 에 동의해 둔 사용자 — 앱을 열면 P04 를 다시 보인다. 저장된 판이 없으면 1 로 본다. */
    fun outdated(storedVersion: Int, healthAgreed: Boolean): Boolean = healthAgreed && storedVersion < CURRENT

    /** 차 화면 표시 동의가 있는가 — 판 2 보다 낮은 ② 동의만 거짓. ② 에 동의한 적이 없으면 처음 설정에서 지금 판으로 받으므로 참. */
    fun carDisplayConsented(storedVersion: Int, healthAgreed: Boolean): Boolean = !(healthAgreed && storedVersion < CAR_DISPLAY_SINCE)
}

/**
 * 보호자 번호 규칙. 자동 문자는 국내 휴대폰 번호(01X, 10~11자리)로만 보낸다 —
 * 119 · 112 같은 특수번호는 이 규칙을 통과할 수 없어 자동 문자 경로가 없다(G4).
 */
object PhoneNumbers {
    private val mobile = Regex("^01[016789][0-9]{7,8}$")

    fun digits(s: String): String = s.filter { it in '0'..'9' }

    fun isGuardianNumber(s: String): Boolean = mobile.matches(digits(s))
}
