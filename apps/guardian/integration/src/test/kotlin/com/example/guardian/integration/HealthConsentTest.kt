// 휴대폰 건강 동의를 끄면 워치 직접 경로 값도 차로 내려가지 않는지(감사 하4) · 휴대폰 위치가 중계로 가지 않는지(감사 중4) — 세 기기 통합
package com.example.guardian.integration

import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.protocol.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConsentTest : Stage() {

    private fun carWatches(fromMs: Long) = world.relay.wire
        .filter { it.path == "/v1/car/sync" && it.status == 200 && it.atMs >= fromMs }
        .map { Json.parseObject(it.response)["watch"] }

    @Test
    fun phoneHealthConsentOffStopsDirectWatchReachingCar() {
        val t = setup()
        armCar(t)
        assertNotNull("동의가 있으면 워치 직접 값이 차로 간다", carWatches(0).lastOrNull())

        // 휴대폰에서만 건강 동의를 끈다 — 워치는 자기 동의로 직접 경로를 계속 쓴다
        t.phone.store.reg = t.phone.store.reg.copy(consents = Consents(emergencyInfo = true, health = false, location = true))
        world.advance(4_000)
        val from = world.relayNow
        world.advance(20_000)

        val watchSyncs = world.relay.wire.filter { it.path == "/v1/watch/sync" && it.atMs >= from }
        assertTrue("워치는 계속 직접 sync 한다", watchSyncs.isNotEmpty())
        assertTrue("워치 sync 는 200 으로 받는다", watchSyncs.all { it.status == 200 })
        val after = carWatches(from)
        assertTrue("차 sync 가 돌았다", after.isNotEmpty())
        assertTrue("차 응답 watch 는 모두 null — $after", after.all { it == null })
        val phoneReqs = world.relay.wire.filter { it.path == "/v1/phone/sync" && it.atMs >= from }
        assertTrue(phoneReqs.all { Json.parseObject(it.request)["healthConsent"] == false })

        // 다시 켜면 예전과 같다
        t.phone.store.reg = t.phone.store.reg.copy(consents = Consents(emergencyInfo = true, health = true, location = true))
        world.advance(4_000)
        val back = world.relayNow
        world.advance(6_000)
        assertNotNull(carWatches(back).lastOrNull())
    }

    @Test
    fun phoneLocationNeverCrossesRelay() {
        val t = setup()
        t.phone.gpsKmh = 0.3
        armCar(t)
        world.advance(10_000)
        val phoneReqs = world.relay.wire.filter { it.path == "/v1/phone/sync" }
        assertTrue(phoneReqs.isNotEmpty())
        for (e in phoneReqs + world.relay.wire.filter { it.path == "/v1/car/sync" }) {
            assertTrue(!e.request.contains("phoneLocation") && !e.response.contains("phoneLocation"))
            assertTrue("휴대폰 좌표가 중계를 지나지 않는다", !e.request.contains("126.979") && !e.response.contains("126.979"))
        }
        assertEquals(true, Json.parseObject(phoneReqs.last().request)["healthConsent"])
    }
}
