// 재생 결과를 글로 — 세션 폴더 안 replay/verdict.csv(1초 판정)와 replay/summary.md(한글 요약, 맨 위에 「이 자료로 말할 수 없는 것」 · 경고, 머리 표는 도착 시각 기준)
package com.example.guardian.watch.replay

import com.example.guardian.shared.bio.GateReason
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.watch.probe.BioStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object SummaryWriter {
    const val OUT_DIR = "replay"
    const val VERDICT_CSV = "verdict.csv"
    const val SUMMARY_MD = "summary.md"
    const val VERDICT_HEADER = "t_ms,elapsed_s,wall_time,speed_band,speed_kmh,verdict,reasons,hr,has_data,verdict_recv,reasons_recv,hr_recv,has_data_recv,wrist_std,screen_on"

    private val wallFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** `replay/` 를 만들고 두 파일을 쓴다. 돌려주는 값은 쓴 폴더. */
    fun write(r: ReplayReport, zone: ZoneId = ZoneId.systemDefault()): File {
        val out = File(r.session.dir, OUT_DIR).also { it.mkdirs() }
        File(out, VERDICT_CSV).writeText(verdictCsv(r, zone))
        File(out, SUMMARY_MD).writeText(summary(r, zone))
        return out
    }

    fun verdictCsv(r: ReplayReport, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine(VERDICT_HEADER)
        r.ticks.forEachIndexed { i, t ->
            val rv = r.ticksRecv[i]
            append(t.tMs).append(',').append((t.tMs - r.t0) / 1000).append(',').append(wall(r, t.tMs, zone) ?: "").append(',')
            append(r.bands[i].name).append(',').append(r.speedKmh[i]?.let { fmt(it, 1) } ?: "").append(',')
            append(t.verdict.name).append(',').append(reasons(t.reasons)).append(',').append(t.hr ?: "").append(',').append(flag(t.hasData)).append(',')
            append(rv.verdict.name).append(',').append(reasons(rv.reasons)).append(',').append(rv.hr ?: "").append(',').append(flag(rv.hasData)).append(',')
            append(r.wristStd[i]?.let { fmt(it, 3) } ?: "").append(',').append(r.screenOn[i]?.let { flag(it) } ?: "")
            appendLine()
        }
    }

    fun summary(r: ReplayReport, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        val s = r.session
        appendLine("# 생체 기록 재생 요약 — ${s.dir.name}")
        appendLine()
        appendLine("## 이 자료로 말할 수 없는 것")
        if (s.synthetic) appendLine("- **합성 세션이다**(시험 소스 시뮬레이터 파형, meta `session=synthetic`). 실측이 아니며 수치는 도구 점검용이다.")
        else appendLine("- 정상 운전 중 기록이다. 맥 소실을 놓치는지(민감도)는 이 자료로 알 수 없다 — 거짓 경보 쪽과 신호 품질만 본다.")
        appendLine("- 사내 분석 문서 — 제출 문서에 판정어를 그대로 옮기지 않는다(G1).")
        appendLine("- 판정 문턱(`GateParams` 기본값 — A1 10% × 3창 · A2 0.35 m/s² · A3 4초 · B SNR 1.5 · 자기상관 0.7)은 모두 [추정]이다. 여기 나온 판정은 그 문턱에서의 결과다.")
        appendLine("- 값 변환 전제 둘은 [미검증]이다 — 가속도 원시값 × 9.81 / (16383.75 / 4) = m/s², IBI 상태 0 만 유효. 감시 경로(`SamsungSensorSource`)와 같은 식 · 같은 연산 순서를 썼다.")
        appendLine("- 머리 표는 **도착 시각 기준**(워치가 받은 시각에 판정 — 화면 꺼짐 묶음 지연까지 겪은, 제품에 가까운 값)이다. 샘플 시각 기준은 비교로 둔다. 기록 앱의 2초 flush 와 감시 앱의 2초 flush 는 같은 순간이 아니므로 지연은 근사다.")
        appendLine("- 관문이 자료 없이 낸 NORMAL 은 「정상」이 아니라 「자료 없음」으로 센다(직전 ${Replayer.DATA_FRESH_MS / 1000}초 안에 PPG · 심박 샘플이 없는 틱).")
        val warns = r.warnings()
        if (warns.isNotEmpty()) {
            appendLine()
            appendLine("## 경고")
            warns.forEach { appendLine("- **$it**") }
        }
        appendLine()

        appendLine("## 세션")
        appendLine("- 폴더 `${s.dir.path}`")
        appendLine("- 길이 ${hms(r.durationS)} (${fmt(r.hours, 2)} 시간), 1초 판정 ${r.ticks.size}틱, 자료 있는 틱 ${r.ticks.size - r.noDataTicks(TimeBasis.RECV)} (도착 기준) · ${r.ticks.size - r.noDataTicks(TimeBasis.SAMPLE)} (샘플 기준)")
        appendLine("- 있는 파일 ${s.present.joinToString(" · ") { it.file }.ifEmpty { "없음" }}")
        val missing = BioStream.entries.filter { it !in s.present }
        if (missing.isNotEmpty()) appendLine("- 없는 파일 ${missing.joinToString(" · ") { it.file }}")
        if (s.skipped.isNotEmpty()) appendLine("- 시각 칸이 없어 버린 줄(잘린 마지막 줄 등) ${s.skipped.entries.joinToString(" · ") { "${it.key.file} ${it.value}" }}")
        appendLine("- 벽시계 − 단조 시계 대응 출처 ${s.clock.source}" + (s.clock.wallMinusMono?.let { " (${it} ms)" } ?: "") +
            (s.clockSpread?.let { " · state.csv 안 최소 ${it.first} ~ 최대 ${it.second} (폭 ${it.second - it.first} ms)" } ?: ""))
        wall(r, r.t0, zone)?.let { appendLine("- 시작 $it ~ 끝 ${wall(r, r.tEnd, zone)} (${zone})") }
        if (r.input.isEmpty) appendLine("- **Sensor SDK 스트림이 없거나 시각 대응이 없어 관문 재생을 하지 못했다.** 모든 틱이 「자료 없음」이다.")
        s.meta.firstOrNull { it.startsWith("device=") }?.let { appendLine("- $it") }
        s.meta.firstOrNull { it.startsWith("app_version=") }?.let { appendLine("- $it") }
        appendLine()

        appendLine("## 판정별 시간 — 도착 시각 기준")
        appendLine("칸 = 틱 수 (자료 있는 틱 기준 % · 전체 틱 기준 %).")
        appendLine()
        verdictTable(r, TimeBasis.RECV)
        appendLine()
        appendLine("샘플 시각 기준(비교) — " + catLine(r.verdictCounts(TimeBasis.SAMPLE)) + ", 두 기준의 판정이 다른 틱 ${r.differingSeconds()}")
        appendLine()

        appendLine("## 경보 재생 — 제품 상태 기계(`FlowMachine`, 기본 `FlowParams`)에 도착 기준 판정열을 넣은 결과")
        appendLine("- 고정한 입력 — 동의 셋 모두 참 · 전원 켬 · 테스트 아님 · 차 상태 없음(휴대폰 주인, 속도 출처 GPS → 정지 = ${com.example.guardian.shared.flow.VehicleState.GPS_STOP_KMH} km/h 이하 ${r.flow.stopDebounceMs / 1000}초) · 에어백 없음 · 워치 값 경과 0(중계 지연 없음). 속도는 speed.csv(없으면 null = 주행으로 봄).")
        appendLine("- 응답 모델 — 경보(진동) 시작 틱 + 지연이 지난 첫 틱에 워치 버튼. 타이머 — 경보 1 ${r.flow.alert1Ms / 1000}초 · 경보 2 ${r.flow.alert2Ms / 1000}초 뒤 무응답(S3 · 정지면 S4), 응답 뒤 나쁜 판정 ${r.flow.realertStepsMs.joinToString("→") { "${it / 1000}" }}초 지속 재경보, 맥박 없음으로 나빠지면 즉시.")
        appendLine()
        appendLine("| 응답 모델 | 경보 시작(사건) | 시간당 | 소리 단계 | S3(무응답 · 주행) 진입 | S4(무응답 · 정지) 진입 | 보호자 사건 문자 | 취소 문자 | 119 작성 열림 | 응답 수 |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for (f in r.flowResults(TimeBasis.RECV)) {
            appendLine("| ${f.model.label} | ${f.alertStarts} | ${r.perHour(f.alertStarts)?.let { fmt(it, 2) } ?: "-"} | ${f.stage2Starts} | ${f.s3Entries} | ${f.s4Entries} | ${f.incidentSms} | ${f.cancelSms} | ${f.compose119} | ${f.responses} |")
        }
        val fs = r.flowResults(TimeBasis.SAMPLE).first()
        appendLine()
        appendLine("샘플 시각 기준(비교, 무응답) — 경보 시작 ${fs.alertStarts}, S3 ${fs.s3Entries}, S4 ${fs.s4Entries}, 보호자 사건 문자 ${fs.incidentSms}.")
        appendLine()

        appendLine("## 나쁜 판정 구간(맥박 약함 · 없음)")
        for ((basis, title) in listOf(TimeBasis.RECV to "도착 시각 기준(머리)", TimeBasis.SAMPLE to "샘플 시각 기준(비교)")) {
            val eps = r.episodes(basis)
            val realert = r.realertCandidates(basis)
            appendLine("### $title")
            appendLine("- 구간 수 ${eps.size}" + (r.perHour(eps.size)?.let { " (시간당 ${fmt(it, 2)})" } ?: "") +
                ", ${r.flow.realertStepsMs.first() / 1000}초 이상 이어진 구간 ${realert.size}, 맥박 없음까지 간 구간 ${eps.count { it.worst == WristVerdict.PULSE_ABSENT }}")
            if (eps.isNotEmpty()) {
                appendLine()
                if (basis == TimeBasis.SAMPLE) appendLine("| # | 시작(경과) | 시작(벽시계) | 길이(초) | 최악 판정 | 도착 기준에서 최악 판정까지 늦은 정도 |")
                else appendLine("| # | 시작(경과) | 시작(벽시계) | 길이(초) | 최악 판정 |")
                appendLine(if (basis == TimeBasis.SAMPLE) "|---|---|---|---|---|---|" else "|---|---|---|---|---|")
                val delays = if (basis == TimeBasis.SAMPLE) r.basisDelays().associate { it.first to it.second } else emptyMap()
                eps.take(200).forEachIndexed { i, e ->
                    append("| ${i + 1} | ${hms((e.startMs - r.t0) / 1000)} | ${wall(r, e.startMs, zone) ?: "-"} | ${e.seconds} | ${label(e.worst)} |")
                    if (basis == TimeBasis.SAMPLE) append(" ${delays[e]?.let { "${it / 1000}초" } ?: "도착 기준은 그 판정에 닿지 않음"} |")
                    appendLine()
                }
                if (eps.size > 200) appendLine("| … | ${eps.size - 200}건 더 | | | |" + if (basis == TimeBasis.SAMPLE) " |" else "")
            }
            appendLine()
        }

        appendLine("## 규칙별 성립 비율(도착 기준, 전체 틱 분모)")
        appendLine("| 규칙 | 전체 | 움직일 때 | 멈췄을 때 | 속도 없음 |")
        appendLine("|---|---|---|---|---|")
        val rules = listOf(
            GateReason.A1_AMPLITUDE_DROP to "A1 진폭 급감", GateReason.A2_WRIST_STILL to "A2 손목 정지",
            GateReason.A3_BEAT_GAP to "A3 박동 공백", GateReason.B_NO_PULSE_BOTH to "B 두 파장 맥 없음",
            GateReason.WARMING_UP to "기준선 잡는 중", GateReason.BASELINE_RESET to "DC 급변 재설정",
            GateReason.MOTION_HOLD to "움직임 보류(−2)", GateReason.LOOSE to "느슨함", GateReason.OFF_WRIST to "미착용",
        )
        for ((reason, name) in rules) {
            append("| $name | ${rate(r.reasonRate(reason, basis = TimeBasis.RECV))} ")
            for (b in SpeedBand.entries) append("| ${rate(r.reasonRate(reason, b, TimeBasis.RECV))} ")
            appendLine("|")
        }
        appendLine()
        appendLine("손목 흔들림(관문과 같은 10초 창 가속도 크기 표준편차, m/s², 샘플 시각 축) — 문턱 θ_drive ${r.params.thetaDrive}")
        appendLine()
        appendLine("| 구간 | 창 찬 틱 | 중앙값 | 90% | 최대 | 문턱 아래 비율 |")
        appendLine("|---|---|---|---|---|---|")
        for ((name, b) in listOf("전체" to null, "움직일 때" to SpeedBand.MOVING, "멈췄을 때" to SpeedBand.STOPPED, "속도 없음" to SpeedBand.UNKNOWN)) {
            val p = r.wristStdPercentiles(b)
            appendLine("| $name | ${p?.n ?: 0} | ${p?.let { fmt(it.median, 3) } ?: "-"} | ${p?.let { fmt(it.p90, 3) } ?: "-"} | ${p?.let { fmt(it.max, 3) } ?: "-"} | ${rate(r.wristBelowTheta(b))} |")
        }
        appendLine()

        appendLine("## PPG 품질")
        val pv = r.ppgValidSeconds()
        appendLine("- **유효한 초 ${cell(pv.good, pv.allTicks)} — 세션 전체 틱 기준**(틱 창에 샘플 20개 이상이고 80% 이상 녹색 상태 0). PPG 가 한 점이라도 있는 틱 기준으로는 ${cell(pv.good, pv.ticksWithPpg)} (보조).")
        appendLine("- 선행 연구(R3 §운전 중 PPG, 동승석 Empatica E4 64Hz, 품질 검사 뒤 유효 구간 67~70%)와는 기기 · 조건 · 품질 판정 방식 · 단위가 달라 정의가 다르다 — 참고만.")
        val (vs, vn) = r.ppgValidShare()
        appendLine("- 녹색 상태 0 인 샘플 ${cell(vs, vn)} (상태 칸이 있는 샘플 기준, [미검증] 0 = 정상 전제)")
        appendLine("- 상태값 분포 — 녹색 ${distLine(r.ppgStatusDist { it.greenStatus })} · 적외선 ${distLine(r.ppgStatusDist { it.irStatus })} · 적색 ${distLine(r.ppgStatusDist { it.redStatus })}")
        appendLine("- 심박 상태값 분포(1 정상 · −2 움직임 · −3 미착용 · −8 · −10 신호 약함) ${distLine(r.hrStatusDist())}")
        appendLine("- IBI 상태값 분포 ${distLine(r.ibiStatusDist())}")
        val ic = r.ibiCounts()
        appendLine("- IBI 건수 — 전부 ${ic.all}, 상태 0 ${ic.status0}, 관문 범위(250–2000 ms) 안 전부 ${ic.allInRange}, 상태 0 이면서 범위 안 ${ic.status0InRange}. " +
            "유효 IBI 가 하나라도 있는 심박 줄 — 상태 0 만 유효로 볼 때 ${ic.hrRowsWithValid}, 거르지 않을 때 ${ic.hrRowsWithAnyInRange} (A3 박동 공백은 이 줄이 없는 4초를 본다).")
        appendLine()

        appendLine("## 스트림별 건수 · 주기 · 공백")
        appendLine("| 파일 | 건수 | 주기 중앙값(ms) | 가장 긴 공백(ms) | 공백 시작(경과) | 버린 줄 | 시각 역행 |")
        appendLine("|---|---|---|---|---|---|---|")
        val reg = s.timeRegressions()
        for (st in r.streamStats()) {
            val present = st.stream in s.present
            appendLine("| ${st.stream.file} | ${if (present) st.n else "없음"} | ${st.medianIntervalMs ?: "-"} | ${st.maxGapMs ?: "-"} | ${st.maxGapAtMs?.let { hms((it - r.t0) / 1000) } ?: "-"} | ${st.skipped} | ${reg[st.stream] ?: 0} |")
        }
        appendLine()
        appendLine("Sensor SDK 세 스트림 도착 지연(받은 시각 − 샘플 시각, ms, 0 이상만 · 음수는 시계 어긋남으로 따로)")
        appendLine()
        appendLine("| 화면 | 샘플 수 | 중앙값 | 90% | 최대 | 음수(어긋남) |")
        appendLine("|---|---|---|---|---|---|")
        for ((name, on) in listOf("켜짐" to true, "꺼짐" to false, "상태 모름" to null)) {
            val a = r.arrivalDelay(on)
            val p = a.p
            appendLine("| $name | ${p?.n ?: 0} | ${p?.let { fmt(it.median, 0) } ?: "-"} | ${p?.let { fmt(it.p90, 0) } ?: "-"} | ${p?.let { fmt(it.max, 0) } ?: "-"} | ${a.negatives} |")
        }
        appendLine()

        appendLine("## 가속도 환산 점검")
        appendLine("- Sensor SDK 가속도 크기 중앙값(환산 뒤, m/s²) — 전체 ${num(r.sdkAccMagnitudeMedian(), 3)}, 멈췄을 때 ${num(r.sdkAccMagnitudeMedian(SpeedBand.STOPPED), 3)} (정지 때 약 9.81 이어야 환산식이 맞다)")
        appendLine("- Android 가속도 크기 중앙값(m/s²) — 전체 ${num(r.androidAccMagnitudeMedian(), 3)}, 멈췄을 때 ${num(r.androidAccMagnitudeMedian(SpeedBand.STOPPED), 3)}")
        val a = r.sdkAccMagnitudeMedian(); val b = r.androidAccMagnitudeMedian()
        if (a != null && b != null && b > 0) appendLine("- 비 SDK ÷ Android = ${fmt(a / b, 3)} (1 에서 멀면 환산식 또는 축 배율을 다시 본다)")
        appendLine()

        appendLine("## Health Services")
        if (!r.hsEnabled) appendLine("- 이 세션은 Health Services 심박을 함께 켜지 않았다(hs_hr.csv 없음 또는 비어 있음).")
        else {
            val span = r.hsSpan()!!
            appendLine("- 켜져 있던 구간 ${hms((span.startMs - r.t0) / 1000)} ~ ${hms((span.endMs - r.t0) / 1000)}, 건수 ${s.hsHr.size}")
            appendLine("- `sensor_status` 분포 ${distLine(r.hsStatusDist())}")
            val d = r.hsVsSdkDiff()
            appendLine("- 두 심박 차이 |HS − SDK|(상태 1 · 샘플 시각 차 ${ReplayReport.HS_PAIR_MS} ms 미만 짝) — ${d?.let { "짝 ${it.n}, 중앙값 ${fmt(it.median, 1)}, 90% ${fmt(it.p90, 1)}, 최대 ${fmt(it.max, 1)} bpm" } ?: "짝 없음"}")
            r.sdkHrStatusInsideHs()?.let { (inside, outside) ->
                appendLine("- 간섭 후보 — Sensor SDK 심박 상태 분포 HS 구간 안 ${distLine(inside)} / 밖 ${distLine(outside)}")
            }
        }
        appendLine()

        appendLine("## 배터리 · 사건 · 상태")
        val bat = r.batteryFirstLast()
        appendLine("- 배터리 ${bat?.let { "${it.first}% → ${it.second}%" } ?: "기록 없음"}" + (r.batteryDropPerHour()?.let { ", 시간당 ${fmt(it, 1)}%p 감소" } ?: ""))
        val kinds = r.eventsByKind()
        appendLine("- 사건 ${s.events.size}건" + (if (kinds.isEmpty()) "" else " — " + kinds.entries.joinToString(" · ") { "${it.key} ${it.value}" }))
        s.events.take(40).forEach { e -> appendLine("  - ${hms((e.recvMs - r.t0) / 1000)} ${e.kind} ${e.detail}") }
        if (s.events.size > 40) appendLine("  - … ${s.events.size - 40}건 더(events.csv)")
        appendLine("- 감시(`monitoring`)가 같이 돌던 구간 ${spans(r.monitoringSpans(), r)}")
        appendLine("- 쓰기 실패(`io_errors` > 0) 구간 ${spans(r.ioErrorSpans(), r)}")
        appendLine()
        appendLine("생성 — `verdict.csv` 열 `$VERDICT_HEADER`. 판정 코드 `watch/.../core/GateRunner.kt` → `shared/.../bio/PulseGate.kt`(주행 기준 고정 `GATE_DRIVING = true`), 경보 재생 `shared/.../flow/FlowMachine.kt`.")
    }

    private fun StringBuilder.verdictTable(r: ReplayReport, basis: TimeBasis) {
        appendLine("| 분류 | 전체 | 움직일 때(> ${ReplayReport.MOVING_KMH} km/h) | 멈췄을 때 | 속도 없음 |")
        appendLine("|---|---|---|---|---|")
        val total = r.verdictCounts(basis)
        val byBand = SpeedBand.entries.associateWith { r.verdictCounts(basis, it) }
        for (c in ReplayReport.Cat.entries) {
            append("| ${c.label} | ${catCell(total, c)} ")
            for (b in SpeedBand.entries) append("| ${catCell(byBand.getValue(b), c)} ")
            appendLine("|")
        }
        append("| 틱 수(자료 있음 / 전체) | ${total.dataTicks} / ${total.allTicks} ")
        for (b in SpeedBand.entries) { val x = byBand.getValue(b); append("| ${x.dataTicks} / ${x.allTicks} ") }
        appendLine("|")
    }

    // ── 보조 ──

    private fun label(v: WristVerdict) = when (v) {
        WristVerdict.NORMAL -> "정상"; WristVerdict.CHECK_WEAR -> "착용 확인"; WristVerdict.PULSE_WEAK -> "맥박 약함"; WristVerdict.PULSE_ABSENT -> "맥박 없음"
    }

    private fun catCell(x: ReplayReport.CatCounts, c: ReplayReport.Cat): String {
        val n = x.count(c)
        if (x.allTicks == 0) return "0"
        val ofData = if (c == ReplayReport.Cat.NO_DATA || x.dataTicks == 0) "-" else fmt(n * 100.0 / x.dataTicks, 1) + "%"
        return "$n ($ofData · ${fmt(n * 100.0 / x.allTicks, 1)}%)"
    }

    private fun catLine(x: ReplayReport.CatCounts) = ReplayReport.Cat.entries.joinToString(" · ") { "${it.label} ${x.count(it)}" }
    private fun reasons(r: Set<GateReason>) = r.sortedBy { it.ordinal }.joinToString("|") { it.name }
    private fun flag(b: Boolean) = if (b) "1" else "0"
    private fun cell(n: Int, total: Int) = if (total == 0) "0" else "$n/$total (${fmt(n * 100.0 / total, 1)}%)"
    private fun rate(p: Pair<Int, Int>) = if (p.second == 0) "-" else "${p.first}/${p.second} (${fmt(p.first * 100.0 / p.second, 1)}%)"
    private fun num(v: Double?, d: Int) = v?.let { fmt(it, d) } ?: "-"
    private fun <K> distLine(m: Map<K, Int>) = if (m.isEmpty()) "없음" else m.entries.joinToString(" · ") { "${it.key ?: "빈칸"}=${it.value}" }
    private fun spans(v: List<Span>, r: ReplayReport) =
        if (v.isEmpty()) "없음" else v.joinToString(" · ") { "${hms((it.startMs - r.t0) / 1000)}~${hms((it.endMs - r.t0) / 1000)}" }

    fun fmt(v: Double, d: Int): String = String.format(Locale.ROOT, "%.${d}f", v)
    fun hms(sec: Long): String = String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
    private fun wall(r: ReplayReport, monoMs: Long, zone: ZoneId): String? =
        r.session.clock.toWall(monoMs)?.let { Instant.ofEpochMilli(it).atZone(zone).format(wallFmt) }
}
