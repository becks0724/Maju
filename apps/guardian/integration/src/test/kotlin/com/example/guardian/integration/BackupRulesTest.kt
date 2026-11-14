// 세 앱 매니페스트가 백업을 끄고 클라우드 백업 · 기기 이전에서 모든 저장 영역을 빼는지 원본 xml 로 확인하는 시험
package com.example.guardian.integration

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class BackupRulesTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val allDomains = setOf("sharedpref", "database", "file")

    // Gradle 시험의 작업 디렉터리는 :integration 모듈 폴더다
    private val root = File("..")
    private val apps = listOf("phone", "watch", "car")

    private fun parse(file: File): Document {
        assertTrue("$file 이 없다", file.isFile)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(file)
    }

    private fun resXml(app: String, name: String) = File(root, "$app/src/main/res/xml/$name.xml")

    // 부모 요소 바로 아래 exclude 가 path="." 로 세 영역을 모두 덮는지
    private fun assertExcludesAll(where: String, parent: Element) {
        val excludes = parent.getElementsByTagName("exclude")
        val domains = (0 until excludes.length).map { excludes.item(it) as Element }
            .onEach { assertEquals("$where exclude path", ".", it.getAttribute("path")) }
            .map { it.getAttribute("domain") }
            .toSet()
        assertEquals("$where 제외 영역", allDomains, domains)
        assertEquals("$where include 없음", 0, parent.getElementsByTagName("include").length)
    }

    @Test
    fun 세_앱_매니페스트가_백업을_끄고_두_규칙을_가리킨다() {
        for (app in apps) {
            val doc = parse(File(root, "$app/src/main/AndroidManifest.xml"))
            val application = doc.getElementsByTagName("application").item(0) as Element
            assertEquals("$app allowBackup", "false", application.getAttributeNS(androidNs, "allowBackup"))
            assertEquals("$app fullBackupContent", "@xml/backup_rules", application.getAttributeNS(androidNs, "fullBackupContent"))
            assertEquals("$app dataExtractionRules", "@xml/data_extraction_rules", application.getAttributeNS(androidNs, "dataExtractionRules"))
        }
    }

    @Test
    fun 세_앱_규칙이_클라우드_백업과_기기_이전에서_모든_영역을_뺀다() {
        for (app in apps) {
            val full = parse(resXml(app, "backup_rules")).documentElement
            assertEquals("full-backup-content", full.tagName)
            assertExcludesAll("$app backup_rules", full)

            val extraction = parse(resXml(app, "data_extraction_rules")).documentElement
            assertEquals("data-extraction-rules", extraction.tagName)
            for (section in listOf("cloud-backup", "device-transfer")) {
                val nodes = extraction.getElementsByTagName(section)
                assertEquals("$app $section 하나", 1, nodes.length)
                assertExcludesAll("$app $section", nodes.item(0) as Element)
            }
        }
    }
}
