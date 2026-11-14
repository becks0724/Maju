// 워치 테마 — 설계 spec §7-2 Pleos 어두운 토큰(늘 검정 바탕 #000000, WO-V13)을 Wear Material 3 색 체계로 감싼다
package com.example.guardian.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** spec §7-2 · §8-2 [11-01 Pleos 양식] 값 그대로. :shared 로 옮길지는 main 결정(spec §11-7 PleosPalette). */
object WatchColors {
    val Bg = Color(0xFF000000) // alarmBg(워치)
    val Surface = Color(0xFF1D1E21) // alarmSurface — Pleos 어두움 basic_50, 목록 줄 · 고르개 칸
    val Fg = Color(0xFFFFFFFF) // text1
    val Fg2 = Color.White.copy(alpha = 0.84f) // text2
    val Fg3 = Color.White.copy(alpha = 0.64f) // text3
    val Hair = Color(0xFF313236) // alarmLine — 고리 남은 부분
    val Filled = Color(0xFFE0E1E6) // watchFilled — Pleos 어두움 button_filled, 평소 버튼
    val OnFilled = Color(0xFF131417) // 채운 버튼 · 흰 버튼 · 단계 글리프 위 글자
    val Stage1 = Color(0xFFFFC224) // alarmStage1 — 경보 1(regulation_yellow)
    val Stage2 = Color(0xFFFF8A00) // alarmStage2 — 경보 2(regulation_orange)
    val Stage3 = Color(0xFFFF4339) // alarmStage3 — 응답 없음(regulation_red 어두움)
    val Ok = Color(0xFF0082FF) // informative_positive 어두움 — 연결됨 ● · 응답 확인됨 원
    val Lost = Color(0xFFFF4C28) // informative_negative 어두움 — ▲ 글리프 전용, 글자는 Fg2
    val AlarmButton = Color(0xFFFFFFFF) // 「괜찮아요」 흰 채움 + OnFilled 글자
    val Basic = Color(0xFF313236) // alarmBasic — 「끄기」 · 「동의 철회」 알약 + Fg 글자
    val TestInk = Color(0xFF7DD3D8)
    val TestTint = Color(0xFF1C3537)

    // 옛 이름 — debug GPS 측정 화면(src/debug)만 쓴다. 그 화면은 이번 양식 변경 범위 밖이라 별칭으로 둔다
    val Mute = Fg3
    val Caution = Stage1
    val Primary = Filled
}

@Composable
fun GuardianWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = WatchColors.Filled,
            onPrimary = WatchColors.OnFilled,
            background = WatchColors.Bg,
            onBackground = WatchColors.Fg,
            surfaceContainer = WatchColors.Surface,
            onSurface = WatchColors.Fg,
            onSurfaceVariant = WatchColors.Fg2,
        ),
        content = content,
    )
}
