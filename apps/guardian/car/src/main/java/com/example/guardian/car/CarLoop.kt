// 2초 주기와 중계 sync 를 떼어 놓는 실행 틀 — 주기(차량 읽기 · 상태 기계 tick · 차 안 실행)는 네트워크를 기다리지 않고, sync 는 다른 스레드에서 돌려 결과만 넘겨받는다
package com.example.guardian.car

import com.example.guardian.shared.protocol.CarSyncRequest
import com.example.guardian.shared.protocol.CarSyncResponse
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [step] 은 주기 스레드에서만 부르고, [hub] 도 그 스레드에서만 만진다.
 * - 요청은 주기 스레드에서 만들어 [net] 에 넘긴다. 한 번에 하나만 보낸다(응답 · 실패를 받을 때까지 다음 요청을 만들지 않음).
 * - 결과는 [deliver] 로 주기 스레드에 돌려보내 허브에 넣는다. 앱은 주기 스레드 Handler.post, 시험은 큐를 쓴다.
 * - 중계가 멈춰 있는 동안에도 주기는 차량 읽기와 tick 을 계속한다 — 링크 신선도는 마지막 성공 시각으로 재므로 10초가 지나면
 *   허브가 스스로 「휴대폰이 주인」으로 보고 차 안 경보만 한다.
 * 왕복 시각(보낸 · 받은 단조 시각)은 sync 스레드에서 HTTP 바로 앞뒤로 잰다(시계 보정 표본).
 */
class CarLoop(
    private val hub: CarHub,
    private val relay: (CarSyncRequest) -> RelayResult<CarSyncResponse>,
    private val net: Executor,
    private val deliver: (() -> Unit) -> Unit,
    private val clock: () -> Long,
    private val onUnauthorized: () -> Unit = {},
    private val onReport: (SyncReport) -> Unit = {},
    /** 결과 처리 중 예외 — 주기 스레드가 죽지 않게 받아서 넘긴다. 앱은 예외 종류만 기록한다(값 · 위치 · 토큰 금지). */
    private val onError: (Exception) -> Unit = {},
    /** 중계 200 인데 본문 해석 실패 — 링크는 살아 있다고 허브에 알린 뒤 예외 종류만 넘긴다(기록용). */
    private val onUnparsable: (String) -> Unit = {},
    /** 401 이 아닌 HTTP 실패(4xx · 5xx) — 상태 코드만 넘긴다(본문 · 토큰 금지). 앱은 기록만 한다(리뷰 하1). */
    private val onHttpError: (Int) -> Unit = {},
) {
    private val inFlight = AtomicBoolean(false)
    /** 보낸 요청의 세대 — [discardInFlight] 뒤에 돌아온 옛 결과는 허브에 넣지 않는다. 주기 스레드에서만 만진다. */
    private var generation = 0

    val syncInFlight: Boolean get() = inFlight.get()

    /**
     * 지금 나가 있는 sync 의 결과를 버린다 — 휴대폰 연결 끊기 직후 옛 토큰으로 보낸 sync 가 성공해 앞 운전자의 워치 · 휴대폰 상태를
     * 다시 채우지 않게. 결과가 오면 「보내는 중」만 풀고 버린다(401 이어도 짝 폐기를 다시 부르지 않음).
     */
    fun discardInFlight() { generation++ }

    /** 주기 한 번. 절대 네트워크를 기다리지 않는다. [token] 이 null 이면(짝 없음 · 중계 주소 없음) 보내지 않는다. */
    fun step(reading: CarReading, token: String?, nextSeq: () -> Long, wallMs: Long) {
        val now = clock()
        hub.onReading(reading, now)
        if (token == null || !inFlight.compareAndSet(false, true)) return
        val req = hub.buildRequest(token, nextSeq(), wallMs, now)
        val gen = generation
        try {
            net.execute {
                val sent = clock()
                val r = try { relay(req) } catch (e: Exception) { RelayResult.Failed(e.javaClass.simpleName) }
                val received = clock()
                deliver {
                    if (gen != generation) { inFlight.set(false); return@deliver }
                    try {
                        finish(req, sent, received, r)
                    } catch (e: Exception) {
                        inFlight.set(false)
                        onError(e)
                    }
                }
            }
        } catch (e: Exception) {
            inFlight.set(false) // 실행기가 닫혔다 — 다음 주기에 다시
        }
    }

    private fun finish(req: CarSyncRequest, sent: Long, received: Long, r: RelayResult<CarSyncResponse>) {
        inFlight.set(false)
        when (r) {
            is RelayResult.Ok -> onReport(hub.onSyncSuccess(req, sent, received, r.value))
            is RelayResult.Http -> {
                hub.onSyncFailure(clock())
                if (r.code == 401) onUnauthorized() else onHttpError(r.code)
            }
            is RelayResult.Failed -> hub.onSyncFailure(clock())
            is RelayResult.Unparsable -> { hub.onSyncUnparsable(req, clock()); onUnparsable(r.reason) }
        }
    }
}
