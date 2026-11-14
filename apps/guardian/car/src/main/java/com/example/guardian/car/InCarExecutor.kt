// 차 안 지시 실행기 — SPEAK · SHOW · OPEN_SEARCH · AMBIENT 를 주행 규칙(G5 · G6)에 맞춰 실행하고 화면 한 줄을 정한다
package com.example.guardian.car

import com.example.guardian.shared.flow.AmbientMode
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.text.Phrase

/** 실제 장치에 닿는 출구. 앱은 Pleos TTS · NaviHelper · Vehicle Light 로, 시험은 기록용 가짜로 채운다. */
interface CarPorts {
    fun speak(text: String)
    fun openSearch(query: String)
    /** [AmbientDecision.Apply] · [AmbientDecision.Off] 만 들어온다. */
    fun ambient(decision: AmbientDecision)
}

/** 지시 하나의 처리 결과(기록 · 시험용). */
enum class Outcome {
    SPOKEN, SHOWN, SEARCH_OPENED,
    /** 주행 중이라 내비 검색 화면을 열지 않았다(정지 뒤만). */
    SEARCH_REFUSED_DRIVING,
    AMBIENT_APPLIED,
    /** 앰비언트 기능이 꺼져 있어 조명을 건드리지 않았다(기본). */
    AMBIENT_DISABLED,
    /** 금지 모드(BLUE_RED_BLINK)이거나 payload 가 틀려 실행하지 않았다. */
    REJECTED,
    /** 차가 실행할 지시가 아니다(ALERT_START 등). */
    NOT_FOR_CAR,
}

/** 차 화면에 올린 한 줄 — 올린 때의 상태를 함께 둔다(상태가 바뀌면 상태 기본 문구로 돌아간다). */
data class ShownLine(val line: String, val button: String?, val state: FlowState)

/** 화면에 그릴 것. [button] 은 이미 주행 규칙을 거친 값이다(주행 중이면 늘 null). */
data class ScreenView(val line: String?, val button: String?, val test: Boolean)

class InCarExecutor(private val ports: CarPorts, private val ambientEnabled: Boolean = AmbientPolicy.ENABLED) {
    var lastShow: ShownLine? = null
        private set

    /**
     * 프로토콜 payload 하나를 실행한다. [state] 는 지금 상태 기계 상태, [touchOk] 는 지금 정지 화면을 만져도 되는지(디바운스 확정 정지 + 현재 신호 정지 + UX 제한 없음).
     */
    fun execute(p: Payload, state: FlowState, touchOk: Boolean): Outcome = when (p) {
        is Payload.Speak -> { ports.speak(p.text); Outcome.SPOKEN }
        is Payload.Show -> { lastShow = ShownLine(p.line, p.button, state); Outcome.SHOWN }
        is Payload.OpenSearch ->
            if (touchOk) { ports.openSearch(p.query); Outcome.SEARCH_OPENED } else Outcome.SEARCH_REFUSED_DRIVING
        is Payload.Ambient -> ambient(p.mode)
        else -> Outcome.NOT_FOR_CAR
    }

    /** 앰비언트 어댑터 입구. BLUE_RED_BLINK 는 기능 스위치와 무관하게 거부한다. */
    fun ambient(mode: AmbientMode?): Outcome = when (val d = AmbientPolicy.decide(mode, ambientEnabled)) {
        AmbientDecision.Rejected -> Outcome.REJECTED
        AmbientDecision.Disabled -> Outcome.AMBIENT_DISABLED
        else -> { ports.ambient(d); Outcome.AMBIENT_APPLIED }
    }

    /**
     * 중계로 받은 지시의 payload 를 읽는다. 금지 모드 · 틀린 payload 는 null(실행하지 않고 ack 만).
     * 금지 모드는 :shared 모델이 만들지 못하게 막지만, 원문에서 먼저 한 번 더 거른다.
     */
    fun parseRemote(c: Command): Payload? {
        if (c.type == CommandType.AMBIENT && AmbientPolicy.isForbiddenRaw(c.payload["mode"])) return null
        return runCatching { Payload.fromJson(c.type, c.payload) }.getOrNull()
    }

    /** 상태 기계를 넘겨받아 지난 화면이 맞지 않을 때 — 상태 기본 문구로 돌아가게 지운다. */
    fun clearShow() { lastShow = null }

    companion object {
        /** 정지 화면(AP · S2 · S4)만 「괜찮아요」 버튼 하나(G5 · :shared DirectiveCommands 와 같음). */
        private val STOPPED_SCREENS = setOf(FlowState.AP, FlowState.S2, FlowState.S4)

        /** 상태마다 기본 문구 — 넘겨받았거나 지시 없이 상태가 바뀌었을 때 쓴다. 감시(S0) · 꺼짐은 경보 줄이 없다. */
        fun defaultLine(state: FlowState): Phrase? = when (state) {
            FlowState.OFF, FlowState.S0 -> null
            FlowState.S1 -> Phrase.SCREEN_S1
            FlowState.S1R -> Phrase.SCREEN_S1R
            FlowState.AP -> Phrase.SCREEN_AP
            FlowState.S2 -> Phrase.SCREEN_S2
            FlowState.S3 -> Phrase.SCREEN_S3
            FlowState.S4 -> Phrase.SCREEN_S4
            FlowState.S5 -> Phrase.SCREEN_S5
        }

        /**
         * 화면 한 줄과 버튼. 버튼은 정지 화면이고 [touchOk] 일 때만 — 주행 중 · UX 제한 중에는 한 줄 · 터치 0(G5).
         * 지난 SHOW 는 같은 상태일 때만 쓰고, 안내(워치 신호 없음 · 착용 확인)는 조건이 풀리면 거둔다.
         */
        fun screen(snap: FlowSnapshot, last: ShownLine?, touchOk: Boolean): ScreenView {
            val shown = last?.takeIf { it.state == snap.state }?.takeUnless {
                (it.line == Phrase.SCREEN_WATCH_NO_SIGNAL.ko && !snap.noSignalNotified) ||
                    (it.line == Phrase.SCREEN_CHECK_WEAR.ko && snap.worn)
            }
            val line = shown?.line ?: defaultLine(snap.state)?.ko
            val button = (shown?.button ?: Phrase.BUTTON_OK.ko.takeIf { shown == null && snap.state in STOPPED_SCREENS })
                ?.takeIf { touchOk && snap.state in STOPPED_SCREENS }
            return ScreenView(line, button, snap.test)
        }
    }
}
