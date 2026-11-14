// 워치에 지시를 보낼 수 있는지 판단 — 워치 지시는 중계 직접 경로로만 닿으므로 직접 경로 나이(watchDirectAgeMs)로 본다(계약 §4.3 · §4.7)
package com.example.guardian.shared.protocol

import com.example.guardian.shared.flow.WatchFreshness

/**
 * 워치에 가는 지시(ALERT_START · ALERT_STOP · TEST_SCENARIO · TEST_SCENARIO_STOP)는 워치가 중계에 직접 붙어 있을 때만 받는다
 * (PM 결정 2026-10-30). Data Layer 는 워치 → 휴대폰 상태 데이터만 나르는 보조 경로다.
 *
 * 중계가 고른 워치 값([Watch.via])은 더 최근 것만 싣기 때문에 직접 경로가 살아 있어도 `via=PHONE` 일 수 있다.
 * 그래서 응답의 `watchDirectAgeMs`(직접 값의 나이, 중계 시계)를 따로 보고, 신선도 규칙(30초, [WatchFreshness.STALE_MS])을 그대로 적용한다.
 */
object WatchReach {
    /** 응답의 [watchDirectAgeMs] 를 지금([nowLocalMs], 단조) 기준 나이로 고친다. 직접 값이 없으면 null. */
    fun directAgeNow(watchDirectAgeMs: Long?, responseReceivedAtLocalMs: Long, nowLocalMs: Long): Long? =
        watchDirectAgeMs?.let { WatchFreshness.viaRelay(it.coerceAtLeast(0), responseReceivedAtLocalMs, nowLocalMs) }

    /** 직접 경로 값이 있고 30,000ms 이하로 신선하면 true. 직접 값이 없거나(휴대폰 경유만 · 연결 없음) 30,001ms 부터 false. */
    fun canCommandWatch(watchDirectAgeMs: Long?, responseReceivedAtLocalMs: Long, nowLocalMs: Long): Boolean =
        directAgeNow(watchDirectAgeMs, responseReceivedAtLocalMs, nowLocalMs)?.let { !WatchFreshness.isStale(it) } == true
}
