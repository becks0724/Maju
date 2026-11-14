// 착용 확인 화면(W05) 출구 시험 — 감시 중 착용 확인 화면에 가장자리 「상태 보기」가 있고, 누르면 상태(끄기 · 동의 철회) 화면이 보이는지 소스로 본다
package com.example.guardian.watch

import com.example.guardian.shared.text.ForbiddenWords
import com.example.guardian.watch.text.WatchText
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CheckWearExitTest {
    private fun src(path: String) = File(path).also { assertTrue("시험은 :watch 모듈 폴더에서 돈다 — ${it.absolutePath}", it.isFile) }.readText()

    /** 화면 — 원형 가장자리 버튼(EdgeButton, Wear 품질 WO-V16)으로 「상태 보기」를 둔다. */
    @Test fun checkWearScreenHasEdgeStatusButton() {
        val text = src("src/main/kotlin/com/example/guardian/watch/ui/Screens.kt")
        val screen = Regex("""fun CheckWearScreen\([\s\S]*?\n\s*\}\n""").find(text)?.value
        assertTrue("CheckWearScreen 없음", screen != null)
        assertTrue(screen, Regex("""edge = \{ Edge\(WatchText\.STATUS\.ko, EdgeButtonSize\.Small, onStatus\) \}""").containsMatchIn(screen!!))
    }

    /** 첫 화면 — 착용 확인 갈래가 상태 화면을 열고, 상태 화면 갈래가 그보다 앞에 있어 실제로 보인다. */
    @Test fun mainOpensStatusFromCheckWear() {
        val text = src("src/main/kotlin/com/example/guardian/watch/ui/MainActivity.kt")
        val wear = text.indexOf("ui.verdict == WristVerdict.CHECK_WEAR -> CheckWearScreen { showStatus = true }")
        val status = text.indexOf("showStatus -> {")
        assertTrue("착용 확인 화면이 상태 화면을 열지 않는다", wear >= 0)
        assertTrue("상태 화면 갈래가 착용 확인보다 뒤면 버튼을 눌러도 바뀌지 않는다", status in 0 until wear)
    }

    @Test fun edgeLabelIsClean() {
        assertTrue(ForbiddenWords.violations(WatchText.STATUS.ko).isEmpty())
    }
}
