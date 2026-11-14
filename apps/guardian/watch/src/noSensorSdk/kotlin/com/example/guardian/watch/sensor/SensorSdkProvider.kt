// Sensor SDK AAR 이 없는 빌드의 공급자 — 늘 null 이라 Health Services · 시뮬레이션 경로만 쓴다(watch/libs 에 AAR 을 두면 src/sensorSdk 판으로 바뀐다)
package com.example.guardian.watch.sensor

import android.content.Context
import com.example.guardian.watch.core.Clocks

object SensorSdkProvider {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, clocks: Clocks, onCapabilities: (List<String>) -> Unit): SensorSource? = null
}
