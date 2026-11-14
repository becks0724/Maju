// 구글 Health Services 보완 출처 — 운동 세션(ExerciseClient)으로 심박을 받아 화면이 꺼져도 끊기지 않게 하고, 다른 앱이 운동 중이면 빼앗지 않고 측정(MeasureClient)으로 내려간다(Sensor SDK 가 없거나 실패한 기기 · 판정 신뢰 낮음)
package com.example.guardian.watch.sensor

import android.util.Log
import com.example.guardian.shared.protocol.WatchSource
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * 주행 3(2026-11-02) 실측 — MeasureClient 는 화면이 꺼지면 심박 간격이 13초 → 중앙 63초(최대 115초)로 늘어 차 화면의 67% 가
 * 「워치 신호 없음」이었다(공식 안내대로 짧은 화면 측정용). 운동 세션은 화면이 꺼져도 잰 값을 묶어 보내므로 이 경로를 먼저 쓴다.
 * - 운동 세션은 기기 전체에 하나뿐이다 — 다른 앱이 쓰고 있으면 사용자의 실제 운동을 끊지 않고 측정으로 간다. 도중에 빼앗기거나
 *   시스템이 끝내도 측정으로 내려가고, 그동안 값이 끊기면 어댑터의 신호 없음 규칙으로 드러난다. [RETRY_MS] 마다 다시 잡아 본다.
 * - 획득(상태 조회 → 능력 조회 → 시작) 전체에 [ACQUIRE_TIMEOUT_MS] 를 둔다 — Health Services 가 응답하지 않으면 측정으로 내려가 다시
 *   잡아 본다. 늦게 온 응답은 시도 번호([acquireSeq])가 달라 버리고, 늦게 열린 세션은 바로 닫는다.
 * - 묶음 전달 지연을 어댑터의 신호 없음 창(10초) 아래로 묶는다 — 기기가 지원하면 5초 묶음 모드, 그리고 [flush] 를 [FLUSH_EVERY_MS]
 *   간격으로 보낸다(서비스가 2초마다 부른다). 샘플은 자체 시각대로 관문에 들어간다.
 * - 감시를 끄면 우리가 연 세션만 끝낸다. 비정상 종료 뒤 우리 세션이 남아 있으면(OURS) 끝내고 새로 연다. 끝나지 않으면 측정으로 가고
 *   [RETRY_MS] 마다 한 번씩만 다시 끝내 본다(끝없이 끝내고 여는 반복 없음).
 * - 판정은 그대로 심박만 → A3 만 · 판정 신뢰 낮음. 화면 · 알림 · 문구를 새로 만들지 않는다.
 * - 경로 로그(태그 `GuardianWatch`, 접두 `HS`) — 사실만 남기고 심박 수치는 찍지 않는다(G2). 1분마다 샘플 도착 요약을 남긴다.
 * 상태는 모두 [executor](감시 tick 실행기 한 스레드)에서 바꾼다. [stop] 과 「멈춘 뒤 늦게 열린 세션 닫기」만 부른 스레드에서 바로 실행한다
 * (실행기가 곧 닫히거나 이미 닫혔으므로). 실기기 동작(운동 중 표시 · 삼성 헬스 기록 · 배터리 · 실제 묶음 간격)은 [미검증].
 */
class HealthServicesSource(
    private val client: HsClient,
    private val executor: Executor,
    private val nowMono: () -> Long,
) : SensorSource {
    override val kind = WatchSource.HEALTH_SERVICES
    override val lowConfidence = true

    @Volatile private var stopped = false
    @Volatile private var exercising = false
    @Volatile private var measuring = false
    private var acquiring = false
    private var acquireSeq = 0
    private var acquireStartedAtMs = 0L
    private var endedLeftover = false
    /** 시작 결과보다 먼저 온 「세션 끝남」의 이유 — 시작 성공 처리에서 반영한다(코드 리뷰 11-08 하). */
    private var endedWhileStarting: String? = null
    private var caps: HsClient.ExerciseCaps? = null
    /** 측정 경로에서 운동 세션을 다시 잡아 볼 단조 시각. null 이면 다시 잡지 않는다(심박 미지원 확정). */
    private var retryAtMs: Long? = null
    private var lastFlushAtMs: Long? = null
    /** [stop] 은 부른 스레드(서비스 메인)에서 비우고 콜백은 실행기에서 읽는다 — 보이게 volatile. */
    @Volatile private var adapter: HsHeartAdapter? = null
    private var onFailure: (String) -> Unit = {}

    // 1분 요약(로그용) — 값이 아니라 도착 사실만 센다
    private var summaryStartMs: Long? = null
    private var samples = 0
    private var flushes = 0
    private var lastArrivalMs: Long? = null
    private var arrivals = 0
    private var maxAgeMs = 0L
    private val arrivalGaps = mutableListOf<Long>()

    /** 지금 운동 세션으로 받고 있는가(로그 · 시험용). */
    val exerciseActive: Boolean get() = exercising

    /** 값 · 가용성 리스너(측정 · 운동 공통). 「끝남」은 운동 세션마다 따로 묶는다([sessionListener]) — 측정 경로에서는 오지 않는다. */
    private val listener = object : HsClient.Listener {
        override fun onAvailability(a: HsHeartAdapter.Availability) = post { adapter?.onAvailability(a, nowMono()) }
        override fun onBpm(bpm: Double, atMs: Long) = post {
            noteArrival(atMs)
            adapter?.onBpm(bpm, atMs)
        }
        override fun onExerciseEnded(reason: String) = Unit
    }

    /**
     * 시도 [seq] 로 연 운동 세션 하나에 묶인 리스너. 「끝남」은 그 시도가 지금 시도(또는 지금 쓰는 세션)일 때만 받는다 —
     * 앞서 빼앗긴 세션 · 시간 제한 뒤 늦게 열려 닫은 세션의 늦은 끝남이 멀쩡한 지금 세션을 측정으로 내리거나 지금 시도를
     * 「시작 직후 끝남」으로 만들지 않게(코드 리뷰 11-08 관찰 ①). 운동 중에는 [acquireSeq] 가 그 세션의 시도 번호 그대로다.
     */
    private fun sessionListener(seq: Int) = object : HsClient.Listener by listener {
        override fun onExerciseEnded(reason: String) = post {
            if (stopped) return@post
            if (seq != acquireSeq) { Log.i(TAG, "HS 앞선 세션의 끝남 — 무시 $reason"); return@post }
            if (!exercising) { if (acquiring) endedWhileStarting = reason; return@post } // 시작 직후 끝나 시작 결과보다 먼저 왔다
            exercising = false
            fallBack("운동 세션 끝남 $reason", retry = true)
        }
    }

    override fun start(sink: SampleSink, onFailure: (String) -> Unit) {
        this.onFailure = onFailure
        adapter = HsHeartAdapter(nowMono(), sink::hr)
        post { acquireExercise(fresh = true) }
    }

    /** 이 시도 번호의 응답이 아직 유효한가 — 멈췄거나, 시간 제한 · 다른 시도로 넘어갔으면 버린다. */
    private fun current(seq: Int) = !stopped && acquiring && seq == acquireSeq

    /** 운동 세션을 잡아 본다. 다른 앱이 쓰고 있으면 빼앗지 않는다. [fresh] 가 거짓이면 남은 세션을 끝낸 뒤 이어지는 같은 시도다. */
    private fun acquireExercise(fresh: Boolean) {
        if (acquiring || exercising || stopped) return
        acquiring = true
        if (fresh) {
            endedLeftover = false
            acquireStartedAtMs = nowMono()
        }
        val seq = ++acquireSeq
        client.exerciseStatus { status ->
            post {
                if (!current(seq)) return@post
                when (status) {
                    null -> fallBack("상태 조회 실패(Health Services 바인딩 지연 · 없음)", retry = true)
                    HsClient.ExerciseStatus.OTHER_APP -> fallBack("다른 앱이 운동 중 — 끊지 않음", retry = true)
                    HsClient.ExerciseStatus.OURS ->
                        if (endedLeftover) fallBack("우리 세션이 끝나지 않음", retry = true)
                        else {
                            endedLeftover = true
                            Log.i(TAG, "HS 남은 우리 세션 끝내는 중")
                            client.endExercise {
                                post {
                                    if (!current(seq)) return@post
                                    acquiring = false
                                    acquireExercise(fresh = false)
                                }
                            }
                        }
                    HsClient.ExerciseStatus.NONE -> withCaps(seq) { c ->
                        when {
                            c == null -> fallBack("능력 조회 실패", retry = true)
                            !c.heartRate -> fallBack("운동 세션이 심박 미지원 — 측정 고정", retry = false)
                            else -> startExercise(seq, c.batch5s)
                        }
                    }
                }
            }
        }
    }

    private fun withCaps(seq: Int, block: (HsClient.ExerciseCaps?) -> Unit) {
        caps?.let { block(it); return }
        client.exerciseCapabilities { c ->
            post {
                if (!current(seq)) return@post
                caps = c
                block(c)
            }
        }
    }

    private fun startExercise(seq: Int, batch5s: Boolean) {
        endedWhileStarting = null
        client.startExercise(batch5s, sessionListener(seq)) { error ->
            // 멈춘 뒤에 열렸으면 실행기(닫혔을 수 있다)에 넘기지 않고 그 자리에서 닫는다
            if (stopped) { if (error == null) client.endExercise {}; return@startExercise }
            post {
                if (!current(seq)) {
                    if (error == null) { Log.i(TAG, "HS 시간 제한 뒤 늦게 열린 세션 — 바로 닫음"); client.endExercise {} }
                    return@post
                }
                acquiring = false
                if (error != null) { fallBack("운동 세션 시작 실패 ${error::class.java.simpleName}", retry = true); return@post }
                endedWhileStarting?.let { endedWhileStarting = null; fallBack("운동 세션 시작 직후 끝남 $it", retry = true); return@post }
                exercising = true
                retryAtMs = null
                Log.i(TAG, "HS 운동 세션 시작 — 5초 묶음 지원=$batch5s 사용=$batch5s 측정에서 올라옴=$measuring")
                if (measuring) { measuring = false; client.stopMeasure() }
            }
        }
    }

    /** 운동 세션을 못 잡았을 때 — 측정으로 가고, [retry] 면 [RETRY_MS] 뒤 다시 잡아 본다. */
    private fun fallBack(reason: String, retry: Boolean) {
        acquiring = false
        retryAtMs = if (retry) nowMono() + RETRY_MS else null
        Log.i(TAG, "HS 측정으로 내려감 — $reason" + if (retry) " · ${RETRY_MS / 1000}초 뒤 다시 잡아 봄" else "")
        useMeasure()
    }

    private fun useMeasure() {
        if (measuring || stopped) return
        measuring = true
        client.startMeasure(listener) { t ->
            post {
                if (stopped) return@post
                adapter?.onRegistrationFailed() // 이 뒤로는 신호 없음으로 보낸다(부른 쪽이 알림도 올린다)
                Log.i(TAG, "HS 측정 등록 실패 ${t::class.java.simpleName}")
                onFailure("Health Services 등록 실패 ${t::class.java.simpleName}")
            }
        }
        // 등록하는 사이 다른 스레드에서 stop() 이 왔으면 등록이 남지 않게 바로 해제한다(코드 리뷰 11-08 하)
        if (stopped) { measuring = false; client.stopMeasure() }
    }

    /** 1초마다(감시 tick 실행기) — 끊긴 상태 · 보류 심박을 관문에 다시 싣고, 획득 시간 제한 · 재시도 · 1분 요약을 처리한다. */
    fun tick(nowMs: Long) {
        adapter?.tick(nowMs)
        summarize(nowMs)
        if (acquiring && nowMs - acquireStartedAtMs > ACQUIRE_TIMEOUT_MS) {
            acquireSeq++ // 이 뒤에 오는 응답은 버린다
            fallBack("Health Services 무응답 ${ACQUIRE_TIMEOUT_MS / 1000}초", retry = true)
        }
        val at = retryAtMs ?: return
        if (nowMs >= at && !exercising && !acquiring) {
            retryAtMs = null
            Log.i(TAG, "HS 운동 세션 다시 잡기 시도")
            acquireExercise(fresh = true)
        }
    }

    /** 운동 세션 묶음을 당겨 받는다 — 서비스가 2초마다 부르고 여기서 [FLUSH_EVERY_MS] 로 띄운다(공식 문서 「아껴 쓸 것 · 조절됨」). */
    override fun flush() {
        if (!exercising) return
        val now = nowMono()
        val last = lastFlushAtMs
        if (last != null && now - last < FLUSH_EVERY_MS) return
        lastFlushAtMs = now
        flushes++
        client.flushExercise()
    }

    /** 유효 심박이 10초 넘게 없거나 등록에 실패했다(계약 §4.3 `noSignal`). */
    override fun noSignal(nowMs: Long): Boolean = adapter?.noSignal(nowMs) == true

    override fun stop() {
        stopped = true
        Log.i(TAG, "HS 종료 — 우리 세션 끝냄=$exercising 측정 해제=$measuring")
        if (exercising) { exercising = false; client.endExercise {} } // 우리가 연 세션만 — 다른 앱 것은 건드리지 않는다
        if (measuring) { measuring = false; client.stopMeasure() }
        adapter = null
    }

    // ── 로그용 도착 요약 ──

    private fun noteArrival(atMs: Long) {
        val now = nowMono()
        samples++
        if (now - atMs > maxAgeMs) maxAgeMs = now - atMs
        if (now != lastArrivalMs) arrivals++
        lastArrivalMs?.let { if (now != it) arrivalGaps += now - it } // 같은 묶음(같은 ms)은 한 번의 도착으로 본다
        lastArrivalMs = now
    }

    private fun summarize(nowMs: Long) {
        val startAt = summaryStartMs ?: run { summaryStartMs = nowMs; return }
        if (nowMs - startAt < SUMMARY_EVERY_MS) return
        val sorted = arrivalGaps.sorted()
        val median = if (sorted.isEmpty()) -1 else sorted[sorted.size / 2]
        val max = sorted.lastOrNull() ?: -1
        Log.i(
            TAG,
            "HS 1분 요약 — 경로=${if (exercising) "운동" else if (measuring) "측정" else "없음"} 샘플 ${samples}건 도착 ${arrivals}번 " +
                "도착 간격 중앙 ${median}ms 최대 ${max}ms 가장 새 샘플 나이 최대 ${maxAgeMs}ms flush ${flushes}번 신호 없음=${noSignal(nowMs)}",
        )
        summaryStartMs = nowMs
        samples = 0; arrivals = 0; flushes = 0; maxAgeMs = 0; arrivalGaps.clear()
    }

    private fun post(block: () -> Unit) {
        try { executor.execute(block) } catch (_: RejectedExecutionException) { /* 감시가 끝나 실행기가 닫혔다 */ }
    }

    companion object {
        private const val TAG = "GuardianWatch"

        /** 운동 세션을 못 잡았을 때(다른 앱 · 빼앗김 · 시작 실패 · 조회 실패 · 무응답) 다시 잡아 보는 간격. */
        const val RETRY_MS = 30_000L

        /** 획득(상태 조회 → 능력 조회 → 시작) 전체의 시간 제한. 넘기면 측정으로 내려가 [RETRY_MS] 뒤 다시 잡아 본다. */
        const val ACQUIRE_TIMEOUT_MS = 15_000L

        /**
         * flush 간격. 신호 없음 창(10초)의 절반 아래로 두어 묶음이 늦어도 어댑터가 끊김으로 읽지 않게 한다. 서비스가 2초마다 부르므로
         * 실제로는 4초마다 한 번이다. 5초 묶음 모드가 실기기에서 확인되면 줄이거나 끌 수 있다 [미검증].
         */
        const val FLUSH_EVERY_MS = 4_000L

        private const val SUMMARY_EVERY_MS = 60_000L
    }
}
