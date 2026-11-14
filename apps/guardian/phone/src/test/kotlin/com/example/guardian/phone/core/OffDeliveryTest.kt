// 끄기 전달(C-6) 시험 — 꺼짐 스냅숏을 중계에 전달하지 못했으면 남기고, 다음 기회(앱 열기 · 부팅 · 중계 다시 닿음)에 보내며, 그 전에 다시 켜면 버린다
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffDeliveryTest {
    private class Mem : OffDeliveryStore { override var offPending = false }
    private val store = Mem()
    private val off = OffDelivery(store)

    @Test fun delivered_clearsAndStops() {
        off.begin(paired = true)
        assertTrue(store.offPending)
        assertTrue(off.onShutdownSync(OffDelivery.Result.DELIVERED, 0))
        assertFalse(store.offPending)
    }

    @Test fun notPaired_nothingToDeliver() {
        off.begin(paired = false)
        assertFalse(store.offPending)
    }

    @Test fun failed_keepsPendingAndRetriesForAWhileThenStops() {
        off.begin(paired = true)
        assertFalse("실패 직후에는 서비스를 멈추지 않고 다시 보낸다", off.onShutdownSync(OffDelivery.Result.FAILED, 1_000))
        assertFalse(off.onShutdownSync(OffDelivery.Result.FAILED, 1_000 + OffDelivery.RETRY_MS - 1))
        assertTrue("다시 보내기 창이 지나면 멈춘다", off.onShutdownSync(OffDelivery.Result.FAILED, 1_000 + OffDelivery.RETRY_MS))
        assertTrue("못 보낸 꺼짐은 남는다", store.offPending)
    }

    @Test fun unauthorized_dropsPending() {
        off.begin(paired = true)
        assertTrue(off.onShutdownSync(OffDelivery.Result.GONE, 0))
        assertFalse(store.offPending)
    }

    @Test fun pending_dueOnNextChance_andClearedWhenSent() {
        store.offPending = true
        assertTrue(off.due(powerOn = false, hasToken = true))
        off.onSent(OffDelivery.Result.FAILED)
        assertTrue("실패면 다음 기회에 다시", off.due(powerOn = false, hasToken = true))
        off.onSent(OffDelivery.Result.DELIVERED)
        assertFalse(off.due(powerOn = false, hasToken = true))
    }

    @Test fun turnedOnBeforeDelivery_dropsPending() {
        store.offPending = true
        off.onPowerOn()
        assertFalse(store.offPending)
        store.offPending = true
        assertFalse("켜져 있으면 보내지 않고 버린다", off.due(powerOn = true, hasToken = true))
        assertFalse(store.offPending)
    }

    @Test fun tokenGone_dropsPending() {
        store.offPending = true
        assertFalse(off.due(powerOn = false, hasToken = false))
        assertFalse(store.offPending)
    }

    @Test fun request_carriesOnlyOffSnapshot() {
        val req = OffDelivery.request("tok", wallNow = 1_793_000_000_000L, monoNow = 5_000L, registration = fullRegistration(powerOn = false))
        assertEquals("tok", req.token)
        assertEquals(1_793_000_000_000L, req.seq)
        val f = req.flow!!
        assertEquals(FlowState.OFF, f.state)
        assertEquals(Owner.CAR, f.owner)
        assertFalse(f.snapshot.powerOn)
        assertFalse(f.test)
        assertNull("워치 값을 싣지 않는다", req.watch)
        assertTrue(req.commands.isEmpty() && req.acks.isEmpty())
        assertNull(req.responseAt)
    }
}
