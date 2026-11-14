// 응답 뒤 연결 증거가 10초 없으면 경보 출력(진동 · 소리 · 최대 음량 · 최대 밝기)만 멈출지 정한다(합동 점검 C-3) — 화면 · 버튼은 남는다
package com.example.guardian.watch.core

/**
 * 워치 버튼 · 손목으로 응답했는데 그 뒤 [windowMs] 동안 연결 증거(중계 sync 성공 · Data Layer 보내기 성공)가 한 번도 없으면,
 * 응답이 주인에게 닿지 않아 ALERT_STOP 도 오지 않을 수 있다. 그때 끝없는 출력만 멈춘다. 보조 장치다 — 「증거는 있는데
 * 주인이 경보를 모름」은 이것으로 닫히지 않는다(C-2 · 계약 개정 몫).
 * - 응답하지 않은 경보는 멈추지 않는다.
 * - 증거는 응답 뒤에 보낸 요청의 성공만 센다([onEvidence] 의 sentAtMs) — 응답 전에 떠난 요청은 응답을 싣지 않았다.
 * - 새 경보 시작 · 정지가 오면 [reset] — 시작이면 출력이 다시 걸린다(부른 쪽).
 * 시각은 단조 ms. 여러 스레드(tick · sync · Data Layer 콜백)에서 부르므로 메서드마다 동기화한다.
 */
class QuietAfterResponse(private val windowMs: Long = WINDOW_MS) {
    private var respondedAtMs: Long? = null
    private var evidence = false

    /** 출력을 멈춘 상태인가 — 경보 화면이 최대 밝기를 풀 때 본다. */
    @get:Synchronized
    var muted = false
        private set

    /** 응답(누를 때마다) — 그 누름부터 다시 잰다. 이미 멈췄으면 멈춘 채다. */
    @Synchronized
    fun onRespond(nowMs: Long) {
        respondedAtMs = nowMs
        evidence = false
    }

    /** 연결 증거 — [sentAtMs] 는 그 요청을 보내기 시작한 단조 시각. */
    @Synchronized
    fun onEvidence(sentAtMs: Long) {
        val r = respondedAtMs ?: return
        if (sentAtMs >= r) evidence = true
    }

    /** 새 경보 시작 · 정지 · 감시 끝 — 처음으로. */
    @Synchronized
    fun reset() {
        respondedAtMs = null
        evidence = false
        muted = false
    }

    /** 지금 출력을 멈춰야 하면 true 를 한 번만 돌려주고 멈춘 상태가 된다. */
    @Synchronized
    fun muteDue(nowMs: Long): Boolean {
        val r = respondedAtMs ?: return false
        if (muted || evidence || nowMs - r < windowMs) return false
        muted = true
        return true
    }

    companion object {
        /** 응답 뒤 기다리는 연결 증거 시간(합동 점검 C-3 합의값). 워치 sync 2초 · Data Layer 1초 주기의 몇 배라 정상 연결이면 넘지 않는다. */
        const val WINDOW_MS = 10_000L
    }
}
