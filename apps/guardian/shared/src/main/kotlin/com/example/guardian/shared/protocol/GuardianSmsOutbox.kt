// 차의 보호자 문자 보낼 함(합동 점검 A-2) — 문자 지시를 휴대폰의 확인(smsDone)이 올 때까지 같은 지시 id 로 다시 싣는 순수 상태
package com.example.guardian.shared.protocol

/**
 * 차가 낸 `SEND_GUARDIAN_SMS`(실제만, 테스트 제외)를 휴대폰이 확인할 때까지 sync 요청마다 **같은 지시 id 로** 다시 싣는다.
 * 중계 `seen_ids` 가 ttl 안 중복을, 휴대폰 사건 id 중복 막기가 그 뒤 중복을 막는다(be-qa 3차).
 *
 * 규칙.
 * - **사건 문자(EVENT) 보류** — 넣은 뒤에 만든 요청의 응답을 한 번 처리할 때까지([onResponseHandled]), 상한 [HOLD_MS] 4초 싣지 않는다.
 *   그사이 같은 사건의 취소(CANCEL)가 들어오면 둘 다 버린다(만료 직전 응답 — 사건 문자 → 취소 두 통을 막음). 사고 · 관찰 문자는 보류하지 않는다.
 * - 한 번이라도 실린 사건 문자의 취소는 버리지 않고 같이 싣는다. **한 요청 안에서는 사건 문자가 취소보다 앞**이다(휴대폰이 취소를 먼저 처리하면
 *   「사건 문자 없음」으로 버려진다).
 * - 휴대폰 `flow.smsDone` 에 같은 (사건 id, 종류)가 SENT · NOT_SENT 로 보이면 지운다([onSmsDone]). PENDING 은 지우지 않는다.
 * - 주인을 내려놓아도 · 응답이 유실돼도 · 401 뒤 다시 짝지어도 남긴다(앱은 이 상태를 지우지 않는다). 상한은 **사건이 끝난 뒤 [KEEP_AFTER_END_MS] 10분**.
 *
 * 앱이 부르는 순서 — 상태 기계 지시 → [add] / 요청 만들기 → [buildRequest] / 응답 처리(흐름 · 지시 반영과 그로 나온 지시의 [add]까지) 뒤 [onResponseHandled] ·
 * [onSmsDone]. 메모리 상태다 — 차가 다시 뜨면 사라지고, 그 빈자리는 휴대폰 대조(A-3)가 메운다.
 */
data class GuardianSmsOutbox(
    val items: List<Item> = emptyList(),
    /** 마지막으로 만든 요청 번호(1부터). */
    val lastRequestNo: Long = 0,
    /** 응답을 처리한 가장 큰 요청 번호. */
    val lastHandledRequestNo: Long = 0,
) {
    data class Item(
        val command: Command,
        val eventId: String,
        val kind: GuardianSmsWireKind,
        val addedAtMs: Long,
        /** 보류 기준 — 이 번호보다 큰 요청의 응답을 처리하면 풀린다. null = 보류 없음. */
        val heldAfterRequestNo: Long?,
        val carried: Boolean = false,
        /** 이 사건이 끝난 것을 본 시각(차 단조). null = 진행 중. */
        val endedAtMs: Long? = null,
    )

    /** 상태 기계가 낸 지시 가운데 실제 보호자 문자만 넣는다(그 밖의 지시 · 테스트 문자는 무시 — 앱이 지금처럼 따로 싣는다). */
    fun add(commands: List<Command>, nowMs: Long): GuardianSmsOutbox {
        var list = items
        for (c in commands) {
            if (c.type != CommandType.SEND_GUARDIAN_SMS || c.test || list.any { it.command.id == c.id }) continue
            val p = parseOrNull(c.payload) { Payload.fromJson(c.type, it.map) as Payload.SendGuardianSms } ?: continue
            if (p.test) continue
            if (p.kind == GuardianSmsWireKind.CANCEL) {
                val pending = list.firstOrNull { it.eventId == p.eventId && it.kind == GuardianSmsWireKind.EVENT && !it.carried }
                if (pending != null) { list = list - pending; continue } // 실리기 전 취소 — 둘 다 버린다
            }
            val hold = if (p.kind == GuardianSmsWireKind.EVENT) lastRequestNo else null
            list = list + Item(c, p.eventId, p.kind, nowMs, hold)
        }
        return copy(items = list)
    }

    /**
     * 요청 하나를 만든다 — 실을 문자 지시(사건 문자가 앞, 같은 지시 id)와 바뀐 상태. [activeEventId] 는 지금 상태 기계의 진행 중 사건 id
     * (사건이 없으면 null) — 다른 사건 id 의 항목은 이때부터 「끝남」으로 세어 10분 뒤 버린다.
     */
    fun buildRequest(nowMs: Long, activeEventId: String?): Pair<GuardianSmsOutbox, List<Command>> {
        val no = lastRequestNo + 1
        val live = items
            .map { if (it.endedAtMs == null && it.eventId != activeEventId) it.copy(endedAtMs = nowMs) else it }
            .filter { it.endedAtMs == null || nowMs - it.endedAtMs <= KEEP_AFTER_END_MS }
        val ready = live.filter { !held(it, nowMs) }
        val ordered = ready.filter { it.kind != GuardianSmsWireKind.CANCEL } + ready.filter { it.kind == GuardianSmsWireKind.CANCEL }
        val readyIds = ready.map { it.command.id }.toSet()
        val next = live.map { if (it.command.id in readyIds) it.copy(carried = true) else it }
        return copy(items = next, lastRequestNo = no) to ordered.map { it.command }
    }

    /** 요청 [requestNo] 의 응답을 처리했다(그 응답으로 나온 지시를 [add] 한 뒤 부른다). */
    fun onResponseHandled(requestNo: Long): GuardianSmsOutbox = copy(lastHandledRequestNo = maxOf(lastHandledRequestNo, requestNo))

    /** 휴대폰이 확인한 결과(SENT · NOT_SENT)의 항목을 지운다. null(휴대폰이 싣지 않음 · 옛 판)이면 그대로. */
    fun onSmsDone(done: List<SmsDone>?): GuardianSmsOutbox {
        if (done == null) return this
        val settled = done.filter { it.done != SmsDoneState.PENDING }.map { it.eventId to it.kind }.toSet()
        return copy(items = items.filterNot { (it.eventId to it.kind) in settled })
    }

    /** 이 사건의 문자가 휴대폰에서 확인됐는가(화면 줄 「보호자에게 알림 보냄」용) — 보낼 함에 없고 [done] 에 SENT 로 있음. */
    fun confirmed(eventId: String, done: List<SmsDone>?): Boolean =
        done?.any { it.eventId == eventId && it.kind != GuardianSmsWireKind.CANCEL && it.done == SmsDoneState.SENT } == true

    private fun held(it: Item, nowMs: Long): Boolean {
        val after = it.heldAfterRequestNo ?: return false
        return lastHandledRequestNo <= after && nowMs - it.addedAtMs < HOLD_MS
    }

    companion object {
        /** 사건 문자 보류 상한 — 만료 직전 응답이 휴대폰에 닿는 창(한 방향 지연 1초에서 최대 2.75초, be-qa 실행)을 덮는다. */
        const val HOLD_MS = 4_000L

        /** 사건이 끝난 뒤 남기는 기간(이하). 휴대폰은 10분 넘게 묵은 사건 문자를 보내지 않는다([PhoneSmsGuard]). */
        const val KEEP_AFTER_END_MS = 10 * 60 * 1000L
    }
}
