// 중계가 응답하지 않을 때 2초 주기가 늘어지지 않게 — 네트워크 오류가 이어지는 동안은 주기당 sync 한 번 · 짧은 연결 제한, 성공하면 원래대로(코드 리뷰 중 6)
package com.example.guardian.phone.net

/** 엔진 스레드에서만 쓴다. */
class SyncPacing {
    /** 직전 sync 가 네트워크 오류(연결 실패 · 시간 초과 · 응답 형식 틀림)였다. */
    var failing = false
        private set

    /** 이번 sync 에 쓸 연결 제한 시간(읽기 제한은 늘 [RelayClient.TIMEOUT_MS]). */
    val connectTimeoutMs: Int get() = if (failing) RelayClient.FAILING_CONNECT_TIMEOUT_MS else RelayClient.TIMEOUT_MS

    /** sync 결과를 적는다. null 은 보내지 않은 것(짝 없음 · 중계 주소 없음)이라 상태를 바꾸지 않는다. */
    fun onResult(r: RelayResult<*>?) {
        if (r != null) failing = r is RelayResult.NetworkError
    }

    /**
     * 한 주기의 sync 부분 — sync → [tick] → (보낼 것이 있고 실패 중이 아니면) sync → [tick].
     * [sync] 는 연결 제한 시간을 받아 결과를 돌려준다. 이번 주기에 부른 sync 횟수를 돌려준다.
     */
    fun cycle(sync: (connectTimeoutMs: Int) -> RelayResult<*>?, tick: () -> Unit, hasOutgoing: () -> Boolean): Int {
        onResult(sync(connectTimeoutMs))
        tick()
        if (failing || !hasOutgoing()) return 1
        onResult(sync(connectTimeoutMs))
        tick()
        return 2
    }
}
