// 생체 기록 세션을 제품 관문(GateRunner → :shared PulseGate)에 재생 — 값 변환은 감시 경로(SamsungSensorSource)와 같게, 1초마다 판정, 샘플 시각 · 도착 시각 두 기준
package com.example.guardian.watch.replay

import com.example.guardian.shared.bio.AccelSample
import com.example.guardian.shared.bio.GateParams
import com.example.guardian.shared.bio.GateReason
import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.PpgSample
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.watch.core.GateRunner

/** 샘플 하나와 두 시각 — [atMs] 는 샘플 자체 시각(단조 축으로 옮긴 것), [recvMs] 는 워치가 받은 단조 시각. */
data class Timed<T>(val sample: T, val atMs: Long, val recvMs: Long)

/** 관문에 넣을 수 있게 변환한 세션 — 세 스트림 모두 [atMs] 오름차순. */
class ReplayInput(val ppg: List<Timed<PpgSample>>, val accel: List<Timed<AccelSample>>, val hr: List<Timed<HrSample>>) {
    val isEmpty get() = ppg.isEmpty() && accel.isEmpty() && hr.isEmpty()
}

/**
 * 원시 행 → 관문 샘플. 식 · 규칙은 `watch/src/sensorSdk/.../SamsungSensorSource.kt` 와 같다(둘 다 [미검증]).
 * - 가속도 원시 정수 × 9.81 / (16383.75 / 4) = m/s².
 * - PPG 상태는 녹색 상태값 하나를 싣는다(감시 경로가 그렇다). 세 값 중 하나라도 빈칸이면 그 점은 넣지 않는다.
 * - IBI 는 상태 0 인 것만 유효로 넘긴다([filterIbi] = false 면 전부 — 전제가 틀렸을 때의 차이를 보기 위해).
 */
object ReplayMapper {
    /** 환산 배율(역변환 · 설명용). 실제 변환은 [toMs2] 가 제품과 같은 연산 순서로 한다. */
    const val ACC_SCALE = 9.81 / (16383.75 / 4.0)

    /** `SamsungSensorSource.toMs2` 와 같은 식 · 같은 연산 순서. */
    fun toMs2(raw: Int): Double = raw * 9.81 / (16383.75 / 4.0)

    fun ppg(r: PpgRow, atMs: Long): PpgSample? {
        val g = r.green ?: return null
        val i = r.ir ?: return null
        val d = r.red ?: return null
        return PpgSample(atMs, g.toDouble(), i.toDouble(), d.toDouble(), status = r.greenStatus ?: 0)
    }

    fun accel(r: AccRow, atMs: Long): AccelSample? {
        val x = r.rawX ?: return null
        val y = r.rawY ?: return null
        val z = r.rawZ ?: return null
        return AccelSample(atMs, toMs2(x), toMs2(y), toMs2(z))
    }

    fun hr(r: HrRow, atMs: Long, filterIbi: Boolean = true): HrSample? {
        val bpm = r.hr ?: return null
        val status = r.status ?: return null
        val ibi = if (filterIbi) r.ibi.filterIndexed { i, _ -> r.ibiStatus.getOrNull(i) == 0 } else r.ibi
        return HrSample(atMs, bpm, status, ibi)
    }

    /** 세션 → 관문 입력. 시각 대응([ClockMap])이 없으면 Sensor SDK 샘플을 단조 축에 놓을 수 없어 빈 입력이다. */
    fun input(s: BioSession, filterIbi: Boolean = true): ReplayInput {
        val off = s.clock.wallMinusMono ?: return ReplayInput(emptyList(), emptyList(), emptyList())
        fun <R, T : Any> conv(rows: List<R>, epoch: (R) -> Long, recv: (R) -> Long, f: (R, Long) -> T?): List<Timed<T>> =
            rows.mapNotNull { r -> val at = epoch(r) - off; f(r, at)?.let { Timed(it, at, recv(r)) } }.sortedBy { it.atMs }
        return ReplayInput(
            ppg = conv(s.ppg, PpgRow::sampleEpochMs, PpgRow::recvMs, ::ppg),
            accel = conv(s.acc, AccRow::sampleEpochMs, AccRow::recvMs, ::accel),
            hr = conv(s.hr, HrRow::sampleEpochMs, HrRow::recvMs) { r, at -> hr(r, at, filterIbi) },
        )
    }
}

/** 평가 시각의 기준 — 샘플 자체 시각(자료가 말하는 것) · 도착 시각(화면 꺼짐 묶음 지연까지 실시간이었다면). */
enum class TimeBasis { SAMPLE, RECV }

/**
 * 1초 판정 한 줄. [tMs] 는 단조 축. [hasData] 는 평가 시각 직전 [Replayer.DATA_FRESH_MS] 안에 PPG 또는 심박 샘플이 들어왔는가 —
 * 거짓이면 관문의 NORMAL 은 자료 없이 나온 값이라 「정상」으로 세지 않는다.
 */
data class Tick(val tMs: Long, val verdict: WristVerdict, val reasons: Set<GateReason>, val hr: Int?, val hasData: Boolean = true)

object Replayer {
    const val EVERY_MS = 1_000L

    /** 「자료 있음」 창 — 관문의 심박 신선도(`GateParams.hrStaleMs` 3초)와 같다. */
    const val DATA_FRESH_MS = 3_000L

    /**
     * [t0] 부터 [tEnd] 까지 [EVERY_MS] 마다 — 그 시각까지 들어온 샘플을 넣고 제품 관문([GateRunner], 주행 기준 고정)으로 판정한다.
     * 넣는 순서는 기준 시각 오름차순(같으면 샘플 시각) — 묶음 도착도 스트림 안에서는 시각 순서를 지키므로 관문 가정과 맞는다.
     */
    fun run(input: ReplayInput, basis: TimeBasis, t0: Long, tEnd: Long, params: GateParams = GateParams()): List<Tick> {
        fun <T> key(x: Timed<T>) = if (basis == TimeBasis.SAMPLE) x.atMs else x.recvMs
        val ppg = input.ppg.sortedWith(compareBy({ key(it) }, { it.atMs }))
        val acc = input.accel.sortedWith(compareBy({ key(it) }, { it.atMs }))
        val hr = input.hr.sortedWith(compareBy({ key(it) }, { it.atMs }))
        val gate = GateRunner(params)
        val out = ArrayList<Tick>()
        var ip = 0; var ia = 0; var ih = 0
        var lastPpg = Long.MIN_VALUE; var lastHr = Long.MIN_VALUE
        var t = t0 + EVERY_MS
        while (t <= tEnd) {
            while (ip < ppg.size && key(ppg[ip]) <= t) { lastPpg = key(ppg[ip]); gate.ppg(ppg[ip++].sample) }
            while (ia < acc.size && key(acc[ia]) <= t) gate.accel(acc[ia++].sample)
            while (ih < hr.size && key(hr[ih]) <= t) { lastHr = key(hr[ih]); gate.hr(hr[ih++].sample) }
            val r = gate.evaluate(t)
            val hasData = (lastPpg != Long.MIN_VALUE && t - lastPpg <= DATA_FRESH_MS) || (lastHr != Long.MIN_VALUE && t - lastHr <= DATA_FRESH_MS)
            out += Tick(t, r.verdict, r.reasons, r.hr, hasData)
            t += EVERY_MS
        }
        return out
    }
}
