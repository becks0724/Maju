// 끄기 · 철회 주기 끝에서 서비스가 언제 멈추고 무엇을 지울지 정하는 판단 — 서비스 생명주기는 기기에서만 보여 판단만 떼어 시험한다(코드 리뷰 2026-11-09)
package com.example.guardian.phone.core

/**
 * 서비스 한 실행의 멈춤 판단.
 * - 멈출 start id — 마지막 sync 를 기다리는 사이 다시 켰으면 멈추지 않는다. 마지막으로 받은 시작 id 를 **먼저** 잡고 켬을 읽어,
 *   그 뒤에 온 새 시작은 `stopSelf(startId)` 가 멈추지 않는다(하 의심).
 * - 철회였으면 멈출 때 저장을 한 번 더 지운다 — 마지막 sync 를 기다리는 몇 초 사이 실행기가 지시 id · 문자 기록을 다시 쓸 수 있어서다(하1, G2).
 *   끄기는 짝 · 등록 정보 · 문자 기록을 남기므로 지우지 않는다. 그사이 다시 켰으면 새 등록을 지우지 않는다.
 */
class ServiceStop {
    /** 이 실행이 철회의 마지막 sync 를 맡았는가. */
    @Volatile var withdrew = false
        private set
    @Volatile private var latestStartId = 0

    /** onStartCommand 마다. */
    fun onStart(startId: Int) { latestStartId = startId }

    /** 철회가 마지막 sync 를 맡겼다. */
    fun onWithdraw() { withdrew = true }

    /** 마지막 sync 를 마친 뒤 멈출 start id. 지금 켬이면 null(멈추지 않고 이어 간다 — 철회 표시도 거둔다). */
    fun stopId(powerOnNow: () -> Boolean): Int? {
        val id = latestStartId
        if (powerOnNow()) { withdrew = false; return null }
        return id
    }

    /**
     * 문자 결과를 화면 문자 기록(UiState.smsLog, 메모리)에 넣을까 — 철회가 그 기록을 비운 뒤 마지막 sync 를 기다리는 사이 늦게 온 결과는
     * 넣지 않는다(코드 리뷰 2026-11-09 새로 찾은 것, G2). 그사이 다시 켜 이어 가면([stopId]) 다시 넣는다.
     */
    fun acceptsSmsLog(): Boolean = !withdrew

    /** 멈출 때(엔진 스레드의 마지막 일) — 철회였고 아직 꺼져 있으면 [clear](저장 모두 지우기)를 부른다. */
    fun onStopped(powerOnNow: Boolean, clear: () -> Unit) {
        if (withdrew && !powerOnNow) clear()
    }
}
