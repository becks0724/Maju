// 차 앱에만 있는 화면 문구(짝짓기 · 연결 상태 · 워치 칸 · 테스트 입구 · 알림) — 경보 문구는 :shared Phrase 를 쓰고, 여기 것도 G1 금지어 시험을 건다
package com.example.guardian.car

import com.example.guardian.shared.text.SmsText

object CarTexts {
    const val APP_TITLE = "운전자 무응답 확인"
    /** 글자 로고 「Maju」 그림의 읽기 이름(TalkBack) — 뒤따르는 부제와 이어 「마주, 운전자 무응답 확인」으로 읽힌다. */
    const val BRAND_SPOKEN = "마주"
    const val PAIR_TITLE = "휴대폰 · 워치와 연결"
    const val PAIR_HINT = "휴대폰 · 워치 앱에 이 번호를 입력하세요 · 10분 안"
    const val PAIR_WAITING = "연결 번호를 받는 중"
    const val PAIR_DRIVING = "차를 세운 뒤 연결하세요"
    const val PAIR_FAILED = "중계에 연결하지 못했습니다 · 잠시 뒤 다시 시도합니다"
    const val NEW_CODE = "새 연결 번호"
    const val RELAY_MISSING = "중계 주소가 없습니다 · 개발용 빌드에서만 연결합니다"

    // 휴대폰 연결 끊기(감사 하3) — 정차 화면(확정 정지 · 사건 없음)에만 보인다
    const val UNPAIR = "휴대폰 연결 끊기"
    const val UNPAIR_CONFIRM_TITLE = "휴대폰 연결을 끊을까요?"
    const val UNPAIR_CONFIRM_BODY = "되돌릴 수 없습니다 · 다시 쓰려면 새 번호로 연결합니다"
    const val UNPAIR_CONFIRM_NOTE = "이 차 화면에 휴대폰 · 워치 상태가 더는 보이지 않습니다"
    const val UNPAIR_CONFIRM_OK = "끊기"
    /** 중계에 끝내기를 알리지 못했을 때(네트워크 실패) — 번호 화면에 둔다. 중계의 짝은 24시간 만료로 사라진다. */
    const val UNPAIR_RELAY_LEFT = "차에서는 끊었습니다 · 중계 기록은 24시간 뒤 사라집니다"

    // 상태 한 줄 — 정차 열(car-spec 7-1). 행동 요구(켜세요 · 착용하세요 · 동의)는 정차 열에만 둔다(G5)
    const val STATUS_WATCHING = "확인 중"
    const val STATUS_OFF = "꺼짐 · 휴대폰 앱에서 켜세요"
    const val STATUS_OFF_CONSENT = "휴대폰 앱에서 동의가 필요합니다"
    const val STATUS_OFF_WATCH_WAIT = "워치 신호를 기다리는 중"
    const val STATUS_OFF_WEAR = "워치를 착용하세요"
    const val STATUS_SERVER_LOST = "서버 연결 끊김 · 다시 연결하는 중"
    const val STATUS_SERVER_LOST_SUB = "끊긴 동안은 차 화면과 음성으로만 안내합니다"
    const val STATUS_CLOCK_UNVERIFIED = "시계 미확인"

    // 상태 한 줄 — 주행 열(원인만, car-spec 7-1)
    const val DRIVE_SERVER_LOST = "서버 연결 끊김"
    const val DRIVE_OFF = "꺼짐"
    const val DRIVE_OFF_CONSENT = "꺼짐 · 동의 필요"
    /** 켬인데 워치 때문에 확인이 멈춘 경우 — 「꺼짐」은 사용자가 끈 것으로 읽혀 휴대폰 「켜짐」과 어긋나 「확인 멈춤」(사용자 결정 2026-11-10). */
    const val DRIVE_OFF_WATCH_WAIT = "확인 멈춤 · 워치 신호 없음"
    const val DRIVE_OFF_WEAR = "확인 멈춤 · 워치 착용 안 됨"
    /** 응답 뒤 안내의 주행 열 — 「쉬어 가세요」 · 「다시 출발하세요」 대신 상태만(PM 결정 2026-11-08, car-spec §7-1 · G5). */
    const val DRIVE_S1R = "응답 확인됨"
    const val DRIVE_S2 = "응답 확인됨 · 쉬는 중"

    // 워치 한 줄(주행 중, car-spec 7-2)
    const val DRIVE_BIO_HR = "워치 착용 · 심박 %d"
    const val DRIVE_BIO_WORN = "워치 착용"
    const val DRIVE_BIO_NOT_WORN = "워치 착용 안 됨"
    const val DRIVE_BIO_NO_SIGNAL = "워치 신호 없음"

    // 연결 줄 — 휴대폰 앱 연결 줄(PhoneTexts.LINK_*)과 같은 뜻
    const val LINK_HEAD = "연결"
    const val LINK_PHONE = "휴대폰"
    const val LINK_WATCH = "워치"
    const val LINK_SERVER = "서버"
    const val LINK_OK = "연결됨"
    const val LINK_LOST = "끊김"
    const val WATCH_LINK_DIRECT = "연결됨 · Wi-Fi 직접"
    const val WATCH_LINK_VIA_PHONE = "연결됨 · 휴대폰 경유"
    const val WATCH_NO_SIGNAL = "신호 없음"
    const val PAIR_WAITING_ROLE = "기다리는 중"
    /** 번호 화면 — 워치가 붙어 「신호 없음」만 보내는 중(리뷰 11-08 의심). 앞에 「워치 」가 붙는다. */
    const val PAIR_WATCH_NO_SIGNAL = "연결됨 · 신호 없음"

    // 워치 칸(정차 중, car-spec 7-3). 심박 · 착용 글자는 문자 문구(SmsText)와 같은 낱말
    const val BIO_TITLE = "워치"
    const val BIO_HR = SmsText.HEART_RATE
    const val BIO_HR_UNIT = SmsText.PER_MIN
    const val BIO_PULSE = "맥박 신호"
    const val BIO_PULSE_FOUND = "잡힘"
    const val BIO_PULSE_WEAK = "약해짐"
    const val BIO_PULSE_NOT_FOUND = "잡히지 않음"
    const val BIO_PULSE_UNREADABLE = "확인할 수 없음 · 착용을 확인하세요"
    const val BIO_WORN = "착용"
    const val BIO_WORN_YES = SmsText.WORN
    const val BIO_WORN_NO = SmsText.NOT_WORN
    const val BIO_UPDATED = "마지막 갱신"
    const val BIO_JUST_NOW = "방금"
    const val BIO_SEC_AGO = "%d초 전"
    const val BIO_STALE = "30초 넘게 받지 못함"
    const val BIO_HIDDEN = "휴대폰에서 숨김"
    const val BIO_PREF_UNKNOWN = "휴대폰 설정 확인 중"
    const val BIO_NO_CONSENT = "동의 전이라 보이지 않음"
    const val BIO_NO_VALUE = "측정값 없음"
    const val BIO_NO_SIGNAL = "워치 신호 없음"
    const val BIO_EMPTY = "—"
    const val BIO_TEST_VALUE = "테스트 값"

    // 테스트 띠 · 입구(car-spec 7-4). 시작 길이는 계약 TEST_REQUEST 120초(PM 결정 2026-11-01 — 「약 2분」)
    const val TEST_BAND = "테스트 · 실제 상황 아님"
    const val TEST_START = "테스트 시작"
    const val TEST_STOP = "테스트 멈추기"
    const val TEST_CONFIRM_TITLE = "테스트를 시작할까요?"
    const val TEST_CONFIRM_BODY = "실제와 같은 경보가 울립니다 · 보호자 테스트 문자는 휴대폰에서 묻습니다"
    const val TEST_CONFIRM_SCENARIO = "시나리오 · 맥박 신호 잡히지 않음 · 약 2분"
    const val TEST_CONFIRM_OK = "시작"
    const val TEST_CONFIRM_CANCEL = "취소"
    const val TEST_WAITING = "휴대폰에 요청함 · 기다리는 중"
    const val TEST_NO_ANSWER = "휴대폰이 답하지 않습니다 · 휴대폰 앱을 확인하세요"
    /** 차의 시작 요청이 휴대폰 단독(휴대폰 안) 시험으로 떨어졌을 때(flow.localTest) — 정차 화면 테스트 입구에만, 흐름은 실제(2026-11-07). */
    const val TEST_PHONE_LOCAL = "휴대폰에서 테스트 중 · 차 화면은 실제 상태입니다"
    const val TEST_NEED_SERVER = "서버에 연결되어야 시작할 수 있습니다"
    const val TEST_NEED_PHONE = "휴대폰이 연결되어야 시작할 수 있습니다"
    const val TEST_NEED_WATCHING = "확인 중일 때만 시작할 수 있습니다"

    // 정차 화면 상세 안내(고지 자리) — 상태 줄에 두지 않는다(1~2초 파악, app-qa 조건). 순정 SOS 를 약속하지 않는다(G7)
    /** Pleos 능력 확인(F-2)에서 에어백 전개 신호를 읽을 수 없을 때. */
    const val AIRBAG_UNREADABLE = "이 차에서는 에어백 전개 신호를 읽을 수 없어 에어백 전개 알림이 동작하지 않습니다"
    /** 차속 권한(G-2) — 권한 창 직전 이유와 거부 뒤 결과. 정차 열에만 보인다. */
    const val SPEED_REASON = "주행 · 정차를 알기 위해 차 속도를 읽습니다"
    const val SPEED_DENIED = "차 속도를 읽지 못해 정차 확인이 늦어질 수 있습니다"

    const val NOTIFICATION_CHANNEL = "운전자 응답 확인"
    const val NOTIFICATION_TITLE = "운전자 무응답 확인 실행 중"
    const val NOTIFICATION_TEXT = "응답 확인이 필요할 때 차 화면과 음성으로 알립니다"

    /**
     * 주행 중 상태 화면에 보일 수 있는 문구(상태 열 · 워치 한 줄 · 테스트 띠) — 행동 요구 「하세요」가 없어야 한다(G5 시험).
     * 번호 화면의 [PAIR_DRIVING] 은 설계가 그대로 두라고 한 문구라 이 목록 밖이다(car-spec 2절 첫 줄).
     */
    val driving = listOf(
        STATUS_WATCHING, DRIVE_SERVER_LOST, DRIVE_OFF, DRIVE_OFF_CONSENT, DRIVE_OFF_WATCH_WAIT, DRIVE_OFF_WEAR, DRIVE_S1R, DRIVE_S2,
        DRIVE_BIO_HR, DRIVE_BIO_WORN, DRIVE_BIO_NOT_WORN, DRIVE_BIO_NO_SIGNAL, TEST_BAND,
    )

    /** 금지어 시험이 훑는 문구 전부. */
    val all = listOf(
        APP_TITLE, BRAND_SPOKEN, PAIR_TITLE, PAIR_HINT, PAIR_WAITING, PAIR_DRIVING, PAIR_FAILED, NEW_CODE, RELAY_MISSING,
        UNPAIR, UNPAIR_CONFIRM_TITLE, UNPAIR_CONFIRM_BODY, UNPAIR_CONFIRM_NOTE, UNPAIR_CONFIRM_OK, UNPAIR_RELAY_LEFT,
        STATUS_WATCHING, STATUS_OFF, STATUS_OFF_CONSENT, STATUS_OFF_WATCH_WAIT, STATUS_OFF_WEAR, STATUS_SERVER_LOST,
        STATUS_SERVER_LOST_SUB, STATUS_CLOCK_UNVERIFIED,
        DRIVE_SERVER_LOST, DRIVE_OFF, DRIVE_OFF_CONSENT, DRIVE_OFF_WATCH_WAIT, DRIVE_OFF_WEAR, DRIVE_S1R, DRIVE_S2,
        DRIVE_BIO_HR, DRIVE_BIO_WORN, DRIVE_BIO_NOT_WORN, DRIVE_BIO_NO_SIGNAL,
        LINK_HEAD, LINK_PHONE, LINK_WATCH, LINK_SERVER, LINK_OK, LINK_LOST, WATCH_LINK_DIRECT, WATCH_LINK_VIA_PHONE,
        WATCH_NO_SIGNAL, PAIR_WAITING_ROLE, PAIR_WATCH_NO_SIGNAL,
        BIO_TITLE, BIO_HR, BIO_HR_UNIT, BIO_PULSE, BIO_PULSE_FOUND, BIO_PULSE_WEAK, BIO_PULSE_NOT_FOUND, BIO_PULSE_UNREADABLE,
        BIO_WORN, BIO_WORN_YES, BIO_WORN_NO, BIO_UPDATED, BIO_JUST_NOW, BIO_SEC_AGO, BIO_STALE, BIO_HIDDEN, BIO_PREF_UNKNOWN,
        BIO_NO_CONSENT, BIO_NO_VALUE, BIO_NO_SIGNAL, BIO_EMPTY, BIO_TEST_VALUE,
        TEST_BAND, TEST_START, TEST_STOP, TEST_CONFIRM_TITLE, TEST_CONFIRM_BODY, TEST_CONFIRM_SCENARIO, TEST_CONFIRM_OK,
        TEST_CONFIRM_CANCEL, TEST_WAITING, TEST_NO_ANSWER, TEST_PHONE_LOCAL, TEST_NEED_SERVER, TEST_NEED_PHONE, TEST_NEED_WATCHING,
        AIRBAG_UNREADABLE, SPEED_REASON, SPEED_DENIED,
        NOTIFICATION_CHANNEL, NOTIFICATION_TITLE, NOTIFICATION_TEXT,
    )
}
