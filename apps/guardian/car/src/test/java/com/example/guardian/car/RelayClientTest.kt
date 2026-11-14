// 중계 HTTP 클라이언트 시험 — 로컬 HTTP 서버로 2xx 해석 성공 · 2xx 본문 해석 실패(링크 유지) · 오류 코드 · 연결 실패를 가른다
package com.example.guardian.car

import com.example.guardian.shared.protocol.CarSyncRequest
import com.example.guardian.shared.protocol.Vehicle
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class RelayClientTest {
    private lateinit var server: HttpServer
    private var status = 200
    private var body = "{}"
    private val req = CarSyncRequest("tok", 1, 0, Vehicle(), null)

    @Before fun up() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.use { it.readBytes() }
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After fun down() { server.stop(0) }

    private fun client() = RelayClient("http://127.0.0.1:${server.address.port}")

    @Test fun okBodyIsOk() {
        body = """{"serverTime":5,"peer":null,"watch":null,"commands":[]}"""
        val r = client().carSync(req)
        assertTrue("$r", r is RelayResult.Ok && r.value.serverTime == 5L)
    }

    /** 리뷰 중 7 — 200 인데 본문이 틀리면(필드 형 · JSON 아님) 네트워크 실패가 아니라 해석 실패다. */
    @Test fun badBodyWith200IsUnparsableNotFailed() {
        body = """{"serverTime":"다른 빌드","peer":null,"watch":null,"commands":[]}"""
        assertTrue(client().carSync(req) is RelayResult.Unparsable)
        body = "json 아님"
        assertTrue(client().carSync(req) is RelayResult.Unparsable)
    }

    @Test fun errorCodeIsHttpWithErrorField() {
        status = 503
        body = """{"error":"CAPACITY"}"""
        val r = client().carSync(req)
        assertEquals(RelayResult.Http(503, "CAPACITY"), r)
    }

    /** 리뷰 11-08 하1 후속 — 짝짓기 · 새 번호 요청의 401 아닌 HTTP 실패는 상태 코드만 기록한다(본문 error 값 · 토큰 없음). */
    @Test fun pairHttpFailureLogsOnlyTheStatusCode() {
        status = 503
        body = """{"error":"CAPACITY"}"""
        val start = client().pairStart()
        assertEquals("짝짓기 실패 HTTP 503", start.httpFailureLog("짝짓기"))
        status = 500
        assertEquals("새 연결 번호 실패 HTTP 500", client().pairCode("secret-token").httpFailureLog("새 연결 번호"))
        status = 401
        assertNull("401 은 짝 폐기가 맡는다", client().pairCode("secret-token").httpFailureLog("새 연결 번호"))
        status = 200
        body = """{"pairCode":"123456","carToken":"t","expiresInSec":600}"""
        assertNull(client().pairStart().httpFailureLog("짝짓기"))
    }

    @Test fun connectionRefusedIsFailed() {
        val port = server.address.port
        server.stop(0)
        assertTrue(RelayClient("http://127.0.0.1:$port").carSync(req) is RelayResult.Failed)
    }
}
