// Sensor SDK AAR 이 없는 debug 빌드의 생체 기록기 공급자 — 늘 null 이라 Android 가속도 · 자이로, Health Services 심박, 속도만 기록한다
package com.example.guardian.watch.probe

import android.content.Context

object SdkRecorderProvider {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, writer: BioProbeWriter, listener: SdkRecorder.Listener): SdkRecorder? = null
}
