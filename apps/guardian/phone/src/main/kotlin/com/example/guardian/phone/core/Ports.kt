// 엔진이 바깥(저장소 · 알람 · 문자 · 119 작성 화면 · 화면 안내)과 만나는 자리 — 안드로이드 구현과 시험용 가짜가 같이 따른다
package com.example.guardian.phone.core

import com.example.guardian.shared.protocol.GuardianSmsDedup

/** 앱 내부 저장(SharedPreferences, 자동 백업 제외). 문자 중복 막기 · 처리한 지시 id 를 앱을 다시 켜도 기억한다. */
interface PhoneStore {
    val registration: Registration

    /**
     * 처리를 마친 보호자 문자 키(`kind:eventId`) — 실제로 보냈거나 테스트 확인 대화에 답했다. 테스트 문자를 다시 묻지 않는 데만 쓰고,
     * 보낼지 판단은 [smsRecords] 와 :shared `GuardianSmsDedup` 이 한다(계약 §6.2).
     */
    fun isSmsSent(key: String): Boolean
    fun markSmsSent(key: String)

    /**
     * 보호자 문자 기록(:shared `GuardianSmsDedup.Record`, 계약 §6.2) — 앱을 다시 켜도 24시간 남는다(정리는 `decideAndRecord` 가 한다).
     * 저장은 `encode` 한 줄씩, 읽을 때 깨진 줄은 버리고 나머지를 쓴다. 동의 철회 · 모두 지우기 때 함께 지운다.
     */
    var smsRecords: List<GuardianSmsDedup.Record>

    fun isCommandDone(id: String): Boolean
    fun markCommandDone(id: String)

    /**
     * 휴대폰이 주인일 때의 상태 기계 스냅숏([SavedFlow], 시각은 벽시계) — 프로세스가 다시 떠도 진행 중 사건을 이어 가 보호자 문자를
     * 다시 보내거나 빠뜨리지 않게 한다. 사건이 없으면 null. 앱이 죽어도 남게 바로 쓴다(commit). 끄기 · 모두 지우기 때 지운다.
     */
    var savedFlow: SavedFlow?

    /**
     * 이번 짝 참여 시각(중계 시각, A-2 「참여 전 사건」 방어). 참여 직후에는 [JOIN_PENDING] 이고 첫 sync 응답의 중계 시각으로 엔진이 채운다.
     * 모르면(옛 설치) null — 그때는 참여 전 방어를 건너뛴다(:shared PhoneSmsGuard).
     */
    var pairJoinedAtServerMs: Long?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") v) {}

    /** 사람이 소리를 줄인(작성 화면 열기) 마지막 사건 id — 다시 뜬 휴대폰이 그 사건 알람을 다시 켜지 않는다(C-5). */
    var mutedEventId: String?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") v) {}

    /** 원격 문자 상한 계수와 막힌 문자 키([RemoteSmsLimit.encode]) — 앱을 다시 띄워도 남는다. 모두 지우기 때 함께 지운다. */
    var remoteSmsLimit: String?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") v) {}

    /**
     * 차에서 넘겨받은 사건 id(최근 [PhoneEngine.CAR_ORIGIN_MAX] 개) — 휴대폰 상태 기계가 그 사건의 문자를 내도 원격 문자로 보고 방어 · 원격 상한을 건다.
     * 저장된 사건을 되살린 뒤에도 남아야 하므로 저장한다. 모두 지우기 때 함께 지운다.
     */
    var carOriginEventIds: List<String>
        get() = emptyList()
        set(@Suppress("UNUSED_PARAMETER") v) {}

    /** 위 가운데 넘겨받은 기계가 휴대폰에서 새로 만든 사건 id — 참여 뒤 휴대폰이 연 사건이라 「참여 전 사건」 방어에서 뺀다(원격 상한은 건다). */
    var adoptedOwnEventIds: List<String>
        get() = emptyList()
        set(@Suppress("UNUSED_PARAMETER") v) {}

    companion object {
        /** 짝을 막 맺었고 아직 중계 시각을 받지 못했다. */
        const val JOIN_PENDING = 0L
    }
}

/** 테스트 보호자 문자 — 보내기 직전에 사람이 확인한다. */
data class PendingSms(val key: String, val to: String, val body: String)

/** 엔진이 알리는 안내(화면 한 줄). 문구는 화면 쪽 문구 표가 정한다. */
enum class Notice {
    /** 보호자에게 문자(위치 포함)를 보냈다 — 위치 제3자 제공 때마다 운전자에게 알린다(G3 · 위치정보법 제19조 ③). */
    SMS_SENT,
    SMS_SKIPPED_NO_CONSENT,
    SMS_SKIPPED_NO_GUARDIAN,
    SMS_SKIPPED_UNMARKED_TEST,
    SMS_FAILED,
    /** 보내기를 요청했지만 [CommandExecutor.SMS_RESULT_TIMEOUT_MS] 안에 결과가 오지 않았다 — 다시 보내지 않는다(중복 문자가 더 나쁘다). */
    SMS_UNCONFIRMED,
    /** 사건 문자를 보내지 못한 사이 운전자가 응답했다 — 사건 · 취소 문자 둘 다 보내지 않았다(PM 결정 2026-11-02). */
    SMS_SKIPPED_RESPONDED,
    COMPOSE_WAITING_STOP,
    REPORT_FAILED,
    /** 워치에 테스트를 보내려 했지만 워치가 중계에 직접 붙어 있지 않아 휴대폰 단독으로 돌린다(계약 §4.3 지시 가능 규칙). */
    TEST_PHONE_ONLY_NO_DIRECT_WATCH,
    /** 차에서 온 보호자 문자가 원격 상한(10분 3통 · 24시간 10통, 사고는 같은 값을 따로, D-6)에 걸려 보내지 않았다. */
    SMS_SKIPPED_LIMIT,
}

/**
 * 바깥 동작. 119 로 보내는 함수는 없다 — [open119Compose] 는 사람이 「보내기」를 누르는 작성 화면만 연다(G4).
 * [sendSms] 는 보호자 번호로만 쓰고, 구현은 [PhoneNumbers.isGuardianNumber] 가 아니면 보내지 않는다.
 */
interface PhoneEffects {
    fun startAlarm(stage: String, level: Int, test: Boolean)
    fun stopAlarm()

    /** 알람 소리만 줄인다(ALERT_STOP 까지 다시 울리지 않음). 상태 기계 · 문자 · 응답 받기는 그대로. */
    fun muteAlarm()

    /**
     * 보내기를 요청했으면 true(권한이 없거나 번호가 규칙 밖 · 예외면 false — 이때 [done] 은 부르지 않는다).
     * 요청한 뒤 실제 결과는 [done] 으로 엔진 스레드에서 한 번 돌아온다 — 모든 조각이 성공이면 true, 하나라도 실패면 false.
     * 결과가 끝내 오지 않을 수 있다(실행기가 시간 초과로 「확인 못 함」 처리). 시험용 가짜는 [done] 을 바로 부를 수도 있다.
     */
    fun sendSms(to: String, body: String, done: (Boolean) -> Unit): Boolean

    /**
     * 보호자 문자 결과 기록(P12 · P14 문자 기록 · G3 위치 제공 통보). [kind] 는 EVENT · CANCEL · ACCIDENT · OBSERVE,
     * 그리고 「응답 확인 꺼짐」 문자를 보내지 못했을 때의 POWER_OFF([CommandExecutor.POWER_OFF_KIND]).
     * [sent] 는 보냄 true · 보내지 못함 false · 결과를 확인 못 함 null.
     */
    fun guardianSmsResult(kind: String, sent: Boolean?) {}

    /**
     * [guardianSmsResult] 에 사건 id 를 더한 것 — 실행기는 이것을 부른다. 화면이 지금 사건의 기록만 보이게(B1 결함 6).
     * 기본은 사건 id 없이 넘긴다(:integration 시험 가짜는 앞 함수를 그대로 쓴다).
     */
    fun guardianSmsResult(kind: String, eventId: String, sent: Boolean?) = guardianSmsResult(kind, sent)

    /**
     * 위치가 든 보호자 문자(EVENT · ACCIDENT · OBSERVE)가 보냄(true) · 확인 못 함(null)으로 정해졌다(보안 7 · D-8 위치 제공 통보, G3) —
     * 본문 · 보호자 이름 · 번호 없이 시각과 「위치 포함」만 알린다. 소리 없음(운전 중 조작을 요구하지 않는다, G5).
     */
    fun locationSmsSettled(sent: Boolean?) {}

    /** 테스트 문자 확인 대화를 띄운다. 사람이 고르면 엔진의 confirmTestSms 로 돌아온다. */
    fun askTestSms(pending: PendingSms)

    /** 정차 뒤 무응답 전체 화면 경보(P12)를 띄운다. 문자 앱은 열지 않는다. */
    fun showNoResponse(body: Body119)

    /** `ACTION_SENDTO smsto:119` 작성 화면(반자동). 사람이 버튼을 눌렀고 차가 정지했을 때만 불린다. */
    fun open119Compose(body: String)

    /** 앱 안 미리보기만 — 보내기 경로가 없다(테스트 · previewOnly). */
    fun show119Preview(body: Body119)

    /** 안내 한 줄. [test] 면 테스트 줄 — [clearTestScreens] 가 테스트 줄만 거둔다. */
    fun showLine(line: String, button: String?, test: Boolean)
    fun notice(n: Notice)

    /** 테스트가 끝났다(멈추기 · 시나리오 끝 · 안전장치 · 실제 사고) — 테스트 미리보기(P18) · 테스트 안내 줄 · 테스트 알림을 거둔다. */
    fun clearTestScreens()

    /** 테스트 문자 확인(P17)에 답했다 — 그 알림을 거둔다(B1 결함 5). */
    fun clearTestSmsAsk() {}
}
