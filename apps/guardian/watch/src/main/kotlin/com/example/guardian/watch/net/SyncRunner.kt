// 워치 직접 경로 sync 한 번 — 워치 상태 · ack 를 실어 보내고, 받은 지시를 엔진에 넘기고, 중계가 받은 ack 를 지운다(계약 §4.1 · §4.6)
package com.example.guardian.watch.net

import com.example.guardian.shared.protocol.SeqGenerator
import com.example.guardian.shared.protocol.WatchSyncRequest
import com.example.guardian.watch.core.Clocks
import com.example.guardian.watch.core.WatchAction
import com.example.guardian.watch.core.WatchEngine

class SyncRunner(
    private val api: RelayApi,
    /** 이 sync 결과를 낸 엔진 — 서비스가 결과를 쓰기 전에 아직 지금 감시의 엔진인지 본다(코드 리뷰 11-09 워치 하). */
    val engine: WatchEngine,
    private val clocks: Clocks,
    /** 계약 §4.1 — 시작 시 에포크 ms 에서 시작해 1씩 올린다(앱을 다시 켜도 줄지 않게). */
    private val seq: SeqGenerator = SeqGenerator(clocks.wall()),
) {
    sealed interface Outcome {
        /** [actions] 는 기기에서 할 일(경보 등), [acksLeft] 가 true 면 곧바로 한 번 더 보내 ack 를 넘긴다. */
        data class Ok(val actions: List<WatchAction>, val acksLeft: Boolean) : Outcome
        data object Unauthorized : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun runOnce(token: String, healthConsent: Boolean): Outcome {
        val (watch, acks) = synchronized(engine) { engine.watch(clocks) to engine.commands.acks() }
        val req = WatchSyncRequest(token, seq.next(), clocks.wall(), watch, acks)
        return when (val r = api.sync(req, healthConsent)) {
            is RelayResult.Ok -> synchronized(engine) {
                engine.commands.confirm(acks)
                engine.commands.ackOnly(r.value.skippedCommandIds)
                val actions = engine.handle(r.value.commands, clocks.mono())
                Outcome.Ok(actions, engine.commands.acks().isNotEmpty())
            }
            RelayResult.Unauthorized -> Outcome.Unauthorized
            RelayResult.InvalidCode -> Outcome.Failed("PAIR_CODE_INVALID")
            is RelayResult.Failed -> Outcome.Failed(r.reason)
        }
    }
}
