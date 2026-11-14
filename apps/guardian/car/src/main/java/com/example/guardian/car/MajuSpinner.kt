// 기다림 회전 로고(마주 R2) — 세 마디 테두리만 반시계로 6초에 한 바퀴, 운전자 점은 멈춤(docs/logo/motion.md)
package com.example.guardian.car

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/** 회전 상태 — 돌기, 정지 각도까지 감속해 서기, 바로 정지 로고(0°). */
enum class SpinMode { ROTATE, SETTLE, STILL }

/** 회전 규격과 판단(순수 함수, motion.md 1 · 4 · 6절). 각도는 반시계로 쌓이는 양수 값이다. */
object MajuSpin {
    const val LAP_MS = 6_000
    const val DEG_PER_S = 60f
    const val START_MS = 600
    const val START_DEG = 18f
    const val SETTLE_DEG = 60f
    const val SETTLE_MS = 2_000

    /**
     * 애니메이션 배율 0(애니메이션 삭제)이거나 주행 제한([restricted], CarUxRestrictions)이면 감속 없이 바로 정지 로고,
     * 아니면 기다리는 동안 돌고 기다림이 끝나면 감속해 선다.
     */
    fun mode(waiting: Boolean, animatorScale: Float, restricted: Boolean): SpinMode = when {
        animatorScale == 0f || restricted -> SpinMode.STILL
        waiting -> SpinMode.ROTATE
        else -> SpinMode.SETTLE
    }

    /** 멈춤 계획 — 다음 정지 각도(360° 배수)까지 남은 각에서 마지막 60°(모자라면 남은 만큼, 같은 비율의 시간)만 감속하고 앞은 등속. */
    data class Settle(val linearDeg: Float, val linearMs: Int, val easeDeg: Float, val easeMs: Int)

    fun settle(angle: Float): Settle {
        val a = ((angle % 360f) + 360f) % 360f
        val remaining = if (a == 0f) 0f else 360f - a
        val linear = (remaining - SETTLE_DEG).coerceAtLeast(0f)
        val ease = remaining - linear
        return Settle(linear, (linear / DEG_PER_S * 1000).roundToInt(), ease, (ease / SETTLE_DEG * SETTLE_MS).roundToInt())
    }
}

/**
 * 기다림 로고 — [waiting] 이 참이면 0.6초 가속 뒤 등속으로 돌고, 거짓이 되면 다음 정지 각도까지 감속해 선다.
 * [restricted](주행 제한)가 참이 되면 그 자리에서 정지 로고(0°)로 바꾼다. 화면이 바뀌면 그냥 사라진다.
 * 꾸밈 그림이라 읽지 않는다(뜻은 옆 대기 문구가 전한다).
 */
@Composable
fun MajuSpinner(waiting: Boolean, restricted: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scale = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
    val mode = MajuSpin.mode(waiting, scale, restricted)
    val angle = remember { Animatable(0f) }
    LaunchedEffect(mode) {
        when (mode) {
            SpinMode.STILL -> angle.snapTo(0f)
            SpinMode.ROTATE -> {
                angle.animateTo(angle.value + MajuSpin.START_DEG, tween(MajuSpin.START_MS, easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)))
                while (true) {
                    angle.snapTo(angle.value % 360f)
                    angle.animateTo(angle.value + 360f, tween(MajuSpin.LAP_MS, easing = LinearEasing))
                }
            }
            SpinMode.SETTLE -> {
                val p = MajuSpin.settle(angle.value)
                if (p.linearDeg > 0f) angle.animateTo(angle.value + p.linearDeg, tween(p.linearMs, easing = LinearEasing))
                if (p.easeDeg > 0f) angle.animateTo(angle.value + p.easeDeg, tween(p.easeMs, easing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)))
                angle.snapTo(0f)
            }
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_logo_ring), null, Modifier.fillMaxSize().graphicsLayer { rotationZ = -angle.value })
        Image(painterResource(R.drawable.ic_logo_dot), null, Modifier.fillMaxSize())
    }
}
