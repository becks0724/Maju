// 1초 판정열을 제품 상태 기계(:shared FlowMachine, 기본 FlowParams)에 넣어 경보 · 무응답 단계 · 보호자 문자 지시를 센다 — 응답 모델 셋(무응답 · 5초 뒤 · 20초 뒤), 로직 복제 없음
package com.example.guardian.watch.replay

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowParams
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.GuardianSmsKind
import com.example.guardian.shared.flow.ResponseSource
import com.example.guardian.shared.flow.SpeedSource
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.report.Consents

object FlowReplay {
    /** 운전자 응답 모델 — 경보 시작 뒤 [delayMs] 지나 워치 버튼을 누른다(null = 끝까지 무응답). */
    enum class ResponseModel(val delayMs: Long?, val label: String) {
        NONE(null, "무응답"), AFTER_5S(5_000, "경보 5초 뒤 응답"), AFTER_20S(20_000, "경보 20초 뒤 응답"),
    }

    data class Result(
        val model: ResponseModel,
        /** 새 경보(진동, level 1 `AlertStart`) 수 = 사건 수. */
        val alertStarts: Int,
        /** 소리 단계(level 2) 수. */
        val stage2Starts: Int,
        val s3Entries: Int,
        val s4Entries: Int,
        val incidentSms: Int,
        val cancelSms: Int,
        val compose119: Int,
        val responses: Int,
    )

    /**
     * 고정 입력 — 동의 셋 모두 참 · 전원 켬 · 테스트 아님 · 차 상태 없음(휴대폰 주인, 속도 출처 GPS) · 에어백 없음 · 워치 값 경과 0.
     * 속도는 [speedKmh](틱마다, 모르면 null) 를 `VehicleState(speedKmh, speedSource = PHONE_GPS)` 로 넣는다 — 기어 · 주차 브레이크 없음이라
     * 정지 = 1 km/h 이하 5초(`VehicleState.GPS_STOP_KMH` · `FlowParams.stopDebounceMs`).
     * 응답은 경보가 시작된 틱 + 지연이 지난 첫 틱에 `WATCH_BUTTON` 으로 넣는다.
     */
    fun run(ticks: List<Tick>, speedKmh: List<Double?>, model: ResponseModel, params: FlowParams = FlowParams()): Result {
        require(ticks.size == speedKmh.size)
        val m = FlowMachine(params)
        var alertStarts = 0; var stage2 = 0; var s3 = 0; var s4 = 0; var incident = 0; var cancel = 0; var compose = 0; var responses = 0
        var pendingResponseAt: Long? = null
        var prev = m.state
        fun absorb(out: List<Directive>, at: Long) {
            for (d in out) when (d) {
                is Directive.AlertStart -> if (d.level == 1) { alertStarts++; model.delayMs?.let { pendingResponseAt = at + it } } else stage2++
                is Directive.SendGuardianSms -> when (d.kind) {
                    GuardianSmsKind.INCIDENT -> incident++
                    GuardianSmsKind.CANCEL -> cancel++
                    else -> Unit
                }
                is Directive.Open119Compose -> compose++
                else -> Unit
            }
            val now = m.state
            if (now != prev) {
                if (now == FlowState.S3) s3++
                if (now == FlowState.S4) s4++
                prev = now
            }
        }
        if (ticks.isEmpty()) return Result(model, 0, 0, 0, 0, 0, 0, 0, 0)
        val t0 = ticks.first().tMs - Replayer.EVERY_MS
        absorb(m.handle(FlowInput.Vehicle(vehicle(speedKmh.first()), t0)), t0)
        absorb(m.handle(FlowInput.ConsentsChanged(Consents(emergencyInfo = true, health = true, location = true), t0)), t0)
        absorb(m.handle(FlowInput.Power(true, t0)), t0)
        absorb(m.handle(FlowInput.Wrist(WristVerdict.NORMAL, t0)), t0)
        ticks.forEachIndexed { i, tick ->
            val t = tick.tMs
            absorb(m.handle(FlowInput.Vehicle(vehicle(speedKmh[i]), t)), t)
            absorb(m.handle(FlowInput.Wrist(tick.verdict, t)), t)
            val due = pendingResponseAt
            if (due != null && t >= due) {
                pendingResponseAt = null
                responses++
                absorb(m.handle(FlowInput.Response(ResponseSource.WATCH_BUTTON, t)), t)
            }
        }
        return Result(model, alertStarts, stage2, s3, s4, incident, cancel, compose, responses)
    }

    private fun vehicle(speedKmh: Double?) = VehicleState(speedKmh = speedKmh, speedSource = SpeedSource.PHONE_GPS)
}
