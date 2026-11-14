// 중계 서버 HTTP 클라이언트 — 짝 참여 · 휴대폰 sync · 짝 끝내기(contracts/guardian/protocol.md), JSON 은 :shared 것을 쓴다
package com.example.guardian.phone.net

import com.example.guardian.phone.BuildConfig
import com.example.guardian.shared.protocol.ErrorResponse
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.PairEndRequest
import com.example.guardian.shared.protocol.PairJoinRequest
import com.example.guardian.shared.protocol.PairJoinResponse
import com.example.guardian.shared.protocol.PhoneSyncRequest
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Role
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface RelayResult<out T> {
    data class Ok<T>(val value: T) : RelayResult<T>
    /** 401 — 짝이 없어졌다. 다시 짝을 맺는다. */
    data object Unauthorized : RelayResult<Nothing>
    /** 그 밖의 HTTP 오류(본문의 error 코드). */
    data class Failed(val status: Int, val error: String?) : RelayResult<Nothing>
    /** 연결 실패 · 시간 초과 · 응답 형식 틀림 — 다음 주기에 다시 보낸다. */
    data class NetworkError(val reason: String) : RelayResult<Nothing>
}

/**
 * 중계 주소는 debug 빌드 설정값(local.properties `guardian.relayBase`)만 쓴다. release 는 HTTPS 주소만 받는다(계약 §2 · G2).
 * 평문 HTTP 는 debug network security config 가 그 호스트 하나에만 허용한다.
 */
class RelayClient(private val base: String = BuildConfig.RELAY_BASE.trimEnd('/')) {
    val configured: Boolean get() = base.isNotEmpty() && (BuildConfig.DEBUG || base.startsWith("https://"))

    fun join(pairCode: String): RelayResult<PairJoinResponse> =
        post("/v1/pair/join", PairJoinRequest(pairCode, Role.PHONE).toJson()) { PairJoinResponse.fromJson(it) }

    /** [connectTimeoutMs] 는 연결 제한 — 네트워크 오류가 이어지는 동안은 [FAILING_CONNECT_TIMEOUT_MS]([SyncPacing]). 읽기 제한은 늘 [TIMEOUT_MS]. */
    fun sync(req: PhoneSyncRequest, healthConsent: Boolean, connectTimeoutMs: Int = TIMEOUT_MS): RelayResult<PhoneSyncResponse> =
        post("/v1/phone/sync", req.toJson(healthConsent), connectTimeoutMs) { PhoneSyncResponse.fromJson(it) }

    fun end(token: String): RelayResult<Unit> = post("/v1/pair/end", PairEndRequest(token).toJson()) { }

    private fun <T> post(path: String, body: Map<String, Any?>, connectTimeoutMs: Int = TIMEOUT_MS, parse: (JsonObj) -> T): RelayResult<T> {
        if (!configured) return RelayResult.NetworkError("중계 주소 없음")
        val conn = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return RelayResult.NetworkError(e.javaClass.simpleName)
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(Json.encode(body).toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            when {
                status in 200..299 -> RelayResult.Ok(parse(JsonObj(Json.parseObject(text))))
                status == 401 -> RelayResult.Unauthorized
                else -> RelayResult.Failed(status, runCatching { ErrorResponse.fromJson(JsonObj(Json.parseObject(text))).error }.getOrNull())
            }
        } catch (e: IOException) {
            RelayResult.NetworkError(e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            // 응답 JSON 형식 틀림(JsonException 등)
            RelayResult.NetworkError(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MS = 5_000
        /**
         * 직전 sync 가 네트워크 오류일 때의 연결 제한 — 주기(2초) 안에 끝나게 짧게 쓴다. 읽기 제한은 줄이지 않는다
         * (늘 느린 망에서 실패 상태를 못 벗어나 휴대폰이 판정을 넘겨받는 일을 막는다, PM 2026-11-02).
         */
        const val FAILING_CONNECT_TIMEOUT_MS = 1_500
    }
}
