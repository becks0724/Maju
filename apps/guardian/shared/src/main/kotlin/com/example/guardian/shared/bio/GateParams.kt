// 신호 관문 기준값(설계 §3) — 모두 [추정], 실기기 · 차 진동 실측으로 다시 정한다
package com.example.guardian.shared.bio

data class GateParams(
    val fs: Double = 25.0,
    val bandLowHz: Double = 0.5,
    val bandHighHz: Double = 4.0,

    // A1 진폭 급감 — 3초 창 AC ≤ 기준의 10% 가 3창 연속, 기준 = 맥이 있던 직전 30초(10창) AC 중앙값
    val acWindowMs: Long = 3_000,
    val acDropRatio: Double = 0.10,
    val acDropWindows: Int = 3,
    val baselineWindows: Int = 10,
    val baselineMinWindows: Int = 5,

    // A2 손목 정지 — 가속도 크기 10초 표준편차 < θ(m/s²). 주행 중 θ_drive · 정차 중 θ_park
    val stillWindowMs: Long = 10_000,
    val thetaDrive: Double = 0.35,
    val thetaPark: Double = 0.05,

    // A3 박동 공백 — 상태 정상인데 유효 IBI 없음 4초 이상, 또는 심박 < 40 이 10초 이상
    val beatGapMs: Long = 4_000,
    val bradyBpm: Int = 40,
    val bradyMs: Long = 10_000,
    /**
     * 느린 맥 이탈 문턱. 기본값은 진입 문턱([bradyBpm])과 같아 40 이상 정상 표본 하나로 바로 지운다(Sensor SDK 경로, 지금 동작).
     * 더 높이면(Health Services 경로는 45) 「이 문턱 이상 [bradyExitHoldMs] 또는 [bradyBpm] 이상 [bradyBandClearMs]」 이어져야 지운다 —
     * 38↔41 흔들림이 서맥을 계속 지우지 않게(합동 점검 B-1, be-qa 실행 — 거짓 W 1초 13 · 5초 묶음 128/3000, 서맥 놓침 0).
     * 「이탈 문턱 이상」 시계는 40 미만이 와야 다시 시작한다(40~44 는 끊지 않음).
     */
    val bradyExitBpm: Int = 40,
    val bradyExitHoldMs: Long = 3_000,
    val bradyBandClearMs: Long = 5_000,

    // B 두 파장 확인 — 10초 창, 40–220BPM 대역 SNR < θ_snr 그리고 자기상관 최대(0.27–1.5초) < θ_ac
    val bWindowMs: Long = 10_000,
    val pulseLowBpm: Double = 40.0,
    val pulseHighBpm: Double = 220.0,
    // 합성 신호 기준 — 대역통과 잡음 SNR 최대 0.84 · 자기상관 최대 0.58, 맥박(잡음 섞임) SNR 최소 2.8 · 자기상관 최소 0.79
    val thetaSnr: Double = 1.5,
    val thetaAc: Double = 0.7,
    val acLagMinS: Double = 0.27,
    val acLagMaxS: Double = 1.5,

    // DC 급변(센서 자동 이득) → 기준 재설정, 안정 시간 동안 A1 판정 안 함
    val dcJumpRatio: Double = 0.3,
    val settleMs: Long = 3_000,

    // 느슨함 의심 — 신호 약함(−8 · −10)이면서 적색 DC 가 정상 착용 기준에서 40% 넘게 벗어남
    val looseRedRatio: Double = 0.4,

    // 심박 샘플이 이보다 오래되면 상태값을 모르는 것으로 본다
    val hrStaleMs: Long = 3_000,

    // 창에 기대 샘플 수의 이 비율 이상이 있어야 계산한다
    val minCoverage: Double = 0.8,
)
