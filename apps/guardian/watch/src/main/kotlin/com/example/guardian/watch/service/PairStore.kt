// 워치 짝 정보 · 철회 뒤 끝낼 토큰 · 건강 관련 동의 · Sensor SDK 지원 목록 기록을 SharedPreferences 에 둔다(백업 제외 — 토큰이 다른 기기로 가지 않게)
package com.example.guardian.watch.service

import android.content.Context
import android.content.SharedPreferences

/** 시험은 SharedPreferences 를 바로 넘긴다(MonitorOffTest). */
class PairStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    val token: String? get() = prefs.getString(K_TOKEN, null)
    val pairId: String? get() = prefs.getString(K_PAIR_ID, null)

    /** 건강 관련 별도 동의(G2). 없으면 워치 직접 경로 · Data Layer 로 값을 보내지 않는다(계약 §8). */
    val healthConsent: Boolean get() = prefs.getLong(K_CONSENT_AT, 0L) > 0L

    fun savePair(token: String, pairId: String) {
        prefs.edit().putString(K_TOKEN, token).putString(K_PAIR_ID, pairId).apply()
    }

    fun clearPair() {
        prefs.edit().remove(K_TOKEN).remove(K_PAIR_ID).apply()
    }

    /** 동의 철회 때 중계에서 아직 지우지 못한 워치 토큰 하나와 저장한 벽시계 시각(재부팅을 넘어 24시간을 재려고 벽시계). */
    data class EndPending(val token: String, val savedAtWallMs: Long)

    /** 「끝낼 토큰」 — 지금 짝(K_TOKEN)과 따로 둔다. 토큰 · 시각 말고는 아무것도 남기지 않는다(G2). */
    val endPending: List<EndPending>
        get() = prefs.getString(K_END_PENDING, null).orEmpty().lines().mapNotNull { line ->
            val sp = line.indexOf(' ')
            if (sp <= 0) null else line.substring(0, sp).toLongOrNull()?.let { EndPending(line.substring(sp + 1), it) }
        }

    /**
     * 동의 철회 — 지금 토큰을 「끝낼 토큰」으로 옮기고 짝을 지운다. 한 번에 디스크까지 쓴다(곧바로 프로세스가 죽어도 잃지 않게).
     * 토큰이 없으면 아무것도 안 하고 null.
     */
    fun moveTokenToEndPending(atWallMs: Long): String? = synchronized(PENDING_LOCK) {
        val token = token ?: return null
        val list = endPending.filter { it.token != token } + EndPending(token, atWallMs)
        prefs.edit().remove(K_TOKEN).remove(K_PAIR_ID).putString(K_END_PENDING, encode(list)).commit()
        token
    }

    /** 끝냈거나(성공 · 401) 24시간이 지난 토큰만 지운다 — 다른 끝낼 토큰 · 지금 짝은 그대로. */
    fun removeEndPending(token: String) = synchronized(PENDING_LOCK) {
        val list = endPending.filter { it.token != token }
        val e = prefs.edit()
        if (list.isEmpty()) e.remove(K_END_PENDING) else e.putString(K_END_PENDING, encode(list))
        e.commit()
    }

    private fun encode(list: List<EndPending>) = list.joinToString("\n") { "${it.savedAtWallMs} ${it.token}" }

    fun setConsent(atWallMs: Long?) {
        prefs.edit().putLong(K_CONSENT_AT, atWallMs ?: 0L).apply()
    }

    /** 사용자가 감시를 켜 둔 상태. 재부팅 · 업데이트 · 프로세스 종료 뒤 다시 켤지 정하는 데 쓴다(ResumeDecision). 끄기 · 동의 철회 때만 지운다. */
    val monitorWanted: Boolean get() = prefs.getBoolean(K_MONITOR_WANTED, false)

    fun setMonitorWanted(on: Boolean) {
        // 수신자 · 화면이 곧바로 다시 읽으므로 commit 으로 디스크까지 쓴다(apply 는 프로세스가 곧 죽으면 잃을 수 있다)
        prefs.edit().putBoolean(K_MONITOR_WANTED, on).commit()
    }

    /** 응답 번호(B-5) — 마지막으로 쓴 값. 응답한 적이 없으면 null. */
    val responseSeq: Long? get() = if (prefs.contains(K_RESPONSE_SEQ)) prefs.getLong(K_RESPONSE_SEQ, 0L) else null

    /** 응답 번호를 1 올려 디스크까지 쓰고(commit — 곧 프로세스가 죽어도 줄지 않게) 돌려준다. */
    fun nextResponseSeq(): Long = synchronized(SEQ_LOCK) {
        val next = (responseSeq ?: 0L) + 1
        prefs.edit().putLong(K_RESPONSE_SEQ, next).commit()
        next
    }

    /** 첫 실행 때 받은 Sensor SDK 지원 트래커 목록(실기기 기록용, R-DEV A). 이미 있으면 덮지 않는다. */
    fun recordCapabilitiesOnce(types: List<String>, atWallMs: Long): Boolean {
        if (prefs.contains(K_CAPABILITIES)) return false
        prefs.edit().putString(K_CAPABILITIES, "$atWallMs ${types.joinToString(",")}").apply()
        return true
    }

    private companion object {
        const val FILE = "guardian_watch"
        const val K_TOKEN = "watchToken"
        const val K_PAIR_ID = "pairId"
        const val K_CONSENT_AT = "healthConsentAt"
        const val K_CAPABILITIES = "sensorSdkCapabilities"
        const val K_MONITOR_WANTED = "monitorWanted"
        const val K_END_PENDING = "endPendingTokens"
        const val K_RESPONSE_SEQ = "responseSeq"
        /** 화면 · 재시도 작업 · 서비스가 각자 PairStore 를 만들어도 끝낼 토큰 목록 고치기는 한 번에 하나. */
        val PENDING_LOCK = Any()
        val SEQ_LOCK = Any()
    }
}
