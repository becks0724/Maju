// 판정 주인 넘김 규칙(합동 점검 A-4) — 차 재시작 판별 · 차의 받아들임 · 휴대폰의 넘겨주기 · 차 시작 유예를 순수 함수로
package com.example.guardian.shared.flow

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.protocol.FlowStatus

/**
 * 주인 = 「차 요청이 10초 안에 닿음 + 차가 스스로 주인이라고 실어 보냄(`flow.owner == CAR`)」([Ownership.decide] 세 인자판).
 * 휴대폰은 마지막 실제 사건 흐름을 따로 쥐고, 차가 내려놓거나 · 경보 상태이던 차 흐름의 사건 id 가 사라지거나 바뀌거나([carIncidentLost]) · 401 이면
 * 넘겨받는다. 차는 사건 id 가 없으면 휴대폰의 사건을 받아들이고([carMayAccept]), 휴대폰은 같은 id 를 차가 실을 때까지 넘겨주지 않는다
 * ([phoneMayRelease]). 차에 사건을 저장하지 않는다. 다시 뜬 차는 휴대폰 흐름을 한 번 볼 때까지(상한 6초) 나쁜 손목 판정을 넣지 않는다
 * ([carWristAllowed], app-qa 3차 실행 — 유예가 없으면 3초 재시작에서 두 주인이 서로 다른 사건 id 를 쥠).
 */
object Handover {
    /** 다시 뜬 차가 휴대폰 흐름을 기다리는 상한. */
    const val CAR_START_GRACE_MS = 6_000L

    /** 사건 진행 상태(경보 · 사고). 응답 뒤(S1R · S2)는 사건이 끝난 것으로 본다. */
    private val ALERT_STATES = setOf(FlowState.S1, FlowState.AP, FlowState.S3, FlowState.S4, FlowState.S5)

    /**
     * 차 재시작(또는 사건 소실) 판별 — 직전 차 흐름 [prev] 가 실제 경보 · 사고 상태였는데 지금 흐름 [cur] 의 사건 id 가 사라지거나 바뀜.
     * [cur] 가 null(흐름 모름 · 끊김)이면 판별하지 않는다(끊김은 신선도 규칙이 맡는다). 정상 응답(S1R · S2 · S0 으로 감)은 사건 id 가 남아 거짓 판별이 없다.
     * 지금 흐름이 사고(S5)면 판별하지 않는다 — 경보 중 에어백은 사건 id 를 사고 id 로 바꿔 이어 간다.
     */
    fun carIncidentLost(prev: FlowStatus?, cur: FlowStatus?): Boolean {
        if (prev == null || cur == null || prev.test) return false
        val id = prev.snapshot.facts.eventId ?: return false
        if (prev.state !in ALERT_STATES) return false
        // 경보 중 에어백 — 차가 사고(S5)로 이어 가며 사건 id 를 accident-… 로 바꾼다. 차가 사고를 맡고 있으니 소실이 아니다(app-phone 지적)
        if (cur.state == FlowState.S5) return false
        return cur.snapshot.facts.eventId != id
    }

    /**
     * 차가 휴대폰의 사건을 받아들인다 — 내 흐름 [mine] 에 진행 중 사건이 없고(OFF · S0, 사건 id 없음) 신선한 휴대폰 흐름 [phone] 이
     * `owner = PHONE` 인 실제 진행 사건일 때. [phoneFresh] 는 휴대폰 흐름이 이번 응답에서 온 신선한 값인지(A-4 조건 — 묵은 흐름으로 켬 · 동의도 받지 않음).
     */
    fun carMayAccept(mine: FlowSnapshot, phone: FlowStatus?, phoneFresh: Boolean): Boolean {
        if (phone == null || !phoneFresh) return false
        if (mine.state != FlowState.OFF && mine.state != FlowState.S0) return false
        if (mine.incidentActive) return false
        val s = phone.snapshot
        return phone.owner == Owner.PHONE && !phone.test && s.incidentActive && s.facts.eventId != null
    }

    /** 휴대폰이 주인을 넘겨줘도 되는가 — 내 사건이 없거나, 차가 같은 사건 id 를 `owner = CAR` 로 싣고 있을 때만(사건 임대). */
    fun phoneMayRelease(mine: FlowSnapshot, car: FlowStatus?): Boolean {
        val id = mine.facts.eventId.takeIf { mine.incidentActive } ?: return true
        return car != null && car.owner == Owner.CAR && car.snapshot.facts.eventId == id
    }

    /** 다시 뜬 차가 **나쁜** 손목 판정을 상태 기계에 넣어도 되는가 — 신선한 휴대폰 흐름을 한 번 봤거나 시작 뒤 [CAR_START_GRACE_MS] 가 지남. */
    fun carWristAllowed(startedAtMs: Long, nowMs: Long, sawFreshPhoneFlow: Boolean): Boolean =
        sawFreshPhoneFlow || nowMs - startedAtMs >= CAR_START_GRACE_MS

    /**
     * 판정 하나를 넣어도 되는가(PM 결정 16, 2026-11-14) — 유예 중에도 나쁘지 않은 판정(NORMAL · CHECK_WEAR)은 넣고 나쁜 판정(PULSE_WEAK · PULSE_ABSENT)만 막는다.
     * 유예가 손목 판정을 모두 막으면 차가 S0 에 못 들어가 OFF 가 에어백 전이를 소비했다(app-qa 차 세션 실행). 나쁜 판정만 막아도 새 사건(새 id)은 열리지 않는다.
     */
    fun carWristAllowed(startedAtMs: Long, nowMs: Long, sawFreshPhoneFlow: Boolean, verdict: WristVerdict): Boolean =
        (verdict != WristVerdict.PULSE_WEAK && verdict != WristVerdict.PULSE_ABSENT) || carWristAllowed(startedAtMs, nowMs, sawFreshPhoneFlow)
}
