// 상태 기계의 상태 · 입력 · 출력 지시 · 타이머 기준 · 스냅숏 모델(설계 §4)
package com.example.guardian.shared.flow

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.text.Phrase

enum class FlowState { OFF, S0, S1, S1R, AP, S2, S3, S4, S5 }

/** 상태 기계 주인. 차 앱의 마지막 sync 가 10초 안이면 CAR, 아니면 PHONE. */
enum class Owner { CAR, PHONE }

enum class Gear { P, R, N, D, UNKNOWN }

/** 응답 출처. PHONE_SCREEN 은 차가 정지했을 때만 받는다(G5). */
enum class ResponseSource { WATCH_BUTTON, WATCH_MOTION, CAR_BUTTON, PHONE_SCREEN }

/** 경보 대상. 차 음성은 [Directive.Speak] 로 따로 낸다. */
enum class AlertTarget { WATCH_VIBRATION, WATCH_SOUND, PHONE_ALARM }

enum class GuardianSmsKind { INCIDENT, CANCEL, ACCIDENT, OBSERVING }

/**
 * 앰비언트 모드(설계 §13). 주행 중 INTERIOR_ALERT(호박색 한 번), 정차 뒤 S2 · S4 PARKED_RESCUE(백색).
 * BLUE_RED_BLINK 는 법률 검토 전 금지 — 상태 기계는 절대 내지 않는다(G6).
 */
enum class AmbientMode { INTERIOR_ALERT, PARKED_RESCUE, BLUE_RED_BLINK }

data class VehicleState(
    val speedKmh: Double? = null,
    val gear: Gear? = null,
    val parkingBrake: Boolean? = null,
    val airbagDeployed: Boolean? = null,
    /** 속도 출처. 차가 주인이면 CAR(차 신호), 휴대폰이 주인(차 상태 없음)이면 PHONE_GPS. */
    val speedSource: SpeedSource = SpeedSource.CAR,
    /** 시동 켜짐(ON · START). ACC · OFF · LOCK 은 false, 모름은 null(계약 §4.2, 합동 점검 B-4). */
    val ignitionOn: Boolean? = null,
) {
    /**
     * 정지 = 속도 ≤ 3km/h 이고 기어 P. 주차 브레이크만으로는 정지로 보지 않는다(사용자 결정 2026-11-13 「완전 정지는 기어 P 또는 시동 꺼짐」).
     * 기어를 모르면 속도만 보되 보수적으로 —
     * - 출처 CAR(기어를 못 읽는 차) → **속도 0 일 때만**. 정체 서행(1~3km/h)을 정지로 읽어 정지 화면 · 119 작성 자격이 열리지 않게(G5, app-qa 참고).
     * - 출처 PHONE_GPS → **[GPS_STOP_KMH] 이하**. 서 있어도 GPS 속도가 0.x km/h 로 흔들려 0 만 보면 S4 · 119 작성을 놓친다(PM 결정).
     * 속도를 모르면 기어 P 이거나 시동 꺼짐([ignitionOn] == false)일 때만 정지, 그 밖은 주행으로 본다(합동 점검 B-4).
     * 「이 판정이 이어짐」은 상태 기계의 정지 디바운스(5초)가 맡는다 — 사건 도중 첫 입력의 즉시 전이는 기어 P 만([confirmedStop]).
     */
    fun stopped(stopSpeedKmh: Double = STOP_KMH): Boolean {
        val speed = speedKmh ?: return gear == Gear.P || ignitionOn == false
        if (speed > stopSpeedKmh) return false
        val gearKnown = gear != null && gear != Gear.UNKNOWN
        if (!gearKnown) return speed <= if (speedSource == SpeedSource.PHONE_GPS) GPS_STOP_KMH else 0.0
        return gear == Gear.P
    }

    /** 확정 정지 — 디바운스 없이 바로 정차 전이를 내도 되는 정지(기어 P). 시동 꺼짐 · 속도만은 5초 지켜본다. */
    fun confirmedStop(stopSpeedKmh: Double = STOP_KMH): Boolean = gear == Gear.P && stopped(stopSpeedKmh)

    companion object {
        /** 정지 속도 문턱(km/h) — [stopped] 와 [FlowParams.stopSpeedKmh] 의 기본값. */
        const val STOP_KMH = 3.0

        /**
         * 휴대폰 GPS 출처의 정지 여유(km/h). 근거 — 서 있는 휴대폰의 GPS 속도가 0.x km/h 로 흔들린다 [추정 — 실측 뒤 조정].
         * 디바운스 5초와 함께 「1km/h 이하가 5초 이어짐」 = 정지로, G5(정지했을 때만 조작)를 지키는 수준으로 본다(PM 결정 2026-10-30).
         */
        const val GPS_STOP_KMH = 1.0
    }
}

/** 정지 판정에 쓰는 속도의 출처. */
enum class SpeedSource { CAR, PHONE_GPS }

data class FlowParams(
    val alert1Ms: Long = 15_000,
    val alert2Ms: Long = 20_000,
    val observeMs: Long = 600_000,
    val restMs: Long = 600_000,
    val stopSpeedKmh: Double = VehicleState.STOP_KMH,
    /** 앰비언트는 법 확인 · Vehicle Control 승인 전까지 꺼 둔다(G6). */
    val ambientEnabled: Boolean = false,
    /** 정차 관찰 중 보호자 안내(선택). */
    val sendObservingSms: Boolean = false,
    /** 워치 값의 경과가 이보다 길면 「워치 신호 없음」 — 판정 입력이 없는 것으로 다룬다. 경과 계산은 [WatchFreshness]. */
    val watchStaleMs: Long = WatchFreshness.STALE_MS,
    /** 정지 · 주행 판정 디바운스 — 새 판정이 이만큼 이어져야 바꾼다(GPS 속도 흔들림 대비). */
    val stopDebounceMs: Long = 5_000,
    /**
     * S1R · S2 에서 나쁜 판정이 이만큼 이어지면 다시 경보한다. 판정이 굳은 채 응답이 반복되면 30초 → 2분 → 5분(이후 5분)으로 늘린다.
     * PULSE_ABSENT 로 나빠지면 단계와 무관하게 즉시.
     */
    val realertStepsMs: List<Long> = listOf(30_000, 120_000, 300_000),
    /** 판정이 NORMAL 로 이만큼 이어지면 재경보 간격을 첫 단계로 되돌린다. */
    val realertResetNormalMs: Long = 30_000,
    /**
     * 미착용 판정이 이만큼 이어져야 꺼짐(OFF) + 「착용 확인」으로 본다 — 감시 중(S0)과 응답 뒤(S1R · S2) 같은 값.
     * 응답 뒤 — 느슨한 착용의 깜박임 한 번으로 OFF → S0 → S1 이 이어져 재경보 간격 · 10분 휴식을 건너뛰지 않게(코드 리뷰 11-09 공통 하1, PM 결정 (a)).
     * 감시 중 — 미착용 깜빡임 한 번으로 감시가 꺼졌다 켜지며 에어백 신호를 놓치지 않게(합동 점검 B-6). 켜기는 착용 + 신선한 값 한 번 그대로다.
     */
    val unwornOffMs: Long = 10_000,
    /** 응답 뒤 나쁜 판정 시작 시각은 NORMAL 이 이만큼 이어져야 지운다 — NORMAL 한 번이 지속 재경보를 지우지 않게(합동 점검 B-2). */
    val realertClearNormalMs: Long = 10_000,
    /** 지속 재경보는 마지막 NORMAL 뒤 나쁜 판정이 이만큼 이어질 때만 낸다(합동 점검 B-2, be-qa 실행으로 8초). */
    val realertMinBadMs: Long = 8_000,
)

/** 모든 입력은 시각을 싣는다(시계 의존 없음). */
sealed interface FlowInput {
    val atMs: Long

    /**
     * [ageMs] 는 [atMs] 시점에 이 워치 값이 얼마나 지났는지(주인 기기 시계로 잰 경과). 기기 시계와 중계 시계를
     * 섞지 않으려고 시각 대신 경과를 받는다 — [WatchFreshness] 로 계산한다.
     */
    data class Wrist(val verdict: WristVerdict, override val atMs: Long, val ageMs: Long = 0) : FlowInput
    data class Response(val source: ResponseSource, override val atMs: Long) : FlowInput
    data class Vehicle(val state: VehicleState, override val atMs: Long) : FlowInput
    data class Tick(override val atMs: Long) : FlowInput
    data class ConsentsChanged(val consents: Consents, override val atMs: Long) : FlowInput
    data class Power(val on: Boolean, override val atMs: Long) : FlowInput
    /**
     * 테스트 모드. 켜기는 OFF · S0 에서만(진행 중 사건의 표시가 바뀌지 않게). 끄기는 거기에 더해 테스트 사건의 응답 뒤(S1R · S2)에서도 받아
     * 표시를 바로 끄고 S0 으로 돌아간다(2026-11-07). 경보 중(S1 · AP · S3 · S4 · S5)에는 어느 쪽도 받지 않는다.
     */
    data class TestMode(val on: Boolean, override val atMs: Long) : FlowInput
    /**
     * 테스트 흐름 거두기(합동 점검 B-7). 흐름이 테스트(test=true)일 때만 경보 중(S1 · AP · S3 · S4)과 응답 뒤(S1R · S2)에서 경보를 멈추고 S0 으로 간다.
     * 실제 흐름과 S5 는 어떤 경우에도 거두지 않는다. 테스트 세션이 정말 끝났을 때(세션 끝 규칙 · 7분 안전장치) 앱이 한 번 넣는다 — 주인 기기가 넣는다.
     */
    data class WithdrawTest(override val atMs: Long) : FlowInput
    /** 차 앱 마지막 sync 시각. 주인이 PHONE 이면 「차 상태 없음」으로 기록한다. */
    data class Link(val carLastSyncAtMs: Long?, override val atMs: Long) : FlowInput
    /** 이 기기의 시계 보정값(중계 − 로컬, [com.example.guardian.shared.protocol.ClockOffset]). 사건 id 를 중계 시각으로 만드는 데 쓴다. */
    data class ClockSync(val offsetMs: Long?, override val atMs: Long) : FlowInput
}

/** 문자 양식에 넘길 사건 사실(시각은 ms). */
data class IncidentFacts(
    val firstAlertAtMs: Long? = null,
    val stoppedAtMs: Long? = null,
    val noResponseAtMs: Long? = null,
    val responseAtMs: Long? = null,
    val carStateMissing: Boolean = false,
    /** 에어백 전개를 감지한 시각. */
    val accidentAtMs: Long? = null,
    /**
     * 사건 id — 사건(경보 · 사고)이 시작될 때 한 번 만들고 넘김 때도 바꾸지 않는다.
     * 시계 보정값이 있으면 `event-{중계 ms}` · `accident-{중계 ms}`, 없으면 로컬 표시 `event-L{로컬 ms}`.
     */
    val eventId: String? = null,
) {
    /** 응답 없음 경과 초 — 첫 경보부터 [nowMs] 까지. */
    fun noResponseSec(nowMs: Long): Int = firstAlertAtMs?.let { ((nowMs - it) / 1000).toInt().coerceAtLeast(0) } ?: 0
}

/** 상태 기계가 내는 지시. 모든 지시에 테스트 표시가 실린다. 119 로 보내는 지시는 없다(G4). */
sealed interface Directive {
    val test: Boolean

    /**
     * [level] 1 = 진동(경보 1), 2 = 소리(경보 2). [durationMs] 는 단계 표시용 길이일 뿐이다 —
     * 받는 쪽은 이 시간이 지나도 멈추지 않고 [AlertStop] 이 올 때까지 경보를 계속한다.
     */
    /** [eventId] 는 이 경보의 사건 id(계약 §4.6 — 워치가 정지한 사건의 시작을 버리고, 같은 사건의 정지만 듣는 데 쓴다). */
    data class AlertStart(
        val targets: Set<AlertTarget>, val stage: FlowState, val level: Int, val durationMs: Long, override val test: Boolean,
        val eventId: String? = null,
    ) : Directive
    /** [eventId] 는 멈추는 경보의 사건 id — 사고(S5)로 바뀔 때는 옛 사건 id 다(새 사고 id 를 실으면 워치가 옛 경보를 못 끈다, app-qa). */
    data class AlertStop(override val test: Boolean, val eventId: String? = null) : Directive
    data class Speak(val phrase: Phrase, override val test: Boolean) : Directive
    data class Show(val phrase: Phrase, override val test: Boolean) : Directive
    /** 켤 때 [mode] 가 있고 끌 때 null. */
    data class Ambient(val mode: AmbientMode?, override val test: Boolean) : Directive {
        val on: Boolean get() = mode != null
    }
    data class OpenSearch(val phrase: Phrase, override val test: Boolean) : Directive
    /** 휴대폰에 119 문자 작성 화면(반자동)을 연다. 사람이 「보내기」를 누른다. 테스트면 미리보기만. */
    data class Open119Compose(val facts: IncidentFacts, val previewOnly: Boolean, override val test: Boolean) : Directive
    data class SendGuardianSms(val kind: GuardianSmsKind, val facts: IncidentFacts, override val test: Boolean) : Directive
}

/** 주인이 바뀔 때 넘기는 상태 전부. 이것만으로 같은 동작이 이어진다. */
data class FlowSnapshot(
    val state: FlowState = FlowState.OFF,
    val sinceMs: Long = 0,
    val test: Boolean = false,
    val powerOn: Boolean = false,
    val consents: Consents = Consents(),
    val worn: Boolean = false,
    val verdict: WristVerdict = WristVerdict.NORMAL,
    /** 디바운스를 거친 정지 판정. */
    val stopped: Boolean = false,
    /** 마지막 입력의 정지 판정(디바운스 전). null = 아직 차량 입력 없음. */
    val rawStopped: Boolean? = null,
    /** rawStopped 가 stopped 와 달라진 시각. null = 같음. */
    val stopPendingSinceMs: Long? = null,
    /** 속도 null 이 연달아 온 횟수 — 한 번은 직전 판정 유지, 두 번째부터 주행으로 본다. */
    val nullSpeedCount: Int = 0,
    val airbag: Boolean = false,
    val stage: Int = 0,
    val alertStartMs: Long = 0,
    val alerting: Boolean = false,
    val incidentActive: Boolean = false,
    val incidentSmsSent: Boolean = false,
    val cancelSent: Boolean = false,
    val observingSent: Boolean = false,
    val accidentSent: Boolean = false,
    val ambientMode: AmbientMode? = null,
    /** 마지막 워치 값을 잰 시각(주인 기기 시계 = 입력 atMs − ageMs). */
    val watchSeenAtMs: Long? = null,
    val noSignalNotified: Boolean = false,
    /** S1R · S2 에서 나쁜 판정이 시작된 시각. */
    val weakSinceMs: Long? = null,
    /** 나쁜 판정 지속 재경보 단계(0 = 30초, 1 = 2분, 2 = 5분). */
    val realertLevel: Int = 0,
    /** NORMAL 판정이 이어지기 시작한 시각 — 30초 이어지면 재경보 단계를 0 으로. */
    val normalSinceMs: Long? = null,
    /** 미착용(CHECK_WEAR) 판정이 이어지기 시작한 시각 — [FlowParams.unwornOffMs] 이어져야 꺼짐. */
    val unwornSinceMs: Long? = null,
    /** 마지막 NORMAL 판정 시각 — 지속 재경보는 이 뒤 나쁜 판정이 [FlowParams.realertMinBadMs] 이어져야 낸다. null = 모름(제한 없음). */
    val lastNormalAtMs: Long? = null,
    /** 이 사건에서 119 작성 · 응급실 검색을 이미 자동으로 열었는지 — 사건마다 한 번만. */
    val composeOpened: Boolean = false,
    /** 이 기기의 시계 보정값(중계 − 로컬). 넘겨받으면 받는 쪽 값으로 바뀐다. */
    val serverOffsetMs: Long? = null,
    /** 시계 표본 없이 넘겨받아 시각을 받은 시점 기준으로 다시 잡았음(타이머가 늦어지는 쪽). */
    val clockUnverified: Boolean = false,
    val facts: IncidentFacts = IncidentFacts(),
)

/**
 * 워치 값 경과 계산(두 경로).
 * - 중계 경유 — 중계가 응답에 실은 `ageMs`(중계 시계로 잰 경과) + 응답을 받은 뒤 지난 로컬 시간.
 * - 휴대폰 Data Layer 직접 — 휴대폰이 값을 받은 로컬 시각부터 센다.
 */
object WatchFreshness {
    const val STALE_MS = 30_000L

    fun viaRelay(relayAgeMs: Long, responseReceivedAtLocalMs: Long, nowLocalMs: Long): Long =
        relayAgeMs + (nowLocalMs - responseReceivedAtLocalMs).coerceAtLeast(0)

    fun viaDataLayer(receivedAtLocalMs: Long, nowLocalMs: Long): Long = (nowLocalMs - receivedAtLocalMs).coerceAtLeast(0)

    fun isStale(ageMs: Long, staleMs: Long = STALE_MS): Boolean = ageMs > staleMs
}

/**
 * 스냅숏의 모든 시각 필드를 [delta] 만큼 옮긴다(주인 넘김 때 로컬 ↔ 중계 시각 변환).
 * 시각 필드 — sinceMs · alertStartMs · stopPendingSinceMs · watchSeenAtMs · weakSinceMs · normalSinceMs · unwornSinceMs · lastNormalAtMs · facts 의 시각 전부.
 */
fun FlowSnapshot.shiftedBy(delta: Long): FlowSnapshot = copy(
    sinceMs = sinceMs + delta,
    alertStartMs = alertStartMs + delta,
    stopPendingSinceMs = stopPendingSinceMs?.plus(delta),
    watchSeenAtMs = watchSeenAtMs?.plus(delta),
    weakSinceMs = weakSinceMs?.plus(delta),
    normalSinceMs = normalSinceMs?.plus(delta),
    unwornSinceMs = unwornSinceMs?.plus(delta),
    lastNormalAtMs = lastNormalAtMs?.plus(delta),
    facts = facts.copy(
        firstAlertAtMs = facts.firstAlertAtMs?.plus(delta),
        stoppedAtMs = facts.stoppedAtMs?.plus(delta),
        noResponseAtMs = facts.noResponseAtMs?.plus(delta),
        responseAtMs = facts.responseAtMs?.plus(delta),
        accidentAtMs = facts.accidentAtMs?.plus(delta),
    ),
)
