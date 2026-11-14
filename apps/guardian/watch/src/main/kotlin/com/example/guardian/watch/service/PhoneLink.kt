// 짝 휴대폰에 우리 휴대폰 앱이 있으면 Wearable Data Layer MessageClient 로 워치 상태 요약을 보낸다(선택 경로, 설계 §2)
package com.example.guardian.watch.service

import android.content.Context
import com.example.guardian.shared.protocol.DataLayer
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

/**
 * 휴대폰 앱이 capability [DataLayer.CAPABILITY_PHONE] 을 선언하면 그 기기를 찾아 [DataLayer.PATH_WATCH] 로
 * Watch JSON(계약 §4.3 필드만, UTF-8)을 보낸다(계약 §4.7, 워치 → 휴대폰 한 방향). 원신호는 싣지 않는다(G2).
 * 휴대폰 · Play 서비스가 없으면 조용히 건너뛴다.
 */
class PhoneLink(context: Context) {
    private val capabilities = Wearable.getCapabilityClient(context)
    private val messages = Wearable.getMessageClient(context)

    @Volatile private var nodeId: String? = null
    private var lookedUpAtMs: Long? = null

    /** [onSent] 는 보내기가 성공했을 때(노드 큐에 들어감) 부른다 — 응답 뒤 연결 증거(C-3). */
    fun send(json: String, nowMs: Long, onSent: () -> Unit = {}) {
        val last = lookedUpAtMs
        if (last == null || nowMs - last >= LOOKUP_EVERY_MS) {
            lookedUpAtMs = nowMs
            capabilities.getCapability(DataLayer.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info -> nodeId = (info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull())?.id }
                .addOnFailureListener { nodeId = null }
        }
        val id = nodeId ?: return
        messages.sendMessage(id, DataLayer.PATH_WATCH, json.toByteArray(Charsets.UTF_8))
            .addOnSuccessListener { onSent() }
            .addOnFailureListener { nodeId = null }
    }

    private companion object {
        const val LOOKUP_EVERY_MS = 30_000L
    }
}
