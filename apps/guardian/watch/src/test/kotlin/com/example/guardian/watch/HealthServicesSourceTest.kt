// Health Services 출처 시험 — 운동 세션 우선 · 묶음(5 · 60 · 150초)에서 신호 없음 오작동 없음 · 실제 끊김 10초(받는 쪽 +30초 = 40초) · 다른 앱이면 측정 · 빼앗김 재획득 · 끌 때 우리 세션만 종료
package com.example.guardian.watch

import com.example.guardian.shared.bio.AccelSample
import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.PpgSample
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.watch.core.GateRunner
import com.example.guardian.watch.sensor.HealthServicesSource
import com.example.guardian.watch.sensor.HsClient
import com.example.guardian.watch.sensor.HsHeartAdapter
import com.example.guardian.watch.sensor.SampleSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class HealthServicesSourceTest {
    private val fake = FakeHsClient()
    private var now = 0L
    private val gate = GateRunner()
    private val hr = mutableListOf<HrSample>()
    private val failures = mutableListOf<String>()
    private val sink = object : SampleSink {
        override fun ppg(s: PpgSample) = error("Health Services 는 PPG 를 주지 않는다")
        override fun accel(s: AccelSample) = error("Health Services 는 가속도를 주지 않는다")
        override fun hr(s: HrSample) { hr += s; gate.hr(s) }
    }
    private val source = HealthServicesSource(fake, Executor { it.run() }, { now })

    private fun start() = source.start(sink) { failures += it }

    /**
     * 1초마다 — 센서가 1초 간격으로 재고(sample), [batchMs] 마다 묶음이 전달되고, 서비스처럼 2초마다 flush 를 부른다([serviceFlush]).
     * 매 초 신호 없음 · 관문 심박이 빈 초를 모은다.
     */
    private fun drive(fromMs: Long, toMs: Long, batchMs: Long, serviceFlush: Boolean, bpm: Double? = 72.0): Pair<List<Long>, List<Long>> {
        val noSignalAt = mutableListOf<Long>()
        val hrMissingAt = mutableListOf<Long>()
        for (t in fromMs..toMs step 1_000) {
            now = t
            if (bpm != null) fake.sample(bpm, t)
            if (t % batchMs == 0L) fake.deliverBuffered()
            source.tick(t)
            if (serviceFlush && t % 2_000 == 0L) source.flush()
            if (source.noSignal(t)) noSignalAt += t
            if (gate.evaluate(t).hr == null) hrMissingAt += t
        }
        return noSignalAt to hrMissingAt
    }

    // ── 정상 경로 ──

    @Test fun startsExerciseWithHeartRateOnlyAndBatchingWhenSupported() {
        start()
        assertEquals(listOf("status", "caps", "startExercise"), fake.calls)
        assertEquals(true, fake.lastBatch5s)
        assertTrue(source.exerciseActive)
        assertEquals(WatchSource.HEALTH_SERVICES, source.kind)
        assertTrue("심박만이라 판정 신뢰 낮음 그대로", source.lowConfidence)
        fake.exerciseListener!!.onAvailability(HsHeartAdapter.Availability.AVAILABLE)
        fake.exerciseListener!!.onBpm(75.0, 1_000)
        assertEquals(1_000, hr.last().atMs)
        assertEquals(75, hr.last().bpm)
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun batchingModeUnsupportedStillUsesExerciseWithoutOverride() {
        fake.caps = HsClient.ExerciseCaps(heartRate = true, batch5s = false)
        start()
        assertEquals(false, fake.lastBatch5s)
        assertTrue(source.exerciseActive)
    }

    // ── 묶음 전달에서 「워치 신호 없음」 오작동 방지 ──

    @Test fun fiveSecondBatchesNeverLookLikeNoSignal() {
        start()
        fake.exerciseListener!!.onAvailability(HsHeartAdapter.Availability.AVAILABLE)
        val (noSignal, missing) = drive(1_000, 180_000, batchMs = 5_000, serviceFlush = false)
        assertEquals("5초 묶음은 flush 없이도 신호 없음이 아니다", emptyList<Long>(), noSignal)
        assertEquals("첫 묶음 뒤 관문 심박이 비는 초가 없다", emptyList<Long>(), missing.filter { it >= 5_000 })
        assertEquals(WristVerdict.NORMAL, gate.evaluate(180_000).verdict)
    }

    @Test fun sixtyAnd150SecondBatchesAreFlushedUnderTheNoSignalWindow() {
        for (batch in listOf(60_000L, 150_000L)) {
            val f = FakeHsClient()
            val g = GateRunner()
            var t0 = 0L
            val s = HealthServicesSource(f, Executor { it.run() }, { t0 })
            s.start(object : SampleSink {
                override fun ppg(s: PpgSample) = Unit
                override fun accel(s: AccelSample) = Unit
                override fun hr(s: HrSample) = g.hr(s)
            }) { }
            f.exerciseListener!!.onAvailability(HsHeartAdapter.Availability.AVAILABLE)
            val noSignalAt = mutableListOf<Long>()
            for (t in 1_000L..600_000L step 1_000) {
                t0 = t
                f.sample(70.0, t)
                if (t % batch == 0L) f.deliverBuffered()
                s.tick(t)
                if (t % 2_000 == 0L) s.flush() // 서비스가 2초마다 부른다 → 출처가 4초마다 flush
                if (s.noSignal(t)) noSignalAt += t
            }
            assertEquals("${batch}ms 묶음 — flush 로 당겨 받아 신호 없음이 없다", emptyList<Long>(), noSignalAt)
            assertTrue("flush 는 4초마다 한 번(2초마다 아님)", f.count("flush") in 140..160)
        }
    }

    /** flush 가 없으면 60초 묶음은 묶음 사이에 신호 없음으로 보인다 — 주기 flush 가 필요한 이유. */
    @Test fun withoutFlushLongBatchesWouldLookLikeNoSignal() {
        start()
        fake.exerciseListener!!.onAvailability(HsHeartAdapter.Availability.AVAILABLE)
        val (noSignal, _) = drive(1_000, 180_000, batchMs = 60_000, serviceFlush = false)
        assertTrue("묶음 사이(예 71~119초)에 신호 없음", 90_000L in noSignal)
        assertFalse("묶음이 온 직후는 아님", 61_000L in noSignal)
    }

    @Test fun realLossShowsWithinTenSecondsOfLastSampleTime() {
        start()
        fake.exerciseListener!!.onAvailability(HsHeartAdapter.Availability.AVAILABLE)
        drive(1_000, 30_000, batchMs = 5_000, serviceFlush = true)
        // 30초 이후 센서가 아무 값도 못 잰다(flush 해도 빈 묶음)
        val (noSignal, _) = drive(31_000, 60_000, batchMs = 5_000, serviceFlush = true, bpm = null)
        assertEquals("마지막 샘플(30초) + 10초 뒤부터 — 받는 쪽 30초 신선도와 합쳐 40초 안", 41_000L, noSignal.first())
        assertTrue(noSignal.containsAll((41_000L..60_000L step 1_000).toList()))
    }

    @Test fun batchedSamplesKeepTheirOwnTimes() {
        start()
        fake.exerciseListener!!.onAvailability(HsHeartAdapter.Availability.AVAILABLE)
        for (t in 1_000L..5_000L step 1_000) fake.sample(60.0 + t / 1_000, t)
        now = 5_400
        fake.deliverBuffered() // 5초치가 5.4초에 한꺼번에 온다
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L), hr.map { it.atMs })
        assertEquals(listOf(61, 62, 63, 64, 65), hr.map { it.bpm })
        // 다음 틱의 보류 샘플은 지금 시각 · 마지막 bpm, 보류 표식(B-1)
        now = 6_000
        source.tick(6_000)
        assertEquals(HrSample(6_000, 65, com.example.guardian.shared.bio.HrStatus.OK, listOf(923), held = true), hr.last())
    }

    // ── 다른 앱의 운동을 끊지 않는다 ──

    @Test fun otherAppExerciseFallsBackToMeasureAndReacquiresLater() {
        fake.status = HsClient.ExerciseStatus.OTHER_APP
        start()
        assertEquals(listOf("status", "startMeasure"), fake.calls)
        assertFalse(source.exerciseActive)
        // 측정 경로로 값이 들어온다
        fake.measureListener!!.onBpm(80.0, 2_000)
        assertEquals(80, hr.last().bpm)
        // 30초 전에는 다시 묻지 않는다
        now = 29_000; source.tick(now)
        assertEquals(1, fake.count("status"))
        // 30초 뒤 다시 묻고, 아직 다른 앱이면 측정 그대로
        now = 30_000; source.tick(now)
        assertEquals(2, fake.count("status"))
        assertEquals(0, fake.count("startExercise"))
        // 다른 앱 운동이 끝나면 운동 세션을 열고 측정을 거둔다
        fake.status = HsClient.ExerciseStatus.NONE
        now = 60_000; source.tick(now)
        assertEquals(1, fake.count("startExercise"))
        assertEquals(1, fake.count("stopMeasure"))
        assertTrue(source.exerciseActive)
    }

    @Test fun sessionTakenOverMidwayFallsBackAndReacquires() {
        start()
        fake.exerciseListener!!.onAvailability(HsHeartAdapter.Availability.AVAILABLE)
        drive(1_000, 20_000, batchMs = 5_000, serviceFlush = true)
        now = 21_000
        fake.takenOverByOtherApp()
        assertFalse(source.exerciseActive)
        assertEquals("측정으로 내려간다", 1, fake.count("startMeasure"))
        assertEquals("다른 앱 세션을 끝내지 않는다", 0, fake.count("endExercise"))
        // 값이 안 오면 신호 없음으로 드러난다(마지막 샘플 20초 + 10초)
        val (noSignal, _) = drive(22_000, 51_000, batchMs = 5_000, serviceFlush = true, bpm = null)
        assertEquals(31_000L, noSignal.first())
        // 30초 뒤(51초) 다시 잡아 본다 — 아직 다른 앱이면 그대로, 끝났으면 다시 연다
        assertEquals(2, fake.count("status"))
        assertEquals(1, fake.count("startExercise"))
        fake.status = HsClient.ExerciseStatus.NONE
        drive(52_000, 82_000, batchMs = 5_000, serviceFlush = true, bpm = null)
        assertEquals(2, fake.count("startExercise"))
        assertTrue(source.exerciseActive)
        assertEquals(1, fake.count("stopMeasure"))
    }

    /** 시작 직후 끝나 「끝남」이 「시작 성공」보다 먼저 와도 버리지 않는다 — 측정으로 내려가 30초 뒤 다시 잡는다(코드 리뷰 11-08 하). */
    @Test fun sessionEndedBeforeStartReplyFallsBackAndReacquires() {
        fake.deferStart = true
        start()
        fake.endWhileStarting("ENDED/AUTO_END_PERMISSION_LOST")
        fake.completeStart()
        fake.status = HsClient.ExerciseStatus.NONE // 이미 끝난 세션
        assertFalse("끝난 세션을 쓰는 중으로 두지 않는다", source.exerciseActive)
        assertEquals("측정으로 내려간다", 1, fake.count("startMeasure"))
        fake.deferStart = false
        now = 30_000; source.tick(now)
        assertEquals("30초 뒤 다시 잡는다", 2, fake.count("startExercise"))
        assertTrue(source.exerciseActive)
        assertEquals(1, fake.count("stopMeasure"))
    }

    // ── 앞선 세션의 늦은 「끝남」이 섞이지 않는다(코드 리뷰 11-08 관찰 ①) ──

    /** 빼앗긴 세션 A 의 끝남이 다시 잡은 세션 B 가 열린 뒤 늦게 한 번 더 와도 B 를 측정으로 내리지 않는다. */
    @Test fun lateEndOfEarlierSessionDoesNotDropCurrentSession() {
        start()
        val a = fake.sessionListeners.single()
        fake.takenOverByOtherApp()
        assertEquals(1, fake.count("startMeasure"))
        fake.status = HsClient.ExerciseStatus.NONE
        now = 30_000; source.tick(now)
        assertTrue("B 가 열렸다", source.exerciseActive)
        assertEquals(1, fake.count("stopMeasure"))
        a.onExerciseEnded("ENDED/AUTO_END_SUPERSEDED") // A 의 늦은 끝남
        assertTrue("B 는 그대로", source.exerciseActive)
        assertEquals("측정으로 다시 내려가지 않는다", 1, fake.count("startMeasure"))
        now = 60_000; source.tick(now)
        assertEquals("다시 잡기도 없다", 2, fake.count("startExercise"))
    }

    /** 시간 제한 뒤 늦게 열려 닫은 세션의 끝남이 다음 시도의 시작 중에 와도 「시작 직후 끝남」으로 읽지 않는다. */
    @Test fun lateEndOfClosedLateSessionDoesNotSpoilNextAttempt() {
        fake.deferStart = true
        start()
        for (t in 1_000L..16_000L step 1_000) { now = t; source.tick(t) }
        fake.completeStart() // 시간 제한 뒤 열림 → 바로 닫음
        assertEquals(1, fake.count("endExercise"))
        val late = fake.sessionListeners.single()
        now = 46_000; source.tick(now) // 30초 뒤 다시 잡기 — 시작 결과 대기 중
        assertEquals(2, fake.count("startExercise"))
        late.onExerciseEnded("ENDED/USER_END") // 닫은 세션의 끝남이 이제 온다
        fake.completeStart()
        assertTrue("새 세션을 쓴다", source.exerciseActive)
        assertEquals("측정은 한 번 등록 · 한 번 해제", 1, fake.count("stopMeasure"))
    }

    /** 지금 세션의 끝남은 그대로 받는다(묶음 리스너가 지금 시도 번호일 때). */
    @Test fun endOfCurrentSessionStillFallsBack() {
        start()
        fake.sessionListeners.single().onExerciseEnded("ENDED/AUTO_END_PERMISSION_LOST")
        assertFalse(source.exerciseActive)
        assertEquals(1, fake.count("startMeasure"))
    }

    /** 실제 클라이언트가 버리는 업데이트 — 요청 전이거나, 요청보다 2초 넘게 앞서 시작한 세션(남은 우리 세션). */
    @Test fun updatesOfSessionsStartedBeforeOurRequestAreFiltered() {
        val req = 1_000_000L
        assertTrue("요청 전 업데이트", com.example.guardian.watch.sensor.fromEarlierSession(500_000L, null))
        assertTrue("요청 전(시작 시각 없음)", com.example.guardian.watch.sensor.fromEarlierSession(null, null))
        assertTrue("30초 앞서 시작한 남은 세션", com.example.guardian.watch.sensor.fromEarlierSession(req - 30_000, req))
        assertFalse("요청 뒤 시작한 지금 세션", com.example.guardian.watch.sensor.fromEarlierSession(req + 150, req))
        assertFalse("ms 반올림 · 같은 기기 시계 여유 안", com.example.guardian.watch.sensor.fromEarlierSession(req - 1_000, req))
        assertFalse("시작 시각이 아직 없는 지금 세션", com.example.guardian.watch.sensor.fromEarlierSession(null, req))
    }

    // ── 끌 때 우리 세션만 끝낸다 ──

    @Test fun stopEndsOurExerciseSession() {
        start()
        source.stop()
        assertEquals(1, fake.count("endExercise"))
        assertEquals(0, fake.count("stopMeasure"))
        assertNull(fake.exerciseListener)
        // 끈 뒤 늦게 온 값 · 종료 알림은 무시한다
        assertFalse(source.noSignal(100_000))
    }

    @Test fun stopOnMeasurePathDoesNotTouchOtherAppsSession() {
        fake.status = HsClient.ExerciseStatus.OTHER_APP
        start()
        source.stop()
        assertEquals(0, fake.count("endExercise"))
        assertEquals(1, fake.count("stopMeasure"))
        assertEquals(HsClient.ExerciseStatus.OTHER_APP, fake.status)
    }

    @Test fun stopWhileStartingClosesTheSessionWhenItOpens() {
        fake.deferStart = true
        start()
        assertEquals(1, fake.count("startExercise"))
        source.stop()
        assertEquals(0, fake.count("endExercise"))
        fake.completeStart() // 끄는 사이에 열렸다
        assertEquals(1, fake.count("endExercise"))
        assertFalse(source.exerciseActive)
    }

    /** 감시 tick 실행기가 측정을 등록하는 사이 다른 스레드에서 stop() 이 오면 등록이 남지 않는다(코드 리뷰 11-08 하 의심). */
    @Test fun stopDuringMeasureRegistrationLeavesNoRegistration() {
        fake.status = HsClient.ExerciseStatus.OTHER_APP
        fake.beforeMeasureRegistered = { source.stop() }
        start()
        assertNull("등록이 남지 않는다", fake.measureListener)
        assertEquals(0, fake.count("endExercise"))
    }

    @Test fun leftoverOwnedSessionIsEndedThenReopened() {
        fake.status = HsClient.ExerciseStatus.OURS // 비정상 종료 뒤 남은 우리 세션
        start()
        assertEquals(listOf("status", "endExercise", "status", "caps", "startExercise"), fake.calls)
        assertTrue(source.exerciseActive)
    }

    // ── 못 쓰는 경우 ──

    @Test fun capabilityWithoutHeartRateUsesMeasureWithoutRetrying() {
        fake.caps = HsClient.ExerciseCaps(heartRate = false, batch5s = false)
        start()
        assertEquals(listOf("status", "caps", "startMeasure"), fake.calls)
        drive(1_000, 120_000, batchMs = 5_000, serviceFlush = true)
        assertEquals("능력 미지원은 다시 묻지 않는다", 1, fake.count("status"))
        assertEquals(0, fake.count("flush"))
    }

    /** 재부팅 직후처럼 Health Services 바인딩이 늦어 조회가 실패하면 측정으로 가되, 30초 뒤 다시 물어 운동 세션을 연다. */
    @Test fun statusQueryFailureUsesMeasureAndRetries() {
        fake.status = null
        start()
        assertEquals(listOf("status", "startMeasure"), fake.calls)
        now = 30_000; source.tick(now)
        assertEquals("아직 실패면 측정 그대로", 2, fake.count("status"))
        assertFalse(source.exerciseActive)
        fake.status = HsClient.ExerciseStatus.NONE
        now = 60_000; source.tick(now)
        assertTrue(source.exerciseActive)
        assertEquals(1, fake.count("stopMeasure"))
    }

    @Test fun capabilityQueryFailureUsesMeasureAndRetries() {
        fake.caps = null
        start()
        assertEquals(listOf("status", "caps", "startMeasure"), fake.calls)
        fake.caps = HsClient.ExerciseCaps(heartRate = true, batch5s = false)
        now = 30_000; source.tick(now)
        assertEquals(2, fake.count("caps"))
        assertTrue(source.exerciseActive)
    }

    // ── Health Services 무응답 · 늦은 응답 ──

    @Test fun noResponseTimesOutToMeasureAndLateReplyIsIgnored() {
        fake.deferStatus = true
        start()
        assertEquals(listOf("status"), fake.calls)
        for (t in 1_000L..15_000L step 1_000) { now = t; source.tick(t) }
        assertEquals("15초까지는 기다린다", 0, fake.count("startMeasure"))
        now = 16_000; source.tick(now)
        assertEquals("15초를 넘기면 측정으로", 1, fake.count("startMeasure"))
        // 늦게 온 응답은 상태를 바꾸지 않는다
        fake.completeStatus(HsClient.ExerciseStatus.NONE)
        assertEquals(0, fake.count("caps"))
        assertEquals(0, fake.count("startExercise"))
        assertFalse(source.exerciseActive)
        // 30초 뒤 다시 잡아 본다 — 이번엔 응답이 오면 운동 세션으로
        fake.deferStatus = false
        now = 46_000; source.tick(now)
        assertEquals(2, fake.count("status"))
        assertTrue(source.exerciseActive)
        assertEquals(1, fake.count("stopMeasure"))
    }

    @Test fun lateStartSuccessAfterTimeoutIsClosedNotKept() {
        fake.deferStart = true
        start()
        for (t in 1_000L..16_000L step 1_000) { now = t; source.tick(t) }
        assertEquals(1, fake.count("startMeasure"))
        fake.completeStart() // 시간 제한 뒤에야 열렸다
        assertEquals("추적 못 하는 세션은 바로 닫는다", 1, fake.count("endExercise"))
        assertFalse(source.exerciseActive)
    }

    @Test fun stopAfterExecutorClosedStillEndsLateOpenedSession() {
        val closable = object : Executor {
            var closed = false
            override fun execute(r: Runnable) { if (closed) throw java.util.concurrent.RejectedExecutionException() else r.run() }
        }
        val f = FakeHsClient().apply { deferStart = true }
        val s = HealthServicesSource(f, closable, { now })
        s.start(sink) { }
        s.stop()
        closable.closed = true // MonitorService.end() 는 stop() 직후 tick 실행기를 닫는다
        f.completeStart()
        assertEquals("실행기가 닫혀도 그 자리에서 세션을 끝낸다", 1, f.count("endExercise"))
    }

    // ── 시작 오류 뒤 남은 우리 세션 ──

    @Test fun sessionLeftOpenAfterStartErrorIsEndedOnRetry() {
        fake.startError = IllegalStateException("시작 예외")
        fake.openDespiteError = true
        start()
        assertEquals(listOf("status", "caps", "startExercise", "startMeasure"), fake.calls)
        fake.startError = null
        now = 30_000; source.tick(now)
        assertEquals(
            listOf("status", "caps", "startExercise", "startMeasure", "status", "endExercise", "status", "startExercise", "stopMeasure"),
            fake.calls,
        )
        assertTrue(source.exerciseActive)
    }

    @Test fun unendableOwnedSessionDoesNotLoopEndlessly() {
        fake.status = HsClient.ExerciseStatus.OURS
        fake.endKeepsOurs = true
        start()
        assertEquals("한 번 끝내 보고 측정으로", listOf("status", "endExercise", "status", "startMeasure"), fake.calls)
        assertFalse(source.exerciseActive)
        drive(1_000, 120_000, batchMs = 5_000, serviceFlush = true)
        assertTrue("30초마다 한 번씩만 다시 끝내 본다(120초에 최대 5번)", fake.count("endExercise") in 2..5)
        assertEquals(0, fake.count("startExercise"))
    }

    @Test fun startFailureUsesMeasureAndRetries() {
        fake.startError = IllegalStateException("거부")
        start()
        assertEquals(listOf("status", "caps", "startExercise", "startMeasure"), fake.calls)
        assertFalse(source.exerciseActive)
        fake.startError = null
        now = 30_000; source.tick(now)
        assertEquals("능력은 한 번만 묻고 다시 시작", listOf("status", "caps", "startExercise", "startMeasure", "status", "startExercise", "stopMeasure"), fake.calls)
        assertTrue(source.exerciseActive)
    }

    @Test fun measureRegistrationFailureIsNoSignalAndReported() {
        fake.status = HsClient.ExerciseStatus.OTHER_APP
        fake.measureError = IllegalStateException("등록 거부")
        start()
        assertTrue(source.noSignal(0))
        assertEquals(listOf("Health Services 등록 실패 IllegalStateException"), failures)
    }
}
