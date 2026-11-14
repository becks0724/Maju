// 실제 중계(relay.py)와의 짧은 연기 시험 — 빈 포트로 띄워 차 RelayClient · 워치 RelayApi · 휴대폰 JSON 으로 짝짓기와 sync 왕복을 실제 HTTP 로 한 번 해 본다
package com.example.guardian.integration

import com.example.guardian.car.CarHub
import com.example.guardian.car.RelayClient
import com.example.guardian.car.RelayResult
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.PairJoinRequest
import com.example.guardian.shared.protocol.PairJoinResponse
import com.example.guardian.shared.protocol.PhoneSyncRequest
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Role
import com.example.guardian.shared.protocol.Via
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.protocol.WatchSyncRequest
import com.example.guardian.shared.report.Consents
import com.example.guardian.watch.net.HttpResponse
import com.example.guardian.watch.net.HttpTransport
import com.example.guardian.watch.net.RelayApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class RealRelaySmokeTest {
    private var proc: Process? = null
    private lateinit var base: String

    @Before
    fun start() {
        val py = System.getProperty("guardian.relayPy")?.let(::File)
        assumeTrue("relay.py 없음", py?.isFile == true)
        val p = runCatching {
            ProcessBuilder("python3", "-I", py!!.absolutePath, "--port", "0").redirectErrorStream(true).start()
        }.getOrNull()
        assumeTrue("python3 실행 불가", p != null)
        proc = p
        val reader = p!!.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + 5_000
        var port: Int? = null
        while (port == null && System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            port = Regex("listening [^:]+:(\\d+)").find(line)?.groupValues?.get(1)?.toInt()
        }
        assumeTrue("relay.py 가 듣기 시작하지 않음", port != null)
        base = "http://127.0.0.1:$port"
        Thread { runCatching { reader.forEachLine { } } }.apply { isDaemon = true }.start()
    }

    @After
    fun stop() {
        proc?.let { it.destroy(); it.waitFor(3, TimeUnit.SECONDS) }
    }

    private fun post(path: String, body: String): HttpResponse {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
        c.disconnect()
        return HttpResponse(code, text)
    }

    @Test
    fun pairAndSyncRoundTrip() {
        val car = RelayClient(base)
        val start = (car.pairStart() as RelayResult.Ok).value
        // 휴대폰 — 앱의 HTTP 클라이언트는 BuildConfig 를 써서 여기서는 같은 :shared 모델 JSON 을 직접 보낸다
        val phoneToken = PairJoinResponse.fromJson(JsonObj(Json.parseObject(post("/v1/pair/join", Json.encode(PairJoinRequest(start.pairCode, Role.PHONE).toJson())).body))).token
        // 워치 — 앱의 RelayApi 를 실제 HTTP 로
        val watchApi = RelayApi(HttpTransport { path, body -> post(path, body) })
        val watchToken = (watchApi.join(start.pairCode) as com.example.guardian.watch.net.RelayResult.Ok).value.token

        val w = Watch(WristVerdict.NORMAL, 70, null, true, WatchSource.SIMULATION, true, null)
        assertTrue(watchApi.sync(WatchSyncRequest(watchToken, 1, 0, w), healthConsent = true) is com.example.guardian.watch.net.RelayResult.Ok)

        val settings = FlowStatus.of(FlowSnapshot(powerOn = true, consents = Consents(true, true, true)), Owner.CAR)
        val pr = post("/v1/phone/sync", Json.encode(PhoneSyncRequest(phoneToken, 1, 0, null, settings).toJson(healthConsent = true)))
        assertEquals(200, pr.code)
        assertTrue(PhoneSyncResponse.fromJson(JsonObj(Json.parseObject(pr.body))).peer == null)

        val hub = CarHub(RecordingCarPorts())
        val resp = (car.carSync(hub.buildRequest(start.carToken, 1, 0, 0)) as RelayResult.Ok).value
        assertNotNull("차가 휴대폰 상태를 받는다", resp.peer)
        assertEquals(true, resp.peer!!.flow?.snapshot?.powerOn)
        assertEquals("중계가 고른 워치 값", Via.WATCH, resp.watch?.via)
        assertNotNull(resp.watch?.ageMs)
        val pr2 = PhoneSyncResponse.fromJson(JsonObj(Json.parseObject(post("/v1/phone/sync", Json.encode(PhoneSyncRequest(phoneToken, 2, 0, null, settings).toJson(true))).body)))
        assertNotNull("휴대폰이 차 상태를 받는다", pr2.peer?.vehicle)

        // 휴대폰 건강 동의 false(감사 하4, 계약 §5) — 실제 중계가 워치 직접 값을 보관 · 전달하지 않는다(app-qa 하2)
        val off = PhoneSyncRequest(phoneToken, 3, 0, null, settings, healthConsent = false).toJson(true)
        assertEquals(200, post("/v1/phone/sync", Json.encode(off)).code)
        assertTrue(watchApi.sync(WatchSyncRequest(watchToken, 2, 0, w), healthConsent = true) is com.example.guardian.watch.net.RelayResult.Ok)
        val resp2 = (car.carSync(hub.buildRequest(start.carToken, 2, 0, 0)) as RelayResult.Ok).value
        assertNull("건강 동의 false 면 차에 워치 값이 내려가지 않는다", resp2.watch)
    }
}
