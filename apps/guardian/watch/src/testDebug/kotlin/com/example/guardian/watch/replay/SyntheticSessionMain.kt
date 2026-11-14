// 합성 세션 폴더를 명령으로 만든다 — `./gradlew :watch:writeSyntheticBioSession -Pout=<폴더>`(재생 도구를 기기 없이 끝까지 돌려 보는 용도, 모두 합성값)
package com.example.guardian.watch.replay

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.SimTrace
import com.example.guardian.shared.bio.Simulator
import java.io.File
import kotlin.system.exitProcess

object SyntheticSessionMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull()?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }
        if (out == null) {
            System.err.println("사용법 — ./gradlew :watch:writeSyntheticBioSession -Pout=<폴더>")
            exitProcess(2)
        }
        composite().write(out)
        println("합성 세션 씀 — $out")
    }

    /**
     * 5분짜리 합성 세션 — 정상 2분(정지 → 50 km/h → 정지) · 진폭 급감 90초(45초에 사건) · 맥 소실 90초(45초에 사건).
     * 60초부터 화면 꺼짐(2초 묶음 도착), Health Services 심박 함께 켬, PPG 상태는 20개 중 1개 −1.
     */
    fun composite(seed: Long = 11): SimTrace {
        val sim = Simulator(seed)
        val a = sim.generate(Scenario.NORMAL, durationMs = 120_000, startAtMs = 0)
        val b = sim.generate(Scenario.AMPLITUDE_DROP, durationMs = 90_000, eventAtMs = 45_000, startAtMs = 120_000)
        val c = sim.generate(Scenario.PULSE_LOST, durationMs = 90_000, eventAtMs = 45_000, startAtMs = 210_000)
        val trace = SimTrace(a.ppg + b.ppg + c.ppg, a.accel + b.accel + c.accel, a.hr + b.hr + c.hr)
        return trace
    }

    private fun SimTrace.write(out: File) = SyntheticSession(
        this,
        speedKmhAt = { t ->
            val e = t - SyntheticSession.MONO0
            when {
                e < 20_000 -> 0.0
                e < 100_000 -> 50.0
                e < 240_000 -> 0.0
                else -> null
            }
        },
        screenOffFromMs = SyntheticSession.MONO0 + 60_000,
        ppgStatusAt = { i -> if (i % 20 == 19) -1 else 0 },
        batteryStart = 88, batteryEnd = 86, hs = true,
    ).write(out)
}
