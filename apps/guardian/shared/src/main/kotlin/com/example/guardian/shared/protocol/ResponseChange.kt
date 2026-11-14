// 응답 시각(워치 · 휴대폰 화면 responseAt)의 증가를 상태 기계 응답 입력으로 바꾸는 추적기 — 차(또는 주인)가 sync 마다 부른다
package com.example.guardian.shared.protocol

import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.ResponseSource

/**
 * `responseAt` 은 보낸 기기의 벽시계이고 **증가 표시 전용**이다(계약 §2 · §4.3) — 값을 다른 시계와 빼지 않고, 이전 값보다 커졌는지만 본다.
 *
 * - **처음 본 값은 기준으로만** 둔다 — 앱을 다시 켰거나 짝을 다시 맺었을 때 옛 응답이 지금 경보를 끄지 않게.
 *   처음 본 값이 null(아직 응답 없음)이면 그 뒤 첫 값은 새 응답이다.
 * - 기준보다 [MARGIN_MS] **넘게 커질 때만** 응답 하나([source], 시각은 받는 쪽 단조 [nowLocalMs]).
 *   같거나 · 작아지거나(벽시계가 뒤로 감) · 여유 안의 변화(변환 흔들림 · 벽시계 보정)는 응답이 아니다.
 * - 기준은 늘 마지막으로 본 null 아닌 값으로 옮긴다 — 작은 흔들림이 쌓여 여유를 넘지 않게, 벽시계가 뒤로 간 뒤의 새 버튼도 잡히게.
 *   null 은 기준을 바꾸지 않는다.
 *
 * 쓰는 곳 — 휴대폰 화면 응답은 `PhonePeer.responseAt` 에 [ResponseSource.PHONE_SCREEN](정지 때만 유효 — 상태 기계가 지킨다, G5),
 * 워치 버튼은 고른 워치 값 `Watch.responseAt` 에 [ResponseSource.WATCH_BUTTON]. 상대 값이 없는 sync(peer null)에는 부르지 않는다.
 */
class ResponseChange(private val source: ResponseSource, private val marginMs: Long = MARGIN_MS) {
    private var seen = false
    private var base: Long? = null

    fun onValue(responseAt: Long?, nowLocalMs: Long): FlowInput.Response? {
        val first = !seen
        seen = true
        if (responseAt == null) return null
        val prev = base
        base = responseAt
        if (first) return null
        return if (prev == null || responseAt - prev > marginMs) FlowInput.Response(source, nowLocalMs) else null
    }

    companion object {
        /**
         * 1초. 근거 — 워치가 단조 → 벽시계를 sync 마다 다시 계산해 ±1ms 흔들린 실측(app-qa 2026-10-30)과 벽시계 소폭 보정을 넘기고,
         * 사람이 버튼을 다시 누르는 간격(수 초)보다는 충분히 짧다. sync 주기 2초 안에 두 번 누르면 한 번으로 본다.
         */
        const val MARGIN_MS = 1_000L
    }
}
