// 차량 원신호 → 프로토콜 Vehicle · 상태 기계 VehicleState 매핑 시험(기어 · 속도 단위 · 위치 동의 · 못 읽은 값 null)
package com.example.guardian.car

import com.example.guardian.shared.flow.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleMappingTest {
    @Test fun gearSelectionMapsToProtocolGear() {
        assertNull("읽기 전", VehicleMapping.gear(null))
        assertEquals(Gear.P, VehicleMapping.gear(0x0004))
        assertEquals(Gear.R, VehicleMapping.gear(0x0002))
        assertEquals(Gear.N, VehicleMapping.gear(0x0001))
        assertEquals(Gear.D, VehicleMapping.gear(0x0008))
        assertEquals("수동 1단", Gear.D, VehicleMapping.gear(0x0010))
        assertEquals("수동 9단", Gear.D, VehicleMapping.gear(0x1000))
        assertEquals("GEAR_UNKNOWN", Gear.UNKNOWN, VehicleMapping.gear(0))
        assertEquals("해석 불가", Gear.UNKNOWN, VehicleMapping.gear(0x3))
    }

    /** 리뷰 하3 — 위치 제3자 제공 동의가 없으면 내비 위치를 묻지도 않는다(10초마다 한 번은 동의가 있을 때만). */
    @Test fun naviLocationIsPolledOnlyWithLocationConsent() {
        for (i in 0 until 20) assertFalse("동의 없음 $i", VehicleMapping.naviPollDue(i, locationConsent = false))
        assertEquals(listOf(0, 5, 10, 15), (0 until 20).filter { VehicleMapping.naviPollDue(it, locationConsent = true) })
    }

    @Test fun speedIsKmhAndDirectionless() {
        assertEquals(36.0, VehicleMapping.speedKmh(10f)!!, 1e-4)
        assertEquals("후진 음수", 18.0, VehicleMapping.speedKmh(-5f)!!, 1e-4)
        assertNull(VehicleMapping.speedKmh(null))
        assertNull(VehicleMapping.speedKmh(Float.NaN))
    }

    @Test fun missingSignalsStayNull() {
        val v = VehicleMapping.toProtocol(CarReading(speedMps = 0f, gearRaw = 4), locationConsent = true)
        assertNull("에뮬레이터 주차 브레이크 없음", v.parkingBrake)
        assertNull("에뮬레이터 에어백 미지원", v.airbagDeployed)
        assertNull(v.make); assertNull(v.year)
    }

    @Test fun naviLocationOnlyWithLocationConsent() {
        val r = CarReading(naviAddress = "서울 종로구 세종대로", naviLat = 37.45, naviLon = 126.89, make = "Hyundai", year = 2026)
        val with = VehicleMapping.toProtocol(r, locationConsent = true)
        assertEquals("서울 종로구 세종대로", with.naviAddress)
        assertEquals(37.45, with.naviLat!!, 0.0)
        val without = VehicleMapping.toProtocol(r, locationConsent = false)
        assertNull(without.naviAddress); assertNull(without.naviLat); assertNull(without.naviLon)
        assertEquals("차종은 위치가 아님", "Hyundai", without.make)
        val nan = VehicleMapping.toProtocol(r.copy(naviLat = Double.NaN), locationConsent = true)
        assertNull("유한하지 않은 좌표", nan.naviLat); assertNull(nan.naviLon)
    }

    /** 공통 하4 — 내비 위치를 받은 단조 시각을 시계 보정값으로 중계 시각(naviAt)으로 바꿔 싣는다. 좌표를 싣지 않거나 보정값이 없으면 null. */
    @Test fun naviAtIsServerTimeOfTheFix() {
        val r = CarReading(naviAddress = "서울", naviLat = 37.45, naviLon = 126.89, naviAtMs = 10_000)
        assertEquals(1_010_000L, VehicleMapping.toProtocol(r, true, serverOffsetMs = 1_000_000).naviAt)
        assertNull("보정값 없음", VehicleMapping.toProtocol(r, true, null).naviAt)
        assertNull("동의 없음", VehicleMapping.toProtocol(r, false, 1_000_000).naviAt)
        assertNull("좌표 없음", VehicleMapping.toProtocol(r.copy(naviLat = null), true, 1_000_000).naviAt)
        assertNull("받은 적 없음", VehicleMapping.toProtocol(r.copy(naviAtMs = null), true, 1_000_000).naviAt)
    }

    @Test fun flowInputUsesSharedStopRule() {
        val parked = VehicleMapping.toFlow(VehicleMapping.toProtocol(PARKED, false))
        assertTrue(parked.stopped())
        assertFalse(VehicleMapping.toFlow(VehicleMapping.toProtocol(MOVING, false)).stopped())
        // D 단에서 속도 0 — 정지 아님(기어 P 필요)
        assertFalse(VehicleMapping.toFlow(VehicleMapping.toProtocol(CarReading(0f, 0x0008), false)).stopped())
        // 속도를 못 읽어도 기어 P 면 정지(합동 점검 B-4), 기어 D 면 정지로 읽지 않는다
        assertTrue(VehicleMapping.toFlow(VehicleMapping.toProtocol(CarReading(null, 0x0004), false)).stopped())
        assertFalse(VehicleMapping.toFlow(VehicleMapping.toProtocol(CarReading(null, 0x0008), false)).stopped())
    }

    @Test fun setupParkedFallsBackToGearWithoutSpeedPermission() {
        assertTrue(VehicleMapping.parkedForSetup(VehicleMapping.toProtocol(CarReading(null, 0x0004), false)))
        assertFalse(VehicleMapping.parkedForSetup(VehicleMapping.toProtocol(CarReading(null, 0x0008), false)))
        assertFalse("속도가 있으면 공유 규칙", VehicleMapping.parkedForSetup(VehicleMapping.toProtocol(CarReading(10f, 0x0004), false)))
    }

    /** B-4 차 쪽 — IGNITION_STATE 원값 → 시동 켜짐/꺼짐/모름. ACC 는 꺼짐(사용자 결정 2026-11-14). */
    @Test fun ignitionStateMapsToOnOffUnknown() {
        assertNull("읽기 전", VehicleMapping.ignitionOn(null))
        assertNull("UNDEFINED", VehicleMapping.ignitionOn(0))
        assertEquals("LOCK", false, VehicleMapping.ignitionOn(1))
        assertEquals("OFF", false, VehicleMapping.ignitionOn(2))
        assertEquals("ACC", false, VehicleMapping.ignitionOn(3))
        assertEquals("ON", true, VehicleMapping.ignitionOn(4))
        assertEquals("START", true, VehicleMapping.ignitionOn(5))
        assertNull("모르는 값", VehicleMapping.ignitionOn(6))
        assertNull("음수", VehicleMapping.ignitionOn(-1))
    }

    @Test fun ignitionRawIsCarriedInReading() {
        assertNull("기본은 못 읽음", CarReading().ignitionRaw)
        assertEquals(4, CarReading(ignitionRaw = 4).ignitionRaw)
    }

    /** B-4 — 시동 매핑 결과를 프로토콜 Vehicle 과 상태 기계 입력에 싣는다(be-dev 9e857c0 의 ignitionOn). */
    @Test fun ignitionIsCarriedToProtocolAndFlow() {
        val off = VehicleMapping.toProtocol(CarReading(ignitionRaw = 3), locationConsent = false)
        assertEquals("ACC 는 꺼짐", false, off.ignitionOn)
        assertEquals(false, VehicleMapping.toFlow(off).ignitionOn)
        assertEquals(true, VehicleMapping.toProtocol(CarReading(ignitionRaw = 4), locationConsent = false).ignitionOn)
        assertNull("에뮬레이터 — 속성 없음", VehicleMapping.toProtocol(CarReading(), locationConsent = false).ignitionOn)
        assertTrue("속도 모름 + 시동 꺼짐 = 정지 후보", VehicleMapping.toFlow(off).stopped())
    }
}
