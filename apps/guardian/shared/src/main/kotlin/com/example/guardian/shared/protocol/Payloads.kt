// 지시 12종의 payload 모델과 상태 기계 지시 → 프로토콜 지시 변환(계약 §4.6 예를 기준으로 확정)
package com.example.guardian.shared.protocol

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.AlertTarget
import com.example.guardian.shared.flow.AmbientMode
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.GuardianSmsKind
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.text.Phrase

enum class AlertStopReason { RESPONDED, CLEARED, PAIR_END }
enum class GuardianSmsWireKind { EVENT, CANCEL, ACCIDENT, OBSERVE }
enum class TestAction { START, STOP }

sealed interface Payload {
    val type: CommandType
    fun toJson(): Map<String, Any?>

    /** [durationMs] 는 단계 표시용이다. 받는 쪽은 시간이 지나도 멈추지 않고 ALERT_STOP 이 올 때까지 경보를 계속한다. */
    /** [eventId] 선택(계약 §4.6, 2026-11-14) — 없으면 싣지 않고 받는 쪽은 지금 규칙. */
    data class AlertStart(
        val stage: FlowState, val level: Int, val vibrate: Boolean, val sound: Boolean, val maxVolume: Boolean, val durationMs: Long,
        val eventId: String? = null,
    ) : Payload {
        override val type get() = CommandType.ALERT_START
        override fun toJson() = linkedMapOf<String, Any?>(
            "stage" to stage.name, "level" to level, "vibrate" to vibrate, "sound" to sound, "maxVolume" to maxVolume, "durationMs" to durationMs,
        ).apply { eventId?.let { put("eventId", it) } }
    }

    /** [eventId] 선택 — 워치는 같은 사건 id 의 정지만 듣는다(없으면 지금처럼 끈다). */
    data class AlertStop(val reason: AlertStopReason, val eventId: String? = null) : Payload {
        override val type get() = CommandType.ALERT_STOP
        override fun toJson() = linkedMapOf<String, Any?>("reason" to reason.name).apply { eventId?.let { put("eventId", it) } }
    }

    data class Speak(val text: String, val lang: String = "ko-KR") : Payload {
        override val type get() = CommandType.SPEAK
        override fun toJson() = linkedMapOf<String, Any?>("text" to text, "lang" to lang)
    }

    /** 주행 중 한 줄 · 버튼 없음, 정지 때만 버튼 하나(G5). */
    data class Show(val line: String, val button: String?) : Payload {
        override val type get() = CommandType.SHOW
        override fun toJson() = linkedMapOf<String, Any?>("line" to line, "button" to button)
    }

    /** 켤 때 mode, 끌 때 on=false · mode=null. BLUE_RED_BLINK 는 법률 검토 전 만들 수 없다(설계 §13). */
    data class Ambient(val on: Boolean, val mode: AmbientMode?) : Payload {
        init {
            require(mode != AmbientMode.BLUE_RED_BLINK || ALLOW_BLUE_RED_BLINK) { "BLUE_RED_BLINK 는 법률 검토 전 금지" }
            require(on == (mode != null)) { "켤 때만 mode" }
        }
        override val type get() = CommandType.AMBIENT
        override fun toJson() = linkedMapOf<String, Any?>("on" to on, "mode" to mode?.name)
        companion object { const val ALLOW_BLUE_RED_BLINK = false }
    }

    data class OpenSearch(val query: String) : Payload {
        override val type get() = CommandType.OPEN_SEARCH
        override fun toJson() = linkedMapOf<String, Any?>("query" to query)
    }

    /**
     * 반자동 119 작성 — 사건 사실만 싣는다(본문 없음, 개인정보 없음). 휴대폰이 등록 정보로 본문을 만든다
     * ([com.example.guardian.shared.report.IncidentComposer]). 휴대폰은 정차 뒤 무응답 전체 화면을 띄우고,
     * 문자 앱(`ACTION_SENDTO smsto:119`)은 사람이 버튼을 눌렀을 때만 연다(G4). [test] 면 늘 [previewOnly].
     */
    data class Open119Compose(val eventId: String, val facts: WireFacts, val previewOnly: Boolean, val test: Boolean) : Payload {
        override val type get() = CommandType.OPEN_119_COMPOSE
        override fun toJson() = linkedMapOf<String, Any?>("eventId" to eventId).apply {
            putAll(facts.toJson()); put("previewOnly", previewOnly); put("test", test)
        }
    }

    /** 보호자 문자 — 사건 사실만 싣는다(본문 없음, 개인정보 없음). 본문은 휴대폰이 만든다. */
    data class SendGuardianSms(val kind: GuardianSmsWireKind, val eventId: String, val facts: WireFacts, val test: Boolean) : Payload {
        override val type get() = CommandType.SEND_GUARDIAN_SMS
        override fun toJson() = linkedMapOf<String, Any?>("kind" to kind.name, "eventId" to eventId).apply {
            putAll(facts.toJson()); put("test", test)
        }
    }

    data class TestScenario(val scenario: Scenario, val durationMs: Long) : Payload {
        override val type get() = CommandType.TEST_SCENARIO
        override fun toJson() = linkedMapOf<String, Any?>("scenario" to scenario.name, "durationMs" to durationMs)
    }

    /** 워치 테스트 시나리오 멈춤 — 워치는 시뮬레이션을 끝내고 실제 센서로 돌아간다. payload 는 빈 객체다. */
    object TestScenarioStop : Payload {
        override val type get() = CommandType.TEST_SCENARIO_STOP
        override fun toJson() = linkedMapOf<String, Any?>()
        override fun toString() = "TestScenarioStop"
    }

    data class FlowSnapshot(val flow: FlowStatus) : Payload {
        override val type get() = CommandType.FLOW_SNAPSHOT
        override fun toJson() = linkedMapOf<String, Any?>("flow" to flow.toJson())
    }

    /**
     * 차 → 휴대폰 테스트 요청(계약 §4.6 「차에서 테스트 요청」, 2026-11-01). 시작은 [START](`PULSE_LOST` · 120초 고정 — 휴대폰 홈 테스트 길이와 같다), 멈춤은 [STOP] 둘뿐이다.
     * 다른 시나리오 · 길이는 만들 수 없다 — 받은 값이 다르면 형식 오류(받는 쪽은 실행하지 않고 ack).
     */
    data class TestRequest(val action: TestAction, val scenario: Scenario? = null, val durationMs: Long? = null) : Payload {
        init {
            if (action == TestAction.START) require(scenario == START_SCENARIO && durationMs == START_DURATION_MS) { "시작은 PULSE_LOST · 120초만" }
            else require(scenario == null && durationMs == null) { "멈춤은 action 만" }
        }
        override val type get() = CommandType.TEST_REQUEST
        override fun toJson() = linkedMapOf<String, Any?>("action" to action.name).apply {
            scenario?.let { put("scenario", it.name) }
            durationMs?.let { put("durationMs", it) }
        }

        companion object {
            val START_SCENARIO = Scenario.PULSE_LOST
            const val START_DURATION_MS = 120_000L
            val START = TestRequest(TestAction.START, START_SCENARIO, START_DURATION_MS)
            val STOP = TestRequest(TestAction.STOP)
        }
    }

    companion object {
        fun fromJson(type: CommandType, payload: Map<String, Any?>): Payload {
            val o = JsonObj(payload)
            return when (type) {
                CommandType.ALERT_START -> AlertStart(
                    o.enum("stage"), o.int("level"), o.bool("vibrate"), o.bool("sound"), o.bool("maxVolume"), o.long("durationMs"),
                    o.strOrNull("eventId")?.takeIf { it.isNotEmpty() },
                )
                CommandType.ALERT_STOP -> AlertStop(o.enum("reason"), o.strOrNull("eventId")?.takeIf { it.isNotEmpty() })
                CommandType.SPEAK -> Speak(o.str("text"), o.strOrNull("lang") ?: "ko-KR")
                CommandType.SHOW -> Show(o.str("line"), o.strOrNull("button"))
                CommandType.AMBIENT -> Ambient(o.bool("on"), o.enumOrNull<AmbientMode>("mode"))
                CommandType.OPEN_SEARCH -> OpenSearch(o.str("query"))
                // 받는 쪽에서도 테스트 119 는 미리보기만(G4 방어 — 보내는 쪽 실수에도 보내기 경로를 열지 않는다)
                CommandType.OPEN_119_COMPOSE -> o.bool("test").let { test ->
                    Open119Compose(o.str("eventId"), WireFacts.fromJson(o), o.bool("previewOnly") || test, test)
                }
                CommandType.SEND_GUARDIAN_SMS -> SendGuardianSms(o.enum("kind"), o.str("eventId"), WireFacts.fromJson(o), o.bool("test"))
                CommandType.TEST_SCENARIO -> TestScenario(o.enum("scenario"), o.long("durationMs"))
                CommandType.TEST_SCENARIO_STOP -> TestScenarioStop
                CommandType.FLOW_SNAPSHOT -> FlowSnapshot(FlowStatus.fromJson(o.obj("flow")))
                // 멈춤은 action 만 읽는다(다른 필드는 보지 않음). 시작은 시나리오 · 길이가 고정값이 아니면 require 실패(형식 오류)
                CommandType.TEST_REQUEST -> when (o.enum<TestAction>("action")) {
                    TestAction.START -> TestRequest(TestAction.START, o.enum<Scenario>("scenario"), o.long("durationMs"))
                    TestAction.STOP -> TestRequest.STOP
                }
            }
        }
    }
}

/**
 * 문자 지시(보호자 문자 · 119 작성)에 싣는 사건 사실. 이름 · 번호 같은 등록 정보는 싣지 않는다(G2).
 *
 * 시각 규칙은 주인 넘김 스냅숏과 같다(계약 §2 · §6) — 보낸 쪽에 시계 보정값이 있으면 중계 시각([SnapshotClock.SERVER]),
 * 없으면 보낸 기기의 단조 시각([SnapshotClock.LOCAL]). 받는 쪽은 [toLocal] 로 자기 단조 시각으로 되돌린다.
 */
data class WireFacts(
    val clock: SnapshotClock,
    val firstAlertAt: Long? = null,
    val stoppedAt: Long? = null,
    val noResponseAt: Long? = null,
    val responseAt: Long? = null,
    val accidentAt: Long? = null,
    val carStateMissing: Boolean = false,
) {
    fun toJson() = linkedMapOf<String, Any?>(
        "clock" to clock.name, "firstAlertAt" to firstAlertAt, "stoppedAt" to stoppedAt, "noResponseAt" to noResponseAt,
        "responseAt" to responseAt, "accidentAt" to accidentAt, "carStateMissing" to carStateMissing,
    )

    /**
     * 받은 사실을 내 단조 시계로 되돌린다. [eventId] 는 그대로 싣는다. 예외를 내지 않는다.
     * - SERVER + 내 [offsetMs] 있음 → 정확히 되돌린다.
     * - LOCAL + [sameDevice](내가 나에게 낸 지시) → 그대로.
     * - 그 밖(보정값 없음 · 다른 기기의 LOCAL) → 「시계 미확인」. 가장 늦은 시각을 [nowLocalMs] 에 맞춘다(지시는 바로 전달되므로 근사).
     */
    fun toLocal(eventId: String?, offsetMs: Long?, nowLocalMs: Long, sameDevice: Boolean): RestoredFacts {
        val times = listOfNotNull(firstAlertAt, stoppedAt, noResponseAt, responseAt, accidentAt)
        val (delta, verified) = when {
            clock == SnapshotClock.SERVER && offsetMs != null -> -offsetMs to true
            clock == SnapshotClock.LOCAL && sameDevice -> 0L to true
            else -> (times.maxOrNull()?.let { nowLocalMs - it } ?: 0L) to false
        }
        fun t(v: Long?) = v?.plus(delta)
        return RestoredFacts(
            IncidentFacts(t(firstAlertAt), t(stoppedAt), t(noResponseAt), t(responseAt), carStateMissing, t(accidentAt), eventId),
            verified,
        )
    }

    companion object {
        /** 내 단조 시각의 사실 → 지시에 실을 사실. [offsetMs] 가 있으면 중계 시각으로 바꾼다. */
        fun of(f: IncidentFacts, offsetMs: Long?): WireFacts {
            fun t(v: Long?) = if (offsetMs == null) v else v?.plus(offsetMs)
            return WireFacts(
                if (offsetMs == null) SnapshotClock.LOCAL else SnapshotClock.SERVER,
                t(f.firstAlertAtMs), t(f.stoppedAtMs), t(f.noResponseAtMs), t(f.responseAtMs), t(f.accidentAtMs), f.carStateMissing,
            )
        }

        fun fromJson(o: JsonObj) = WireFacts(
            o.enum("clock"), o.longOrNull("firstAlertAt"), o.longOrNull("stoppedAt"), o.longOrNull("noResponseAt"),
            o.longOrNull("responseAt"), o.longOrNull("accidentAt"), o.bool("carStateMissing"),
        )
    }
}

/** 되돌린 사건 사실. [clockVerified] 가 false 면 시각이 근사다(화면 · 기록에 「시계 미확인」). */
data class RestoredFacts(val facts: IncidentFacts, val clockVerified: Boolean)

/**
 * 주인(차 또는 휴대폰)이 낸 상태 기계 지시를 프로토콜 지시로 바꾼다. 주인이 누구든 같은 지시가 나간다.
 * 문자 지시(보호자 문자 · 119 작성)는 사건 사실만 싣고, 본문은 휴대폰이 등록 정보로 만든다(PM 결정 2026-10-30, G2).
 * 테스트 지시의 119 작성은 늘 미리보기(previewOnly)다.
 *
 * 휴대폰이 주인이면 문자 지시의 받는 쪽이 자기 자신(PHONE)이다. 이런 지시는 중계로 보내지 않고([splitLocal])
 * 받은 지시와 같은 처리 함수로 바로 처리한다(중계는 자기 자신 대상 지시를 400 으로 거른다).
 */
object DirectiveCommands {
    /** 정지 화면 — 버튼 「괜찮아요」 하나를 둔다. 나머지는 버튼 없음. */
    private val stoppedScreens = setOf(Phrase.SCREEN_AP, Phrase.SCREEN_S2, Phrase.SCREEN_S4)

    /** 차와 휴대폰 둘 다에 띄우는 안내. */
    private val carAndPhone = setOf(Phrase.SCREEN_WATCH_NO_SIGNAL, Phrase.SCREEN_CHECK_WEAR)

    /** [offsetMs] 는 주인 기기의 시계 보정값(중계 − 단조, [ClockOffset.offsetAt]). 없으면 null — 문자 지시 시각이 LOCAL 로 실린다. */
    fun toCommands(
        d: Directive,
        from: Party,
        newId: () -> String,
        ttlMs: Long,
        offsetMs: Long?,
    ): List<Command> {
        fun cmd(p: Payload, to: CommandTo) = Command(newId(), p.type, p.toJson(), ttlMs, d.test, from, to)
        return when (d) {
            is Directive.AlertStart -> buildList {
                val vibrate = AlertTarget.WATCH_VIBRATION in d.targets
                val watchSound = AlertTarget.WATCH_SOUND in d.targets
                if (vibrate || watchSound) add(cmd(Payload.AlertStart(d.stage, d.level, vibrate, watchSound, watchSound, d.durationMs, d.eventId), CommandTo.WATCH))
                if (AlertTarget.PHONE_ALARM in d.targets) add(cmd(Payload.AlertStart(d.stage, d.level, false, true, true, d.durationMs, d.eventId), CommandTo.PHONE))
            }
            is Directive.AlertStop -> listOf(cmd(Payload.AlertStop(AlertStopReason.CLEARED, d.eventId), CommandTo.ALL))
            is Directive.Speak -> listOf(cmd(Payload.Speak(d.phrase.ko), CommandTo.CAR))
            is Directive.Show -> {
                val p = Payload.Show(d.phrase.ko, if (d.phrase in stoppedScreens) Phrase.BUTTON_OK.ko else null)
                if (d.phrase in carAndPhone) listOf(cmd(p, CommandTo.CAR), cmd(p, CommandTo.PHONE)) else listOf(cmd(p, CommandTo.CAR))
            }
            is Directive.Ambient -> listOf(cmd(Payload.Ambient(d.on, d.mode), CommandTo.CAR))
            is Directive.OpenSearch -> listOf(cmd(Payload.OpenSearch(d.phrase.ko), CommandTo.CAR))
            is Directive.Open119Compose -> listOf(cmd(
                Payload.Open119Compose(eventId(GuardianSmsKind.INCIDENT, d.facts), WireFacts.of(d.facts, offsetMs), d.previewOnly || d.test, d.test),
                CommandTo.PHONE,
            ))
            is Directive.SendGuardianSms -> listOf(cmd(
                Payload.SendGuardianSms(wireKind(d.kind), eventId(d), WireFacts.of(d.facts, offsetMs), d.test),
                CommandTo.PHONE,
            ))
        }
    }

    /** (자기에게 낸 지시, 중계로 보낼 지시). 자기에게 낸 지시는 바로 처리한다. */
    fun splitLocal(commands: List<Command>, self: Party): Pair<List<Command>, List<Command>> =
        commands.partition { it.resolvedTo.name == self.name }

    fun wireKind(k: GuardianSmsKind) = when (k) {
        GuardianSmsKind.INCIDENT -> GuardianSmsWireKind.EVENT
        GuardianSmsKind.CANCEL -> GuardianSmsWireKind.CANCEL
        GuardianSmsKind.ACCIDENT -> GuardianSmsWireKind.ACCIDENT
        GuardianSmsKind.OBSERVING -> GuardianSmsWireKind.OBSERVE
    }

    /**
     * 사건 id — 상태 기계가 사건 시작 때 만든 문자열([com.example.guardian.shared.flow.IncidentFacts.eventId])을 그대로 쓴다.
     * 주인이 바뀌어도 같은 사건은 같은 id 라 휴대폰이 중복 발송을 막는 데 쓴다.
     */
    fun eventId(d: Directive.SendGuardianSms): String = eventId(d.kind, d.facts)

    fun eventId(kind: GuardianSmsKind, f: IncidentFacts): String =
        f.eventId ?: if (kind == GuardianSmsKind.ACCIDENT) "accident-L${f.accidentAtMs ?: 0}" else "event-L${f.firstAlertAtMs ?: 0}"
}
