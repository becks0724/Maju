// 보호자 문자 전달 확인(합동 점검 A-1) — 휴대폰이 흐름에 싣는 「사건별 문자 결과」와 싣는 범위 · 코덱
package com.example.guardian.shared.protocol

/** 휴대폰 문자 기록에서 본 결과. 확인 못 함(보냄 시도 · 결과 모름)은 다시 보내지 않는 정책과 맞춰 SENT 로 싣는다(app-phone 3차). */
enum class SmsDoneState { SENT, NOT_SENT, PENDING }

/**
 * 휴대폰 `flow.smsDone` 한 줄 — 사건 id · 문자 종류 · 결과만 싣는다(번호 · 본문 · 위치 없음, G2).
 * 확인의 원본은 휴대폰 문자 기록이다. 중계 ack 는 확인이 아니다(건너뛴 경우에도 ack 가 나감).
 */
data class SmsDone(val eventId: String, val kind: GuardianSmsWireKind, val done: SmsDoneState) {
    fun toJson() = linkedMapOf<String, Any?>("eventId" to eventId, "kind" to kind.name, "done" to done.name)

    /** 휴대폰이 [select] 에 넘기는 후보 — [settledAtWallMs] 는 사건이 끝난 휴대폰 벽시계 시각(null = 아직 미해결). */
    data class Entry(
        val done: SmsDone,
        val test: Boolean,
        val startedAtWallMs: Long,
        val settledAtWallMs: Long?,
    )

    companion object {
        /** 끝난 사건을 싣는 기간(이하) — 차 보낼 함의 상한(사건이 끝난 뒤 10분)과 같다. */
        const val WINDOW_MS = 10 * 60 * 1000L

        /**
         * 싣는 범위(sec-audit 조건) — 지금 짝에서 생긴(시작 ≥ [pairJoinedAtWallMs]) 실제 사건 가운데 미해결이거나 끝난 지 10분 안인 것 전부.
         * 테스트는 싣지 않는다. 순서는 넣은 순서 그대로.
         */
        fun select(entries: List<Entry>, nowWallMs: Long, pairJoinedAtWallMs: Long): List<SmsDone> =
            entries.filter { e ->
                !e.test && e.startedAtWallMs >= pairJoinedAtWallMs &&
                    (e.settledAtWallMs == null || nowWallMs - e.settledAtWallMs <= WINDOW_MS)
            }.map { it.done }

        /** 문자 기록 하나 → 결과(보냄 = SENT, 「보내지 않음」 = NOT_SENT). 결과를 기다리는 중이면 앱이 PENDING 으로 만든다. */
        fun of(r: GuardianSmsDedup.Record): SmsDone =
            SmsDone(r.eventId, r.kind, if (r.sent) SmsDoneState.SENT else SmsDoneState.NOT_SENT)

        fun fromJson(o: JsonObj) = SmsDone(o.str("eventId"), o.enum("kind"), o.enum("done"))

        /** 흐름의 `smsDone` 배열을 읽는다. 배열이 아니면 null(모름), 틀린 항목은 그 항목만 버린다(옛 판 · 다른 빌드). */
        fun listFromJsonOrNull(v: Any?): List<SmsDone>? {
            val arr = v as? List<*> ?: return null
            return arr.mapNotNull { item -> parseOrNull(item, ::fromJson) }
        }
    }
}
