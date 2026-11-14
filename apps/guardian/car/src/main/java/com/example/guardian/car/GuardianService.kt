// 감시 중 화면이 가려져도 sync · 판정 · 차 안 경보가 멈추지 않게 프로세스를 붙잡는 specialUse 포그라운드 서비스(Prenim GuidanceService 와 같은 방식)
package com.example.guardian.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class GuardianService : Service() {
    override fun onCreate() {
        super.onCreate()
        CarController.get(this) // 화면 없이 서비스만 남아도 sync 루프가 돈다
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 로 시작됐으면 먼저 startForeground 를 부른다(안 부르면 시스템이 죽인다)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notification())
        }.onFailure { e ->
            Log.w(TAG, "포그라운드 시작 실패 ${e.javaClass.simpleName} — 서비스 끔")
            stopSelf()
        }
        // 시스템이 프로세스를 죽였으면 다시 띄운다(휴대폰과 같음, 11-02 남은 하) — 사건 중 차가 사라져 양쪽에서 사건이 끊기지 않게.
        // 다시 띄울 때 포그라운드 시작이 거부되면 위에서 끈다. 부팅 · 업데이트 뒤는 ResumeReceiver 가 맡는다
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 알림 센터 한 줄 — 중요도 낮음(헤드업 없음), 서비스 분류(내비 분류는 차량 UI 가 헤드업으로 띄운다 — Prenim 실측). */
    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, CarTexts.NOTIFICATION_CHANNEL, NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(CarTexts.NOTIFICATION_TITLE)
            .setContentText(CarTexts.NOTIFICATION_TEXT)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "GuardianService"
        private const val CHANNEL_ID = "guardian_watch"
        private const val NOTIFICATION_ID = 1

        /**
         * 창이 보일 때, 또는 부팅 · 업데이트 방송 수신자([ResumeReceiver] — 백그라운드 시작 제한의 예외)에서만 부른다.
         * 그 밖의 뒤에서 부르면 시스템이 거부한다(여기서 받아 기록만 한다).
         */
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, GuardianService::class.java)) }
                .onFailure { Log.w(TAG, "서비스 시작 실패 ${it.javaClass.simpleName}") }
        }

        /** 짝 없음 + OFF · S0 일 때([ServicePolicy]) 놓는다. 창이 보이는 동안은 화면이 프로세스를 붙잡는다. */
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, GuardianService::class.java)) }
                .onSuccess { if (it) Log.i(TAG, "짝 없음 · 꺼짐 — 서비스 놓음") }
                .onFailure { Log.w(TAG, "서비스 멈춤 실패 ${it.javaClass.simpleName}") }
        }
    }
}
