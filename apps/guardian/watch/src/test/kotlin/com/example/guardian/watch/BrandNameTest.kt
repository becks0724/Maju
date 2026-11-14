// 워치 출품 이름 시험 — 런처 이름 Maju, 워치 문구 · strings 에 옛 이름 「아드숨 · Adsum」이 남지 않는지(2026-11-09 이름 교체)
package com.example.guardian.watch

import com.example.guardian.watch.text.WatchText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandNameTest {
    private val old = Regex("아드숨|adsum", RegexOption.IGNORE_CASE)

    /** res/values · values-xx 폴더의 <string> 값 전부(이름 → 값). 시험 작업 폴더는 모듈 폴더다. */
    private fun resStrings(): Map<String, String> =
        File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .flatMap { it.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .flatMap { Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""").findAll(it.readText()).map { m -> m.groupValues[1] to m.groupValues[2] }.toList() }
            .toMap()

    @Test fun launcherNameIsMaju() {
        val s = resStrings()
        assertEquals("Maju", s["app_name"])
        s.forEach { (k, v) -> assertTrue("$k: $v", !old.containsMatchIn(v)) }
    }

    @Test fun watchTextsHaveNoOldName() = WatchText.entries.forEach { assertTrue(it.ko, !old.containsMatchIn(it.ko)) }
}
