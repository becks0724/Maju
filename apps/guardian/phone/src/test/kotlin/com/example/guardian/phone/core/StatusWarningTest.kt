// 상태 경고 시험 — 워치에 경보가 가지 않는 상태(C-7 · 앱 흐름 상1 최소안)와 서버에 닿지 않는 상태(하9)를 30초 뒤 사실대로 알린다
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusWarningTest {
    private val store = MemoryStore()
    private val fx = FakeEffects()
    private var ids = 0
    private val wall0 = 1_793_000_000_000L
    private val mono0 = 1_000_000L
    private var mono = mono0
    private val serverOffset = 5_000_000_000L
    private var relay = true
    private val engine = PhoneEngine(store, fx, { "c${ids++}" }, { wall0 + (mono - mono0) }, wall0, mono0, { relay })

    private val watch = Watch(WristVerdict.NORMAL, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = null)

    private fun syncOk(watchDirectAgeMs: Long?) {
        engine.buildSync("tok", mono)
        engine.onSyncResponse(PhoneSyncResponse(mono + serverOffset, null, null, emptyList(), watchDirectAgeMs = watchDirectAgeMs), mono, mono)
    }

    /** 1초마다 Data Layer 워치 값, 2초마다 sync([sync]), tick. */
    private fun run(ms: Long, dl: Boolean = true, sync: () -> Unit) {
        val end = mono + ms
        while (mono < end) {
            mono += 1_000
            if (dl) engine.onDataLayerWatch(watch, mono)
            if ((mono - mono0) % 2_000 == 0L) sync()
            engine.tick(mono)
        }
    }

    @Test fun dataLayerFresh_syncOk_noDirectWatch_warnsAfter30s() {
        run(20_000) { syncOk(null) }
        assertFalse("30초 전에는 경고 없음", engine.status(mono).watchAlertUnreachable)
        run(20_000) { syncOk(null) }
        assertTrue(engine.status(mono).watchAlertUnreachable)
    }

    @Test fun directWatchPresent_noWarning() {
        run(40_000) { syncOk(1_000) }
        assertFalse(engine.status(mono).watchAlertUnreachable)
    }

    /** 레드 app-qa 조건 — 휴대폰 자신의 sync 가 실패하면 워치 직접 경로를 알 수 없다. 거짓 경고를 내지 않는다. */
    @Test fun phoneSyncFailing40s_dataLayerFresh_noWarning() {
        syncOk(1_000)
        run(40_000) { engine.onSyncError(mono) }
        assertFalse(engine.status(mono).watchAlertUnreachable)
    }

    @Test fun dataLayerStale_noWarning() {
        run(40_000, dl = false) { syncOk(null) }
        assertFalse(engine.status(mono).watchAlertUnreachable)
    }

    @Test fun powerOff_noWarning() {
        store.registration = fullRegistration(powerOn = false)
        run(40_000) { syncOk(null) }
        assertFalse(engine.status(mono).watchAlertUnreachable)
    }

    @Test fun warningClearsAtOnceWhenDirectReturns() {
        run(40_000) { syncOk(null) }
        assertTrue(engine.status(mono).watchAlertUnreachable)
        run(2_000) { syncOk(1_000) }
        assertFalse(engine.status(mono).watchAlertUnreachable)
    }

    // ── 하9 서버에 닿지 않음 ──

    @Test fun syncFailing30s_serverUnreachable_recoversAtOnce() {
        syncOk(1_000)
        run(30_000) { engine.onSyncError(mono) } // 첫 실패는 2초 뒤 — 실패 28초째
        assertFalse("30초 전에는 그대로(깜빡임 막기)", engine.status(mono).serverUnreachable)
        run(2_000) { engine.onSyncError(mono) }
        assertTrue(engine.status(mono).serverUnreachable)
        run(2_000) { syncOk(1_000) }
        assertFalse("복구는 바로", engine.status(mono).serverUnreachable)
    }

    @Test fun noRelay_notServerUnreachable() {
        relay = false
        run(40_000) { engine.onSyncError(mono) }
        assertFalse(engine.status(mono).serverUnreachable)
    }
}
