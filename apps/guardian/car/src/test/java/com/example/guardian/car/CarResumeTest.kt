// 차 감시 다시 띄우기 시험 — 켜짐 · 짝 조합 판단, 받는 방송 두 가지, 매니페스트 수신자 · 부팅 권한, 서비스 START_STICKY
package com.example.guardian.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CarResumeTest {
    @Test fun startsOnlyWhenItWasOnAndPaired() {
        assertTrue(CarResume.shouldStart(wasOn = true, paired = true))
        assertFalse("끈 것은 켜지 않는다", CarResume.shouldStart(wasOn = false, paired = true))
        assertFalse("짝이 없으면 다시 짝짓기는 화면에서", CarResume.shouldStart(wasOn = true, paired = false))
    }

    @Test fun onlyBootAndPackageReplacedAreHandled() {
        assertEquals(CarResume.Trigger.BOOT, CarResume.triggerOf("android.intent.action.BOOT_COMPLETED"))
        assertEquals(CarResume.Trigger.PACKAGE_REPLACED, CarResume.triggerOf("android.intent.action.MY_PACKAGE_REPLACED"))
        assertNull(CarResume.triggerOf("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertNull(CarResume.triggerOf("android.intent.action.PACKAGE_REPLACED"))
        assertNull(CarResume.triggerOf(null))
    }

    /** 매니페스트 — 수신자가 공개 방송 두 개만 받고, 부팅 권한이 있고, 서비스는 시스템이 죽였다 다시 띄울 수 있게 START_STICKY. */
    @Test fun manifestRegistersReceiverAndServiceIsSticky() {
        val xml = File("src/main/AndroidManifest.xml")
        assertTrue("시험은 :car 모듈 폴더에서 돈다 — ${xml.absolutePath}", xml.isFile)
        val text = xml.readText()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        fun elements(parent: Element, tag: String) = parent.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }
        fun Element.androidName() = getAttribute("android:name")
        assertTrue(elements(doc.documentElement, "uses-permission").any { it.androidName() == "android.permission.RECEIVE_BOOT_COMPLETED" })
        val receiver = elements(doc.documentElement, "receiver").firstOrNull { it.androidName() == ".ResumeReceiver" }
        assertTrue("ResumeReceiver 등록 없음", receiver != null)
        val actions = elements(receiver!!, "action").map { it.androidName() }.toSet()
        assertEquals(setOf(CarResume.ACTION_BOOT_COMPLETED, CarResume.ACTION_MY_PACKAGE_REPLACED), actions)
        assertFalse(text.contains("LOCKED_BOOT_COMPLETED"))
        val service = File("src/main/java/com/example/guardian/car/GuardianService.kt").readText()
        assertTrue(service.contains("return START_STICKY"))
        assertFalse(service.contains("START_NOT_STICKY"))
    }
}
