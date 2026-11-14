// 「휴대폰 연결 끊기」 시험(감사 하3) — 주행 · 경보 · 응답 뒤에는 막히고 확정 정지(기어 P · 주차 브레이크)에서만 열림, 끊기 뒤 토큰 · 짝 상태 · 보낼 함 · ack 지움, 성공과 401 은 같은 처리, 나가 있던 sync 결과 버림
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.CarSyncRequest
import com.example.guardian.shared.protocol.CarSyncResponse
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhonePeer
import com.example.guardian.shared.protocol.TestAction
import com.example.guardian.shared.protocol.CommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class CarUnpairTest {
    private val token = "0123456789abcdef0123456789abcdef"

    /** 주차 브레이크만 걸린 정차(기어 N, 속도 0, UX 제한 없음). */
    private val BRAKE_N = CarReading(speedMps = 0f, gearRaw = VehicleMapping.GEAR_NEUTRAL, parkingBrake = true, ux = UX_PARKED)
    /** 기어 N · 주차 브레이크 풀림 · 속도 0 — 확정 정지 아님. */
    private val ROLLING_N = CarReading(speedMps = 0f, gearRaw = VehicleMapping.GEAR_NEUTRAL, parkingBrake = false, ux = UX_PARKED)

    private fun pairedStore(): Pair<MemKeyValue, CarPairStore> {
        val kv = MemKeyValue()
        val store = CarPairStore(kv, FakeCipher())
        store.saveToken(token)
        store.rememberPower(linkOk = true, powerOn = true)
        return kv to store
    }

    private fun remoteShow(id: String) = Command(
        id, CommandType.SHOW, Payload.Show("x", null).toJson(), 30_000, test = false, from = Party.PHONE, to = CommandTo.CAR,
    )

    @Test fun openOnlyWhenConfirmedStopAndNoIncident() {
        val r = Rig()
        r.arm(PARKED)
        assertEquals(FlowState.S0, r.hub.snapshot.state)
        assertTrue("기어 P 정차 · 감시 중 — 열림", r.hub.unpairOk())

        r.hub.onReading(BRAKE_N, 2_000)
        assertFalse("주차 브레이크만(기어 N)은 확정 정지 아님 — 닫힘(합동 점검 B-4)", r.hub.unpairOk())

        r.hub.onReading(ROLLING_N, 4_000)
        assertFalse("확정 정지 아님 — 닫힘", r.hub.unpairOk())

        r.hub.onReading(MOVING, 6_000)
        assertFalse("주행 — 닫힘", r.hub.unpairOk())

        r.hub.onReading(PARKED.copy(ux = UxGate(requiresDo = false, noSetup = true)), 8_000)
        assertFalse("NO_SETUP 제한 — 닫힘", r.hub.unpairOk())
        r.hub.onReading(PARKED.copy(ux = null), 8_100)
        assertFalse("UX 값 모름 — 닫힘", r.hub.unpairOk())

        // 꺼짐(OFF)도 사건이 없으니 열림
        val off = Rig()
        off.hub.onReading(PARKED, 0)
        off.sync(0, watch(WristVerdict.NORMAL), peerFlow = settings(power = false))
        assertEquals(FlowState.OFF, off.hub.snapshot.state)
        assertTrue(off.hub.unpairOk())
    }

    @Test fun closedDuringAlarmAndAfterResponse() {
        val r = Rig()
        r.arm(PARKED)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        r.hub.onReading(PARKED, 2_100)
        assertTrue("정차 중 경보", r.hub.snapshot.state in setOf(FlowState.S1, FlowState.AP))
        assertFalse("경보 중 — 닫힘", r.hub.unpairOk())
        assertTrue("버튼 문(touchOk)은 열려 있어도", r.hub.setupOk())

        // 「괜찮아요」 응답 뒤 정차 관찰(S2) — 사건이 끝나지 않았으니 닫힘(취소 문자 흐름을 끊지 않게)
        assertTrue(r.hub.onCarButton(2_200, PARKED))
        assertTrue(r.hub.snapshot.state in setOf(FlowState.S1R, FlowState.S2))
        assertFalse("응답 뒤 — 닫힘", r.hub.unpairOk())

        // 주행 중 경보도 닫힘
        val m = Rig()
        m.arm(MOVING)
        m.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        assertEquals(FlowState.S1, m.hub.snapshot.state)
        assertFalse(m.hub.unpairOk())
    }

    @Test fun refusedLeavesEverythingAsIs() {
        val r = Rig()
        r.arm(MOVING)
        val (_, store) = pairedStore()
        assertEquals(CarUnpair.Start.Refused, CarUnpair.start(r.hub, store, 2_000))
        assertEquals(token, store.token())
        assertTrue(store.wasOn)
        assertEquals(FlowState.S0, r.hub.snapshot.state)
    }

    @Test fun clearsTokenPairStateOutboxAndAcks() {
        val r = Rig()
        r.arm(PARKED)
        r.sync(2_000, watch(WristVerdict.NORMAL), commands = listOf(remoteShow("old-1")))
        r.hub.sendTestRequest(TestAction.START, 2_100)
        assertTrue("보낼 함에 지시가 있다", r.hub.pendingCommands.isNotEmpty())
        assertTrue(r.hub.links(2_200).phone)
        assertFalse(r.hub.bio(2_200).noSignal)
        val (kv, store) = pairedStore()

        val start = CarUnpair.start(r.hub, store, 2_200)

        assertEquals("끝낼 토큰을 꺼내 준다", CarUnpair.Start.Cleared(token), start)
        assertNull("토큰 지움", store.token())
        assertFalse("켬 기억 지움 — 재부팅 뒤 되살리지 않음", store.wasOn)
        assertTrue("저장소가 비었다", kv.map.isEmpty())
        assertFalse(CarResume.shouldStart(CarPairStore.resumeState(kv).first, CarPairStore.resumeState(kv).second))
        assertTrue("보낼 함을 비운다", r.hub.pendingCommands.isEmpty())
        assertNull(r.hub.testPending)
        val next = r.hub.buildRequest("new", 9, 0, 2_300)
        assertTrue("옛 짝 ack 도 새 짝에 보내지 않는다", next.acks.isEmpty())
        assertTrue(next.commands.isEmpty())
        assertEquals("짝 상태 — 꺼짐으로 새로 시작", FlowState.OFF, r.hub.snapshot.state)
        assertFalse(r.hub.snapshot.powerOn)
        assertFalse("앞 운전자 휴대폰 연결이 남지 않는다", r.hub.links(2_300).phone)
        assertTrue("앞 운전자 워치 값이 남지 않는다", r.hub.bio(2_300).noSignal)
        assertNull(r.hub.carDisplay)
        assertNull(r.hub.watchVia)
        // 다음 주기에도 감시로 돌아가지 않는다(새 짝의 휴대폰이 켜기를 보낼 때까지)
        r.hub.onReading(PARKED, 4_000)
        assertEquals(FlowState.OFF, r.hub.snapshot.state)
        assertTrue("새 짝짓기 문은 열려 있다", r.hub.setupOk())
    }

    /** 성공과 401(이미 끝남 · 만료)은 중계도 지워진 것 — 안내 없음. 네트워크 실패 · 그 밖의 HTTP 는 「중계 기록 24시간」 안내. */
    @Test fun okAnd401AreTheSameOutcome() {
        assertTrue(CarUnpair.relayCleared(RelayResult.Ok(Unit)))
        assertTrue(CarUnpair.relayCleared(RelayResult.Http(401, "unauthorized")))
        assertFalse(CarUnpair.relayCleared(RelayResult.Http(500, null)))
        assertFalse(CarUnpair.relayCleared(RelayResult.Http(400, "bad_request")))
        assertFalse(CarUnpair.relayCleared(RelayResult.Failed("SocketTimeoutException")))
        assertTrue("2xx 인데 본문을 못 읽음 — 지워진 것", CarUnpair.relayCleared(RelayResult.Unparsable("IllegalStateException")))
        // 기기 쪽 지우기는 중계 결과보다 먼저라 두 경우 모두 같은 상태다
        val (_, store) = pairedStore()
        val r = Rig(); r.arm(PARKED)
        assertTrue(CarUnpair.start(r.hub, store, 2_000) is CarUnpair.Start.Cleared)
        assertNull(store.token())
    }

    /** 끊기 직전에 옛 토큰으로 나간 sync 가 성공으로 돌아와도 앞 운전자의 워치 · 휴대폰 상태를 다시 채우지 않는다. */
    @Test fun inFlightSyncResultIsDiscardedAfterUnpair() {
        val hub = CarHub(FakePorts())
        val queue = ArrayList<() -> Unit>()
        val ranOnNet = ArrayList<Runnable>()
        val relay: (CarSyncRequest) -> RelayResult<CarSyncResponse> = {
            RelayResult.Ok(CarSyncResponse(1_000, PhonePeer(1, 0, 0, null, settings()), watch(WristVerdict.NORMAL), emptyList()))
        }
        var unauthorized = 0
        val loop = CarLoop(hub, relay, Executor { ranOnNet += it }, { queue += it }, { 1_000L }, onUnauthorized = { unauthorized++ })
        loop.step(PARKED, "old", { 1L }, 0)
        assertTrue(loop.syncInFlight)
        val (_, store) = pairedStore()
        assertTrue(CarUnpair.start(hub, store, 1_000) is CarUnpair.Start.Cleared)
        loop.discardInFlight()

        ranOnNet.forEach { it.run() }
        queue.forEach { it() }

        assertFalse("보내는 중은 풀린다", loop.syncInFlight)
        assertFalse(hub.links(1_100).phone)
        assertTrue(hub.bio(1_100).noSignal)
        assertEquals(FlowState.OFF, hub.snapshot.state)
        assertEquals(0, unauthorized)
        // 다음 요청부터는 정상 처리
        loop.step(PARKED, "new", { 2L }, 0)
        ranOnNet.drop(1).forEach { it.run() }
        queue.drop(1).forEach { it() }
        assertTrue(hub.links(1_100).phone)
    }

    /**
     * 코드 리뷰 11-09 차 하 — 번호 화면(휴대폰 · 워치 아직 안 붙음, 최대 10분)에서도 문이 열려 있으면 「휴대폰 연결 끊기」를 둔다.
     * 문이 닫혔거나(주행 · 사건 · 요청 중) 주행 · 경보 화면이면 두지 않는다.
     */
    @Test fun unpairReachableFromThePairingPage() {
        val r = Rig()
        r.hub.onReading(PARKED, 0)
        r.sync(0) // 휴대폰만 붙고 워치는 아직
        val paired = true
        val code = "123456"
        val page = CarScreen.page(null, relayConfigured = true, paired, code, r.hub.links(200), r.hub.setupOk())
        assertEquals("워치가 아직 안 붙어 번호 화면", CarPage.PAIRING, page)
        assertTrue("문은 열려 있다", r.hub.unpairOk())
        assertTrue("번호 화면에서도 둔다", CarScreen.unpairAllowed(CarPage.PAIRING, unpairOk = true))
        assertTrue(CarScreen.unpairAllowed(CarPage.PARKED, unpairOk = true))
        assertFalse(CarScreen.unpairAllowed(CarPage.PAIRING, unpairOk = false))
        for (p in listOf(CarPage.DRIVING, CarPage.ALARM, CarPage.RELAY_MISSING)) assertFalse(CarScreen.unpairAllowed(p, unpairOk = true))
    }

    @Test fun newTextsAreInTheTableAndParkedOnly() {
        val news = listOf(
            CarTexts.UNPAIR, CarTexts.UNPAIR_CONFIRM_TITLE, CarTexts.UNPAIR_CONFIRM_BODY, CarTexts.UNPAIR_CONFIRM_NOTE,
            CarTexts.UNPAIR_CONFIRM_OK, CarTexts.UNPAIR_RELAY_LEFT,
        )
        for (t in news) assertTrue(t, t in CarTexts.all)
        for (t in news) assertFalse("주행 열 문구가 아니다 — $t", t in CarTexts.driving)
        for (t in news) assertFalse("「…세요」 없이 — $t", t.contains("세요"))
    }
}
