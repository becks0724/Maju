// 차 화면에 보일 상태를 고르는 순수 함수 — 경보 · 안내 가르기 · 번호 화면 여부 · 연결 줄 · 상태 한 줄(정차 · 주행 두 열) · 워치 칸 · 주행 워치 한 줄 · 테스트 입구(안드로이드 의존 없음)
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.WatchFreshness
import com.example.guardian.shared.protocol.TestAction
import com.example.guardian.shared.protocol.Via
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.text.Phrase

/** 연결 줄 하나 — 이름 · 정상 여부(표시 기호) · 상태 문구. */
data class LinkRowView(val name: String, val ok: Boolean, val detail: String)

/** 맥박 신호 값 — 글리프(● ◐ ○, 값을 보일 때만)와 G1 낱말. 상태색은 쓰지 않는다(car-spec 4-2). */
data class PulseView(val glyph: String?, val text: String)

/**
 * 워치 칸(정차 중 2×2 격자) — 화면이 그대로 그리는 글자.
 * [hr] 는 숫자를 보일 때만 값이 있고, 아니면 [hrText] 가 대신 문구(숨김 · 동의 전 · 측정값 없음 · 「—」)다.
 * [worn] 은 신호가 있을 때의 착용 판정(주행 워치 한 줄용), [noSignal] 이면 큰 글자 「워치 신호 없음」.
 */
data class BioView(
    val hr: Int?,
    val hrText: String,
    val pulse: PulseView,
    val wornText: String,
    val updated: String,
    val worn: Boolean,
    val noSignal: Boolean,
    val testTag: Boolean,
) {
    companion object {
        val NO_SIGNAL = BioView(
            hr = null, hrText = CarTexts.BIO_EMPTY, pulse = PulseView(null, CarTexts.BIO_EMPTY), wornText = CarTexts.BIO_EMPTY,
            updated = CarTexts.BIO_STALE, worn = false, noSignal = true, testTag = false,
        )
    }
}

/** 테스트 입구 — 버튼 글자 · 누를 수 있는지 · 아래 사유 한 줄 · 누르면 보낼 동작. */
data class TestEntryView(val label: String, val enabled: Boolean, val reason: String?, val action: TestAction)

/**
 * 짝지은 뒤 상태 화면.
 * - 정차 중 — 상태 한 줄 [line] (+ [sub]), 연결 줄, 「새 연결 번호」 또는 [code], 워치 칸 [bio], 테스트 입구 [test].
 * - 주행 중 — 상태 한 줄과 워치 한 줄 [driveBio] 만(버튼 · 연결 줄 · 칸 없음, G5).
 */
data class IdleView(
    val line: String,
    val rows: List<LinkRowView>,
    val newCode: Boolean,
    val code: String? = null,
    val sub: String? = null,
    val bio: BioView? = null,
    val test: TestEntryView? = null,
    val driveBio: String? = null,
) {
    /** 테스트 시작 확인 창을 그려도 되는지 — 창을 연 상태여도 주행으로 바뀌거나 입구가 막히면 바로 걷는다. */
    fun confirmVisible(open: Boolean): Boolean = open && test?.let { it.enabled && it.action == TestAction.START } == true
}

/** 지금 그릴 화면 — 경보 > 중계 주소 없음 > 번호 화면 > 상태 화면(정차 · 주행) 순서(car-spec 1절). */
enum class CarPage { ALARM, RELAY_MISSING, PAIRING, PARKED, DRIVING }

object CarScreen {
    /** 마지막 갱신 「방금」 범위 · 계단 폭(car-spec 4-3). */
    const val UPDATED_STEP_MS = 5_000L
    /** 테스트 요청 뒤 이 시간 안에 흐름 test 가 바뀌지 않으면 「휴대폰이 답하지 않습니다」(car-spec 5절). */
    const val TEST_ANSWER_MS = 15_000L

    /**
     * 다른 화면을 덮는 경보 상태 — 응답 확인 필요(S1 · AP) · 응답 없음(S3 · S4) · 에어백(S5). 테스트 경보도 같은 상태다.
     * 꺼짐 · 감시(OFF · S0)와 응답 뒤(S1R · S2)의 문구는 안내라 덮지 않는다(베타 2일차 11-02 — 짝이 풀린 S0 의 「워치 신호 없음」이 번호 화면을 덮음).
     */
    val ALARM_STATES = setOf(FlowState.S1, FlowState.AP, FlowState.S3, FlowState.S4, FlowState.S5)

    /** 경보 화면 한 줄 — 경보 상태일 때만. */
    fun alarmLine(state: FlowState, screen: ScreenView): String? = screen.line?.takeIf { state in ALARM_STATES }

    /** 안내 한 줄 — 경보가 아닌 상태의 문구. 상태 화면의 상태 한 줄 자리에 두고, 번호 화면에서는 생략한다. */
    fun noticeLine(state: FlowState, screen: ScreenView): String? = screen.line?.takeUnless { state in ALARM_STATES }

    /** 화면 고르기. 경보([alarmLine])는 다른 모든 칸(연결 줄 · 워치 칸 · 테스트 입구 · 번호)을 덮는다. */
    fun page(alarmLine: String?, relayConfigured: Boolean, paired: Boolean, pairCode: String?, links: Links, setupOk: Boolean): CarPage = when {
        alarmLine != null -> CarPage.ALARM
        !relayConfigured -> CarPage.RELAY_MISSING
        showPairing(paired, pairCode, links) -> CarPage.PAIRING
        setupOk -> CarPage.PARKED
        else -> CarPage.DRIVING
    }

    /** 테스트 시작 확인 창을 열 수 있는지 — 정차 중 상태 화면이고 입구가 「테스트 시작」으로 열려 있을 때만. 거짓이 되면 연 창도 닫는다. */
    fun confirmAllowed(page: CarPage, view: IdleView): Boolean = page == CarPage.PARKED && view.confirmVisible(open = true)

    /**
     * 「휴대폰 연결 끊기」 버튼 · 확인 창을 둘 수 있는지 — 문([CarHub.unpairOk])이 열려 있고 정차 화면 또는 번호 화면일 때.
     * 번호 화면(휴대폰 · 워치를 기다리는 최대 10분)에서도 끊을 수 있게 한다(코드 리뷰 11-09 차 하).
     */
    /**
     * 차속 권한 줄(G-2) — 정차 열에만. 받기 전에는 이유(권한 창 직전), 물은 뒤에도 없으면 결과, 받았으면 없음.
     * 권한 창은 위치 그룹 대화상자라 실제 창 문구는 [미검증].
     */
    fun speedLine(granted: Boolean, asked: Boolean): String? = when {
        granted -> null
        asked -> CarTexts.SPEED_DENIED
        else -> CarTexts.SPEED_REASON
    }

    /**
     * 보호자 문자 결과 줄(A-5) — 휴대폰이 보냄을 확인하면 경보 화면 · 정차 화면에 「보호자에게 알림 보냄」(음성은 새로 내지 않음).
     * 보내지 않았다고 알리면 정차 열에만(경보 화면은 1~2초 파악을 위해 줄을 늘리지 않음). 결과 모름은 줄 없음.
     */
    fun smsLine(status: SmsStatus?, alarm: Boolean): String? = when (status) {
        SmsStatus.CONFIRMED -> com.example.guardian.shared.text.Phrase.SCREEN_SMS_CONFIRMED.ko
        SmsStatus.NOT_SENT -> com.example.guardian.shared.text.Phrase.SCREEN_SMS_NOT_SENT.ko.takeUnless { alarm }
        null -> null
    }

    fun unpairAllowed(page: CarPage, unpairOk: Boolean): Boolean = (page == CarPage.PARKED || page == CarPage.PAIRING) && unpairOk

    /** 번호 화면 — 짝이 없거나, 번호가 살아 있고 아직 안 붙은 역할(휴대폰 · 워치)이 있을 때만. 「신호 없음」만 보내는 워치도 붙은 것이다. */
    fun showPairing(paired: Boolean, pairCode: String?, links: Links): Boolean =
        !paired || (pairCode != null && !(links.phone && links.watchJoined))

    /**
     * 번호 화면 로고가 「기다림」으로 도는지 — 번호를 띄워 휴대폰 · 워치를 기다리거나 번호를 받는 중일 때만.
     * 중계 기록 안내 · 중계 연결 실패는 기다림이 아니다(motion.md 5절). 주행 제한은 [MajuSpin.mode] 가 따로 본다.
     */
    fun pairingWaiting(pairCode: String?, unpairRelayLeft: Boolean, pairFailed: Boolean): Boolean =
        pairCode != null || (!unpairRelayLeft && !pairFailed)

    /** 번호 화면의 한 줄 — 이미 붙은 역할과 기다리는 역할. 워치가 붙어 「신호 없음」만 보내면 그 사실대로(리뷰 11-08 의심). */
    fun pairingLine(links: Links): String {
        fun part(name: String, ok: Boolean) = "$name ${if (ok) CarTexts.LINK_OK else CarTexts.PAIR_WAITING_ROLE}"
        val watch = if (!links.watchSeen && links.watchSendingNoSignal) "${CarTexts.LINK_WATCH} ${CarTexts.PAIR_WATCH_NO_SIGNAL}"
        else part(CarTexts.LINK_WATCH, links.watchSeen)
        return "${part(CarTexts.LINK_PHONE, links.phone)} · $watch"
    }

    /** 연결 줄 세 개. 워치 줄은 받는 길(Wi-Fi 직접 · 휴대폰 경유)만 보이고 착용은 워치 칸이 맡는다(car-spec 3-1). */
    fun linkRows(links: Links, via: Via? = null): List<LinkRowView> = listOf(
        LinkRowView(CarTexts.LINK_PHONE, links.phone, if (links.phone) CarTexts.LINK_OK else CarTexts.LINK_LOST),
        if (!links.watchSeen) LinkRowView(CarTexts.LINK_WATCH, false, CarTexts.WATCH_NO_SIGNAL)
        else LinkRowView(
            CarTexts.LINK_WATCH, true,
            when (via) {
                Via.WATCH -> CarTexts.WATCH_LINK_DIRECT
                Via.PHONE -> CarTexts.WATCH_LINK_VIA_PHONE
                null -> CarTexts.LINK_OK
            },
        ),
        LinkRowView(CarTexts.LINK_SERVER, links.server, if (links.server) CarTexts.LINK_OK else CarTexts.LINK_LOST),
    )

    /**
     * 흐름이 꺼짐(OFF)인 까닭 — 상태 기계의 OFF → S0 조건(켬 · 동의 전부 · 워치 신선 · 착용)을 이 순서로 본다.
     * 네 조건이 다 맞으면 다음 입력에 S0 로 가므로 「확인 중」. [driving] 이면 원인만 적는 주행 열(car-spec 7-1).
     */
    fun offLine(powerOn: Boolean, consentsAll: Boolean, watch: WatchLink, driving: Boolean = false): String = when {
        !powerOn -> if (driving) CarTexts.DRIVE_OFF else CarTexts.STATUS_OFF
        !consentsAll -> if (driving) CarTexts.DRIVE_OFF_CONSENT else CarTexts.STATUS_OFF_CONSENT
        watch == WatchLink.NO_SIGNAL -> if (driving) CarTexts.DRIVE_OFF_WATCH_WAIT else CarTexts.STATUS_OFF_WATCH_WAIT
        watch == WatchLink.NOT_WORN -> if (driving) CarTexts.DRIVE_OFF_WEAR else CarTexts.STATUS_OFF_WEAR
        else -> CarTexts.STATUS_WATCHING
    }

    /**
     * 워치 칸(car-spec 4절). [watch] 는 마지막으로 받은 값, [ageNowMs] 는 그 나이(중계 `ageMs` + 받은 뒤 지난 단조 시간).
     * 서버가 끊겼으면 부르는 쪽이 [watch] 를 null 로 넘긴다.
     * - 나이가 30초를 넘거나 값이 없으면 신호 없음 — 옛 심박 숫자를 지운다(계약 §4.3 신선도). 워치가 신호를 못 잡고 있다고
     *   알린 값(`noSignal`)도 같다 — 그 값의 심박 · 판정은 보이지 않는다(코드 리뷰 2026-11-02 상 1).
     * - 심박 자리는 숨김 설정 모름 → 숨김 → 동의 없음 → 값 없음 순으로 고른다. 맥박 신호는 앞의 셋을 같이 따른다.
     * [hideHr] 는 휴대폰 `carDisplay.hideHr`(null = 모름).
     */
    fun bio(watch: Watch?, ageNowMs: Long?, healthConsent: Boolean, hideHr: Boolean?): BioView {
        if (watch == null || ageNowMs == null || WatchFreshness.isStale(ageNowMs) || watch.noSignal) return BioView.NO_SIGNAL
        val blocked = when {
            hideHr == null -> CarTexts.BIO_PREF_UNKNOWN
            hideHr -> CarTexts.BIO_HIDDEN
            !healthConsent -> CarTexts.BIO_NO_CONSENT
            else -> null
        }
        val hr = if (blocked == null) watch.hr else null
        return BioView(
            hr = hr,
            hrText = blocked ?: hr?.toString() ?: CarTexts.BIO_NO_VALUE,
            pulse = if (blocked != null) PulseView(null, blocked) else pulse(watch.verdict),
            wornText = if (watch.worn) CarTexts.BIO_WORN_YES else CarTexts.BIO_WORN_NO,
            updated = updated(ageNowMs),
            worn = watch.worn,
            noSignal = false,
            testTag = watch.test || watch.source == WatchSource.SIMULATION,
        )
    }

    /** 맥박 신호 — 모양과 G1 낱말만(car-spec 4-2). */
    fun pulse(v: WristVerdict): PulseView = when (v) {
        WristVerdict.NORMAL -> PulseView("●", CarTexts.BIO_PULSE_FOUND)
        WristVerdict.PULSE_WEAK -> PulseView("◐", CarTexts.BIO_PULSE_WEAK)
        WristVerdict.PULSE_ABSENT -> PulseView("○", CarTexts.BIO_PULSE_NOT_FOUND)
        WristVerdict.CHECK_WEAR -> PulseView("○", CarTexts.BIO_PULSE_UNREADABLE)
    }

    /** 마지막 갱신 — 5초 미만 「방금」, 그 뒤 5초 단위로 내린 「%d초 전」, 30초 넘으면 「30초 넘게 받지 못함」. */
    fun updated(ageMs: Long): String = when {
        WatchFreshness.isStale(ageMs) -> CarTexts.BIO_STALE
        ageMs < UPDATED_STEP_MS -> CarTexts.BIO_JUST_NOW
        else -> CarTexts.BIO_SEC_AGO.format((ageMs / UPDATED_STEP_MS) * (UPDATED_STEP_MS / 1000))
    }

    /** 주행 중 원인 문구로 접는 상태 기계 워치 안내와 그 원인. */
    private val WATCH_NOTICES = mapOf(
        Phrase.SCREEN_CHECK_WEAR.ko to WatchLink.NOT_WORN, Phrase.SCREEN_WATCH_NO_SIGNAL.ko to WatchLink.NO_SIGNAL,
    )

    /** 주행 중 상태 문구로 접는 응답 뒤 안내 — 정차 중에는 그대로 둔다(car-spec §7-1 · G5). */
    private val REST_NOTICES = mapOf(Phrase.SCREEN_S1R.ko to CarTexts.DRIVE_S1R, Phrase.SCREEN_S2.ko to CarTexts.DRIVE_S2)

    /** 이미 워치가 원인인 상태 한 줄 — 꺼짐 원인(주행 열)과 상태 기계 안내(신호 없음 · 착용 확인). */
    private val WATCH_CAUSE_LINES = setOf(
        CarTexts.DRIVE_OFF_WATCH_WAIT, CarTexts.DRIVE_OFF_WEAR, Phrase.SCREEN_WATCH_NO_SIGNAL.ko, Phrase.SCREEN_CHECK_WEAR.ko,
    )

    /**
     * 주행 중 워치 한 줄(car-spec 7-2). 상태 한 줄이 이미 워치 원인이면 같은 말을 두 번 읽지 않게 null.
     * 맥박 신호 상태는 넣지 않는다 — 운전자가 주행 중 건강 상태를 해석하지 않게.
     */
    fun driveBioLine(bio: BioView, statusLine: String): String? = when {
        statusLine in WATCH_CAUSE_LINES -> null
        bio.noSignal -> CarTexts.DRIVE_BIO_NO_SIGNAL
        !bio.worn -> CarTexts.DRIVE_BIO_NOT_WORN
        bio.hr != null -> CarTexts.DRIVE_BIO_HR.format(bio.hr)
        else -> CarTexts.DRIVE_BIO_WORN
    }

    /**
     * 테스트 입구(car-spec 5절). 조건은 서버 연결 → 휴대폰 연결 → S0(시작만) 순서이고, 첫 거짓의 사유를 아래 한 줄에 둔다.
     * 테스트 중이면 「테스트 멈추기」다. 보낸 요청([pending])이 아직 결과(흐름 test 가 바뀜)를 못 봤으면
     * 15초 동안 「기다리는 중」(흐림), 그 뒤에는 버튼을 되살리고 「휴대폰이 답하지 않습니다」를 둔다.
     * 휴대폰 단독(휴대폰 안) 시험이 도는 중([phoneLocalTest], 표시 전용)이면 흐름은 실제 그대로이고 시작 버튼은 흐림 +
     * 「휴대폰에서 테스트 중 · 차 화면은 실제 상태입니다」 — 휴대폰은 그동안 새 시작 요청을 받지 않는다(2026-11-07). 정차 화면에만 있다(G5).
     */
    fun testEntry(
        linkOk: Boolean, phone: Boolean, state: FlowState, test: Boolean, pending: TestPending?, nowMs: Long, phoneLocalTest: Boolean = false,
    ): TestEntryView {
        val action = if (test) TestAction.STOP else TestAction.START
        val label = if (test) CarTexts.TEST_STOP else CarTexts.TEST_START
        val blocked = when {
            !linkOk -> CarTexts.TEST_NEED_SERVER
            !phone -> CarTexts.TEST_NEED_PHONE
            !test && state != FlowState.S0 -> CarTexts.TEST_NEED_WATCHING
            !test && phoneLocalTest -> CarTexts.TEST_PHONE_LOCAL
            else -> null
        }
        if (blocked != null) return TestEntryView(label, false, blocked, action)
        // 결과를 아직 못 본 같은 동작의 요청만 기다린다(시작 요청 뒤 테스트가 켜졌으면 지금 동작은 멈춤이라 다르다)
        val waiting = pending?.takeIf { it.action == action } ?: return TestEntryView(label, true, null, action)
        return if (nowMs - waiting.atMs < TEST_ANSWER_MS) TestEntryView(label, false, CarTexts.TEST_WAITING, action)
        else TestEntryView(label, true, CarTexts.TEST_NO_ANSWER, action)
    }

    /**
     * 상태 화면. [setupOk](NO_SETUP 제한 없음 + 정차)가 아니면 주행 열 한 줄과 워치 한 줄만 — 연결 줄 · 칸 · 버튼 · 번호를 숨긴다(G5).
     * 서버가 끊긴 줄은 원인이 서버라고 쓴다(휴대폰만 끊긴 경우는 연결 줄이 맡는다, car-spec 7-1).
     * 상태 기계 안내([notice], [noticeLine])는 서버 끊김 다음 순서로 상태 한 줄 자리에 둔다 — 정차 · 주행 같은 자리, 버튼 없음.
     * 단 주행 중 워치 안내(「워치 착용을 확인하세요」 · 「…워치 연결을 확인하세요」)는 몸 · 휴대폰 조작 요구라 [offLine] 주행 열 원인
     * 문구로 접는다(car-spec §7-1 · G5, 리뷰 중3). 그 안내가 떠 있으면 확인이 돌지 않는 중이라 「꺼짐 ·」이 맞다.
     * 응답 뒤 안내(S1R 「…쉬어 가세요」 · S2 「…다시 출발하세요」)도 주행 중에는 [CarTexts.DRIVE_S1R] · [CarTexts.DRIVE_S2] 로 접는다(2026-11-08).
     */
    fun idle(
        linkOk: Boolean, state: FlowState, powerOn: Boolean, consentsAll: Boolean, links: Links, setupOk: Boolean,
        pairCode: String? = null, bio: BioView = BioView.NO_SIGNAL, test: TestEntryView? = null, via: Via? = null,
        notice: String? = null,
    ): IdleView {
        val driving = !setupOk
        val line = when {
            !linkOk -> if (driving) CarTexts.DRIVE_SERVER_LOST else CarTexts.STATUS_SERVER_LOST
            driving && notice != null && notice in WATCH_NOTICES -> {
                // 연결 줄이 아직 착용이라 해도 안내가 원인을 말한다 — 원인 없는 「확인 중」으로 떨어지지 않게
                val w = if (links.watch == WatchLink.WORN) WATCH_NOTICES.getValue(notice) else links.watch
                offLine(powerOn, consentsAll, w, driving = true)
            }
            // 응답 뒤 안내(「쉬어 가세요」 · 「다시 출발하세요」)도 주행 중에는 행동 요구 없는 상태 문구로(PM 결정 2026-11-08)
            driving && notice != null && notice in REST_NOTICES -> REST_NOTICES.getValue(notice)
            notice != null -> notice
            state == FlowState.OFF -> offLine(powerOn, consentsAll, links.watch, driving)
            else -> CarTexts.STATUS_WATCHING
        }
        if (driving) return IdleView(line, emptyList(), newCode = false, driveBio = driveBioLine(bio, line))
        return IdleView(
            line, linkRows(links, via), newCode = pairCode == null, code = pairCode,
            sub = if (!linkOk) CarTexts.STATUS_SERVER_LOST_SUB else null, bio = bio, test = test,
        )
    }
}

/**
 * 주행 중 워치 한 줄을 10초에 한 번만 바꾼다(car-spec 4-3, 화면 쪽 단조 시계). 정차로 돌아오면 [reset] — 다음 주행 첫 호출은 바로 그린다.
 * 한 줄을 지우는 것(null — 상태 한 줄이 워치 원인이 됨)은 같은 말을 두 번 보이지 않게 바로 반영한다.
 */
class BioThrottle(private val periodMs: Long = 10_000L) {
    private var shownAtMs: Long? = null
    private var shown: String? = null

    fun show(nowMs: Long, text: String?): String? {
        val at = shownAtMs
        when {
            text == null -> reset()
            at == null || nowMs - at >= periodMs -> {
                shownAtMs = nowMs
                shown = text
            }
        }
        return shown
    }

    fun reset() {
        shownAtMs = null
        shown = null
    }
}
