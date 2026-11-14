// 감시 다시 켜기 판단 시험 — 켜 둠 × 빠진 조건 조합, 방송 동작 대응, 매니페스트 수신자 · 부팅 권한 등록
package com.example.guardian.phone.core

import com.example.guardian.phone.core.ResumeDecision.Decision
import com.example.guardian.phone.core.ResumeDecision.Inputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResumeDecisionTest {
    @Test fun notWantedNeverStarts() {
        assertEquals(Decision.Stay, ResumeDecision.decide(Inputs(wanted = false, missing = emptyList())))
        assertEquals(Decision.Stay, ResumeDecision.decide(Inputs(wanted = false, missing = Missing.entries)))
    }

    @Test fun wantedAndReadyStarts() {
        assertEquals(Decision.Start, ResumeDecision.decide(Inputs(wanted = true, missing = emptyList())))
        // 표시만 하는 항목(방해 금지 · 짝)은 막지 않는다
        assertEquals(Decision.Start, ResumeDecision.decide(Inputs(wanted = true, missing = listOf(Missing.DND, Missing.PAIR))))
    }

    /** 켜기를 막는 항목 하나라도 빠지면 Blocked — 그 항목들만 알린다(저장된 켜짐은 그대로). */
    @Test fun everyBlockingItemBlocksAlone() {
        for (m in Missing.entries.filter { it.blocking }) {
            assertEquals("$m", Decision.Blocked(setOf(m)), ResumeDecision.decide(Inputs(wanted = true, missing = listOf(m, Missing.DND))))
        }
        assertEquals(
            Decision.Blocked(setOf(Missing.LOCATION, Missing.SMS)),
            ResumeDecision.decide(Inputs(wanted = true, missing = listOf(Missing.LOCATION, Missing.SMS, Missing.PAIR))),
        )
    }

    @Test fun onlyBootAndPackageReplacedAreHandled() {
        assertEquals(ResumeDecision.Trigger.BOOT, ResumeDecision.triggerOf("android.intent.action.BOOT_COMPLETED"))
        assertEquals(ResumeDecision.Trigger.PACKAGE_REPLACED, ResumeDecision.triggerOf("android.intent.action.MY_PACKAGE_REPLACED"))
        assertNull(ResumeDecision.triggerOf("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertNull(ResumeDecision.triggerOf("android.intent.action.PACKAGE_REPLACED"))
        assertNull(ResumeDecision.triggerOf(null))
    }

    /** 매니페스트 — 수신자가 두 방송을 받고, 부팅 권한이 있고, LOCKED_BOOT_COMPLETED 는 받지 않는다. */
    @Test fun manifestRegistersReceiver() {
        val xml = File("src/main/AndroidManifest.xml")
        assertTrue("시험은 :phone 모듈 폴더에서 돈다 — ${xml.absolutePath}", xml.isFile)
        val text = xml.readText()
        assertTrue(text.contains("<uses-permission android:name=\"android.permission.RECEIVE_BOOT_COMPLETED\" />"))
        val receiver = Regex("<receiver\\s+android:name=\"\\.service\\.ResumeReceiver\"[\\s\\S]*?</receiver>").find(text)?.value
        assertTrue("ResumeReceiver 등록 없음", receiver != null)
        val actions = Regex("<action android:name=\"([^\"]+)\"").findAll(receiver!!).map { it.groupValues[1] }.toSet()
        assertEquals(setOf(ResumeDecision.ACTION_BOOT_COMPLETED, ResumeDecision.ACTION_MY_PACKAGE_REPLACED), actions)
        actions.forEach { assertTrue(it, ResumeDecision.triggerOf(it) != null) }
        assertFalse(text.contains("LOCKED_BOOT_COMPLETED"))
        // 매니페스트가 가리키는 수신자 클래스가 실제로 있다(초기화하지 않고 불러오기만)
        Class.forName("com.example.guardian.phone.service.ResumeReceiver", false, javaClass.classLoader)
    }
}
