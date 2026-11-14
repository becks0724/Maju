// debug 전용 GPS 측정 화면 — 워치가 LocationManager 로 위치 · 속도를 직접 받는지 재서 logcat · 앱 파일 CSV 에 남긴다(제품 기능 아님)
package com.example.guardian.watch.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.example.guardian.watch.ui.GuardianWearTheme
import com.example.guardian.watch.ui.WatchColors
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * adb `am start` 로 연다(런처에 없음). 「측정 시작」 → 정밀 위치 권한 → gps · fused 제공자를 각각 1초 간격으로 구독.
 * 위치 한 건마다 [GpsProbeCsv.row] 한 줄을 logcat(태그 [TAG]) 과 files/gps_probe/ CSV 에 남긴다. 위도 · 경도는 남기지 않는다(G3).
 * 시각은 모두 단조 시계(SystemClock.elapsedRealtime). 파일 이름에만 벽시계를 쓴다(꺼낼 때 구별용).
 */
class GpsProbeActivity : ComponentActivity() {
    private lateinit var lm: LocationManager

    private var startedAtMs by mutableLongStateOf(0L)
    private var measuring by mutableStateOf(false)
    private var nowMs by mutableLongStateOf(SystemClock.elapsedRealtime())
    private var batteryStart by mutableStateOf<Int?>(null)
    private var batteryNow by mutableStateOf<Int?>(null)
    private var envSummary by mutableStateOf("")
    private var phoneNodes by mutableStateOf<String?>(null)
    private var message by mutableStateOf<String?>(null)
    private val stats = mutableStateMapOf<String, ProviderStats>()

    private val listeners = mutableMapOf<String, LocationListener>()
    private var gnssCallback: GnssStatus.Callback? = null
    @Volatile private var satUsed: Int? = null
    @Volatile private var satVisible: Int? = null
    private var writer: BufferedWriter? = null
    private var file: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lm = getSystemService(LocationManager::class.java)
        batteryNow = battery()
        environment().forEach { note(it) }
        envSummary = buildString {
            append("GPS 하드웨어 ").append(if (hasGpsFeature()) "있음" else "없음")
            append(" · 위치 ").append(if (lm.isLocationEnabled) "켜짐" else "꺼짐")
            append("\n켜진 제공자 ").append(lm.getProviders(true).joinToString(", ").ifEmpty { "없음" })
        }
        lookUpPhone()
        setContent { GuardianWearTheme { Probe() } }
    }

    override fun onStart() {
        super.onStart()
        if (measuring) note("# 화면 앞으로 옴 t=${SystemClock.elapsedRealtime()}")
    }

    override fun onStop() {
        // 화면이 뒤로 가도 구독은 이어 두되(백그라운드 위치 제한으로 드물어질 수 있다) 구간을 표시해 둔다
        if (measuring) note("# 화면 뒤로 감 t=${SystemClock.elapsedRealtime()}")
        super.onStop()
    }

    override fun onDestroy() {
        stopMeasuring("화면 종료")
        super.onDestroy()
    }

    // ---- 기록 ----

    private fun hasGpsFeature() = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

    private fun battery(): Int? = getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    /** 시작 때 남기는 기기 · 제공자 상태(# 으로 시작하는 줄 — CSV 를 읽을 때 주석으로 건너뛴다). */
    private fun environment(): List<String> = buildList {
        add("# device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} t=${SystemClock.elapsedRealtime()}")
        add("# feature_location_gps=${hasGpsFeature()} location_enabled=${lm.isLocationEnabled} gnss_hw=${lm.gnssHardwareModelName ?: "-"}")
        add("# all_providers=${lm.allProviders.joinToString("|")}")
        add("# " + PROVIDERS.joinToString(" ") { p ->
            val state = if (p in lm.allProviders) runCatching { lm.isProviderEnabled(p) }.getOrNull()?.toString() ?: "?" else "absent"
            "$p=$state"
        })
        add("# battery_pct=${battery() ?: "-"}")
        phoneNodes?.let { add("# $it") }
    }

    /** 짝 휴대폰 연결 — 이미 쓰는 Wearable Data Layer NodeClient 로 연결된 기기 수만 본다(이름 · id 는 남기지 않는다). */
    private fun lookUpPhone() {
        runCatching {
            Wearable.getNodeClient(this).connectedNodes
                .addOnSuccessListener { nodes ->
                    phoneNodes = "phone_connected_nodes=${nodes.size} nearby=${nodes.count { it.isNearby }}"
                    note("# $phoneNodes")
                }
                .addOnFailureListener { phoneNodes = "phone_connected_nodes=? (${it.javaClass.simpleName})"; note("# $phoneNodes") }
        }
    }

    private fun note(line: String) {
        Log.i(TAG, line)
        writer?.let { runCatching { it.write(line); it.newLine(); it.flush() } }
    }

    // ---- 측정 ----

    private fun hasFine() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startMeasuring() {
        if (measuring || !hasFine()) return
        val now = SystemClock.elapsedRealtime()
        val dir = File(filesDir, "gps_probe").apply { mkdirs() }
        file = File(dir, "gps-probe-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.csv")
        writer = runCatching { file!!.bufferedWriter() }.getOrNull()
        note("# START t=$now file=${file?.name}")
        environment().forEach { note(it) }
        note("# sat_used/sat_visible = GnssStatus 마지막 값(GNSS 엔진 전체, 제공자 무관)")
        note(GpsProbeCsv.HEADER)

        startedAtMs = now
        batteryStart = battery()
        batteryNow = batteryStart
        stats.clear()
        measuring = true
        message = null

        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                satVisible = status.satelliteCount
                satUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
            }
            override fun onFirstFix(ttffMillis: Int) = note("# gnss_first_fix_ms=$ttffMillis t=${SystemClock.elapsedRealtime()}")
            override fun onStarted() = note("# gnss_started t=${SystemClock.elapsedRealtime()}")
            override fun onStopped() = note("# gnss_stopped t=${SystemClock.elapsedRealtime()}")
        }
        runCatching { lm.registerGnssStatusCallback(mainExecutor, cb) }
            .onSuccess { gnssCallback = cb }
            .onFailure { note("# gnss_status_callback 실패 ${it.javaClass.simpleName}") }

        for (p in listOf(LocationManager.GPS_PROVIDER, FUSED)) subscribe(p)
        if (listeners.isEmpty()) message = "구독한 제공자가 없습니다"
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(provider: String) {
        if (provider !in lm.allProviders) {
            note("# $provider 제공자 없음 — 구독 안 함")
            return
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(provider, location)
            override fun onProviderEnabled(p: String) = note("# $p enabled t=${SystemClock.elapsedRealtime()}")
            override fun onProviderDisabled(p: String) = note("# $p disabled t=${SystemClock.elapsedRealtime()}")
        }
        val at = SystemClock.elapsedRealtime()
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                // 옛 3인자 판은 QUALITY_BALANCED 라 fused 가 GNSS 를 안 켤 수 있다 — 고정밀을 명시한다
                val req = LocationRequest.Builder(INTERVAL_MS)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(INTERVAL_MS)
                    .setMinUpdateDistanceMeters(0f)
                    .build()
                lm.requestLocationUpdates(provider, req, mainExecutor, listener)
            } else {
                lm.requestLocationUpdates(provider, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            }
        }.onSuccess {
            listeners[provider] = listener
            stats[provider] = ProviderStats(provider, subscribedAtMs = at)
            note("# subscribe $provider t=$at interval_ms=$INTERVAL_MS")
        }.onFailure { note("# subscribe $provider 실패 ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun onFix(provider: String, loc: Location) {
        val now = SystemClock.elapsedRealtime()
        val fix = ProbeFix(
            recvElapsedMs = now,
            provider = provider,
            fixAgeMs = now - loc.elapsedRealtimeNanos / 1_000_000,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            speedAccMps = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else null,
            horizAccM = if (loc.hasAccuracy()) loc.accuracy else null,
            hasAltitude = loc.hasAltitude(),
            satUsed = satUsed,
            satVisible = satVisible,
        )
        stats[provider] = (stats[provider] ?: ProviderStats(provider, now)).with(fix)
        val row = GpsProbeCsv.row(fix)
        Log.i(TAG, "FIX,$row")
        writer?.let { runCatching { it.write(row); it.newLine(); it.flush() } }
    }

    private fun stopMeasuring(reason: String) {
        if (!measuring) return
        listeners.values.forEach { runCatching { lm.removeUpdates(it) } }
        listeners.clear()
        gnssCallback?.let { runCatching { lm.unregisterGnssStatusCallback(it) } }
        gnssCallback = null
        val now = SystemClock.elapsedRealtime()
        batteryNow = battery()
        note("# STOP reason=$reason t=$now")
        note(GpsProbeCsv.summary(now - startedAtMs, batteryStart, batteryNow, stats.values.sortedBy { it.provider }))
        runCatching { writer?.close() }
        writer = null
        measuring = false
        message = "저장 ${file?.name ?: "실패"}"
    }

    // ---- 화면 ----

    @Composable
    private fun Probe() {
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) startMeasuring()
            else message = "정밀 위치 권한이 없어 측정할 수 없습니다"
        }
        LaunchedEffect(Unit) {
            while (true) {
                nowMs = SystemClock.elapsedRealtime()
                batteryNow = battery()
                delay(1_000)
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("GPS 측정(debug)", fontSize = 14.sp, color = WatchColors.Fg2)
            Text(envSummary, fontSize = 12.sp, color = WatchColors.Mute, textAlign = TextAlign.Center)
            phoneNodes?.let { Text(it, fontSize = 11.sp, color = WatchColors.Mute, textAlign = TextAlign.Center) }

            if (measuring) {
                val elapsedS = (nowMs - startedAtMs) / 1000
                Text("%d:%02d".format(elapsedS / 60, elapsedS % 60), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("배터리 ${batteryStart ?: "-"}% → ${batteryNow ?: "-"}%", fontSize = 14.sp)
            }
            stats.values.sortedBy { it.provider }.forEach { ProviderCard(it) }

            message?.let { Text(it, fontSize = 13.sp, color = WatchColors.Caution, textAlign = TextAlign.Center) }

            Button(
                onClick = {
                    if (measuring) stopMeasuring("측정 끝 버튼")
                    else if (hasFine()) startMeasuring()
                    else ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (measuring) "측정 끝" else "측정 시작", fontSize = 16.sp) }
        }
    }

    @Composable
    private fun ProviderCard(s: ProviderStats) {
        val l = s.last
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.provider.uppercase(Locale.ROOT), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = WatchColors.Primary)
            Text(
                "첫 위치 ${s.ttffMs?.let { "%.1f초".format(it / 1000.0) } ?: "대기"} · ${s.count}건",
                fontSize = 15.sp,
            )
            Text("속도 ${GpsProbeCsv.num(l?.speedMps, 2).ifEmpty { "-" }} m/s", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "속도 ±${GpsProbeCsv.num(l?.speedAccMps, 2).ifEmpty { "-" }} · 수평 ±${GpsProbeCsv.num(l?.horizAccM, 1).ifEmpty { "-" }} m",
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            if (l != null) Text("위성 ${l.satUsed ?: "-"}/${l.satVisible ?: "-"}", fontSize = 12.sp, color = WatchColors.Fg2)
        }
    }

    private companion object {
        const val TAG = "GuardianGpsProbe"
        const val INTERVAL_MS = 1_000L
        /** LocationManager.FUSED_PROVIDER(API 31) — minSdk 30 이라 문자열로 둔다. */
        const val FUSED = "fused"
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    }
}
