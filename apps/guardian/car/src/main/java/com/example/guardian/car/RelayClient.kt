// 중계 서버 HTTP 클라이언트(계약 contracts/guardian/protocol.md) — pair/start · pair/code · pair/end · car/sync 를 표준 HttpURLConnection 과 :shared Json 으로 보낸다
package com.example.guardian.car

import com.example.guardian.shared.protocol.CarSyncRequest
import com.example.guardian.shared.protocol.CarSyncResponse
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.PairStartResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface RelayResult<out T> {
    data class Ok<T>(val value: T) : RelayResult<T>
    /** 중계가 오류로 답했다. 401 이면 짝짓기를 다시 한다. */
    data class Http(val code: Int, val error: String?) : RelayResult<Nothing>
    /** 연결 실패 · 시간 초과 — 다음 주기에 다시 보낸다. */
    data class Failed(val reason: String) : RelayResult<Nothing>
    /**
     * 중계는 200 을 주었는데 본문을 읽지 못했다(상대 빌드가 다른 형식을 실음 등). **링크는 살아 있다** — 링크 실패로 치지 않는다
     * (리뷰 중 7, 2026-11-02). [reason] 은 예외 종류만(본문 · 토큰 금지, G2).
     */
    data class Unparsable(val reason: String) : RelayResult<Nothing>
}

/**
 * 기록 한 줄 — 401 이 아닌 HTTP 실패면 「[what] 실패 HTTP 코드」, 아니면 null. 상태 코드만 싣는다(본문 error 값 · 토큰 금지, G2).
 * 401 은 짝 폐기가 따로 기록한다. 시연 중 짝짓기가 왜 안 되는지 찾게(리뷰 11-08 하1 후속).
 */
fun RelayResult<*>.httpFailureLog(what: String): String? =
    (this as? RelayResult.Http)?.takeIf { it.code != 401 }?.let { "$what 실패 HTTP ${it.code}" }

data class PairCodeResponse(val pairCode: String, val expiresInSec: Int)

/** [base] 가 비어 있으면(release — 공개 중계 미정) 아무 데도 보내지 않는다. 한 스레드(sync 스레드)에서만 부른다. */
class RelayClient(private val base: String) {
    val configured: Boolean get() = base.isNotBlank()

    fun pairStart(): RelayResult<PairStartResponse> = post("/v1/pair/start", emptyMap<String, Any?>()) { PairStartResponse.fromJson(it) }

    fun pairCode(token: String): RelayResult<PairCodeResponse> =
        post("/v1/pair/code", mapOf("token" to token)) { PairCodeResponse(it.str("pairCode"), it.int("expiresInSec")) }

    /** 차 토큰으로 짝 전체를 끝낸다(계약 §3.2 — 휴대폰 연결 끊기). 응답 본문 `{}` 는 보지 않는다. */
    fun pairEnd(token: String): RelayResult<Unit> = post("/v1/pair/end", mapOf("token" to token)) { }

    fun carSync(req: CarSyncRequest): RelayResult<CarSyncResponse> = post("/v1/car/sync", req.toJson()) { CarSyncResponse.fromJson(it) }

    private fun <T> post(path: String, body: Map<String, Any?>, parse: (JsonObj) -> T): RelayResult<T> {
        if (!configured) return RelayResult.Failed("중계 주소 없음")
        val conn = try {
            URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return RelayResult.Failed(e.javaClass.simpleName)
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(Json.encode(body).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val err = runCatching { JsonObj(Json.parseObject(text)).strOrNull("error") }.getOrNull()
                RelayResult.Http(code, err)
            } else {
                // 2xx 를 받았으면 그 뒤의 해석 실패는 네트워크 실패가 아니다
                try {
                    RelayResult.Ok(parse(JsonObj(Json.parseObject(text))))
                } catch (e: Exception) {
                    RelayResult.Unparsable(e.javaClass.simpleName)
                }
            }
        } catch (e: Exception) {
            // 기록에는 예외 종류만 — 본문 · 토큰을 남기지 않는다(G2)
            RelayResult.Failed(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 5_000
    }
}
