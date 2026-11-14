// 시험용 시계 — 단조 시각과 벽시계를 손으로 움직인다(벽시계 = 단조 + 차, 차는 NTP 보정 · 읽기 차이를 흉내 내려고 바꿀 수 있다)
package com.example.guardian.watch

import com.example.guardian.watch.core.Clocks

class FakeClocks(var monoMs: Long = 1_000_000L, var wallMinusMono: Long = 1_793_000_000_000L) : Clocks {
    override fun mono() = monoMs
    override fun wall() = monoMs + wallMinusMono
}
