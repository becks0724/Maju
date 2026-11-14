// 워치 화면 문구 — 사건 흐름 문구는 :shared 문구 표(Phrase · SmsText), 워치 전용 문구는 여기(설계 spec §6-2 PM 채택분 + 워치 조작 문구)
package com.example.guardian.watch.text

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.text.Phrase
import com.example.guardian.shared.text.SmsText

/**
 * 워치 전용 문구. 이름 옆 주석의 ID 는 휴대폰 · 워치 화면 명세(비공개) §6-2 의 제안 ID(PM 채택 2026-10-30)이고,
 * 「(워치)」 표시는 spec 에 없어 app-wear 가 더한 것이다. 판정어 · 질병 이름 · 차량 제어 약속을 쓰지 않는다(G1, WatchTextTest).
 */
enum class WatchText(val ko: String) {
    HOME_ON("켜짐"), // HOME_ON
    HOME_OFF("꺼짐"), // HOME_OFF
    TURN_ON("켜기"), // (워치) 꺼짐 화면의 가장자리 버튼 — spec 은 「휴대폰에서 켜세요」지만 휴대폰 → 워치 켜기 지시가 계약에 없다
    TURN_OFF("끄기"), // (워치)
    STATUS("상태 보기"), // W_STATUS
    STATUS_HEAD("상태"), // W_STATUS_HEAD
    MEASURE("측정"), // W_MEASURE
    SEND("전송"), // W_SEND
    LINK_CAR("차"), // LINK_CAR
    LINK_OK("연결됨"), // LINK_OK
    LINK_AGO("%d초 전"), // LINK_AGO
    LINK_CHECKING("확인 중"), // LINK_CHECKING
    LINK_LOST("연결 끊김 · 휴대폰과 Wi-Fi 를 확인하세요"), // W_LINK_LOST
    LINK_NONE("연결 안 됨"), // (워치) 짝을 맺기 전
    PAIR_EXPIRED("연결 기간(24시간)이 끝났습니다 · 차 화면 코드로 다시 연결하세요"), // PAIR_EXPIRED
    VIA_PHONE("휴대폰 경유"), // VIA_PHONE
    VIA_WIFI("Wi-Fi 직접"), // VIA_WIFI

    PAIR("차와 연결"), // PAIR_TITLE
    STEP1("앞 3자리"), // W_STEP1
    STEP2("뒤 3자리"), // W_STEP2
    NEXT("다음"), // NEXT
    CONNECT("연결"), // PAIR_BUTTON
    PAIR_DONE("차와 연결됨"), // PAIR_DONE
    PAIR_INVALID("코드가 맞지 않음"), // W_PAIR_INVALID
    RETRY("다시 넣기"), // W_RETRY
    PAIR_NETWORK("서버에 연결하지 못했습니다 · 같은 Wi-Fi 인지 확인하세요"), // PAIR_NETWORK
    PAIR_NO_RELAY("중계 주소가 없는 빌드입니다"), // (워치) local.properties guardian.relayBase 없이 빌드

    WEAR_HINT("손목에 맞게 조여 주세요"), // W_WEAR_HINT
    ALARM_STAGE1("경보 1 · 진동"), // ALARM_STAGE1
    ALARM_STAGE2("경보 2 · 소리"), // ALARM_STAGE2
    RESPONDED_WAITING("응답 보냄 · 확인 중"), // (워치) 응답 뒤 ALERT_STOP 이 오기 전

    TEST("테스트"), // W_TEST
    TEST_MODE("테스트 모드"), // TEST_ENTRY
    STOP("멈추기"), // W_STOP
    REMAIN("남은 %d초"), // W_REMAIN
    SCN_LOOSE("느슨하게 착용"), // SCN_LOOSE
    SCN_MOTION("손목 움직임"), // SCN_MOTION
    SCN_DRIVE("차 진동"), // SCN_DRIVE

    SOURCE_SENSOR_SDK("삼성 센서"), // (워치)
    SOURCE_HEALTH_SERVICES("심박만 · 맥박 신호 정보 적음"), // (워치) 설계 §3 출처 표시 — spec 6-3 바꿈(PM 결정 11-02, 「판정」 낱말 제거 G1)
    PERMISSION_NEEDED("센서 · 알림 권한이 필요합니다"), // (워치)
    // 신호 없음(코드 리뷰 2026-11-02 상 1) — Health Services 유효 심박 10초 공백 · 등록 실패. 첫 화면은 앞부분, 상태 화면 측정 줄은 전체
    NO_SIGNAL("심박 신호를 못 잡고 있습니다 · 손목에 맞게 조여 주세요"), // (워치)
    SENSOR_FAILED("심박 측정을 시작하지 못했습니다 · 응답 확인을 끄고 다시 켜 보세요"), // (워치) 등록 실패 알림(RESUME_OFF 와 같은 방식)

    // 워치 직접 경로의 건강 관련 별도 동의(계약 §8 「동의가 없으면 워치 직접 경로를 쓰지 않는다」) — spec 에 워치 동의 화면이 없어 더함
    CONSENT_TITLE("건강 관련 정보 별도 동의"), // CONSENT_2
    CONSENT_BODY("워치의 맥박 신호 상태 · 심박 수치 · 착용 여부 · 응답 시각을 차 앱과 휴대폰 앱에 보내 응답 확인에 씁니다"), // (워치)
    CONSENT_RAW_STAYS("원신호는 워치 밖으로 보내지 않습니다"), // CONSENT_RAW_STAYS
    CONSENT_RIGHT("동의하지 않을 수 있습니다. 동의하지 않으면 이 기능을 쓸 수 없습니다"), // CONSENT_RIGHT
    CONSENT_AGREE("동의"), // (워치)
    CONSENT_WITHDRAW("동의 철회"), // (워치) G2 철회 시 삭제

    NOTIFICATION_MONITOR("응답 확인 켜짐"), // (워치) 포그라운드 서비스 알림
    ONGOING_DRIVING("운전 중"), // (워치) 감시 중 Ongoing Activity 상태 글자 — 시계 화면 · 「최근 앱」(사용자 지시 2026-11-08)
    // 켜 둔 감시를 재부팅 · 업데이트 뒤 저절로 다시 켜지 못했을 때(ResumeDecision) — 알림 제목 · 본문, 첫 화면 한 줄
    RESUME_OFF("응답 확인이 꺼졌습니다 · 눌러서 다시 켜세요"), // (워치)
    RESUME_NEED_PAIR("차와 연결하면 다시 켜집니다"), // (워치)
}

object WatchTexts {
    /** 「앞 · 뒤」 두 줄 문구의 앞부분(spec §6-1 「워치는 앞부분만」). */
    fun head(p: Phrase): String = p.ko.substringBefore(" · ")
    fun tail(p: Phrase): String = p.ko.substringAfter(" · ")
    fun headOf(t: WatchText): String = t.ko.substringBefore(" · ")

    fun source(s: WatchSource): String = when (s) {
        WatchSource.SENSOR_SDK -> WatchText.SOURCE_SENSOR_SDK.ko
        WatchSource.HEALTH_SERVICES -> WatchText.SOURCE_HEALTH_SERVICES.ko
        WatchSource.SIMULATION -> WatchText.TEST_MODE.ko
    }

    /** 테스트 시나리오 이름 — 맥박 신호 문구는 문구 표 그대로(spec §6-1 P15). */
    fun scenario(s: Scenario): String = when (s) {
        Scenario.NORMAL -> SmsText.PULSE_NORMAL
        Scenario.AMPLITUDE_DROP -> SmsText.PULSE_WEAK
        Scenario.PULSE_LOST -> SmsText.PULSE_NOT_FOUND
        Scenario.NOT_WORN -> SmsText.NOT_WORN
        Scenario.LOOSE -> WatchText.SCN_LOOSE.ko
        Scenario.MOTION -> WatchText.SCN_MOTION.ko
        Scenario.DRIVE_VIBRATION -> WatchText.SCN_DRIVE.ko
    }

    /** 경보 화면이 응답 없음(W09)인가 — 주행 중 무응답 S3 · 정차 뒤 무응답 S4. */
    fun isNoResponse(stage: FlowState) = stage == FlowState.S3 || stage == FlowState.S4

    /** 경보 배지 글자(W06 · W07 · W09). */
    fun alertBadge(stage: FlowState, level: Int): String = when {
        isNoResponse(stage) -> head(Phrase.SCREEN_S3) // 응답 없음
        level >= 2 -> WatchText.ALARM_STAGE2.ko
        else -> WatchText.ALARM_STAGE1.ko
    }

    /** 경보 큰 줄 — W06 · W07 은 SCREEN_S1 앞부분, W09 는 SCREEN_S3 뒷부분. */
    fun alertLine(stage: FlowState): String =
        if (isNoResponse(stage)) tail(Phrase.SCREEN_S3) else head(Phrase.SCREEN_S1)
}
