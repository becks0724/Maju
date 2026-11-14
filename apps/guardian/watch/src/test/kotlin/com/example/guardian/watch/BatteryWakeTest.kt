// 배터리 절약 시험 — 화면 상태가 그대로면 메인 스레드를 깨우지 않는 판단(data class 비교)과 중계 없는 빌드의 2초 sync 미예약을 본다
package com.example.guardian.watch

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.watch.service.WatchHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BatteryWakeTest {
    private fun src(path: String) = File(path).also { assertTrue("시험은 :watch 모듈 폴더에서 돈다 — ${it.absolutePath}", it.isFile) }.readText()

    private val before = WatchHub.UiState(
        monitoring = true, verdict = WristVerdict.NORMAL, source = WatchSource.HEALTH_SERVICES,
        alert = WatchHub.AlertView(FlowState.S1, 1, test = true, durationMs = 60_000, startedAtMs = 1_000, responded = false),
        test = WatchHub.TestView(Scenario.PULSE_LOST, 90_000), link = WatchHub.Link.OK, lastSyncOkAtMs = 5_000,
    )

    /** tick 이 같은 값으로 새로 만든 상태(새 AlertView · TestView 객체)는 이전과 같다 — update 가 알리지 않는다. */
    @Test fun sameTickValuesCompareEqual() {
        val again = before.copy(
            verdict = WristVerdict.NORMAL, source = WatchSource.HEALTH_SERVICES, lowConfidence = false, noSignal = false,
            alert = WatchHub.AlertView(FlowState.S1, 1, test = true, durationMs = 60_000, startedAtMs = 1_000, responded = false),
            test = WatchHub.TestView(Scenario.PULSE_LOST, 90_000),
        )
        assertEquals(before, again)
    }

    /** 화면이 쓰는 값이 하나라도 바뀌면 다르다 — 그때는 원래대로 알린다. */
    @Test fun anyFieldChangeCompareDifferent() {
        for (changed in listOf(
            before.copy(verdict = WristVerdict.CHECK_WEAR),
            before.copy(noSignal = true),
            before.copy(alert = before.alert!!.copy(responded = true)),
            before.copy(alert = null),
            before.copy(test = null),
            before.copy(link = WatchHub.Link.RETRY),
            before.copy(lastSyncOkAtMs = 7_000),
            before.copy(respondedClearedAtMs = 9_000),
        )) assertNotEquals(changed, before)
    }

    /** update 는 바뀌었을 때만 post 하고, post 는 잠금 안에서 한다(알림 순서 = 상태 순서). */
    @Test fun updatePostsOnlyWhenChanged() {
        val text = src("src/main/kotlin/com/example/guardian/watch/service/WatchHub.kt")
        val update = Regex("""fun update\(f: \(UiState\) -> UiState\) \{[\s\S]*?\n {4}\}""").find(text)?.value
        assertTrue("update 없음", update != null)
        assertTrue(update, Regex("""synchronized\(this\) \{[\s\S]*if \(s != old\) main\.post \{""").containsMatchIn(update!!))
        assertEquals(update, 1, Regex("""main\.post""").findAll(update).count())
    }

    /** 2초 sync 예약은 중계가 있을 때(syncRunner 있음)만 — 없으면 syncOnce 가 바로 돌아가므로 하는 일이 같다. */
    @Test fun periodicSyncOnlyWithRelay() {
        val text = src("src/main/kotlin/com/example/guardian/watch/service/MonitorService.kt")
        val begin = Regex("""private fun begin\(\) \{[\s\S]*?\n {4}\}""").find(text)!!.value
        assertTrue(begin, begin.contains("if (syncRunner != null) netExec?.scheduleWithFixedDelay({ requestSync() }"))
        assertEquals(begin, 1, Regex("""scheduleWithFixedDelay\(\{ requestSync\(\) \}""").findAll(begin).count())
        val syncOnce = Regex("""private fun syncOnce\(\) \{\n\s*([^\n]*)""").find(text)!!.groupValues[1]
        assertEquals("val runner = syncRunner ?: return", syncOnce.trim())
    }
}
