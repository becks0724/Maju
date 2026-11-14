// 재생 도구 시험용 합성 세션 — :shared Simulator 파형을 기록 앱과 같은 CSV 형식(BioProbeCsv)으로 폴더에 쓴다(실측값 · 좌표 없음, 모두 합성)
package com.example.guardian.watch.replay

import com.example.guardian.shared.bio.SimTrace
import com.example.guardian.watch.probe.BioProbeCsv
import com.example.guardian.watch.probe.BioStream
import com.example.guardian.watch.probe.SpeedFix
import java.io.File
import kotlin.math.roundToInt

/**
 * - 단조 시각은 [MONO0] 부터, 벽시계는 [WALL0] 부터(대응 차 [OFFSET]). Sensor SDK 샘플 시각은 에포크(벽시계) ms 로 적는다.
 * - 받은 시각 = 샘플 시각 + 지연. 화면 켜짐 [onDelayMs], 꺼짐([screenOffFromMs] 이후)은 [batchMs] 묶음 경계 + [onDelayMs].
 * - 가속도는 감시 경로 환산식의 역(m/s² ÷ 배율)으로 원시 정수를 만든다 — 재생이 같은 식으로 되돌린다.
 */
class SyntheticSession(
    val trace: SimTrace,
    val speedKmhAt: (Long) -> Double? = { 0.0 },
    val screenOffFromMs: Long? = null,
    val batchMs: Long = 2_000,
    val onDelayMs: Long = 30,
    val ppgStatusAt: (Int) -> Int = { 0 },
    /** 참이면 심박 줄마다 상태 −1 인 IBI 를 하나 덧붙인다(거르지 않았을 때의 건수 차이 시험). */
    val extraBadIbi: Boolean = false,
    val batteryStart: Int = 90,
    val batteryEnd: Int = 80,
    val hs: Boolean = false,
) {
    val startMs: Long = MONO0
    val endMs: Long = MONO0 + listOf(trace.ppg.lastOrNull()?.atMs, trace.accel.lastOrNull()?.atMs, trace.hr.lastOrNull()?.atMs).filterNotNull().max() - traceStart()

    private fun traceStart(): Long = listOf(trace.ppg.firstOrNull()?.atMs, trace.accel.firstOrNull()?.atMs, trace.hr.firstOrNull()?.atMs).filterNotNull().min()

    /** 시뮬레이터 시각(startAtMs 기준) → 세션 단조 시각. */
    fun mono(traceAtMs: Long): Long = MONO0 + (traceAtMs - traceStart())

    fun recv(monoMs: Long): Long {
        val off = screenOffFromMs != null && monoMs >= screenOffFromMs
        return if (!off) monoMs + onDelayMs else ((monoMs + batchMs - 1) / batchMs) * batchMs + onDelayMs
    }

    /** 폴더에 모든 파일을 쓴다. [skip] 에 든 스트림은 쓰지 않는다(파일 빠짐 시험). */
    fun write(dir: File, skip: Set<BioStream> = emptySet()) {
        dir.mkdirs()
        fun lines(s: BioStream, body: List<String>) {
            if (s in skip) return
            File(dir, s.file).writeText((listOfNotNull(BioProbeCsv.header(s)) + body).joinToString("\n") + "\n")
        }
        lines(BioStream.SDK_PPG, trace.ppg.mapIndexed { i, p ->
            val m = mono(p.atMs); val st = ppgStatusAt(i)
            BioProbeCsv.sdkPpg(recv(m), m + OFFSET, p.green.roundToInt(), p.ir.roundToInt(), p.red.roundToInt(), st, st, st)
        })
        lines(BioStream.SDK_ACC, trace.accel.map { a ->
            val m = mono(a.atMs)
            BioProbeCsv.sdkAcc(recv(m), m + OFFSET, raw(a.x), raw(a.y), raw(a.z))
        })
        lines(BioStream.SDK_HR, trace.hr.map { h ->
            val m = mono(h.atMs)
            val ibi = h.ibiMs + (if (extraBadIbi) listOf(3_000) else emptyList())
            val st = h.ibiMs.map { 0 } + (if (extraBadIbi) listOf(-1) else emptyList())
            BioProbeCsv.sdkHr(recv(m), m + OFFSET, h.bpm, h.status, ibi, st)
        })
        lines(BioStream.AND_ACC, trace.accel.map { a ->
            val m = mono(a.atMs)
            BioProbeCsv.andSensor(recv(m), m, a.x.toFloat(), a.y.toFloat(), a.z.toFloat(), 3)
        })
        lines(BioStream.AND_GYRO, emptyList())
        lines(BioStream.HS_HR, if (!hs) emptyList() else trace.hr.map { h ->
            val m = mono(h.atMs)
            BioProbeCsv.hsHr(recv(m), m, (h.bpm + 1).toDouble(), "ACCURACY_MEDIUM")
        })
        val seconds = (startMs..endMs step 1000).toList()
        lines(BioStream.SPEED, seconds.map { t ->
            BioProbeCsv.speed(SpeedFix(recvMs = t, fixAgeMs = 0, speedMps = speedKmhAt(t)?.div(3.6)?.toFloat(), speedAccMps = 0.5f, horizAccM = 4f))
        })
        lines(BioStream.STATE, seconds.mapIndexed { i, t ->
            val bat = batteryStart + ((batteryEnd - batteryStart) * i.toDouble() / (seconds.size - 1).coerceAtLeast(1)).roundToInt()
            val on = screenOffFromMs == null || t < screenOffFromMs
            BioProbeCsv.state(t, t + OFFSET, on, bat, BioStream.COUNTED.map { 0L }, monitoring = false, ioErrors = 0L)
        })
        lines(BioStream.EVENTS, listOf(BioProbeCsv.event(startMs, "start", "session=synthetic hs=$hs"), BioProbeCsv.event(endMs, "stop", "button")))
        lines(BioStream.META, listOf("session=synthetic", "device=synthetic sdk_int=0 release=0", "mono_ms=$MONO0 wall_ms=$WALL0"))
    }

    companion object {
        const val MONO0 = 100_000L
        const val WALL0 = 1_762_500_000_000L
        const val OFFSET = WALL0 - MONO0

        /** m/s² → Sensor SDK 원시 정수(감시 경로 환산식의 역). */
        fun raw(ms2: Double): Int = (ms2 / ReplayMapper.ACC_SCALE).roundToInt()
    }
}
