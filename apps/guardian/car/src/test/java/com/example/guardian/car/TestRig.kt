// 차 허브 시험 도구 — 가짜 출구(음성 · 검색 · 조명 기록)와 중계 응답을 만드는 작은 무대
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CarSyncRequest
import com.example.guardian.shared.protocol.CarSyncResponse
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.PhonePeer
import com.example.guardian.shared.protocol.Via
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.report.Consents

class FakePorts : CarPorts {
    val spoken = ArrayList<String>()
    val searches = ArrayList<String>()
    val ambient = ArrayList<AmbientDecision>()
    override fun speak(text: String) { spoken += text }
    override fun openSearch(query: String) { searches += query }
    override fun ambient(decision: AmbientDecision) { ambient += decision }
}

/** 주행 중 UX 제한 — DO 요구 · NO_SETUP. 정차(PARKED 기준선)는 제한 없음. */
val UX_DRIVING = UxGate(requiresDo = true, noSetup = true)
val UX_PARKED = UxGate(requiresDo = false, noSetup = false)
val MOVING = CarReading(speedMps = 16.7f, gearRaw = VehicleMapping.GEAR_DRIVE, ux = UX_DRIVING)
val PARKED = CarReading(speedMps = 0f, gearRaw = VehicleMapping.GEAR_PARK, ux = UX_PARKED)

fun watch(verdict: WristVerdict, ageMs: Long? = 0, responseAt: Long? = null, test: Boolean = false, noSignal: Boolean = false) =
    Watch(verdict, null, null, true, WatchSource.SIMULATION, test, responseAt, Via.WATCH, 0, ageMs, noSignal)

/** 휴대폰이 보내는 설정(켬 · 동의 3종 · 테스트). 차가 주인일 때 휴대폰 흐름은 owner CAR, 아니면 PHONE 이다([Rig.sync] 기본값). */
fun settings(power: Boolean = true, test: Boolean = false, owner: Owner = Owner.CAR, localTest: Boolean = false) =
    FlowStatus.of(FlowSnapshot(powerOn = power, consents = Consents(true, true, true), test = test), owner).copy(localTest = localTest)

/**
 * 차 단조 시계 = 중계 시계 − [skew]. sync 는 보낸 뒤 100ms 에 받고, 중계는 그 가운데(50ms)에 처리한다 — 보정값은 정확히 [skew].
 */
class Rig(val skew: Long = 0) {
    val ports = FakePorts()
    private var n = 0
    val hub = CarHub(ports, newId = { "c${n++}" })
    private var seq = 1L
    var lastRequest: CarSyncRequest? = null
    /** 성공한 요청에 실려 나간 지시 전부. */
    val sentCommands = ArrayList<Command>()

    fun server(carMs: Long) = carMs + skew

    fun sync(
        t: Long,
        watch: Watch? = null,
        // 휴대폰은 차가 주인이 되기 전에는 자기가 주인(owner=PHONE)으로 싣는다 — 차 시작 유예(A-4)는 이 흐름을 받아야 풀린다
        peerFlow: FlowStatus? = settings(owner = if (hub.carIsOwner(t)) Owner.CAR else Owner.PHONE),
        commands: List<Command> = emptyList(),
        peerAgeMs: Long = 1_000,
        peerResponseAt: Long? = null,
        skipped: List<String> = emptyList(),
    ): SyncReport {
        val req = hub.buildRequest("tok", seq++, 0, t)
        lastRequest = req
        sentCommands += req.commands
        val serverTime = server(t + 50)
        val peer = PhonePeer(1, 0, serverTime - peerAgeMs, null, peerFlow, peerResponseAt)
        return hub.onSyncSuccess(req, t, t + 100, CarSyncResponse(serverTime, peer, watch, commands, skippedCommandIds = skipped))
    }

    /** 켜고 정상 판정까지 — S0 로 만든다. */
    fun arm(reading: CarReading, t: Long = 0) {
        hub.onReading(reading, t)
        sync(t, watch(WristVerdict.NORMAL))
    }
}
