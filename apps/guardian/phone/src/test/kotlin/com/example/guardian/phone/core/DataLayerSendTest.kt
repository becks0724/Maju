// Data Layer 워치 값 보내기 시험(11-02 남은 하) — sync 가 실패하면 그 값을 잃지 않고 다음 sync 에 다시 싣고, 성공한 뒤에만 「보냄」으로 친다
package com.example.guardian.phone.core

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.protocol.PhoneSyncResponse
import com.example.guardian.shared.protocol.Watch
import com.example.guardian.shared.protocol.WatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataLayerSendTest {
    private val wall0 = 1_793_000_000_000L
    private var mono = 1_000_000L
    private val engine = PhoneEngine(MemoryStore(), FakeEffects(), { "c" }, { wall0 + mono }, wall0, mono, { true })

    private fun watch(r: Long?) = Watch(WristVerdict.NORMAL, 70, wall0, worn = true, source = WatchSource.SENSOR_SDK, test = false, responseAt = r)
    private fun ok() = engine.onSyncResponse(PhoneSyncResponse(mono + 5_000_000_000L, null, null, emptyList()), mono, mono)

    @Test fun failedSyncKeepsWatchValueForNextSync() {
        val pressed = watch(wall0 + 1_000) // 워치 버튼 응답이 든 값
        engine.onDataLayerWatch(pressed, mono)
        assertEquals(pressed, engine.buildSync("tok", mono).watch)
        engine.onSyncError() // 네트워크 실패 — 중계가 받지 못했다
        mono += 2_000
        assertEquals("실패한 값은 다음 sync 에 다시 싣는다", pressed, engine.buildSync("tok", mono).watch)
        ok()
        mono += 2_000
        assertNull("받은 뒤에는 같은 값을 거듭 보내지 않는다", engine.buildSync("tok", mono).watch)
    }

    @Test fun valueArrivingDuringSyncIsSentNext() {
        engine.onDataLayerWatch(watch(null), mono)
        engine.buildSync("tok", mono)
        val newer = watch(wall0 + 3_000)
        engine.onDataLayerWatch(newer, mono + 500) // 보내는 동안 새 값
        ok()
        mono += 2_000
        assertEquals(newer, engine.buildSync("tok", mono).watch)
    }

    /** 오래 실패한 값은 다시 싣지 않는다 — 중계가 받은 시각으로 나이를 재므로 옛 값이 새 값처럼 신선해 보이지 않게. */
    @Test fun staleUnsentValueIsNotResent() {
        engine.onDataLayerWatch(watch(null), mono)
        engine.buildSync("tok", mono)
        engine.onSyncError()
        mono += PhoneEngine.DL_RESEND_MAX_MS + 1
        assertNull(engine.buildSync("tok", mono).watch)
    }
}
