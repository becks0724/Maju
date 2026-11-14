// 차량 원신호(android.car · Pleos Vehicle · NaviHelper 값)를 프로토콜 Vehicle 과 상태 기계 VehicleState 로 바꾸는 순수 함수
package com.example.guardian.car

import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.protocol.Vehicle
import kotlin.math.abs

/**
 * 한 번 읽은 차량 원신호. 못 읽은 값은 null 이다(에뮬레이터에 없는 주차 브레이크 · 에어백 전개 포함).
 * [driverSeated] 는 Pleos 착석 값이다 — 계약 Vehicle 에 칸이 없어 중계로 보내지 않고 화면 · 기록에만 쓴다(에뮬레이터는 콘솔과 반대로 나옴, 카탈로그 A2).
 */
data class CarReading(
    /** PERF_VEHICLE_SPEED 원값(m/s). 후진이면 음수일 수 있다. */
    val speedMps: Float? = null,
    /** GEAR_SELECTION 원값(android.car.VehicleGear). */
    val gearRaw: Int? = null,
    val parkingBrake: Boolean? = null,
    /** IGNITION_STATE 원값(android.car.VehicleIgnitionState). 에뮬레이터 HAL 에는 이 속성이 없어 null 이 정상이다(Prenim 실측). */
    val ignitionRaw: Int? = null,
    val brakePedal: Boolean? = null,
    val airbagDeployed: Boolean? = null,
    val driverSeated: Boolean? = null,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val naviAddress: String? = null,
    val naviLat: Double? = null,
    val naviLon: Double? = null,
    /** 내비 위치를 받은 단조 시각(`SystemClock.elapsedRealtime`). 받은 적 없으면 null. */
    val naviAtMs: Long? = null,
    /** 주행 중 UX 제한(CarUxRestrictions). 매니저를 못 얻었거나 값이 아직 없으면 null — 「제한됨」으로 본다. */
    val ux: UxGate? = null,
)

/**
 * CarUxRestrictions 한 번의 값. android.car 타입을 순수 로직 밖에 두려고 옮겨 담는다(Prenim UxrSnapshot 과 같은 방식).
 * [noSetup] 은 activeRestrictions 에 UX_RESTRICTIONS_NO_SETUP 비트가 있는지다.
 */
data class UxGate(val requiresDo: Boolean, val noSetup: Boolean) {
    companion object {
        /** 경보 버튼 · 응급실 검색 — DO 요구가 없어야 한다. 값이 없으면 제한됨(Pleos 지침 — DO 선언 앱도 직접 적용). */
        fun allowsTouch(ux: UxGate?): Boolean = ux != null && !ux.requiresDo
        /** 짝짓기 · 새 연결 번호 · 권한 요청 창 — NO_SETUP 비트가 없어야 한다. 값이 없으면 제한됨. */
        fun allowsSetup(ux: UxGate?): Boolean = ux != null && !ux.noSetup
    }
}

object VehicleMapping {
    // android.car.VehicleGear 값(공개 상수와 같음). 단위 시험이 android.car 없이 돌게 숫자로 둔다
    const val GEAR_UNKNOWN = 0x0000
    const val GEAR_NEUTRAL = 0x0001
    const val GEAR_REVERSE = 0x0002
    const val GEAR_PARK = 0x0004
    const val GEAR_DRIVE = 0x0008
    /** GEAR_1(0x0010) ~ GEAR_9(0x1000) — 수동 단수는 주행(D)으로 본다. */
    private val FORWARD_GEARS = (0..8).map { 0x0010 shl it }.toSet()

    /** null = 읽기 전, UNKNOWN = 읽었으나 해석 불가(계약 §4.2). */
    fun gear(raw: Int?): Gear? = when (raw ?: return null) {
        GEAR_PARK -> Gear.P
        GEAR_REVERSE -> Gear.R
        GEAR_NEUTRAL -> Gear.N
        GEAR_DRIVE, in FORWARD_GEARS -> Gear.D
        else -> Gear.UNKNOWN
    }

    // android.car.VehicleIgnitionState 값(공개 상수와 같음) — 기어와 같은 이유로 숫자로 둔다
    const val IGNITION_UNDEFINED = 0
    const val IGNITION_LOCK = 1
    const val IGNITION_OFF = 2
    const val IGNITION_ACC = 3
    const val IGNITION_ON = 4
    const val IGNITION_START = 5

    /**
     * 시동 켜짐(true) · 꺼짐(false) · 모름(null) — 프로토콜 `Vehicle.ignitionOn` 의 뜻(be-dev 와 맞춤, 게시판 11-14).
     * ACC(엔진 꺼짐 · 전장만)는 꺼짐이다(사용자 결정 2026-11-14). UNDEFINED · 모르는 값 · 읽기 전은 모름.
     * 전기차의 「주행 가능」이 ON 으로 오는지는 [미검증].
     */
    fun ignitionOn(raw: Int?): Boolean? = when (raw) {
        IGNITION_ON, IGNITION_START -> true
        IGNITION_LOCK, IGNITION_OFF, IGNITION_ACC -> false
        else -> null
    }

    /** 내비 위치는 2초 주기의 5번에 한 번(10초) 묻는다 [추정]. */
    const val NAVI_POLL_EVERY = 5

    /** 이번 읽기에서 내비 위치를 물을지 — 위치 제3자 제공 동의가 있을 때만(G2 최소 수집 · 리뷰 하3). */
    fun naviPollDue(readIndex: Int, locationConsent: Boolean): Boolean = locationConsent && readIndex % NAVI_POLL_EVERY == 0

    /** m/s → km/h. 방향은 버린다(후진 음수). 유한하지 않으면 못 읽은 것으로 본다. */
    fun speedKmh(mps: Float?): Double? = mps?.takeIf { it.isFinite() }?.let { abs(it.toDouble()) * 3.6 }

    /**
     * 프로토콜 Vehicle. 위치(내비 주소 · 좌표)는 위치 제3자 제공 동의가 있을 때만 싣는다(G3 · 계약 §8).
     * 좌표가 유한한 수가 아니면 좌표를 싣지 않는다.
     * 내비 위치를 받은 시각은 [serverOffsetMs](시계 보정값)로 중계 시각으로 바꿔 `naviAt` 에 싣는다 — 좌표를 실을 때만, 보정값이 없으면 null(공통 하4).
     */
    fun toProtocol(r: CarReading, locationConsent: Boolean, serverOffsetMs: Long? = null): Vehicle {
        val coordOk = r.naviLat?.isFinite() == true && r.naviLon?.isFinite() == true
        val naviAt = if (locationConsent && coordOk && serverOffsetMs != null) r.naviAtMs?.plus(serverOffsetMs) else null
        return Vehicle(
            speedKmh = speedKmh(r.speedMps),
            gear = gear(r.gearRaw),
            parkingBrake = r.parkingBrake,
            brakePedal = r.brakePedal,
            airbagDeployed = r.airbagDeployed,
            make = r.make?.takeIf { it.isNotBlank() },
            model = r.model?.takeIf { it.isNotBlank() },
            year = r.year?.takeIf { it > 0 },
            naviAddress = r.naviAddress?.takeIf { locationConsent && it.isNotBlank() },
            naviLat = r.naviLat?.takeIf { locationConsent && coordOk },
            naviLon = r.naviLon?.takeIf { locationConsent && coordOk },
            naviAt = naviAt,
            ignitionOn = ignitionOn(r.ignitionRaw),
        )
    }

    /** 상태 기계 입력. 정지 판정(속도 ≤ 3 이고 기어 P, 속도 모르면 기어 P 또는 시동 꺼짐)은 :shared 몫이다(B-4). */
    fun toFlow(v: Vehicle): VehicleState = VehicleState(
        speedKmh = v.speedKmh,
        gear = v.gear,
        parkingBrake = v.parkingBrake,
        airbagDeployed = v.airbagDeployed,
        ignitionOn = v.ignitionOn,
    )

    /**
     * 짝짓기 화면용 정차(상태 기계 판정 아님). 속도를 읽으면 :shared 규칙 그대로,
     * 속도 권한을 받기 전(속도 null)에는 기어 P 만으로 본다 — 속도 권한 요청 창을 정차 중에만 띄우려는 것.
     */
    fun parkedForSetup(v: Vehicle): Boolean =
        if (v.speedKmh != null) toFlow(v).stopped() else v.gear == Gear.P
}
