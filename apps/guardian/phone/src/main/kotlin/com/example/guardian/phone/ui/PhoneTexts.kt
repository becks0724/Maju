// 휴대폰 앱 화면 전용 문구 — 설계 휴대폰 · 워치 화면 명세(비공개) §6-2(PM 채택 2026-10-30). 사건 흐름 문구는 :shared Phrase · SmsText 를 그대로 쓴다(G1 금지어 시험 대상)
package com.example.guardian.phone.ui

import com.example.guardian.phone.core.Missing
import com.example.guardian.phone.core.Notice
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.text.SmsText

object PhoneTexts {
    /** 글자 로고 「Maju」 그림의 읽기 이름(TalkBack) — 뒤따르는 부제와 이어 「마주, 운전자 무응답 확인」으로 읽힌다. */
    const val BRAND_SPOKEN = "마주"

    // P01 시작
    const val START = "시작하기"
    const val START_INTRO = "워치와 차가 운전자의 응답을 확인하고, 응답이 없으면 보호자에게 알립니다"

    // P02 운전자 · 차량
    const val PROFILE_TITLE = "운전자 · 차량 정보"
    const val FIELD_NAME = "이름"
    const val FIELD_BIRTH = "생년월일"
    const val FIELD_BIRTH_HINT = "예 1968-03-15"
    const val FIELD_CAR_COLOR = "차량 색상"
    const val FIELD_CAR_PLATE = "차량 번호"
    const val COLOR_OTHER = "그 밖"
    val CAR_COLORS = listOf("흰색", "검은색", "회색", "은색", "파란색", "빨간색")
    const val PROFILE_WHY = "응답이 없을 때 보호자 문자와 119 작성 화면에 들어갑니다"
    const val AGE = "만 %d세"

    // P03 보호자
    const val GUARDIAN_TITLE = "보호자"
    const val FIELD_GUARDIAN_NAME = "보호자 이름"
    const val FIELD_GUARDIAN_PHONE = "보호자 연락처"
    const val GUARDIAN_WHY = "응답이 없을 때 문자를 받고, 119 의 확인 전화를 대신 받을 수 있는 분입니다"
    const val GUARDIAN_AGREED = "보호자에게 알리고 동의를 받았어요"
    const val GUARDIAN_PHONE_RULE = "보호자 연락처는 010 으로 시작하는 휴대폰 번호여야 합니다"
    const val REQUIRED = "필수"
    const val NEXT = "다음"

    // P04 동의 세 가지(G2 · G3 · G9). 불이익 고지 문장은 법률 검토 전 초안이다 [법률 검토 필요 · G8]
    const val CONSENT_TITLE = "동의 세 가지"
    const val CONSENT_ALL_NEEDED = "세 가지 모두 있어야 기능을 켤 수 있습니다"
    const val CONSENT_1 = "긴급 연락용 정보 등록 · 제공"
    const val CONSENT_2 = "건강 관련 정보 별도 동의"
    const val CONSENT_3 = "위치 제3자 제공"
    const val ROW_ITEMS = "항목"
    const val ROW_TO = "받는 곳"
    const val ROW_WHY = "목적"
    const val ROW_KEEP = "보관"
    val CONSENT_BODY_1 = listOf(
        "이름 · 만 나이 · 차량(차종 · 색상 · 번호) · 보호자 이름 · 연락처",
        "보호자(문자) · 119(작성 화면에서 직접 보낼 때)",
        "응답이 없을 때 연락",
        "이 휴대폰 안 · 동의를 철회하면 지움",
    )
    val CONSENT_BODY_2 = listOf(
        "워치 심박 수치 · 맥박 신호 상태 · 착용 여부 · 측정 시각 · 응답 확인이 필요한 상태",
        "보호자(문자) · 119(작성 화면) · 중계 서버(최신 1건만 메모리에, 기록 남기지 않음) · 연결한 차 화면(표시만 · 저장하지 않음)",
        "응답 확인이 필요한 상태를 알리고 문자에 사실을 적기",
        "이 휴대폰 안 · 동의를 철회하면 지움 · 중계 서버는 연결을 끝내거나 24시간이 지나면 지움",
    )
    val CONSENT_BODY_3 = listOf(
        "이 휴대폰의 위치(위도 · 경도 · 주소 · 정확도 · 속도 · 잰 시각)와 연결한 차의 내비 위치(위도 · 경도 · 주소 · 잰 시각)",
        "보호자(문자) · 119(작성 화면에서 직접 보낼 때) · 중계 서버(차 위치 최신 1건만 메모리에, 기록 남기지 않음) · 주소를 찾을 때 휴대폰의 주소 찾기 기능에 좌표가 전달될 수 있음",
        "구조하는 사람이 차를 찾도록 문자에 위치를 적기 · 주행과 정차 판단",
        "보내는 문자에만 · 중계 서버는 연결을 끝내거나 24시간이 지나면 지움",
    )
    const val CONSENT_RAW_STAYS = "원신호는 워치 밖으로 보내지 않습니다"
    /** 동의 문구 판이 올라 P04 를 다시 보일 때만 맨 위에 둔다(처음 설정에는 없음, PM 결정 2026-11-01). 판 2 에서 판 3 으로 올 때. */
    const val CONSENT_CHANGED = "동의 내용이 바뀌어 다시 동의가 필요합니다 · 건강 관련 정보와 위치 동의의 내용을 사실대로 고쳤습니다"
    /** 판 1 에서 바로 판 3 으로 올 때 — 판 2 의 차 화면 표시도 함께 알린다. */
    const val CONSENT_CHANGED_FROM_1 = "동의 내용이 바뀌어 다시 동의가 필요합니다 · 차 화면 표시가 더해졌고, 건강 관련 정보와 위치 동의의 내용을 사실대로 고쳤습니다"

    fun consentChanged(storedVersion: Int): String = if (storedVersion < 2) CONSENT_CHANGED_FROM_1 else CONSENT_CHANGED
    /** 동의 ② 차 화면 표시 고지(설계 car-spec 7-5, 동의 문구 판 2 — 2026-11-01). */
    const val CONSENT_CAR_DISPLAY = "연결한 차 화면에 심박 · 맥박 신호 상태 · 착용 여부를 보여 줍니다. 함께 탄 사람에게 보일 수 있으며, 홈의 「차 화면에 심박 숨기기」로 가릴 수 있습니다. 차에는 저장하지 않습니다"
    const val CONSENT_NOTIFY_EACH = "제공할 때마다 이 휴대폰에 알려 드립니다"
    const val CONSENT_RIGHT = "동의하지 않을 수 있습니다. 동의하지 않으면 이 기능을 쓸 수 없습니다"
    const val DEFAULT_OFF = "기능은 꺼진 채로 시작합니다. 홈에서 직접 켜세요"
    const val CONSENT_AGREE = "세 가지 모두 동의하고 다음"
    const val CONSENT_DECLINE = "동의하지 않고 나가기"

    // P05 권한
    const val PERM_TITLE = "권한"
    const val PERM_SMS = "문자 보내기"
    const val PERM_LOCATION = "위치"
    const val PERM_NOTIFY = "알림"
    const val PERM_SMS_WHY = "보호자에게 문자를 보냅니다"
    const val PERM_LOCATION_WHY = "문자에 위치를 넣습니다"
    const val PERM_NOTIFY_WHY = "경보를 알립니다"
    const val PERM_FULLSCREEN = "잠금 화면 전체 화면 알림"
    const val PERM_FULLSCREEN_WHY = "잠긴 화면 위에 경보를 띄웁니다"
    const val PERM_DND = "방해 금지 중 알람"
    const val PERM_DND_WHY = "방해 금지 중에도 알람을 울립니다"
    const val PERM_BG_LOCATION = "위치 항상 허용"
    const val PERM_BG_LOCATION_WHY = "휴대폰을 다시 켜거나 앱이 업데이트된 뒤 앱을 열지 않아도 위치를 받습니다 · 허용하지 않아도 기능을 켤 수 있습니다"
    const val PERM_BG_LOCATION_NEED_FINE = "위치를 먼저 허용하세요"
    /** 시스템 요청 전 이유 안내(P05 대화). */
    const val PERM_BG_LOCATION_ASK = "다음 화면에서 「항상 허용」을 고르세요. 휴대폰을 다시 켜거나 앱이 업데이트된 뒤 앱을 열지 않아도 위치를 받습니다. 위치는 기능이 켜져 있는 동안에만 받고, 보호자 문자 · 119 문자 작성 화면 · 주행과 정차 판단에 씁니다. 허용하지 않아도 기능을 켤 수 있습니다"
    const val PERM_CONTINUE = "계속"
    const val PERM_OPEN_SETTINGS = "설정 열기"
    const val PERM_GRANTED = "허용됨"
    const val PERM_ALLOW = "허용"
    const val PERM_NOT_NEEDED = "이 휴대폰은 따로 허용하지 않아도 됩니다"

    // P06 차와 연결
    const val PAIR_TITLE = "차와 연결"
    const val PAIR_HINT = "차 화면에 보이는 6자리 코드를 넣으세요"
    /** 코드 칸 묶음의 접근성 설명(화면에는 보이지 않음, 보조 기술이 읽음). */
    const val PAIR_CODE_LABEL = "연결 코드 6자리"
    const val PAIR_EXPIRE ="코드는 10분 동안 쓸 수 있습니다"
    const val PAIR_BUTTON = "연결"
    /** 「연결」을 누른 뒤 중계 답을 기다리는 동안 기다림 로고 옆 문구(2026-11-09). */
    const val PAIR_JOINING = "연결하는 중"
    const val PAIR_INVALID = "코드가 맞지 않거나 시간이 지났습니다 · 차 화면에서 새 코드를 받으세요"
    const val PAIR_NETWORK = "서버에 연결하지 못했습니다 · 같은 Wi-Fi 인지 확인하세요"
    const val PAIR_DONE = "차와 연결됨"
    const val PAIR_WATCH_NEXT = "워치에서도 같은 코드를 10분 안에 넣으세요"
    const val RELAY_MISSING = "중계 서버 주소가 없는 빌드입니다 · 휴대폰 혼자 동작합니다"

    // P07 · P08 홈
    const val HOME_ON = "켜짐"
    const val HOME_OFF = "꺼짐"
    const val HOME_ON_SUB = "켜져 있습니다 · 연결 상태는 아래를 보세요"
    const val HOME_OFF_SUB = "켜려면 아래 항목을 먼저 마치세요"
    /** 꺼짐인데 켜기를 막는 항목이 없을 때(B1 결함 7, 2026-11-08). */
    const val HOME_OFF_SUB_READY = "준비가 끝났습니다 · 오른쪽 스위치로 켜세요"
    const val GO = "바로 가기"
    const val LINK_HEAD = "연결"
    const val LINK_CAR = "차"
    const val LINK_WATCH = "워치"
    const val LINK_SERVER = "서버"
    const val LINK_OK = "연결됨"
    const val LINK_CHECKING = "확인 중"
    const val WATCH_DIRECT_MISSING = "워치 직접 연결 없음"
    const val WORN_NOW = SmsText.WORN
    const val CAR_MISSING_WHY = "차 앱과 연결이 끊겨 휴대폰 GPS 로 정차를 판단합니다"
    const val SERVER_LOST = "서버 연결 끊김 · Wi-Fi 를 확인하세요"
    const val PAIR_EXPIRED = "연결 기간(24시간)이 끝났습니다 · 차 화면 코드로 다시 연결하세요"
    const val RECONNECT = "다시 연결"
    const val NOT_PAIRED = "짝 없음 · 휴대폰 혼자 동작합니다"
    const val ALARM_OPEN = "경보 화면 보기"
    // 홈 「차 화면」 묶음(설계 car-spec 7-5 · 8-1)
    const val CAR_SCREEN_HEAD = "차 화면"
    const val HIDE_HR_ON_CAR = "차 화면에 심박 숨기기"
    const val HIDE_HR_ON_CAR_SUB = "켜면 차 화면에 심박 숫자와 맥박 신호 상태를 보이지 않습니다 · 경보는 그대로 울립니다"
    /** 재동의 전(동의 문구 판 1) — 스위치 대신(설계 spec 5-2 재동의 대기). */
    const val CAR_SCREEN_NEEDS_CONSENT = "차 화면 표시는 새 동의 뒤에 켜집니다"
    // 동의 철회 · 기록 지우기(G2, PM 결정 2026-10-30 — 설정 화면 전 최소판, 워치 앱의 「동의 철회」와 같은 동작)
    const val WITHDRAW = "동의 철회 · 기록 지우기"
    const val WITHDRAW_CONFIRM = "감시를 끄고 차와의 연결을 끊은 뒤, 이 휴대폰에 저장한 등록 정보 · 동의 · 문자 기록을 모두 지웁니다"
    const val WITHDRAW_OK = "철회하고 지우기"
    const val DRIVING_LOCK = "주행 중 · 휴대폰 조작은 정차한 뒤에 하세요"
    /** 차 연결도 GPS 속도도 없을 때(사용자 결정 2026-11-01) — 잠그지 않고 경고만 한다. */
    const val MOTION_UNKNOWN = "정차 여부를 알 수 없습니다 · 운전 중이면 조작하지 마세요"

    // P10 ~ P14 경보
    const val ALARM_STAGE1 = "경보 1 · 진동"
    const val ALARM_STAGE2 = "경보 2 · 소리"
    const val PHONE_HANDS_OFF = "휴대폰은 만지지 마세요"
    const val CAR_BUTTON_TOO = "차 화면 버튼으로도 응답할 수 있습니다"
    /** 차가 주인일 때 휴대폰 「괜찮아요」를 차로 보낸 뒤 ALERT_STOP 이 올 때까지(PM 지시 2026-10-30, spec 에 문구 없음). */
    const val RESPONSE_SENT = "응답 보냄 · 확인 중"
    const val COMPOSE_119 = "119 문자 작성 화면 열기"
    const val COMPOSE_119_HINT = "문자 앱에 내용이 채워집니다 · 보내기는 직접 누르세요"
    const val COMPOSE_119_REOPEN = "119 작성 다시 열기"
    const val COMPOSE_OPENED = "작성 화면을 열었습니다 %s · 보냈는지 문자 앱에서 확인하세요"
    const val OPEN_119_APP = "「119신고」 앱 열기"
    const val GUARDIAN_SENT = "문자 보냄 %s · 위치 포함"
    const val GUARDIAN_FAILED = "보내지 못함"
    /** 보내기를 요청했지만 결과가 오지 않았다(코드 리뷰 중 5) — 다시 보내지 않는다. */
    const val GUARDIAN_UNCONFIRMED = "보냈는지 확인 못 함"
    /** 「확인 못 함」 아래 작은 글씨 · 안내 뒷말 — 위치 제공 가능성 통보(G3). */
    const val LOCATION_MAYBE_GIVEN = "위치가 담긴 문자가 갔을 수 있습니다"
    const val PREVIEW_HEAD = "보낼 내용"
    const val EXPAND = "펼치기"
    const val OK_CONFIRM = "괜찮아요를 누르면 보호자에게 취소 문자가 갑니다"
    const val BACK = "돌아가기"
    const val RESULT_HEAD = "보호자 문자"
    const val RESULT_INCIDENT_SENT = "%s 응답 없음 안내 보냄"
    const val RESULT_CANCEL_SENT = "%s 취소 문자 보냄"
    const val LOCATION_GIVEN = "보호자에게 위치를 보냈습니다 %s"
    /** P14 응답 결과(S1R · S2, 정차 중)에만 작은 글씨로 — 문장은 사용자 결정 ⑥(2026-11-07) 그대로. 진단 · 판정어 없음(G1). */
    const val RESULT_CONSULT = "몸 상태가 걱정되면 의료진과 상담해 보세요"
    const val CLOSE = "닫기"

    // P15 ~ P18 테스트
    const val TEST_ENTRY = "테스트 모드"
    const val TEST_ONLY_PARKED = "정차 중에만 시작할 수 있습니다"
    /** 테스트 띠 — SmsText.TEST_PREFIX 의 낱말 + TEST_NOTICE(설계 §6-1). */
    val TEST_BAND = "${SmsText.TEST_PREFIX.trim('[', ']')} · ${SmsText.TEST_NOTICE}"
    const val TEST_SCENARIO = "시나리오"
    const val TEST_RUN_ON = "실행 기기"
    const val TEST_ON_WATCH = "워치"
    const val TEST_PHONE_ONLY = "휴대폰만"
    const val TEST_ASKS_SMS = "마지막에 보호자 테스트 문자를 보낼지 묻습니다"
    const val TEST_119_PREVIEW_ONLY = "119 는 미리보기만 보여 줍니다"
    const val TEST_START = "테스트 시작"
    /** 정차 여부를 모를 때 테스트 시작 전에 누르는 스스로 확인(10분 유효). */
    const val TEST_CONFIRM_STOPPED = "정차 중입니다"
    /** 「모름」이고 아직 확인하지 않았을 때 「테스트 시작」 아래 사유(설계 6-3 제안, 11-01 Pleos 양식). */
    const val TEST_CONFIRM_FIRST = "정차 중입니다를 먼저 눌러 주세요"
    /** 차 화면에서 시작한 테스트의 진행 화면 한 줄(설계 6-3 제안). */
    const val TEST_FROM_CAR = "차 화면에서 시작함"
    const val TEST_STOP = "테스트 멈추기"
    const val TEST_STEP_WATCH = "워치 신호 · %s"
    const val TEST_STEP_1 = "경보 1 · 진동 15초"
    const val TEST_STEP_2 = "경보 2 · 소리 20초"
    const val TEST_STEP_3 = "응답 없음 · 보호자 테스트 문자 확인"
    const val TEST_STEP_4 = "119 미리보기"
    const val TEST_NOW = "지금"
    const val TEST_BUSY = "진행 중인 경보가 끝난 뒤 시작할 수 있습니다"
    const val TEST_NEEDS_SETUP = "기능을 켠 뒤 시험할 수 있습니다"
    const val TEST_SMS_ASK = "보호자에게 테스트 문자를 보낼까요?"
    const val TEST_SMS_SEND = "보내기"
    const val TEST_SMS_SKIP = "보내지 않기"
    const val PREVIEW_ONLY = "미리보기 · 119 로 보내지 않습니다"
    const val TAB_FULL = "전체 판"
    const val TAB_SHORT = "초단문 판"
    const val COUNT_LMS = "%d자 · 장문 한 통"
    const val COUNT_SMS = "%dbyte · 단문 한 통"

    // 알림
    const val SERVICE_NOTIFY_TITLE = "운전자 응답 확인 중"
    const val SERVICE_NOTIFY_BODY = "기능이 켜져 있습니다 · 워치 상태와 응답 확인 상태를 짝지은 차 앱에 보내는 중"
    /** 백그라운드에서 되살아났고 위치 「항상 허용」이 없을 때(실측 11-13) — 앱을 열면 [SERVICE_NOTIFY_BODY] 로 돌아간다. */
    const val SERVICE_NOTIFY_BODY_NO_LOCATION = "기능이 켜져 있습니다 · 위치를 받지 못하고 있습니다 · 눌러서 앱을 열어 주세요"

    /** 차와 짝이 없거나 중계 주소가 없을 때 — 차로 보내는 것이 없다(검증 2차 하4, 사용자 결정 2026-11-13). */
    const val SERVICE_NOTIFY_BODY_UNPAIRED = "기능이 켜져 있습니다 · 차와 연결되어 있지 않습니다"

    /** 감시 중 켜기를 막는 권한이 빠졌을 때(중6) — 앱을 열라는 안내는 정차한 뒤(G5). */
    const val SERVICE_NOTIFY_BODY_NO_SMS = "기능이 켜져 있습니다 · 문자 권한이 꺼져 보호자에게 알릴 수 없습니다 · 정차한 뒤 앱을 열어 주세요"
    const val SERVICE_NOTIFY_BODY_NO_NOTIFY = "기능이 켜져 있습니다 · 알림이 꺼져 경보 화면이 뜨지 않을 수 있습니다 · 정차한 뒤 앱을 열어 주세요"
    const val SERVICE_NOTIFY_BODY_NO_FULL_SCREEN = "기능이 켜져 있습니다 · 전체 화면 알림이 꺼져 잠금 화면에 경보가 뜨지 않습니다 · 정차한 뒤 앱을 열어 주세요"
    /** 중계에 30초 넘게 닿지 않을 때(하9) — 「보내는 중」이라고 하지 않는다. */
    const val SERVICE_NOTIFY_BODY_SERVER_LOST = "기능이 켜져 있습니다 · 서버에 닿지 않아 차 앱에 보내지 못하고 있습니다"
    /** 워치 값은 오지만 경보 · 응답 지시가 워치에 닿지 않을 때(C-7 · 상1 최소안). */
    const val SERVICE_NOTIFY_BODY_WATCH_ALERT = "기능이 켜져 있습니다 · 워치에 경보가 가지 않습니다 · 정차한 뒤 앱을 열어 주세요"
    /** 홈 워치 줄 아래(C-7) — 「워치 직접 연결 없음」의 결과를 말한다. */
    const val WATCH_ALERT_UNREACHABLE = "워치에 경보가 가지 않습니다 · 정차한 뒤 워치를 다시 연결하세요"

    /**
     * 상태 알림 본문 — 위치가 막힘 · 켜기를 막는 권한이 빠짐(중6) · 짝 없음 · 서버에 닿지 않음(하9) · 워치에 경보가 가지 않음(C-7) 순으로
     * 하나만 말한다(보호자에게 닿지 못하는 것이 먼저). [blocked] 는 [Missing.SMS] · [Missing.NOTIFY] · [Missing.FULL_SCREEN] 가운데 첫 것.
     */
    fun serviceNotifyBody(
        locationBlocked: Boolean, paired: Boolean = true, blocked: Missing? = null,
        serverUnreachable: Boolean = false, watchAlertUnreachable: Boolean = false,
    ): String = when {
        locationBlocked -> SERVICE_NOTIFY_BODY_NO_LOCATION
        blocked == Missing.SMS -> SERVICE_NOTIFY_BODY_NO_SMS
        blocked == Missing.NOTIFY -> SERVICE_NOTIFY_BODY_NO_NOTIFY
        blocked == Missing.FULL_SCREEN -> SERVICE_NOTIFY_BODY_NO_FULL_SCREEN
        !paired -> SERVICE_NOTIFY_BODY_UNPAIRED
        serverUnreachable -> SERVICE_NOTIFY_BODY_SERVER_LOST
        watchAlertUnreachable -> SERVICE_NOTIFY_BODY_WATCH_ALERT
        else -> SERVICE_NOTIFY_BODY
    }

    /** 감시 중 상태 알림이 말할 빠진 권한(중6) — 감시를 막는 것 가운데 첫 것. */
    fun serviceBlocked(missing: List<Missing>): Missing? =
        missing.firstOrNull { it == Missing.SMS || it == Missing.NOTIFY || it == Missing.FULL_SCREEN }

    /** 테스트 문자 확인 알림 본문(보안 9) — 보호자 이름 · 번호 · 본문 전문을 잠금 화면에 싣지 않는다. 전문은 앱 화면(P17)에서만. */
    const val TEST_SMS_ASK_BODY = "보낼 테스트 문자를 앱에서 확인하세요"

    /** 위치가 든 보호자 문자가 정해질 때마다 남기는 알림(보안 7 · D-8) — 제목 · 시각 · 「위치 포함」만, 본문 · 이름 · 번호 없음. */
    const val LOCATION_SMS_SENT_TITLE = "보호자에게 위치를 담은 문자를 보냈습니다"
    const val LOCATION_SMS_UNCONFIRMED_TITLE = "보호자 문자가 나갔는지 확인하지 못했습니다"
    const val LOCATION_SMS_SENT_BODY = "%s · 위치 포함"
    const val LOCATION_SMS_UNCONFIRMED_BODY = "%s · 위치가 전달됐을 수 있습니다"
    const val LOCATION_SMS_CHANNEL = "보호자 문자 기록"

    /** 경보 화면 — 보내지 못한 보호자 문자를 지금 다시 보낸다(정지일 때만, 위임 결정 3). */
    const val RESEND_GUARDIAN_SMS = "보호자 문자 다시 보내기"

    /** 홈 「보낸 문자 기록」(D-8) — 저장된 최근 24시간 기록. */
    const val SMS_HISTORY_HEAD = "보낸 문자 기록"
    const val SMS_HISTORY_EMPTY = "최근 24시간 동안 보낸 보호자 문자가 없습니다"
    /** 한 줄 — 시각 · 종류 · 결과(저장 기록은 보냄 · 확인 못 함을 「보냄」 하나로, 보내지 못함 · 건너뜀을 「보내지 않음」 하나로 남긴다). */
    const val SMS_HISTORY_SENT = "%s · %s · 보냄"
    const val SMS_HISTORY_NOT_SENT = "%s · %s · 보내지 않음"
    /** 원격 상한(D-6)에 걸려 보내지 않은 줄에 붙인다. */
    const val SMS_HISTORY_LIMITED_MARK = "문자 상한"
    const val SMS_KIND_EVENT = "응답 확인 안내"
    const val SMS_KIND_ACCIDENT = "에어백 전개 안내"
    const val SMS_KIND_CANCEL = "응답 안내"
    const val SMS_KIND_OBSERVE = "정차 관찰 안내"
    const val ALARM_NOTIFY_TITLE = "응답 확인 필요"
    /** 시스템이 감시를 다시 띄우지 못했을 때(Android 14 이상 백그라운드 위치 유형 시작 거부 등). */
    const val SERVICE_STOPPED = "감시가 멈췄습니다 · 앱을 열어 다시 켜세요"

    fun scenario(s: Scenario): String = when (s) {
        Scenario.NORMAL -> SmsText.PULSE_NORMAL
        Scenario.AMPLITUDE_DROP -> SmsText.PULSE_WEAK
        Scenario.PULSE_LOST -> SmsText.PULSE_NOT_FOUND
        Scenario.LOOSE -> "느슨하게 착용"
        Scenario.NOT_WORN -> SmsText.NOT_WORN
        Scenario.MOTION -> "손목 움직임"
        Scenario.DRIVE_VIBRATION -> "차 진동"
    }

    // 엔진 안내
    fun notice(n: Notice): String = when (n) {
        Notice.SMS_SENT -> "보호자에게 위치를 담은 안내 문자를 보냈습니다"
        Notice.SMS_SKIPPED_NO_CONSENT -> "동의가 없어 보호자 문자를 보내지 않았습니다"
        Notice.SMS_SKIPPED_NO_GUARDIAN -> "보호자 번호가 없어 문자를 보내지 않았습니다"
        Notice.SMS_SKIPPED_UNMARKED_TEST -> "테스트 중이라 테스트 표시가 없는 문자는 보내지 않았습니다"
        Notice.SMS_FAILED -> "문자를 보내지 못했습니다. 휴대폰 신호와 문자 권한을 확인하세요"
        Notice.SMS_UNCONFIRMED -> "보호자 문자가 나갔는지 확인하지 못했습니다 · $LOCATION_MAYBE_GIVEN"
        Notice.SMS_SKIPPED_RESPONDED -> "운전자가 응답해 보호자 문자를 보내지 않았습니다"
        Notice.COMPOSE_WAITING_STOP -> "차가 멈추면 119 문자 작성 화면을 열 수 있습니다"
        Notice.REPORT_FAILED -> "등록 정보나 동의가 모자라 문자를 만들지 못했습니다"
        Notice.SMS_SKIPPED_LIMIT -> "짧은 시간에 차에서 온 문자 요청이 많아 보호자 문자를 보내지 않았습니다"
        Notice.TEST_PHONE_ONLY_NO_DIRECT_WATCH -> "워치가 직접 연결되지 않아 휴대폰만으로 테스트합니다 · 워치에서 연결 번호를 넣어 주세요"
    }

    /** 금지어 시험이 훑는 문구 전부(상수 · 목록 · 함수 결과). 상수는 반사로 모아 빠뜨리지 않는다. */
    val all: List<String> by lazy {
        val consts = PhoneTexts::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
        consts + CAR_COLORS + CONSENT_BODY_1 + CONSENT_BODY_2 + CONSENT_BODY_3 + TEST_BAND +
            Notice.entries.map { notice(it) } + Scenario.entries.map { scenario(it) }
    }
}
