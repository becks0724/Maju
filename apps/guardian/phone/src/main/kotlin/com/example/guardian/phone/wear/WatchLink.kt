// 워치 → 휴대폰 Data Layer 경로와 받은 워치 상태 보관함(선택 경로 — 시험 구성(Note10 ↔ Watch9 짝 불가)에서는 워치가 중계에 직접 붙는다)
package com.example.guardian.phone.wear

import android.os.SystemClock
import com.example.guardian.shared.protocol.DataLayer
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.Watch

/**
 * 워치는 MessageClient 경로 [STATE_PATH] 로 계약 §4.3 Watch JSON(UTF-8)을 1초마다 보낸다(app-wear 확정 2026-10-30).
 * 휴대폰 → 워치 Data Layer 지시 경로는 두지 않는다 — TEST_SCENARIO 등 워치 지시는 중계(to WATCH)로만 보낸다(PM 지시).
 * 경로 · capability 이름은 :shared [DataLayer] 상수(워치와 같은 값). capability 는 `res/values/wear.xml` 에 있고 시험이 상수와 같은지 본다.
 */
object WatchLink {
    const val STATE_PATH = DataLayer.PATH_WATCH
    /** DataItem 으로 올 때의 키(대비용). */
    const val DATA_KEY = "json"

    private var latest: Pair<Watch, Long>? = null
    @Volatile private var listener: (() -> Unit)? = null

    /** 받은 값과 받은 단조 시각. 형식이 틀리면 버린다. */
    fun onBytes(bytes: ByteArray) {
        val w = runCatching { Watch.fromJson(JsonObj(Json.parseObject(bytes.toString(Charsets.UTF_8)))) }.getOrNull() ?: return
        synchronized(this) { latest = w to SystemClock.elapsedRealtime() }
        listener?.invoke()
    }

    /** 새 값만 한 번 꺼낸다. */
    fun take(): Pair<Watch, Long>? = synchronized(this) { latest.also { latest = null } }

    fun setListener(l: (() -> Unit)?) { listener = l }
}
