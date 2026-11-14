// Sensor SDK AAR 이 있는 debug 빌드의 생체 기록기 공급자 — 삼성 트래커 기록기를 만든다(watch/libs/samsung-health-sensor-api.aar 이 있을 때만 컴파일)
package com.example.guardian.watch.probe

import android.content.Context

/** 감시 잠금(`SensorSdkProvider.MONITOR_READY`)과 무관하다 — 기록은 잠금을 풀기 위한 자료를 모으는 쪽이다. */
object SdkRecorderProvider {
    const val AVAILABLE = true

    fun create(context: Context, writer: BioProbeWriter, listener: SdkRecorder.Listener): SdkRecorder? =
        SamsungBioRecorder(context, writer, listener)
}
