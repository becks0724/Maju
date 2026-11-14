// 경보 연결부 소스 시험 — 서비스의 ALERT_START 가 감싼 순서(startAlertSteps)를 거치고, 경보 끄기의 진동 끄기 · 음량 복원이 step 으로 감싸져 있는지 본다
package com.example.guardian.watch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlertWiringTest {
    private fun src(path: String) = File(path).also { assertTrue("시험은 :watch 모듈 폴더에서 돈다 — ${it.absolutePath}", it.isFile) }.readText()

    @Test fun serviceStartAlertGoesThroughSteps() {
        val text = src("src/main/kotlin/com/example/guardian/watch/service/MonitorService.kt")
        assertTrue("StartAlert 갈래가 startAlertSteps 를 거치지 않는다", text.contains("is WatchAction.StartAlert -> startAlertSteps("))
    }

    @Test fun stopOutputsWrapsVibratorAndVolume() {
        val text = src("src/main/kotlin/com/example/guardian/watch/service/Alerter.kt")
        val stop = Regex("""private fun stopOutputs\(\) \{[\s\S]*?\n    \}""").find(text)?.value
        assertTrue("stopOutputs 없음", stop != null)
        assertTrue("진동 끄기를 step 으로 감싸야 한다", Regex("""step\("[^"]*"\) \{ vibrator\?\.cancel\(\) \}""").containsMatchIn(stop!!))
        assertTrue("음량 복원을 step 으로 감싸야 한다", Regex("""step\("[^"]*"\) \{ audio\.setStreamVolume\(""").containsMatchIn(stop))
    }
}
