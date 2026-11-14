// 휴대폰이 주인일 때 저장해 두는 상태 기계 스냅숏 — 프로세스가 죽었다 다시 떠도(START_STICKY · 업데이트 · 재부팅) 진행 중 사건을 이어 간다(코드 리뷰 2026-11-02 중 4)
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.protocol.Json
import com.example.guardian.shared.protocol.JsonObj
import com.example.guardian.shared.protocol.SnapshotCodec

/**
 * [snapshot] 의 모든 시각은 **벽시계 ms** 다 — 단조 시계는 재부팅 뒤 0 부터 다시 세므로 저장할 때 벽시계로 옮기고 되살릴 때 되돌린다.
 * 사건 사실(상태 · 경보 · 시각 · 판정)만 담고 이름 · 번호 · 위치 · 심박 수치는 없다(G2). 사건이 끝나면(OFF · S0) 지우고,
 * [PhoneEngine.RESTORE_MAX_AGE_MS] 가 지난 저장은 되살리지 않는다.
 */
data class SavedFlow(val snapshot: FlowSnapshot, val savedAtWallMs: Long) {
    fun encode(): String = Json.encode(linkedMapOf("savedAtWallMs" to savedAtWallMs, "snapshot" to SnapshotCodec.toJson(snapshot)))

    companion object {
        /** 형식이 틀리면 null(그 저장은 버린다). */
        fun decode(s: String): SavedFlow? = runCatching {
            val o = JsonObj(Json.parseObject(s))
            SavedFlow(SnapshotCodec.fromJson(o.obj("snapshot")), o.long("savedAtWallMs"))
        }.getOrNull()
    }
}
