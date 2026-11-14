// 부팅 · 앱 업데이트 · 앱 열기 때 켜 둔 감시를 되살리는 수신자와 Android 쪽 동작 — 판단은 core/ResumeDecision, 시작하지 못하면 「감시가 멈췄습니다」 알림
package com.example.guardian.phone.service

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.guardian.phone.core.PermState
import com.example.guardian.phone.core.Readiness
import com.example.guardian.phone.core.ResumeDecision
import com.example.guardian.phone.data.Prefs
import com.example.guardian.phone.net.OffSender

/**
 * 매니페스트 `<receiver android:name=".service.ResumeReceiver">` 가 BOOT_COMPLETED(RECEIVE_BOOT_COMPLETED 권한) · MY_PACKAGE_REPLACED 를 받는다.
 * 둘 다 시스템만 보내는 보호 방송이고, 그 밖의 동작은 [ResumeDecision.triggerOf] 가 null 로 버린다.
 * LOCKED_BOOT_COMPLETED 는 쓰지 않는다(잠금 해제 전에는 저장값 · 토큰을 못 읽는다).
 */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = ResumeDecision.triggerOf(intent.action) ?: return
        Resume.inBackground(context, trigger)
    }
}

/** 다시 켜기의 Android 쪽 — 권한 상태 모으기 · 서비스 시작 · 알림. 판단 자체는 [ResumeDecision]. */
object Resume {
    private const val TAG = "GuardianPhone"

    /** 지금 권한 상태(홈 · P05 · 다시 켜기 공용). 이 휴대폰에서 따로 허용이 필요 없는 항목은 true(Android 13 이하 전체 화면 알림). */
    fun permState(ctx: Context): PermState {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return PermState(
            location = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            sms = granted(Manifest.permission.SEND_SMS),
            notify = nm.areNotificationsEnabled(),
            fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
            dnd = nm.isNotificationPolicyAccessGranted,
            backgroundLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        )
    }

    /** 저장된 켜짐과 빠진 조건 — 짝 · 중계 여부는 보지 않는다(중계 없이 휴대폰 혼자도 돈다). */
    fun inputs(ctx: Context, prefs: Prefs = Prefs(ctx)): ResumeDecision.Inputs {
        val reg = prefs.registration
        return ResumeDecision.Inputs(wanted = reg.powerOn, missing = Readiness.missing(reg, permState(ctx), paired = true, relayConfigured = false))
    }

    /** 부팅 · 업데이트 뒤 — Start 면 서비스 시작(시작이 거부되면 [GuardianService.start] 가 알림을 남긴다), Blocked 면 알림만. */
    fun inBackground(ctx: Context, trigger: ResumeDecision.Trigger) {
        OffSender.retry(ctx) // 꺼 둔 채 부팅 — 앞선 끄기에서 차에 전달하지 못한 꺼짐을 보낸다(C-6, 켜져 있으면 버린다)
        when (val d = ResumeDecision.decide(inputs(ctx))) {
            ResumeDecision.Decision.Stay -> Unit
            ResumeDecision.Decision.Start -> { Log.i(TAG, "감시 다시 켜기 $trigger"); GuardianService.start(ctx, fromResumeReceiver = true) }
            is ResumeDecision.Decision.Blocked -> { Log.i(TAG, "감시 다시 켜기 보류 $trigger ${d.missing}"); GuardianService.notifyStopped(ctx) }
        }
    }

    /** 앱을 열었을 때 — 켜 둔 채 프로세스가 죽었으면 되살린다. Blocked 면 홈이 빠진 조건을 보이므로 알림은 띄우지 않는다. */
    fun onAppOpen(ctx: Context, prefs: Prefs) {
        if (ResumeDecision.decide(inputs(ctx, prefs)) == ResumeDecision.Decision.Start) GuardianService.start(ctx)
    }
}
