// 401 뒤 연결 기간 끝(REPAIR) 유지 시험 — 2초 sync 가 토큰 없음으로 「연결 안 됨」으로 덮지 않는지, 401 이 경보 · 시나리오를 실제로 끝내는지, 다시 짝 · 동의 철회 때만 풀리는지 본다
package com.example.guardian.watch

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.AlertStopReason
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.watch.core.GateRunner
import com.example.guardian.watch.core.WatchAction
import com.example.guardian.watch.core.WatchEngine
import com.example.guardian.watch.net.HttpResponse
import com.example.guardian.watch.net.HttpTransport
import com.example.guardian.watch.net.RelayApi
import com.example.guardian.watch.net.SyncRunner
import com.example.guardian.watch.service.WatchHub.Link
import com.example.guardian.watch.service.pairEndedSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RepairLinkTest {
    @Test fun repairSurvivesSyncsWithoutToken() {
        // 401 → REPAIR, 그 뒤 2초마다 토큰 없음 sync 가 와도 그대로
        var link = Link.REPAIR
        repeat(5) { link = link.withoutToken() }
        assertEquals(Link.REPAIR, link)
    }

    @Test fun otherLinksBecomeNoneWithoutToken() {
        for (l in listOf(Link.NONE, Link.OK, Link.RETRY)) assertEquals(l.name, Link.NONE, l.withoutToken())
    }

    private fun src(path: String) = File(path).also { assertTrue("시험은 :watch 모듈 폴더에서 돈다 — ${it.absolutePath}", it.isFile) }.readText()

    /** 서비스의 토큰 없음 갈래가 REPAIR 를 지키는 함수를 쓰는지(직접 NONE 으로 덮으면 2초 만에 안내가 사라진다). */
    @Test fun serviceKeepsRepairWhenTokenMissing() {
        val text = src("src/main/kotlin/com/example/guardian/watch/service/MonitorService.kt")
        val branch = Regex("""if \(token == null\) \{[^\n]*\}""").find(text)?.value
        assertTrue("토큰 없음 갈래 없음", branch != null)
        assertTrue(branch, branch!!.contains("withoutToken()"))
        assertFalse(branch, branch.contains("Link.NONE"))
    }

    /**
     * 401(짝 끝남)을 실제로 돌린다 — 테스트 시나리오 중 경보가 울리는 엔진에 중계가 401 을 주면, 서비스가 부르는 [pairEndedSteps] 가
     * 토큰을 지우고 경보 · 시나리오를 끝내 실제 출처로 돌아가고, 경보 멈춤(PAIR_END)을 돌려준다(코드 리뷰 11-08 하 · 관찰 ③).
     */
    @Test fun unauthorizedEndsAlertAndRunningScenario() {
        val clocks = FakeClocks()
        val e = WatchEngine(WatchSource.HEALTH_SERVICES, lowConfidence = true)
        fun cmd(id: String, type: CommandType, p: Payload) = Command(id, type, p.toJson(), 30_000, true, Party.PHONE, CommandTo.ALL)
        e.handle(listOf(cmd("c-1", CommandType.TEST_SCENARIO, Payload.TestScenario(Scenario.PULSE_LOST, 120_000))), clocks.monoMs)
        repeat(40) { clocks.monoMs += 1_000; e.tick(clocks.monoMs) }
        e.handle(listOf(cmd("c-2", CommandType.ALERT_START, Payload.AlertStart(FlowState.S1, 1, true, false, false, 15_000))), clocks.monoMs)
        assertEquals(WatchSource.SIMULATION, e.source)
        assertNotNull(e.alert)

        val http = HttpTransport { _, _ -> HttpResponse(401, "{\"error\":\"TOKEN_INVALID\"}") }
        assertEquals(SyncRunner.Outcome.Unauthorized, SyncRunner(RelayApi(http), e, clocks).runOnce("tok", true))
        var cleared = 0
        val actions = pairEndedSteps(e, clocks.monoMs, clearPair = { cleared++ })

        assertEquals("토큰을 한 번 지운다", 1, cleared)
        assertNull("경보를 거둔다", e.alert)
        assertNull("시나리오를 끝낸다", e.scenario)
        assertEquals("실제 출처로 돌아간다", WatchSource.HEALTH_SERVICES, e.source)
        assertEquals(listOf(WatchAction.StopAlert(AlertStopReason.PAIR_END)), actions)
        val t = clocks.monoMs + 1_000
        assertEquals("시나리오 판정이 남지 않는다(새 관문)", GateRunner().evaluate(t).verdict, e.tick(t).verdict)
    }

    /** 서비스의 401 갈래가 위 함수를 쓰는지만 본다(서비스는 단위 시험에서 띄울 수 없다 — Robolectric 은 의존성 밖). */
    @Test fun serviceUnauthorizedBranchUsesPairEndedSteps() {
        val text = src("src/main/kotlin/com/example/guardian/watch/service/MonitorService.kt")
        val branch = Regex("""SyncRunner\.Outcome\.Unauthorized -> \{[\s\S]*?\n {12}\}""").find(text)?.value
        assertTrue("401 갈래 없음", branch != null)
        assertTrue(branch, branch!!.contains("apply(runner.engine, pairEndedSteps(engine, clocks.mono(), store::clearPair))"))
    }

    /** 화면이 REPAIR 를 푸는 곳 — 다시 짝을 맺었을 때와 동의 철회 때. */
    @Test fun screenClearsRepairOnRepairOrWithdraw() {
        val text = src("src/main/kotlin/com/example/guardian/watch/ui/MainActivity.kt")
        val done = Regex("""if \(result == PairOutcome\.DONE\) \{[\s\S]*?\n\s*\}""").find(text)?.value
        assertTrue("짝 맺음 갈래 없음", done != null)
        assertTrue(done, done!!.contains("clearRepair()"))
        val withdraw = Regex("""WatchText\.CONSENT_WITHDRAW\.ko to \{[\s\S]*?\n\s*\}\)""").find(text)?.value
        assertTrue("동의 철회 경로 없음", withdraw != null)
        assertTrue(withdraw, withdraw!!.contains("clearRepair()"))
    }
}
