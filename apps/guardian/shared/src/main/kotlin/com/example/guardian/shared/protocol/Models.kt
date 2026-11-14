// 중계 프로토콜 메시지 모델과 JSON 변환(contracts/guardian/protocol.md) — 짝짓기 · sync(차 · 휴대폰 · 워치) · 지시
package com.example.guardian.shared.protocol

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.shiftedBy
import java.util.concurrent.atomic.AtomicLong

enum class Role { PHONE, WATCH }
enum class Party { CAR, PHONE, WATCH }
enum class CommandTo { PHONE, CAR, WATCH, ALL }
enum class WatchSource { SENSOR_SDK, HEALTH_SERVICES, SIMULATION }
enum class Via { WATCH, PHONE }

enum class CommandType {
    ALERT_START, ALERT_STOP, SPEAK, SHOW, AMBIENT, OPEN_SEARCH, OPEN_119_COMPOSE, SEND_GUARDIAN_SMS, TEST_SCENARIO, FLOW_SNAPSHOT,

    /** 휴대폰 → 워치 — 워치 테스트 시나리오를 끝내고 실제 센서로 돌아가라(2026-10-30 추가, 계약 §4.6). 늘 test=true. */
    TEST_SCENARIO_STOP,

    /** 차 → 휴대폰 — 정차 중 차 화면에서 테스트 시작 · 멈춤을 요청한다(2026-11-01 추가, 계약 §4.6 「차에서 테스트 요청」). 늘 test=true. */
    TEST_REQUEST,
}

/** sync 순번 — 앱을 다시 켜도 줄지 않게 시작 시 에포크 밀리초에서 시작해 1씩 올린다(중계는 줄어든 seq 를 덮지 않음). */
class SeqGenerator(startEpochMs: Long) {
    private val n = AtomicLong(startEpochMs)
    fun next(): Long = n.getAndIncrement()
}

private fun <T> List<Any?>.objs(f: (JsonObj) -> T): List<T> = map {
    @Suppress("UNCHECKED_CAST")
    f(JsonObj(it as? Map<String, Any?> ?: throw JsonException("배열 원소는 객체")))
}

private fun List<Any?>.strings(): List<String> = map { it as? String ?: throw JsonException("배열 원소는 문자열") }

/**
 * 응답의 지시 목록 — 모르는 type · 형식이 틀린 지시(payload 가 객체가 아님 등)는 **그 지시만 건너뛴다**(계약 §4.6, 2026-10-30).
 * 나머지 지시 · peer · watch 는 그대로 읽는다. 건너뛴 지시 중 id 가 문자열인 것은 두 번째 값으로 돌려준다 —
 * 받는 쪽은 「지원하지 않음」으로 보고 다음 sync 의 acks 에 넣는다(id 가 없으면 ack 할 수 없어 버린다).
 */
private fun List<Any?>.commandsSkipping(): Pair<List<Command>, List<String>> {
    val ok = ArrayList<Command>()
    val skipped = ArrayList<String>()
    for (e in this) {
        @Suppress("UNCHECKED_CAST")
        val m = e as? Map<String, Any?> ?: continue
        try {
            ok += Command.fromJson(JsonObj(m))
        } catch (_: JsonException) {
            (m["id"] as? String)?.let { skipped += it }
        }
    }
    return ok to skipped
}

// ── 짝짓기 ──

data class PairStartResponse(val pairCode: String, val carToken: String, val expiresInSec: Int) {
    fun toJson() = linkedMapOf<String, Any?>("pairCode" to pairCode, "carToken" to carToken, "expiresInSec" to expiresInSec)
    companion object {
        fun fromJson(o: JsonObj) = PairStartResponse(o.str("pairCode"), o.str("carToken"), o.int("expiresInSec"))
    }
}

data class PairJoinRequest(val pairCode: String, val role: Role = Role.PHONE) {
    fun toJson() = linkedMapOf<String, Any?>("pairCode" to pairCode, "role" to role.name)
    companion object {
        fun fromJson(o: JsonObj) = PairJoinRequest(o.str("pairCode"), o.enumOrNull<Role>("role") ?: Role.PHONE)
    }
}

/** PHONE → phoneToken, WATCH → watchToken. */
data class PairJoinResponse(val pairId: String, val phoneToken: String? = null, val watchToken: String? = null) {
    init { require((phoneToken == null) != (watchToken == null)) { "토큰은 하나만" } }
    val role: Role get() = if (phoneToken != null) Role.PHONE else Role.WATCH
    val token: String get() = phoneToken ?: watchToken!!

    fun toJson() = linkedMapOf<String, Any?>().apply {
        if (phoneToken != null) put("phoneToken", phoneToken) else put("watchToken", watchToken)
        put("pairId", pairId)
    }
    companion object {
        fun fromJson(o: JsonObj) = PairJoinResponse(o.str("pairId"), o.strOrNull("phoneToken"), o.strOrNull("watchToken"))
    }
}

data class PairEndRequest(val token: String) {
    fun toJson() = linkedMapOf<String, Any?>("token" to token)
    companion object { fun fromJson(o: JsonObj) = PairEndRequest(o.str("token")) }
}

/** 오류 본문 `{"error":"<코드>"}` — PAIR_CODE_INVALID · TOKEN_INVALID · BAD_REQUEST 등. */
data class ErrorResponse(val error: String) {
    fun toJson() = linkedMapOf<String, Any?>("error" to error)
    companion object { fun fromJson(o: JsonObj) = ErrorResponse(o.str("error")) }
}

// ── 상태 ──

/**
 * [naviAt] 은 내비 위치를 받은 순간의 **중계 시각** ms(선택 · 생략 = null — 2026-11-08 코드 리뷰 공통 하4). 차는 시계 보정값과 좌표가 있을 때만 싣는다.
 * 받는 휴대폰은 자기 보정값으로 측정 시각을 되돌려 문자 위치 줄 시각 · 30초 신선도에 쓰고, 없으면 측정 시각을 모르는 위치로 보고 쓰지 않는다.
 */
data class Vehicle(
    val speedKmh: Double? = null,
    val gear: Gear? = null,
    val parkingBrake: Boolean? = null,
    val brakePedal: Boolean? = null,
    val airbagDeployed: Boolean? = null,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val naviAddress: String? = null,
    val naviLat: Double? = null,
    val naviLon: Double? = null,
    val naviAt: Long? = null,
    /** 시동 켜짐(ON · START = true, ACC · OFF · LOCK = false, 모름 = null). 없으면 싣지 않는다(옛 판과 같음, 합동 점검 B-4). */
    val ignitionOn: Boolean? = null,
) {
    fun toJson() = linkedMapOf<String, Any?>(
        "speedKmh" to speedKmh, "gear" to gear?.name, "parkingBrake" to parkingBrake, "brakePedal" to brakePedal,
        "airbagDeployed" to airbagDeployed, "make" to make, "model" to model, "year" to year,
        "naviAddress" to naviAddress, "naviLat" to naviLat, "naviLon" to naviLon,
    ).apply { naviAt?.let { put("naviAt", it) }; ignitionOn?.let { put("ignitionOn", it) } }
    companion object {
        fun fromJson(o: JsonObj) = Vehicle(
            o.doubleOrNull("speedKmh"), o.enumOrNull<Gear>("gear"), o.boolOrNull("parkingBrake"), o.boolOrNull("brakePedal"),
            o.boolOrNull("airbagDeployed"), o.strOrNull("make"), o.strOrNull("model"), o.intOrNull("year"),
            o.strOrNull("naviAddress"), o.doubleOrNull("naviLat"), o.doubleOrNull("naviLon"), o.longOrNull("naviAt"),
            o.boolOrNull("ignitionOn"),
        )
    }
}

/**
 * 워치 상태. [via] · [receivedAt] · [ageMs] 는 중계가 고른 값(응답 최상위 watch)에만 붙는 선택 필드다 — 생략 = null.
 * 심박 정수는 건강 관련 동의가 있을 때만 싣는다([toJson] 의 healthConsent).
 * [noSignal] 은 워치가 신호를 못 잡고 있다는 표시(선택, 생략 = false, true 일 때만 싣는다 — 2026-11-02 코드 리뷰 상 1).
 * 받는 쪽은 이 값이 true 면 [verdict] 를 판정 입력으로 쓰지 않고 [responseAt] 만 처리한다. 옛 판은 모르는 필드라 무시한다.
 */
data class Watch(
    val verdict: WristVerdict,
    val hr: Int?,
    val lastPulseAt: Long?,
    val worn: Boolean,
    val source: WatchSource,
    val test: Boolean,
    val responseAt: Long?,
    val via: Via? = null,
    val receivedAt: Long? = null,
    val ageMs: Long? = null,
    val noSignal: Boolean = false,
    /** 응답 번호(B-5, 선택) — 워치 응답마다 1 증가, 워치가 저장. null = 옛 워치(받는 쪽은 [responseAt] 규칙) 또는 응답한 적 없음. */
    val responseSeq: Long? = null,
    /** 워치가 지금 울리는 경보(C-2, 선택). [alertingReported] 가 거짓이면 옛 워치라 모른다 — 주인은 다시 내기를 하지 않는다. */
    val alerting: WatchAlertStatus? = null,
    /** 새 워치는 `alerting` 키를 늘 싣는다(울리지 않으면 null). 키가 없으면 옛 워치. */
    val alertingReported: Boolean = false,
) {
    fun toJson(healthConsent: Boolean) = linkedMapOf<String, Any?>(
        "verdict" to verdict.name, "hr" to if (healthConsent) hr else null, "lastPulseAt" to lastPulseAt, "worn" to worn,
        "source" to source.name, "test" to test, "responseAt" to responseAt,
    ).apply {
        via?.let { put("via", it.name) }
        receivedAt?.let { put("receivedAt", it) }
        ageMs?.let { put("ageMs", it) }
        if (noSignal) put("noSignal", true)
        responseSeq?.let { put("responseSeq", it) }
        if (alertingReported) put("alerting", alerting?.toJson())
    }
    companion object {
        fun fromJson(o: JsonObj) = Watch(
            o.enum("verdict"), o.intOrNull("hr"), o.longOrNull("lastPulseAt"), o.bool("worn"), o.enum("source"),
            o.bool("test"), o.longOrNull("responseAt"), o.enumOrNull<Via>("via"), o.longOrNull("receivedAt"), o.longOrNull("ageMs"),
            noSignal = o.boolOrNull("noSignal") ?: false,
            responseSeq = o.longOrNull("responseSeq"),
            // 틀린 alerting 은 모름(옛 워치처럼) — 이 필드 하나로 워치 값 전체를 버리지 않는다
            alerting = parseOrNull(o.map["alerting"], WatchAlertStatus::fromJson),
            alertingReported = o.map.containsKey("alerting") && (o.map["alerting"] == null || parseOrNull(o.map["alerting"], WatchAlertStatus::fromJson) != null),
        )

        /** 응답 안의 watch(최상위 · peer) — 객체가 아니거나 안쪽이 틀리면 null(모름). 이 필드 하나로 응답 전체를 버리지 않는다(리뷰 11-08 하3). */
        fun fromJsonOrNull(v: Any?): Watch? = parseOrNull(v, ::fromJson)
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> parseOrNull(v: Any?, read: (JsonObj) -> T): T? {
    val m = v as? Map<String, Any?> ?: return null
    return try { read(JsonObj(m)) } catch (_: JsonException) { null }
}

data class Loc(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val speedKmh: Double?,
    val at: Long,
    val address: String?,
) {
    /** 필수 좌표가 유한한 수인가. 아니면 보내는 쪽이 Loc 객체 자체를 null 로 싣는다. */
    val isFinite: Boolean get() = lat.isFinite() && lon.isFinite()

    fun toJson() = linkedMapOf<String, Any?>(
        "lat" to lat, "lon" to lon, "accuracyM" to accuracyM, "speedKmh" to speedKmh, "at" to at, "address" to address,
    )
    companion object {
        fun fromJson(o: JsonObj) = Loc(
            o.double("lat"), o.double("lon"), o.doubleOrNull("accuracyM"), o.doubleOrNull("speedKmh"), o.long("at"), o.strOrNull("address"),
        )

    }
}

/** 넘겨받은 스냅숏 복원 결과. [clockVerified] 가 false 면 화면 · 기록에 「시계 미확인」을 표시한다. */
data class RestoredSnapshot(val snapshot: FlowSnapshot, val clockVerified: Boolean)

/** 스냅숏 시각의 기준 시계. 주인 넘김은 SERVER(중계 시각)로 싣는다. */
enum class SnapshotClock { LOCAL, SERVER }

/**
 * 프로토콜 Flow — 상태 · 주인 · 스냅숏(주인 넘김용 전부). [since] 와 스냅숏 시각은 [clock] 기준이다.
 * [localTest] 는 휴대폰 단독(휴대폰 안) 시험이 도는 중이라는 **표시 전용** 선택 필드(계약 §4.5, 2026-11-07) — 참일 때만 싣고 생략은 거짓.
 * 상태 기계 입력이 아니다(스냅숏에 들어가지 않아 넘김 · 복원에 닿지 않는다). 차는 이것으로 테스트 요청을 풀고 안내 한 줄을 보인다.
 */
data class FlowStatus(
    val state: FlowState, val since: Long, val owner: Owner, val test: Boolean, val snapshot: FlowSnapshot,
    val clock: SnapshotClock = SnapshotClock.LOCAL,
    val localTest: Boolean = false,
    /**
     * 휴대폰 흐름만 싣는 보호자 문자 결과(합동 점검 A-1, 선택 필드) — null = 싣지 않음(차 · 옛 휴대폰). 빈 목록은 「확인된 것 없음」이다.
     * 범위는 [SmsDone.select]. 옛 판은 모르는 필드라 무시한다.
     */
    val smsDone: List<SmsDone>? = null,
) {
    fun toJson() = linkedMapOf<String, Any?>(
        "state" to state.name, "since" to since, "owner" to owner.name, "test" to test,
        "snapshot" to SnapshotCodec.toJson(snapshot, clock),
    ).also {
        if (localTest) it["localTest"] = true
        smsDone?.let { l -> it["smsDone"] = l.map(SmsDone::toJson) }
    }

    /**
     * 받은 스냅숏을 내 로컬(단조) 시계로 되돌린다. 예외를 내지 않는다.
     * - SERVER + 내 offsetMs 있음 → 정확히 되돌린다.
     * - LOCAL + [sameDevice](내가 보낸 스냅숏) → 그대로.
     * - 그 밖(SERVER 인데 내 보정값 없음 · 다른 기기의 LOCAL) → 「시계 미확인」. 가장 늦은 시각을 [nowLocalMs] 에 맞춰 다시 잡는다 —
     *   모든 타이머가 실제보다 늦어지는 쪽(앞당기지 않음). 다른 기기의 단조 시각은 내 시계와 섞어 쓸 수 없다(계약 §2).
     */
    fun restoreLocal(offsetMs: Long?, nowLocalMs: Long, sameDevice: Boolean = false): RestoredSnapshot = when {
        clock == SnapshotClock.LOCAL && sameDevice -> RestoredSnapshot(snapshot.copy(serverOffsetMs = offsetMs), clockVerified = true)
        clock == SnapshotClock.SERVER && offsetMs != null ->
            RestoredSnapshot(snapshot.shiftedBy(-offsetMs).copy(serverOffsetMs = offsetMs, clockUnverified = false), clockVerified = true)
        else -> {
            val f = snapshot.facts
            val latest = listOfNotNull(
                snapshot.sinceMs, snapshot.alertStartMs.takeIf { snapshot.stage > 0 }, snapshot.stopPendingSinceMs,
                snapshot.watchSeenAtMs, snapshot.weakSinceMs, snapshot.normalSinceMs, snapshot.lastNormalAtMs, f.firstAlertAtMs, f.stoppedAtMs, f.noResponseAtMs,
                f.responseAtMs, f.accidentAtMs,
            ).max()
            RestoredSnapshot(
                snapshot.shiftedBy(nowLocalMs - latest).copy(serverOffsetMs = offsetMs, clockUnverified = true),
                clockVerified = false,
            )
        }
    }

    companion object {
        fun of(snapshot: FlowSnapshot, owner: Owner) = FlowStatus(snapshot.state, snapshot.sinceMs, owner, snapshot.test, snapshot)

        /**
         * sync 에 싣는 flow — 규칙 하나(2026-10-30). 시계 보정값이 있으면 늘 중계 시각(SERVER, [forHandoff] 와 같음),
         * 없을 때만 내 단조 시각(LOCAL). 받는 쪽은 [restoreLocal] 로 되돌린다(평소 sync · 넘김 · `FLOW_SNAPSHOT` 모두 같다).
         */
        fun forSync(snapshot: FlowSnapshot, owner: Owner, offsetMs: Long?): FlowStatus =
            if (offsetMs != null) forHandoff(snapshot, owner, offsetMs) else of(snapshot, owner)

        /** 주인 넘김용 — 모든 시각을 중계 시각(로컬 + [offsetMs])으로 바꿔 싣는다. */
        fun forHandoff(snapshot: FlowSnapshot, owner: Owner, offsetMs: Long): FlowStatus {
            val server = snapshot.shiftedBy(offsetMs)
            return FlowStatus(server.state, server.sinceMs, owner, server.test, server, SnapshotClock.SERVER)
        }

        fun fromJson(o: JsonObj): FlowStatus {
            val snap = o.obj("snapshot")
            return FlowStatus(
                o.enum("state"), o.long("since"), o.enum("owner"), o.bool("test"), SnapshotCodec.fromJson(snap),
                snap.enumOrNull<SnapshotClock>("clock") ?: SnapshotClock.LOCAL,
                localTest = o.boolOrNull("localTest") ?: false,
                smsDone = SmsDone.listFromJsonOrNull(o.map["smsDone"]),
            )
        }

        /**
         * 상대가 보낸 `flow` 를 읽는다(peer 안). 객체가 아니거나 안쪽이 틀리면(모르는 enum · 빠진 필드 — 상대 빌드가 다름) null(모름) —
         * 이 필드 하나 때문에 응답 전체를 버리지 않는다([CarDisplay.fromJsonOrNull] 방식, 계약 §4.6, 리뷰 중 7 · 2026-11-02).
         */
        fun fromJsonOrNull(v: Any?): FlowStatus? = parseOrNull(v, ::fromJson)
    }
}

/**
 * 차 화면 표시 설정(휴대폰 → 중계 → 차, 계약 §4.1 `carDisplay`, 2026-11-01). [hideHr] 면 차 화면에 심박 숫자와 맥박 신호 상태를 둘 다 숨긴다.
 * 차는 설정을 모르면(필드 없음 · null · 형식 틀림) 숨긴다 — [hidden].
 */
data class CarDisplay(val hideHr: Boolean) {
    fun toJson() = linkedMapOf<String, Any?>("hideHr" to hideHr)

    companion object {
        /** 받은 값을 읽는다. 객체가 아니거나 `hideHr` 가 참거짓이 아니면 null(모름) — 이 필드 하나 때문에 응답 전체를 버리지 않는다. */
        fun fromJsonOrNull(v: Any?): CarDisplay? = ((v as? Map<*, *>)?.get("hideHr") as? Boolean)?.let(::CarDisplay)

        /** 차가 숨길지 — 모르면 숨긴다(G2, 계약 §4.1). */
        fun hidden(d: CarDisplay?): Boolean = d?.hideHr ?: true
    }
}

/**
 * [responseAt] 휴대폰 화면 「괜찮아요」 응답 시각(없으면 null). 워치 [Watch.responseAt] 과 같은 규칙 — 휴대폰 벽시계(에포크 ms)이고
 * **변화 표시 전용**이다(다른 시계와 빼지 않는다). 차는 [ResponseChange] 로 상태 기계 응답으로 바꾼다.
 */
data class PhonePeer(
    val seq: Long, val sentAt: Long, val receivedAt: Long, val watch: Watch?, val flow: FlowStatus?,
    val responseAt: Long? = null,
    /** 차 화면 표시 설정 — 없으면(모름) 차는 숨긴다([CarDisplay.hidden]). */
    val carDisplay: CarDisplay? = null,
) {
    fun toJson(healthConsent: Boolean) = linkedMapOf<String, Any?>(
        "seq" to seq, "sentAt" to sentAt, "receivedAt" to receivedAt, "watch" to watch?.toJson(healthConsent),
        "flow" to flow?.toJson(), "responseAt" to responseAt, "carDisplay" to carDisplay?.toJson(),
    )
    companion object {
        fun fromJson(o: JsonObj) = PhonePeer(
            o.long("seq"), o.long("sentAt"), o.long("receivedAt"), Watch.fromJsonOrNull(o.map["watch"]),
            FlowStatus.fromJsonOrNull(o.map["flow"]),
            o.longOrNull("responseAt"), CarDisplay.fromJsonOrNull(o.map["carDisplay"]),
        )
    }
}

/** [flow] 가 읽히지 않으면(상대 빌드가 다름) null(모름) — 나머지 필드는 그대로 읽는다([FlowStatus.fromJsonOrNull]). */
data class CarPeer(val seq: Long, val sentAt: Long, val receivedAt: Long, val vehicle: Vehicle, val flow: FlowStatus?) {
    fun toJson() = linkedMapOf<String, Any?>(
        "seq" to seq, "sentAt" to sentAt, "receivedAt" to receivedAt, "vehicle" to vehicle.toJson(), "flow" to flow?.toJson(),
    )
    companion object {
        fun fromJson(o: JsonObj) = CarPeer(
            o.long("seq"), o.long("sentAt"), o.long("receivedAt"), Vehicle.fromJson(o.obj("vehicle")), FlowStatus.fromJsonOrNull(o.map["flow"]),
        )
    }
}

// ── 지시 ──

data class Command(
    val id: String,
    val type: CommandType,
    val payload: Map<String, Any?>,
    val ttlMs: Long,
    val test: Boolean,
    val from: Party,
    val to: CommandTo? = null,
) {
    /** `to` 가 없으면 차 → PHONE, 휴대폰 → CAR, 워치 → PHONE. */
    val resolvedTo: CommandTo get() = to ?: defaultTo(from)

    fun toJson() = linkedMapOf<String, Any?>(
        "id" to id, "type" to type.name, "payload" to payload, "ttlMs" to ttlMs, "test" to test, "from" to from.name,
    ).apply { to?.let { put("to", it.name) } }

    companion object {
        fun defaultTo(from: Party) = when (from) {
            Party.CAR -> CommandTo.PHONE
            Party.PHONE -> CommandTo.CAR
            Party.WATCH -> CommandTo.PHONE
        }

        fun fromJson(o: JsonObj) = Command(
            o.str("id"), o.enum("type"), o.objOrNull("payload")?.map ?: emptyMap(), o.long("ttlMs"), o.bool("test"),
            o.enum("from"), o.enumOrNull<CommandTo>("to"),
        )
    }
}

// ── sync ──

data class CarSyncRequest(
    val token: String, val seq: Long, val sentAt: Long, val vehicle: Vehicle, val flow: FlowStatus?,
    val commands: List<Command> = emptyList(), val acks: List<String> = emptyList(),
) {
    fun toJson() = linkedMapOf<String, Any?>(
        "token" to token, "seq" to seq, "sentAt" to sentAt, "vehicle" to vehicle.toJson(), "flow" to flow?.toJson(),
        "commands" to commands.map { it.toJson() }, "acks" to acks,
    )
    companion object {
        fun fromJson(o: JsonObj) = CarSyncRequest(
            o.str("token"), o.long("seq"), o.long("sentAt"), Vehicle.fromJson(o.obj("vehicle")), o.objOrNull("flow")?.let(FlowStatus::fromJson),
            o.listOrEmpty("commands").objs(Command::fromJson), o.listOrEmpty("acks").strings(),
        )
    }
}

/**
 * 차가 받는 응답. 판정 입력은 최상위 [watch](중계가 고른 최신 값)를 쓴다.
 * [watchDirectAgeMs] 는 워치가 중계에 직접 보낸 마지막 값의 나이(중계 시계, 직접 값이 없으면 null) — [WatchReach] 참고.
 */
data class CarSyncResponse(
    val serverTime: Long, val peer: PhonePeer?, val watch: Watch?, val commands: List<Command>,
    val watchDirectAgeMs: Long? = null,
    /** 해석하며 건너뛴 지시 id(모르는 type 등) — 직렬화하지 않는다. 받는 쪽은 ack 한다. */
    val skippedCommandIds: List<String> = emptyList(),
) {
    fun toJson(healthConsent: Boolean) = linkedMapOf<String, Any?>(
        "serverTime" to serverTime, "peer" to peer?.toJson(healthConsent), "watch" to watch?.toJson(healthConsent),
        "commands" to commands.map { it.toJson() },
    ).apply { watchDirectAgeMs?.let { put("watchDirectAgeMs", it) } }
    companion object {
        fun fromJson(o: JsonObj): CarSyncResponse {
            val (cmds, skipped) = o.listOrEmpty("commands").commandsSkipping()
            return CarSyncResponse(
                o.long("serverTime"), o.objOrNull("peer")?.let(PhonePeer::fromJson), Watch.fromJsonOrNull(o.map["watch"]),
                cmds, o.longOrNull("watchDirectAgeMs"), skipped,
            )
        }
    }
}

/**
 * [responseAt] 은 [PhonePeer] 와 같은 뜻(휴대폰 벽시계 · 변화 표시 전용) — 정차 중 휴대폰 화면 응답을 차의 상태 기계로 보낸다.
 * [carDisplay] 는 차 화면 표시 설정 — 중계가 차의 [PhonePeer.carDisplay] 로 그대로 넘긴다(계약 §4.1, 2026-11-01).
 */
data class PhoneSyncRequest(
    val token: String, val seq: Long, val sentAt: Long, val watch: Watch?, val flow: FlowStatus?,
    val commands: List<Command> = emptyList(), val acks: List<String> = emptyList(),
    val responseAt: Long? = null,
    val carDisplay: CarDisplay? = null,
    /**
     * 휴대폰에 지금 저장된 건강 관련 동의(계약 §4.1 · §8, 감사 하4). false 면 중계는 워치 직접 값을 저장하지 않고 차 응답 `watch` 를 null 로
     * 내린다. null 이면 싣지 않는다(옛 휴대폰과 같음 — 중계는 예전처럼 동작).
     */
    val healthConsent: Boolean? = null,
) {
    // 휴대폰 위치는 싣지 않는다 — 문자 본문에만 로컬로 쓴다(감사 중4 A 안, 계약 §8)
    fun toJson(healthConsent: Boolean) = linkedMapOf<String, Any?>(
        "token" to token, "seq" to seq, "sentAt" to sentAt, "watch" to watch?.toJson(healthConsent),
        "flow" to flow?.toJson(), "commands" to commands.map { it.toJson() }, "acks" to acks,
        "responseAt" to responseAt, "carDisplay" to carDisplay?.toJson(),
    ).apply { this@PhoneSyncRequest.healthConsent?.let { put("healthConsent", it) } }
    companion object {
        /** 옛 휴대폰이 보낸 `phoneLocation` 키는 읽지 않고 넘긴다(관용 해석, 계약 §4.1 · §4.6). */
        fun fromJson(o: JsonObj) = PhoneSyncRequest(
            o.str("token"), o.long("seq"), o.long("sentAt"), o.objOrNull("watch")?.let(Watch::fromJson),
            o.objOrNull("flow")?.let(FlowStatus::fromJson),
            o.listOrEmpty("commands").objs(Command::fromJson), o.listOrEmpty("acks").strings(),
            o.longOrNull("responseAt"), CarDisplay.fromJsonOrNull(o.map["carDisplay"]),
            o.map["healthConsent"] as? Boolean,
        )
    }
}

/**
 * [watchDirectAgeMs] 는 워치가 중계에 직접 보낸 마지막 값의 나이(중계 시계, 직접 값이 없으면 null). 고른 [watch] 는 더 최근 값이라
 * 직접 경로가 살아 있어도 `via=PHONE` 일 수 있으므로, 워치에 지시를 보낼 수 있는지는 이 값으로 판단한다([WatchReach.canCommandWatch]).
 */
data class PhoneSyncResponse(
    val serverTime: Long, val peer: CarPeer?, val watch: Watch?, val commands: List<Command>,
    val watchDirectAgeMs: Long? = null,
    /** 해석하며 건너뛴 지시 id(모르는 type 등) — 직렬화하지 않는다. 받는 쪽은 ack 한다. */
    val skippedCommandIds: List<String> = emptyList(),
) {
    fun toJson(healthConsent: Boolean) = linkedMapOf<String, Any?>(
        "serverTime" to serverTime, "peer" to peer?.toJson(), "watch" to watch?.toJson(healthConsent),
        "commands" to commands.map { it.toJson() },
    ).apply { watchDirectAgeMs?.let { put("watchDirectAgeMs", it) } }
    companion object {
        fun fromJson(o: JsonObj): PhoneSyncResponse {
            val (cmds, skipped) = o.listOrEmpty("commands").commandsSkipping()
            return PhoneSyncResponse(
                o.long("serverTime"), o.objOrNull("peer")?.let(CarPeer::fromJson), Watch.fromJsonOrNull(o.map["watch"]),
                cmds, o.longOrNull("watchDirectAgeMs"), skipped,
            )
        }
    }
}

/** 워치 직접 경로. 워치 직접 경로는 건강 관련 동의가 있을 때만 쓴다(계약 §8). */
data class WatchSyncRequest(
    val token: String, val seq: Long, val sentAt: Long, val watch: Watch,
    val acks: List<String> = emptyList(), val commands: List<Command> = emptyList(),
) {
    fun toJson(healthConsent: Boolean) = linkedMapOf<String, Any?>(
        "token" to token, "seq" to seq, "sentAt" to sentAt, "watch" to watch.toJson(healthConsent), "acks" to acks,
    ).apply { if (commands.isNotEmpty()) put("commands", commands.map { it.toJson() }) }
    companion object {
        fun fromJson(o: JsonObj) = WatchSyncRequest(
            o.str("token"), o.long("seq"), o.long("sentAt"), Watch.fromJson(o.obj("watch")),
            o.listOrEmpty("acks").strings(), o.listOrEmpty("commands").objs(Command::fromJson),
        )
    }
}

/** [skippedCommandIds] — 해석하며 건너뛴 지시 id(모르는 type 등). 직렬화하지 않는다. 워치는 ack 한다. */
data class WatchSyncResponse(val serverTime: Long, val commands: List<Command>, val skippedCommandIds: List<String> = emptyList()) {
    fun toJson() = linkedMapOf<String, Any?>("serverTime" to serverTime, "commands" to commands.map { it.toJson() })
    companion object {
        fun fromJson(o: JsonObj): WatchSyncResponse {
            val (cmds, skipped) = o.listOrEmpty("commands").commandsSkipping()
            return WatchSyncResponse(o.long("serverTime"), cmds, skipped)
        }
    }
}
