// Pleos Vehicle Light 앰비언트 어댑터 — AmbientPolicy 가 허락한 Apply · Off 만 실행한다(기본 꺼짐 상수라 지금은 호출 0, 에뮬레이터 실측 전 [미검증])
package com.example.guardian.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 좌석 구역은 능력 조회(`checkAmbientLightBrightnessColorCapabilities`)가 준 목록을 쓴다. 깜빡임 API 는 없고 반복 호출도 하지 않는다(G6).
 * 최대 밝기는 `checkAmbientLightBrightnessCapabilities` 의 구역별 최댓값이다 — 조회가 실패하면 밝기는 건드리지 않는다.
 */
class AmbientLight(private val vehicle: () -> Vehicle?) {
    private val main = Handler(Looper.getMainLooper())

    fun apply(d: AmbientDecision) = main.post {
        val light = vehicle()?.getLight() ?: run { Log.w(TAG, "Vehicle 없음 — 조명 못 바꿈"); return@post }
        runCatching {
            light.checkAmbientLightBrightnessColorCapabilities({ _, areas ->
                for (area in areas) when (d) {
                    is AmbientDecision.Apply -> on(area, d)
                    AmbientDecision.Off -> light.setAmbientLightSwitch(area, LightSwitch.OFF, { _, _ -> }, fail("끄기"))
                    else -> Unit // Rejected · Disabled 는 여기까지 오지 않는다(InCarExecutor)
                }
            }, fail("능력 조회"))
        }.onFailure { Log.w(TAG, "조명 호출 실패 ${it.javaClass.simpleName}") }
    }

    private fun on(area: SeatArea, d: AmbientDecision.Apply) {
        val light = vehicle()?.getLight() ?: return
        light.setAmbientLightSwitch(area, LightSwitch.ON, { _, _ -> }, fail("켜기"))
        light.setAmbientLightBrightnessColor(area, d.argb, { _, _ -> }, fail("색"))
        if (d.maxBrightness) {
            light.checkAmbientLightBrightnessCapabilities({ _, bounds ->
                bounds[area]?.maxValue?.let { max -> light.setAmbientLightBrightness(area, max, { _, _ -> }, fail("밝기")) }
            }, fail("밝기 범위"))
        }
    }

    private fun fail(what: String) = { e: Exception -> Log.w(TAG, "앰비언트 $what 실패 ${e.javaClass.simpleName}"); Unit }

    private companion object { const val TAG = "AmbientLight" }
}
