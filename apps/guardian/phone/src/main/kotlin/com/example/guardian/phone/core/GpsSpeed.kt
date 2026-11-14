// 휴대폰 GPS 속도를 화면 잠금용 이동 판단에 쓰기 전에 속도 정확도로 거르는 순수 함수 — 오차 큰 속도를 정차 증거로 쓰지 않는다(G5, 2026-11-01). 경보 흐름은 거르기 전 속도를 쓴다
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.VehicleState

object GpsSpeed {
    /** 이보다 오차(68% 신뢰, m/s)가 크면 「나쁜 정확도」로 본다. [미검증] — 베타 실측으로 조정한다. */
    const val SPEED_ACC_MAX_MPS = 1.0

    /** 거르기 전 속도(km/h) — 경보 흐름 · 중계용. */
    fun rawKmh(speedMps: Double?): Double? = speedMps?.let { it * KMH_PER_MPS }

    /**
     * 화면 잠금용 이동 판단에 쓸 속도(km/h). [speedMps] 없음 → null.
     * - 정확도 [accMps] 없음 · 좋음(≤ [SPEED_ACC_MAX_MPS]) → 속도 그대로.
     * - 정확도 나쁨 → 「확실히 움직임」(속도 − 오차 > [VehicleState.GPS_STOP_KMH])일 때만 속도, 그 밖에는 null(정차 증거로 쓰지 않는다).
     */
    fun usableKmh(speedMps: Double?, accMps: Double?): Double? {
        if (speedMps == null) return null
        val kmh = speedMps * KMH_PER_MPS
        if (accMps == null || accMps <= SPEED_ACC_MAX_MPS) return kmh
        return kmh.takeIf { it - accMps * KMH_PER_MPS > VehicleState.GPS_STOP_KMH }
    }

    private const val KMH_PER_MPS = 3.6
}
