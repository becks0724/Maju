// 차 「휴대폰 연결 끊기」 판단 — 문(확정 정지 · 사건 없음) 확인, 기기 쪽 지우기(토큰 · 켬 기억 · 짝 상태 · 보낼 함 · ack), 중계 결과 해석(안드로이드 의존 없음)
package com.example.guardian.car

/**
 * 순서(감사 하3) — 문 확인 → **기기 쪽을 먼저 지운다** → 꺼낸 토큰으로 `pair/end` 를 한 번 보낸다.
 * 성공 · 401 은 중계도 지워진 것이다. 네트워크 실패 · 그 밖의 HTTP 는 다시 시도하지 않고 「중계 기록은 24시간 뒤 사라짐」을 알린다.
 * 끝낼 토큰을 따로 두고 다시 시도하는 길보다 단순하고(저장할 비밀이 하나 더 생기지 않는다), 앞 운전자 상태가 차에 보이지 않게 되는
 * 목적은 기기 쪽 지우기로 이미 이뤄진다. 중계에 남은 짝은 차 토큰이 사라져 아무도 읽지 못하는 채로 24시간 뒤 사라진다.
 */
object CarUnpair {
    sealed interface Start {
        /** 문이 닫혀 있다(주행 · NO_SETUP 제한 · 확정 정지 아님 · 사건 중) — 아무것도 지우지 않는다. */
        data object Refused : Start
        /** 기기 쪽을 지웠다. [token] 이 있으면 그 토큰으로 `pair/end` 를 보낸다. */
        data class Cleared(val token: String?) : Start
    }

    /** 문을 다시 확인하고(누른 순간의 최신 차량 값을 허브에 넣은 뒤 부른다) 기기 쪽을 지운다. */
    fun start(hub: CarHub, store: CarPairStore, nowMs: Long): Start {
        if (!hub.unpairOk()) return Start.Refused
        val token = store.token()
        store.clear()
        hub.onUnpair(nowMs)
        return Start.Cleared(token)
    }

    /** 중계 쪽도 지워졌는지 — 2xx(본문을 못 읽어도 — Unparsable, app-qa 하3) 또는 401(이미 끝났거나 만료). */
    fun relayCleared(r: RelayResult<*>): Boolean =
        r is RelayResult.Ok || r is RelayResult.Unparsable || (r is RelayResult.Http && r.code == 401)
}
