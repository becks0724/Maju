// debug 전용 생체 기록 — 삼성 Sensor SDK 의 PPG(녹 · 적외 · 적) · 가속도 · 심박+IBI 연속 트래커 원시값을 CSV 줄로 넘긴다(환산 · 거름 없음)
package com.example.guardian.watch.probe

import android.content.Context
import android.os.SystemClock
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AAR v1.4.1 로 컴파일했다(2026-11-07). 실기기 동작(Wear OS 7 · 연결 · 값 도착)은 [미검증] — 계획 단계 4 에서 처음 확인한다.
 * - 연결되면 지원 트래커 목록을 meta · events 에 남기고, 세 연속 트래커 중 지원하는 것만 켠다(하나가 없어도 나머지는 기록).
 * - 샘플 시각은 SDK 타임스탬프(에포크 ms)를 그대로 `sample_epoch_ms` 에, 받은 순간의 단조 시각을 `recv_ms` 에 쓴다.
 * - IBI 는 상태 목록과 함께 전부 남긴다. 상태값의 뜻은 재생 분석에서 대조한다.
 * - 운전 중에는 아무도 만질 수 없으므로 자료가 조용히 사라지는 길을 닫는다 — 필드는 [SdkFieldReader] 로 하나씩 읽고(키 없음 → 그 칸만 빈칸),
 *   점마다 예외를 감싸 묶음의 다음 점을 살리며, [setUp] 전체 실패는 `sdk_setup_failed` 사건 · 화면 상태로 알린다.
 */
class SamsungBioRecorder(
    private val context: Context,
    private val writer: BioProbeWriter,
    private val listener: SdkRecorder.Listener,
) : SdkRecorder {
    private var service: HealthTrackingService? = null
    /** SDK 콜백 스레드와 서비스의 flush · stop 이 함께 닿는다. */
    private val trackers = CopyOnWriteArrayList<HealthTracker>()
    private val once = OnceEvents(listener::event)
    private val fields = SdkFieldReader(once)

    override fun start() {
        listener.sdkStatus("Sensor SDK 연결 중")
        val conn = object : ConnectionListener {
            override fun onConnectionSuccess() = setUp()
            override fun onConnectionEnded() {
                listener.event("sdk_connection_ended", "")
                listener.sdkStatus("Sensor SDK 연결 끝남")
            }
            override fun onConnectionFailed(e: HealthTrackerException) {
                listener.event("sdk_connection_failed", "code=${e.errorCode} resolution=${e.hasResolution()} ${e.message ?: ""}")
                listener.sdkStatus("Sensor SDK 연결 실패 ${e.errorCode}")
            }
        }
        service = HealthTrackingService(conn, context.applicationContext).also { it.connectService() }
    }

    private fun setUp() {
        try {
            setUpTrackers()
        } catch (e: RuntimeException) {
            listener.event("sdk_setup_failed", e.javaClass.simpleName)
            listener.sdkStatus("Sensor SDK 설정 실패 ${e.javaClass.simpleName}")
        }
    }

    private fun setUpTrackers() {
        val svc = service ?: return
        val cap = svc.trackingCapability
        val types = cap.supportHealthTrackerTypes
        listener.meta("sdk_version=${cap.version}")
        listener.meta("sdk_trackers=${types.joinToString("|") { it.name }}")
        listener.event("sdk_connected", "trackers=${types.size}")

        val plan: List<Triple<HealthTrackerType, () -> HealthTracker, (DataPoint) -> Unit>> = listOf(
            Triple(HealthTrackerType.PPG_CONTINUOUS, { svc.getHealthTracker(HealthTrackerType.PPG_CONTINUOUS, setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)) }, ::ppgRow),
            Triple(HealthTrackerType.ACCELEROMETER_CONTINUOUS, { svc.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS) }, ::accRow),
            Triple(HealthTrackerType.HEART_RATE_CONTINUOUS, { svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS) }, ::hrRow),
        )
        var on = 0
        for ((type, make, each) in plan) {
            if (type !in types) {
                listener.event("sdk_unsupported", type.name)
                continue
            }
            on += open(type, make, each)
        }
        listener.sdkStatus("Sensor SDK 트래커 ${on}개 켬 / 지원 ${types.size}개")
    }

    private fun ppgRow(p: DataPoint) {
        writer.append(
            BioStream.SDK_PPG,
            BioProbeCsv.sdkPpg(
                SystemClock.elapsedRealtime(), p.timestamp,
                fields.read("PpgSet.PPG_GREEN") { p.getValue(ValueKey.PpgSet.PPG_GREEN) },
                fields.read("PpgSet.PPG_IR") { p.getValue(ValueKey.PpgSet.PPG_IR) },
                fields.read("PpgSet.PPG_RED") { p.getValue(ValueKey.PpgSet.PPG_RED) },
                fields.read("PpgSet.GREEN_STATUS") { p.getValue(ValueKey.PpgSet.GREEN_STATUS) },
                fields.read("PpgSet.IR_STATUS") { p.getValue(ValueKey.PpgSet.IR_STATUS) },
                fields.read("PpgSet.RED_STATUS") { p.getValue(ValueKey.PpgSet.RED_STATUS) },
            ),
        )
    }

    private fun accRow(p: DataPoint) {
        writer.append(
            BioStream.SDK_ACC,
            BioProbeCsv.sdkAcc(
                SystemClock.elapsedRealtime(), p.timestamp,
                fields.read("AccelerometerSet.ACCELEROMETER_X") { p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X) },
                fields.read("AccelerometerSet.ACCELEROMETER_Y") { p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y) },
                fields.read("AccelerometerSet.ACCELEROMETER_Z") { p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z) },
            ),
        )
    }

    private fun hrRow(p: DataPoint) {
        val hr: Int? = fields.read("HeartRateSet.HEART_RATE") { p.getValue(ValueKey.HeartRateSet.HEART_RATE) }
        val status: Int? = fields.read("HeartRateSet.HEART_RATE_STATUS") { p.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) }
        writer.append(
            BioStream.SDK_HR,
            BioProbeCsv.sdkHr(
                SystemClock.elapsedRealtime(), p.timestamp, hr, status,
                fields.read("HeartRateSet.IBI_LIST") { p.getValue(ValueKey.HeartRateSet.IBI_LIST) },
                fields.read("HeartRateSet.IBI_STATUS_LIST") { p.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) },
            ),
        )
        listener.hr(hr, status)
    }

    /** 트래커 하나를 만들고 리스너를 건다. 성공하면 1, 실패(예외)하면 0 — 사건으로 남긴다. 점마다 예외를 감싸 묶음의 다음 점을 버리지 않는다. */
    private fun open(type: HealthTrackerType, make: () -> HealthTracker, each: (DataPoint) -> Unit): Int {
        val t = try { make() } catch (e: RuntimeException) {
            listener.event("sdk_tracker_failed", "${type.name} ${e.javaClass.simpleName}")
            return 0
        }
        t.setEventListener(object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(points: List<DataPoint>) {
                for (p in points) {
                    try { each(p) } catch (e: RuntimeException) { once.once("sdk_point_error", "${type.name} ${e.javaClass.simpleName}") }
                }
            }
            override fun onFlushCompleted() = Unit
            override fun onError(error: HealthTracker.TrackerError) = listener.event("sdk_error", "${type.name} $error")
        })
        trackers += t
        listener.event("sdk_tracker_on", type.name)
        return 1
    }

    /** 2초마다 — 화면이 꺼져 묶음으로 쌓인 값을 바로 받는다(감시와 같은 주기, 설계 §3). */
    override fun flush() {
        trackers.forEach { runCatching { it.flush() } }
    }

    override fun stop() {
        trackers.forEach { runCatching { it.unsetEventListener() } }
        trackers.clear()
        runCatching { service?.disconnectService() }
        service = null
    }
}
