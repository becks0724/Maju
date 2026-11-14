// HttpURLConnection 으로 중계에 JSON 을 POST 한다 — 연결은 주입받은 네트워크(Wi-Fi 우선, 없으면 기본 경로)로 연다
package com.example.guardian.watch.net

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

class UrlTransport(
    private val base: String,
    private val open: (URL) -> URLConnection = { it.openConnection() },
    private val timeoutMs: Int = 5_000,
) : HttpTransport {
    override fun post(path: String, body: String): HttpResponse {
        val c = open(URL(base.trimEnd('/') + path)) as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.useCaches = false
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResponse(code, text)
        } finally {
            c.disconnect()
        }
    }
}
