// 휴대폰 문자 본문 조립 — 지시의 사건 사실 + 휴대폰에만 있는 등록 정보 + 차 상태 · 워치 · 위치로 Reports 를 불러 본문을 만든다
package com.example.guardian.shared.report

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.flow.SpeedSource
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.flow.WatchFreshness
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.Watch

/**
 * 본문은 휴대폰이 만든다(PM 결정 2026-10-30). 주인(차 또는 휴대폰)은 문자 지시에 사건 사실만 싣고, 이름 · 번호 같은
 * 등록 정보는 휴대폰 밖으로 나가지 않는다(G2). 순수 함수 — 보내는 일은 앱이 한다(보호자 문자는 자동, 119 는 사람이 보내기, G4).
 */
object IncidentComposer {

    /**
     * 휴대폰이 가진 값.
     * - [vehicle] 차 상태(phone/sync 응답의 `peer.vehicle`). 차 링크가 끊겼으면 null — 「차 상태 없음」 줄이 붙는다.
     * - [watch] 중계가 고른 워치 값(응답 최상위 watch). [Watch.ageMs] 는 지금 기준 경과로 고쳐 넣는다([WatchFreshness.viaRelay]).
     *   30초를 넘으면 워치 줄을 넣지 않는다(워치 신호 없음).
     * - [phoneLocation] 휴대폰 위치. [Loc.at] 은 잰 시각(휴대폰 벽시계, [nowWallMs] 와 같은 시계)이다. 없거나 30초가 넘었으면
     *   차 내비 위치를 쓴다(G9 출처 순서 · 공통 하4).
     * - [nowLocalMs] 휴대폰 단조 시각, [nowWallMs] 휴대폰 벽시계(표시용 시:분).
     * - [guardianContacted] 이 사건에서 보호자 문자를 보냈는지(119 본문 「(연락함)」).
     * - [serverOffsetMs] 휴대폰 시계 보정값(중계 시각 = 단조 + 보정값). 차 내비 위치의 잰 시각([Vehicle.naviAt], 중계 시각)을
     *   되돌리는 데 쓴다 — 없으면 내비 위치는 잰 시각을 몰라 쓰지 않는다.
     */
    data class PhoneContext(
        val profile: Profile,
        val consents: Consents,
        val vehicle: Vehicle?,
        val watch: Watch?,
        val phoneLocation: Loc?,
        val nowLocalMs: Long,
        val nowWallMs: Long,
        val guardianContacted: Boolean = false,
        val serverOffsetMs: Long? = null,
    )

    /** 보호자 문자 지시 → 본문. [from] 은 지시를 낸 쪽, [offsetMs] 는 휴대폰 시계 보정값. */
    fun guardianSms(p: Payload.SendGuardianSms, from: Party, offsetMs: Long?, c: PhoneContext): ReportResult {
        val facts = p.facts.toLocal(p.eventId, offsetMs, c.nowLocalMs, sameDevice = from == Party.PHONE).facts
        return guardian(p.kind, facts, p.test, c)
    }

    /** 119 작성 지시 → 본문(G9 전체 판). 미리보기 여부는 [Payload.Open119Compose.previewOnly] 를 그대로 따른다. */
    fun compose119(p: Payload.Open119Compose, from: Party, offsetMs: Long?, c: PhoneContext): ReportResult {
        val facts = p.facts.toLocal(p.eventId, offsetMs, c.nowLocalMs, sameDevice = from == Party.PHONE).facts
        return Reports.full119(input(facts, p.test, c))
    }

    /** [facts] 는 휴대폰 단조 시각. */
    fun guardian(kind: GuardianSmsWireKind, facts: IncidentFacts, test: Boolean, c: PhoneContext): ReportResult {
        val r = input(facts, test, c)
        return when (kind) {
            GuardianSmsWireKind.EVENT -> Reports.guardianIncident(r)
            GuardianSmsWireKind.CANCEL -> Reports.guardianCancel(r, facts.responseAtMs?.let { wall(it, c) } ?: c.nowWallMs)
            GuardianSmsWireKind.ACCIDENT -> Reports.guardianAccident(r)
            GuardianSmsWireKind.OBSERVE -> Reports.guardianObserving(r)
        }
    }

    /** 휴대폰 단조 시각 → 벽시계(표시용). */
    private fun wall(localMs: Long, c: PhoneContext) = localMs + (c.nowWallMs - c.nowLocalMs)

    fun input(facts: IncidentFacts, test: Boolean, c: PhoneContext): ReportInput {
        val v = c.vehicle
        val car = v?.takeUnless { facts.carStateMissing }
        val carMissing = car == null
        val speed = if (car == null) c.phoneLocation?.speedKmh else car.speedKmh
        // 차 상태가 없으면 휴대폰 GPS 속도만으로 본다(기어 · 브레이크 모름)
        val stopped = if (car == null) VehicleState(speedKmh = speed, speedSource = SpeedSource.PHONE_GPS).stopped() else VehicleState(car.speedKmh, car.gear, car.parkingBrake, ignitionOn = car.ignitionOn).stopped()
        return ReportInput(
            nowMs = c.nowWallMs,
            profile = c.profile,
            consents = c.consents,
            location = LocationInfo.pick(phoneLoc(c), naviLoc(v, c), null),
            driving = DrivingInfo(
                stopped = stopped, speedKmh = speed,
                stoppedAtMs = facts.stoppedAtMs?.takeIf { stopped }?.let { wall(it, c) }, carStateMissing = carMissing,
            ),
            noResponseSec = facts.noResponseSec(c.nowLocalMs),
            timeline = AlertTimeline(
                facts.firstAlertAtMs?.let { wall(it, c) }, facts.stoppedAtMs?.let { wall(it, c) }, facts.noResponseAtMs?.let { wall(it, c) },
            ),
            watch = watchFacts(c.watch, facts, c),
            vehicle = VehicleInfo(v?.make, v?.model, v?.year),
            test = test,
            guardianContacted = c.guardianContacted,
        )
    }

    /** 출처마다 30초 안에 잰 것만 후보로 둔다 — 낡은 휴대폰 위치가 신선한 내비 위치를 가리지 않게(공통 하4). */
    private fun phoneLoc(c: PhoneContext): LocationInfo? =
        c.phoneLocation?.takeIf { it.isFinite }
            ?.let { LocationInfo(it.address, it.lat, it.lon, it.accuracyM, LocationSource.PHONE_GPS, measuredAtMs = it.at) }
            ?.takeIf { it.freshAt(c.nowWallMs) }

    private fun naviLoc(v: Vehicle?, c: PhoneContext): LocationInfo? {
        val lat = v?.naviLat ?: return null
        val lon = v.naviLon ?: return null
        if (!lat.isFinite() || !lon.isFinite()) return null
        val at = v.naviAt ?: return null
        val offset = c.serverOffsetMs ?: return null
        return LocationInfo(v.naviAddress, lat, lon, null, LocationSource.CAR_NAVI, measuredAtMs = wall(at - offset, c))
            .takeIf { it.freshAt(c.nowWallMs) }
    }

    /**
     * 워치 값 하나로 만든 사실. 구간은 첫 경보부터 값을 잰 때까지. 맥박을 못 찾으면 마지막 맥박 시각(워치 벽시계)부터.
     * 나이([Watch.ageMs])를 모르는 값은 오래된 값으로 보고 워치 줄을 넣지 않는다(끊긴 워치 값을 신선하게 읽지 않게).
     * 워치가 신호를 못 잡고 있다고 알린 값([Watch.noSignal])도 같다 — 그 판정 · 심박은 신선해도 쓰지 않는다(코드 리뷰 2026-11-02 상 1).
     * 보호자 문자 · 119 작성 둘 다 [input] 을 거치므로 여기 한 곳이다.
     */
    private fun watchFacts(w: Watch?, facts: IncidentFacts, c: PhoneContext): WatchFacts? {
        val age = w?.ageMs?.coerceAtLeast(0) ?: return null
        if (WatchFreshness.isStale(age) || w.noSignal) return null
        val toMs = c.nowWallMs - age
        val fromMs = facts.firstAlertAtMs?.let { wall(it, c) }?.coerceAtMost(toMs) ?: toMs
        return WatchFacts(
            fromMs = fromMs, toMs = toMs, hrStart = null, hrLatest = w.hr,
            pulseWeak = w.verdict == WristVerdict.PULSE_WEAK,
            pulseLostAtMs = if (w.verdict == WristVerdict.PULSE_ABSENT) (w.lastPulseAt ?: toMs) else null,
            worn = w.worn && w.verdict != WristVerdict.CHECK_WEAR,
        )
    }
}
