// 기기 단조 시계와 중계 시계의 차이 추정 — sync 왕복마다 표본을 받아 최근 60초 안에서 왕복이 가장 짧은 표본의 차이를 쓴다
package com.example.guardian.shared.protocol

/**
 * offsetMs = serverTime − (요청 보낸 단조 시각 + 응답 받은 단조 시각) / 2. 중계 시각 = 단조 시각 + offsetMs.
 *
 * 시계 규칙 — 앱은 이 계산기와 상태 기계 입력 atMs 모두에 같은 **단조 시계**(안드로이드 `SystemClock.elapsedRealtime()`)를 쓴다.
 * 벽시계(`System.currentTimeMillis()`)는 사용자 설정 · 네트워크 시각 동기화로 뒤로 갈 수 있어 쓰지 않는다.
 *
 * - 최근 [window] 개, [maxAgeMs](60초) 안의 표본 가운데 왕복이 가장 짧은 것을 쓴다(왕복이 길수록 중간값 가정의 오차가 크다).
 * - 왕복이 [maxRttMs] 를 넘거나 음수인 표본은 받지 않는다.
 * - 받은 시각이 앞 표본보다 이르면(시계가 되돌아감 — 기기 재부팅 등) 옛 표본을 모두 버린다.
 */
class ClockOffset(
    private val window: Int = 8,
    private val maxRttMs: Long = 5_000,
    private val maxAgeMs: Long = 60_000,
) {
    private data class Sample(val receivedMs: Long, val rttMs: Long, val offsetMs: Long)
    private val samples = ArrayDeque<Sample>()

    /** 표본을 넣고 받아들였으면 true. */
    fun add(sentLocalMs: Long, receivedLocalMs: Long, serverTimeMs: Long): Boolean {
        val rtt = receivedLocalMs - sentLocalMs
        if (rtt < 0 || rtt > maxRttMs) return false
        if (samples.isNotEmpty() && receivedLocalMs < samples.last().receivedMs) samples.clear()
        samples.addLast(Sample(receivedLocalMs, rtt, serverTimeMs - (sentLocalMs + rtt / 2)))
        while (samples.size > window) samples.removeFirst()
        return true
    }

    /** [nowLocalMs](단조) 기준 60초 안 표본으로 구한 보정값. 없으면 null. */
    fun offsetAt(nowLocalMs: Long): Long? =
        samples.filter { nowLocalMs - it.receivedMs in 0..maxAgeMs }.minByOrNull { it.rttMs }?.offsetMs
}
