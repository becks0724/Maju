// 차 감시 다시 띄우기 판단 — 부팅 · 앱 업데이트 방송을 받으면 켜져 있던(휴대폰 켬 · 짝 있음) 차 서비스를 되살릴지 고른다(휴대폰 ResumeDecision 과 같은 꼴)
package com.example.guardian.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 차 서비스는 창이 보일 때만 켜지고 프로세스와 함께 사라진다 — 앱 업데이트 · 재부팅 뒤 사용자가 앱을 다시 열기 전까지 sync 가 멈춰
 * 휴대폰이 주인을 맡고 차 안 경보가 없었다(코드 리뷰 11-02 남은 하). 마지막으로 본 흐름이 켜짐이고 짝(토큰)이 있으면 되살린다.
 * 받는 방송은 문서에 있는 공개 시스템 방송 두 개(BOOT_COMPLETED · MY_PACKAGE_REPLACED)뿐이다. 두 방송의 수신자는 백그라운드
 * 포그라운드 서비스 시작 제한의 예외다. AAOS(사용자 10, 헤드리스 시스템 사용자)에서 두 방송이 앱 사용자에게 오는지 · specialUse 서비스가
 * 그대로 시작되는지는 [미검증].
 */
object CarResume {
    enum class Trigger { BOOT, PACKAGE_REPLACED }

    /** 켜져 있었고([wasOn] — 마지막으로 본 흐름의 켬) 짝이 있을 때만. 짝이 없으면 다시 짝짓기는 정차 중 화면에서 한다. */
    fun shouldStart(wasOn: Boolean, paired: Boolean): Boolean = wasOn && paired

    fun triggerOf(action: String?): Trigger? = when (action) {
        ACTION_BOOT_COMPLETED -> Trigger.BOOT
        ACTION_MY_PACKAGE_REPLACED -> Trigger.PACKAGE_REPLACED
        else -> null
    }

    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
}

/** 매니페스트 `<receiver android:name=".ResumeReceiver">` — 두 방송 밖은 [CarResume.triggerOf] 가 버린다. LOCKED_BOOT_COMPLETED 는 받지 않는다(잠금 해제 전 저장값을 못 읽음). */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = CarResume.triggerOf(intent.action) ?: return
        val (wasOn, paired) = CarController.savedResumeState(context)
        if (!CarResume.shouldStart(wasOn, paired)) return
        Log.i("CarResume", "차 감시 다시 띄우기 $trigger")
        GuardianService.start(context)
    }
}
