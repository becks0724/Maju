// 등록 정보 · 보호자 번호 규칙 시험 — 특수번호(119 등)는 보호자 번호가 될 수 없다
package com.example.guardian.phone.core

import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationTest {
    @Test fun guardianNumberRule() {
        assertTrue(PhoneNumbers.isGuardianNumber("010-0000-0003"))
        assertTrue(PhoneNumbers.isGuardianNumber("0110000000"))
        for (n in listOf("119", "112", "911", "1199", "02-123-4567", "", "010-123")) assertFalse(n, PhoneNumbers.isGuardianNumber(n))
    }

    @Test fun completeNeedsGuardian() {
        assertTrue(fullRegistration().complete)
        assertFalse(fullRegistration().copy(guardianName = "").complete)
        assertFalse(fullRegistration().copy(guardianPhone = "119").complete)
        assertNull(fullRegistration().copy(birthDate = null).profile())
    }

    @Test fun turnOnNeedsAllThreeConsents() {
        assertTrue(fullRegistration().canTurnOn)
        assertFalse(fullRegistration(consents = Consents(true, false, true)).canTurnOn)
        assertFalse(Registration().canTurnOn) // 기본 꺼짐 · 미등록
    }
}
