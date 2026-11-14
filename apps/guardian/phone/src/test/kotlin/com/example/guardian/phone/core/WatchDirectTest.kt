// 워치 직접 경로 · 테스트 멈춤 · 건너뛴 지시 시험 — 워치 지시는 중계 직접 경로가 있을 때만(계약 §4.3 · §4.7), 워치 테스트를 끝내면 TEST_SCENARIO_STOP, skippedCommandIds 는 ack
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDirectTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { false })

    private fun sync(directAge: Long?, skipped: List<String> = emptyList()) =
        engine.buildSync("tok", mono).also {
            engine.onSyncResponse(
                PhoneSyncResponse(mono + serverOffset, null, null, emptyList(), watchDirectAgeMs = directAge, skippedCommandIds = skipped), mono, mono,
            )
        }

    /** 정차 · Data Layer 워치 값이 신선한 상태. */
    private fun ready() {
        engine.onDataLayerWatch(Watch(WristVerdict.NORMAL, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = null), mono)
        engine.onLocation(Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "주소"), mono)
        engine.tick(mono)
    }

    private fun outCommands() = engine.buildSync("tok", mono).commands

    @Test fun dataLayerOnlyWatchRunsPhoneOnlyTestWithNotice() {
        ready()
        sync(directAge = null)
        assertFalse("Data Layer 값만으로는 워치 직접 연결이 아니다", engine.status(mono).watchDirect)
        assertFalse(engine.status(mono).watchStale) // 워치 값 자체는 신선하다
        assertEquals(TestStart.LOCAL, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
        assertEquals(TestMode.LOCAL, engine.test?.mode)
        assertEquals(listOf(Notice.TEST_PHONE_ONLY_NO_DIRECT_WATCH), fx.notices)
        assertTrue("워치로 가는 지시가 없다", outCommands().none { it.to == CommandTo.WATCH })
    }

    @Test fun phoneOnlyChoiceShowsNoNotice() {
        ready()
        sync(directAge = null)
        assertEquals(TestStart.LOCAL, engine.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = mono))
        assertTrue(fx.notices.isEmpty())
    }

    /** 직접 경로 나이는 응답 뒤 지난 로컬 시간을 더해 본다 — 30,000ms 까지는 지시 가능, 넘으면 휴대폰 단독. */
    @Test fun directAgeAgesWithLocalTime() {
        ready()
        sync(directAge = 10_000)
        mono += 20_000
        ready()
        assertTrue(engine.status(mono).watchDirect)
        mono += 1
        ready()
        assertFalse(engine.status(mono).watchDirect)
        assertEquals(TestStart.LOCAL, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
        assertEquals(listOf(Notice.TEST_PHONE_ONLY_NO_DIRECT_WATCH), fx.notices)
    }

    @Test fun unauthorizedForgetsDirectWatch() {
        ready()
        sync(directAge = 0)
        assertTrue(engine.status(mono).watchDirect)
        engine.onUnauthorized()
        assertFalse(engine.status(mono).watchDirect)
    }

    @Test fun stoppingWatchTestSendsTestScenarioStop() {
        ready()
        sync(directAge = 0)
        assertEquals(TestStart.WATCH, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
        sync(directAge = 0) // TEST_SCENARIO 는 나갔다
        engine.stopTest()
        val stop = outCommands().single()
        assertEquals(CommandType.TEST_SCENARIO_STOP, stop.type)
        assertEquals(CommandTo.WATCH, stop.to)
        assertTrue(stop.test)
        assertTrue(stop.payload.isEmpty())
    }

    @Test fun stoppingPhoneOnlyTestSendsNothingToWatch() {
        ready()
        sync(directAge = 0)
        assertEquals(TestStart.LOCAL, engine.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = mono))
        engine.stopTest()
        assertTrue(outCommands().isEmpty())
    }

    @Test fun watchTestTimeoutAlsoSendsTestScenarioStop() {
        ready()
        sync(directAge = 0)
        assertEquals(TestStart.WATCH, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
        sync(directAge = 0)
        mono += LocalTestRun.DURATION_MS + PhoneEngine.TEST_GRACE_MS + 1
        engine.tick(mono)
        assertNull(engine.test)
        assertEquals(listOf(CommandType.TEST_SCENARIO_STOP), outCommands().filter { it.to == CommandTo.WATCH }.map { it.type })
    }

    @Test fun skippedCommandIdsAreAckedOnNextSync() {
        ready()
        sync(directAge = null, skipped = listOf("unknown-1", "bad-2"))
        assertTrue(engine.hasOutgoing())
        val req = sync(directAge = null)
        assertEquals(listOf("unknown-1", "bad-2"), req.acks)
        assertTrue(engine.buildSync("tok", mono).acks.isEmpty()) // 한 번만
    }
}
