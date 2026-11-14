// 중계 HTTP 호출(짝 참여 role WATCH · 워치 직접 sync · 워치 연결 끝내기) — 본문은 :shared 프로토콜 모델로 만들고 읽는다
package com.example.guardian.watch.net

import com.example.guardian.shared.protocol.ErrorResponse
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonException
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.PairEndRequest
import com.example.guardian.shared.protocol.PairJoinRequest
import com.example.guardian.shared.protocol.PairJoinResponse
import com.example.guardian.shared.protocol.Role
import com.example.guardian.shared.protocol.WatchSyncRequest
import com.example.guardian.shared.protocol.WatchSyncResponse
import java.io.IOException

data class HttpResponse(val code: Int, val body: String)

/** POST 한 번. 연결 실패는 IOException. 시험에서는 가짜로 바꾼다. */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun post(path: String, body: String): HttpResponse
}

sealed interface RelayResult<out T> {
    data class Ok<T>(val value: T) : RelayResult<T>
    /** 401 — 토큰 없음 · 만료 · 짝 종료. 앱은 다시 짝을 맺는다(계약 §7). */
    data object Unauthorized : RelayResult<Nothing>
    /** 404 PAIR_CODE_INVALID — 없는 코드 · 만료 · 이미 쓴 역할(어느 쪽인지 드러내지 않음). */
    data object InvalidCode : RelayResult<Nothing>
    data class Failed(val reason: String) : RelayResult<Nothing>
}

class RelayApi(private val http: HttpTransport) {
    fun join(pairCode: String): RelayResult<PairJoinResponse> =
        call("/v1/pair/join", PairJoinRequest(pairCode, Role.WATCH).toJson()) { o ->
            PairJoinResponse.fromJson(o).also { if (it.role != Role.WATCH) throw JsonException("워치 토큰이 아님") }
        }

    /** [healthConsent] 가 false 면 심박 정수를 싣지 않는다(계약 §4.3 · G2). */
    fun sync(req: WatchSyncRequest, healthConsent: Boolean): RelayResult<WatchSyncResponse> =
        call("/v1/watch/sync", req.toJson(healthConsent), WatchSyncResponse::fromJson)

    /** 워치 토큰으로 부르면 워치 연결만 지운다(계약 §3.2). */
    fun end(token: String): RelayResult<Unit> = call("/v1/pair/end", PairEndRequest(token).toJson()) { }

    private fun <T> call(path: String, body: Map<String, Any?>, parse: (JsonObj) -> T): RelayResult<T> {
        val res = try {
            http.post(path, Json.encode(body))
        } catch (e: IOException) {
            return RelayResult.Failed("연결 실패 ${e::class.java.simpleName}")
        }
        return when (res.code) {
            200 -> try {
                RelayResult.Ok(parse(JsonObj(Json.parseObject(res.body))))
            } catch (e: JsonException) {
                RelayResult.Failed("응답 형식 ${e.message}")
            }
            401 -> RelayResult.Unauthorized
            404 -> if (errorCode(res.body) == "PAIR_CODE_INVALID") RelayResult.InvalidCode else RelayResult.Failed("HTTP 404")
            else -> RelayResult.Failed("HTTP ${res.code}")
        }
    }

    private fun errorCode(body: String): String? =
        try { ErrorResponse.fromJson(JsonObj(Json.parseObject(body))).error } catch (e: JsonException) { null }
}
