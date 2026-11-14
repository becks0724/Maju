// Sensor SDK AAR 이 있는 빌드의 공급자 — 삼성 Sensor SDK 출처를 만든다(watch/libs/samsung-health-sensor-api.aar 이 있을 때만 컴파일). 감시 잠금 상수가 여기 있다
package com.example.guardian.watch.sensor

import android.content.Context
import com.example.guardian.watch.core.Clocks

object SensorSdkProvider {
    /**
     * 감시 잠금 — false 인 동안 AAR 이 있어도 감시는 Health Services 로 돈다. Sensor SDK 경로의 문턱은 모두 [추정]이고 운전 중 근거가 없어
     * 그대로 켜면 실주행에서 거짓 경보가 울릴 수 있다(G5). debug 「생체 기록」으로 모은 원자료를 재생해 본 뒤 사용자 결정으로 푼다(계획 11-07 핵심 결정 2).
     */
    const val MONITOR_READY = false

    const val AVAILABLE = MONITOR_READY

    fun create(context: Context, clocks: Clocks, onCapabilities: (List<String>) -> Unit): SensorSource? =
        if (MONITOR_READY) SamsungSensorSource(context, clocks, onCapabilities) else null
}
