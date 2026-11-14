// 홈 짝짓기 진입 버튼 표시 조건 시험 — 짝 없음 · 짝 끝남일 때만 보이고 주행 중에는 비활성, 켜짐 부제는 연결을 단정하지 않는다
package com.example.guardian.phone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairEntryTest {
    @Test fun notPairedShowsPairEntry() {
        assertEquals(PhoneTexts.PAIR_TITLE, PairEntry.label(ServerLink.NOT_PAIRED))
    }

    @Test fun expiredKeepsReconnect() {
        assertEquals(PhoneTexts.RECONNECT, PairEntry.label(ServerLink.EXPIRED))
    }

    @Test fun otherStatesHideEntry() {
        for (s in listOf(ServerLink.OK, ServerLink.FAILED, ServerLink.NO_RELAY)) assertNull(s.name, PairEntry.label(s))
    }

    @Test fun drivingDisablesEntry() {
        assertFalse(PairEntry.enabled(driving = true))
        assertTrue(PairEntry.enabled(driving = false))
    }

    @Test fun homeOnSubDoesNotClaimConnection() {
        assertFalse(PhoneTexts.HOME_ON_SUB.contains("연결되어 있습니다"))
    }
}
