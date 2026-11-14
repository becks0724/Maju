// 사용자에게 보이는 모든 한국어 문구 표 — 화면 · 음성 · 문자 조각을 한곳에 모아 G1 금지어 시험을 건다
package com.example.guardian.shared.text

/**
 * 화면 · 음성 문구. 상태 기계 지시는 이 ID 만 싣고, 앱은 [ko] 를 보여 주거나 읽는다.
 * 판정어(실신 · 의식 소실 등) · 차량 제어 약속 문구는 쓰지 않는다(G1 · G6).
 */
enum class Phrase(val ko: String) {
    APP_NAME("운전자 무응답 확인"),

    // 1차 경보(주행 중)
    VOICE_S1_PULL_OVER("응답 확인이 필요합니다. 안전한 곳에 차를 세우세요."),
    SCREEN_S1("응답 확인 필요 · 워치 버튼을 누르세요"),

    // 응답함
    VOICE_S1R_REST("응답을 확인했습니다. 쉬어 가세요."),
    SCREEN_S1R("응답 확인됨 · 쉬어 가세요"),

    // 정차 중 경보
    VOICE_AP_CHECK("응답이 필요합니다. 괜찮으면 버튼을 누르세요."),
    SCREEN_AP("응답 확인 필요 · 괜찮으면 버튼을 누르세요"),

    // 정차 관찰
    SCREEN_S2("쉬는 중 · 괜찮아지면 다시 출발하세요"),
    BUTTON_OK("괜찮아요"),

    // 주행 중 무응답
    VOICE_S3_NO_RESPONSE("응답이 없어 보호자에게 알림을 보냅니다. 비상등 버튼을 누르고 안전한 곳에 차를 세우세요."),
    SCREEN_S3("응답 없음 · 보호자에게 알림"),

    // 정차 뒤 무응답
    VOICE_S4_NO_RESPONSE("보호자에게 알림을 보냅니다. 괜찮으면 버튼을 누르세요."),
    SCREEN_S4("보호자에게 알림 · 가까운 응급실 안내"),
    SEARCH_EMERGENCY_ROOM("응급실"),

    // 사고(에어백 전개)
    VOICE_S5_ACCIDENT("에어백 전개 신호를 확인했습니다. 보호자에게 알림을 보냅니다."),
    SCREEN_S5("에어백 전개 신호 · 차량 SOS 버튼으로 구조를 요청할 수 있습니다"),

    // 보호자 문자 전달을 확인하지 못함(합동 점검 A-5) — 차 음성은 완료형을 쓰지 않는다. 휴대폰이 확인하면 화면 줄만 바꾼다
    VOICE_S3_SMS_DELAYED("응답이 없습니다. 휴대폰 연결을 확인하지 못해 보호자 알림이 늦어질 수 있습니다. 비상등 버튼을 누르고 안전한 곳에 차를 세우세요."),
    VOICE_S4_SMS_DELAYED("휴대폰 연결을 확인하지 못해 보호자 알림이 늦어질 수 있습니다. 괜찮으면 버튼을 누르세요."),
    VOICE_S5_SMS_DELAYED("에어백 전개 신호를 확인했습니다. 휴대폰 연결을 확인하지 못해 보호자 알림이 늦어질 수 있습니다."),
    SCREEN_SMS_CONFIRMED("보호자에게 알림 보냄"),
    /** 휴대폰 smsDone 이 NOT_SENT — 건너뜀(동의 · 번호 없음 · 응답함 · 중복)과 실패가 섞여 있어 원인을 말하지 않는다. */
    SCREEN_SMS_NOT_SENT("보호자 알림을 보내지 않았습니다 · 휴대폰을 확인하세요"),

    // 착용
    SCREEN_CHECK_WEAR("워치 착용을 확인하세요"),
    SCREEN_WATCH_NO_SIGNAL("워치 신호 없음 · 워치 연결을 확인하세요"),

    // 첫 화면 · 설정 화면 고지(G1 이 요구하는 문장 그대로)
    NOTICE_LIMITS("모든 상황을 알아차리지는 못합니다. 위급하면 직접 119 에 연락하세요"),
    NOTICE_NOT_MEDICAL("의료기기가 아니며 진단 · 응급 대응을 대신하지 않습니다"),
}

/** 문자(119 반자동 작성 · 보호자 문자) 조각. 순서 · 조합 규칙은 report 패키지가 정한다(G9). */
object SmsText {
    /** 테스트 접두는 짧게 첫 줄 맨 앞에(좌표가 첫 40자 안에 남게), 「실제 상황 아님」은 둘째 줄. */
    const val TEST_PREFIX = "[테스트]"
    const val TEST_NOTICE = "실제 상황 아님"
    const val HEADER_119 = "[119 구조요청]"
    /** 보호자 문자 머리 — 119 신고로 오해하지 않게 「앱 안내」. */
    const val HEADER_GUARDIAN = "[앱 안내]"
    const val ULTRA_HEADER = "[119]"
    const val NO_RESPONSE_DRIVER = "운전자 응답 없음"
    const val NO_RESPONSE_ULTRA = "운전자무응답"

    const val LOCATION = "위치"
    const val LOCATION_NEAR = "부근"
    const val LOCATION_NO_ADDRESS = "주소 확인 안 됨"
    const val LOCATION_NO_CONSENT = "위치 제공 동의 없음 · 직접 입력 필요"
    const val LOCATION_UNKNOWN = "위치 확인 안 됨 · 직접 입력 필요"

    const val STATE = "상태"
    const val STOPPED = "차량 정차"
    const val STOPPED_SINCE = "차량 정차(%s부터)"
    const val DRIVING = "차량 주행 중"
    /** 속도를 모를 때 — 주행 중이라고 단정하지 않는다(G9 사실만, 코드 리뷰 11-09). */
    const val SPEED_UNKNOWN = "속도 확인 안 됨"
    const val CAR_STATE_MISSING = "차 상태 없음 · 휴대폰 기준"
    const val NO_RESPONSE = "응답 없음"

    const val ALERTS = "경보"
    const val ALERT_FIRST = "1차 경보"
    const val ALERT_STOPPED = "정차"
    const val ALERT_NO_RESPONSE = "무응답"

    const val WATCH = "워치"
    const val HEART_RATE = "심박"
    const val PER_MIN = "회/분"
    const val PULSE_NORMAL = "맥박 신호 잡힘"
    const val PULSE_WEAK = "맥박 신호 약해짐"
    const val PULSE_NOT_FOUND = "맥박 신호 잡히지 않음"
    const val SINCE = "부터"
    const val WORN = "착용 중"
    const val NOT_WORN = "착용 확인 안 됨"

    const val DRIVER = "운전자"
    const val AGE = "만 %d세"
    const val VEHICLE = "차량"
    const val VEHICLE_UNKNOWN = "차종 확인 안 됨"
    const val GUARDIAN = "보호자"
    const val GUARDIAN_CONTACTED = "(연락함)"

    /** 119 꼬리 — 119 회신은 전화라 「확인 전화」. %s = 앱 이름. */
    const val TAIL_119 = "확인 전화: 이 번호 또는 보호자 · %s 앱 자동 작성 · 의료 판정 아님"
    const val TAIL_GUARDIAN = "%s 앱 자동 작성 · 의료 판정 아님"

    const val GUARDIAN_CALL_119 = "위급하면 119 에 전화하세요"
    const val CANCEL_BODY = "운전자가 %s에 응답했습니다. 앞선 안내는 취소합니다."
    /**
     * 사건 문자 뒤 취소 없이 휴대폰에서 켬을 껐을 때(사용자 결정 2026-11-08). 누가 껐는지 · 운전자가 괜찮은지 단정하지 않고(G1)
     * 사실(꺼진 시각)과 그 뒤 앱이 확인하지 못한다는 것만 쓴다. 보호자에게 할 일을 시키지 않는다 — 앞선 사건 문자의 「위급하면 119 에 전화하세요」가 그대로다.
     */
    const val POWER_OFF_BODY = "%s에 휴대폰에서 응답 확인이 꺼졌습니다. 이후 운전자 상태는 앱으로 확인되지 않습니다."
    const val ACCIDENT_BODY = "차량 에어백 전개 신호를 확인했습니다. 차량 SOS 로 구조 접수가 될 수 있습니다."
    const val OBSERVE_BODY = "운전자가 차를 세우고 쉬는 중입니다."

    const val MINUTE = "분"
    const val SECOND = "초"

    /** 금지어 시험이 훑는 문자 조각 전부. */
    val all: List<String> = listOf(
        TEST_PREFIX, TEST_NOTICE, HEADER_119, HEADER_GUARDIAN, ULTRA_HEADER, NO_RESPONSE_DRIVER,
        NO_RESPONSE_ULTRA, LOCATION, LOCATION_NEAR, LOCATION_NO_ADDRESS, LOCATION_NO_CONSENT, LOCATION_UNKNOWN,
        STATE, STOPPED, STOPPED_SINCE, DRIVING, SPEED_UNKNOWN, CAR_STATE_MISSING, NO_RESPONSE, ALERTS,
        ALERT_FIRST, ALERT_STOPPED, ALERT_NO_RESPONSE, WATCH, HEART_RATE, PER_MIN, PULSE_NORMAL,
        PULSE_WEAK, PULSE_NOT_FOUND, SINCE, WORN, NOT_WORN, DRIVER, AGE, VEHICLE, VEHICLE_UNKNOWN,
        GUARDIAN, GUARDIAN_CONTACTED, TAIL_119, TAIL_GUARDIAN, GUARDIAN_CALL_119, CANCEL_BODY, POWER_OFF_BODY, ACCIDENT_BODY,
        OBSERVE_BODY, MINUTE, SECOND,
    )
}
