// 재생 도구 시험 — 합성 세션으로 정상 0회 · 진폭 급감은 약함 · 맥 소실은 없음, 제품 관문 직접 재생과 일치, 파일 빠짐 · 빈칸 · 잘린 줄, 도착 기준 지연, 요약 수치 손 계산 대조
package com.example.guardian.watch.replay

import com.example.guardian.shared.bio.PulseGate
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.Simulator
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.watch.core.GateRunner
import com.example.guardian.watch.probe.BioProbeCsv
import com.example.guardian.watch.probe.BioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BioReplayTest {
    @get:Rule val tmp = TemporaryFolder()

    private val event = 45_000L

    private fun session(name: String, s: SyntheticSession, skip: Set<BioStream> = emptySet()): File =
        File(tmp.root, name).also { s.write(it, skip) }

    private fun report(dir: File) = ReplayReport.build(BioSession.load(dir))

    @Test fun normalPulseHasNoWeakOrAbsentVerdict() {
        val dir = session("normal", SyntheticSession(Simulator(3).generate(Scenario.NORMAL, durationMs = 120_000)))
        val r = report(dir)
        val sec = r.verdictSeconds(TimeBasis.SAMPLE)
        assertEquals(0, sec.getValue(WristVerdict.PULSE_WEAK))
        assertEquals(0, sec.getValue(WristVerdict.PULSE_ABSENT))
        assertTrue(r.episodes(TimeBasis.SAMPLE).isEmpty())
        assertTrue("기준선이 잡힌 뒤에는 정상", r.ticks.drop(20).all { it.verdict == WristVerdict.NORMAL })
        val md = SummaryWriter.summary(r)
        assertTrue(md.contains("구간 수 0"))
        assertTrue("고정 머리 문단", md.lines()[2] == "## 이 자료로 말할 수 없는 것")
    }

    @Test fun amplitudeDropSegmentIsWeakNotAbsent() {
        val dir = session("drop", SyntheticSession(Simulator(3).generate(Scenario.AMPLITUDE_DROP, eventAtMs = event)))
        val r = report(dir)
        val after = r.ticks.filter { it.tMs - SyntheticSession.MONO0 >= event + 15_000 }
        assertTrue(after.isNotEmpty())
        assertTrue("급감 뒤 15초부터는 약함", after.all { it.verdict == WristVerdict.PULSE_WEAK })
        assertEquals(0, r.verdictSeconds(TimeBasis.SAMPLE).getValue(WristVerdict.PULSE_ABSENT))
        assertTrue("급감 전에는 정상", r.ticks.filter { it.tMs - SyntheticSession.MONO0 < event }.none { ReplayReport.bad(it.verdict) })
        val eps = r.episodes(TimeBasis.SAMPLE)
        assertEquals(1, eps.size)
        assertEquals(WristVerdict.PULSE_WEAK, eps[0].worst)
        // 흐름 S0 에서는 구간마다 바로 경보, 30초 이상 지속이라 재경보 기준에도 든다
        assertEquals(1, r.alertCandidates(TimeBasis.SAMPLE).size)
        assertEquals(1, r.realertCandidates(TimeBasis.SAMPLE).size)
    }

    @Test fun pulseLostWithStillWristIsAbsent() {
        val dir = session("lost", SyntheticSession(Simulator(3).generate(Scenario.PULSE_LOST, eventAtMs = event)))
        val r = report(dir)
        val absent = r.ticks.filter { it.verdict == WristVerdict.PULSE_ABSENT }
        assertTrue(absent.isNotEmpty())
        val firstAbsent = absent.first().tMs - SyntheticSession.MONO0
        assertTrue("소실 뒤 15초 안 없음 판정 $firstAbsent", firstAbsent - event in 1..15_000)
        assertTrue("없음 뒤 유지", r.ticks.filter { it.tMs - SyntheticSession.MONO0 >= firstAbsent }.all { it.verdict == WristVerdict.PULSE_ABSENT })
        // 시뮬레이터 소실은 A3(상태 정상 · IBI 없음)로 먼저 약함, 상태가 −8 로 바뀌면 잠시 정상, A1 · B 가 서면 없음 — 구간이 둘로 갈린다
        val eps = r.episodes(TimeBasis.SAMPLE)
        assertEquals(WristVerdict.PULSE_ABSENT, eps.last().worst)
        assertEquals(1, eps.count { it.worst == WristVerdict.PULSE_ABSENT })
        val md = SummaryWriter.summary(r)
        assertTrue(md.contains("맥박 없음까지 간 구간 1"))
    }

    @Test fun replayEqualsProductGateFedDirectly() {
        val trace = Simulator(5).generate(Scenario.PULSE_LOST, eventAtMs = event)
        val dir = session("same", SyntheticSession(trace))
        val sess = BioSession.load(dir)
        val r = ReplayReport.build(sess)
        // (1) 같은 변환 샘플을 제품 GateRunner 에 직접 넣은 결과와 틱마다 같다
        val input = ReplayMapper.input(sess)
        val gate = GateRunner()
        var ip = 0; var ia = 0; var ih = 0
        for (t in r.ticks) {
            while (ip < input.ppg.size && input.ppg[ip].atMs <= t.tMs) gate.ppg(input.ppg[ip++].sample)
            while (ia < input.accel.size && input.accel[ia].atMs <= t.tMs) gate.accel(input.accel[ia++].sample)
            while (ih < input.hr.size && input.hr[ih].atMs <= t.tMs) gate.hr(input.hr[ih++].sample)
            val d = gate.evaluate(t.tMs)
            assertEquals("t=${t.tMs}", d.verdict, t.verdict)
            assertEquals("t=${t.tMs}", d.reasons, t.reasons)
            assertEquals("t=${t.tMs}", d.hr, t.hr)
        }
        // (2) 정수로 적기 전 원 파형을 :shared PulseGate 로 돌린 결과와도 판정이 같다(단조 축으로 옮긴 같은 시각끼리)
        val shifted = com.example.guardian.shared.bio.SimTrace(
            trace.ppg.map { it.copy(atMs = it.atMs + SyntheticSession.MONO0) },
            trace.accel.map { it.copy(atMs = it.atMs + SyntheticSession.MONO0) },
            trace.hr.map { it.copy(atMs = it.atMs + SyntheticSession.MONO0) },
        )
        val direct = shifted.replay(PulseGate(), driving = GateRunner.GATE_DRIVING).toMap()
        val compared = r.ticks.count { t -> direct[t.tMs]?.let { assertEquals("t=${t.tMs}", it.verdict, t.verdict); true } == true }
        assertTrue("비교한 틱 $compared", compared >= r.ticks.size - 2)
    }

    @Test fun missingFilesBlankCellsAndTruncatedLastLineAreTolerated() {
        val s = SyntheticSession(Simulator(3).generate(Scenario.NORMAL, durationMs = 30_000))
        // AAR 없는 빌드 · 위치 권한 없음 · HS 끔 — Android 가속도와 상태 · 사건만 있다
        val lean = session("lean", s, skip = setOf(BioStream.SDK_PPG, BioStream.SDK_ACC, BioStream.SDK_HR, BioStream.HS_HR, BioStream.SPEED, BioStream.AND_GYRO, BioStream.META))
        val lr = report(lean)
        assertTrue(lr.input.isEmpty)
        assertEquals(setOf(BioStream.AND_ACC, BioStream.STATE, BioStream.EVENTS), lr.session.present)
        assertTrue(lr.ticks.isNotEmpty())
        assertEquals(lr.ticks.size, lr.bandSeconds().getValue(SpeedBand.UNKNOWN))
        val md = SummaryWriter.summary(lr)
        assertTrue(md.contains("없는 파일"))
        assertTrue(md.contains("관문 재생을 하지 못했다"))
        assertTrue(md.contains("| sdk_ppg.csv | 없음 |"))

        // 빈칸 · 빠진 열 · 잘린 마지막 줄
        val dirty = session("dirty", s)
        val ppg = File(dirty, BioStream.SDK_PPG.file)
        val lines = ppg.readLines().toMutableList()
        val n = lines.size - 1
        lines[5] = lines[5].split(",").mapIndexed { i, c -> if (i == 2) "" else c }.joinToString(",") // green 빈칸 → 그 점은 관문에 안 넣는다
        lines[6] = lines[6].split(",").mapIndexed { i, c -> if (i >= 5) "" else c }.joinToString(",") // 상태 세 칸 빈칸 → 상태 0 으로 넣는다
        lines[lines.size - 1] = lines.last().take(lines.last().length / 2) // 잘린 마지막 줄
        ppg.writeText(lines.joinToString("\n"))
        val hr = File(dirty, BioStream.SDK_HR.file)
        hr.writeText(hr.readLines().joinToString("\n") { l -> l.split(",").take(4).joinToString(",") } + "\n") // ibi 열 두 개가 아예 없음
        val acc = File(dirty, BioStream.SDK_ACC.file)
        acc.writeText(acc.readText() + "99999") // 쉼표조차 없는 마지막 줄
        val sess = BioSession.load(dirty)
        assertEquals(n, sess.ppg.size) // 잘린 줄도 recv · sample 시각은 살아 있다
        assertNull(sess.ppg[4].green)
        assertNull(sess.ppg[5].greenStatus)
        assertTrue(sess.ppg.last().red == null || sess.ppg.last().redStatus == null)
        assertTrue(sess.hr.all { it.ibi.isEmpty() && it.ibiStatus.isEmpty() })
        assertEquals(1, sess.skipped[BioStream.SDK_ACC])
        val r = ReplayReport.build(sess)
        // 세 값이 다 있는 점만 관문에 넣는다 — 녹색 빈칸 줄과(잘렸다면) 마지막 줄이 빠진다
        assertEquals(sess.ppg.count { it.green != null && it.ir != null && it.red != null }, r.input.ppg.size)
        assertTrue(r.input.ppg.size in (n - 2)..(n - 1))
        assertEquals(0, r.input.ppg.count { it.sample.status != 0 })
        assertTrue(SummaryWriter.summary(r).contains("sdk_acc.csv 1"))
    }

    @Test fun recvBasisIsLaterThanSampleBasisWhenScreenOffBatches() {
        val dir = session("batched", SyntheticSession(Simulator(3).generate(Scenario.PULSE_LOST, eventAtMs = event), screenOffFromMs = SyntheticSession.MONO0 + 10_000, batchMs = 2_000))
        val r = report(dir)
        // A3(박동 공백)는 지금 시각으로 재서 묶음에도 늦지 않는다. PPG 가 필요한 「없음」(A1 · B)은 묶음만큼 늦는다
        val sampleAbsent = r.ticks.first { it.verdict == WristVerdict.PULSE_ABSENT }.tMs
        val recvAbsent = r.ticksRecv.first { it.verdict == WristVerdict.PULSE_ABSENT }.tMs
        assertTrue("도착 기준이 늦다 sample=$sampleAbsent recv=$recvAbsent", recvAbsent > sampleAbsent)
        assertTrue(recvAbsent - sampleAbsent <= 3_000)
        val delays = r.basisDelays()
        val absentEp = delays.last { it.first.worst == WristVerdict.PULSE_ABSENT }
        assertEquals(recvAbsent - sampleAbsent, absentEp.second)
        assertTrue(r.differingSeconds() > 0)
        // 도착 지연 분포 — 켜짐은 30ms, 꺼짐은 묶음 경계까지(최대 2초 + 30ms)
        val on = r.arrivalDelay(true).p!!
        val off = r.arrivalDelay(false).p!!
        assertEquals(0, r.negativeDelays())
        assertEquals(30.0, on.max, 0.0)
        assertTrue(off.median > 30 && off.max <= 2_030)
        val md = SummaryWriter.summary(r)
        assertTrue(md.contains("도착 기준에서 최악 판정까지 늦은 정도"))
        assertTrue(md.contains("| 맥박 없음 | ${(recvAbsent - sampleAbsent) / 1000}초 |"))
    }

    @Test fun summaryNumbersMatchHandComputation() {
        val dur = 120_000L
        val s = SyntheticSession(
            Simulator(3).generate(Scenario.NORMAL, durationMs = dur),
            // 처음 30초 정지, 그다음 60초 50 km/h, 마지막 30초 속도 없음
            speedKmhAt = { t -> val e = t - SyntheticSession.MONO0; if (e < 30_000) 0.0 else if (e < 90_000) 50.0 else null },
            ppgStatusAt = { i -> if (i % 5 == 4) -1 else 0 }, // 5개 중 1개 상태 −1 → 유효 80%
            extraBadIbi = true, batteryStart = 90, batteryEnd = 80, hs = true,
        )
        val dir = session("hand", s)
        val r = report(dir)
        // 틱 — t0 = 100000(상태 첫 줄), 끝 = 마지막 샘플 119960 + 30ms 도착 → 틱 101000..119000 = 119개
        assertEquals(100_000L, r.t0)
        assertEquals(119, r.ticks.size)
        // 속도 구간 — 속도 줄은 100000, 101000, … 에 있고 틱은 그 시각의 줄을 본다. 틱 101000~129000 정지(29), 130000~189000 이동(60), 190000~219000 속도 빈칸(30)
        val bands = r.bandSeconds()
        assertEquals(29, bands.getValue(SpeedBand.STOPPED))
        assertEquals(60, bands.getValue(SpeedBand.MOVING))
        assertEquals(30, bands.getValue(SpeedBand.UNKNOWN))
        // 스트림 건수 — 25Hz × 120초 = 3000, 심박 1Hz = 120, 속도 · 상태 = 120초 ÷ 1초 + 1 = 120(100000..119960 → 100000, 101000, …, 119000)
        val st = r.streamStats().associateBy { it.stream }
        assertEquals(3000, st.getValue(BioStream.SDK_PPG).n)
        assertEquals(3000, st.getValue(BioStream.SDK_ACC).n)
        assertEquals(120, st.getValue(BioStream.SDK_HR).n)
        assertEquals(120, st.getValue(BioStream.STATE).n)
        assertEquals(40L, st.getValue(BioStream.SDK_PPG).medianIntervalMs)
        assertEquals(1000L, st.getValue(BioStream.SDK_HR).maxGapMs)
        assertEquals(0, st.getValue(BioStream.AND_GYRO).n)
        // PPG 품질 — 상태 0 이 5개 중 4개
        assertEquals(2400 to 3000, r.ppgValidShare())
        assertEquals(mapOf(-1 to 600, 0 to 2400), r.ppgStatusDist { it.greenStatus })
        // IBI — 줄마다 유효 1개 + 상태 −1 인 3000ms 1개
        val ic = r.ibiCounts()
        assertEquals(240, ic.all); assertEquals(120, ic.status0); assertEquals(120, ic.allInRange); assertEquals(120, ic.status0InRange)
        assertEquals(120, ic.hrRowsWithValid); assertEquals(120, ic.hrRowsWithAnyInRange)
        assertEquals(mapOf(-1 to 120, 0 to 120), r.ibiStatusDist())
        assertEquals(mapOf<Int?, Int>(1 to 120), r.hrStatusDist())
        // 배터리 90 → 80 이 119초 동안 = 시간당 302.5%p
        assertEquals(90 to 80, r.batteryFirstLast())
        assertEquals(10 / (119_000 / 3.6e6), r.batteryDropPerHour()!!, 1e-6)
        // 가속도 — 시뮬레이터 정지 손목은 (0, 0, 9.81) 근처
        assertEquals(9.81, r.sdkAccMagnitudeMedian()!!, 0.05)
        assertEquals(9.81, r.sdkAccMagnitudeMedian(SpeedBand.STOPPED)!!, 0.05)
        assertEquals(1.0, r.sdkAccMagnitudeMedian()!! / r.androidAccMagnitudeMedian()!!, 0.01)
        // 손목 흔들림 — 정지 손목이라 문턱 아래 100%
        val below = r.wristBelowTheta()
        assertTrue(below.second > 0); assertEquals(below.second, below.first)
        // HS — bpm 은 SDK + 1 로 적었으니 차이 1
        assertTrue(r.hsEnabled)
        assertEquals(1.0, r.hsVsSdkDiff()!!.median, 0.0)
        assertEquals(mapOf<String?, Int>("ACCURACY_MEDIUM" to 120), r.hsStatusDist())
        // 사건 · 상태 표
        assertEquals(mapOf("start" to 1, "stop" to 1), r.eventsByKind())
        assertTrue(r.monitoringSpans().isEmpty() && r.ioErrorSpans().isEmpty())
        assertEquals(SyntheticSession.OFFSET, r.session.clock.wallMinusMono)
        assertEquals("state.csv", r.session.clock.source)
        val md = SummaryWriter.summary(r)
        assertTrue(md.contains("2400/3000 (80.0%)"))
        // 유효한 초 — 분모는 세션 전체 틱(119), PPG 가 있는 틱도 119(첫 틱부터 샘플이 있다)
        val pv = r.ppgValidSeconds()
        assertEquals(119, pv.allTicks); assertEquals(119, pv.ticksWithPpg); assertEquals(119, pv.good)
        assertTrue(r.warnings().isEmpty())
        assertEquals(0L, r.clockSpreadMs())
        assertTrue(md.contains("90% → 80%"))
        assertTrue(md.contains("| sdk_ppg.csv | 3000 | 40 |"))
    }

    @Test fun mainWritesVerdictCsvAndSummaryIntoReplayFolder() {
        val dir = session("main", SyntheticSession(Simulator(3).generate(Scenario.NORMAL, durationMs = 30_000)))
        val out = BioReplayMain.run(dir)
        assertEquals(File(dir, SummaryWriter.OUT_DIR), out)
        val csv = File(out, SummaryWriter.VERDICT_CSV).readLines()
        assertEquals(SummaryWriter.VERDICT_HEADER, csv[0])
        val r = report(dir)
        assertEquals(r.ticks.size + 1, csv.size)
        assertEquals(SummaryWriter.VERDICT_HEADER.split(",").size, csv[1].split(",").size)
        assertTrue(File(out, SummaryWriter.SUMMARY_MD).readText().startsWith("# 생체 기록 재생 요약 — main"))
        // 좌표 열 · 원신호 값은 결과에 없다(G2 · G3) — 판정 · 이유 · 속도 구간만
        assertFalse(csv[0].contains("lat") || csv[0].contains("green"))
    }

    /** 폴더의 Sensor SDK 세 파일에서 단조 시각이 [keep] 을 만족하는 줄만 남긴다(끊김 · 공백 세션 만들기). */
    private fun cutSdk(dir: File, keep: (Long) -> Boolean) {
        for (s in listOf(BioStream.SDK_PPG, BioStream.SDK_ACC, BioStream.SDK_HR)) {
            val f = File(dir, s.file)
            val lines = f.readLines()
            f.writeText((listOf(lines[0]) + lines.drop(1).filter { keep(it.split(",")[1].toLong() - SyntheticSession.OFFSET) }).joinToString("\n") + "\n")
        }
    }

    @Test fun sdkStreamCutMidSessionCountsAsNoDataNotNormal() {
        val dir = session("cut", SyntheticSession(Simulator(3).generate(Scenario.NORMAL, durationMs = 300_000)))
        cutSdk(dir) { it < SyntheticSession.MONO0 + 150_000 } // 150초에서 SDK 가 끊김(Android 가속도 · 상태 · 속도는 계속)
        val r = report(dir)
        assertEquals(299, r.ticks.size)
        val nd = r.noDataTicks(TimeBasis.RECV)
        assertTrue("끊긴 뒤 150초 가까이 자료 없음 $nd", nd in 145..150)
        val c = r.verdictCounts(TimeBasis.RECV)
        assertEquals(nd, c.count(ReplayReport.Cat.NO_DATA))
        // 정상(자료 있음)은 자료 있는 틱(150초 + 신선도 창 3초)을 넘지 못한다 — 끊긴 뒤 150초가 정상으로 들어가면 안 된다
        assertEquals(299 - nd, c.dataTicks)
        assertEquals(c.dataTicks, c.count(ReplayReport.Cat.NORMAL))
        assertTrue(c.count(ReplayReport.Cat.NORMAL) <= 153)
        assertTrue(r.ticks.drop(160).all { !it.hasData })
        val md = SummaryWriter.summary(r)
        assertTrue(md.contains("## 경고"))
        assertTrue(md.contains("자료 없음(도착 기준)"))
        assertFalse("자료 없는 구간이 정상 100% 로 보이면 안 된다", md.contains("| 정상(자료 있음) | 299 "))
    }

    @Test fun ppgValidShareUsesWholeSessionAsDenominator() {
        val dir = session("gap", SyntheticSession(Simulator(3).generate(Scenario.NORMAL, durationMs = 300_000)))
        cutSdk(dir) { it < SyntheticSession.MONO0 + 100_000 || it >= SyntheticSession.MONO0 + 160_000 } // 가운데 60초 공백
        val r = report(dir)
        val pv = r.ppgValidSeconds()
        assertEquals(r.ticks.size, pv.allTicks)
        assertTrue("PPG 있는 틱 ${pv.ticksWithPpg}", pv.ticksWithPpg in 238..241)
        assertTrue("유효한 초 ${pv.good}", pv.good in 236..241)
        assertTrue("머리 값은 100% 가 아니다", pv.good < pv.allTicks * 0.85)
        assertTrue(r.noDataTicks(TimeBasis.RECV) in 55..60)
        val md = SummaryWriter.summary(r)
        assertTrue(md.contains("세션 전체 틱 기준"))
        assertTrue(md.contains("참고만"))
    }

    @Test fun flowReplayCountsMatchDirectFlowMachineAndHandDerivation() {
        // 300틱, 50 km/h 주행, 100~135번째 틱(36초)만 맥박 약함
        val ticks = (0 until 300).map { i ->
            val t = SyntheticSession.MONO0 + (i + 1) * 1_000L
            Tick(t, if (i in 100..135) WristVerdict.PULSE_WEAK else WristVerdict.NORMAL, emptySet(), 70)
        }
        val speed = List(300) { 50.0 }
        val none = FlowReplay.run(ticks, speed, FlowReplay.ResponseModel.NONE)
        val r5 = FlowReplay.run(ticks, speed, FlowReplay.ResponseModel.AFTER_5S)
        val r20 = FlowReplay.run(ticks, speed, FlowReplay.ResponseModel.AFTER_20S)
        // 손 계산 — 무응답: 경보 1 → 15초 뒤 소리 → 35초 뒤 주행 중 무응답(S3) · 보호자 사건 문자 1
        assertEquals(1, none.alertStarts); assertEquals(1, none.stage2Starts); assertEquals(1, none.s3Entries); assertEquals(0, none.s4Entries)
        assertEquals(1, none.incidentSms); assertEquals(0, none.cancelSms); assertEquals(0, none.compose119); assertEquals(0, none.responses)
        // 5초 뒤 응답: 105 에 응답 → S1R, 나쁜 판정이 30초 더 이어져 135 에 재경보(경보 2) → 140 에 다시 응답. 무응답 단계 · 문자 없음
        assertEquals(2, r5.alertStarts); assertEquals(0, r5.s3Entries); assertEquals(0, r5.incidentSms); assertEquals(2, r5.responses)
        // 20초 뒤 응답: 120 에 응답, 남은 나쁜 판정 15초 < 30초 → 경보 1
        assertEquals(1, r20.alertStarts); assertEquals(0, r20.s3Entries); assertEquals(0, r20.incidentSms); assertEquals(1, r20.responses)
        // 제품 FlowMachine 을 직접 같은 입력으로 돌린 결과와 같다(무응답)
        val m = com.example.guardian.shared.flow.FlowMachine()
        val log = ArrayList<com.example.guardian.shared.flow.Directive>()
        fun go(i: com.example.guardian.shared.flow.FlowInput) { log += m.handle(i) }
        val t0 = ticks.first().tMs - 1_000
        val v = com.example.guardian.shared.flow.VehicleState(speedKmh = 50.0, speedSource = com.example.guardian.shared.flow.SpeedSource.PHONE_GPS)
        go(com.example.guardian.shared.flow.FlowInput.Vehicle(v, t0))
        go(com.example.guardian.shared.flow.FlowInput.ConsentsChanged(com.example.guardian.shared.report.Consents(true, true, true), t0))
        go(com.example.guardian.shared.flow.FlowInput.Power(true, t0))
        go(com.example.guardian.shared.flow.FlowInput.Wrist(WristVerdict.NORMAL, t0))
        var s3 = 0; var prev = m.state
        for (t in ticks) {
            go(com.example.guardian.shared.flow.FlowInput.Vehicle(v, t.tMs)); go(com.example.guardian.shared.flow.FlowInput.Wrist(t.verdict, t.tMs))
            if (m.state != prev) { if (m.state == com.example.guardian.shared.flow.FlowState.S3) s3++; prev = m.state }
        }
        assertEquals(log.count { it is com.example.guardian.shared.flow.Directive.AlertStart && it.level == 1 }, none.alertStarts)
        assertEquals(log.count { it is com.example.guardian.shared.flow.Directive.SendGuardianSms && it.kind == com.example.guardian.shared.flow.GuardianSmsKind.INCIDENT }, none.incidentSms)
        assertEquals(s3, none.s3Entries)
        assertEquals(com.example.guardian.shared.flow.FlowState.S3, m.state)
    }

    @Test fun clockSpreadRegressionAndNegativeDelayAreWarned() {
        val dir = session("clock", SyntheticSession(Simulator(3).generate(Scenario.NORMAL, durationMs = 60_000)))
        // state.csv — 30초부터 벽시계가 800ms 앞으로 뛴다
        val st = File(dir, BioStream.STATE.file)
        st.writeText(st.readLines().mapIndexed { i, l ->
            if (i == 0) l else l.split(",").toMutableList().also { c -> if (c[0].toLong() >= SyntheticSession.MONO0 + 30_000) c[1] = (c[1].toLong() + 800).toString() }.joinToString(",")
        }.joinToString("\n") + "\n")
        // sdk_ppg.csv — 10번째 줄의 샘플 시각을 뒤로(역행), 20번째 줄의 샘플 시각을 받은 시각보다 뒤로(음수 지연)
        val ppg = File(dir, BioStream.SDK_PPG.file)
        ppg.writeText(ppg.readLines().mapIndexed { i, l ->
            val c = l.split(",").toMutableList()
            when (i) {
                10 -> c[1] = (c[1].toLong() - 5_000).toString()
                20 -> c[1] = (c[0].toLong() + SyntheticSession.OFFSET + 3_000).toString()
            }
            c.joinToString(",")
        }.joinToString("\n") + "\n")
        val r = report(dir)
        assertEquals(800L, r.clockSpreadMs())
        assertEquals(mapOf(BioStream.SDK_PPG to 2), r.session.timeRegressions()) // 뒤로 간 줄과 그다음 줄에서 되돌아오는 줄
        assertTrue(r.negativeDelays() >= 1)
        val w = r.warnings()
        assertTrue(w.any { it.contains("벽시계 − 단조 시계 폭이 800 ms") })
        assertTrue(w.any { it.contains("샘플 시각 역행") })
        assertTrue(w.any { it.contains("음수 지연") })
        val md = SummaryWriter.summary(r)
        assertTrue(md.contains("## 경고"))
        assertTrue(md.contains("재생 ≠ 감시"))
    }

    @Test fun syntheticSessionIsMarkedAndNoResponseReachesGuardianSms() {
        val dir = session("composite", SyntheticSession(SyntheticSessionMain.composite(), speedKmhAt = { 50.0 }, screenOffFromMs = SyntheticSession.MONO0 + 60_000))
        val r = report(dir)
        assertTrue(r.session.synthetic)
        val md = SummaryWriter.summary(r)
        assertTrue(md.contains("합성 세션이다"))
        assertFalse(md.contains("정상 운전 중 기록이다"))
        assertTrue(md.contains("사내 분석 문서"))
        val none = r.flowResults(TimeBasis.RECV).first { it.model == FlowReplay.ResponseModel.NONE }
        assertTrue("맥 소실 구간에서 무응답이면 보호자 사건 문자까지 간다", none.incidentSms >= 1 && none.s3Entries >= 1)
        assertTrue(none.alertStarts >= 1)
        assertTrue(md.contains("| 무응답 | ${none.alertStarts} |"))
    }

    @Test fun csvSplitHandlesQuotedEventDetail() {
        val row = BioProbeCsv.event(5L, "sdk_connected", "trackers=[A, B] say \"hi\"")
        assertEquals(listOf("5", "sdk_connected", "trackers=[A, B] say \"hi\""), CsvTable.split(row))
    }
}
