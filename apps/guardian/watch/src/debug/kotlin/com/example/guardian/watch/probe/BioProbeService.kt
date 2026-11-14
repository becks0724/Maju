// debug 전용 생체 기록 포그라운드 서비스(health · location) — 가속도 · 자이로 · Health Services 심박 · 속도 · Sensor SDK 원시값을 경보 없이 파일에 남긴다
package com.example.guardian.watch.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.HeartRateAccuracy
import androidx.health.services.client.unregisterMeasureCallback
import com.example.guardian.watch.BuildConfig
import com.example.guardian.watch.R
import com.example.guardian.watch.core.ResumeDecision
import com.example.guardian.watch.sensor.SensorSdkProvider
import com.example.guardian.watch.service.WatchHub
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * 기록과 판정을 분리한다(계획 11-07 핵심 결정 1) — WatchEngine · GateRunner · PulseGate · 경보 · 중계를 부르지 않는다.
 * - 화면이 꺼지거나 화면을 나가도 끝 버튼까지 기록한다 — 포그라운드 서비스 + 부분 wake lock(주행 3 의 GPS 측정 화면은 뒤로 가자 멈췄다).
 * - 모든 스트림은 줄을 [BioProbeWriter] 에 넘기기만 한다. 파일 쓰기는 그 스레드 하나가 2초마다 비운다.
 * - 시각은 받은 순간의 단조 시계(`recv_ms`)와 샘플 자체 시각을 둘 다 남긴다. 좌표는 어디에도 남기지 않는다(G3). 값은 logcat 에 찍지 않는다(G2).
 * - 배터리 10% 이하면 스스로 끝낸다.
 */
class BioProbeService : Service() {
    private var writer: BioProbeWriter? = null
    private var exec: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorThread: HandlerThread? = null
    private var sensorListener: SensorEventListener? = null
    private var locationListener: LocationListener? = null
    private var hsCallback: MeasureCallback? = null
    private var sdk: SdkRecorder? = null
    private var ticks = 0L
    private var running = false
    @Volatile private var autoStopPosted = false
    /** 쓰기 실패 감시 — 직전 틱의 누적 수와 「연속으로 늘어난 틱 수」. [IO_FAIL_TICKS] 틱 연속이면 끝낸다(자료가 안 남는 채로 돌지 않게). */
    private var lastIoErrors = 0L
    private var ioGrowStreak = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent.getBooleanExtra(EXTRA_HS, false))
            else -> { end("정지 요청"); return START_NOT_STICKY } // ACTION_STOP, 또는 시스템이 다시 띄운 경우 — 세션 폴더를 모르니 다시 시작하지 않는다
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        end("서비스 종료")
        super.onDestroy()
    }

    // ---- 시작 · 끝 ----

    private fun begin(hsEnabled: Boolean) {
        if (running) return
        createChannel()
        // health 유형은 건강 권한, location 유형은 위치 권한이 허용돼 있어야 한다(API 34+). 없는 쪽은 유형에서 뺀다
        val type = (if (healthGranted()) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0) or
            (if (locationGranted()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        try {
            ServiceCompat.startForeground(this, NOTIFY_ID, notification(), type)
        } catch (e: RuntimeException) {
            Log.w(TAG, "생체 기록 포그라운드 시작 거부 ${e::class.java.simpleName}")
            BioProbeHub.update { it.copy(running = false, notice = "포그라운드 시작 거부 ${e::class.java.simpleName}") }
            stopSelf()
            return
        }
        running = true
        autoStopPosted = false
        lastIoErrors = 0L
        ioGrowStreak = 0
        val now = SystemClock.elapsedRealtime()
        val session = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val w = BioProbeWriter(File(File(filesDir, DIR), session)).also { writer = it }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guardian:bioprobe").apply { acquire() }
        val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bio-probe-tick") }.also { exec = it }

        BioProbeHub.update {
            BioProbeHub.State(running = true, startedAtMs = now, session = session, hsEnabled = hsEnabled, batteryPct = battery(),
                sdk = if (SdkRecorderProvider.AVAILABLE) "Sensor SDK 준비" else "Sensor SDK 없음(AAR 없는 빌드)")
        }
        writeMeta(session, hsEnabled)
        event("start", "session=$session hs=$hsEnabled")

        startAndroidSensors()
        if (hsEnabled) startHealthServices(ex)
        startSpeed()
        startSdk()
        ex.scheduleWithFixedDelay({ safely(::tick) }, 1, 1, TimeUnit.SECONDS)
    }

    /** 어느 정지 단계가 예외를 던져도 wake lock 해제 · foreground 해제 · 파일 닫기까지 반드시 간다. */
    private fun end(reason: String) {
        if (!running) { stopSelf(); return }
        running = false
        val w = writer
        val ioErrors = w?.ioErrorCount() ?: 0L
        try {
            runCatching { event("stop", "reason=$reason io_errors=$ioErrors") }
            runCatching { exec?.shutdownNow() }; exec = null
            runCatching { sdk?.stop() }; sdk = null
            runCatching { stopAndroidSensors() }
            runCatching { stopHealthServices() }
            runCatching { stopSpeed() }
        } finally {
            writer = null
            val counts = runCatching { w?.countedSnapshot() }.getOrNull() ?: BioStream.COUNTED.map { 0L }
            val notice = "끝 — $reason" + if (ioErrors > 0) " · 쓰기 실패 ${ioErrors}건" else ""
            runCatching { BioProbeHub.update { it.copy(running = false, counts = counts, batteryPct = battery(), notice = notice) } }
            runCatching { w?.close { Log.i(TAG, "생체 기록 저장 끝(건수 ${counts.sum()} 쓰기 실패 $ioErrors)") } }
            runCatching { wakeLock?.let { if (it.isHeld) it.release() } }; wakeLock = null
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    /** 1초마다 — state.csv 한 줄, 화면 상태, 2초마다 Sensor SDK flush, 배터리 · 쓰기 실패 자동 종료. */
    private fun tick() {
        val w = writer ?: return
        val now = SystemClock.elapsedRealtime()
        val bat = battery()
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val counts = w.countedSnapshot()
        val ioErrors = w.ioErrorCount()
        val monitoring = WatchHub.state.monitoring // 기록 중 감시 켜기는 막지 않고 열로만 남긴다
        w.append(BioStream.STATE, BioProbeCsv.state(now, System.currentTimeMillis(), screenOn, bat, counts, monitoring, ioErrors))
        BioProbeHub.update { it.copy(counts = counts, batteryPct = bat, notice = if (ioErrors > 0) "쓰기 실패 ${ioErrors}건" else it.notice) }
        if (++ticks % 2 == 0L) sdk?.flush()
        ioGrowStreak = if (ioErrors > lastIoErrors) ioGrowStreak + 1 else 0
        lastIoErrors = ioErrors
        val stopReason = when {
            bat != null && bat <= BATTERY_STOP_PCT -> "배터리 ${bat}%".also { event("auto_stop", "battery_pct=$bat") }
            ioGrowStreak >= IO_FAIL_TICKS -> "쓰기 실패".also { event("auto_stop", "io_errors=$ioErrors streak_ticks=$ioGrowStreak") }
            else -> null
        }
        if (stopReason != null && !autoStopPosted) {
            autoStopPosted = true
            // 종료는 메인 스레드에서 — tick 실행기 스스로를 끄는 자리에서 하지 않는다
            Handler(Looper.getMainLooper()).post { end(stopReason) }
        }
    }

    // ---- 스트림 ----

    private fun startAndroidSensors() {
        val sm = getSystemService(SensorManager::class.java)
        val thread = HandlerThread("bio-probe-sensors").also { it.start(); sensorThread = it }
        val handler = Handler(thread.looper)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = safely {
                val stream = when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> BioStream.AND_ACC
                    Sensor.TYPE_GYROSCOPE -> BioStream.AND_GYRO
                    else -> return@safely
                }
                // e.timestamp 는 부팅 뒤 ns(elapsedRealtimeNanos 기준)
                writer?.append(stream, BioProbeCsv.andSensor(SystemClock.elapsedRealtime(), e.timestamp / 1_000_000, e.values[0], e.values[1], e.values[2], e.accuracy))
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = safely { event("sensor_accuracy", "type=${sensor.type} accuracy=$accuracy") }
        }
        sensorListener = l
        for ((name, s) in listOf("accelerometer" to acc, "gyroscope" to gyro)) {
            if (s == null) { event("sensor_absent", name); continue }
            val ok = sm.registerListener(l, s, SENSOR_PERIOD_US, 0, handler)
            event(if (ok) "sensor_on" else "sensor_register_failed", "$name period_us=$SENSOR_PERIOD_US")
        }
    }

    private fun stopAndroidSensors() {
        sensorListener?.let { getSystemService(SensorManager::class.java).unregisterListener(it) }
        sensorListener = null
        sensorThread?.quitSafely(); sensorThread = null
    }

    /** Health Services 심박(선택) — 값 · 정확도 상태를 거르지 않고 그대로 남긴다(관문용 HsHeartAdapter 는 쓰지 않는다). */
    private fun startHealthServices(ex: ScheduledExecutorService) {
        val cb = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) =
                safely { event("hs_availability", availability.toString()) }

            override fun onDataReceived(data: DataPointContainer) = safely {
                val now = SystemClock.elapsedRealtime()
                for (p in data.getData(DataType.HEART_RATE_BPM)) {
                    val status = (p.accuracy as? HeartRateAccuracy)?.sensorStatus?.name
                    writer?.append(BioStream.HS_HR, BioProbeCsv.hsHr(now, p.timeDurationFromBoot.toMillis(), p.value, status))
                    BioProbeHub.update { it.copy(lastHr = "HS ${p.value.toInt()} ${status ?: "-"}") }
                }
            }

            override fun onRegistrationFailed(throwable: Throwable) = safely { event("hs_registration_failed", throwable::class.java.simpleName) }
        }
        hsCallback = cb
        runCatching { HealthServices.getClient(applicationContext).measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, ex, cb) }
            .onSuccess { event("hs_on", "HEART_RATE_BPM") }
            .onFailure { event("hs_register_failed", it::class.java.simpleName) }
    }

    private fun stopHealthServices() {
        // 해제 결과는 기다리지 않는다 — HealthServicesSource 와 같은 이유로 suspend 판을 표준 라이브러리로 부른다(새 의존성 없음)
        hsCallback?.let { cb ->
            val client = HealthServices.getClient(applicationContext).measureClient
            suspend { client.unregisterMeasureCallback(DataType.HEART_RATE_BPM, cb) }
                .startCoroutine(Continuation(EmptyCoroutineContext) { })
        }
        hsCallback = null
    }

    /** GPS 제공자 1초 — 속도 · 정확도만 옮긴다. [SpeedFix] 에는 좌표 필드가 없다(G3). */
    @SuppressLint("MissingPermission")
    private fun startSpeed() {
        if (!locationGranted()) { event("perm_missing", "location — 속도 기록 안 함"); return }
        val lm = getSystemService(LocationManager::class.java)
        val provider = LocationManager.GPS_PROVIDER
        if (provider !in lm.allProviders) { event("gps_absent", provider); return }
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) = safely {
                val now = SystemClock.elapsedRealtime()
                val fix = SpeedFix(
                    recvMs = now,
                    fixAgeMs = now - loc.elapsedRealtimeNanos / 1_000_000,
                    speedMps = if (loc.hasSpeed()) loc.speed else null,
                    speedAccMps = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else null,
                    horizAccM = if (loc.hasAccuracy()) loc.accuracy else null,
                )
                writer?.append(BioStream.SPEED, BioProbeCsv.speed(fix))
            }
            override fun onProviderEnabled(p: String) = safely { event("gps_provider_enabled", p) }
            override fun onProviderDisabled(p: String) = safely { event("gps_provider_disabled", p) }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val req = LocationRequest.Builder(SPEED_INTERVAL_MS)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(SPEED_INTERVAL_MS)
                    .setMinUpdateDistanceMeters(0f)
                    .build()
                lm.requestLocationUpdates(provider, req, mainExecutor, l)
            } else {
                lm.requestLocationUpdates(provider, SPEED_INTERVAL_MS, 0f, l, Looper.getMainLooper())
            }
        }.onSuccess { locationListener = l; event("gps_on", "$provider interval_ms=$SPEED_INTERVAL_MS") }
            .onFailure { event("gps_subscribe_failed", it::class.java.simpleName) }
    }

    private fun stopSpeed() {
        locationListener?.let { runCatching { getSystemService(LocationManager::class.java).removeUpdates(it) } }
        locationListener = null
    }

    private fun startSdk() {
        val w = writer ?: return
        val l = object : SdkRecorder.Listener {
            override fun event(kind: String, detail: String) = this@BioProbeService.event(kind, detail)
            override fun meta(line: String) = w.append(BioStream.META, line)
            override fun hr(hr: Int?, status: Int?) = BioProbeHub.update { it.copy(lastHr = "SDK ${hr ?: "-"} 상태 ${status ?: "-"}") }
            override fun sdkStatus(line: String) = BioProbeHub.update { it.copy(sdk = line) }
        }
        sdk = SdkRecorderProvider.create(this, w, l)?.also { runCatching { it.start() }.onFailure { event("sdk_start_failed", it::class.java.simpleName) } }
    }

    // ---- 보조 ----

    private fun event(kind: String, detail: String) {
        writer?.append(BioStream.EVENTS, BioProbeCsv.event(SystemClock.elapsedRealtime(), kind, detail))
        Log.i(TAG, "사건 $kind") // 종류만 — 값 · 상세는 파일에만(G2)
    }

    /** 시작 때 한 번 — 기기 · 권한 · 센서 목록 · 단조 ↔ 벽시계 대응 · 감시 상태. Sensor SDK 지원 목록은 연결 뒤 기록기가 덧붙인다. */
    private fun writeMeta(session: String, hsEnabled: Boolean) {
        val w = writer ?: return
        val sm = getSystemService(SensorManager::class.java)
        val lines = buildList {
            add("session=$session")
            add("device=${Build.MANUFACTURER} ${Build.MODEL} sdk_int=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
            add("app_version=${BuildConfig.VERSION_NAME} sensor_sdk_compiled=${BuildConfig.SENSOR_SDK} monitor_sensor_sdk=${SensorSdkProvider.AVAILABLE}")
            add("mono_ms=${SystemClock.elapsedRealtime()} wall_ms=${System.currentTimeMillis()}")
            add("free_bytes=${runCatching { filesDir.usableSpace }.getOrDefault(-1L)}")
            add("monitoring=${WatchHub.state.monitoring} hs_hr_enabled=$hsEnabled battery_pct=${battery() ?: "-"}")
            add("perm_health=${healthGranted()} perm_location=${locationGranted()} perm_missing=${missing().joinToString("|").ifEmpty { "-" }}")
            for (s in sm.getSensorList(Sensor.TYPE_ALL)) {
                add("sensor type=${s.type} name=${s.name} vendor=${s.vendor} min_delay_us=${s.minDelay} max_range=${s.maximumRange} resolution=${s.resolution} wakeup=${s.isWakeUpSensor}")
            }
        }
        lines.forEach { w.append(BioStream.META, it) }
    }

    private fun battery(): Int? = getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun locationGranted() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun healthGranted() = healthPermissions(Build.VERSION.SDK_INT).any(::granted)
    private fun missing() = (healthPermissions(Build.VERSION.SDK_INT) + LOCATION_PERMISSIONS).filterNot(::granted)

    private fun safely(block: () -> Unit) {
        try { block() } catch (t: RuntimeException) { Log.e(TAG, "생체 기록 루프 오류 ${t::class.java.simpleName}", t) }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "생체 기록(debug)", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, BioProbeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("생체 기록 중")
            .setContentIntent(pi)
            .setOngoing(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "GuardianBioProbe"
        private const val ACTION_START = "com.example.guardian.watch.BIO_START"
        private const val ACTION_STOP = "com.example.guardian.watch.BIO_STOP"
        private const val EXTRA_HS = "hs"
        private const val CHANNEL = "bio_probe"
        private const val NOTIFY_ID = 7
        /** 세션 폴더의 부모 — 꺼내기 `run-as app.maju tar cf - files/bio_probe`. */
        const val DIR = "bio_probe"
        /** Android 가속도 · 자이로 25Hz. */
        const val SENSOR_PERIOD_US = 40_000
        const val SPEED_INTERVAL_MS = 1_000L
        const val BATTERY_STOP_PCT = 10
        /** 쓰기 실패 누적 수가 이만큼 틱(초) 연속으로 늘면 끝낸다. */
        const val IO_FAIL_TICKS = 30

        val LOCATION_PERMISSIONS = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        /** 건강 권한 — 감시와 같은 목록(API 36+ 심박 세분 권한, AAR 이 컴파일돼 있으면 삼성 PPG 권한까지). */
        fun healthPermissions(sdkInt: Int): List<String> = ResumeDecision.requiredPermissions(sdkInt, BuildConfig.SENSOR_SDK)

        fun start(ctx: Context, hsEnabled: Boolean) {
            ctx.startForegroundService(Intent(ctx, BioProbeService::class.java).setAction(ACTION_START).putExtra(EXTRA_HS, hsEnabled))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BioProbeService::class.java).setAction(ACTION_STOP))
        }
    }
}
