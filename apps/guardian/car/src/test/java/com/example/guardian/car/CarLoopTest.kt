// 실행 틀 시험 — 중계가 응답하지 않아도 2초 주기(차량 읽기 · tick · 차 안 경보)가 막히지 않고, 요청은 한 번에 하나만 나가는지
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.CarSyncRequest
import com.example.guardian.shared.protocol.CarSyncResponse
import com.example.guardian.shared.protocol.PhonePeer
import com.example.guardian.shared.text.Phrase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CarLoopTest {
    private val net = Executors.newSingleThreadExecutor()
    private val inbox = ConcurrentLinkedQueue<() -> Unit>()
    private val clock = AtomicLong(0)
    private val ports = FakePorts()
    private val hub = CarHub(ports)
    private val calls = AtomicInteger(0)
    private val release = CountDownLatch(1)
    private var seq = 1L

    /** 1 · 2번째는 바로 답하고(켬 + 정상 → 약해짐), 3번째부터는 풀어 줄 때까지 멈춘 중계. */
    private val slowRelay: (CarSyncRequest) -> RelayResult<CarSyncResponse> = {
        when (calls.incrementAndGet()) {
            1 -> ok(WristVerdict.NORMAL)
            2 -> ok(WristVerdict.PULSE_WEAK)
            else -> { release.await(60, TimeUnit.SECONDS); RelayResult.Failed("SocketTimeoutException") }
        }
    }

    private fun ok(v: WristVerdict): RelayResult<CarSyncResponse> {
        val server = clock.get()
        return RelayResult.Ok(CarSyncResponse(server, PhonePeer(1, 0, server - 1_000, null, settings(owner = Owner.PHONE)), watch(v), emptyList()))
    }

    private val loop = CarLoop(hub, slowRelay, net, { inbox.add(it) }, { clock.get() })

    @After fun tearDown() { release.countDown(); net.shutdownNow() }

    private fun drain() { while (true) (inbox.poll() ?: return).invoke() }

    private fun awaitDelivery() {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (inbox.isEmpty() && System.nanoTime() < until) Thread.sleep(5)
    }

    @Test fun stuckRelayDoesNotDelayTicksOrInCarAlerts() {
        var slowestStepMs = 0L
        for (t in 0L..40_000L step 2_000) {
            clock.set(t)
            drain()
            val started = System.nanoTime()
            loop.step(MOVING, "tok", { seq++ }, 0)
            slowestStepMs = maxOf(slowestStepMs, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
            if (calls.get() <= 2) awaitDelivery()
        }
        assertTrue("주기 한 번이 중계를 기다리지 않음(가장 느린 주기 ${slowestStepMs}ms)", slowestStepMs < 200)
        assertEquals("멈춘 요청이 있는 동안 새 요청을 만들지 않음", 3, calls.get())
        assertTrue(loop.syncInFlight)
        // 첫 경보(중계 2,000) + 35초 = 37,000 에 S3 — 중계가 멈춰 있어도 tick 이 제때
        assertEquals(FlowState.S3, hub.snapshot.state)
        assertTrue("차 안 음성은 계속 — 주인이 아니라 「늦어질 수 있습니다」(A-5)", ports.spoken.contains(Phrase.VOICE_S3_SMS_DELAYED.ko))
        assertFalse("링크 10초 초과 — 차는 주인이 아님", hub.carIsOwner(clock.get()))

        release.countDown()
        awaitDelivery()
        drain()
        assertFalse("실패를 받으면 다음 주기에 다시 보냄", loop.syncInFlight)
        clock.set(42_000)
        loop.step(MOVING, "tok", { seq++ }, 0)
        assertTrue(loop.syncInFlight)
    }

    /** 결과 처리(onSyncSuccess → 차 안 음성)에서 예외가 나도 주기 스레드는 살아 있고 다음 주기 · 다음 sync 가 돈다. */
    @Test fun exceptionWhileApplyingResultDoesNotStopTheLoop() {
        val errors = ArrayList<Exception>()
        val throwing = object : CarPorts {
            override fun speak(text: String) = throw IllegalStateException("음성 장치 오류")
            override fun openSearch(query: String) = Unit
            override fun ambient(decision: AmbientDecision) = Unit
        }
        val h = CarHub(throwing)
        val n = AtomicInteger(0)
        val relay: (CarSyncRequest) -> RelayResult<CarSyncResponse> = {
            if (n.incrementAndGet() == 1) ok(WristVerdict.NORMAL) else ok(WristVerdict.PULSE_WEAK)
        }
        val l = CarLoop(h, relay, net, { inbox.add(it) }, { clock.get() }, onError = { errors += it })
        for (t in 0L..2_000L step 2_000) {
            clock.set(t)
            drain()
            l.step(MOVING, "tok", { seq++ }, 0)
            awaitDelivery()
        }
        drain() // 두 번째 결과 — S1 진입에서 음성이 예외를 낸다
        assertEquals(1, errors.size)
        assertTrue(errors.single() is IllegalStateException)
        assertEquals(FlowState.S1, h.snapshot.state)
        assertFalse("요청 표시가 풀려 다음 sync 를 보낸다", l.syncInFlight)
        clock.set(4_000)
        l.step(MOVING, "tok", { seq++ }, 0)
        assertTrue(l.syncInFlight)
        awaitDelivery(); drain()
        assertEquals("다음 주기 sync 도 처리됨", 3, n.get())
    }

    /** 리뷰 하1 — 401 이 아닌 4xx · 5xx 는 상태 코드만 기록으로 넘기고(본문 없음), 401 은 짝 폐기로만 간다. */
    @Test fun nonAuthHttpCodesAreReported() {
        val codes = listOf(503, 400, 401, 500)
        val n = AtomicInteger(0)
        val relay: (CarSyncRequest) -> RelayResult<CarSyncResponse> = { RelayResult.Http(codes[n.getAndIncrement()], "본문") }
        val logged = ArrayList<Int>()
        var unauthorized = 0
        val l = CarLoop(
            CarHub(FakePorts()), relay, net, { inbox.add(it) }, { clock.get() },
            onUnauthorized = { unauthorized++ }, onHttpError = { logged += it },
        )
        repeat(codes.size) {
            l.step(MOVING, "tok", { seq++ }, 0)
            awaitDelivery(); drain()
        }
        assertEquals(listOf(503, 400, 500), logged)
        assertEquals(1, unauthorized)
    }

    @Test fun noTokenSendsNothingButStillTicks() {
        loop.step(MOVING, null, { seq++ }, 0)
        assertFalse(loop.syncInFlight)
        assertEquals(0, calls.get())
        assertEquals(FlowState.OFF, hub.snapshot.state)
    }
}
