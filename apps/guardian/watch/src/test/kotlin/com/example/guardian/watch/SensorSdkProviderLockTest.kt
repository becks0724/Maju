// 감시 잠금 시험 — AAR 유무와 관계없이 SensorSdkProvider 가 null · AVAILABLE=false 라 감시가 Health Services 로 도는지 본다(계획 11-07 핵심 결정 2)
package com.example.guardian.watch

import android.content.ContextWrapper
import com.example.guardian.watch.sensor.SensorSdkProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AAR 이 있는 빌드에서는 src/sensorSdk 의 공급자(잠금 상수 `MONITOR_READY = false`)를, 없는 빌드에서는 src/noSensorSdk 의 빈 공급자를 시험한다.
 * 어느 쪽이든 결과가 같아야 감시 경로가 AAR 배치로 바뀌지 않는다. 잠금을 풀 때(사용자 결정) 이 시험의 기대값도 함께 바꾼다.
 */
class SensorSdkProviderLockTest {
    @Test fun monitorDoesNotGetSensorSdkWhileLocked() {
        assertFalse("SensorSdkProvider.AVAILABLE 은 잠금 동안 false", SensorSdkProvider.AVAILABLE)
        var capabilities: List<String>? = null
        // 잠금 동안 Context 는 쓰이지 않는다 — 스텁 생성자(unitTests.isReturnDefaultValues)로 빈 래퍼를 넘긴다
        val source = SensorSdkProvider.create(ContextWrapper(null), FakeClocks()) { capabilities = it }
        assertNull("잠금 동안 create 는 null(Health Services 로 간다)", source)
        assertNull("출처를 만들지 않았으니 지원 트래커 콜백도 없다", capabilities)
    }
}
