// app-qa 2차 검증 권고 경계 시험 — GPS 정지 위쪽 경계, 워치 응답이 중계 · Data Layer 로 번갈아 오는 경우, ALL(ALERT_STOP) 되돌아옴
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.CommandTo
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.Loc
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Via
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundaryTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private var relay = false
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private val engine = PhoneEngine(store, fx, { "q${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { relay })

    private fun w(v: WristVerdict, r: Long?) = Watch(v, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = r)
    private fun loc(speed: Double) = Loc(37.57590, 126.97690, 8.0, speed, wall0, "주소")
    private fun dl(v: WristVerdict, r: Long?) = engine.onDataLayerWatch(w(v, r), mono)
    private fun viaRelay(v: WristVerdict, r: Long?) = engine.onSyncResponse(
        PhoneSyncResponse(mono + serverOffset, null, w(v, r).copy(via = Via.WATCH, receivedAt = mono + serverOffset, ageMs = 0), emptyList()), mono, mono)
    private fun step(each: () -> Unit) { mono += 1_000; each(); engine.tick(mono) }

    /** 중계 값(옛 응답 · null)과 Data Layer 값(최신 응답)이 번갈아 와도 거짓 응답이 없고, 진짜 새 응답은 잡힌다. */
    @Test fun alternatingWatchSourcesNoFalseResponse() {
        val old = wall0 - 600_000
        val older = wall0 - 900_000
        engine.onLocation(loc(60.0), mono); dl(WristVerdict.NORMAL, old); engine.tick(mono)
        var i = 0
        repeat(45) {
            step {
                engine.onLocation(loc(60.0), mono)
                when (i++ % 3) {
                    0 -> dl(WristVerdict.PULSE_WEAK, old)
                    1 -> viaRelay(WristVerdict.PULSE_WEAK, null)
                    else -> viaRelay(WristVerdict.PULSE_WEAK, older)
                }
            }
        }
        val st = engine.status(mono).flowState
        assertTrue("상태 $st", st == FlowState.S1 || st == FlowState.S3)
        // 진짜 새 응답 — 중계 쪽에 먼저 와도 잡힌다
        step { engine.onLocation(loc(60.0), mono); viaRelay(WristVerdict.PULSE_WEAK, wall0 + (mono - mono0)) }
        assertEquals(FlowState.S1R, engine.status(mono).flowState)
    }

    /** ALL(ALERT_STOP) — 휴대폰에서 한 번 실행, 중계에 한 번, 되돌아와도 다시 실행하지 않고 ack 만. */
    @Test fun allAlertStopRunsOnceAndEchoIsAckedOnly() {
        engine.onLocation(loc(0.0), mono); dl(WristVerdict.NORMAL, null); engine.tick(mono)
        repeat(20) { step { engine.onLocation(loc(0.0), mono); dl(WristVerdict.PULSE_ABSENT, null) } }
        assertEquals(FlowState.AP, engine.status(mono).flowState)
        val before = fx.alarmStops
        engine.phoneResponse(mono)
        assertEquals(before + 1, fx.alarmStops)
        relay = true
        val req = engine.buildSync("tok", mono)
        val stops = req.commands.filter { it.type == CommandType.ALERT_STOP }
        assertEquals(1, stops.size)
        assertEquals(CommandTo.ALL, stops[0].resolvedTo)
        assertFalse(req.commands.any { it.resolvedTo == CommandTo.PHONE })
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, null, null, stops), mono, mono)
        assertEquals(before + 1, fx.alarmStops)
        assertEquals(listOf(stops[0].id), engine.buildSync("tok", mono).acks)
    }

    private fun stoppedAfter(speed: Double, ms: Long): Boolean {
        engine.onLocation(loc(30.0), mono); engine.tick(mono)
        val end = mono + ms
        while (mono < end) step { engine.onLocation(loc(speed), mono) }
        return engine.status(mono).stopped
    }

    /** 차 상태 없음(PHONE_GPS) 정지 위쪽 경계 — 1.0km/h 는 정지, 1.1km/h 는 아님, 0 이어도 5초 디바운스 전에는 아님. */
    @Test fun gpsAt1_0For6sIsStopped() { assertTrue(stoppedAfter(1.0, 6_000)) }
    @Test fun gpsAt1_1For6sIsNotStopped() { assertFalse(stoppedAfter(1.1, 6_000)) }
    @Test fun gpsAt0For4sIsNotYetStopped() { assertFalse(stoppedAfter(0.0, 4_000)) }
}
