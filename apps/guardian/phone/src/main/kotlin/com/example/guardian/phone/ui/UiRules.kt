// 화면에서 뺀 순수 표시 판단 — 주행 · 모름 띠 종류, 연결 줄 글리프, 경보 단계 띠, 테스트 시작 버튼 활성 · 사유, 테스트 진행 단계, 미리보기 탭(단위 시험 대상)
package com.example.guardian.phone.ui

import com.example.guardian.phone.core.ConsentVersion
import com.example.guardian.phone.core.EngineStatus
import com.example.guardian.phone.core.Motion
import com.example.guardian.phone.core.MotionRules
import com.example.guardian.phone.core.PendingSms
import com.example.guardian.phone.core.PhoneEngine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.GuardianSmsDedup
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.text.SmsText

/** P09 · P09' 띠. */
enum class BannerKind { DRIVING_LOCK, MOTION_UNKNOWN }

/** 연결 줄 글리프 — ● 연결됨(ok) · ▲ 끊김(lost 글리프만) · ○ 확인 중 · 짝 없음(text3). */
enum class LinkGlyph { OK, LOST, PENDING }

/** 경보 단계 띠(§7-2 · 7-3) — 경보 1 원 · 경보 2 삼각형 · 응답 없음 팔각형. */
enum class AlarmStage { STAGE1, STAGE2, NO_RESPONSE }

/** 경보가 끝난 뒤 경보 화면 — 응답 결과(P14), 에어백 전개(P14 변형), 응답을 보지 못했으면 닫기, 상태 모름이면 직전 화면 유지. */
enum class ResultKind { RESPONDED, ACCIDENT, CLOSE, KEEP }

object UiRules {
    /** 머리 막대 Maju 로고는 ← 없는 홈에만 — 처음 화면은 본문 가로 조합(MajuLockup)이 맡고, 하위 화면은 ← 와 제목만. */
    fun topBarLogo(canGoBack: Boolean, isStart: Boolean): Boolean = !canGoBack && !isStart

    /** 처음 화면 막대 제목은 비운다 — 바로 아래 MajuLockup 부제와 같은 문구가 두 번 보이고 두 번 읽히지 않게(PM 결정 2026-11-08). */
    fun topBarTitle(isStart: Boolean, appName: String): String = if (isStart) "" else appName

    /** 잠금 띠는 주행일 때만, 「모름」은 경고 띠, 정차 · 아직 모름(null)은 띠 없음. */
    fun banner(motion: Motion?): BannerKind? = when (motion) {
        Motion.DRIVING -> BannerKind.DRIVING_LOCK
        Motion.UNKNOWN -> BannerKind.MOTION_UNKNOWN
        Motion.STOPPED, null -> null
    }

    fun carGlyph(carFresh: Boolean, owner: Owner?): LinkGlyph = when {
        carFresh -> LinkGlyph.OK
        owner == Owner.PHONE -> LinkGlyph.LOST
        else -> LinkGlyph.PENDING
    }

    fun watchGlyph(watchStale: Boolean): LinkGlyph = if (watchStale) LinkGlyph.LOST else LinkGlyph.OK

    /** 짝 없음 · 중계 없는 빌드는 끊김이 아니라 ○(휴대폰 혼자 동작). */
    fun serverGlyph(server: ServerLink): LinkGlyph = when (server) {
        ServerLink.OK -> LinkGlyph.OK
        ServerLink.FAILED, ServerLink.EXPIRED -> LinkGlyph.LOST
        ServerLink.NOT_PAIRED, ServerLink.NO_RELAY -> LinkGlyph.PENDING
    }

    fun alarmStage(shownState: FlowState?, stage: Int): AlarmStage = when {
        shownState == FlowState.S3 || shownState == FlowState.S4 -> AlarmStage.NO_RESPONSE
        stage >= 2 -> AlarmStage.STAGE2
        else -> AlarmStage.STAGE1
    }

    /**
     * 경보 화면이 그릴 엔진 상태 — 서비스가 낸 상태가 있으면 그것. 상태 자체가 없으면(서비스가 멈춰 상태를 지움 · 첫 상태 전) 닫는다(null).
     * 서비스가 돌고 있고 이 화면이 경보를 본 적이 있을 때만 마지막 상태로 직전 화면을 둔다(코드 리뷰 2026-11-08 하2).
     * 엔진이 살아 있는데 흐름 상태만 모르는 틈(shownState=null)은 [heldState] 가 맡는다 — 이것과 다르다.
     */
    fun alarmStatus(status: EngineStatus?, last: EngineStatus?, running: Boolean, everAlarm: Boolean): EngineStatus? =
        status ?: last?.takeIf { running && everAlarm }

    /**
     * 전체 화면 경보 알림을 둘 것인가 — 경보 상태(S1 · AP · S3 · S4)거나 남은 알람이면 둔다. 알려진 비경보 상태(응답 · ALERT_STOP 뒤 ·
     * 남은 알람 응답 · 사건 끝)면 거둔다. 상태 모름(이어받기 전 틈)이면 직전 그대로(코드 리뷰 2026-11-08 하4). 서비스가 멈출 때는 서비스가 거둔다.
     */
    fun alarmNotice(prev: Boolean, shownState: FlowState?, stranded: Boolean): Boolean = when {
        stranded || shownState in PhoneEngine.ALARM_STATES -> true
        shownState == null -> prev
        else -> false
    }

    /** 경보 화면이 그릴 상태 — 상태 모름(null)이면 직전 상태를 그대로(경보면 경보 화면, 결과면 결과 화면). 알려진 상태는 그대로 바꾼다. */
    fun heldState(prev: FlowState?, shownState: FlowState?): FlowState? = shownState ?: prev

    /**
     * 경보 화면이 그릴 상태 — 실제 알람만 남아 울리면([EngineStatus.strandedAlarm]) 끌 수단이 있는 경보 화면으로 그린다.
     * 정차면 「괜찮아요」 하나인 정차 경보(AP), 주행이면 버튼 없는 주행 경보(S1, 워치 버튼 안내 · G5). 그 밖은 [held] 그대로.
     */
    fun alarmView(held: FlowState?, stranded: Boolean, stopped: Boolean): FlowState? = when {
        !stranded || held in PhoneEngine.ALARM_STATES -> held
        stopped -> FlowState.AP
        else -> FlowState.S1
    }

    /** 주행 경보 화면의 「차 버튼으로도」 안내 — 남은 알람은 차 버튼으로 꺼지지 않으니 그 화면에서는 숨긴다(app-qa 하, 2026-11-08). */
    fun showCarButtonHint(stranded: Boolean): Boolean = !stranded

    /**
     * 경보 화면이 이번 경보의 응답(S1R · S2)을 보았는가 — 새 경보(S1 · AP · S3 · S4)면 처음부터, 응답 상태면 참, 그 밖(S0 · OFF · S5 · 모름)은 앞 값 그대로.
     * 응답한 뒤 테스트가 끝나 S0 이 되어도 응답 결과는 사실이라 남긴다.
     */
    fun alarmResponded(prev: Boolean, shownState: FlowState?): Boolean = when (shownState) {
        in PhoneEngine.ALARM_STATES -> false
        FlowState.S1R, FlowState.S2 -> true
        else -> prev
    }

    /**
     * 경보가 끝난 뒤 무엇을 보일까 — 「응답 확인됨」은 응답 뒤 상태이거나 이 화면이 응답을 보았을 때만(B1 결함 1, 2026-11-08).
     * 테스트 멈춤 · 안전장치 · 끄기처럼 끝난 것이 확인된 상태(S0 · OFF)이고 응답을 보지 못했으면 응답했다고 쓰지 않고 화면을 닫는다.
     * 상태 모름(null — 차 링크가 끊겨 휴대폰이 이어받기 전의 틈)은 끝난 것이 아니라 닫지 않는다(app-qa 불합격 · PM 결정 2026-11-08).
     */
    fun resultKind(shownState: FlowState?, responded: Boolean): ResultKind = when {
        shownState == null -> ResultKind.KEEP
        shownState == FlowState.S5 -> ResultKind.ACCIDENT
        shownState == FlowState.S1R || shownState == FlowState.S2 || responded -> ResultKind.RESPONDED
        else -> ResultKind.CLOSE
    }

    /**
     * 경보 화면이 기억할 사건 id — 경보 · 응답 · 사고 상태(S1 · AP · S3 · S4 · S1R · S2 · S5)의 사건 id, 그 밖(테스트가 끝난 S0 등)은 앞 값 그대로.
     * 응답 뒤 테스트가 끝나도 P14 가 그 사건의 문자 기록을 계속 보인다.
     */
    fun incidentEventId(prev: String?, shownState: FlowState?, eventId: String?): String? =
        if (eventId != null && shownState in INCIDENT_STATES) eventId else prev

    /** P12 · P14 문자 기록 — 이번 사건 것만(최근 10건에 앞 사건 결과가 섞이지 않게, B1 결함 6). 사건을 모르면 비운다. */
    fun incidentSmsLog(log: List<SmsLogItem>, eventId: String?): List<SmsLogItem> =
        if (eventId == null) emptyList() else log.filter { it.eventId == eventId }

    private val INCIDENT_STATES = PhoneEngine.ALARM_STATES + setOf(FlowState.S1R, FlowState.S2, FlowState.S5)

    /** P12 「「119신고」 앱 열기」 — 실제 경보에서만. 테스트는 「119 는 미리보기만 보여 줍니다」를 약속한다(B1 결함 3, G4). */
    fun showOpen119App(flowTest: Boolean): Boolean = !flowTest

    /** P12 안 테스트 문자 확인(P17) — 테스트 경보 화면에서도 같은 확인을 답하게 한다(B1 결함 4). 실제 경보 화면에는 두지 않는다. */
    fun alarmSmsAsk(pending: List<PendingSms>, flowTest: Boolean): PendingSms? = pending.firstOrNull()?.takeIf { flowTest }

    /** P07 꺼진 홈 부제 — 켜기를 막는 항목이 있을 때만 「먼저 마치세요」, 없으면 켜기 안내(B1 결함 7). */
    fun homeOffSub(canTurnOn: Boolean): String = if (canTurnOn) PhoneTexts.HOME_OFF_SUB_READY else PhoneTexts.HOME_OFF_SUB

    /** P15 「정차 중입니다」 — 「모름」이고 아직 확인하지 않았을 때만 보인다. */
    fun showStopConfirm(motion: Motion?, stopConfirmed: Boolean): Boolean = motion == Motion.UNKNOWN && !stopConfirmed

    /** P15 「테스트 시작」 활성 — 지금 동작 그대로(MotionRules.canStartTest). */
    fun testStartEnabled(motion: Motion?, stopConfirmed: Boolean): Boolean =
        motion != null && MotionRules.canStartTest(motion, stopConfirmed)

    /** 「테스트 시작」이 비활성일 때 아래 사유 한 줄. */
    fun testStartReason(motion: Motion?, stopConfirmed: Boolean): String? = when {
        testStartEnabled(motion, stopConfirmed) -> null
        showStopConfirm(motion, stopConfirmed) -> PhoneTexts.TEST_CONFIRM_FIRST
        else -> PhoneTexts.TEST_ONLY_PARKED
    }

    /** P16 지금 단계(0 워치 신호 · 1 경보 1 · 2 경보 2 · 3 응답 없음). */
    fun testStep(shownState: FlowState?, stage: Int): Int = when {
        shownState == FlowState.S4 || shownState == FlowState.S3 -> 3
        stage >= 2 -> 2
        stage == 1 -> 1
        else -> 0
    }

    /**
     * P14 상담 권유 한 줄(사용자 결정 ⑥) — 응답 결과(S1R · S2)이고 정차했을 때만. 주행 중 결과 화면(5초 뒤 홈)에는 읽을거리를 두지 않고(G5),
     * S5(에어백 전개)는 설계 spec P14 변형이 SCREEN_S5 와 문자 결과만 두는 화면이라 넣지 않는다 — 사고 뒤 안내는 차량 SOS · 119 몫이다.
     */
    fun showConsultLine(shownState: FlowState?, stopped: Boolean): Boolean =
        stopped && (shownState == FlowState.S1R || shownState == FlowState.S2)

    /** P18 탭 — 초단문 판이 없으면 늘 전체 판(0). */
    fun previewTab(selected: Int, hasUltra: Boolean): Int = if (hasUltra && selected == 1) 1 else 0

    /** 「보호자 문자 다시 보내기」 — 다시 보낼 문자가 있고 정지일 때만(G5 운전 중 조작 요구 금지). */
    fun showResendSms(canResend: Boolean, stopped: Boolean): Boolean = canResend && stopped

    /** 테스트 문자 확인 알림 본문(보안 9) — 늘 같은 문장. 보호자 이름 · 번호 · 본문을 잠금 화면에 싣지 않는다. */
    @Suppress("UNUSED_PARAMETER")
    fun testSmsNotifyBody(pending: PendingSms): String = PhoneTexts.TEST_SMS_ASK_BODY

    /** 위치 제공 통보(보안 7) 제목 · 본문 — [sent] 보냄 true · 확인 못 함 null, [hm] 은 정해진 시각 「시:분」. */
    fun locationNotice(sent: Boolean?, hm: String): Pair<String, String> =
        if (sent == true) PhoneTexts.LOCATION_SMS_SENT_TITLE to PhoneTexts.LOCATION_SMS_SENT_BODY.format(hm)
        else PhoneTexts.LOCATION_SMS_UNCONFIRMED_TITLE to PhoneTexts.LOCATION_SMS_UNCONFIRMED_BODY.format(hm)

    /**
     * 홈 「보낸 문자 기록」(D-8) — 저장된 보호자 문자 기록(최근 24시간, [GuardianSmsDedup.prune])을 새것부터. 번호 · 본문은 싣지 않는다.
     * 테스트 문자는 테스트 머리를 붙인다. 「위치 포함」은 싣지 않는다 — 저장 기록에 본문의 위치 여부가 없다(app-qa 관문 2 조건 1, 위치는 통보 알림이 사실대로 말한다).
     */
    fun smsHistory(
        records: List<GuardianSmsDedup.Record>, nowWall: Long, limitedKeys: Set<String> = emptySet(), hm: (Long) -> String,
    ): List<String> =
        GuardianSmsDedup.prune(records, nowWall).sortedByDescending { it.recordedAtWallMs }.map { r ->
            val kind = (if (r.test) "${SmsText.TEST_PREFIX} " else "") + smsKind(r.kind)
            val line = (if (r.sent) PhoneTexts.SMS_HISTORY_SENT else PhoneTexts.SMS_HISTORY_NOT_SENT).format(hm(r.recordedAtWallMs), kind)
            // 원격 상한(D-6)에 걸린 문자는 이유를 붙인다 — 사람이 「왜 안 갔는지」 알 수 있게
            if (!r.sent && "${r.kind.name}:${r.eventId}" in limitedKeys) "$line · ${PhoneTexts.SMS_HISTORY_LIMITED_MARK}" else line
        }

    private fun smsKind(k: GuardianSmsWireKind) = when (k) {
        GuardianSmsWireKind.EVENT -> PhoneTexts.SMS_KIND_EVENT
        GuardianSmsWireKind.ACCIDENT -> PhoneTexts.SMS_KIND_ACCIDENT
        GuardianSmsWireKind.CANCEL -> PhoneTexts.SMS_KIND_CANCEL
        GuardianSmsWireKind.OBSERVE -> PhoneTexts.SMS_KIND_OBSERVE
    }
}

/** P04 동의 화면 판단(spec 5-1 · 9절) — 재동의([changed])일 때만 바뀐 동의(② · ③)를 끈 채로 열고, 거절해도 철회하지 않는다. */
object ConsentRules {
    /** 처음 값 — 재동의면 ② · ③ 은 꺼진 채, ① 은 저장값 그대로(판 3). 처음 설정 · 설정에서 다시는 저장값 그대로. */
    fun initial(current: Consents, changed: Boolean): Consents = if (changed) current.copy(health = false, location = false) else current

    /** 재동의 화면의 「바뀐 곳」 표시 — ② 차 화면 표시 고지([carNote], 판 1 에서만) · ② 항목 · 보관 줄([healthItems]) · ③ 네 줄([location]). */
    data class Changes(val carNote: Boolean, val healthItems: Boolean, val location: Boolean)

    fun changes(storedVersion: Int, changed: Boolean): Changes =
        if (!changed) Changes(false, false, false)
        else Changes(carNote = storedVersion < ConsentVersion.CAR_DISPLAY_SINCE, healthItems = storedVersion < 3, location = storedVersion < 3)

    /**
     * 「동의하지 않고 나가기」가 철회인가 — 철회면 「동의 철회 · 기록 지우기」와 같이 저장(등록 정보 · 보호자 포함)을 모두 지운다(PM 결정 2026-11-09).
     * 재동의 맥락에서는 저장된 동의를 그대로 두고 홈으로 간다.
     */
    fun declineWithdraws(changed: Boolean): Boolean = !changed

    /** 「세 가지 모두 동의하고 다음」 — 셋 다 켜졌을 때만. */
    fun canAgree(c: Consents): Boolean = c.emergencyInfo && c.health && c.location
}
