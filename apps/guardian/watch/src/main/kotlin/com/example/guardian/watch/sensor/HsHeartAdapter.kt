// Health Services 심박(IBI 없음)을 관문 심박 샘플로 옮긴다 — 착용 안 함 · 측정 중 상태를 상태값으로 바꿔 1초마다 다시 싣고, 묶음으로 온 마지막 유효 심박은 10초 안에서 1초마다 다시 실어 관문에 신선하게 보이며, 유효 심박이 10초 넘게 없으면 「신호 없음」을 센다
package com.example.guardian.watch.sensor

import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.HrStatus
import kotlin.math.roundToInt

/**
 * Health Services 는 심박(bpm)과 가용성만 준다(PPG · 가속도 · IBI 없음). 그래서 관문은 A3(서맥 지속)만 판단하게 되고,
 * 화면에 「판정 신뢰 낮음」을 표시한다(설계 §3).
 * - 값이 오면 bpm 을 간격 하나(60000/bpm ms)로 바꿔 싣는다 — 「유효 박동이 들어왔다」는 표시다. IBI 실측값이 아니다.
 * - 착용 안 함 → 상태 −3(CHECK_WEAR), 측정 중 · 쓸 수 없음 → −8(신호 약함). 관문은 3초 넘은 심박을 버리므로 1초마다 다시 싣는다.
 * - 가용성 깜빡임 유예(합동 점검 B-1) — 유효 심박 중에 측정 중 · 쓸 수 없음으로 바뀌어도 [GRACE_MS] 동안은 마지막 유효 심박을
 *   보류값으로 계속 싣는다(서맥 시작 시각이 지워지지 않게). 그 상태가 3초 넘게 이어질 때만 신호 약함을 싣는다. 착용 안 함은 바로다.
 * - 간격은 아래쪽만 250ms 로 묶는다(B-3) — 241회/분 이상이 「유효 박동 아님」이 되어 박동 공백으로 오판되지 않게.
 *   30회/분 미만(2000ms 초과)은 그대로 둬 관문이 박동 공백으로 빨리 잡는다(위쪽까지 묶으면 판정이 6초 늦어진다).
 * - 묶음 전달(운동 세션 · 화면 꺼짐) — 샘플은 자체 시각([onBpm] 의 atMs)대로 싣는다. 관문의 심박 유효 시간(`hrStaleMs` 3초)은 1초 측정을
 *   전제라서, 묶음 사이에는 심박 숫자가 비고 A3 판정도 멈춘다. 그래서 마지막 유효 심박을 [NO_SIGNAL_MS] 안에서 1초마다
 *   atMs = 지금으로 다시 싣는다(보류). 간격(IBI)도 같이 싣는다 — 빼면 관문이 「상태 정상인데 유효 박동 없음 4초」(A3 박동 공백)로
 *   오판한다. 부작용은 `lastPulseAt` 이 마지막 실제 샘플보다 최대 10초 늦게 보이는 것이다. 신호 없음 뒤에는 보류하지 않는다.
 *   보류 표본에는 `held = true` 를 단다 — 관문은 신선도 · 박동 시각만 갱신하고 서맥 시작 · 띠 이탈 시간 · 순서 시계는 세지 않는다.
 * - 신호 없음([noSignal]) — 관문은 PPG 없이 「신호 약함」을 느슨함으로 못 보고, 심박이 끊기면 NORMAL 로 되돌아간다(코드 리뷰 2026-11-02 상 1).
 *   그래서 유효 심박(bpm > 0, OK)이 [NO_SIGNAL_MS] 넘게 없으면(측정 중 · 쓸 수 없음 지속 · 콜백 자체가 안 옴 포함) 또는 등록에 실패하면
 *   신호 없음으로 알린다. 착용 안 함은 신호 없음이 아니라 CHECK_WEAR 그대로다. 받는 쪽은 신호 없음인 값의 판정을 쓰지 않는다(계약 §4.3).
 *   공백은 샘플 자체 시각으로 잰다 — 묶음이 늦게 와도 그 안의 가장 새 샘플 기준이고, 시각이 뒤섞여 와도 뒤로 가지 않는다.
 *
 * [startedAtMs] 는 측정을 시작한 단조 시각 — 첫 유효 심박이 올 때까지의 기준점(Health Services 가 첫 값을 주기까지 몇 초 걸린다 [미검증]).
 */
class HsHeartAdapter(private val startedAtMs: Long, private val out: (HrSample) -> Unit) {
    enum class Availability { AVAILABLE, ACQUIRING, OFF_BODY, UNAVAILABLE }

    private var availability = Availability.ACQUIRING
    /** 관문에 마지막으로 실은 샘플의 atMs(관문의 `latestHr` 가 보는 시각). 묶음이면 과거일 수 있다. */
    private var lastEmitAtMs: Long? = null
    private var lastOkAtMs: Long? = null
    private var lastOk: HrSample? = null
    private var registrationFailed = false
    /** 유효 심박(AVAILABLE)에서 측정 중 · 쓸 수 없음으로 떠난 단조 시각. 둘 사이를 오가도 다시 재지 않는다. */
    private var leftAvailableAtMs: Long? = null

    fun onAvailability(a: Availability, nowMs: Long) {
        val weak = a == Availability.ACQUIRING || a == Availability.UNAVAILABLE
        if (!weak) leftAvailableAtMs = null
        else if (availability == Availability.AVAILABLE) leftAvailableAtMs = nowMs
        availability = a
        lastEmitAtMs = null
        tick(nowMs)
    }

    fun onBpm(bpm: Double, atMs: Long) {
        if (availability == Availability.OFF_BODY || !bpm.isFinite() || bpm <= 0) return
        availability = Availability.AVAILABLE
        leftAvailableAtMs = null
        val s = HrSample(atMs, bpm.roundToInt(), HrStatus.OK, listOf((60_000 / bpm).roundToInt().coerceAtLeast(MIN_IBI_MS)))
        out(s)
        lastEmitAtMs = atMs
        if (lastOkAtMs?.let { atMs >= it } != false) {
            lastOkAtMs = atMs
            lastOk = s
        }
    }

    /** 측정 등록 실패(`onRegistrationFailed`) — 이 뒤로는 값이 오지 않으므로 늘 신호 없음이다. */
    fun onRegistrationFailed() {
        registrationFailed = true
    }

    /** 1초마다 부른다. 값이 끊긴 상태(착용 안 함 · 측정 중)를 다시 싣고, 묶음 사이에는 마지막 유효 심박을 보류한다. */
    fun tick(nowMs: Long) {
        val last = lastEmitAtMs
        if (last != null && nowMs - last < 1_000) return
        val sample = when (availability) {
            Availability.AVAILABLE -> {
                val held = lastOk ?: return
                if (nowMs - held.atMs > NO_SIGNAL_MS) return // 신호 없음 — 더 싣지 않아 관문 심박이 빈다
                held.copy(atMs = nowMs, held = true)
            }
            Availability.OFF_BODY -> HrSample(nowMs, 0, HrStatus.OFF_WRIST)
            Availability.ACQUIRING, Availability.UNAVAILABLE -> {
                val held = lastOk
                val left = leftAvailableAtMs
                if (held != null && left != null && nowMs - left <= GRACE_MS && nowMs - held.atMs <= NO_SIGNAL_MS) held.copy(atMs = nowMs, held = true)
                else HrSample(nowMs, 0, HrStatus.WEAK_SIGNAL)
            }
        }
        out(sample)
        lastEmitAtMs = nowMs
    }

    /** 지금 신호를 못 잡고 있는가 — 등록 실패, 또는 착용 안 함이 아닌데 유효 심박이 [NO_SIGNAL_MS] 넘게 없음. */
    fun noSignal(nowMs: Long): Boolean {
        if (registrationFailed) return true
        if (availability == Availability.OFF_BODY) return false
        return nowMs - (lastOkAtMs ?: startedAtMs) > NO_SIGNAL_MS
    }

    companion object {
        /**
         * 유효 심박 공백 기준. 관문의 심박 상태 유효 시간(`GateParams.hrStaleMs` 3초)보다 길어 1초 주기의 짧은 빈틈에 흔들리지 않고,
         * 관문이 한 판정을 확정하는 가장 긴 창(서맥 `bradyMs` · 두 파장 `bWindowMs` 10초)과 같다. 받는 쪽 30초 신선도 규칙과 합쳐
         * 신호가 끊긴 뒤 약 40초 안에 「워치 신호 없음」이 뜬다. PM 기본값 10초(2026-11-02).
         * 묶음 전달을 위해 올리지 않는다 — 올리면 40초를 넘긴다. 대신 출처가 전달 지연을 이 값 아래로 묶는다
         * (운동 세션 5초 묶음 모드 · 주기 flush, [HealthServicesSource]).
         */
        const val NO_SIGNAL_MS = 10_000L

        /** 측정 중 · 쓸 수 없음 깜빡임을 보류로 넘기는 시간(B-1, be-qa 2차 실행 조합). 관문은 「OK 아님」을 바로 반영하므로 겹치지 않는다. */
        const val GRACE_MS = 3_000L

        /** 간격 표시값의 아래 끝 — 관문의 유효 간격 범위(250~2000ms) 아래 끝(B-3). */
        const val MIN_IBI_MS = 250
    }
}
