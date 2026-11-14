// debug 전용 생체 기록 — Sensor SDK 트래커 기록기의 인터페이스(실체는 AAR 유무로 갈리는 src/debugSensorSdk · src/debugNoSensorSdk 의 SdkRecorderProvider)
package com.example.guardian.watch.probe

/**
 * PPG · 가속도 · 심박 연속 트래커를 켜서 원시값을 [BioProbeWriter] 에 그대로 넘긴다. 판정 · 관문 · 경보에 연결하지 않는다(계획 11-07 핵심 결정 1).
 * 기록 서비스가 2초마다 [flush] 를 불러 화면 꺼짐 때 쌓인 묶음을 받는다.
 */
interface SdkRecorder {
    fun start()
    fun flush()
    fun stop()

    /** 기록기가 서비스에 알리는 것 — 사건 줄 · meta 줄 · 최근 심박(화면 표시) · 연결 상태 한 줄. */
    interface Listener {
        fun event(kind: String, detail: String)
        fun meta(line: String)
        fun hr(hr: Int?, status: Int?)
        fun sdkStatus(line: String)
    }
}
