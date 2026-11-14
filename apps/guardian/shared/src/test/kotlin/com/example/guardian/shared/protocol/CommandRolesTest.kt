// 지시 허용 표 대조(합동 점검 D-1) — 상태 기계 지시를 차 · 휴대폰이 중계로 보낼 때 나오는 지시가 모두 공용 표 contracts/guardian/command-roles.json 안인지
package com.example.guardian.shared.protocol

import com.example.guardian.shared.flow.AlertTarget
import com.example.guardian.shared.flow.AmbientMode
import com.example.guardian.shared.flow.Directive
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.GuardianSmsKind
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandRolesTest {
    @Suppress("UNCHECKED_CAST")
    private val table: Map<String, Map<String, List<String>>> by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "contracts/guardian/command-roles.json").exists()) dir = dir.parentFile
        Json.parseObject(File(dir!!, "contracts/guardian/command-roles.json").readText())["allow"] as Map<String, Map<String, List<String>>>
    }

    private val directives = listOf(
        Directive.AlertStart(setOf(AlertTarget.WATCH_VIBRATION), FlowState.S1, 1, 15_000, false, "e"),
        Directive.AlertStart(setOf(AlertTarget.WATCH_SOUND, AlertTarget.PHONE_ALARM), FlowState.AP, 2, 20_000, false, "e"),
        Directive.AlertStop(false, "e"),
        Directive.Speak(Phrase.VOICE_S1_PULL_OVER, false),
        Directive.Show(Phrase.SCREEN_S1, false),
        Directive.Show(Phrase.SCREEN_CHECK_WEAR, false),
        Directive.Ambient(AmbientMode.INTERIOR_ALERT, false),
        Directive.OpenSearch(Phrase.SEARCH_EMERGENCY_ROOM, false),
        Directive.Open119Compose(IncidentFacts(eventId = "e"), previewOnly = false, test = false),
        Directive.SendGuardianSms(GuardianSmsKind.INCIDENT, IncidentFacts(eventId = "e"), false),
    )

    @Test fun everyRelayedDirectiveCommandIsInSharedTable() {
        for (owner in listOf(Party.CAR, Party.PHONE)) for (d in directives) {
            val cmds = DirectiveCommands.toCommands(d, owner, { "id" }, 30_000, null)
            val (_, relayed) = DirectiveCommands.splitLocal(cmds, owner)
            for (c in relayed) {
                val to = c.resolvedTo.name
                val allowed = table[owner.name]?.get(c.type.name).orEmpty()
                assertTrue("$owner ${c.type} → $to 가 허용 표 밖", to in allowed)
            }
        }
    }
}
