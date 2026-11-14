// 위치의 주소 — 역지오코딩을 엔진 스레드 밖(작업 스레드)에서 묻고, 마지막으로 얻은 주소를 그 자리 100m 안에서만 돌려준다(늦으면 주소 없이 좌표만)
package com.example.guardian.phone.core

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [address] 는 막지 않는다 — 주소가 없거나 [REFRESH_M] 넘게 움직였으면 null(좌표만)을 돌려주고 [worker] 에 한 번만 묻는다
 * (11-02 남은 하 — Geocoder 동기 호출이 엔진 스레드를 막았다). 실패(null · 예외)도 그 자리 결과로 두어 100m 안에서는 다시 묻지 않는다.
 * [distanceM] 은 (위도 1, 경도 1, 위도 2, 경도 2) → 미터.
 */
class AddressCache(
    private val worker: Executor,
    private val distanceM: (Double, Double, Double, Double) -> Float,
    private val lookup: (Double, Double) -> String?,
) {
    private class Hit(val lat: Double, val lon: Double, val address: String?)

    @Volatile private var last: Hit? = null
    private val busy = AtomicBoolean(false)

    fun address(lat: Double, lon: Double): String? {
        val h = last
        if (h != null && distanceM(h.lat, h.lon, lat, lon) < REFRESH_M) return h.address
        if (busy.compareAndSet(false, true)) {
            try {
                worker.execute {
                    try { last = Hit(lat, lon, runCatching { lookup(lat, lon) }.getOrNull()) } finally { busy.set(false) }
                }
            } catch (e: RejectedExecutionException) {
                busy.set(false) // 서비스가 멈추는 중 — 주소 없이
            }
        }
        return null
    }

    companion object {
        const val REFRESH_M = 100f
    }
}
