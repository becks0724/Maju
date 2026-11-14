// 끄기 · 동의 철회 시험 — MonitorOff 가 저장된 켜짐을 지운 뒤 서비스를 멈추는지, 화면의 두 경로가 그 함수만 쓰는지 본다
package com.example.guardian.watch

import com.example.guardian.watch.core.ResumeDecision
import com.example.guardian.watch.service.MonitorOff
import com.example.guardian.watch.service.PairStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MonitorOffTest {
    @Test fun clearsWantedBeforeStoppingService() {
        val store = PairStore(MemoryPrefs())
        store.setMonitorWanted(true)
        assertTrue(store.monitorWanted)
        var wantedWhenStopped: Boolean? = null
        MonitorOff.run(store) { wantedWhenStopped = store.monitorWanted }
        assertEquals("서비스를 멈출 때 이미 저장이 지워져 있어야 한다", false, wantedWhenStopped)
        assertFalse(store.monitorWanted)
        // 지운 뒤에는 부팅 · 업데이트 · 앱 열기 어느 자리에서도 다시 켜지 않는다
        val i = ResumeDecision.Inputs(store.monitorWanted, consent = true, paired = true, permissions = true, backgroundSensor = true)
        for (t in ResumeDecision.Trigger.entries) assertEquals(ResumeDecision.Decision.Stay, ResumeDecision.decide(t, i))
    }

    /** 화면 소스 검사 — 끄기 · 동의 철회가 MonitorOff 를 부르고, 저장을 거치지 않는 직접 정지 · 직접 해제가 없다. */
    @Test fun screenOffPathsGoThroughMonitorOff() {
        val src = File("src/main/kotlin/com/example/guardian/watch/ui/MainActivity.kt")
        assertTrue("시험은 :watch 모듈 폴더에서 돈다 — ${src.absolutePath}", src.isFile)
        val text = src.readText()
        assertTrue("끄기", Regex("""WatchText\.TURN_OFF\.ko to \{ MonitorOff\.run\(this@MainActivity\) \}""").containsMatchIn(text))
        val withdraw = Regex("""WatchText\.CONSENT_WITHDRAW\.ko to \{[\s\S]*?\n\s*\}\)""").find(text)?.value
        assertTrue("동의 철회 경로 없음", withdraw != null)
        assertTrue("동의 철회", withdraw!!.contains("MonitorOff.run(this@MainActivity)"))
        assertFalse("화면이 MonitorService.stop 을 직접 부르면 저장이 남는다", text.contains("MonitorService.stop("))
        assertFalse(text.contains("setMonitorWanted(false)"))
    }
}
