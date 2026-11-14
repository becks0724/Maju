// 워치 센서 샘플 모델 — PPG(녹색 · 적외선 · 적색) · 가속도 · 심박(상태값 · IBI)과 손목 판정 결과
package com.example.guardian.shared.bio

/** PPG 한 샘플(25Hz). [status] 는 Sensor SDK PPG 상태값 그대로(0 = 정상으로 가정, [미검증]). */
data class PpgSample(val atMs: Long, val green: Double, val ir: Double, val red: Double, val status: Int = 0)

/** 가속도 한 샘플(25Hz, m/s²). */
data class AccelSample(val atMs: Long, val x: Double, val y: Double, val z: Double)

/**
 * 심박 한 샘플(1Hz). [status] 는 Sensor SDK 심박 상태값 — 1 정상 · −2 움직임 · −3 미착용 · −8 · −10 신호 약함.
 * [held] 는 어댑터가 마지막 실제 표본을 지금 시각으로 다시 실은 보류 표본이다(Health Services 묶음 사이 · 깜빡임 유예).
 * 관문은 보류 표본으로 신선도와 박동 시각만 갱신하고, 순서 시계 · 서맥 시작 · 느린 맥 지움 시간은 세지 않는다(합동 점검 B-1).
 */
data class HrSample(val atMs: Long, val bpm: Int, val status: Int, val ibiMs: List<Int> = emptyList(), val held: Boolean = false)

object HrStatus {
    const val OK = 1
    const val MOTION = -2
    const val OFF_WRIST = -3
    const val WEAK_SIGNAL = -8
    const val WEAK_SIGNAL_2 = -10
}

/** 손목 판정. 프로토콜 Watch.verdict 와 같은 이름을 쓴다. */
enum class WristVerdict { NORMAL, CHECK_WEAR, PULSE_WEAK, PULSE_ABSENT }

/** 판정 이유(앱 기록 · 시험용, 화면 문구 아님). */
enum class GateReason {
    WARMING_UP, BASELINE_RESET, OFF_WRIST, LOOSE, MOTION_HOLD,
    A1_AMPLITUDE_DROP, A2_WRIST_STILL, A3_BEAT_GAP, B_NO_PULSE_BOTH,
}

data class GateResult(
    val verdict: WristVerdict,
    val reasons: Set<GateReason>,
    val lastPulseAtMs: Long?,
    val hr: Int?,
)
