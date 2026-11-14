// wear.xml 의 capability 선언이 :shared DataLayer.CAPABILITY_WATCH 와 같은지 묶는 시험 — 한쪽만 바뀌면 휴대폰이 워치를 못 찾는다
package com.example.guardian.watch

import com.example.guardian.shared.protocol.DataLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DataLayerBindingTest {
    @Test fun wearXmlDeclaresSharedWatchCapability() {
        val xml = File("src/main/res/values/wear.xml")
        assertTrue("시험은 :watch 모듈 폴더에서 돈다 — ${xml.absolutePath}", xml.isFile)
        val items = Regex("<item>([^<]*)</item>").findAll(xml.readText()).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf(DataLayer.CAPABILITY_WATCH), items)
    }
}
