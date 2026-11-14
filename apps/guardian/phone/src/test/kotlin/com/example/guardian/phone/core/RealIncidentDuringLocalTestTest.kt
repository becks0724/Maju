// 테스트 세션 중 실제 사건 시험 — 휴대폰 안 시험(LOCAL) 중 실제 워치 값의 사건은 실제 문자 · 실제 119 화면 · 차에는 테스트 아님, 워치 시뮬레이션(WATCH) 중 표시 없는 문자는 계속 차단
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CarPeer
import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.protocol.Vehicle
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import com.example.guardian.shared.protocol.WireFacts
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.text.SmsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealIncidentDuringLocalTestTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private var relay = false
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { relay })

    private fun real(v: WristVerdict) = Watch(v, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = null)
    private fun loc() = Loc(37.57590, 126.97690, 8.0, 0.0, wall0, "서울 종로구 세종대로 172")
    private fun feed(w: Watch) { engine.onDataLayerWatch(w, mono); engine.onLocation(loc(), mono) }

    private fun advance(ms: Long, each: () -> Unit) {
        val end = mono + ms
        while (mono < end) { mono += 1_000; each(); engine.tick(mono) }
    }

    private fun carFlow(state: FlowState, test: Boolean) =
        FlowStatus.of(FlowSnapshot(state = state, test = test, powerOn = true, consents = Consents(true, true, true)), Owner.CAR)

    /** 차가 1초 전 중계에 닿았고 정차(P). [watchDirect] 면 워치 직접 경로가 있다. */
    private fun carSync(flow: FlowStatus?, commands: List<Command> = emptyList(), watchDirect: Boolean = false) {
        engine.buildSync("tok", mono)
        val peer = CarPeer(1, wall0, mono + serverOffset - 1_000, Vehicle(speedKmh = 0.0, gear = Gear.P, parkingBrake = true), flow)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, peer, null, commands, watchDirectAgeMs = if (watchDirect) 1_000 else null), mono, mono)
    }

    private fun eventCmd(id: String, kind: GuardianSmsWireKind = GuardianSmsWireKind.EVENT): Command {
        val p = Payload.SendGuardianSms(kind, "event-L1", WireFacts(SnapshotClock.LOCAL, firstAlertAt = mono - 35_000, noResponseAt = mono), test = false)
        return Command(id, p.type, p.toJson(), 30_000, false, Party.CAR, CommandTo.PHONE)
    }

    private fun guardianSms() = fx.sms.filter { it.first == GUARDIAN }

    // ── 휴대폰 주인 · LOCAL ──

    /** 휴대폰 안 시험(정상 시나리오) 중 실제 워치가 맥박 없음 → 내 상태 기계의 실제 사건. 보호자 문자는 실제로 나가고 본문은 실제 워치 값이다. */
    @Test fun phoneOwner_realIncidentDuringLocalTest_sendsRealSmsAndShowsRealScreens() {
        feed(real(WristVerdict.NORMAL))
        engine.tick(mono)
        assertEquals(Owner.PHONE, engine.owner)
        assertEquals(TestStart.LOCAL, engine.startTest(Scenario.NORMAL, phoneOnly = true, now = mono))
        assertFalse("휴대폰 안 시험은 차에 테스트로 알리지 않는다", engine.buildSync("tok", mono).flow!!.test)

        advance(40_000) { feed(real(WristVerdict.PULSE_ABSENT)) }
        assertEquals(FlowState.S4, engine.status(mono).flowState)
        assertEquals("실제 사건 문자 한 통", 1, guardianSms().size)
        val body = guardianSms()[0].second
        assertFalse(body.startsWith(SmsText.TEST_PREFIX))
        assertTrue("본문은 시뮬레이션이 아니라 실제 워치 값", body.contains(SmsText.PULSE_NOT_FOUND))
        assertFalse(Notice.SMS_SKIPPED_UNMARKED_TEST in fx.notices)
        assertTrue(fx.asked.isEmpty())
        // 119 는 미리보기가 아니라 정차 뒤 무응답 화면(P12)
        assertTrue(fx.previews.isEmpty())
        assertEquals(1, fx.noResponses.size)
        // 실제 사건이 열리면 휴대폰 안 시험은 끝나고 화면은 실제 사건을 보인다
        assertNull(engine.test)
        assertEquals(FlowState.S4, engine.status(mono).shownState)
        assertFalse(engine.status(mono).flowTest)
        assertFalse(engine.buildSync("tok", mono).flow!!.test)
        // 응답하면 취소 문자도 실제로 한 통
        engine.phoneResponse(mono)
        assertEquals(2, guardianSms().size)
        assertFalse(guardianSms()[1].second.startsWith(SmsText.TEST_PREFIX))
    }

    // ── 차 주인 · LOCAL ──

    /** 차가 주인이고 휴대폰 안 시험 중 — 차가 실제 사건 문자 지시(test=false)를 보내면 보낸다. 차에 보내는 설정도 테스트가 아니다. */
    @Test fun carOwner_realEventCommandDuringLocalTest_isSent() {
        relay = true
        feed(real(WristVerdict.NORMAL))
        carSync(carFlow(FlowState.S0, test = false))
        engine.tick(mono)
        assertEquals(Owner.CAR, engine.owner)
        assertEquals(TestStart.LOCAL, engine.startTest(Scenario.PULSE_LOST, phoneOnly = true, now = mono))
        assertFalse(engine.buildSync("tok", mono).flow!!.test)
        assertTrue("휴대폰 안 시험은 표시 전용 flow.localTest 로 알린다", engine.buildSync("tok", mono).flow!!.localTest)

        mono += 1_000
        carSync(carFlow(FlowState.S4, test = false), listOf(eventCmd("e-1")))
        assertEquals(1, guardianSms().size)
        assertFalse(guardianSms()[0].second.startsWith(SmsText.TEST_PREFIX))
        assertFalse(Notice.SMS_SKIPPED_UNMARKED_TEST in fx.notices)
        assertNull("차의 실제 경보가 보이면 휴대폰 안 시험은 끝난다", engine.test)
        engine.tick(mono)
        assertEquals(FlowState.S4, engine.status(mono).shownState)
        assertFalse(engine.status(mono).flowTest)
        assertFalse("시험이 끝나면 localTest 도 거짓", engine.buildSync("tok", mono).flow!!.localTest)
    }

    // ── WATCH 세션은 그대로 막는다 ──

    /** 워치 시뮬레이션 세션 중 테스트 표시 없는 사건 문자 — 표시가 늦은 시뮬레이션 사건일 수 있어 계속 보내지 않는다(계약 §4.6 이중 방어). */
    @Test fun watchSession_unmarkedEventIsStillSkipped() {
        relay = true
        feed(real(WristVerdict.NORMAL))
        carSync(carFlow(FlowState.S0, test = false), watchDirect = true)
        engine.tick(mono)
        assertEquals(TestStart.WATCH, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
        // 워치가 시뮬레이션 값(test=true)을 낼 때만 테스트로 다룬다(C-1)
        feed(real(WristVerdict.NORMAL).copy(source = WatchSource.SIMULATION, test = true))
        assertTrue(engine.buildSync("tok", mono).flow!!.test)
        assertFalse("워치 시나리오 세션은 localTest 가 아니다", engine.buildSync("tok", mono).flow!!.localTest)

        mono += 1_000
        feed(real(WristVerdict.NORMAL).copy(source = WatchSource.SIMULATION, test = true))
        carSync(carFlow(FlowState.S4, test = false), listOf(eventCmd("e-2")), watchDirect = true)
        assertTrue(guardianSms().isEmpty())
        assertTrue(Notice.SMS_SKIPPED_UNMARKED_TEST in fx.notices)
        assertNotNull("실제 사고(S5)가 아니면 워치 세션은 끝내지 않는다", engine.test)
    }

    /** 워치 세션이 남아 있어도 신선한 워치 값이 실제(test=false)면 표시 없는 사건 문자는 실제로 보낸다(C-1, 앱2-중2). */
    @Test fun watchSession_realWatchValues_unmarkedEventIsSent() {
        relay = true
        feed(real(WristVerdict.NORMAL))
        carSync(carFlow(FlowState.S0, test = false), watchDirect = true)
        engine.tick(mono)
        assertEquals(TestStart.WATCH, engine.startTest(Scenario.PULSE_LOST, phoneOnly = false, now = mono))
        mono += 1_000
        feed(real(WristVerdict.NORMAL))
        carSync(carFlow(FlowState.S4, test = false), listOf(eventCmd("e-3")), watchDirect = true)
        assertEquals(1, guardianSms().size)
        assertFalse(Notice.SMS_SKIPPED_UNMARKED_TEST in fx.notices)
    }
}
