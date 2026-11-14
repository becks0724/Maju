// 생체 기록 세션 폴더(Mac 재생용) 읽기 — 파일 없음 · 빈칸 · 빠진 열 · 잘린 마지막 줄을 견디는 CSV 표와 스트림별 행 모델, 단조 ↔ 벽시계 대응
package com.example.guardian.watch.replay

import com.example.guardian.watch.probe.BioProbeCsv
import com.example.guardian.watch.probe.BioStream
import java.io.File

/** 머리글 이름으로 칸을 찾는 표. 줄마다 칸 수가 달라도(잘린 마지막 줄) 있는 칸만 돌려주고, 빈칸은 null 이다. */
class CsvTable(val header: List<String>, val rows: List<List<String>>) {
    private val index = header.withIndex().associate { (i, h) -> h.trim() to i }

    fun cell(row: List<String>, col: String): String? = index[col]?.let { row.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
    fun long(row: List<String>, col: String): Long? = cell(row, col)?.toLongOrNull()
    fun int(row: List<String>, col: String): Int? = cell(row, col)?.toIntOrNull()
    fun double(row: List<String>, col: String): Double? = cell(row, col)?.toDoubleOrNull()?.takeIf { it.isFinite() }

    companion object {
        /** 파일이 없거나 비었으면 null. 첫 줄이 머리글이다. */
        fun read(f: File): CsvTable? {
            if (!f.isFile) return null
            val lines = f.readLines().filter { it.isNotBlank() }
            val header = lines.firstOrNull() ?: return null
            return CsvTable(split(header), lines.drop(1).map(::split))
        }

        /** RFC 4180 식 — 따옴표 안의 쉼표를 건너뛰고 두 번 쓴 따옴표를 하나로(events.csv 의 detail 칸). */
        fun split(line: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out += cur.toString(); cur.setLength(0) }
                    else -> cur.append(c)
                }
                i++
            }
            out += cur.toString()
            return out
        }
    }
}

// ── 스트림별 행(원시값 그대로, 빈칸은 null) ──

data class PpgRow(val recvMs: Long, val sampleEpochMs: Long, val green: Int?, val ir: Int?, val red: Int?, val greenStatus: Int?, val irStatus: Int?, val redStatus: Int?)
data class AccRow(val recvMs: Long, val sampleEpochMs: Long, val rawX: Int?, val rawY: Int?, val rawZ: Int?)
data class HrRow(val recvMs: Long, val sampleEpochMs: Long, val hr: Int?, val status: Int?, val ibi: List<Int>, val ibiStatus: List<Int>)
/** Android 가속도 · 자이로 한 건. `sample_boot_ms` 는 단조 시계와 같은 축이다. */
data class AndRow(val recvMs: Long, val sampleBootMs: Long, val x: Double?, val y: Double?, val z: Double?, val accuracy: Int?)
data class HsRow(val recvMs: Long, val sampleBootMs: Long, val bpm: Double?, val sensorStatus: String?)
data class SpeedRow(val recvMs: Long, val fixAgeMs: Long?, val speedMps: Double?, val speedAccMps: Double?, val horizAccM: Double?)
data class StateRow(
    val recvMs: Long, val wallMs: Long?, val screenOn: Boolean?, val batteryPct: Int?,
    val monitoring: Boolean?, val ioErrors: Long?,
)
data class EventRow(val recvMs: Long, val kind: String, val detail: String)

/** 벽시계 − 단조 시계(ms). 샘플 에포크 시각을 단조 축으로 옮기고, 요약의 시각 표시에 쓴다. */
data class ClockMap(val wallMinusMono: Long?, val source: String) {
    fun toMono(epochMs: Long): Long? = wallMinusMono?.let { epochMs - it }
    fun toWall(monoMs: Long): Long? = wallMinusMono?.let { monoMs + it }
}

/** 세션 폴더 하나를 읽은 결과. 없는 파일은 빈 목록이고 [present] 에 들어가지 않는다. [skipped] 는 시각 칸이 없어 버린 줄 수. */
class BioSession(
    val dir: File,
    val ppg: List<PpgRow>,
    val acc: List<AccRow>,
    val hr: List<HrRow>,
    val andAcc: List<AndRow>,
    val andGyro: List<AndRow>,
    val hsHr: List<HsRow>,
    val speed: List<SpeedRow>,
    val state: List<StateRow>,
    val events: List<EventRow>,
    val meta: List<String>,
    val present: Set<BioStream>,
    val skipped: Map<BioStream, Int>,
) {
    /**
     * 벽시계 − 단조 시계. 1순위 state.csv(1초마다 두 시계를 함께 적음 — 중앙값), 2순위 meta.txt 의 `mono_ms= wall_ms=`,
     * 3순위 Sensor SDK 샘플의 (에포크 − 받은 시각) 최대값(도착 지연이 0 에 가까운 샘플이 기준이 된다 — 근사).
     */
    val clock: ClockMap by lazy {
        val fromState = state.mapNotNull { s -> s.wallMs?.let { it - s.recvMs } }
        if (fromState.isNotEmpty()) return@lazy ClockMap(median(fromState), "state.csv")
        val m = META_CLOCK.find(meta.joinToString("\n"))
        if (m != null) return@lazy ClockMap(m.groupValues[2].toLong() - m.groupValues[1].toLong(), "meta.txt")
        val fromSdk = (ppg.map { it.sampleEpochMs - it.recvMs } + acc.map { it.sampleEpochMs - it.recvMs } + hr.map { it.sampleEpochMs - it.recvMs })
        if (fromSdk.isNotEmpty()) return@lazy ClockMap(fromSdk.max(), "SDK 샘플 근사(에포크 − 받은 시각 최대)")
        ClockMap(null, "없음")
    }

    /** state.csv 안 (벽시계 − 단조) 의 최소 · 최대 — 폭이 크면 벽시계가 움직인 것이라 에포크 샘플 시각을 한 값으로 옮긴 재생은 감시와 어긋난다. */
    val clockSpread: Pair<Long, Long>? by lazy {
        val v = state.mapNotNull { s -> s.wallMs?.let { it - s.recvMs } }
        if (v.isEmpty()) null else v.min() to v.max()
    }

    /** 합성 세션(시험 소스 `SyntheticSession` 이 meta 에 `session=synthetic` 을 남긴다) — 실측이 아니다. */
    val synthetic: Boolean get() = meta.any { it.trim() == "session=synthetic" }

    /** 스트림 안에서 샘플 자체 시각이 앞 줄보다 뒤로 간 건수(파일 순서 기준). */
    fun timeRegressions(): Map<BioStream, Int> = linkedMapOf(
        BioStream.SDK_PPG to regress(ppg.map { it.sampleEpochMs }),
        BioStream.SDK_ACC to regress(acc.map { it.sampleEpochMs }),
        BioStream.SDK_HR to regress(hr.map { it.sampleEpochMs }),
        BioStream.AND_ACC to regress(andAcc.map { it.sampleBootMs }),
        BioStream.AND_GYRO to regress(andGyro.map { it.sampleBootMs }),
        BioStream.HS_HR to regress(hsHr.map { it.sampleBootMs }),
    ).filterValues { it > 0 }

    private fun regress(t: List<Long>): Int = t.zipWithNext().count { (a, b) -> b < a }

    companion object {
        private val META_CLOCK = Regex("""mono_ms=(-?\d+)\s+wall_ms=(-?\d+)""")

        fun load(dir: File): BioSession {
            val present = LinkedHashSet<BioStream>()
            val skipped = LinkedHashMap<BioStream, Int>()
            fun <T> table(s: BioStream, parse: (CsvTable, List<String>) -> T?): List<T> {
                val t = CsvTable.read(File(dir, s.file)) ?: return emptyList()
                present += s
                var bad = 0
                val out = ArrayList<T>(t.rows.size)
                for (r in t.rows) {
                    val v = parse(t, r)
                    if (v == null) bad++ else out += v
                }
                if (bad > 0) skipped[s] = bad
                return out
            }
            val metaFile = File(dir, BioStream.META.file)
            val meta = if (metaFile.isFile) metaFile.readLines().also { present += BioStream.META } else emptyList()
            return BioSession(
                dir = dir,
                ppg = table(BioStream.SDK_PPG) { t, r ->
                    PpgRow(
                        t.long(r, "recv_ms") ?: return@table null, t.long(r, "sample_epoch_ms") ?: return@table null,
                        t.int(r, "green"), t.int(r, "ir"), t.int(r, "red"),
                        t.int(r, "green_status"), t.int(r, "ir_status"), t.int(r, "red_status"),
                    )
                },
                acc = table(BioStream.SDK_ACC) { t, r ->
                    AccRow(
                        t.long(r, "recv_ms") ?: return@table null, t.long(r, "sample_epoch_ms") ?: return@table null,
                        t.int(r, "raw_x"), t.int(r, "raw_y"), t.int(r, "raw_z"),
                    )
                },
                hr = table(BioStream.SDK_HR) { t, r ->
                    HrRow(
                        t.long(r, "recv_ms") ?: return@table null, t.long(r, "sample_epoch_ms") ?: return@table null,
                        t.int(r, "hr"), t.int(r, "hr_status"), intList(t.cell(r, "ibi_list")), intList(t.cell(r, "ibi_status_list")),
                    )
                },
                andAcc = table(BioStream.AND_ACC, ::andRow),
                andGyro = table(BioStream.AND_GYRO, ::andRow),
                hsHr = table(BioStream.HS_HR) { t, r ->
                    HsRow(t.long(r, "recv_ms") ?: return@table null, t.long(r, "sample_boot_ms") ?: return@table null, t.double(r, "bpm"), t.cell(r, "sensor_status"))
                },
                speed = table(BioStream.SPEED) { t, r ->
                    SpeedRow(t.long(r, "recv_ms") ?: return@table null, t.long(r, "fix_age_ms"), t.double(r, "speed_mps"), t.double(r, "speed_acc_mps"), t.double(r, "horiz_acc_m"))
                },
                state = table(BioStream.STATE) { t, r ->
                    StateRow(
                        t.long(r, "recv_ms") ?: return@table null, t.long(r, "wall_ms"), t.int(r, "screen_on")?.let { it != 0 }, t.int(r, "battery_pct"),
                        t.int(r, "monitoring")?.let { it != 0 }, t.long(r, "io_errors"),
                    )
                },
                events = table(BioStream.EVENTS) { t, r ->
                    EventRow(t.long(r, "recv_ms") ?: return@table null, t.cell(r, "kind") ?: "", t.cell(r, "detail") ?: "")
                },
                meta = meta,
                present = present,
                skipped = skipped,
            )
        }

        private fun andRow(t: CsvTable, r: List<String>): AndRow? {
            val recv = t.long(r, "recv_ms") ?: return null
            val boot = t.long(r, "sample_boot_ms") ?: return null
            return AndRow(recv, boot, t.double(r, "x"), t.double(r, "y"), t.double(r, "z"), t.int(r, "accuracy"))
        }

        /** `830|845|0` → [830, 845, 0]. 숫자가 아닌 조각은 버린다. */
        private fun intList(cell: String?): List<Int> =
            cell?.split(BioProbeCsv.LIST_SEP)?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

        internal fun median(v: List<Long>): Long {
            val s = v.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}
