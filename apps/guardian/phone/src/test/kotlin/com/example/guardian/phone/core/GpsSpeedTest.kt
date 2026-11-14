// GPS 속도 정확도 거르기 시험 — 정확도 없음 · 좋음 · 나쁨 + 확실히 움직임 · 나쁨 + 애매함 · 하한 경계
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.SpeedSource
import com.example.guardian.shared.flow.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GpsSpeedTest {
    private fun kmh(v: Double?) = v!!

    @Test fun noSpeedIsNull() {
        assertNull(GpsSpeed.usableKmh(null, null))
        assertNull(GpsSpeed.usableKmh(null, 0.3))
    }

    @Test fun noAccuracyKeepsSpeedAsBefore() {
        assertEquals(0.36, kmh(GpsSpeed.usableKmh(0.1, null)), 1e-9)
        assertEquals(36.0, kmh(GpsSpeed.usableKmh(10.0, null)), 1e-9)
    }

    @Test fun goodAccuracyKeepsSpeed() {
        assertEquals(0.36, kmh(GpsSpeed.usableKmh(0.1, 0.5)), 1e-9)
        // 경계 — 기준과 같으면 좋음
        assertEquals(0.72, kmh(GpsSpeed.usableKmh(0.2, GpsSpeed.SPEED_ACC_MAX_MPS)), 1e-9)
    }

    @Test fun badAccuracyButSurelyMovingKeepsSpeed() {
        // 15 m/s ± 2 m/s → 13 m/s(46.8 km/h) > 1 km/h
        assertEquals(54.0, kmh(GpsSpeed.usableKmh(15.0, 2.0)), 1e-9)
    }

    @Test fun badAccuracyAndAmbiguousIsNull() {
        // 0.2 m/s ± 1.5 m/s — 정지일 수도 움직일 수도 있다
        assertNull(GpsSpeed.usableKmh(0.2, 1.5))
        // 2 m/s ± 1.8 m/s → 하한 0.72 km/h ≤ 1 km/h
        assertNull(GpsSpeed.usableKmh(2.0, 1.8))
        // 오차 큰 0 은 정차 증거로 쓰지 않는다 — stopped 쪽에서는 「속도 모름 = 주행」
        assertFalse(VehicleState(speedKmh = GpsSpeed.usableKmh(0.0, 3.0), speedSource = SpeedSource.PHONE_GPS).stopped())
    }

    /** 정확도 나쁨에서 「속도 − 오차」가 정지 기준(GPS_STOP_KMH = 1.0 km/h) 바로 위 · 같음 · 바로 아래 — `>` 가 `>=` 로 바뀌면 같음에서 잡힌다. */
    @Test fun badAccuracyLowerBoundEdge() {
        assertEquals(1.0, VehicleState.GPS_STOP_KMH, 0.0)
        // 1.5 m/s ± 1.2 m/s → 5.4 − 4.32 = 1.08 km/h > 1.0 → 쓴다
        assertEquals(5.4, kmh(GpsSpeed.usableKmh(1.5, 1.2)), 1e-9)
        // 2.5 m/s ± (2.5 − 1/3.6) m/s → 9.0 − 8.0 = 정확히 1.0 km/h → 쓰지 않는다(확실히 움직임이 아니다)
        assertNull(GpsSpeed.usableKmh(2.5, 2.5 - 1 / 3.6))
        // 1.5 m/s ± 1.25 m/s → 5.4 − 4.5 = 0.9 km/h ≤ 1.0 → 쓰지 않는다
        assertNull(GpsSpeed.usableKmh(1.5, 1.25))
    }
}
