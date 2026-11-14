// 홈 서버 줄 아래 짝짓기 진입 버튼의 표시 조건 — 짝이 끝났거나(EXPIRED) 없을 때(NOT_PAIRED) 보이고, 주행 중에는 누를 수 없다(G5)
package com.example.guardian.phone.ui

object PairEntry {
    /** 버튼 문구. null 이면 버튼을 숨긴다. */
    fun label(server: ServerLink): String? = when (server) {
        ServerLink.EXPIRED -> PhoneTexts.RECONNECT
        ServerLink.NOT_PAIRED -> PhoneTexts.PAIR_TITLE
        ServerLink.OK, ServerLink.FAILED, ServerLink.NO_RELAY -> null
    }

    fun enabled(driving: Boolean): Boolean = !driving
}
