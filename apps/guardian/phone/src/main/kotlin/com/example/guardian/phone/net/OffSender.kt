// 전달하지 못한 끄기(C-6)를 따로 저장하고 앱 열기 · 부팅 수신 때 꺼짐 sync 를 다시 보내는 Android 쪽 — 규칙은 core/OffDelivery
package com.example.guardian.phone.net

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.guardian.phone.core.OffDelivery
import com.example.guardian.phone.core.OffDeliveryStore
import com.example.guardian.phone.data.Prefs
import java.util.concurrent.Executors

object OffSender {
    private const val TAG = "GuardianPhone"

    /** 등록 정보 저장(Prefs.FILE)과 다른 파일 — 값은 「남았나」 하나뿐이고 토큰은 Prefs 의 것을 쓴다(토큰이 없으면 버린다). */
    private const val FILE = "guardian_off_pending"
    private const val KEY = "offPending"

    private val worker = Executors.newSingleThreadExecutor()

    private class Store(ctx: Context) : OffDeliveryStore {
        private val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        override var offPending: Boolean
            get() = sp.getBoolean(KEY, false)
            set(v) { sp.edit().apply { if (v) putBoolean(KEY, true) else remove(KEY) }.commit() }
    }

    /** 서비스가 쓰는 규칙 객체 — 저장은 이 파일. */
    fun delivery(ctx: Context) = OffDelivery(Store(ctx))

    /** 남긴 꺼짐이 있고 지금 꺼져 있으며 짝이 있으면 꺼짐 sync 를 한 번 보낸다(작업 스레드). 켜져 있으면 버린다. */
    fun retry(ctx: Context, relay: RelayClient = RelayClient()) {
        val app = ctx.applicationContext
        worker.execute {
            val off = delivery(app)
            val prefs = Prefs(app)
            val token = prefs.token
            if (!off.due(prefs.registration.powerOn, token != null && relay.configured)) return@execute
            val reg = prefs.registration
            val req = OffDelivery.request(token!!, System.currentTimeMillis(), SystemClock.elapsedRealtime(), reg)
            val r = result(relay.sync(req, reg.consents.health))
            Log.i(TAG, "꺼짐 다시 보내기 $r") // 결과 종류만 — 토큰은 남기지 않는다
            off.onSent(r)
        }
    }

    fun result(r: RelayResult<*>?): OffDelivery.Result = when (r) {
        null -> OffDelivery.Result.NOT_SENT
        is RelayResult.Ok -> OffDelivery.Result.DELIVERED
        RelayResult.Unauthorized -> OffDelivery.Result.GONE
        else -> OffDelivery.Result.FAILED
    }
}
