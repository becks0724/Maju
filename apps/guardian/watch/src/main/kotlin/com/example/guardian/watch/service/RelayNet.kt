// 중계로 가는 네트워크 — 쓰는 동안 Wi-Fi 를 요청해 그 위로 연결하고, Wi-Fi 가 없으면 기본 경로(짝 휴대폰 블루투스 경유 등)로 연다
package com.example.guardian.watch.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.guardian.watch.BuildConfig
import com.example.guardian.watch.net.RelayApi
import com.example.guardian.watch.net.UrlTransport

/**
 * Wear OS 는 앱이 요청하지 않으면 Wi-Fi 를 꺼 두고 휴대폰 경유로 보낼 수 있다(공식 「고대역 네트워크 요청」 안내).
 * 베타 중계는 같은 Wi-Fi 의 Mac 이라 감시 · 짝짓기 동안 Wi-Fi 를 요청한다 — 배터리 영향은 실기기 [미검증].
 */
class RelayNet(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    @Volatile private var wifi: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 지금 Wi-Fi 로 직접 보내는가(아니면 기본 경로 — 짝 휴대폰 경유일 수 있음). 상태 화면 「전송」 줄. */
    val onWifi: Boolean get() = wifi != null

    /** 이 빌드에 중계 주소가 있는가(debug local.properties guardian.relayBase). */
    val configured: Boolean get() = BuildConfig.RELAY_BASE.isNotBlank()

    val api: RelayApi = RelayApi(UrlTransport(BuildConfig.RELAY_BASE, open = { url -> wifi?.openConnection(url) ?: url.openConnection() }))

    fun acquire() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wifi = network }
            override fun onLost(network: Network) { if (wifi == network) wifi = null }
        }
        callback = cb
        runCatching {
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        }.onFailure { callback = null }
    }

    /** 백그라운드 스레드에서만 — Wi-Fi 가 잡힐 때까지 최대 [timeoutMs] 기다린다(짝짓기 한 번 보낼 때). */
    fun awaitWifi(timeoutMs: Long) {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (wifi == null && System.nanoTime() < until) Thread.sleep(100)
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        wifi = null
    }
}
