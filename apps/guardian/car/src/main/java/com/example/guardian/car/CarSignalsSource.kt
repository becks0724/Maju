// 차량 원신호 수집 — android.car(속도 · 기어 · 주차 브레이크 · 시동 · 차종), Pleos Vehicle(브레이크 페달 · 에어백 전개 · 착석), NaviHelper(현재 위치 · 검색 화면)
package com.example.guardian.car

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * [read] 는 `guardian-tick` 스레드에서 2초마다(그리고 버튼을 누른 순간) 부른다. android.car 속성은 그 자리에서 동기로 읽고,
 * Pleos 값은 메인 스레드에서 비동기로 다시 물어 다음 [read] 에 반영한다(Vehicle SDK 는 연결 콜백이 없다 — 실측 노트).
 * 못 읽은 값은 null 이다. 에뮬레이터는 주차 브레이크 · 에어백 전개가 없어 null 이 정상이다(설계 §12).
 * 주행 중 UX 제한(CarUxRestrictions)도 여기서 받아 [CarReading.ux] 로 넘긴다 — DO 를 선언했어도 앱이 직접 적용한다(Pleos 지침).
 * 받은 위치는 메모리에만 두고 저장하지 않는다(G2 · G3).
 */
class CarSignalsSource(context: Context, private val onUxChanged: (UxGate?) -> Unit = {}) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    @Volatile private var props: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var uxSession = 0
    /** 마지막 UX 제한 값. 매니저를 못 얻었거나 값이 오기 전이면 null(제한됨). */
    @Volatile private var ux: UxGate? = null
    private var car: Car? = null
    private val vehicle: Vehicle? = runCatching { Vehicle(app).also { it.initialize() } }
        .onFailure { Log.w(TAG, "Vehicle 초기화 실패 ${it.javaClass.simpleName}") }.getOrNull()
    private val navi: NaviHelper? = runCatching { NaviHelper(app) }.getOrNull()
    @Volatile private var naviReady = false

    @Volatile private var brakePedal: Boolean? = null
    @Volatile private var airbagDeployed: Boolean? = null
    @Volatile private var driverSeated: Boolean? = null
    @Volatile private var naviAddress: String? = null
    @Volatile private var naviLat: Double? = null
    @Volatile private var naviLon: Double? = null
    /** 내비 위치를 받은 단조 시각 — SDK 값에 측정 시각이 있는지는 [미검증]이라 받은 순간을 쓴다. */
    @Volatile private var naviAtMs: Long? = null
    @Volatile private var make: String? = null
    @Volatile private var model: String? = null
    @Volatile private var year: Int? = null
    @Volatile private var infoRead = false
    private var reads = 0
    /** Pleos 능력 확인 결과(F-2) — 운전석이 없거나 오류면 그 질의를 멈추고, 실패면 10분마다 다시 확인한다. */
    private val caps = CapabilityGate()

    /**
     * 위치 제3자 제공 동의(휴대폰이 정한 값, 허브 스냅숏) — 주기 스레드가 [read] 전에 넣는다. 거짓이면 내비 위치를 묻지 않고,
     * 들고 있던 값도 지우며, 늦게 온 응답도 버린다(G2 최소 수집 · 리뷰 하3).
     */
    @Volatile var locationConsent = false

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            if (!locationConsent) return
            naviLat = currentLocationInfo.latitude
            naviLon = currentLocationInfo.longitude
            naviAddress = currentLocationInfo.address
            naviAtMs = SystemClock.elapsedRealtime()
        }
    }

    init {
        main.post {
            runCatching {
                car = Car.createCar(app, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready -> onCarLifecycle(c, ready) }
            }.onFailure { Log.w(TAG, "android.car 연결 실패 ${it.javaClass.simpleName}") }
            navi?.let { n ->
                runCatching {
                    n.initialize(
                        { n.addListener(naviListener); naviReady = true },
                        { e -> Log.w(TAG, "NaviHelper 연결 실패 ${e.javaClass.simpleName}") },
                    )
                }
            }
        }
    }

    fun read(): CarReading {
        val p = props
        if (p != null && !infoRead) {
            make = p.value<String>(VehiclePropertyIds.INFO_MAKE)
            model = p.value<String>(VehiclePropertyIds.INFO_MODEL)
            year = p.value<Int>(VehiclePropertyIds.INFO_MODEL_YEAR)
            infoRead = true
        }
        val speed = if (p != null && speedGranted()) p.value<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED) else null
        val gear = p?.value<Int>(VehiclePropertyIds.GEAR_SELECTION)
        val parkingBrake = p?.value<Boolean>(VehiclePropertyIds.PARKING_BRAKE_ON)
        // 시동 — CAR_POWERTRAIN(normal) 권한, 기어와 같은 방식(「사용 가능」일 때만). CarPowerManager 는 privileged 라 쓰지 않는다
        val ignition = p?.value<Int>(VehiclePropertyIds.IGNITION_STATE)
        val consent = locationConsent
        if (!consent) { naviLat = null; naviLon = null; naviAddress = null; naviAtMs = null }
        askPleos(VehicleMapping.naviPollDue(reads++, consent))
        return CarReading(
            speedMps = speed, gearRaw = gear, parkingBrake = parkingBrake, ignitionRaw = ignition,
            brakePedal = brakePedal, airbagDeployed = airbagDeployed, driverSeated = driverSeated,
            make = make, model = model, year = year,
            naviAddress = naviAddress, naviLat = naviLat, naviLon = naviLon, naviAtMs = naviAtMs, ux = ux,
        )
    }

    /**
     * car 서비스 연결 · 끊김(메인 스레드). 연결되면 속성 매니저와 UX 제한 매니저를 얻고 리스너 + 현재 값을 받는다.
     * 끊기면 매니저 · 값을 모두 비운다 — 옛 값으로 정차를 열지 않는다(Prenim CarSignals 하-5).
     * 옛 매니저의 늦은 콜백은 세대 번호로 버린다.
     */
    private fun onCarLifecycle(c: Car, ready: Boolean) {
        val session = ++uxSession
        runCatching { uxManager?.unregisterListener() }
        uxManager = null
        setUx(null)
        if (!ready) {
            props = null
            infoRead = false
            return
        }
        props = runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager? }.getOrNull()
        val m = runCatching { c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager? }.getOrNull()
        uxManager = m
        if (m == null) { Log.w(TAG, "UX 제한 매니저 없음 — 제한됨으로 본다"); return }
        runCatching {
            m.registerListener { r -> if (session == uxSession) setUx(r.gate()) }
        }.onFailure { Log.w(TAG, "UX 제한 리스너 실패 ${it.javaClass.simpleName}") }
        runCatching { m.currentCarUxRestrictions }.getOrNull()?.let { if (session == uxSession) setUx(it.gate()) }
    }

    /** 값을 두고, 바뀌었으면 바로 알린다 — 주기(2초)를 기다리지 않고 버튼 · 검색 문을 닫게(G5). */
    private fun setUx(v: UxGate?) {
        if (v == ux) return
        ux = v
        onUxChanged(v)
    }

    private fun CarUxRestrictions.gate() = UxGate(
        requiresDo = isRequiresDistractionOptimization,
        noSetup = (activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP) != 0,
    )

    fun speedGranted(): Boolean = app.checkSelfPermission(PERMISSION_CAR_SPEED) == PackageManager.PERMISSION_GRANTED

    /** 내비 검색 화면(정지 뒤에만 부른다 — 실행기가 막는다). 결과는 앱으로 돌아오지 않는다(카탈로그 B). */
    fun openSearch(query: String) = main.post {
        if (!naviReady) { Log.w(TAG, "NaviHelper 미연결 — 검색 화면 못 엶"); return@post }
        runCatching { navi?.requestNavigationScreen(NavigationScreenRequest.SearchPlace(query)) }
            .onFailure { Log.w(TAG, "검색 화면 실패 ${it.javaClass.simpleName}") }
    }

    /** Pleos Vehicle(조명 어댑터가 함께 쓴다). 초기화 실패면 null. */
    fun vehicleSdk(): Vehicle? = vehicle

    fun release() = main.post {
        runCatching { navi?.removeListener(naviListener); navi?.release() }
        runCatching { vehicle?.release() }
        uxSession++
        runCatching { uxManager?.unregisterListener() }
        uxManager = null
        ux = null
        runCatching { car?.disconnect() }
        car = null
        props = null
    }

    /** 에어백 전개 신호를 읽지 못하는 차(능력 미지원 · 확인 오류) — 정차 화면 상세 안내용. */
    fun airbagUnreadable(): Boolean = caps.airbagUnreadable()

    private fun askPleos(location: Boolean) = main.post {
        if (caps.checkDue(SystemClock.elapsedRealtime())) checkCapabilities()
        vehicle?.let { v ->
            if (caps.shouldQuery(CapabilityGate.Signal.BRAKE)) {
                runCatching { v.getBrake().getCurrentBrakePedalStatus({ brakePedal = it }, { brakePedal = null }) }
            } else brakePedal = null
            if (caps.shouldQuery(CapabilityGate.Signal.AIRBAG)) {
                runCatching { v.getSafety().hasSeatAirbagBeenDeployed(driverSeat, { airbagDeployed = it }, { airbagDeployed = null }) }
            } else airbagDeployed = null
            if (caps.shouldQuery(CapabilityGate.Signal.SEAT)) {
                runCatching { v.getSeat().getCurrentSeatOccupancy(driverSeat, { driverSeated = it }, { driverSeated = null }) }
            } else driverSeated = null
        }
        if (location && naviReady) runCatching { navi?.getCurrentLocationInfo() }
    }

    /**
     * 공개 능력 확인 API 세 개를 한 번씩 부른다(메인 스레드). Vehicle 초기화가 실패했으면 셋 다 오류로 두고 10분 뒤 다시 본다.
     * 에뮬레이터에서 이 API 가 어떻게 답하는지는 [미검증] — 오류면 질의를 멈추고 상세 안내를 띄운다.
     */
    private fun checkCapabilities() {
        caps.onChecking(SystemClock.elapsedRealtime())
        val v = vehicle
        if (v == null) { CapabilityGate.Signal.entries.forEach(caps::onError); return }
        fun failed(s: CapabilityGate.Signal) = { e: Exception -> Log.w(TAG, "능력 확인 실패 $s ${e.javaClass.simpleName}"); caps.onError(s) }
        fun done(s: CapabilityGate.Signal, c: CapabilityConfig, areas: List<SeatArea>?) {
            val driver = areas == null || areas.any { a -> SeatValue.ROW_1_LEFT in a.values }
            caps.onResult(s, c.accessType == AccessType.READ || c.accessType == AccessType.READ_WRITE, driver)
            Log.i(TAG, "능력 확인 $s 접근=${c.accessType.name} 운전석=$driver")
        }
        runCatching { v.getSafety().checkAirbagDeployedCapabilities({ c, a -> done(CapabilityGate.Signal.AIRBAG, c, a) }, failed(CapabilityGate.Signal.AIRBAG)) }
            .onFailure { caps.onError(CapabilityGate.Signal.AIRBAG) }
        runCatching { v.getBrake().checkBrakePedalStatusCapabilities({ c -> done(CapabilityGate.Signal.BRAKE, c, null) }, failed(CapabilityGate.Signal.BRAKE)) }
            .onFailure { caps.onError(CapabilityGate.Signal.BRAKE) }
        runCatching { v.getSeat().checkSeatOccupancyCapabilities({ c: CapabilityConfig, a: List<SeatArea> -> done(CapabilityGate.Signal.SEAT, c, a) }, failed(CapabilityGate.Signal.SEAT)) }
            .onFailure { caps.onError(CapabilityGate.Signal.SEAT) }
    }

    private inline fun <reified T> CarPropertyManager.value(id: Int): T? = runCatching {
        val v: CarPropertyValue<*> = getProperty<Any>(id, 0)
        if (v.availability() == CarPropertyValue.STATUS_AVAILABLE) v.value as? T else null
    }.getOrNull()

    /**
     * 값 상태. 새 API `getPropertyStatus()` 를 쓰고, 그 메서드가 없는 옛 car 서비스에서는 폐기된 `getStatus()` 로 떨어진다
     * (두 API 모두 「사용 가능」은 STATUS_AVAILABLE). Pleos 에뮬레이터 · 실차에 새 API 가 있는지는 [미검증].
     */
    private fun CarPropertyValue<*>.availability(): Int = try {
        propertyStatus
    } catch (e: NoSuchMethodError) {
        @Suppress("DEPRECATION") status
    }

    companion object {
        private const val TAG = "CarSignals"
        const val PERMISSION_CAR_SPEED = "android.car.permission.CAR_SPEED"
    }
}
