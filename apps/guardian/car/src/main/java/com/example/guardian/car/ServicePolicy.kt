// 포그라운드 서비스를 붙잡아 둘지 정하는 순수 판단 — 짝(토큰)이 없고 흐름이 OFF · S0 일 때만 놓는다(C-7 · Pleos D-6)
package com.example.guardian.car

import com.example.guardian.shared.flow.FlowState

object ServicePolicy {
    /**
     * 서비스를 유지할지. 사건 중 401 로 토큰이 지워져도 차 안 경보 · 음성은 이어져야 하므로(`CarHub` 가 경보 상태를 유지)
     * 「토큰 없음 그리고 OFF · S0」일 때만 놓는다(app-qa 조건, 레드블루 C-7). 꺼짐이어도 짝이 있으면 휴대폰의 켬을 받아야 해 유지한다.
     */
    fun keep(hasToken: Boolean, state: FlowState): Boolean = hasToken || state !in IDLE

    private val IDLE = setOf(FlowState.OFF, FlowState.S0)
}

/**
 * 유지 판단이 「멈춤」으로 바뀌는 순간만 알린다(처음 판단이 멈춤이어도 한 번 — START_STICKY 로 다시 뜬 서비스를 놓게).
 * 시작은 알리지 않는다 — 뒤에서는 포그라운드 서비스를 시작할 수 없어 창이 보일 때 화면이 맡는다.
 */
class ServiceGate {
    enum class Change { STOP }

    private var last: Boolean? = null

    fun update(keep: Boolean): Change? {
        val prev = last
        last = keep
        return if (!keep && prev != false) Change.STOP else null
    }
}
