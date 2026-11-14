// Health Services 호출 경계 — 운동 세션(ExerciseClient) · 측정(MeasureClient)을 얇게 감싸 실제 구현(AndroidHsClient)과 시험용 가짜를 바꿔 끼운다
package com.example.guardian.watch.sensor

/**
 * [HealthServicesSource] 가 쓰는 호출만 둔다(추상화를 넓히지 않는다). 모든 결과 · 콜백은 어느 스레드에서든 올 수 있다 —
 * 부르는 쪽이 자기 실행기로 옮긴다.
 */
interface HsClient {
    /** 기기의 운동 세션 상태 — 없음 · 우리 것(비정상 종료 뒤 남은 것 포함) · 다른 앱 것. */
    enum class ExerciseStatus { NONE, OURS, OTHER_APP }

    /** 우리 운동 종류가 심박을 주는지, 화면 꺼짐 5초 묶음 모드(`BatchingMode.HEART_RATE_5_SECONDS`)를 지원하는지. */
    data class ExerciseCaps(val heartRate: Boolean, val batch5s: Boolean)

    interface Listener {
        fun onAvailability(a: HsHeartAdapter.Availability)

        /** 심박 한 점. [atMs] 는 샘플 자체의 부팅 뒤 경과 시각(= elapsedRealtime). 묶음이면 과거 시각이 여러 점 온다. */
        fun onBpm(bpm: Double, atMs: Long)

        /** 운동 세션이 끝났다(다른 앱이 가져감 · 권한 상실 · 시스템 종료). 측정 경로에서는 오지 않는다. */
        fun onExerciseEnded(reason: String)
    }

    /** 운동 세션 상태를 묻는다. 조회 자체가 실패하면 null. */
    fun exerciseStatus(onResult: (ExerciseStatus?) -> Unit)

    /** 운동 세션 능력. 조회 실패면 null. */
    fun exerciseCapabilities(onResult: (ExerciseCaps?) -> Unit)

    /** 콜백을 등록한 뒤 심박만 받는 운동 세션을 연다(GPS · 자동 일시정지 끔). 등록 · 시작 실패는 [onStarted] 에 예외로 온다. */
    fun startExercise(batch5s: Boolean, listener: Listener, onStarted: (Throwable?) -> Unit)

    /** 묶여 있는 운동 세션 값을 바로 보내 달라고 한다. */
    fun flushExercise()

    /** 우리가 연 운동 세션을 끝내고 콜백을 거둔다. */
    fun endExercise(onDone: () -> Unit)

    /** 측정 경로(MeasureClient) 심박 등록. 등록 실패는 [onFailed] 로 온다. */
    fun startMeasure(listener: Listener, onFailed: (Throwable) -> Unit)

    fun stopMeasure()
}
