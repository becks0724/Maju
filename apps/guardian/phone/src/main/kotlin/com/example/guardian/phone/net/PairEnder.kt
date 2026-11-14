// 짝 끝내기(pair/end)를 부르고, 끝내지 못한 토큰만 따로 저장해 다음 앱 실행 · 서비스 시작 · 중계 다시 닿음 때 다시 부르는 Android 쪽 — 규칙은 core/PendingEnd
package com.example.guardian.phone.net

import android.content.Context
import android.util.Log
import com.example.guardian.phone.core.PendingEnd
import com.example.guardian.phone.core.PendingEndStore
import com.example.guardian.phone.data.Prefs
import java.util.concurrent.Executors

object PairEnder {
    private const val TAG = "GuardianPhone"

    /** 등록 정보 저장(Prefs.FILE)과 다른 파일 — 「모두 지우기」가 지우지 않는다. 자동 백업 · 기기 이전 제외는 매니페스트 규칙(sharedpref 전체)이 덮는다. */
    private const val FILE = "guardian_pair_end"
    private const val KEY = "pendingEnds"

    /** 시도 · 다시 시도를 한 줄로 세워 저장 목록을 읽고 쓰는 일이 겹치지 않게 한다. */
    private val worker = Executors.newSingleThreadExecutor()

    private class Store(ctx: Context) : PendingEndStore {
        private val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        override var pendingEnds: List<PendingEnd.Entry>
            get() = PendingEnd.decode(sp.getString(KEY, null))
            set(v) { sp.edit().apply { if (v.isEmpty()) remove(KEY) else putString(KEY, PendingEnd.encode(v)) }.commit() }
    }

    /** 철회의 짝 끝내기 — 작업 스레드에서 토큰을 먼저 남기고 한 번 부른다. 성공 · 401 이면 지우고, 실패면 토큰만 남는다(감사 하2 · 코드 리뷰 2026-11-09 하). */
    fun end(ctx: Context, token: String, relay: RelayClient = RelayClient()) {
        val app = ctx.applicationContext
        worker.execute { PendingEnd.attempt(Store(app), token, System.currentTimeMillis()) { call(relay, it) } }
    }

    /** 남은 끝낼 토큰을 다시 부른다. 지금 짝의 토큰과 같으면 부르지 않는다(새 짝과 섞이지 않게). */
    fun retry(ctx: Context, relay: RelayClient = RelayClient()) {
        val app = ctx.applicationContext
        worker.execute {
            val store = Store(app)
            if (store.pendingEnds.isEmpty()) return@execute
            PendingEnd.retry(store, Prefs(app).token, System.currentTimeMillis()) { call(relay, it) }
        }
    }

    /** 로그에는 결과 종류(HTTP 상태 코드 · 네트워크 오류)만 남긴다 — 토큰은 남기지 않는다. */
    private fun call(relay: RelayClient, token: String): PendingEnd.Result {
        val r = relay.end(token)
        val kind = when (r) {
            is RelayResult.Ok -> "200"
            RelayResult.Unauthorized -> "401"
            is RelayResult.Failed -> r.status.toString()
            is RelayResult.NetworkError -> "네트워크 오류"
        }
        Log.i(TAG, "pair/end $kind")
        return when (r) {
            is RelayResult.Ok -> PendingEnd.Result.ENDED
            RelayResult.Unauthorized -> PendingEnd.Result.GONE
            else -> PendingEnd.Result.FAILED
        }
    }
}
