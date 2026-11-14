// 문자 양식 입력 모델 — 등록 프로필 · 동의 3종 · 위치 · 주행 · 경보 · 워치 사실(G9)
package com.example.guardian.shared.report

import java.time.LocalDate

/**
 * 동의 3종(G9). [emergencyInfo] 긴급 연락용 정보 등록 · 119 · 보호자 제공, [health] 건강 관련 정보 별도 동의,
 * [location] 위치 제3자(119 · 보호자) 제공.
 */
data class Consents(
    val emergencyInfo: Boolean = false,
    val health: Boolean = false,
    val location: Boolean = false,
) {
    val all: Boolean get() = emergencyInfo && health && location

    /** 사람 없이 보내는 자동 문자(보호자)는 긴급 연락 정보 · 위치 동의가 모두 있어야 한다. */
    val autoSmsAllowed: Boolean get() = emergencyInfo && location
}

/** 등록 프로필. 보호자는 필수(사용자 지시 — 지병 확인 수단)지만 등록 전일 수 있어 null 을 받고, 양식이 실패로 돌려준다. */
data class Profile(
    val name: String,
    val birthDate: LocalDate,
    val guardianName: String?,
    val guardianPhone: String?,
    val carColor: String,
    val carPlate: String,
) {
    val hasGuardian: Boolean get() = !guardianName.isNullOrBlank() && !guardianPhone.isNullOrBlank()
}

enum class LocationSource { PHONE_GPS, CAR_NAVI, WATCH }

/** [measuredAtMs] 는 위치를 잰 벽시계 ms(ReportInput.nowMs 와 같은 시계) — 위치 줄 시각과 30초 신선도에 쓴다(공통 하4). */
data class LocationInfo(
    val address: String?,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val source: LocationSource,
    val measuredAtMs: Long,
) {
    /** 지금([nowMs]) 기준 30초 안에 잰 위치인가([Reports.LOCATION_FRESH_MS]). */
    fun freshAt(nowMs: Long): Boolean = nowMs - measuredAtMs <= Reports.LOCATION_FRESH_MS

    companion object {
        /** 출처 우선순위 — 휴대폰 GPS → 차 내비 현재 위치 → 워치(G9). */
        fun pick(phone: LocationInfo?, car: LocationInfo?, watch: LocationInfo?): LocationInfo? = phone ?: car ?: watch
    }
}

data class DrivingInfo(
    val stopped: Boolean,
    val speedKmh: Double? = null,
    val stoppedAtMs: Long? = null,
    /** 차 앱 링크가 끊겨 휴대폰이 주인일 때. */
    val carStateMissing: Boolean = false,
)

data class AlertTimeline(
    val firstAlertAtMs: Long? = null,
    val stoppedAtMs: Long? = null,
    val noResponseAtMs: Long? = null,
)

/** 워치 실측 사실 — 수치와 사실만(G9 5번 줄). */
data class WatchFacts(
    val fromMs: Long,
    val toMs: Long,
    val hrStart: Int?,
    val hrLatest: Int?,
    val pulseWeak: Boolean,
    val pulseLostAtMs: Long?,
    val worn: Boolean,
)

data class VehicleInfo(val make: String?, val model: String?, val year: Int?)

/** 양식 결과. 실패면 앱은 등록 · 동의 화면으로 보낸다. */
sealed interface ReportResult {
    data class Ok(val text: String) : ReportResult
    data class Fail(val reason: ReportFailure) : ReportResult
}

enum class ReportFailure {
    /** 보호자 이름 · 연락처 미등록 — 등록을 요구한다. */
    GUARDIAN_MISSING,
    /** 긴급 연락 정보(이름 · 나이 · 차량 · 보호자) 등록 · 제공 동의 없음. */
    EMERGENCY_INFO_CONSENT_MISSING,
    /** 위치 제3자 제공 동의 없음 — 사람 없이 보내는 자동 문자는 쓰지 않는다(반자동만). */
    AUTO_SMS_NOT_ALLOWED,
}

data class ReportInput(
    val nowMs: Long,
    val profile: Profile,
    val consents: Consents,
    val location: LocationInfo?,
    val driving: DrivingInfo,
    val noResponseSec: Int,
    val timeline: AlertTimeline,
    val watch: WatchFacts?,
    val vehicle: VehicleInfo,
    val test: Boolean,
    val guardianContacted: Boolean = false,
)
