// Health Services 실제 호출 — ExerciseClient(심박만 · WORKOUT · GPS · 자동 일시정지 끔 · 지원하면 5초 묶음)와 MeasureClient 를 HsClient 경계로 감싼다
package com.example.guardian.watch.sensor

import android.content.Context
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.clearUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.endExercise
import androidx.health.services.client.flush
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.getCurrentExerciseInfo
import androidx.health.services.client.startExercise
import androidx.health.services.client.unregisterMeasureCallback
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * - `*Async` 판은 guava `ListenableFuture` 가 컴파일 경로에 없어(runtime 범위) 못 부른다. 같은 라이브러리의 suspend 판을
 *   표준 라이브러리 `startCoroutine` 으로 부른다(새 의존성 없음 — `awaitWithException` 은 kotlinx 를 쓰지 않는다, javap 확인).
 * - 운동 세션 순서는 공식 안내대로 콜백 등록(`onRegistered`) → `startExercise`. 등록 실패 · 시작 예외는 [HsClient.startExercise] 의 onStarted 로 한 번만 보낸다.
 * - 샘플 시각은 `timeDurationFromBoot`(= elapsedRealtime) 그대로이고, 묶음은 시각순으로 넘긴다. 정확도(`HeartRateAccuracy`)는 측정 경로와 같이 쓰지 않는다.
 * - 콜백은 [executor] 에서 오고, suspend 결과는 Health Services 쪽 스레드에서 온다 — 받는 쪽([HealthServicesSource])이 옮긴다.
 * - 등록한 콜백 두 개는 tick 실행기 · 서비스 메인(끄기) · suspend 결과 스레드에서 함께 읽고 바꾼다 — 원자 참조로 두고,
 *   거둘 때는 꺼낸 쪽 하나만 해제한다(코드 리뷰 11-08 관찰 ②).
 * - 거둔 콜백에 늦게 온 업데이트와 우리가 시작을 요청하기 전에 시작된 세션(남은 우리 세션)의 업데이트는 버린다 — 새 콜백을
 *   등록하면 끝난 앞 세션의 마지막 업데이트가 올 수 있어, 그 「끝남」이 새 세션의 콜백을 거두고 측정으로 내리지 않게(관찰 ①) [미검증 — 실기기 전달 여부].
 */
class AndroidHsClient(context: Context, private val executor: Executor) : HsClient {
    private val hs = HealthServices.getClient(context.applicationContext)
    private val exerciseCb = AtomicReference<ExerciseUpdateCallback?>(null)
    private val measureCb = AtomicReference<MeasureCallback?>(null)

    override fun exerciseStatus(onResult: (HsClient.ExerciseStatus?) -> Unit) =
        launch({ hs.exerciseClient.getCurrentExerciseInfo() }) { r ->
            onResult(
                when (r.getOrNull()?.exerciseTrackedStatus) {
                    ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS -> HsClient.ExerciseStatus.NONE
                    ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS -> HsClient.ExerciseStatus.OURS
                    ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS -> HsClient.ExerciseStatus.OTHER_APP
                    else -> null
                },
            )
        }

    override fun exerciseCapabilities(onResult: (HsClient.ExerciseCaps?) -> Unit) =
        launch({ hs.exerciseClient.getCapabilities() }) { r ->
            onResult(
                r.getOrNull()?.let { c ->
                    val type = c.typeToCapabilities[EXERCISE_TYPE]
                    HsClient.ExerciseCaps(
                        heartRate = type != null && DataType.HEART_RATE_BPM in type.supportedDataTypes,
                        batch5s = BatchingMode.HEART_RATE_5_SECONDS in c.supportedBatchingModeOverrides,
                    )
                },
            )
        }

    override fun startExercise(batch5s: Boolean, listener: HsClient.Listener, onStarted: (Throwable?) -> Unit) {
        val exercise = hs.exerciseClient
        dropExerciseCallback() // 시작 실패 · 빼앗김 뒤 남은 이전 콜백을 거둔다(콜백은 한 번에 하나)
        val once = AtomicBoolean(false)
        lateinit var cb: ExerciseUpdateCallback
        fun finish(t: Throwable?) {
            if (!once.compareAndSet(false, true)) return
            if (t != null) dropExerciseCallback(cb)
            onStarted(t)
        }
        cb = object : ExerciseUpdateCallback {
            /** 시작을 요청한 벽시계 시각 — 콜백은 모두 [executor] 한 스레드에서 온다. */
            private var requestedAtMs: Long? = null

            override fun onRegistered() {
                if (once.get()) return
                requestedAtMs = System.currentTimeMillis()
                launch({ exercise.startExercise(config(batch5s)) }) { r -> finish(r.exceptionOrNull()) }
            }

            override fun onRegistrationFailed(throwable: Throwable) = finish(throwable)

            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                if (exerciseCb.get() !== this) return // 이미 거둔 콜백 — 지금 세션 것이 아니다
                if (fromEarlierSession(update.startTime?.toEpochMilli(), requestedAtMs)) return
                deliver(update.latestMetrics, listener)
                val info = update.exerciseStateInfo
                if (info.state.isEnded) {
                    dropExerciseCallback(this) // 더 올 업데이트가 없다(빼앗김 · 권한 상실 · 시스템 종료)
                    listener.onExerciseEnded("${info.state.name}/${info.endReason}")
                }
            }

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
                if (exerciseCb.get() !== this) return
                if (dataType == DataType.HEART_RATE_BPM && availability is DataTypeAvailability) listener.onAvailability(map(availability))
            }
        }
        exerciseCb.set(cb)
        runCatching { exercise.setUpdateCallback(executor, cb) }.onFailure(::finish)
    }

    override fun flushExercise() = launch({ hs.exerciseClient.flush() }) {}

    override fun endExercise(onDone: () -> Unit) {
        val exercise = hs.exerciseClient
        val cb = exerciseCb.get() // 끝내기를 부른 때의 콜백만 — 그사이 새로 등록된 콜백은 둔다
        launch({ exercise.endExercise() }) {
            cb?.let(::dropExerciseCallback)
            onDone()
        }
    }

    /** 등록된 운동 업데이트 콜백을 거둔다(결과는 기다리지 않는다). 없으면 아무것도 안 한다. */
    private fun dropExerciseCallback() {
        exerciseCb.getAndSet(null)?.let(::clear)
    }

    /** [cb] 가 아직 지금 콜백일 때만 거둔다 — 앞 시도의 콜백이 늦게 새 세션의 콜백을 거두지 않게. */
    private fun dropExerciseCallback(cb: ExerciseUpdateCallback) {
        if (exerciseCb.compareAndSet(cb, null)) clear(cb)
    }

    private fun clear(cb: ExerciseUpdateCallback) = launch({ hs.exerciseClient.clearUpdateCallback(cb) }) {}

    override fun startMeasure(listener: HsClient.Listener, onFailed: (Throwable) -> Unit) {
        val cb = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
                if (availability is DataTypeAvailability) listener.onAvailability(map(availability))
            }

            override fun onDataReceived(data: DataPointContainer) = deliver(data, listener)
            override fun onRegistrationFailed(throwable: Throwable) = onFailed(throwable)
        }
        measureCb.set(cb)
        runCatching { hs.measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, executor, cb) }.onFailure(onFailed)
    }

    override fun stopMeasure() {
        // 해제 결과는 기다리지 않는다. 꺼낸 쪽 하나만 해제한다(두 스레드가 함께 불러도 한 번)
        measureCb.getAndSet(null)?.let { cb -> launch({ hs.measureClient.unregisterMeasureCallback(DataType.HEART_RATE_BPM, cb) }) {} }
    }

    private fun deliver(data: DataPointContainer, listener: HsClient.Listener) {
        for (p in data.getData(DataType.HEART_RATE_BPM).sortedBy { it.timeDurationFromBoot }) {
            listener.onBpm(p.value, p.timeDurationFromBoot.toMillis())
        }
    }

    private fun config(batch5s: Boolean): ExerciseConfig = ExerciseConfig.builder(EXERCISE_TYPE)
        .setDataTypes(setOf(DataType.HEART_RATE_BPM))
        .setIsGpsEnabled(false) // 위치는 받지 않는다(G3) — 위치 FGS 유형도 필요 없다
        .setIsAutoPauseAndResumeEnabled(false) // 운전 중 손목이 멈춰도 세션이 멈추면 안 된다
        .apply { if (batch5s) setBatchingModeOverrides(setOf(BatchingMode.HEART_RATE_5_SECONDS)) }
        .build()

    private fun map(a: DataTypeAvailability): HsHeartAdapter.Availability = when (a) {
        DataTypeAvailability.AVAILABLE -> HsHeartAdapter.Availability.AVAILABLE
        DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> HsHeartAdapter.Availability.OFF_BODY
        DataTypeAvailability.ACQUIRING -> HsHeartAdapter.Availability.ACQUIRING
        else -> HsHeartAdapter.Availability.UNAVAILABLE
    }

    private fun <T> launch(block: suspend () -> T, onDone: (Result<T>) -> Unit) {
        block.startCoroutine(Continuation(EmptyCoroutineContext) { r -> onDone(r) })
    }

    companion object {
        /** 운동 종류 — 일반 운동(WORKOUT). 모든 종류가 심박을 주지만(공식 호환성 문서) 거리 · 걸음 같은 부수 센서가 가장 적은 쪽을 고른다. */
        val EXERCISE_TYPE: ExerciseType = ExerciseType.WORKOUT
    }
}

/** 세션 시작 시각이 우리 요청보다 이만큼 넘게 앞서면 앞 세션으로 본다(같은 기기 벽시계 · ms 반올림 여유). 앞 세션은 30초 넘게 앞선다(재시도 간격). */
internal const val EARLIER_SESSION_SLACK_MS = 2_000L

/**
 * 이 업데이트가 우리가 시작을 요청하기 전의 세션 것인가 — 요청 전([requestedAtMs] null)이면 모두 앞 세션 것이고, 요청 뒤에는
 * 세션 시작 시각이 요청보다 [EARLIER_SESSION_SLACK_MS] 넘게 앞설 때만이다. 시작 시각이 없으면(아직 시작 전) 지금 세션으로 본다.
 * 시험이 Health Services 클래스 없이 부를 수 있게 파일 수준에 둔다.
 */
internal fun fromEarlierSession(sessionStartMs: Long?, requestedAtMs: Long?): Boolean =
    requestedAtMs == null || (sessionStartMs != null && sessionStartMs < requestedAtMs - EARLIER_SESSION_SLACK_MS)
