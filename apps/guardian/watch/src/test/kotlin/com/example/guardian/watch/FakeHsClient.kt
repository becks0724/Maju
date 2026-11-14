// 시험용 Health Services 가짜 — 운동 세션 상태 · 능력 · 시작 결과를 손으로 정하고, 잰 심박을 묶어 두었다가 주기나 flush 때 넘긴다
package com.example.guardian.watch

import com.example.guardian.watch.sensor.HsClient

class FakeHsClient : HsClient {
    var status: HsClient.ExerciseStatus? = HsClient.ExerciseStatus.NONE
    var caps: HsClient.ExerciseCaps? = HsClient.ExerciseCaps(heartRate = true, batch5s = true)
    var startError: Throwable? = null
    var measureError: Throwable? = null
    /** true 면 startExercise 결과를 [completeStart] 로 나중에 돌려준다. */
    var deferStart = false
    /** true 면 상태 조회에 응답하지 않는다(Health Services 무응답) — [completeStatus] 로 늦게 돌려줄 수 있다. */
    var deferStatus = false
    /** 시작이 예외로 끝났는데도 세션은 열린 경우(우리 세션이 남음). */
    var openDespiteError = false
    /** endExercise 를 불러도 세션이 우리 것으로 남는 경우. */
    var endKeepsOurs = false

    val calls = mutableListOf<String>()
    var lastBatch5s: Boolean? = null
    var exerciseListener: HsClient.Listener? = null
    var measureListener: HsClient.Listener? = null
    /** startExercise 마다 받은 리스너(시도 순서) — 앞 세션 콜백이 늦게 끝남을 보내는 흉내에 쓴다. */
    val sessionListeners = mutableListOf<HsClient.Listener>()
    private var pendingStart: ((Throwable?) -> Unit)? = null
    private var pendingStatus: ((HsClient.ExerciseStatus?) -> Unit)? = null
    private val buffered = mutableListOf<Pair<Double, Long>>()

    override fun exerciseStatus(onResult: (HsClient.ExerciseStatus?) -> Unit) {
        calls += "status"
        if (deferStatus) pendingStatus = onResult else onResult(status)
    }

    fun completeStatus(s: HsClient.ExerciseStatus?) { pendingStatus?.also { pendingStatus = null }?.invoke(s) }

    override fun exerciseCapabilities(onResult: (HsClient.ExerciseCaps?) -> Unit) {
        calls += "caps"
        onResult(caps)
    }

    override fun startExercise(batch5s: Boolean, listener: HsClient.Listener, onStarted: (Throwable?) -> Unit) {
        calls += "startExercise"
        lastBatch5s = batch5s
        startingListener = listener
        sessionListeners += listener
        val finish: (Throwable?) -> Unit = { err ->
            if (err == null || openDespiteError) { exerciseListener = listener; status = HsClient.ExerciseStatus.OURS }
            onStarted(err)
        }
        if (deferStart) pendingStart = finish else finish(startError)
    }

    fun completeStart() { pendingStart?.also { pendingStart = null }?.invoke(startError) }

    private var startingListener: HsClient.Listener? = null

    /** 시작 결과가 오기 전에 세션이 끝났다는 업데이트가 먼저 온다(시작 직후 시스템이 끝냄 · 권한 상실). */
    fun endWhileStarting(reason: String) { startingListener!!.onExerciseEnded(reason) }

    override fun flushExercise() {
        calls += "flush"
        deliverBuffered()
    }

    override fun endExercise(onDone: () -> Unit) {
        calls += "endExercise"
        exerciseListener = null
        if (status == HsClient.ExerciseStatus.OURS && !endKeepsOurs) status = HsClient.ExerciseStatus.NONE
        onDone()
    }

    /** 측정 등록이 끝나기 직전에 끼어드는 일(다른 스레드의 stop() 흉내). */
    var beforeMeasureRegistered: (() -> Unit)? = null

    override fun startMeasure(listener: HsClient.Listener, onFailed: (Throwable) -> Unit) {
        calls += "startMeasure"
        beforeMeasureRegistered?.invoke()
        measureListener = listener
        measureError?.let(onFailed)
    }

    override fun stopMeasure() {
        calls += "stopMeasure"
        measureListener = null
    }

    // ── 센서 흉내 ──

    /** 센서가 잰 값(아직 앱에 전달되지 않음). */
    fun sample(bpm: Double, atMs: Long) { buffered += bpm to atMs }

    /** 묶음을 운동 세션 리스너에 넘긴다(화면이 켜지거나 묶음이 찼거나 flush). 세션이 없으면 버린다. */
    fun deliverBuffered() {
        val l = exerciseListener
        if (l != null) for ((bpm, at) in buffered.sortedBy { it.second }) l.onBpm(bpm, at)
        buffered.clear()
    }

    /** 다른 앱이 운동을 시작해 우리 세션이 끝났다. */
    fun takenOverByOtherApp() {
        val l = exerciseListener
        exerciseListener = null
        status = HsClient.ExerciseStatus.OTHER_APP
        l?.onExerciseEnded("AUTO_ENDED/AUTO_END_SUPERSEDED")
    }

    fun count(call: String) = calls.count { it == call }
}
