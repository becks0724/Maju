// 동의 철회 · 기록 지우기(G2 철회 시 삭제) — 감시 끄기 · 중계 짝 끝내기 · 이 휴대폰 저장 전부 지우기, 정차 중에만 보인다
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.Ownership
import com.example.guardian.shared.protocol.PhoneSyncResponse

/** 철회에 필요한 저장소 부분 — 중계 토큰과 전부 지우기. */
interface ResetStore {
    val token: String?
    fun clearAll()
}

/** 마지막 sync — 잡아 둔 토큰(없으면 null)으로 꺼짐 · OFF 스냅숏을 보낸 뒤 [after] 를 부른다. 감시가 꺼져 있으면 바로 부른다. */
typealias LastSync = (token: String?, after: () -> Unit) -> Unit

object Withdrawal {
    /** 홈의 「동의 철회 · 기록 지우기」 줄 — 주행 중에는 보이지 않는다(G5). 감시가 꺼졌거나([status] null) 정차 여부를 모르면 보인다. */
    fun visible(status: EngineStatus?): Boolean = status == null || status.motion != Motion.DRIVING

    /** 「동의 철회 · 기록 지우기」 — 저장 전부(등록 정보 · 동의 · 켬 · 토큰 · 문자 기록)를 지우고 [end] 순서로 짝을 끝낸다(계약 §3.2). */
    fun run(store: ResetStore, lastSync: LastSync, endPair: (String) -> Unit) = end(store.token, store::clearAll, lastSync, endPair)

    /**
     * 짝 끝내는 순서. 토큰을 잡아 둔 채 [clear] 로 기기 안 저장(토큰 포함)을 **바로** 지운다 — 앱이 죽어도 남지 않게(G2).
     * 그 뒤 잡아 둔 토큰으로 마지막 sync(꺼짐 · OFF)를 보내 차가 꺼짐을 알게 하고, 그것이 끝난 다음에 중계의 짝을 끝낸다
     * (짝을 먼저 끝내면 마지막 sync 가 401 이 된다. 1차 [미검증] — 토큰을 먼저 지워 마지막 sync 가 건너뛰어졌다).
     */
    fun end(token: String?, clear: () -> Unit, lastSync: LastSync, endPair: (String) -> Unit) {
        clear()
        lastSync(token) { token?.let(endPair) }
    }

    /** 차가 마지막 sync 를 읽기를 기다리는 최대 시간 — 차 sync 주기(2초) 두세 번 [추정]. */
    const val CAR_READ_WAIT_MS = 6_000L
}

/**
 * 철회의 마지막 sync 를 언제 끝낼지. 짝을 끝내면 중계가 상태를 바로 지워 차는 내 꺼짐을 읽지 못하고 401 만 받는다 — 그래서 차가 내 꺼짐
 * (OFF · 동의 없음)을 읽을 때까지 sync 를 주기마다 더 한다. 차가 내 첫 마지막 sync 뒤에 중계에 붙었으면(`peer.receivedAt` 이 그 응답의
 * `serverTime` 보다 늦음, 둘 다 중계 시계) 그 응답으로 읽은 것이다. 차가 붙어 있지 않거나(peer 없음 · 10초 넘게 낡음), sync 가 실패했거나,
 * [Withdrawal.CAR_READ_WAIT_MS] 가 지났으면 기다리지 않는다. 끝나면 [finish] 로 [after](짝 끝내기)를 한 번만 부른다.
 */
class LastSyncWait(val token: String?, private val after: () -> Unit) {
    private var firstServerTime: Long? = null
    private var since = 0L
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 이번 sync 응답(실패 · 보내지 않음이면 null)과 지금 단조 시각. 짝을 끝내도 되면 true. */
    fun done(resp: PhoneSyncResponse?, nowMono: Long): Boolean {
        if (token == null || resp == null) return true
        val first = firstServerTime ?: resp.serverTime.also { firstServerTime = it; since = nowMono }
        if (nowMono - since >= Withdrawal.CAR_READ_WAIT_MS) return true
        val carAt = resp.peer?.receivedAt ?: return true
        if (resp.serverTime - carAt > Ownership.CAR_FRESH_MS) return true
        return carAt > first
    }

    fun finish() { if (finished.compareAndSet(false, true)) after() }
}
