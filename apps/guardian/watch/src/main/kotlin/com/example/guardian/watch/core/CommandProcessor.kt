// 중계에서 받은 지시를 한 번만 실행하고 ack 를 모은다 — 워치가 하는 일은 ALERT_START · ALERT_STOP · TEST_SCENARIO · TEST_SCENARIO_STOP 네 가지
package com.example.guardian.watch.core

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.protocol.AlertStopReason
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload

/** 지시를 해석한 워치 동작. 경보 · 시나리오 실행은 이것을 받은 쪽(엔진 · 서비스)이 한다. */
sealed interface WatchAction {
    /** [eventId] 는 payload 에 사건 id 가 실려 왔을 때만(계약 §4.6 — 없으면 옛 지시 규칙). */
    data class StartAlert(val commandId: String, val alert: Payload.AlertStart, val test: Boolean, val eventId: String? = null) : WatchAction
    data class StopAlert(val reason: AlertStopReason, val eventId: String? = null) : WatchAction
    data class RunScenario(val scenario: Scenario, val durationMs: Long) : WatchAction
    /** 계약 §4.6 TEST_SCENARIO_STOP — 시뮬레이션을 끝내고 실제 센서로. 진행 중인 시나리오가 없으면 아무것도 하지 않는다. */
    data object StopScenario : WatchAction
}

/**
 * 계약 §4.6 — 지시는 「한 번 이상」 온다. 처리한 id 를 [memory] 개 기억해 두 번 실행하지 않는다.
 * 처리했거나 무시하기로 한 지시는 모두 ack 한다(ack 전까지 중계가 다시 보내므로, 이미 본 id 가 다시 와도 다시 ack 에 싣는다).
 * 형식이 틀린 payload · 워치가 할 일이 없는 종류 · 대상이 워치가 아닌 지시는 실행하지 않고 ack 만 한다.
 * 보낸 쪽도 본다(합동 점검 D-1) — 경보 시작 · 정지는 차 · 휴대폰, 테스트 시나리오 · 멈춤은 휴대폰에서 온 것만 실행하고 그 밖은 ack 만.
 */
class CommandProcessor(private val memory: Int = 256) {
    private val seen = LinkedHashSet<String>()
    private val pending = LinkedHashSet<String>()

    fun accept(commands: List<Command>): List<WatchAction> = commands.mapNotNull { c ->
        pending += c.id
        if (!seen.add(c.id)) return@mapNotNull null
        while (seen.size > memory) seen.remove(seen.first())
        toAction(c)
    }

    /**
     * 응답 해석에서 건너뛴 지시(모르는 type · 형식이 틀린 지시, 계약 §4.6 「건너뛰고 ack」)를 실행 없이 ack 에 싣는다.
     * ack 하지 않으면 중계가 ttl 동안 같은 지시를 계속 다시 보낸다.
     */
    fun ackOnly(ids: Collection<String>) {
        pending += ids
    }

    /** 다음 sync 에 실을 ack. */
    fun acks(): List<String> = pending.toList()

    /** 중계가 받은(200) sync 에 실었던 ack 를 지운다. */
    fun confirm(sent: Collection<String>) {
        pending.removeAll(sent.toSet())
    }

    private fun toAction(c: Command): WatchAction? {
        if (c.resolvedTo != CommandTo.WATCH && c.resolvedTo != CommandTo.ALL) return null
        val p = try {
            Payload.fromJson(c.type, c.payload)
        } catch (e: RuntimeException) { // JsonException · 모델 require 실패 — 지시 하나 때문에 sync 를 멈추지 않는다
            return null
        }
        return when (p) {
            is Payload.AlertStart -> if (c.from in ALERT_FROM) WatchAction.StartAlert(c.id, p, c.test, p.eventId) else null
            is Payload.AlertStop -> if (c.from in ALERT_FROM) WatchAction.StopAlert(p.reason, p.eventId) else null
            is Payload.TestScenario -> if (c.from in SCENARIO_FROM) WatchAction.RunScenario(p.scenario, p.durationMs) else null
            is Payload.TestScenarioStop -> if (c.from in SCENARIO_FROM) WatchAction.StopScenario else null
            else -> null
        }
    }

    private companion object {
        /** 경보를 내고 거두는 쪽은 흐름의 주인(차 · 휴대폰)뿐이다. */
        val ALERT_FROM = setOf(Party.CAR, Party.PHONE)

        /** 테스트 시나리오는 휴대폰 「테스트」 화면에서만 시작 · 멈춘다(계약 §4.6). */
        val SCENARIO_FROM = setOf(Party.PHONE)
    }
}
