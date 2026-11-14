// 상태 기계 스냅숏 JSON 변환 — 프로토콜 Flow.snapshot 에 실어 주인을 넘긴다
package com.example.guardian.shared.protocol

import com.example.guardian.shared.flow.AmbientMode
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.IncidentFacts
import com.example.guardian.shared.report.Consents

object SnapshotCodec {
    fun toJson(s: FlowSnapshot, clock: SnapshotClock = SnapshotClock.LOCAL) = linkedMapOf<String, Any?>(
        "clock" to clock.name,
        "state" to s.state.name, "sinceMs" to s.sinceMs, "test" to s.test, "powerOn" to s.powerOn,
        "consents" to linkedMapOf<String, Any?>(
            "emergencyInfo" to s.consents.emergencyInfo, "health" to s.consents.health, "location" to s.consents.location,
        ),
        "worn" to s.worn, "verdict" to s.verdict.name, "stopped" to s.stopped, "rawStopped" to s.rawStopped,
        "stopPendingSinceMs" to s.stopPendingSinceMs, "nullSpeedCount" to s.nullSpeedCount, "airbag" to s.airbag,
        "stage" to s.stage, "alertStartMs" to s.alertStartMs, "alerting" to s.alerting,
        "incidentActive" to s.incidentActive,
        "incidentSmsSent" to s.incidentSmsSent, "cancelSent" to s.cancelSent, "observingSent" to s.observingSent,
        "accidentSent" to s.accidentSent, "ambientMode" to s.ambientMode?.name, "watchSeenAtMs" to s.watchSeenAtMs,
        "noSignalNotified" to s.noSignalNotified, "weakSinceMs" to s.weakSinceMs,
        "realertLevel" to s.realertLevel, "normalSinceMs" to s.normalSinceMs, "unwornSinceMs" to s.unwornSinceMs,
        "lastNormalAtMs" to s.lastNormalAtMs,
        "composeOpened" to s.composeOpened, "serverOffsetMs" to s.serverOffsetMs, "clockUnverified" to s.clockUnverified,
        "facts" to linkedMapOf<String, Any?>(
            "firstAlertAtMs" to s.facts.firstAlertAtMs, "stoppedAtMs" to s.facts.stoppedAtMs,
            "noResponseAtMs" to s.facts.noResponseAtMs, "responseAtMs" to s.facts.responseAtMs,
            "carStateMissing" to s.facts.carStateMissing, "accidentAtMs" to s.facts.accidentAtMs,
            "eventId" to s.facts.eventId,
        ),
    )

    fun fromJson(o: JsonObj): FlowSnapshot {
        val c = o.obj("consents")
        val f = o.obj("facts")
        return FlowSnapshot(
            state = o.enum("state"), sinceMs = o.long("sinceMs"), test = o.bool("test"), powerOn = o.bool("powerOn"),
            consents = Consents(c.bool("emergencyInfo"), c.bool("health"), c.bool("location")),
            worn = o.bool("worn"), verdict = o.enum("verdict"), stopped = o.bool("stopped"), rawStopped = o.boolOrNull("rawStopped"),
            stopPendingSinceMs = o.longOrNull("stopPendingSinceMs"), nullSpeedCount = o.int("nullSpeedCount"), airbag = o.bool("airbag"),
            stage = o.int("stage"), alertStartMs = o.long("alertStartMs"), alerting = o.bool("alerting"),
            incidentActive = o.bool("incidentActive"),
            incidentSmsSent = o.bool("incidentSmsSent"), cancelSent = o.bool("cancelSent"), observingSent = o.bool("observingSent"),
            accidentSent = o.bool("accidentSent"), ambientMode = o.enumOrNull<AmbientMode>("ambientMode"),
            watchSeenAtMs = o.longOrNull("watchSeenAtMs"), noSignalNotified = o.bool("noSignalNotified"),
            weakSinceMs = o.longOrNull("weakSinceMs"),
            realertLevel = o.int("realertLevel"), normalSinceMs = o.longOrNull("normalSinceMs"),
            unwornSinceMs = o.longOrNull("unwornSinceMs"), // 없으면(이전 판 스냅숏) null — 다음 미착용 판정부터 센다
            lastNormalAtMs = o.longOrNull("lastNormalAtMs"), // 없으면 null — 재경보의 8초 제한 없이 지금 규칙
            composeOpened = o.bool("composeOpened"), serverOffsetMs = o.longOrNull("serverOffsetMs"),
            clockUnverified = o.bool("clockUnverified"),
            facts = IncidentFacts(
                f.longOrNull("firstAlertAtMs"), f.longOrNull("stoppedAtMs"), f.longOrNull("noResponseAtMs"),
                f.longOrNull("responseAtMs"), f.bool("carStateMissing"), f.longOrNull("accidentAtMs"),
                f.strOrNull("eventId"),
            ),
        )
    }
}
