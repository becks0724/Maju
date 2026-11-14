// 앰비언트 조명 정책(G6 · 설계 §13) — 기본 꺼짐 상수, BLUE_RED_BLINK 는 늘 거부, 호박색 한 번 · 정차 뒤 백색 최대 밝기만
package com.example.guardian.car

import com.example.guardian.shared.flow.AmbientMode

/** 어댑터가 할 일. Pleos Light 호출은 [Apply] · [Off] 에서만 한다. */
sealed interface AmbientDecision {
    /** 법률 검토 전 금지 모드 — 어떤 설정에서도 실행하지 않는다. */
    data object Rejected : AmbientDecision
    /** 기능이 꺼져 있다(Vehicle Control 승인 · 법 확인 전 기본값). 조명을 건드리지 않는다. */
    data object Disabled : AmbientDecision
    data object Off : AmbientDecision
    /** [argb] 색으로 켠다. [maxBrightness] 면 능력 조회의 최대 밝기로 올린다(조회 실패면 밝기는 그대로). 깜빡이지 않는다. */
    data class Apply(val argb: Int, val maxBrightness: Boolean) : AmbientDecision
}

object AmbientPolicy {
    /** 앰비언트 제어 스위치. 법률 검토 · Pleos Vehicle Control 승인 뒤 사용자 결정으로만 바꾼다(G6). */
    const val ENABLED = false

    /** 호박색(주행 중 실내 경고 한 번). */
    const val AMBER = 0xFFFFB000.toInt()
    /** 백색(정차 뒤 구조자가 차 안을 찾기 쉽게). */
    const val WHITE = 0xFFFFFFFF.toInt()

    /** [enabled] 는 시험용 입력이다 — 앱은 늘 [ENABLED] 를 쓴다. */
    fun decide(mode: AmbientMode?, enabled: Boolean = ENABLED): AmbientDecision = when {
        mode == AmbientMode.BLUE_RED_BLINK -> AmbientDecision.Rejected
        !enabled -> AmbientDecision.Disabled
        mode == null -> AmbientDecision.Off
        mode == AmbientMode.INTERIOR_ALERT -> AmbientDecision.Apply(AMBER, maxBrightness = false)
        else -> AmbientDecision.Apply(WHITE, maxBrightness = true) // PARKED_RESCUE
    }

    /** 원문 payload 의 mode 가 금지 모드인가(대소문자 무시 — 중계와 같은 이중 방어). */
    fun isForbiddenRaw(mode: Any?): Boolean = (mode as? String)?.equals(AmbientMode.BLUE_RED_BLINK.name, ignoreCase = true) == true
}
