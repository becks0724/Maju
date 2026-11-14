// 응답 시각 변화 추적 시험 — 바뀐 값만 한 번 · 처음 본 값은 기억만 · 휴대폰 화면 응답은 정지 때만 · JSON 왕복
package com.example.guardian.shared.protocol

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowInput
import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Gear
import com.example.guardian.shared.flow.ResponseSource
import com.example.guardian.shared.flow.VehicleState
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseChangeTest {
    private val resp = FlowInput.Response(ResponseSource.PHONE_SCREEN, 77)

    private val t0 = 1_761_800_000_000L

    @Test fun onlyIncreaseBeyondMarginIsResponse() {
        val m = ResponseChange(ResponseSource.PHONE_SCREEN)
        assertNull(m.onValue(null, 77))
        assertEquals("응답 없음 → 첫 버튼은 응답", resp, m.onValue(t0, 77))
        assertNull("같은 값", m.onValue(t0, 77))
        assertNull("+1ms 흔들림", m.onValue(t0 + 1, 77))
        assertNull("−1ms 흔들림", m.onValue(t0, 77))
        assertNull("null 은 기준을 바꾸지 않음", m.onValue(null, 77))
        assertNull("0.5초 증가", m.onValue(t0 + 500, 77))
        assertNull("여유 경계 1,000ms 는 아님", m.onValue(t0 + 1_500, 77))
        assertEquals("1.5초 넘게 증가(새 버튼)", resp, m.onValue(t0 + 3_001, 77))
        assertNull("벽시계가 뒤로 감", m.onValue(t0 - 3_600_000, 77))
        assertEquals("뒤로 간 뒤의 새 버튼도 잡힘", resp, m.onValue(t0 - 3_600_000 + 1_500, 77))
    }

    @Test fun jitterDoesNotAccumulate() {
        val m = ResponseChange(ResponseSource.WATCH_BUTTON)
        m.onValue(t0, 0)
        var v = t0
        repeat(5_000) { v += if (it % 2 == 0) 1 else 0; assertNull(m.onValue(v, 0)) }
    }

    @Test fun firstSeenValueIsOnlyBaseline() {
        val m = ResponseChange(ResponseSource.WATCH_BUTTON)
        assertNull("앱을 다시 켰을 때 옛 응답은 쓰지 않음", m.onValue(t0, 1))
        assertNull(m.onValue(t0 + 500, 1))
        assertEquals(FlowInput.Response(ResponseSource.WATCH_BUTTON, 2), m.onValue(t0 + 1_501, 2))
        assertEquals(1_000L, ResponseChange.MARGIN_MS)
    }

    @Test fun phoneResponseRoundTripsAndOldPeerReads() {
        val r = PhoneSyncRequest("t", 1, 1, null, null, responseAt = 1_761_800_000_500)
        assertEquals(r, PhoneSyncRequest.fromJson(JsonObj(Json.parseObject(Json.encode(r.toJson(false))))))
        val p = PhonePeer(1, 1, 1, null, null, 9)
        assertEquals(p, PhonePeer.fromJson(JsonObj(Json.parseObject(Json.encode(p.toJson(false))))))
        assertNull(PhonePeer.fromJson(JsonObj(Json.parseObject("""{"seq":1,"sentAt":1,"receivedAt":1}"""))).responseAt)
    }

    /** 휴대폰 화면 응답은 정지 때만 경보를 끈다 — 주행 중(S1)이면 무시, 정차(AP)면 S2. */
    @Test fun machineAcceptsPhoneScreenOnlyWhenStopped() {
        fun armed(v: VehicleState) = FlowMachine().apply {
            handle(FlowInput.Vehicle(v, 0)); handle(FlowInput.ConsentsChanged(Consents(true, true, true), 0)); handle(FlowInput.Power(true, 0))
            handle(FlowInput.Wrist(WristVerdict.NORMAL, 0)); handle(FlowInput.Wrist(WristVerdict.PULSE_ABSENT, 1_000))
        }
        fun respond(m: FlowMachine) {
            val t = ResponseChange(ResponseSource.PHONE_SCREEN)
            t.onValue(null, 1_500)
            t.onValue(1_761_800_002_000, 2_000)?.let { m.handle(it) }
        }
        val moving = armed(VehicleState(60.0, Gear.D, false))
        assertEquals(FlowState.S1, moving.state)
        respond(moving)
        assertEquals(FlowState.S1, moving.state)
        val parked = armed(VehicleState(0.0, Gear.P, true))
        assertEquals(FlowState.AP, parked.state)
        respond(parked)
        assertEquals(FlowState.S2, parked.state)
    }
}
