// 사용자 끄기 · 동의 철회의 한 길 — 저장된 켜짐을 지우고 감시 서비스를 멈춘다(지우지 않으면 재부팅 · 업데이트 때 다시 켜진다)
package com.example.guardian.watch.service

import android.content.Context

object MonitorOff {
    fun run(ctx: Context) = run(PairStore(ctx)) { MonitorService.stop(ctx) }

    /** 저장 해제를 먼저 한다 — 서비스가 멈추며 화면이 다시 판단(ResumeDecision APP_OPEN)할 때 이미 꺼짐이어야 한다. */
    fun run(store: PairStore, stopService: () -> Unit) {
        store.setMonitorWanted(false)
        stopService()
    }
}
