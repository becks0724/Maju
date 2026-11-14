// wear.xml 의 capability 선언 · 수신 경로가 :shared DataLayer 상수(워치와 같은 값)와 같은지 본다
package com.example.guardian.phone.wear

import com.example.guardian.shared.protocol.DataLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DataLayerResTest {
    @Test fun capabilityMatchesShared() {
        // 단위 시험의 작업 폴더는 모듈 폴더(apps/guardian/phone)다
        val xml = File("src/main/res/values/wear.xml").readText()
        val items = Regex("<item>([^<]+)</item>").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(listOf(DataLayer.CAPABILITY_PHONE), items)
    }

    @Test fun pathMatchesShared() {
        assertEquals(DataLayer.PATH_WATCH, WatchLink.STATE_PATH)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:pathPrefix=\"${DataLayer.PATH_WATCH}\""))
    }
}
