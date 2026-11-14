// 경보 화면 액티비티 — 잠금 화면 위로 켜져 「괜찮아요」 가장자리 버튼 하나를 보이고, 경보가 끝나면(ALERT_STOP) 응답 확인됨을 5초 보인 뒤 닫힌다
package com.example.guardian.watch.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.example.guardian.watch.service.MonitorService

/**
 * - 뒤로 가기 키 · 밀어서 닫기는 응답이 아니다 — 뒤로 가기는 무시하고(BackHandler), 밀어서 닫기는 창 테마로 끈다.
 * - 화면 전체 탭은 응답이 아니다(바탕에 클릭 없음). 측면 물리 버튼도 응답으로 받지 않는다(PM 결정, 지원 여부 [미검증]).
 * - 경보 2 이상에서 창 밝기를 최대(1f)로, 경보 동안 화면을 켜 둔다(앰비언트 진입 막기). 응답 뒤 출력을 멈췄으면(C-3) 최대 밝기만 푼다.
 */
class AlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            GuardianWearTheme {
                val ui by rememberHubState()
                val now = rememberNow()
                BackHandler { /* 뒤로 가기는 응답이 아니다 — 경보 화면을 그대로 둔다 */ }
                val alert = ui.alert
                val cleared = ui.respondedClearedAtMs
                FullBrightness((alert?.level ?: 0) >= 2 && alert?.muted != true)
                when {
                    alert != null -> AlertScreen(alert, now) { MonitorService.respond(this) }
                    cleared != null && now - cleared < RESPONDED_SHOW_MS -> RespondedScreen()
                    else -> LaunchedEffect(Unit) { finish() }
                }
            }
        }
    }

    @Composable
    private fun FullBrightness(full: Boolean) {
        DisposableEffect(full) {
            val lp = window.attributes
            lp.screenBrightness = if (full) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            onDispose {
                val back = window.attributes
                back.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = back
            }
        }
    }

    companion object {
        const val RESPONDED_SHOW_MS = 5_000L
    }
}
