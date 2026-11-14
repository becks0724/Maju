// 삼성 Health Sensor SDK 주 출처 — PPG(녹 · 적외 · 적) 25Hz · 가속도 25Hz · 심박+IBI 1Hz 연속 트래커를 관문 샘플로 옮긴다
package com.example.guardian.watch.sensor

import android.content.Context
import android.util.Log
import com.example.guardian.shared.bio.AccelSample
import com.example.guardian.shared.bio.HrSample
import com.example.guardian.shared.bio.PpgSample
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.watch.core.Clocks
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey

/**
 * 이 파일은 AAR 이 있을 때만 컴파일된다. 2026-11-07 AAR v1.4.1 로 컴파일을 확인했다(API 이름 · 서명은 javap 와 일치, 고친 곳 없음).
 * 실기기 동작(연결 · 값 도착 · 상태값의 뜻 · 가속도 환산식)은 [미검증] — 감시는 `SensorSdkProvider.MONITOR_READY` 잠금으로 이 출처를
 * 쓰지 않고, debug 「생체 기록」(probe/BioProbe*)으로 모은 원자료를 재생해 본 뒤 사용자 결정으로 푼다(계획 11-07).
 * 근거 — Sensor SDK API 참조(HealthTrackingService · HealthTracker · TrackerEventListener · ValueKey · HealthTrackerType)와
 * data-specifications(S0 조사 2절).
 * 잠금을 풀기 전에 닫을 것 — `DataPoint.getValue` 는 키가 없으면 null 이 아니라 NPE 를 던진다(PM javap -c 확인 11-07). 아래 `getValue` 호출은
 * 감싸지 않았으므로 debug 기록기(probe/SamsungBioRecorder · SdkFieldReader)처럼 필드별로 감싸야 한다(PM 결정 — 이번 범위 밖).
 *
 * - 연결되면 `getTrackingCapability().getSupportHealthTrackerTypes()` 를 [onCapabilities] 로 넘긴다(첫 실행 기록).
 * - 세 트래커 중 하나라도 지원하지 않으면 실패로 알려 Health Services 로 바꾸게 한다.
 * - 샘플 시각은 SDK 타임스탬프(에포크 ms)를 받은 순간의 시계 차로 단조 시각으로 바꾼다 — 화면이 꺼져 묶음으로 와도 원래 시각을 지킨다.
 * - 원신호는 [SampleSink](관문)로만 간다. 네트워크로 보내지 않는다(G2).
 */
class SamsungSensorSource(
    private val context: Context,
    private val clocks: Clocks,
    private val onCapabilities: (List<String>) -> Unit,
) : SensorSource {
    override val kind = WatchSource.SENSOR_SDK
    override val lowConfidence = false

    private var service: HealthTrackingService? = null
    private val trackers = mutableListOf<HealthTracker>()

    override fun start(sink: SampleSink, onFailure: (String) -> Unit) {
        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() = setUp(sink, onFailure)
            override fun onConnectionEnded() { Log.i(TAG, "Sensor SDK 연결 끝남") }
            override fun onConnectionFailed(e: HealthTrackerException) = onFailure("Sensor SDK 연결 실패 ${e.errorCode}")
        }
        service = HealthTrackingService(listener, context.applicationContext).also { it.connectService() }
    }

    private fun setUp(sink: SampleSink, onFailure: (String) -> Unit) {
        val svc = service ?: return
        val types = svc.trackingCapability.supportHealthTrackerTypes
        Log.i(TAG, "Sensor SDK 지원 트래커 $types")
        onCapabilities(types.map { it.name })
        val need = listOf(HealthTrackerType.PPG_CONTINUOUS, HealthTrackerType.ACCELEROMETER_CONTINUOUS, HealthTrackerType.HEART_RATE_CONTINUOUS)
        val missing = need.filter { it !in types }
        if (missing.isNotEmpty()) {
            onFailure("Sensor SDK 미지원 $missing")
            return
        }
        val ppg = svc.getHealthTracker(HealthTrackerType.PPG_CONTINUOUS, setOf(PpgType.GREEN, PpgType.IR, PpgType.RED))
        ppg.setEventListener(listener(onFailure) { p ->
            sink.ppg(
                PpgSample(
                    atMs = mono(p),
                    green = p.getValue(ValueKey.PpgSet.PPG_GREEN).toDouble(),
                    ir = p.getValue(ValueKey.PpgSet.PPG_IR).toDouble(),
                    red = p.getValue(ValueKey.PpgSet.PPG_RED).toDouble(),
                    status = p.getValue(ValueKey.PpgSet.GREEN_STATUS),
                ),
            )
        })
        val acc = svc.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
        acc.setEventListener(listener(onFailure) { p ->
            sink.accel(
                AccelSample(
                    atMs = mono(p),
                    x = toMs2(p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X)),
                    y = toMs2(p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y)),
                    z = toMs2(p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z)),
                ),
            )
        })
        val hr = svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
        hr.setEventListener(listener(onFailure) { p ->
            val ibi = p.getValue(ValueKey.HeartRateSet.IBI_LIST) ?: emptyList()
            val ibiStatus = p.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) ?: emptyList()
            // IBI 상태 0 = 유효로 본다 [미검증 — 데이터 사양 대조]
            val valid = ibi.filterIndexed { i, _ -> ibiStatus.getOrNull(i) == 0 }
            sink.hr(HrSample(mono(p), p.getValue(ValueKey.HeartRateSet.HEART_RATE), p.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS), valid))
        })
        trackers += listOf(ppg, acc, hr)
    }

    private fun listener(onFailure: (String) -> Unit, each: (DataPoint) -> Unit) = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: List<DataPoint>) = points.forEach(each)
        override fun onFlushCompleted() = Unit
        override fun onError(error: HealthTracker.TrackerError) = onFailure("Sensor SDK 측정 오류 $error")
    }

    private fun mono(p: DataPoint): Long = clocks.toMono(p.timestamp)

    /** 원시 정수 → m/s². 데이터 사양의 환산식(값 × 9.81 / (16383.75 / 4)) [미검증 — 원문 대조]. */
    private fun toMs2(v: Int): Double = v * 9.81 / (16383.75 / 4.0)

    /** 화면이 꺼져 묶음으로 쌓인 값을 바로 받는다 — 판정 중에는 2초마다 부른다(설계 §3). */
    override fun flush() {
        trackers.forEach { it.flush() }
    }

    override fun stop() {
        trackers.forEach { it.unsetEventListener() }
        trackers.clear()
        service?.disconnectService()
        service = null
    }

    private companion object {
        const val TAG = "GuardianWatch"
    }
}
