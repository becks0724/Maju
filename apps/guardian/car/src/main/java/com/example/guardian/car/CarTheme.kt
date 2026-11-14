// 차 화면 Pleos 양식 토큰 — 밝음 · 어두움 색(PleosColors), 늘 어두운 경보 색(AlarmColors), 글자 단계(car-spec 6절 · design-system 2~3절)
package com.example.guardian.car

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** 상태 화면 색 — 시스템 밤 모드(Pleos 낮 · 밤 자동 전환)를 따른다. 반투명 글자는 Pleos 의 static 84 · 64 · 32% 그대로. */
@Immutable
data class PleosColors(
    val bg: Color,
    val surface: Color,
    val line: Color,
    val popup: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val text4: Color,
    val ok: Color,
    val lost: Color,
    val inverse: Color,
    val onInverse: Color,
    val basic: Color,
    val disabled: Color,
    val testInk: Color,
    val testTint: Color,
) {
    companion object {
        private val DARK_INK = Color(0xFF131417)

        val LIGHT = PleosColors(
            bg = Color(0xFFF7F8FA), surface = Color.White, line = Color(0xFFEDEEF2), popup = Color.White,
            text1 = DARK_INK, text2 = DARK_INK.copy(alpha = 0.84f), text3 = DARK_INK.copy(alpha = 0.64f), text4 = DARK_INK.copy(alpha = 0.32f),
            ok = Color(0xFF0064FF), lost = Color(0xFFFE3D16), inverse = Color(0xFF44464E), onInverse = Color.White,
            basic = Color(0xFFEDEEF2), disabled = DARK_INK.copy(alpha = 0.05f), testInk = Color(0xFF0B6B70), testTint = Color(0xFFE2F3F3),
        )

        val DARK = PleosColors(
            bg = Color(0xFF1D1E21), surface = DARK_INK, line = Color(0xFF313236), popup = Color(0xFF313236),
            text1 = Color.White, text2 = Color.White.copy(alpha = 0.84f), text3 = Color.White.copy(alpha = 0.64f), text4 = Color.White.copy(alpha = 0.32f),
            ok = Color(0xFF0082FF), lost = Color(0xFFFF4C28), inverse = Color(0xFFE0E1E6), onInverse = DARK_INK,
            basic = Color(0xFF313236), disabled = Color.White.copy(alpha = 0.08f), testInk = Color(0xFF7DD3D8), testTint = Color(0xFF1C3537),
        )
    }
}

/** 경보 화면 색 — 테마와 무관하게 늘 어둡다. 「괜찮아요」는 흰 채운 버튼(Pleos 반전 방식). */
object AlarmColors {
    val bg = Color(0xFF131417)
    val text = Color.White
    val button = Color.White
    val onButton = Color(0xFF131417)
    val testInk = Color(0xFF7DD3D8)
    val testTint = Color(0xFF1C3537)
    /** 확인 창 뒤 덮개 — Pleos static_dark 64%. */
    val scrim = Color(0xFF131417).copy(alpha = 0.64f)
}

/** Pleos 글자 단계(sp, 밀도 1.0 가정 — car-spec 6-2). */
object CarType {
    const val HEADLINE_L = 60
    const val HEADLINE_M = 56
    const val HEADLINE_S = 48
    const val TITLE_M = 36
    const val TITLE_S = 32
    const val BODY_L = 30
    const val BODY_S = 26
    const val LABEL_M = 24
}
