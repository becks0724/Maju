// 재생 결과 집계 — 판정 분포 · 거짓 경보 후보 · 규칙별 성립 · PPG 품질 · 스트림 주기 · 도착 지연 · 가속도 환산 점검 · HS 비교 · 배터리 · 사건(숫자만, 글은 SummaryWriter)
package com.example.guardian.watch.replay

import com.example.guardian.shared.bio.GateParams
import com.example.guardian.shared.bio.GateReason
import com.example.guardian.shared.bio.Spectral
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowParams
import com.example.guardian.watch.probe.BioStream
import kotlin.math.abs
import kotlin.math.sqrt

/** 속도 구간 — 5 km/h 초과 이동 · 그 이하 정지 · 최근 5초 안 속도 값 없음. */
enum class SpeedBand { MOVING, STOPPED, UNKNOWN }

data class Percentiles(val n: Int, val median: Double, val p90: Double, val max: Double) {
    companion object {
        fun of(v: List<Double>): Percentiles? {
            if (v.isEmpty()) return null
            val s = v.sorted()
            fun q(p: Double) = s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
            return Percentiles(s.size, q(0.5), q(0.9), s.last())
        }
    }
}

/** 나쁜 판정(맥박 약함 · 없음)이 이어진 구간. [endMs] 는 마지막 나쁜 틱의 시각(포함). */
data class Episode(val startMs: Long, val endMs: Long, val worst: WristVerdict) {
    val seconds: Int get() = ((endMs - startMs) / Replayer.EVERY_MS + 1).toInt()
}

data class StreamStat(val stream: BioStream, val n: Int, val medianIntervalMs: Long?, val maxGapMs: Long?, val maxGapAtMs: Long?, val skipped: Int)

/** 상태 표(`monitoring` · `io_errors`)가 켜져 있던 구간. */
data class Span(val startMs: Long, val endMs: Long)

/**
 * 세션 하나의 재생 결과. 틱 배열([ticks] · [ticksRecv] · [bands] · [speedKmh] · [wristStd] · [screenOn])은 같은 길이 · 같은 시각이다.
 * 판정 자체는 제품 관문이 냈고, 여기서는 세기만 한다. [wristStd] 만 분포를 보려고 관문과 같은 창(10초 가속도 크기 표준편차)을 따로 계산했다.
 */
class ReplayReport(
    val session: BioSession,
    val input: ReplayInput,
    val t0: Long,
    val tEnd: Long,
    val ticks: List<Tick>,
    val ticksRecv: List<Tick>,
    val params: GateParams,
) {
    val flow = FlowParams()

    // 틱 시각 → 속도 · 화면 조회용(아래 bands 보다 먼저 초기화돼야 한다)
    private val speedSorted = session.speed.sortedBy { it.recvMs }
    private val speedKeys = speedSorted.map { it.recvMs }
    private val stateSorted = session.state.sortedBy { it.recvMs }
    private val stateKeys = stateSorted.map { it.recvMs }

    val durationS: Long get() = (tEnd - t0) / 1000
    val hours: Double get() = durationS / 3600.0
    val bands: List<SpeedBand> = ticks.map { bandAt(it.tMs) }
    val speedKmh: List<Double?> = ticks.map { speedAt(it.tMs)?.times(3.6) }
    val screenOn: List<Boolean?> = ticks.map { screenAt(it.tMs) }
    val wristStd: List<Double?> = wristStdSeries()

    fun ticks(basis: TimeBasis) = if (basis == TimeBasis.SAMPLE) ticks else ticksRecv

    // ── 판정 분포 ──

    /** 요약 표의 분류 — 관문 NORMAL 은 자료가 있던 틱만 「정상」, 자료 없이 나온 NORMAL 은 「자료 없음」. 나쁜 판정은 자료 유무와 무관하게 센다. */
    enum class Cat(val label: String) {
        NORMAL("정상(자료 있음)"), NO_DATA("자료 없음(관문은 정상이나 근거 없음)"), CHECK_WEAR("착용 확인"), PULSE_WEAK("맥박 약함"), PULSE_ABSENT("맥박 없음");

        companion object {
            fun of(t: Tick): Cat = when (t.verdict) {
                WristVerdict.NORMAL -> if (t.hasData) NORMAL else NO_DATA
                WristVerdict.CHECK_WEAR -> CHECK_WEAR
                WristVerdict.PULSE_WEAK -> PULSE_WEAK
                WristVerdict.PULSE_ABSENT -> PULSE_ABSENT
            }
        }
    }

    /** 분류별 틱 수와 분모 둘(자료 있는 틱 · 전체 틱). */
    data class CatCounts(val counts: Map<Cat, Int>, val dataTicks: Int, val allTicks: Int) {
        fun count(c: Cat) = counts.getValue(c)
    }

    fun verdictCounts(basis: TimeBasis, band: SpeedBand? = null): CatCounts {
        val m = Cat.entries.associateWith { 0 }.toMutableMap()
        var data = 0; var all = 0
        ticks(basis).forEachIndexed { i, t ->
            if (band != null && bands[i] != band) return@forEachIndexed
            val c = Cat.of(t)
            m[c] = m.getValue(c) + 1
            all++
            if (c != Cat.NO_DATA) data++
        }
        return CatCounts(m, data, all)
    }

    /** 이전 이름 호환 — 관문 판정 그대로의 틱 수(자료 유무 무관). */
    fun verdictSeconds(basis: TimeBasis, band: SpeedBand? = null): Map<WristVerdict, Int> {
        val m = WristVerdict.entries.associateWith { 0 }.toMutableMap()
        ticks(basis).forEachIndexed { i, t -> if (band == null || bands[i] == band) m[t.verdict] = m.getValue(t.verdict) + 1 }
        return m
    }

    fun noDataTicks(basis: TimeBasis): Int = ticks(basis).count { !it.hasData }
    fun noDataShare(basis: TimeBasis): Double = if (ticks.isEmpty()) 0.0 else noDataTicks(basis).toDouble() / ticks.size

    fun bandSeconds(): Map<SpeedBand, Int> = SpeedBand.entries.associateWith { b -> bands.count { it == b } }

    // ── 흐름 재생(경보 지시) ──

    fun flowResults(basis: TimeBasis = TimeBasis.RECV): List<FlowReplay.Result> =
        FlowReplay.ResponseModel.entries.map { FlowReplay.run(ticks(basis), speedKmh, it, flow) }

    // ── 거짓 경보 후보 ──

    fun episodes(basis: TimeBasis): List<Episode> {
        val out = ArrayList<Episode>()
        var start: Long? = null; var last = 0L; var worst = WristVerdict.NORMAL
        for (t in ticks(basis)) {
            if (bad(t.verdict)) {
                if (start == null) { start = t.tMs; worst = t.verdict }
                if (severity(t.verdict) > severity(worst)) worst = t.verdict
                last = t.tMs
            } else if (start != null) {
                out += Episode(start, last, worst); start = null
            }
        }
        if (start != null) out += Episode(start, last, worst)
        return out
    }

    /** 흐름 S0 에서는 나쁜 판정 첫 틱에 바로 경보한다(FlowMachine.onVerdict) — 모든 구간이 경보 후보다. 실제 경보 수는 [flowResults]. */
    fun alertCandidates(basis: TimeBasis) = episodes(basis)

    /** 응답 뒤(S1R · S2) 재경보 기준 — 나쁜 판정 시작 틱부터 realertStepsMs[0](30초)이 지난 틱까지 이어진 구간(`at - weakSince >= 30000`). */
    fun realertCandidates(basis: TimeBasis) = episodes(basis).filter { it.endMs - it.startMs >= flow.realertStepsMs.first() }

    fun perHour(n: Int): Double? = if (hours > 0) n / hours else null

    /**
     * 샘플 기준 구간마다 — 도착 기준이 그 구간의 최악 판정(약함이면 약함 이상, 없음이면 없음)에 처음 닿은 시각까지의 지연(ms).
     * 구간 시작부터 끝 + 10초 안에 없으면 null. A3(박동 공백)는 지금 시각으로 재므로 묶음 도착에도 늦지 않고, A1 · B 는 PPG 가 와야 해서 늦는다.
     */
    fun basisDelays(): List<Pair<Episode, Long?>> {
        val sample = episodes(TimeBasis.SAMPLE)
        return sample.map { e ->
            val firstSample = ticks.first { it.tMs >= e.startMs && severity(it.verdict) >= severity(e.worst) }.tMs
            val firstRecv = ticksRecv.firstOrNull { it.tMs >= e.startMs && it.tMs <= e.endMs + 10_000 && severity(it.verdict) >= severity(e.worst) }?.tMs
            e to firstRecv?.let { it - firstSample }
        }
    }

    fun differingSeconds(): Int = ticks.indices.count { ticks[it].verdict != ticksRecv[it].verdict }

    // ── 규칙별 성립 ──

    /** (성립 틱 수, 전체 틱 수). */
    fun reasonRate(reason: GateReason, band: SpeedBand? = null, basis: TimeBasis = TimeBasis.SAMPLE): Pair<Int, Int> {
        var hit = 0; var total = 0
        ticks(basis).forEachIndexed { i, t ->
            if (band == null || bands[i] == band) { total++; if (reason in t.reasons) hit++ }
        }
        return hit to total
    }

    fun wristStdPercentiles(band: SpeedBand? = null): Percentiles? =
        Percentiles.of(wristStd.filterIndexed { i, v -> v != null && (band == null || bands[i] == band) }.map { it!! })

    /** 손목 흔들림이 θ_drive 아래인 틱 비율(창이 찬 틱만). */
    fun wristBelowTheta(band: SpeedBand? = null): Pair<Int, Int> {
        var hit = 0; var total = 0
        wristStd.forEachIndexed { i, v -> if (v != null && (band == null || bands[i] == band)) { total++; if (v < params.thetaDrive) hit++ } }
        return hit to total
    }

    // ── PPG · 심박 품질 ──

    fun ppgStatusDist(pick: (PpgRow) -> Int?): Map<Int?, Int> = dist(session.ppg.map(pick))

    /** 녹색 상태 0 인 샘플 비율(상태 칸이 있는 샘플 기준). [미검증] 0 = 정상 전제. */
    fun ppgValidShare(): Pair<Int, Int> {
        val withStatus = session.ppg.mapNotNull { it.greenStatus }
        return withStatus.count { it == 0 } to withStatus.size
    }

    /** 유효한 초(틱 창 (t−1s, t] 에 샘플 20개 이상 · 80% 이상 상태 0) · PPG 가 한 점이라도 있는 틱 · 세션 전체 틱. 머리 값은 전체 틱 기준이다. */
    data class PpgValid(val good: Int, val ticksWithPpg: Int, val allTicks: Int)

    fun ppgValidSeconds(): PpgValid {
        val off = session.clock.wallMinusMono
        val times = if (off == null) emptyList() else session.ppg.map { (it.sampleEpochMs - off) to it.greenStatus }.sortedBy { it.first }
        var lo = 0; var hi = 0; var good = 0; var with = 0
        for (t in ticks) {
            while (hi < times.size && times[hi].first <= t.tMs) hi++
            while (lo < hi && times[lo].first <= t.tMs - Replayer.EVERY_MS) lo++
            val n = hi - lo
            if (n == 0) continue
            with++
            var ok = 0
            for (i in lo until hi) if (times[i].second == 0) ok++
            if (n >= 20 && ok >= n * 0.8) good++
        }
        return PpgValid(good, with, ticks.size)
    }

    fun hrStatusDist(rows: List<HrRow> = session.hr): Map<Int?, Int> = dist(rows.map { it.status })
    fun ibiStatusDist(): Map<Int?, Int> = dist(session.hr.flatMap { it.ibiStatus.map<Int, Int?> { s -> s } })

    /** IBI 건수 — 전부 · 상태 0 · 관문 범위(250–2000ms) 안인 것 전부 · 상태 0 이면서 범위 안. */
    data class IbiCounts(val all: Int, val status0: Int, val allInRange: Int, val status0InRange: Int, val hrRowsWithValid: Int, val hrRowsWithAnyInRange: Int)

    fun ibiCounts(): IbiCounts {
        var all = 0; var s0 = 0; var allIn = 0; var s0In = 0; var rowsValid = 0; var rowsAny = 0
        for (r in session.hr) {
            var anyValid = false; var anyIn = false
            r.ibi.forEachIndexed { i, v ->
                all++
                val ok = r.ibiStatus.getOrNull(i) == 0
                val inRange = v in 250..2000
                if (ok) s0++
                if (inRange) { allIn++; anyIn = true }
                if (ok && inRange) { s0In++; anyValid = true }
            }
            if (anyValid) rowsValid++
            if (anyIn) rowsAny++
        }
        return IbiCounts(all, s0, allIn, s0In, rowsValid, rowsAny)
    }

    // ── 스트림 건수 · 주기 · 공백 · 도착 지연 ──

    fun streamStats(): List<StreamStat> {
        fun stat(s: BioStream, times: List<Long>): StreamStat {
            val t = times.sorted()
            val d = t.zipWithNext { a, b -> b - a }
            val gapIdx = d.indices.maxByOrNull { d[it] }
            return StreamStat(s, t.size, if (d.isEmpty()) null else BioSession.median(d), gapIdx?.let { d[it] }, gapIdx?.let { t[it] }, session.skipped[s] ?: 0)
        }
        val off = session.clock.wallMinusMono
        fun epoch(v: List<Long>) = if (off == null) emptyList() else v.map { it - off }
        return listOf(
            stat(BioStream.SDK_PPG, epoch(session.ppg.map { it.sampleEpochMs })),
            stat(BioStream.SDK_ACC, epoch(session.acc.map { it.sampleEpochMs })),
            stat(BioStream.SDK_HR, epoch(session.hr.map { it.sampleEpochMs })),
            stat(BioStream.AND_ACC, session.andAcc.map { it.sampleBootMs }),
            stat(BioStream.AND_GYRO, session.andGyro.map { it.sampleBootMs }),
            stat(BioStream.HS_HR, session.hsHr.map { it.sampleBootMs }),
            stat(BioStream.SPEED, session.speed.map { it.recvMs }),
            stat(BioStream.STATE, session.state.map { it.recvMs }),
        )
    }

    /** 도착 지연 분포(0 이상만)와 음수 건수 — 음수는 샘플 시각이 받은 시각보다 뒤라는 뜻이라 시계 어긋남으로 따로 센다. */
    data class ArrivalDelay(val p: Percentiles?, val negatives: Int)

    /** Sensor SDK 세 스트림의 도착 지연(받은 시각 − 샘플 시각, ms). 화면 켜짐 · 꺼짐은 받은 시각의 상태 표 기준(null = 상태 모름). */
    fun arrivalDelay(screen: Boolean?): ArrivalDelay {
        val all = input.ppg.map { it.recvMs to it.atMs } + input.accel.map { it.recvMs to it.atMs } + input.hr.map { it.recvMs to it.atMs }
        val d = all.filter { screenAt(it.first) == screen }.map { it.first - it.second }
        return ArrivalDelay(Percentiles.of(d.filter { it >= 0 }.map { it.toDouble() }), d.count { it < 0 })
    }

    fun negativeDelays(): Int = listOf(true, false, null).sumOf { arrivalDelay(it).negatives }

    /** state.csv 의 (벽시계 − 단조) 폭(ms). [CLOCK_SPREAD_WARN_MS] 를 넘으면 경고. */
    fun clockSpreadMs(): Long? = session.clockSpread?.let { it.second - it.first }

    /** 요약 맨 위에 올릴 경고 — 자료 없음 비율 · 시계 폭 · 샘플 시각 역행 · 음수 지연. */
    fun warnings(): List<String> = buildList {
        val nd = noDataShare(TimeBasis.RECV)
        if (nd > NO_DATA_WARN) add("세션 틱의 ${SummaryWriter.fmt(nd * 100, 1)}% 가 Sensor SDK 자료 없음(도착 기준) — 그 시간은 판정이 없었던 것이지 정상이 아니다. 아래 비율은 자료 있는 틱 기준과 전체 기준을 함께 본다.")
        clockSpreadMs()?.let { if (it > CLOCK_SPREAD_WARN_MS) add("state.csv 의 벽시계 − 단조 시계 폭이 ${it} ms (> $CLOCK_SPREAD_WARN_MS) — 벽시계가 움직였다. 에포크 샘플 시각을 한 값(중앙값)으로 옮겼으므로 재생의 시각 축은 감시와 다를 수 있다(재생 ≠ 감시).") }
        val reg = session.timeRegressions()
        if (reg.isNotEmpty()) add("샘플 시각 역행 — " + reg.entries.joinToString(" · ") { "${it.key.file} ${it.value}건" } + ". 재생은 샘플 시각 순으로 넣고(감시는 도착 순), 역행 구간의 판정은 감시와 다를 수 있다.")
        val neg = negativeDelays()
        if (neg > 0) add("받은 시각보다 뒤인 샘플 시각(음수 지연) ${neg}건 — 시계 어긋남. 지연 분포에서는 뺐다.")
    }

    // ── 가속도 환산 점검 ──

    /** Sensor SDK 가속도 크기(m/s², 환산 뒤) 중앙값 — [band] 가 있으면 그 속도 구간의 샘플만. */
    fun sdkAccMagnitudeMedian(band: SpeedBand? = null): Double? =
        median(input.accel.filter { band == null || bandAt(it.atMs) == band }.map { val a = it.sample; sqrt(a.x * a.x + a.y * a.y + a.z * a.z) })

    fun androidAccMagnitudeMedian(band: SpeedBand? = null): Double? =
        median(session.andAcc.filter { it.x != null && it.y != null && it.z != null && (band == null || bandAt(it.sampleBootMs) == band) }
            .map { sqrt(it.x!! * it.x + it.y!! * it.y + it.z!! * it.z) })

    // ── Health Services ──

    val hsEnabled: Boolean get() = session.hsHr.isNotEmpty()
    fun hsSpan(): Span? = session.hsHr.takeIf { it.isNotEmpty() }?.let { Span(it.minOf { r -> r.recvMs }, it.maxOf { r -> r.recvMs }) }
    fun hsStatusDist(): Map<String?, Int> = dist(session.hsHr.map { it.sensorStatus })

    /** HS 심박 − Sensor SDK 심박(상태 1, 샘플 시각 차 [HS_PAIR_MS] 미만인 짝) 차이의 절대값 분포. */
    fun hsVsSdkDiff(): Percentiles? {
        val sdk = input.hr.filter { it.sample.status == 1 && it.sample.bpm > 0 }.sortedBy { it.atMs }
        if (sdk.isEmpty()) return null
        val diffs = session.hsHr.mapNotNull { h ->
            val bpm = h.bpm ?: return@mapNotNull null
            val near = sdk.minByOrNull { abs(it.atMs - h.sampleBootMs) } ?: return@mapNotNull null
            if (abs(near.atMs - h.sampleBootMs) >= HS_PAIR_MS) null else abs(bpm - near.sample.bpm)
        }
        return Percentiles.of(diffs)
    }

    /** HS 가 켜져 있던 구간 안 · 밖의 Sensor SDK 심박 상태 분포(간섭 후보 — 안에서만 나쁜 상태가 늘면 의심). */
    fun sdkHrStatusInsideHs(): Pair<Map<Int?, Int>, Map<Int?, Int>>? {
        val span = hsSpan() ?: return null
        val (inside, outside) = session.hr.partition { it.recvMs in span.startMs..span.endMs }
        return hrStatusDist(inside) to hrStatusDist(outside)
    }

    // ── 상태 · 사건 ──

    /** 배터리 감소율(%/시간) — 상태 표 첫 · 끝 값. */
    fun batteryDropPerHour(): Double? {
        val rows = session.state.filter { it.batteryPct != null }
        if (rows.size < 2) return null
        val dtH = (rows.last().recvMs - rows.first().recvMs) / 3.6e6
        if (dtH <= 0) return null
        return (rows.first().batteryPct!! - rows.last().batteryPct!!) / dtH
    }

    fun batteryFirstLast(): Pair<Int, Int>? = session.state.mapNotNull { it.batteryPct }.let { if (it.isEmpty()) null else it.first() to it.last() }
    fun eventsByKind(): Map<String, Int> = session.events.groupingBy { it.kind }.eachCount()
    fun monitoringSpans(): List<Span> = flagSpans { it.monitoring == true }
    fun ioErrorSpans(): List<Span> = flagSpans { (it.ioErrors ?: 0L) > 0L }

    private fun flagSpans(on: (StateRow) -> Boolean): List<Span> {
        val out = ArrayList<Span>()
        var start: Long? = null; var last = 0L
        for (r in session.state.sortedBy { it.recvMs }) {
            if (on(r)) { if (start == null) start = r.recvMs; last = r.recvMs } else if (start != null) { out += Span(start, last); start = null }
        }
        if (start != null) out += Span(start, last)
        return out
    }

    // ── 틱 시각에서의 속도 · 화면 ──

    /** [tMs] 이전 5초 안의 마지막 속도 값(m/s). */
    fun speedAt(tMs: Long): Double? {
        val i = lastAtOrBefore(speedKeys, tMs) ?: return null
        // 값이 빈 줄(위치는 왔으나 속도 없음)은 그 초의 속도를 모르는 것으로 둔다
        val r = speedSorted[i]
        return if (tMs - r.recvMs <= FRESH_MS) r.speedMps else null
    }

    fun bandAt(tMs: Long): SpeedBand {
        val v = speedAt(tMs) ?: return SpeedBand.UNKNOWN
        return if (v * 3.6 > MOVING_KMH) SpeedBand.MOVING else SpeedBand.STOPPED
    }

    fun screenAt(tMs: Long): Boolean? {
        val i = lastAtOrBefore(stateKeys, tMs) ?: return null
        val r = stateSorted[i]
        return if (tMs - r.recvMs <= FRESH_MS) r.screenOn else null
    }

    private fun wristStdSeries(): List<Double?> {
        val mags = input.accel.map { it.atMs to sqrt(it.sample.x * it.sample.x + it.sample.y * it.sample.y + it.sample.z * it.sample.z) }
        val need = params.stillWindowMs * params.fs / 1000 * params.minCoverage
        var lo = 0; var hi = 0
        return ticks.map { t ->
            while (hi < mags.size && mags[hi].first <= t.tMs) hi++
            while (lo < hi && mags[lo].first <= t.tMs - params.stillWindowMs) lo++
            val v = mags.subList(lo, hi).map { it.second }
            if (v.size < need) null else Spectral.std(v)
        }
    }

    companion object {
        const val MOVING_KMH = 5.0
        const val FRESH_MS = 5_000L
        /** 자료 없음 틱 비율이 이보다 크면 요약 맨 위에 경고. */
        const val NO_DATA_WARN = 0.10
        const val CLOCK_SPREAD_WARN_MS = 500L
        /** HS · SDK 심박 짝의 샘플 시각 차 상한(미만). */
        const val HS_PAIR_MS = 1_000L

        fun bad(v: WristVerdict) = severity(v) >= 1
        fun severity(v: WristVerdict) = when (v) { WristVerdict.PULSE_WEAK -> 1; WristVerdict.PULSE_ABSENT -> 2; else -> 0 }

        /** 값별 건수 — 숫자는 숫자 순, 나머지는 글자 순, 빈칸(null)은 맨 뒤. */
        fun <K> dist(v: List<K>): Map<K, Int> = v.groupingBy { it }.eachCount().entries
            .sortedWith(compareBy<Map.Entry<K, Int>> { it.key == null }.thenBy { (it.key as? Int)?.toDouble() ?: 0.0 }.thenBy { it.key?.toString() ?: "" })
            .associate { it.key to it.value }

        fun median(v: List<Double>): Double? = if (v.isEmpty()) null else Spectral.median(v)

        /** 정렬된 [keys] 에서 [x] 이하인 마지막 자리. */
        private fun lastAtOrBefore(keys: List<Long>, x: Long): Int? {
            var lo = 0; var hi = keys.size - 1; var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (keys[mid] <= x) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return if (ans < 0) null else ans
        }

        /** 세션 전체를 재생해 보고서를 만든다. 틱 범위는 모든 스트림의 받은 시각 · 샘플 시각 중 최소 ~ 최대. */
        fun build(session: BioSession, params: GateParams = GateParams()): ReplayReport {
            val input = ReplayMapper.input(session)
            val recvAll = session.ppg.map { it.recvMs } + session.acc.map { it.recvMs } + session.hr.map { it.recvMs } +
                session.andAcc.map { it.recvMs } + session.andGyro.map { it.recvMs } + session.hsHr.map { it.recvMs } +
                session.speed.map { it.recvMs } + session.state.map { it.recvMs } + session.events.map { it.recvMs }
            val sampleAll = input.ppg.map { it.atMs } + input.accel.map { it.atMs } + input.hr.map { it.atMs }
            val all = recvAll + sampleAll
            val t0 = all.minOrNull() ?: 0L
            val tEnd = all.maxOrNull() ?: 0L
            return ReplayReport(
                session, input, t0, tEnd,
                ticks = Replayer.run(input, TimeBasis.SAMPLE, t0, tEnd, params),
                ticksRecv = Replayer.run(input, TimeBasis.RECV, t0, tEnd, params),
                params = params,
            )
        }
    }
}
