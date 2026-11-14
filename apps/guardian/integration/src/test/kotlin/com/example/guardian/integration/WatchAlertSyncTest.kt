// 워치 경보 맞추기 통합 시험(합동 점검 C-2 · 앱2-중3) — 워치가 시작 · 정지를 못 받음, 묵은 시작이 정지 뒤에 도착, 사고(S5) 전환
package com.example.guardian.integration

import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 주인이 워치 값 `alerting` 을 보고 시작 · 정지를 다시 내는 배선(차 · 휴대폰)과 워치가 `alerting` 을 싣는 배선이 들어와 모두 켜져 있다.
 */
class WatchAlertSyncTest : Stage() {
    /** [dropping] 이 참인 동안 처음 보인 [type] 지시는 끝까지 워치에 닿지 않는다(중계는 ttl 동안 계속 내려 준다). */
    private class Dropper(val type: String) {
        var dropping = false
        private val firstSeen = HashMap<String, Boolean>()
        fun drop(c: Map<String, Any?>): Boolean = c["type"] == type && firstSeen.getOrPut(c["id"] as String) { dropping }
    }

    private fun parkedAlert(t: Trio) {
        armCar(t)
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
        world.runUntil(60_000, "AP") { t.car.state() == FlowState.AP }
    }

    @Test fun watchMissesStart_ownerResends() {
        val t = setup()
        val d = Dropper("ALERT_START")
        t.watch.dropCommand = d::drop
        d.dropping = true
        parkedAlert(t)
        world.advance(3_000)
        d.dropping = false
        assertFalse("첫 시작은 닿지 않음", t.watch.alerting)
        // 2단계 시작(15초) 전에 주인이 진동 시작을 다시 낸다(10초에 한 번)
        world.runUntil(11_000, "워치가 다시 울림") { t.watch.alerting }
    }

    @Test fun watchMissesStop_ownerResends() {
        val t = setup()
        parkedAlert(t)
        world.runUntil(6_000, "워치 울림") { t.watch.alerting }
        val d = Dropper("ALERT_STOP")
        t.watch.dropCommand = d::drop
        d.dropping = true
        assertTrue(t.car.pressOk())
        world.advance(3_000)
        d.dropping = false
        assertTrue("첫 정지는 닿지 않음", t.watch.alerting)
        world.runUntil(15_000, "주인이 정지를 다시 냄") { !t.watch.alerting }
    }

    /** 늦게 도착한 같은 사건의 시작(다시 낸 시작이 정지 뒤에 닿음)은 버린다 — 워치는 정지한 사건 id 의 시작을 모두 버린다. */
    @Test fun staleStartAfterStopIsIgnored() {
        val t = setup()
        var start: Map<String, Any?>? = null
        t.watch.dropCommand = { c -> if (c["type"] == "ALERT_START" && start == null) start = c; false }
        parkedAlert(t)
        world.runUntil(6_000, "워치 울림") { t.watch.alerting }
        assertTrue(t.car.pressOk())
        world.runUntil(8_000, "정지") { !t.watch.alerting }
        t.watch.lateCommands += start!!.toMutableMap().also { it["id"] = "late-start" }
        world.advance(4_000)
        assertFalse("정지한 사건의 늦은 시작은 버림", t.watch.alerting)
    }

    /** 경보 중 에어백 — 사고(S5)로 바뀔 때 내는 정지에는 옛 사건 id 가 실려 워치가 옛 경보를 끈다(app-qa). */
    @Test fun accidentTransitionStopsOldWatchAlert() {
        val t = setup()
        t.car.reading = CarDevice.driving(); t.phone.gpsKmh = 60.0
        armCar(t)
        t.watch.realSignal(Scenario.PULSE_LOST, eventInMs = 3_000)
        world.runUntil(60_000, "S1") { t.car.state() == FlowState.S1 }
        world.runUntil(6_000, "워치 울림") { t.watch.alerting }
        t.car.reading = t.car.reading.copy(airbagDeployed = true)
        world.runUntil(6_000, "S5") { t.car.state() == FlowState.S5 }
        world.runUntil(8_000, "옛 경보 꺼짐") { !t.watch.alerting }
    }

}
