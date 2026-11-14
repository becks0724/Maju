// 휴대폰 위치 — 서비스가 도는 동안 LocationManager GPS 를 1초마다 계속 받고(GPS 가 없으면 NETWORK 좌표만), 주소는 Geocoder 로 가끔만 작업 스레드에서 묻는다(위치 동의 · 권한이 있을 때만)
package com.example.guardian.phone.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.example.guardian.phone.core.AddressCache
import com.example.guardian.phone.core.GpsSpeed
import com.example.guardian.shared.protocol.Loc
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class PhoneLocator(private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var provider: String? = null
    private val geoWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "guardian-geocode").apply { isDaemon = true } }
    private val addresses = AddressCache(geoWorker, ::distanceM, ::geocode)

    fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 매 주기 부른다. [enabled](위치 동의) · 권한 · 켜진 제공자에 맞춰 구독을 시작하거나 바꾸거나 해제한다.
     * - 동의 · 권한이 있고 GPS 가 켜져 있으면 GPS 를 [UPDATE_MS] 마다 계속 받는다(터널 · 지하에서 1회 요청이 늦게 끝나 끊기던 것을 줄인다).
     * - GPS 가 꺼져 있거나 없으면 NETWORK 위치로 좌표만 받는다(속도 null). 둘 다 없으면 받지 않는다.
     * - 동의를 거두면 해제한다. 제공자가 바뀌면(GPS 를 다시 켬 등) 다음 주기에 갈아탄다.
     * 결과는 [executor] 위에서 [onLoc](잡은 위치 — 속도는 거르기 전, 단조 시각 ms, 화면 잠금용으로 정확도를 거른 속도 km/h)로 온다. 같은 스레드에서 부르고, 서비스가 멈출 때 [stop] 한다.
     */
    fun ensure(enabled: Boolean, executor: Executor, onLoc: (Loc, Long, Double?) -> Unit) {
        val want = when {
            !enabled || !permitted() -> null
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (want == provider) return
        stop()
        if (want == null) return
        val l = LocationListener { loc ->
            // NETWORK 위치는 좌표만 쓴다(속도 null)
            val mps = if (loc.provider == LocationManager.GPS_PROVIDER && loc.hasSpeed()) loc.speed.toDouble() else null
            val accMps = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toDouble() else null
            onLoc(toLoc(loc, GpsSpeed.rawKmh(mps)), loc.elapsedRealtimeNanos / 1_000_000, GpsSpeed.usableKmh(mps, accMps))
        }
        try {
            lm.requestLocationUpdates(want, UPDATE_MS, 0f, executor, l)
            listener = l
            provider = want
        } catch (e: SecurityException) {
            // 권한이 막 거둬졌다 — 다음 주기에 다시 본다
        }
    }

    /** 구독 해제. 서비스가 멈추거나 동의를 거둘 때. */
    fun stop() {
        listener?.let { lm.removeUpdates(it) }
        listener = null
        provider = null
    }

    /** 경보 흐름 · 중계용 위치 — 속도는 거르기 전 값(7fb88eb 이전과 같다). */
    private fun toLoc(l: Location, speedKmh: Double?) = Loc(
        lat = l.latitude, lon = l.longitude,
        accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
        speedKmh = speedKmh,
        at = l.time,
        address = address(l),
    )

    /** 서비스가 멈출 때 — 주소 묻는 작업 스레드를 끝낸다. */
    fun close() {
        geoWorker.shutdownNow()
    }

    private fun address(l: Location): String? = addresses.address(l.latitude, l.longitude)

    /**
     * 역지오코딩은 네트워크를 써서 느리다 — 엔진 스레드를 막지 않게 작업 스레드에서, 100m 넘게 움직였거나 처음일 때만 묻는다.
     * 주소가 아직 없거나 실패하면 주소 없이(좌표만) 보낸다.
     */
    @Suppress("DEPRECATION")
    private fun geocode(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return Geocoder(context, Locale.KOREA).getFromLocation(lat, lon, 1)?.firstOrNull()?.getAddressLine(0)?.removePrefix("대한민국 ")
    }

    companion object {
        const val UPDATE_MS = 1_000L

        private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float =
            FloatArray(1).also { Location.distanceBetween(lat1, lon1, lat2, lon2, it) }[0]
    }
}
