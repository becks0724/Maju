// 차 출품 이름 시험 — 런처 이름 Maju · 머리글 읽기 이름 「마주」, 화면 문구 · strings 에 옛 이름 「아드숨 · Adsum」이 남지 않는지(2026-11-09 이름 교체)
package com.example.guardian.car

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandNameTest {
    private val old = Regex("아드숨|adsum", RegexOption.IGNORE_CASE)

    /** res/values · values-xx 폴더의 <string> 값 전부(이름 → 값). 시험 작업 폴더는 모듈 폴더다. */
    private fun resStrings(): Map<String, String> =
        File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .flatMap { it.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .flatMap { f ->
                val l = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getElementsByTagName("string")
                (0 until l.length).map { (l.item(it) as Element).let { e -> e.getAttribute("name") to e.textContent } }
            }
            .toMap()

    @Test fun launcherNameIsMaju() {
        val s = resStrings()
        assertEquals("Maju", s["app_name"])
        s.forEach { (k, v) -> assertTrue("$k: $v", !old.containsMatchIn(v)) }
    }

    @Test fun spokenBrandIsMajuAndFeatureNameStays() {
        assertEquals("마주", CarTexts.BRAND_SPOKEN)
        assertEquals("운전자 무응답 확인", CarTexts.APP_TITLE)
        CarTexts.all.forEach { assertTrue(it, !old.containsMatchIn(it)) }
    }
}
