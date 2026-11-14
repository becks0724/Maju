// 기능이 켜져 있는 동안 도는 포그라운드 서비스(위치 유형) — 2초마다 + 사건 즉시 중계 sync, 위치 · 워치 값 수집, 엔진 실행, 지시의 안드로이드 동작
package com.example.guardian.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.Handler
import com.example.guardian.phone.R
import com.example.guardian.phone.core.BackgroundLocation
import com.example.guardian.phone.core.Body119
import com.example.guardian.phone.core.LastSyncWait
import com.example.guardian.phone.core.Missing
import com.example.guardian.phone.core.Readiness
import com.example.guardian.phone.core.Notice
import com.example.guardian.phone.core.PendingSms
import com.example.guardian.phone.core.PhoneEffects
import com.example.guardian.phone.core.PhoneEngine
import com.example.guardian.phone.core.ServiceStop
import com.example.guardian.phone.data.Prefs
import com.example.guardian.phone.net.OffSender
import com.example.guardian.phone.net.PairEnder
import com.example.guardian.phone.net.RelayClient
import com.example.guardian.phone.net.RelayResult
import com.example.guardian.phone.net.SyncPacing
import com.example.guardian.phone.ui.AlarmActivity
import com.example.guardian.phone.ui.MainActivity
import com.example.guardian.phone.ui.PhoneTexts
import com.example.guardian.phone.ui.ServerLink
import com.example.guardian.phone.ui.SmsLogItem
import com.example.guardian.phone.ui.UiRules
import com.example.guardian.phone.ui.UiState
import com.example.guardian.phone.wear.WatchLink
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Watch
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

/**
 * 포그라운드 서비스 유형은 `location` 하나다 — 차 상태가 없을 때 정지 판정(GPS 속도)과 보호자 · 119 문자 위치를 위해 화면이 꺼져도
 * 위치를 계속 잡아야 해서다. 사용자가 화면에서 켰을 때만 시작한다(위치 권한 · 동의 3종 · 기능 켬).
 * 엔진과 모든 네트워크는 한 작업 스레드에서 돈다.
 */
class GuardianService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var prefs: Prefs
    private lateinit var relay: RelayClient
    private val pacing = SyncPacing()
    private lateinit var engine: PhoneEngine
    private lateinit var alarm: AlarmPlayer
    private lateinit var sms: SmsSender
    private lateinit var locator: PhoneLocator
    private var wakeLock: PowerManager.WakeLock? = null
    /** onDestroy 가 세운다 — 그 뒤 다른 스레드(워치 수신 등)가 늦게 넣은 주기가 위치를 다시 구독하지 않게 한다. */
    @Volatile private var stopping = false
    /** 철회가 맡긴 마지막 sync(지우기 전에 잡아 둔 토큰 · 그 뒤 짝 끝내기). 끄기 주기가 꺼내 [lastWait] 로 돌리고, 못 했으면 onDestroy 가 끝낸다. */
    private val lastSyncs = ConcurrentLinkedQueue<LastSyncWait>()
    @Volatile private var lastWait: LastSyncWait? = null
    /** 멈출 start id · 철회 뒤 저장 다시 지우기 판단(코드 리뷰 2026-11-09). */
    private val stop = ServiceStop()
    private val executor = Executor { handler.post(it) }

    private val loop = object : Runnable {
        override fun run() {
            cycle()
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        notifyPaired = RelayClient().configured && Prefs(this).token != null
        try {
            startForeground(NOTIFY_STATUS, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: RuntimeException) {
            // 위치 권한이 없거나, 시스템이 START_STICKY 로 백그라운드에서 다시 띄울 때 위치 유형 시작이 거부될 수 있다(Android 14 이상, [미검증]).
            // 조용히 멈추지 않고 일반 알림을 남긴다(app-qa 1차 7)
            notifyStopped(this)
            stopSelf()
            return
        }
        prefs = Prefs(this)
        relay = RelayClient()
        alarm = AlarmPlayer(this)
        locator = PhoneLocator(this)
        thread = HandlerThread("guardian-sync").apply { start() }
        handler = Handler(thread.looper)
        sms = SmsSender(this, handler)
        engine = PhoneEngine(
            store = prefs, effects = effects, newId = { UUID.randomUUID().toString() }, wallNow = System::currentTimeMillis,
            seqStartEpochMs = System.currentTimeMillis(), startMono = SystemClock.elapsedRealtime(),
            relayActive = { relay.configured && prefs.token != null },
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guardian:sync").apply { setReferenceCounted(false); acquire() }
        // 워치 값은 1초마다 올 수 있다 — 판정 · 응답이 바뀐 때만 주기를 앞당긴다(사건 즉시 sync)
        WatchLink.setListener { handler.post { if (pullWatch()) cycle() } }
        current = this
        UiState.post { UiState.running.value = true }
        PairEnder.retry(this, relay) // 앞선 철회에서 끝내지 못한 짝(감사 하2)
        handler.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stop.onStart(startId)
        if (!Prefs(this).registration.powerOn) stopSelf()
        // 백그라운드에서 되살아났고 「항상 허용」이 없으면 위치를 받지 못한다(실측 11-13) — 상태 알림을 사실대로 바꾸고, 앱 화면에서 다시 시작되면 되돌린다
        val blocked = BackgroundLocation.locationBlocked(
            BackgroundLocation.startedInBackground(intent == null, intent?.getBooleanExtra(EXTRA_FROM_RESUME, false) == true),
            Resume.permState(this).backgroundLocation,
        )
        if (::thread.isInitialized && blocked != locationBlocked) {
            locationBlocked = blocked
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFY_STATUS, statusNotification()) }
        }
        return START_STICKY
    }

    /** 상태 알림이 「위치를 받지 못하고 있습니다」인가(메인 스레드에서 쓰고, 엔진 스레드가 알림을 다시 만들 때 읽는다). */
    @Volatile private var locationBlocked = false

    /** 상태 알림이 본 짝 상태 — 중계 주소와 짝 토큰이 있는가(엔진의 relayActive 와 같은 조건). 짝이 없으면 「차 앱에 보내는 중」이라고 하지 않는다. */
    @Volatile private var notifyPaired = true

    override fun onDestroy() {
        stopping = true
        current = null
        WatchLink.setListener(null)
        if (::thread.isInitialized) {
            sms.close()
            handler.removeCallbacksAndMessages(null)
            handler.post {
                locator.stop(); locator.close() // 위치 구독 해제 · 주소 작업 스레드 끝 — ensure 와 같은 스레드에서, quitSafely 전에 실행된다
                // 철회였으면 저장을 한 번 더 지운다 — 마지막 sync 를 기다리는 사이 실행기가 지시 id · 문자 기록을 다시 썼을 수 있다(코드 리뷰 2026-11-09 하1, G2).
                // 엔진 스레드의 마지막 일이라 그 뒤 쓰는 것이 없다. 끝낼 토큰 파일(guardian_pair_end)은 다른 파일이라 남는다(철회 재시도, 계약 §3.2)
                stop.onStopped(prefs.registration.powerOn) { prefs.clearAll() }
            }
            thread.quitSafely()
        }
        // 마지막 sync 를 하지 못하고 멈췄으면 짝 끝내기만이라도 부른다(철회의 짝 끝내기를 잃지 않게)
        lastWait?.finish()
        generateSequence { lastSyncs.poll() }.forEach { it.finish() }
        if (::alarm.isInitialized) alarm.stop()
        cancelAlarmNotice() // 서비스가 멈추면 경보도 끝이다 — 전체 화면 경보 알림을 남기지 않는다(하4)
        wakeLock?.release()
        UiState.post { UiState.running.value = false; UiState.status.value = null }
        super.onDestroy()
    }

    /** 한 주기 — 위치 구독 확인, 워치 값, 엔진, sync(사건이 생기면 한 번 더 — 중계가 네트워크 오류 중이면 한 번만, [SyncPacing]). */
    private fun cycle() {
        if (stopping) return
        if (!prefs.registration.powerOn) {
            // 끄기 — 짝은 남기고, 꺼짐(켬 false)을 담은 스냅숏을 한 번 보내 차가 알게 한 뒤 멈춘다(PM 예고 · flow 늘 싣기).
            // 휴대폰이 주인이면 엔진이 먼저 상태 기계를 OFF 로 만든다 — 알람은 바로 멈추고 마지막 flow 는 경보 중이 아니다(코드 리뷰 2026-11-08 중 1)
            // 철회는 저장의 토큰을 이미 지웠다 — 넘겨받은 토큰으로 마지막 sync 를 보내고, 차가 그것을 읽은 뒤(주기마다 다시, 최대 몇 초)
            // 짝 끝내기를 부른다([LastSyncWait])
            engine.shutdown(SystemClock.elapsedRealtime())
            // 끈 상태를 화면에 바로 낸다 — 마지막 sync 를 기다리는 몇 초 동안 경보 화면 · 전체 화면 알림이 남지 않게(코드 리뷰 2026-11-09 하)
            publish()
            val w = lastWait ?: lastSyncs.poll()?.also { lastWait = it }
            // 끄기(철회 아님) — 꺼짐 sync 를 보내기 전에 남기고, 닿지 않으면 잠깐 다시 보낸 뒤 남긴 채 멈춘다(C-6 — 다음 앱 열기 · 부팅에 보낸다)
            if (w == null && !offBegun) { offBegun = true; off.begin(prefs.token != null && relay.configured) }
            val r = syncWith(prefs.token ?: w?.token, pacing.connectTimeoutMs)
            if (w != null && !w.done((r as? RelayResult.Ok)?.value as? PhoneSyncResponse,SystemClock.elapsedRealtime())) return
            if (w == null && !off.onShutdownSync(OffSender.result(r), SystemClock.elapsedRealtime())) return
            w?.finish()
            lastWait = null
            generateSequence { lastSyncs.poll() }.forEach { it.finish() } // 거듭 맡긴 것(드묾)은 짝 끝내기만
            // 마지막 sync 도중 다시 켰으면 멈추지 않는다 — 다음 주기부터 켬으로 돈다. 그 뒤 온 새 시작은 stopSelf(id) 가 멈추지 않는다(코드 리뷰 2026-11-09 하)
            val id = stop.stopId { prefs.registration.powerOn } ?: return
            stopSelf(id)
            return
        }
        // 켜져 있다 — 전달하지 못한 꺼짐(앞선 끄기 · 이번 끄기 대기 중 다시 켬)은 버린다(C-6)
        if (offBegun || !offCleared) { off.onPowerOn(); offBegun = false; offCleared = true }
        checkPerms()
        pullWatch()
        if (!stopping) locator.ensure(prefs.registration.consents.location, executor) { loc, at, motionKmh -> engine.onLocation(loc, at, motionKmh) }
        engine.tick(SystemClock.elapsedRealtime())
        pacing.cycle(::syncOnce, { engine.tick(SystemClock.elapsedRealtime()) }, engine::hasOutgoing)
        publish()
    }

    /** 끄기 전달(C-6) — 이번 끄기에서 꺼짐을 남겼는가, 켜진 뒤 남은 꺼짐을 한 번 버렸는가(엔진 스레드에서만 쓴다). */
    private val off by lazy { OffSender.delivery(this) }
    private var offBegun = false
    private var offCleared = false

    private var lastDlWatch: Watch? = null

    /** Data Layer 로 새로 온 워치 값을 엔진에 넣는다. 판정이나 응답 시각이 바뀌었으면 true. */
    private fun pullWatch(): Boolean {
        val (w, at) = WatchLink.take() ?: return false
        engine.onDataLayerWatch(w, at)
        val prev = lastDlWatch
        lastDlWatch = w
        return prev == null || prev.verdict != w.verdict || prev.responseAt != w.responseAt
    }

    /** sync 한 번. 보내지 않았으면(짝 없음 · 중계 주소 없음) null. */
    private fun syncOnce(connectTimeoutMs: Int): RelayResult<*>? = syncWith(prefs.token, connectTimeoutMs)

    private fun syncWith(token: String?, connectTimeoutMs: Int): RelayResult<*>? {
        token ?: run { setServer(ServerLink.NOT_PAIRED); return null }
        if (!relay.configured) { setServer(ServerLink.NO_RELAY); return null }
        val sent = SystemClock.elapsedRealtime()
        val req = engine.buildSync(token, sent)
        val r = relay.sync(req, prefs.registration.consents.health, connectTimeoutMs)
        when (r) {
            is RelayResult.Ok -> {
                engine.onSyncResponse(r.value, sent, SystemClock.elapsedRealtime())
                setServer(ServerLink.OK)
                if (syncFailing) PairEnder.retry(this, relay) // 중계에 다시 닿았다 — 끝내지 못한 짝을 다시 끝낸다(감사 하2)
                syncFailing = false
            }
            RelayResult.Unauthorized -> {
                prefs.token = null; prefs.pairId = null
                engine.onUnauthorized()
                setServer(ServerLink.EXPIRED)
            }
            else -> {
                engine.onSyncError(SystemClock.elapsedRealtime())
                setServer(ServerLink.FAILED)
                syncFailing = true
            }
        }
        return r
    }

    /** 직전 sync 가 실패했는가 — 다시 성공하면 「중계 다시 닿음」으로 본다(엔진 스레드에서만 쓴다). */
    private var syncFailing = false

    private fun setServer(v: ServerLink) = UiState.post { UiState.server.value = v }

    private var alarmShown = false

    /** 지금 보이는 안내 줄이 테스트 줄인가 — 실제 사고로 테스트를 끝낼 때 테스트 줄만 거둔다(엔진 스레드에서만 쓴다). */
    private var lineIsTest = false

    private fun publish() {
        val s = engine.status(SystemClock.elapsedRealtime())
        val pending = engine.executor.pending()
        UiState.post { UiState.status.value = s; UiState.pendingSms.value = pending }
        // 경보 상태에 들어서면 전체 화면 경보를 띄우고(P10 · P11 · P12), 경보가 끝나면 그 알림을 거둔다(코드 리뷰 2026-11-08 하4)
        val alarm = UiRules.alarmNotice(alarmShown, s.shownState, s.strandedAlarm)
        if (alarm && !alarmShown) showAlarm()
        if (!alarm && alarmShown) cancelAlarmNotice()
        alarmShown = alarm
        // 상태 알림 문구를 사실에 맞춘다 — 짝(검증 2차 하4) · 빠진 권한(중6) · 서버 30초 닿지 않음(하9) · 워치 경보 닿지 않음(C-7). 바뀔 때만 다시 건다
        notifyPaired = relay.configured && prefs.token != null
        notifyServerLost = s.serverUnreachable
        notifyWatchAlert = s.watchAlertUnreachable
        val body = notifyBody()
        if (body != shownNotifyBody) {
            shownNotifyBody = body
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFY_STATUS, statusNotification()) }
        }
    }

    /** 감시 중 권한을 다시 본다(중6) — 문자 · 알림 · 전체 화면 알림이 꺼지면 상태 알림이 말한다. [PERM_CHECK_CYCLES] 주기마다(엔진 스레드). */
    private fun checkPerms() {
        if (++permCycle % PERM_CHECK_CYCLES != 0) return
        val reg = prefs.registration
        notifyBlocked = PhoneTexts.serviceBlocked(Readiness.missing(reg, Resume.permState(this), paired = true, relayConfigured = false))
    }

    private var permCycle = -1
    @Volatile private var notifyBlocked: Missing? = null
    @Volatile private var notifyServerLost = false
    @Volatile private var notifyWatchAlert = false
    @Volatile private var shownNotifyBody: String? = null

    private fun notifyBody(): String =
        PhoneTexts.serviceNotifyBody(locationBlocked, notifyPaired, notifyBlocked, notifyServerLost, notifyWatchAlert)

    private fun cancelAlarmNotice() {
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFY_ALARM) }
    }

    /** 화면이 보이면 바로, 아니면 전체 화면 알림(잠금 화면 위)으로 경보 화면을 띄운다. */
    private fun showAlarm() {
        val intent = Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (UiState.foreground) { runCatching { startActivity(intent) }; return }
        val pi = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(PhoneTexts.ALARM_NOTIFY_TITLE)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFY_ALARM, n) }
    }

    // ── 화면에서 오는 요청(엔진 스레드로 넘긴다) ──

    private fun onEngine(block: (PhoneEngine) -> Unit) = handler.post { block(engine); publish() }

    private val effects = object : PhoneEffects {
        override fun startAlarm(stage: String, level: Int, test: Boolean) {
            alarm.start()
        }

        override fun stopAlarm() {
            alarm.stop()
        }

        override fun muteAlarm() {
            alarm.mute()
        }

        // 결과는 엔진 스레드에서 돌아온다 — 실행기에 알린 뒤 화면을 고친다
        override fun sendSms(to: String, body: String, done: (Boolean) -> Unit): Boolean =
            sms.send(to, body) { ok -> done(ok); if (!stopping) publish() }

        override fun guardianSmsResult(kind: String, eventId: String, sent: Boolean?) {
            if (!stop.acceptsSmsLog()) return // 철회가 비운 문자 기록에 늦게 온 결과를 다시 넣지 않는다
            val item = SmsLogItem(kind, System.currentTimeMillis(), sent, eventId)
            UiState.post { UiState.smsLog.value = (UiState.smsLog.value + item).takeLast(10) }
        }

        // 본문 전문 · 보호자 번호를 잠금 화면에 싣지 않는다 — 전문은 앱 화면(P17)에서만(보안 9)
        override fun askTestSms(pending: PendingSms) {
            alert(NOTIFY_TEST_SMS, PhoneTexts.TEST_SMS_ASK, UiRules.testSmsNotifyBody(pending), appIntent())
        }

        // 위치가 든 문자가 정해질 때마다 남는 기록 알림(보안 7 · D-8) — 시각과 「위치 포함」만, 소리 없음. 사건마다 하나씩 남도록 번호를 돌려 쓴다
        override fun locationSmsSettled(sent: Boolean?) {
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date())
            val (title, body) = UiRules.locationNotice(sent, hm)
            val n = Notification.Builder(this@GuardianService, CH_LOCATION)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(appIntent())
                .setAutoCancel(true)
                .build()
            locationNotifySeq = (locationNotifySeq + 1) % LOCATION_NOTIFY_SLOTS
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFY_LOCATION_BASE + locationNotifySeq, n) }
        }

        override fun showNoResponse(body: Body119) {
            UiState.post { UiState.noResponse.value = body; UiState.composeOpenedAt.value = null }
            showAlarm()
        }

        /** 사람이 경보 화면(P12)에서 버튼을 눌렀을 때만 불린다 — 그 화면이 보이는 중이라 바로 연다. 보내기는 사람이 한다. */
        override fun open119Compose(body: String) {
            if (Emergency119.openCompose(this@GuardianService, body)) UiState.post { UiState.composeOpenedAt.value = System.currentTimeMillis() }
        }

        override fun show119Preview(body: Body119) {
            UiState.post { UiState.preview119.value = body }
            alert(NOTIFY_119, PhoneTexts.TEST_STEP_4, PhoneTexts.PREVIEW_ONLY, appIntent())
        }

        override fun showLine(line: String, button: String?, test: Boolean) {
            lineIsTest = test
            UiState.post { UiState.line.value = line }
        }

        override fun notice(n: Notice) {
            UiState.post { UiState.notice.value = PhoneTexts.notice(n) }
        }

        // 테스트 띠 · P16 은 status.test · flowTest 로, P17 은 pendingSms 로 publish() 가 거둔다 — 여기서는 그 밖의 테스트 흔적(테스트 끝 · 실제 사고)
        override fun clearTestScreens() {
            val clearLine = lineIsTest
            lineIsTest = false
            UiState.post { UiState.preview119.value = null; if (clearLine) UiState.line.value = null }
            val nm = getSystemService(NotificationManager::class.java)
            runCatching { nm.cancel(NOTIFY_TEST_SMS); nm.cancel(NOTIFY_119) }
        }

        override fun clearTestSmsAsk() {
            runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFY_TEST_SMS) }
        }
    }

    // ── 알림 ──

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, PhoneTexts.SERVICE_NOTIFY_TITLE, NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, PhoneTexts.ALARM_NOTIFY_TITLE, NotificationManager.IMPORTANCE_HIGH))
        // 위치 제공 통보(보안 7) — 기본 중요도, 소리 · 진동 없음(운전 중 조작을 요구하지 않는다, G5)
        nm.createNotificationChannel(
            NotificationChannel(CH_LOCATION, PhoneTexts.LOCATION_SMS_CHANNEL, NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false)
            },
        )
    }

    private var locationNotifySeq = 0

    private fun appIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun statusNotification(): Notification = Notification.Builder(this, CH_STATUS)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle(PhoneTexts.SERVICE_NOTIFY_TITLE)
        .setContentText(notifyBody())
        .setStyle(Notification.BigTextStyle().bigText(notifyBody()))
        .setContentIntent(appIntent())
        .setOngoing(true)
        .build()

    private fun alert(id: Int, title: String, body: String, intent: PendingIntent) {
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    companion object {
        const val PERIOD_MS = 2_000L
        private const val CH_STATUS = "guardian_status"
        private const val CH_ALERT = "guardian_alert"
        private const val NOTIFY_STATUS = 1
        private const val NOTIFY_119 = 2
        private const val NOTIFY_TEST_SMS = 3
        private const val NOTIFY_ALARM = 4
        private const val NOTIFY_STOPPED = 5
        private const val CH_LOCATION = "guardian_location_sms"
        /** 위치 제공 통보 번호 — [NOTIFY_LOCATION_BASE] 부터 [LOCATION_NOTIFY_SLOTS] 개를 돌려 쓴다(사건당 1~2통, 앞 것을 덮지 않게). */
        private const val NOTIFY_LOCATION_BASE = 100
        private const val LOCATION_NOTIFY_SLOTS = 10
        /** 권한을 다시 보는 주기 수 — 2초 주기 5번 = 10초. */
        private const val PERM_CHECK_CYCLES = 5
        /** 부팅 · 업데이트 수신자([ResumeReceiver])에서 온 시작 — 백그라운드 시작이다. */
        private const val EXTRA_FROM_RESUME = "from_resume_receiver"

        @Volatile private var current: GuardianService? = null

        /** 시작이 거부되면(백그라운드 시작 제한 등) 「감시가 멈췄습니다」 알림을 남긴다. [fromResumeReceiver] 는 부팅 · 업데이트 수신자에서 부를 때만 true. */
        fun start(context: Context, fromResumeReceiver: Boolean = false) {
            try {
                context.startForegroundService(Intent(context, GuardianService::class.java).putExtra(EXTRA_FROM_RESUME, fromResumeReceiver))
            } catch (e: RuntimeException) {
                notifyStopped(context)
            }
        }

        /** 감시가 멈췄음을 일반 알림으로 알린다 — 누르면 앱이 열린다. */
        fun notifyStopped(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, PhoneTexts.ALARM_NOTIFY_TITLE, NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(context, CH_ALERT)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(PhoneTexts.SERVICE_STOPPED)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(NOTIFY_STOPPED, n) }
        }

        fun stop(context: Context) = context.stopService(Intent(context, GuardianService::class.java))

        /** 화면 요청을 엔진 스레드에서 실행한다. 서비스가 꺼져 있으면 아무것도 하지 않는다. */
        fun withEngine(block: (PhoneEngine) -> Unit) { current?.onEngine(block) }

        /** 설정이 바뀌었으니 주기를 한 번 바로 돌린다(끄기면 마지막 sync 뒤 멈춘다). */
        fun poke() { current?.let { s -> s.handler.post { s.cycle() } } }

        /**
         * 철회 — 켬은 이미 꺼졌고 저장의 토큰도 지웠다. 서비스가 돌고 있으면 [token] 으로 마지막 sync(꺼짐 · OFF)를 보내고 차가 읽은 뒤
         * [after] 를 엔진 스레드에서 부르고([LastSyncWait]), 서비스가 꺼져 있으면 바로 부른다. 서비스가 그 전에 멈추면 onDestroy 가 부른다.
         */
        fun stopWithLastSync(token: String?, after: () -> Unit) {
            val s = current ?: return after()
            s.stop.onWithdraw()
            s.lastSyncs += LastSyncWait(token, after)
            s.handler.post { s.cycle() }
        }
    }
}
