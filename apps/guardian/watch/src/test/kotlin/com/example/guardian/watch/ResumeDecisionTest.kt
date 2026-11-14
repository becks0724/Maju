// 감시 다시 켜기 판단 시험 — 저장된 켜짐 × 동의 × 짝 × 권한 × 백그라운드 권한 × 시작 자리 전 조합, 방송 동작 대응, 권한 목록, 매니페스트 등록
package com.example.guardian.watch

import com.example.guardian.watch.core.ResumeDecision
import com.example.guardian.watch.core.ResumeDecision.Decision
import com.example.guardian.watch.core.ResumeDecision.Inputs
import com.example.guardian.watch.core.ResumeDecision.Missing
import com.example.guardian.watch.core.ResumeDecision.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResumeDecisionTest {
    private val all = Inputs(wanted = true, consent = true, paired = true, permissions = true, backgroundSensor = true)

    /** 다섯 입력 × 세 시작 자리 = 96 조합을 모두 돈다. 기대값은 지시문 조건을 그대로 옮긴 것. */
    @Test fun everyCombination() {
        val bools = listOf(false, true)
        var n = 0
        for (t in Trigger.entries) for (w in bools) for (c in bools) for (p in bools) for (perm in bools) for (bg in bools) {
            val i = Inputs(w, c, p, perm, bg)
            val d = ResumeDecision.decide(t, i)
            n++
            when {
                !w -> assertEquals("$t $i", Decision.Stay, d)
                c && p && perm && (t == Trigger.APP_OPEN || bg) -> assertEquals("$t $i", Decision.Start, d)
                else -> {
                    val expected = buildSet {
                        if (!c) add(Missing.CONSENT)
                        if (!p) add(Missing.PAIR)
                        if (!perm) add(Missing.PERMISSION)
                        if (t != Trigger.APP_OPEN && !bg) add(Missing.BACKGROUND_PERMISSION)
                    }
                    assertEquals("$t $i", Decision.Blocked(expected), d)
                }
            }
        }
        assertEquals(96, n)
    }

    @Test fun notWantedNeverStartsEvenWithEverything() {
        for (t in Trigger.entries) assertEquals(Decision.Stay, ResumeDecision.decide(t, all.copy(wanted = false)))
    }

    @Test fun backgroundNeedsBackgroundSensorButAppOpenDoesNot() {
        val noBg = all.copy(backgroundSensor = false)
        assertEquals(Decision.Blocked(setOf(Missing.BACKGROUND_PERMISSION)), ResumeDecision.decide(Trigger.BOOT, noBg))
        assertEquals(Decision.Blocked(setOf(Missing.BACKGROUND_PERMISSION)), ResumeDecision.decide(Trigger.PACKAGE_REPLACED, noBg))
        assertEquals(Decision.Start, ResumeDecision.decide(Trigger.APP_OPEN, noBg))
    }

    @Test fun missingPairIsReportedAlone() {
        assertEquals(Decision.Blocked(setOf(Missing.PAIR)), ResumeDecision.decide(Trigger.APP_OPEN, all.copy(paired = false)))
    }

    @Test fun onlyBootAndPackageReplacedAreHandled() {
        assertEquals(Trigger.BOOT, ResumeDecision.triggerOf("android.intent.action.BOOT_COMPLETED"))
        assertEquals(Trigger.PACKAGE_REPLACED, ResumeDecision.triggerOf("android.intent.action.MY_PACKAGE_REPLACED"))
        assertNull(ResumeDecision.triggerOf("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertNull(ResumeDecision.triggerOf("android.intent.action.PACKAGE_REPLACED"))
        assertNull(ResumeDecision.triggerOf(null))
    }

    @Test fun requiredPermissionsByApi() {
        assertEquals(
            listOf(ResumeDecision.BODY_SENSORS, ResumeDecision.ACTIVITY_RECOGNITION),
            ResumeDecision.requiredPermissions(35, sensorSdk = true),
        )
        assertEquals(
            listOf(ResumeDecision.HEALTH_READ_HEART_RATE, ResumeDecision.ACTIVITY_RECOGNITION),
            ResumeDecision.requiredPermissions(36, sensorSdk = false),
        )
        assertEquals(
            listOf(ResumeDecision.HEALTH_READ_HEART_RATE, ResumeDecision.SAMSUNG_READ_ADDITIONAL_HEALTH_DATA, ResumeDecision.ACTIVITY_RECOGNITION),
            ResumeDecision.requiredPermissions(37, sensorSdk = true),
        )
    }

    @Test fun backgroundSensorPermissionByApi() {
        assertNull(ResumeDecision.backgroundSensorPermission(30))
        assertNull(ResumeDecision.backgroundSensorPermission(32))
        assertEquals(ResumeDecision.BODY_SENSORS_BACKGROUND, ResumeDecision.backgroundSensorPermission(33))
        assertEquals(ResumeDecision.BODY_SENSORS_BACKGROUND, ResumeDecision.backgroundSensorPermission(35))
        assertEquals(ResumeDecision.HEALTH_READ_IN_BACKGROUND, ResumeDecision.backgroundSensorPermission(36))
    }

    /** 매니페스트 — 수신자가 두 방송을 받고, 부팅 권한이 있고, LOCKED_BOOT_COMPLETED 는 받지 않으며, 백그라운드 권한은 API 36 것만 선언한다. */
    @Test fun manifestRegistersReceiver() {
        val xml = File("src/main/AndroidManifest.xml")
        assertTrue("시험은 :watch 모듈 폴더에서 돈다 — ${xml.absolutePath}", xml.isFile)
        val text = xml.readText()
        assertTrue(text.contains("<uses-permission android:name=\"android.permission.RECEIVE_BOOT_COMPLETED\" />"))
        val receiver = Regex("<receiver\\s+android:name=\"\\.service\\.ResumeReceiver\"[\\s\\S]*?</receiver>").find(text)?.value
        assertTrue("ResumeReceiver 등록 없음", receiver != null)
        val actions = Regex("<action android:name=\"([^\"]+)\"").findAll(receiver!!).map { it.groupValues[1] }.toSet()
        assertEquals(setOf(ResumeDecision.ACTION_BOOT_COMPLETED, ResumeDecision.ACTION_MY_PACKAGE_REPLACED), actions)
        actions.forEach { assertTrue(it, ResumeDecision.triggerOf(it) != null) }
        assertFalse(text.contains("LOCKED_BOOT_COMPLETED"))
        assertTrue(text.contains(ResumeDecision.HEALTH_READ_IN_BACKGROUND))
        assertTrue(File("src/main/kotlin/com/example/guardian/watch/service/ResumeReceiver.kt").isFile)
    }
}
