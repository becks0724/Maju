// 동의 철회 뒤 중계에서 못 지운 워치 연결을 다시 끝낸다 — 성공 · 401 이면 끝낼 토큰을 지우고, 24시간(짝 만료)이 지나면 부르지 않고 지운다
package com.example.guardian.watch.service

import android.util.Log
import com.example.guardian.watch.net.RelayResult

/**
 * 보안 감사 11-08 하2 · 계약 §3.2. 철회 때 `pair/end` 가 실패(네트워크 · 5xx · 시간 초과)하면 그 워치 토큰만 [PairStore.endPending] 에
 * 남기고, 앱 실행 · 감시 시작 · 네트워크 복구 때 이 함수로 다시 부른다. 지금 짝의 토큰은 건드리지 않는다.
 * 로그에는 HTTP 상태 코드만 남긴다(토큰 · 응답 본문 없음).
 */
class PairEndRetry(
    private val store: PairStore,
    private val end: (String) -> RelayResult<Unit>,
    private val nowWall: () -> Long,
) {
    /** 남은 끝낼 토큰을 하나씩 한 번 시도한다. 아직 남았으면 true(다음 기회에 다시). */
    fun runOnce(): Boolean {
        for (p in store.endPending) {
            if (nowWall() - p.savedAtWallMs >= PAIR_TTL_MS) {
                store.removeEndPending(p.token)
                Log.i(TAG, "끝낼 토큰 — 24시간 지나 시도 없이 지움")
                continue
            }
            when (val r = end(p.token)) {
                is RelayResult.Ok -> { store.removeEndPending(p.token); Log.i(TAG, "끝낼 토큰 — HTTP 200 지움") }
                RelayResult.Unauthorized -> { store.removeEndPending(p.token); Log.i(TAG, "끝낼 토큰 — HTTP 401 지움") }
                RelayResult.InvalidCode -> Log.i(TAG, "끝낼 토큰 — HTTP 404 남김")
                is RelayResult.Failed -> Log.i(TAG, "끝낼 토큰 — ${status(r.reason)} 남김")
            }
        }
        return store.endPending.isNotEmpty()
    }

    /** 실패 이유에서 HTTP 상태 코드만 꺼낸다. 연결 실패 · 응답 형식 오류는 코드 없이 「연결 실패」. */
    private fun status(reason: String): String =
        Regex("""^HTTP (\d{3})$""").find(reason)?.let { "HTTP ${it.groupValues[1]}" } ?: "연결 실패"

    companion object {
        private const val TAG = "GuardianWatch"

        /** 짝 수명(계약 §3.1 — 24시간). 저장한 지 이만큼 지나면 중계에서도 이미 사라졌으므로 부르지 않는다. */
        const val PAIR_TTL_MS = 24 * 60 * 60 * 1_000L
    }
}
