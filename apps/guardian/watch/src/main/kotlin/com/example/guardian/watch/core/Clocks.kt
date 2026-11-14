// 단조 시계(판정 · 타이머)와 벽시계(보낼 때 표시용)를 한곳에서 받는 시계 묶음
package com.example.guardian.watch.core

/**
 * 판정 · 타이머 · 샘플 시각은 모두 [mono](안드로이드 `SystemClock.elapsedRealtime`)로 잰다(계약 §2 단조 시계 규칙).
 * 벽시계는 계약이 에포크 ms 로 정한 `sentAt` · `lastPulseAt` · `responseAt` 을 보낼 때만 [toWall] 로 바꿔 쓴다.
 */
interface Clocks {
    fun mono(): Long
    fun wall(): Long

    /** 단조 시각 [monoMs] 를 지금 기준 벽시계 시각으로 바꾼다. */
    fun toWall(monoMs: Long): Long = wall() - (mono() - monoMs)

    /** 벽시계 시각 [wallMs](예 Sensor SDK 샘플 시각)를 지금 기준 단조 시각으로 바꾼다. */
    fun toMono(wallMs: Long): Long = mono() - (wall() - wallMs)
}
