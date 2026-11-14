// 시나리오 시험의 공용 무대 — 세 기기 짝짓기 · 주기 설치 · 감시 시작(S0)까지, 그리고 시험 전체에 걸친 G2 감시(등록 정보 · 원신호가 중계를 지나지 않음)
package com.example.guardian.integration

import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

abstract class Stage {
    val world = World()

    /** 중계 · Data Layer 를 쓰지 않는 시나리오(B1)는 false — 감시가 헛도는지 보는 검사를 끈다. */
    var auditNeedsWire = true

    /** 차 · 휴대폰 · 워치를 만들고 짝짓는다. [car] 가 false 면 차 없이(휴대폰이 주인), 코드는 차 대신 시험이 바로 연다. */
    fun setup(car: Boolean = true, watchDirect: Boolean = true, dataLayer: Boolean = false): Trio {
        val c = CarDevice(world)
        val p = PhoneDevice(world)
        val w = WatchDevice(world, direct = watchDirect)
        val code = c.pairStart()
        p.join(code)
        w.join(code)
        if (!car) c.token = null
        if (dataLayer) w.dataLayerTo = p
        if (car) c.install()
        p.install()
        w.install()
        return Trio(c, p, w)
    }

    class Trio(val car: CarDevice, val phone: PhoneDevice, val watch: WatchDevice)

    /** 차 주인 감시(S0)까지. */
    fun armCar(t: Trio) {
        world.runUntil(40_000, "차 상태 기계 S0") { t.car.state() == FlowState.S0 }
    }

    /**
     * 차 앱 프로세스가 죽었다가 [downMs] 뒤 같은 토큰으로 새 프로세스(새 CarHub)가 뜬다(합동 점검 앱2-상1). 옛 차는 더는 sync 하지 않는다.
     * 새 차를 돌려준다(옛 [Trio.car] 는 멈춘 채 남는다).
     */
    fun restartCar(t: Trio, downMs: Long, reading: com.example.guardian.car.CarReading = t.car.reading): CarDevice {
        val tok = t.car.token
        t.car.token = null; t.car.linkUp = false
        world.advance(downMs)
        val c2 = CarDevice(world); c2.token = tok; c2.reading = reading
        c2.install(phase = world.elapsed % 2_000)
        return c2
    }

    /** 짝 지우기 — 24시간 만료 · 중계 재기동과 같은 효과(차 토큰으로 짝 전체 끝내기). 그 뒤 세 기기는 401 을 받는다. */
    fun endPair(t: Trio) {
        world.relay.post("/v1/pair/end", Json.encode(mapOf("token" to t.car.token)))
    }

    /** 다시 짝짓기 — 차가 새 번호를 열고 휴대폰 · 워치가 참여한다([car] 는 401 뒤 토큰이 없는 차). */
    fun repair(t: Trio, car: CarDevice = t.car) {
        val code = car.pairStart()
        t.phone.join(code)
        t.watch.join(code)
    }

    /** 휴대폰 주인 감시(S0)까지. */
    fun armPhone(t: Trio) {
        world.runUntil(40_000, "휴대폰 상태 기계 S0") { t.phone.status().flowState == FlowState.S0 }
    }

    /**
     * G2 감시 — 중계를 지나간 모든 JSON(요청 · 응답)과 워치 → 휴대폰 Data Layer JSON 에 등록 정보(이름 · 생년월일 · 보호자 이름 · 번호 ·
     * 차 번호 · 색)와 원신호 필드(PPG · 가속도 · 심박 샘플)가 한 번도 없어야 한다.
     */
    @After
    fun g2Audit() {
        val texts = world.relay.wire.flatMap { listOf(it.path to it.request, it.path to it.response) } +
            world.dataLayer.map { "datalayer" to it }
        if (auditNeedsWire) assertTrue("중계를 지난 JSON 이 없다 — 감시가 헛돈다", world.relay.wire.isNotEmpty() || world.dataLayer.isNotEmpty())
        for ((path, text) in texts) {
            for (bad in Fixture.forbiddenText) if (text.contains(bad)) fail("G2 — $path 에 등록 정보 「$bad」: ${text.take(300)}")
            val keys = HashSet<String>()
            collectKeys(Json.parse(text), keys)
            val raw = keys intersect Fixture.forbiddenKeys
            if (raw.isNotEmpty()) fail("G2 — $path 에 원신호 필드 $raw")
        }
    }

    private fun collectKeys(v: Any?, out: MutableSet<String>) {
        when (v) {
            is Map<*, *> -> v.forEach { (k, x) -> out += k as String; collectKeys(x, out) }
            is List<*> -> v.forEach { collectKeys(it, out) }
        }
    }
}
