// 휴대폰의 차 문자 대조(합동 점검 A-3)와 휴대폰 문자 방어 둘 — 차 흐름과 휴대폰 문자 기록을 사건 id 로 맞춰 「보낼 것」 · 「취소할 것」을 내는 순수 함수
package com.example.guardian.shared.protocol

import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState

/**
 * 차가 「문자를 냈다」(흐름의 `incidentSmsSent` · `accidentSent`)는데 휴대폰 기록에 없으면 휴대폰이 스스로 보내고,
 * 휴대폰이 사건 문자를 보냈는데 차 흐름에 응답 시각이 생겼으면 취소를 보낸다.
 *
 * - 보냄 — 차 흐름이 실제(test 아님)이고, S3 · S4(사고는 S5)이고, 응답 시각이 없고(사고는 응답 시각을 남기지 않아 보지 않음, app-qa),
 *   같은 사건 id 의 그 종류 기록 · 취소 기록이 없을 때. 차의 보낼 함(A-2 보류 4초 + 창 2.75초)이 먼저 닿을 여유로 [WAIT_MS] 10초 기다린다.
 *   넘겨받을 때([immediate])는 기다리지 않는다.
 * - 취소 — 같은 사건 id 의 사건 문자를 보낸 기록이 있고 차 흐름에 응답 시각이 있고 같은 id 의 취소 기록이 없으면 바로.
 * - 이 길로 낸 문자도 원격 상한 · 문자열 거르기 · [PhoneSmsGuard] · [GuardianSmsDedup] 를 그대로 탄다(앱 몫).
 *
 * [car] 는 휴대폰 시계로 되돌린 차 흐름 스냅숏(null = 모름 — 아무것도 내지 않고 기다림을 비운다).
 * [waiting] 은 앞 호출이 돌려준 기다림(키 → 처음 본 휴대폰 단조 시각), 앱이 메모리에 쥔다.
 */
object CarSmsReconcile {
    const val WAIT_MS = 10_000L

    data class Action(val kind: GuardianSmsWireKind, val eventId: String)
    data class Result(val actions: List<Action>, val waiting: Map<String, Long>)

    fun step(
        car: FlowSnapshot?,
        records: List<GuardianSmsDedup.Record>,
        waiting: Map<String, Long>,
        nowLocalMs: Long,
        immediate: Boolean,
    ): Result {
        val id = car?.facts?.eventId
        if (car == null || car.test || id == null) return Result(emptyList(), emptyMap())
        fun has(kind: GuardianSmsWireKind, sentOnly: Boolean) =
            records.any { !it.test && it.eventId == id && it.kind == kind && (it.sent || !sentOnly) }
        val cancelled = has(GuardianSmsWireKind.CANCEL, sentOnly = false)
        val responded = car.facts.responseAtMs != null

        val want: GuardianSmsWireKind? = when {
            car.state == FlowState.S5 && car.accidentSent && !has(GuardianSmsWireKind.ACCIDENT, sentOnly = true) -> GuardianSmsWireKind.ACCIDENT
            (car.state == FlowState.S3 || car.state == FlowState.S4) && car.incidentSmsSent && !responded && !cancelled &&
                !has(GuardianSmsWireKind.EVENT, sentOnly = true) -> GuardianSmsWireKind.EVENT
            else -> null
        }
        val actions = ArrayList<Action>()
        val nextWaiting = HashMap<String, Long>()
        if (want != null) {
            val key = "${want.name}|$id"
            val since = waiting[key] ?: nowLocalMs
            if (immediate || nowLocalMs - since >= WAIT_MS) actions += Action(want, id) else nextWaiting[key] = since
        }
        if (responded && has(GuardianSmsWireKind.EVENT, sentOnly = true) && !cancelled) actions += Action(GuardianSmsWireKind.CANCEL, id)
        return Result(actions, nextWaiting)
    }
}

/**
 * 휴대폰 문자 방어 둘(sec-audit · app-phone 3차, 위임 결정 4) — 원격 지시(A-2 다시 싣기)와 대조(A-3) 둘 다에 건다. 휴대폰이 스스로 연 사건에는 걸지 않는다.
 * 1. **묵은 사건 문자** — 사건 id 의 중계 시각이 [STALE_MS] 10분 넘게 지난 사건 문자(EVENT · ACCIDENT · OBSERVE)는 보내지 않는다. 취소는 막지 않는다.
 * 2. **참여 전 사건** — 사건 id 시각이 내 참여 시각보다 앞이고 내가 본 적 없는 사건(내 기록 · 이 짝에서 본 흐름)의 사건 문자는 보내지 않는다.
 *    시각을 읽을 수 없는 id(`event-L…` 로컬 표시 — 차가 중계 시각을 받기 전에 연 사건)는 참여 전 사건으로 다룬다. 단 **그 id 가 지금 신선한 차 흐름의
 *    진행 중 사건이면 허용**한다 — 시동 뒤 데이터가 없던 차의 사고 문자를 막지 않게(app-phone · app-qa 지적). 다시 짝짓기 뒤 보낼 함에 남은 옛 사건은
 *    차의 진행 중 사건이 아니라 여전히 막힌다. 기준은 「참여 시각」이다 — 참여 직후 처음 본 차 흐름을
 *    「본 사건」으로 치면 방어가 비워진다(sec-audit).
 * 남의 보호자에게 남의 위치가 가는 오발송은 되돌릴 수 없고, 막히는 정상 경우는 두 고장이 겹칠 때뿐이다(위임 결정 4).
 */
object PhoneSmsGuard {
    const val STALE_MS = 10 * 60 * 1000L

    enum class Verdict { ALLOW, STALE, BEFORE_JOIN }

    /** `event-{중계 ms}` · `accident-{중계 ms}` 의 시각. 로컬 표시(`…-L…`)나 다른 꼴이면 null. */
    fun eventServerMs(eventId: String): Long? {
        val dash = eventId.indexOf('-')
        if (dash < 0) return null
        val tail = eventId.substring(dash + 1)
        if (tail.startsWith("L")) return null
        return tail.toLongOrNull()
    }

    /**
     * [nowServerMs] · [joinedAtServerMs] 는 중계 시각(모르면 null). [seenEventIds] 는 내 문자 기록 가운데 **실제로 보낸 기록(sent)의** 사건 id 와
     * **이번 참여 전에** 본 흐름의 사건 id(다시 짝지은 같은 휴대폰). 「보내지 않음」 기록(이 방어 · 중복으로 막혀 남긴 것)은 넣지 않는다 — 넣으면 막힌 뒤
     * 대조 · 같은 id 의 다른 문자 · 새 지시 id 로 나간다(PM 결정 19, sec-audit 실행). 이번 참여 뒤 처음 본 흐름의 id 도 넣지 않는다.
     * 시각을 못 읽는 id 는 [carFlowStartServerMs] 를 알아도 10분 묵음 판정(STALE)을 하지 않는다 — 망이 늦게 돌아온 진행 중 사고 문자를 막지 않게(sec-audit 안).
     * [carFlowStartServerMs] 는 휴대폰이 중계 시계(SERVER) 차 흐름에서 본 그 id 의 시작 시각(사고면 accidentAt, 아니면 firstAlertAt) — 시각을 읽을 수
     * 없는 id 는 이 시각으로 참여 전을 가린다(sec-audit — 차 흐름은 요청마다 지금 보정값으로 다시 만들어져 id 가 L 꼴이어도 시각은 중계 시각이다).
     * [liveCarEventId] 는 지금 신선한 차 흐름의 진행 중 사건 id(없으면 null) — 시작 시각도 모를 때만 쓴다.
     * 참여 시각을 모르면(옛 설치) 2 는 건너뛴다.
     */
    fun check(
        kind: GuardianSmsWireKind,
        eventId: String,
        nowServerMs: Long?,
        joinedAtServerMs: Long?,
        seenEventIds: Set<String>,
        liveCarEventId: String? = null,
        carFlowStartServerMs: Long? = null,
    ): Verdict {
        if (kind == GuardianSmsWireKind.CANCEL) return Verdict.ALLOW
        val t = eventServerMs(eventId)
        if (t != null && nowServerMs != null && nowServerMs - t > STALE_MS) return Verdict.STALE
        if (joinedAtServerMs == null || eventId in seenEventIds) return Verdict.ALLOW
        val start = t ?: carFlowStartServerMs ?: return if (eventId == liveCarEventId) Verdict.ALLOW else Verdict.BEFORE_JOIN
        return if (start < joinedAtServerMs) Verdict.BEFORE_JOIN else Verdict.ALLOW
    }
}
