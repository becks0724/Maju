// 휴대폰 화면 잠금 · 테스트 시작용 이동 판단(주행 · 정차 · 모름)과 「정차 중입니다」 스스로 확인 규칙 — 경보 흐름의 정차 판단(stopped)은 바꾸지 않는다
package com.example.guardian.phone.core

/**
 * 화면 잠금용 이동 판단(사용자 결정 2026-11-01).
 * - 차 연결 있음 → 차 판단, 차 연결 없음 + 휴대폰 GPS 속도 있음 → GPS 판단(둘 다 지금의 정지 판정 그대로).
 * - 차 연결도 GPS 속도도 없음 → [UNKNOWN] — 화면은 잠그지 않되 경고 띠를 보이고, 테스트는 스스로 확인한 뒤에만 시작한다.
 * - 주행 유지(G5, 2026-11-01) — 「모름」이어도 최근 [MotionRules.DRIVING_HOLD_MS] 안에 주행 판단(차 또는 GPS)이 있었으면 [DRIVING].
 *   터널 · 지하에서 GPS 가 끊기거나 속도가 빠져도 주행 중 설정 조작이 곧바로 열리지 않게 한다. 정차 판단은 유지를 끊는다.
 */
enum class Motion { DRIVING, STOPPED, UNKNOWN }

object MotionRules {
    /** 「정차 중입니다」 스스로 확인이 유효한 시간. */
    const val SELF_CONFIRM_MS = 600_000L

    /** 주행 유지 시간 — 마지막 주행 판단 뒤 이 시간 안의 「모름」은 주행으로 본다. */
    const val DRIVING_HOLD_MS = 120_000L

    /**
     * [carKnown] 차 연결이 살아 있다(LOCAL 시험의 흉내 정차 포함), [gpsKnown] 신선한 휴대폰 GPS 속도(정확도로 거른 값, [GpsSpeed])가 있다, [stopped] 지금의 정지 판정,
     * [drivingHeld] 주행 유지 중([drivingHeld] 함수). 유지는 「모름 → 주행」만 바꾼다.
     */
    fun motion(carKnown: Boolean, gpsKnown: Boolean, stopped: Boolean, drivingHeld: Boolean = false): Motion = when {
        !carKnown && !gpsKnown -> if (drivingHeld) Motion.DRIVING else Motion.UNKNOWN
        stopped -> Motion.STOPPED
        else -> Motion.DRIVING
    }

    /** 마지막 주행 판단([lastDrivingAt], 단조 ms)이 [DRIVING_HOLD_MS] 안이면 유지 중. */
    fun drivingHeld(lastDrivingAt: Long?, now: Long): Boolean =
        lastDrivingAt != null && now - lastDrivingAt in 0..DRIVING_HOLD_MS

    /** 확인은 「모름」 동안 10분까지만 유효하다. 주행이나 정차로 판단이 바뀌면 그때 풀린다(엔진이 지운다). */
    fun confirmValid(confirmedAt: Long?, motion: Motion, now: Long): Boolean =
        confirmedAt != null && motion == Motion.UNKNOWN && now - confirmedAt in 0..SELF_CONFIRM_MS

    /** 테스트 시작 — 정차, 또는 모름 + 유효한 확인. 주행이면 확인이 있어도 안 된다. */
    fun canStartTest(motion: Motion, confirmed: Boolean): Boolean =
        motion == Motion.STOPPED || (motion == Motion.UNKNOWN && confirmed)
}
