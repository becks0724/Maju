// 감시 포그라운드 서비스(health 유형) — 센서 → 관문 판정 1초, 중계 sync 2초 + 사건 즉시, 지시(경보 · 시나리오) 실행, 손목 · 버튼 응답
package com.example.guardian.watch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.example.guardian.shared.bio.AccelSample
import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.PpgSample
import com.example.guardian.shared.protocol.AlertStopReason
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.text.Phrase
import com.example.guardian.watch.R
import com.example.guardian.watch.core.ActiveAlert
import com.example.guardian.watch.core.Clocks
import com.example.guardian.watch.core.QuietAfterResponse
import com.example.guardian.watch.core.Reading
import com.example.guardian.watch.core.WatchAction
import com.example.guardian.watch.core.WatchEngine
import com.example.guardian.watch.core.WristResponseDetector
import com.example.guardian.watch.net.SyncRunner
import com.example.guardian.watch.sensor.AndroidHsClient
import com.example.guardian.watch.sensor.HealthServicesSource
import com.example.guardian.watch.sensor.SampleSink
import com.example.guardian.watch.sensor.SensorSdkProvider
import com.example.guardian.watch.sensor.SensorSource
import com.example.guardian.watch.text.WatchText
import com.example.guardian.watch.text.WatchTexts
import com.example.guardian.watch.ui.AlertActivity
import com.example.guardian.watch.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ALERT_START 실행 순서 — 출력(진동 · 소리) → 손목 응답 듣기 → 경보 상태 싣기 → 경보 화면 · 알림.
 * 앞의 둘이 예외를 내도 상태 · 화면(「괜찮아요」 버튼)은 반드시 띄운다 — 지시는 이미 처리됨이라 다시 오지 않는다(코드 리뷰 11-08 중4).
 */
internal fun startAlertSteps(outputs: () -> Unit, accel: () -> Unit, publish: () -> Unit, show: () -> Unit) {
    Alerter.step("경보 출력", outputs)
    Alerter.step("손목 응답 듣기", accel)
    publish()
    show()
}

/**
 * 중계 401(짝 끝남 · 토큰 만료) — 이 기기 토큰을 지우고, 경보와 진행 중인 테스트 시나리오를 끝내 실제 출처로 돌아가고,
 * 기기 경보를 멈출 동작을 돌려준다(코드 리뷰 11-08 하). 서비스와 시험이 같은 함수를 지나간다.
 */
internal fun pairEndedSteps(engine: WatchEngine?, nowMono: Long, clearPair: () -> Unit): List<WatchAction> {
    clearPair()
    engine?.let { synchronized(it) { it.clearAlert(); it.stopScenario(nowMono) } }
    return listOf(WatchAction.StopAlert(AlertStopReason.PAIR_END))
}

/**
 * 감시를 끈 뒤 도착한 sync 결과는 버린다(코드 리뷰 11-09 워치 하). 끄는 순간 응답을 기다리던 HTTP 는 interrupt 로 멈추지 않아
 * 그 응답에 실린 경보 시작이 end() 의 경보 멈춤 뒤에 돌 수 있다. 결과를 낸 엔진([from])이 지금 엔진([current])이 아니면
 * (꺼짐 = null, 다시 켬 = 다른 엔진) 아무 동작도 하지 않는다 — 경보는 end() 가 이미 멈췄다.
 */
internal fun liveActions(current: WatchEngine?, from: WatchEngine, actions: List<WatchAction>): List<WatchAction> =
    if (current === from) actions else emptyList()

/**
 * 워치 화면 「멈추기」 — 시나리오를 끝낸 뒤 바로 중계 sync([sync])와 Data Layer 보내기([publish], tick)를 한 번씩 한다(합동 점검 C-1).
 * 휴대폰은 신선한 워치 값이 test=false 로 10초 넘게 이어져야 테스트 세션을 끝내므로, 다음 판정 변화나 주기를 기다리지 않고
 * 2초 안에 보이게 한다. 둘 다 멈춘 뒤의 값을 읽는다.
 */
internal fun stopTestSteps(engine: WatchEngine, nowMono: Long, sync: () -> Unit, publish: () -> Unit) {
    synchronized(engine) { engine.stopScenario(nowMono) }
    sync()
    publish()
}

/** 감시 중 Ongoing Activity 상태 글자 — 켜져 있는 동안 늘 「운전 중」(사용자 지시 2026-11-08). */
internal fun monitorOngoingStatus(): Status = Status.Builder().addTemplate(WatchText.ONGOING_DRIVING.ko).build()

object SystemClocks : Clocks {
    override fun mono(): Long = SystemClock.elapsedRealtime()
    override fun wall(): Long = System.currentTimeMillis()
}

/**
 * 실행 유지 근거.
 * - 화면이 꺼져도 판정 · 2초 sync 가 돌아야 해서 포그라운드 서비스(type health)와 부분 wake lock 을 감시 중에만 쓴다.
 * - health 유형은 FOREGROUND_SERVICE_HEALTH 와 함께 심박(BODY_SENSORS · API 36+ READ_HEART_RATE) 또는 ACTIVITY_RECOGNITION
 *   런타임 권한이 허용돼 있어야 시작된다(Android 14+). 화면(MainActivity)에서 권한을 받은 뒤 시작한다.
 * - 감시를 끄면 센서 · 네트워크 · wake lock 을 모두 놓고 서비스를 끝낸다.
 */
class MonitorService : Service() {
    private val clocks: Clocks = SystemClocks
    private lateinit var store: PairStore
    private lateinit var alerter: Alerter
    private lateinit var phoneLink: PhoneLink
    private lateinit var net: RelayNet

    /** 감시 중인 엔진 — 바꾸기(end)와 「아직 같은 엔진인지 확인 + 경보 시작 · 상태 싣기」는 [life] 안에서 한다(코드 리뷰 11-09 워치 하). */
    @Volatile private var engine: WatchEngine? = null
    private val life = Any()
    private var source: SensorSource? = null
    private var syncRunner: SyncRunner? = null
    private var tickExec: ScheduledExecutorService? = null
    private var netExec: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val syncQueued = AtomicBoolean(false)
    /** 응답 뒤 연결 증거가 10초 없으면 출력만 멈춘다(C-3). 판단 · 출력 멈춤 · 새 지시의 되돌림은 [life] 안에서 한다. */
    private val quiet = QuietAfterResponse()
    private var ticks = 0L
    private var lastVerdictName: String? = null

    // 손목 응답 — 경보 중에만 플랫폼 가속도계를 듣는다(Sensor SDK 유무와 관계없이 같은 경로)
    private val detector = WristResponseDetector()
    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val atMs = e.timestamp / 1_000_000 // elapsedRealtimeNanos 기준
            if (detector.add(atMs, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())) respond()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private var accelOn = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = PairStore(this)
        alerter = Alerter(this)
        phoneLink = PhoneLink(this)
        net = RelayNet(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP || (engine == null && (action == ACTION_RESPOND || action == ACTION_SYNC_NOW || action == ACTION_STOP_TEST))) {
            // 감시 중이 아닐 때 온 응답 · 동기화 요청은 할 일이 없다 — 포그라운드 없이 남지 않게 바로 끝낸다
            end()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_RESPOND -> respond()
            ACTION_SYNC_NOW -> requestSync()
            ACTION_STOP_TEST -> stopTest()
            else -> begin() // ACTION_START, 또는 시스템이 다시 띄운 경우(intent null)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        end()
        super.onDestroy()
    }

    private fun begin() {
        try {
            ServiceCompat.startForeground(this, NOTIFY_MONITOR, monitorNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } catch (e: RuntimeException) {
            // 백그라운드(부팅 · 업데이트 · 시스템 재시작)에서 health 유형 조건이 안 맞으면 SecurityException ·
            // ForegroundServiceStartNotAllowedException 이 난다 — 저장된 켜짐은 두고 「다시 켜기」 알림을 띄우고 끝낸다
            Log.w(TAG, "포그라운드 시작 거부 ${e::class.java.simpleName}")
            Resume.notifyOff(this)
            if (engine == null) stopSelf()
            return
        }
        Resume.clearNotice(this)
        if (engine != null) return
        PairEnd.kick(this) // 감시 시작 — 앞서 철회 때 못 끝낸 워치 연결이 있으면 다시 끝낸다(보안 감사 하2)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guardian:monitor").apply { acquire() }
        val tick = Executors.newSingleThreadScheduledExecutor().also { tickExec = it }
        netExec = Executors.newSingleThreadScheduledExecutor()

        val sdk = SensorSdkProvider.create(this, clocks) { types ->
            if (store.recordCapabilitiesOnce(types, clocks.wall())) Log.i(TAG, "Sensor SDK 첫 실행 지원 트래커 기록 $types")
        }
        val first: SensorSource = sdk ?: HealthServicesSource(AndroidHsClient(this, tick), tick, clocks::mono)
        val e = WatchEngine(first.kind, first.lowConfidence)
        e.responseSeq = store.responseSeq // 응답 번호는 재시작 · 재부팅에도 줄지 않는다(B-5)
        engine = e
        startSource(first, e)

        if (net.configured) {
            net.acquire()
            syncRunner = SyncRunner(net.api, e, clocks)
        }
        tick.scheduleWithFixedDelay({ safely(::tick) }, 0, 1, TimeUnit.SECONDS)
        // 중계 주소가 없는 빌드는 2초 sync 를 예약하지 않는다 — syncOnce 가 syncRunner 없이는 바로 돌아가 깨우기만 한다(배터리)
        if (syncRunner != null) netExec?.scheduleWithFixedDelay({ requestSync() }, 0, SYNC_EVERY_MS, TimeUnit.MILLISECONDS)
        WatchHub.update { it.copy(monitoring = true, source = e.source, lowConfidence = e.lowConfidence) }
    }

    private fun startSource(s: SensorSource, e: WatchEngine) {
        source = s
        val sink = object : SampleSink {
            override fun ppg(s: PpgSample) = synchronized(e) { e.ppg(s) }
            override fun accel(s: AccelSample) = synchronized(e) { e.accel(s) }
            override fun hr(s: HrSample) = synchronized(e) { e.hr(s) }
        }
        s.start(sink) { reason -> onSourceFailure(s, e, sink, reason) }
    }

    /**
     * 센서 출처 실패 — Sensor SDK 는 Health Services 로 바꾼다. Health Services 등록 실패는 값이 더 오지 않으므로
     * 사용자에게 알린다(「다시 켜기」 알림과 같은 방식) — 그동안 보내는 값은 `noSignal` 이다(코드 리뷰 2026-11-02 상 1).
     */
    private fun onSourceFailure(s: SensorSource, e: WatchEngine, sink: SampleSink, reason: String) {
        Log.w(TAG, "센서 출처 실패 — $reason")
        when (s.kind) {
            WatchSource.SENSOR_SDK -> tickExec?.execute { fallbackToHealthServices(e, sink) }
            WatchSource.HEALTH_SERVICES -> Resume.notifyOff(this, WatchText.SENSOR_FAILED)
            WatchSource.SIMULATION -> Unit
        }
    }

    /** Sensor SDK 가 연결 · 지원에 실패하면 Health Services 로 바꾼다(설계 §3 보완 경로). */
    private fun fallbackToHealthServices(e: WatchEngine, sink: SampleSink) {
        if (engine !== e || source?.kind != WatchSource.SENSOR_SDK) return
        source?.stop()
        val tick = tickExec ?: return
        val hs = HealthServicesSource(AndroidHsClient(this, tick), tick, clocks::mono)
        synchronized(e) { e.useRealSource(hs.kind, hs.lowConfidence) }
        source = hs
        hs.start(sink) { reason -> onSourceFailure(hs, e, sink, reason) }
    }

    private fun tick() {
        val e = engine ?: return
        val now = clocks.mono()
        (source as? HealthServicesSource)?.tick(now)
        val noSignal = source?.noSignal(now) == true // Health Services 유효 심박 10초 공백 · 등록 실패(다른 출처는 늘 false)
        val (reading, watch, alert, src, lowConf, test) = synchronized(e) {
            e.noSignal = noSignal
            val r = e.tick(now)
            val sc = e.scenario
            val ends = e.scenarioEndsAtMs
            Snap(r, e.watch(clocks), e.alert, e.source, e.lowConfidence, if (sc != null && ends != null) WatchHub.TestView(sc, ends) else null)
        }
        if (++ticks % 2 == 0L) source?.flush() // 화면이 꺼져 묶음으로 쌓인 값을 2초마다 받는다(설계 §3)
        val verdictKey = "${reading.verdict.name}/${watch.noSignal}" // 신호 없음 전환도 사건처럼 바로 알린다
        if (verdictKey != lastVerdictName) {
            lastVerdictName = verdictKey
            requestSync() // 사건 즉시
        }
        if (store.healthConsent) runCatching { phoneLink.send(Json.encode(watch.toJson(healthConsent = true)), now) { quiet.onEvidence(now) } }
        synchronized(life) {
            if (engine !== e) return // 그사이 감시를 껐다 — 낡은 경보 상태를 end() 뒤에 다시 싣지 않는다
            if (quiet.muteDue(now)) {
                // 응답했는데 10초 동안 중계 · Data Layer 어느 쪽도 닿지 않았다 — 진동 · 소리 · 음량만 멈추고 화면 · 버튼은 둔다(C-3)
                alerter.stop()
                Log.i(TAG, "응답 뒤 연결 증거 없음 — 경보 출력만 멈춤")
            }
            WatchHub.update {
                it.copy(
                    verdict = reading.verdict, source = src, lowConfidence = lowConf, noSignal = watch.noSignal,
                    alert = alert?.let(::alertView), test = test,
                )
            }
        }
    }

    private fun alertView(a: ActiveAlert) = WatchHub.AlertView(
        a.payload.stage, a.payload.level, a.test, a.payload.durationMs, a.startedAtMs, responded = a.respondedAtMs != null,
        muted = quiet.muted,
    )

    private data class Snap(
        val reading: Reading,
        val watch: Watch,
        val alert: ActiveAlert?,
        val source: WatchSource,
        val lowConfidence: Boolean,
        val test: WatchHub.TestView?,
    )

    private fun requestSync() {
        val ex = netExec ?: return
        if (syncQueued.compareAndSet(false, true)) ex.execute {
            syncQueued.set(false)
            safely(::syncOnce)
        }
    }

    private fun syncOnce() {
        val runner = syncRunner ?: return
        val token = store.token
        if (token == null) { WatchHub.update { it.copy(link = it.link.withoutToken()) }; return }
        if (!store.healthConsent) return // 건강 관련 동의가 없으면 워치 직접 경로를 쓰지 않는다(계약 §8)
        val sentAt = clocks.mono()
        when (val o = runner.runOnce(token, healthConsent = true)) {
            is SyncRunner.Outcome.Ok -> {
                quiet.onEvidence(sentAt)
                WatchHub.update { it.copy(link = WatchHub.Link.OK, lastSyncOkAtMs = clocks.mono(), viaWifi = net.onWifi) }
                apply(runner.engine, o.actions)
                if (o.acksLeft) requestSync()
            }
            SyncRunner.Outcome.Unauthorized -> {
                apply(runner.engine, pairEndedSteps(engine, clocks.mono(), store::clearPair))
                WatchHub.update { it.copy(link = WatchHub.Link.REPAIR) }
            }
            is SyncRunner.Outcome.Failed -> {
                Log.i(TAG, "sync 실패 ${o.reason}")
                WatchHub.update { it.copy(link = WatchHub.Link.RETRY) }
            }
        }
    }

    private fun apply(from: WatchEngine, actions: List<WatchAction>) = synchronized(life) {
        val live = liveActions(engine, from, actions)
        for (a in live) when (a) {
            is WatchAction.StartAlert -> startAlertSteps(
                // 새 시작 지시는 응답 뒤 출력 멈춤을 거두고 다시 울린다(C-3)
                outputs = { quiet.reset(); alerter.start(a.alert) },
                accel = { setAccel(true) },
                // 경보 화면이 열리기 전에 상태를 먼저 싣는다(화면은 경보가 없으면 바로 닫히므로)
                publish = {
                    val view = synchronized(from) { from.alert }?.let(::alertView)
                    WatchHub.update { it.copy(alert = view) }
                },
                show = { showAlert(WatchTexts.alertBadge(a.alert.stage, a.alert.level)) },
            )
            is WatchAction.StopAlert -> {
                quiet.reset()
                alerter.stop()
                setAccel(false)
                // 응답한 경보가 끝났으면 응답 확인됨(W08)을 5초 보인다
                val responded = WatchHub.state.alert?.responded == true
                WatchHub.update { it.copy(alert = null, respondedClearedAtMs = if (responded) clocks.mono() else it.respondedClearedAtMs) }
                getSystemService(NotificationManager::class.java).cancel(NOTIFY_ALERT)
            }
            is WatchAction.RunScenario -> Log.i(TAG, "테스트 시나리오 ${a.scenario} ${a.durationMs}ms")
            WatchAction.StopScenario -> Log.i(TAG, "테스트 시나리오 멈춤 지시")
        }
        if (live.isNotEmpty()) tickExec?.execute { safely(::tick) }
    }

    /** 워치 화면 「멈추기」 — 테스트 시나리오만 끝내고(감시는 계속) 바로 알린다. */
    private fun stopTest() {
        val e = engine ?: return
        stopTestSteps(e, clocks.mono(), sync = ::requestSync, publish = { tickExec?.execute { safely(::tick) } })
    }

    /** 워치 버튼 · 손목 움직임 응답 → responseAt, 즉시 sync. 경보는 ALERT_STOP 까지 그대로 둔다. */
    private fun respond() {
        val e = engine ?: return
        val now = clocks.mono()
        synchronized(e) {
            // 응답 번호 — 경보 중 누름만 1 증가, 보내기 전에 디스크까지 쓴다(B-5)
            e.respond(now, clocks.wall(), store::nextResponseSeq)
            if (e.alert != null) quiet.onRespond(now) // 경보 중 응답만 — 응답 뒤 출력 멈춤의 시작점(C-3)
        }
        requestSync()
        tickExec?.execute { safely(::tick) }
    }

    @Synchronized
    private fun setAccel(on: Boolean) {
        if (on && !WristResponseDetector.WRIST_RESPONSE_ENABLED) return // 기본은 화면 버튼만(오응답 = 놓침)
        if (on == accelOn) return
        val sm = getSystemService(SensorManager::class.java)
        if (on) {
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            detector.reset()
            accelOn = sm.registerListener(accelListener, acc, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sm.unregisterListener(accelListener)
            accelOn = false
        }
    }

    private fun showAlert(line: String) {
        val open = Intent(this, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(WatchTexts.head(Phrase.SCREEN_S1))
            .setContentText(line)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true) // Wear OS 에서 화면을 띄우는지는 실기기 [미검증]
            .setOngoing(true)
            .setLocalOnly(true) // 휴대폰으로 브리징하지 않는다
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFY_ALERT, n) }
        // 백그라운드 화면 띄우기 제한에 막힐 수 있다 — 진동 · 소리는 이와 관계없이 계속된다
        runCatching { startActivity(open) }.onFailure { Log.i(TAG, "경보 화면 직접 띄우기 실패 ${it::class.java.simpleName}") }
    }

    private fun end() {
        val e = synchronized(life) { engine.also { engine = null } }
        source?.stop(); source = null
        tickExec?.shutdownNow(); tickExec = null
        netExec?.shutdownNow(); netExec = null
        syncRunner = null
        net.release()
        alerter.stop()
        quiet.reset()
        setAccel(false)
        e?.let { synchronized(it) { it.clearAlert() } }
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFY_ALERT)
        WatchHub.update { it.copy(monitoring = false, alert = null, verdict = null, noSignal = false, test = null) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun safely(block: () -> Unit) {
        try { block() } catch (t: RuntimeException) { Log.e(TAG, "감시 루프 오류 ${t::class.java.simpleName}", t) }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MONITOR, WatchText.NOTIFICATION_MONITOR.ko, NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, WatchTexts.head(Phrase.SCREEN_S1), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun monitorNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(WatchText.NOTIFICATION_MONITOR.ko)
            .setContentIntent(pi)
            .setOngoing(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        // 시계 화면 아이콘 · 「최근 앱」 항목에 「운전 중」과 차 아이콘(Wear OS Ongoing Activity). 알림에 묶여 있어
        // end() 의 stopForeground(REMOVE) 로 알림이 지워지면 함께 사라진다. apply 는 build 전에 불러야 알림에 실린다
        OngoingActivity.Builder(applicationContext, NOTIFY_MONITOR, builder)
            .setStaticIcon(R.drawable.ic_ongoing_car)
            .setTouchIntent(pi)
            .setStatus(monitorOngoingStatus())
            .build()
            .apply(applicationContext)
        return builder.build()
    }

    companion object {
        private const val TAG = "GuardianWatch"
        private const val ACTION_START = "com.example.guardian.watch.START"
        private const val ACTION_STOP = "com.example.guardian.watch.STOP"
        private const val ACTION_RESPOND = "com.example.guardian.watch.RESPOND"
        private const val ACTION_SYNC_NOW = "com.example.guardian.watch.SYNC_NOW"
        private const val ACTION_STOP_TEST = "com.example.guardian.watch.STOP_TEST"
        private const val CHANNEL_MONITOR = "monitor"
        private const val CHANNEL_ALERT = "alert"
        private const val NOTIFY_MONITOR = 1
        private const val NOTIFY_ALERT = 2
        private const val SYNC_EVERY_MS = 2_000L

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, MonitorService::class.java).setAction(ACTION_START))
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP)) }
        fun respond(ctx: Context) { ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_RESPOND)) }
        fun stopTest(ctx: Context) { ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP_TEST)) }
        fun syncNow(ctx: Context) { ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_SYNC_NOW)) }
    }
}
