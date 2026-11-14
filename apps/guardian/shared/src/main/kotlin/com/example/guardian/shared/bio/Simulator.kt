// 실신 테스트 모드용 결정적 시나리오 생성기 — 실제 센서와 같은 샘플 형식을 만들어 같은 관문 코드에 넣는다
package com.example.guardian.shared.bio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 계약 TEST_SCENARIO 이름과 같다. DRIVE_VIBRATION 은 시험용 추가(주행 진동 · 조향, 정상 맥박). */
enum class Scenario { NORMAL, AMPLITUDE_DROP, PULSE_LOST, LOOSE, NOT_WORN, MOTION, DRIVE_VIBRATION }

data class SimTrace(val ppg: List<PpgSample>, val accel: List<AccelSample>, val hr: List<HrSample>) {
    /** 샘플을 시간순으로 관문에 넣고 [everyMs] 마다 판정한다. 관문을 건너뛰지 않는다(설계 §3). */
    fun replay(gate: PulseGate, driving: Boolean, everyMs: Long = 1_000): List<Pair<Long, GateResult>> {
        val end = maxOf(ppg.lastOrNull()?.atMs ?: 0, accel.lastOrNull()?.atMs ?: 0, hr.lastOrNull()?.atMs ?: 0)
        val out = ArrayList<Pair<Long, GateResult>>()
        var ip = 0; var ia = 0; var ih = 0
        var t = everyMs
        while (t <= end) {
            while (ip < ppg.size && ppg[ip].atMs <= t) gate.addPpg(ppg[ip++])
            while (ia < accel.size && accel[ia].atMs <= t) gate.addAccel(accel[ia++])
            while (ih < hr.size && hr[ih].atMs <= t) gate.addHr(hr[ih++])
            out += t to gate.evaluate(t, driving)
            t += everyMs
        }
        return out
    }
}

/**
 * 시드 고정 시나리오. [eventAtMs] 전까지는 정상(72회/분 · 손목 정지)이고 그 뒤 시나리오 변화가 생긴다.
 * DRIVE_VIBRATION 은 처음부터 끝까지 주행 진동 + 조향 움직임 + 정상 맥박이다.
 * [carVibrationStd] 는 다른 시나리오에 차 진동(가속도 크기 잡음, m/s²)을 더한다 — 주행 중 늘어진 손목 재현.
 * 신호 크기 · 상태값 거동은 실측 전 [추정]이다.
 */
class Simulator(private val seed: Long = 42) {
    fun generate(
        scenario: Scenario,
        durationMs: Long = 90_000,
        eventAtMs: Long = 45_000,
        startAtMs: Long = 0,
        carVibrationStd: Double = 0.0,
    ): SimTrace {
        val rnd = Random(seed)
        fun gauss(): Double {
            // Box–Muller
            val u1 = rnd.nextDouble().coerceAtLeast(1e-12); val u2 = rnd.nextDouble()
            return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * PI * u2)
        }
        val ppg = ArrayList<PpgSample>(); val acc = ArrayList<AccelSample>(); val hr = ArrayList<HrSample>()
        val stepMs = 40L
        val baseBpm = if (scenario == Scenario.DRIVE_VIBRATION) 75.0 else 72.0
        var t = 0L
        while (t < durationMs) {
            val s = t / 1000.0
            val after = scenario != Scenario.NORMAL && scenario != Scenario.DRIVE_VIBRATION && t >= eventAtMs
            val f = baseBpm / 60
            val wave = sin(2 * PI * f * s) + 0.3 * sin(4 * PI * f * s + 0.5)
            val wander = 120 * sin(2 * PI * 0.15 * s)
            var acGain = 1.0; var dcG = 50_000.0; var dcI = 60_000.0; var dcR = 40_000.0
            var noise = 15.0; var artifact = 0.0
            var ax = 0.0; var ay = 0.0; var az = 9.81; var accNoise = 0.01
            if (after) when (scenario) {
                Scenario.PULSE_LOST -> acGain = 0.0
                Scenario.AMPLITUDE_DROP -> acGain = 0.05
                Scenario.LOOSE -> { acGain = 0.15; dcG *= 0.6; dcI *= 0.6; dcR *= 0.5; noise = 60.0 }
                Scenario.NOT_WORN -> { acGain = 0.0; dcG = 2_000.0; dcI = 1_500.0; dcR = 1_000.0; noise = 10.0; accNoise = 0.005 }
                Scenario.MOTION -> {
                    artifact = 3_000 * sin(2 * PI * 1.5 * s + 0.7 * sin(2 * PI * 0.2 * s)); noise = 300.0
                    ax = 3.0 * sin(2 * PI * 1.2 * s); ay = 2.0 * sin(2 * PI * 0.8 * s); az += 2.5 * sin(2 * PI * 1.0 * s)
                }
                else -> Unit
            }
            if (scenario == Scenario.DRIVE_VIBRATION) {
                az += 0.15 * gauss() + 0.8 * sin(2 * PI * 0.3 * s) + 0.5 * sin(2 * PI * 0.7 * s + 1)
                ax += 0.6 * sin(2 * PI * 0.3 * s)
                noise = 40.0
            }
            az += carVibrationStd * gauss()
            ppg += PpgSample(
                startAtMs + t,
                green = dcG + wander + 800 * acGain * wave + artifact + noise * gauss(),
                ir = dcI + wander + 600 * acGain * wave + 0.8 * artifact + noise * gauss(),
                red = dcR + 0.5 * wander + 400 * acGain * wave + 0.6 * artifact + noise * gauss(),
            )
            acc += AccelSample(startAtMs + t, ax + accNoise * gauss(), ay + accNoise * gauss(), az + accNoise * gauss())
            if (t % 1000 == 0L) hr += hrAt(scenario, t, eventAtMs, baseBpm, startAtMs, after)
            t += stepMs
        }
        return SimTrace(ppg, acc, hr)
    }

    private fun hrAt(scenario: Scenario, t: Long, eventAtMs: Long, bpm: Double, startAtMs: Long, after: Boolean): HrSample {
        val at = startAtMs + t
        val ibi = (60_000 / bpm).toInt()
        if (!after) return HrSample(at, bpm.toInt(), HrStatus.OK, listOf(ibi))
        return when (scenario) {
            // 처음 6초는 상태 정상이면서 IBI 없음(심박 값만 떨어짐), 그 뒤 신호 약함 [추정]
            Scenario.PULSE_LOST ->
                if (t - eventAtMs < 6_000) HrSample(at, (bpm - (t - eventAtMs) / 200).toInt(), HrStatus.OK)
                else HrSample(at, 0, HrStatus.WEAK_SIGNAL)
            Scenario.LOOSE -> HrSample(at, 0, HrStatus.WEAK_SIGNAL)
            Scenario.NOT_WORN -> HrSample(at, 0, HrStatus.OFF_WRIST)
            Scenario.MOTION -> HrSample(at, 90, HrStatus.MOTION)
            else -> HrSample(at, bpm.toInt(), HrStatus.OK, listOf(ibi))
        }
    }
}
