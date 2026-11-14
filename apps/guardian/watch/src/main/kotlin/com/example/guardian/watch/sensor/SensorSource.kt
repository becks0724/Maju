// 센서 출처 인터페이스 — Sensor SDK(주) · Health Services(보완) · 시뮬레이션이 같은 샘플 형식으로 관문에 넣는다
package com.example.guardian.watch.sensor

import com.example.guardian.shared.bio.AccelSample
import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.PpgSample
import com.example.guardian.shared.protocol.WatchSource

/** 샘플을 받는 쪽. 샘플 시각(atMs)은 단조 시계(elapsedRealtime)다. 원신호는 이 경로 밖(네트워크)으로 나가지 않는다(G2). */
interface SampleSink {
    fun ppg(s: PpgSample)
    fun accel(s: AccelSample)
    fun hr(s: HrSample)
}

interface SensorSource {
    val kind: WatchSource

    /** 판정 신뢰가 낮은 출처(Health Services 심박만 — 설계 §3 「A3 만 판단」). 화면에 표시한다. */
    val lowConfidence: Boolean

    /** 측정을 시작한다. 연결 · 지원 실패는 [onFailure] 로 알린다(부른 쪽이 다른 출처로 바꾼다). */
    fun start(sink: SampleSink, onFailure: (String) -> Unit)

    /** 화면이 꺼져 묶음으로 쌓인 샘플을 바로 받는다(Sensor SDK `flush()` · Health Services 운동 세션 `flush`). 없는 출처는 아무것도 안 한다. */
    fun flush() {}

    /**
     * 지금 신호를 못 잡고 있는가(계약 §4.3 `noSignal`) — Health Services 만 참일 수 있다(유효 심박 10초 공백 · 등록 실패).
     * Sensor SDK 는 PPG 로 맥 없음을 PULSE_ABSENT 로 판정하므로 늘 false 다(여기서 참을 주면 실제 맥 소실을 숨긴다).
     */
    fun noSignal(nowMs: Long): Boolean = false

    fun stop()
}
