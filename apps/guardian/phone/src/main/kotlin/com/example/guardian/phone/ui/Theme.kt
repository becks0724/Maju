// 휴대폰 앱 디자인 토큰(휴대폰 · 워치 화면 명세(비공개) §7, 11-01 Pleos 양식) — 시스템 테마를 따르는 Pleos 색 · 늘 어두운 경보 색 · 글자 단계 · 크기
package com.example.guardian.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** §7-1 — Pleos 토큰 이름 그대로. 우리 고유는 test* 둘뿐이다. */
data class PleosColors(
    val bg: Color, val surface: Color, val surfaceHigh: Color, val popup: Color, val line: Color,
    val text1: Color, val text2: Color, val text3: Color, val text4: Color,
    val inverse: Color, val onInverse: Color, val disabled: Color,
    val ok: Color, val lost: Color, val active: Color,
    val testInk: Color, val testTint: Color,
)

val LightColors = PleosColors(
    bg = Color(0xFFF7F8FA), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFEDEEF2), popup = Color(0xFFFFFFFF), line = Color(0xFFEDEEF2),
    text1 = Color(0xFF131417), text2 = Color(0xFF393A3C), text3 = Color(0xFF68696B), text4 = Color(0xFFB3B4B5),
    inverse = Color(0xFF44464E), onInverse = Color(0xFFFFFFFF), disabled = Color(0x0D131417),
    ok = Color(0xFF0064FF), lost = Color(0xFFFE3D16), active = Color(0xFF02C265),
    testInk = Color(0xFF0B6B70), testTint = Color(0xFFE2F3F3),
)

val DarkColors = PleosColors(
    bg = Color(0xFF1D1E21), surface = Color(0xFF131417), surfaceHigh = Color(0xFF313236), popup = Color(0xFF313236), line = Color(0xFF313236),
    text1 = Color(0xFFFFFFFF), text2 = Color(0xFFD9D9DA), text3 = Color(0xFFAAAAAB), text4 = Color(0xFF5F5F61),
    inverse = Color(0xFFE0E1E6), onInverse = Color(0xFF131417), disabled = Color(0x14FFFFFF),
    ok = Color(0xFF0082FF), lost = Color(0xFFFF4C28), active = Color(0xFF00E074),
    testInk = Color(0xFF7DD3D8), testTint = Color(0xFF1C3537),
)

/** §7-2 — 경보 화면(P10 ~ P14)은 테마와 상관없이 늘 어두움. 단계색은 Pleos 규제색. */
object AlarmColors {
    val bg = Color(0xFF131417)
    val surface = Color(0xFF1D1E21)
    val text = Color(0xFFFFFFFF)
    val text2 = Color(0xD6FFFFFF)
    val text3 = Color(0xA3FFFFFF)
    val line = Color(0xFF313236)
    val stage1 = Color(0xFFFFC224)
    val stage2 = Color(0xFFFF8A00)
    val stage3 = Color(0xFFFF4339)
    /** 「괜찮아요」 · 「119 문자 작성 화면 열기」 — 흰 채운 버튼. */
    val button = Color(0xFFFFFFFF)
    val onButton = Color(0xFF131417)
    val basic = Color(0xFF313236)
    val onBasic = Color(0xFFFFFFFF)
    val ok = Color(0xFF0082FF)
    val lost = Color(0xFFFF4C28)
}

val LocalPleosColors = staticCompositionLocalOf { LightColors }

/** §7-4 글자(Pleos 단계, sp) · §7-5 크기(dp). */
object Dims {
    val headlineL = 40.sp
    val headlineM = 34.sp
    val headlineS = 28.sp
    val titleL = 24.sp
    val titleM = 22.sp
    val titleS = 20.sp
    val bodyL = 17.sp
    val bodyS = 15.sp
    val labelM = 14.sp

    val gutter = 16.dp
    val touch = 48.dp
    val buttonH = 56.dp
    val okButtonH = 72.dp
    val composeButtonH = 64.dp
    val rowH = 56.dp
    val topBarH = 56.dp
    val testBandH = 40.dp
    val stageH = 56.dp
    val icon = 24.dp
}

/** 모서리 0 — Material 기본 둥근 모양을 쓰지 않는다(§7-5). */
private val Square: CornerBasedShape = RoundedCornerShape(CornerSize(0.dp))
private val SquareShapes = Shapes(extraSmall = Square, small = Square, medium = Square, large = Square, extraLarge = Square)

@Composable
fun GuardianTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.inverse, onPrimary = c.onInverse, background = c.bg, onBackground = c.text1,
        surface = c.surface, onSurface = c.text1, surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.text2,
        surfaceContainerHigh = c.popup, surfaceContainerHighest = c.surfaceHigh, outline = c.text3,
        outlineVariant = c.line, error = c.lost, secondaryContainer = c.surfaceHigh, onSecondaryContainer = c.text1,
    )
    CompositionLocalProvider(LocalPleosColors provides c) { MaterialTheme(colorScheme = scheme, shapes = SquareShapes, content = content) }
}
