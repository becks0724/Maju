// 워치 경보 맞추기(합동 점검 C-2)와 워치 응답 번호(B-5) — 주인이 자기 흐름과 워치가 실어 보낸 경보 · 응답 번호를 맞추는 순수 함수
package com.example.guardian.shared.protocol

import com.example.guardian.shared.flow.AlertTarget
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowParams
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.ResponseSource

/** 워치 값 `alerting` — 워치가 지금 울리는 경보. [eventId] 는 지시에 실려 온 사건 id(옛 지시면 null), [stage] 는 지시의 상태 이름, [level] 1 진동 · 2 소리. */
data class WatchAlertStatus(val eventId: String?, val stage: FlowState, val level: Int) {
    fun toJson() = linkedMapOf<String, Any?>("eventId" to eventId, "stage" to stage.name, "level" to level)

    companion object {
        fun fromJson(o: JsonObj) = WatchAlertStatus(o.strOrNull("eventId")?.takeIf { it.isNotEmpty() }, o.enum("stage"), o.int("level"))
    }
}

/**
 * 주인(차 또는 휴대폰)이 sync 마다 부른다 — 자기 스냅숏의 경보 여부를 기준으로 워치와 어긋나면 경보 지시를 다시 낸다(C-2).
 * - 내가 경보 중(스냅숏 `alerting` — S5 는 경보를 멈춘 상태라 아님, app-qa)인데 워치가 안 울리거나 · 단계(진동 1 · 소리 2)가 다르거나 · 다른 사건 id 로
 *   울리면 **시작**을 다시 낸다(워치 대상만 — 휴대폰 알람은 다시 내지 않음).
 * - 내가 경보가 아닌데 워치가 울리면 **정지**를 다시 낸다 — 내 사건 id 로 울리면 바로, 모르는 사건 id(옛 지시 · 다른 주인)면 [UNKNOWN_WAIT_MS] 10초 지켜본 뒤.
 *   정지에는 워치가 울리는 사건 id 를 싣는다(워치는 같은 사건 id 의 정지만 듣는다).
 * - 시작 · 정지 각각 [RESEND_MS] 10초에 한 번.
 * - 워치 값이 신선하지 않거나 옛 워치(`alerting` 을 싣지 않음)면 아무것도 내지 않는다.
 * 나온 지시는 상태 기계 지시와 같은 길([DirectiveCommands.toCommands])로 보낸다.
 */
object WatchAlertReconcile {
    const val RESEND_MS = 10_000L
    const val UNKNOWN_WAIT_MS = 10_000L

    /** [unknownEventId] 는 [unknownSinceMs] 부터 지켜보는 모르는 사건 id — 바뀌면 대기를 다시 시작한다(be-qa 3차). */
    data class State(
        val lastStartAtMs: Long? = null, val lastStopAtMs: Long? = null, val unknownSinceMs: Long? = null, val unknownEventId: String? = null,
    )
    data class Result(val directives: List<Directive>, val state: State)

    private val ALERT_STATES = setOf(FlowState.S1, FlowState.AP, FlowState.S3, FlowState.S4)

    fun step(s: FlowSnapshot, watch: Watch?, watchFresh: Boolean, st: State, nowMs: Long, params: FlowParams = FlowParams()): Result {
        if (watch == null || !watchFresh || !watch.alertingReported) return Result(emptyList(), st.copy(unknownSinceMs = null, unknownEventId = null))
        val w = watch.alerting
        val id = s.facts.eventId
        fun due(last: Long?) = last == null || nowMs - last >= RESEND_MS

        if (s.alerting && s.state in ALERT_STATES) {
            val level = if (s.stage == 1) 1 else 2
            val mismatch = w == null || w.level != level || (w.eventId != null && w.eventId != id)
            if (!mismatch || !due(st.lastStartAtMs)) return Result(emptyList(), st.copy(unknownSinceMs = null, unknownEventId = null))
            val targets = if (level == 1) setOf(AlertTarget.WATCH_VIBRATION) else setOf(AlertTarget.WATCH_SOUND)
            val duration = if (level == 1) params.alert1Ms else params.alert2Ms
            val start = Directive.AlertStart(targets, s.state, level, duration, s.test, id)
            return Result(listOf(start), st.copy(lastStartAtMs = nowMs, unknownSinceMs = null, unknownEventId = null))
        }

        if (w == null) return Result(emptyList(), st.copy(unknownSinceMs = null, unknownEventId = null))
        val known = w.eventId != null && w.eventId == id
        val sameUnknown = st.unknownSinceMs != null && st.unknownEventId == w.eventId
        val since = if (known) null else if (sameUnknown) st.unknownSinceMs else nowMs
        val next = st.copy(unknownSinceMs = since, unknownEventId = if (known) null else w.eventId)
        val waited = since == null || nowMs - since >= UNKNOWN_WAIT_MS
        if (!waited || !due(st.lastStopAtMs)) return Result(emptyList(), next)
        return Result(listOf(Directive.AlertStop(s.test, w.eventId)), next.copy(lastStopAtMs = nowMs))
    }
}

/**
 * 워치 응답 → 상태 기계 응답 입력(B-5). 워치 값에 `responseSeq` 가 있으면 번호로, 없으면(옛 워치) 지금의 [ResponseChange](`responseAt` 증가) 규칙.
 * 한 번이라도 번호를 본 뒤에는 `responseAt` 을 보지 않는다(두 길로 같은 누름을 두 번 세지 않게).
 *
 * 번호 규칙 — 처음 본 번호는 기준으로만(앱 재시작 · 다시 짝지음 뒤 옛 응답 무시), 기준(지금까지 본 가장 큰 번호)보다 **커지면** 응답 하나.
 * 작은 번호 하나는 순서가 뒤바뀐 옛 값(중계 · Data Layer 두 길이 번갈아 골라짐)이라 무시하고, **작은 번호가 연달아 [REINSTALL_RUN] 번** 오면
 * 재설치(번호가 처음부터)로 보고 기준을 옮긴다 — 놓침이 거짓 응답(경보 끔)보다 낫다(be-qa 3차 흔들기 — 작아짐마다 기준을 낮추면 거짓 응답 0.39%).
 * 작은 값이 이어지는 동안에는 **그 연속의 첫 값을 임시 기준**으로 두고 임시 기준보다 커지면 응답 하나와 함께 임시 기준을 올린다 — 재설치 직후
 * 0 → 1 → 2 의 누름 둘을 버리지 않게(be-qa · PM 비교 — 거짓 응답은 거의 같고 놓침 30~50% 줄어듦). 3번째에 기준 = 임시 기준.
 * 짝이 바뀌면 앱이 새로 만든다. 워치는 경보가 울리는 동안의 누름만 번호를 올린다(app-wear 와 합의 — 경보 밖 누름이 다음 경보를 끄지 않게).
 * **주인이든 아니든 받은 모든 워치 값으로 부른다**(결과 응답은 주인일 때만 상태 기계에 넣는다). 주인일 때만 부르면 내가 주인이 아닐 때 다른 주인이
 * 처리한 누름(번호 +1)이 다음 내 경보에서 「커짐」으로 읽혀 거짓 응답이 난다(be-qa 3차 흔들기 — 주인일 때만 12,167/338,081, 모두 볼 때 2/348,495).
 * 벽시계와 무관하다(앱2-하6 — 워치 벽시계가 뒤로 가도 첫 누름이 잡힘).
 */
class WatchResponseTracker(private val source: ResponseSource = ResponseSource.WATCH_BUTTON) {
    private val byTime = ResponseChange(source)
    private var seqSeen = false
    private var seqBase: Long? = null
    private var lowRun = 0
    private var lowCand: Long? = null

    fun onValue(responseSeq: Long?, responseAt: Long?, nowLocalMs: Long): FlowInput.Response? {
        if (responseSeq == null) return if (seqSeen) null else byTime.onValue(responseAt, nowLocalMs)
        seqSeen = true
        val base = seqBase
        if (base == null) { seqBase = responseSeq; return null }
        if (responseSeq >= base) {
            lowRun = 0; lowCand = null
            if (responseSeq == base) return null
            seqBase = responseSeq
            return FlowInput.Response(source, nowLocalMs)
        }
        // 기준보다 작음 — 순서가 뒤바뀐 옛 값이거나 재설치. 연속의 첫 값을 임시 기준으로 둔다
        val cand = lowCand
        lowRun++
        var out: FlowInput.Response? = null
        if (cand == null) lowCand = responseSeq
        else if (responseSeq > cand) { lowCand = responseSeq; out = FlowInput.Response(source, nowLocalMs) }
        if (lowRun >= REINSTALL_RUN) { seqBase = lowCand; lowRun = 0; lowCand = null }
        return out
    }

    companion object {
        /** 작은 번호가 이만큼 연달아 오면 재설치로 본다. 두 길이 번갈아 골라져도 옛 값이 3번 연달아 오기는 어렵다(sync 2초 · 신선한 쪽 고름). */
        const val REINSTALL_RUN = 3
    }

    /**
     * 워치 값 하나로. 새 워치(`alerting` 키를 싣는 워치)가 번호를 싣지 않으면 아직 누르지 않은 것 — 0 으로 본다. 그래야 첫 누름(1)이
     * 「처음 본 번호」로 기준에만 잡혀 놓쳐지지 않는다. 옛 워치(`alerting` 키 없음)면 `responseAt` 규칙.
     */
    fun onWatch(w: Watch, nowLocalMs: Long): FlowInput.Response? =
        onValue(w.responseSeq ?: if (w.alertingReported) 0L else null, w.responseAt, nowLocalMs)
}
