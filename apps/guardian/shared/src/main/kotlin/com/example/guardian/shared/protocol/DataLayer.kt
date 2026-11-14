// 워치 ↔ 휴대폰 Wearable Data Layer 상수 — 메시지 경로 · capability 이름(계약 §4.7)
package com.example.guardian.shared.protocol

/**
 * 워치 → 휴대폰 한 방향만 있다. 휴대폰 → 워치 Data Layer 지시는 **없다** — 워치 지시는 중계 `to:WATCH` 로만 간다.
 * 이 경로는 선택 경로다(워치가 중계에 직접 붙으면 쓰지 않아도 된다, 계약 §1).
 */
object DataLayer {
    /** MessageClient 경로. 본문은 [Watch] JSON(UTF-8, `Watch.toJson`), 약 1초마다. */
    const val PATH_WATCH = "/guardian/watch"

    /** 워치 앱이 광고하는 capability. 휴대폰은 이것으로 보낼 노드를 고른다. */
    const val CAPABILITY_WATCH = "guardian_watch"

    /** 휴대폰 앱이 광고하는 capability. 워치는 이것으로 보낼 노드를 고른다. */
    const val CAPABILITY_PHONE = "guardian_phone"

    /** 워치 → 휴대폰 보내는 주기(ms) [추정 — 배터리 실측으로 조정]. */
    const val WATCH_INTERVAL_MS = 1_000L
}
