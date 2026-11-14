// 부팅 · 앱 업데이트 뒤 켜 둔 감시를 되살리는 수신자 — 판단은 ResumeDecision, 시작하지 못하면 「다시 켜기」 알림
package com.example.guardian.watch.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.guardian.watch.R
import com.example.guardian.watch.core.ResumeDecision
import com.example.guardian.watch.sensor.SensorSdkProvider
import com.example.guardian.watch.text.WatchText
import com.example.guardian.watch.text.WatchTexts
import com.example.guardian.watch.ui.MainActivity

/**
 * 처리 경로.
 * 1. 매니페스트 `<receiver android:name=".service.ResumeReceiver">` 가 BOOT_COMPLETED(RECEIVE_BOOT_COMPLETED 권한) ·
 *    MY_PACKAGE_REPLACED 를 받는다. 둘 다 시스템만 보내는 보호 방송이고, 그 밖의 동작은 [ResumeDecision.triggerOf] 가 null 로 버린다.
 *    LOCKED_BOOT_COMPLETED 는 쓰지 않는다(잠금 해제 전에는 저장값 · 토큰이 든 credential 저장소를 못 읽는다).
 * 2. [Resume.inputs] 로 저장된 켜짐 · 동의 · 토큰 · 권한 · 백그라운드 몸 센서 권한을 모아 [ResumeDecision.decide] 에 넘긴다.
 * 3. Start → [MonitorService.start](startForegroundService, health 유형). 서비스는 시작하자마자 2초 sync 를 0초 지연으로 돌려
 *    휴대폰 · 차가 곧바로 워치 상태를 받는다(기존 흐름, 새 필드 없음).
 *    Blocked(또는 시작 예외) → 저장된 켜짐은 두고 「응답 확인이 꺼졌습니다」 알림. 누르면 첫 화면이 열리고, 화면 자리(APP_OPEN)로 다시 판단한다.
 */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = ResumeDecision.triggerOf(intent.action) ?: return
        Resume.inBackground(context, trigger)
    }
}

/** 다시 켜기의 Android 쪽 — 입력 모으기 · 서비스 시작 · 알림. 판단 자체는 [ResumeDecision]. */
object Resume {
    private const val TAG = "GuardianWatch"
    private const val CHANNEL_RESUME = "resume"
    const val NOTIFY_RESUME = 3

    fun inputs(ctx: Context): ResumeDecision.Inputs {
        val store = PairStore(ctx)
        val bg = ResumeDecision.backgroundSensorPermission(Build.VERSION.SDK_INT)
        return ResumeDecision.Inputs(
            wanted = store.monitorWanted,
            consent = store.healthConsent,
            paired = store.token != null,
            permissions = missingPermissions(ctx).isEmpty(),
            backgroundSensor = bg == null || granted(ctx, bg),
        )
    }

    fun missingPermissions(ctx: Context): List<String> =
        ResumeDecision.requiredPermissions(Build.VERSION.SDK_INT, SensorSdkProvider.AVAILABLE).filterNot { granted(ctx, it) }

    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun inBackground(ctx: Context, trigger: ResumeDecision.Trigger) {
        when (val d = ResumeDecision.decide(trigger, inputs(ctx))) {
            ResumeDecision.Decision.Stay -> Unit
            ResumeDecision.Decision.Start -> try {
                MonitorService.start(ctx)
                Log.i(TAG, "감시 다시 켜기 $trigger")
            } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException 포함
                Log.w(TAG, "감시 다시 켜기 거부 $trigger ${e::class.java.simpleName}")
                notifyOff(ctx)
            } catch (e: SecurityException) {
                Log.w(TAG, "감시 다시 켜기 권한 거부 $trigger")
                notifyOff(ctx)
            }
            is ResumeDecision.Decision.Blocked -> {
                Log.i(TAG, "감시 다시 켜기 보류 $trigger ${d.missing}")
                notifyOff(ctx)
            }
        }
    }

    /**
     * 「응답 확인이 꺼졌습니다 · 눌러서 다시 켜세요」 — 누르면 첫 화면(화면 자리에서 다시 판단). 알림 권한이 없으면 조용히 실패한다.
     * [text] 를 바꿔 같은 방식으로 다른 알림도 올린다(Health Services 등록 실패 → [WatchText.SENSOR_FAILED]). 「앞 · 뒤」가 제목 · 본문.
     */
    fun notifyOff(ctx: Context, text: WatchText = WatchText.RESUME_OFF) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESUME, WatchTexts.headOf(WatchText.RESUME_OFF), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_RESUME)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(WatchTexts.headOf(text))
            .setContentText(text.ko.substringAfter(" · "))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setLocalOnly(true) // 휴대폰으로 브리징하지 않는다
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { nm.notify(NOTIFY_RESUME, n) }.onFailure { Log.i(TAG, "다시 켜기 알림 실패 ${it::class.java.simpleName}") }
    }

    fun clearNotice(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFY_RESUME)
}
