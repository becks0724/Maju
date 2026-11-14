// debug 전용 생체 기록 — 기록 서비스가 쓰고 화면이 1초마다 읽는 상태 묶음(같은 프로세스, 판정 · 경보 상태와 섞지 않는다)
package com.example.guardian.watch.probe

object BioProbeHub {
    data class State(
        val running: Boolean = false,
        /** 시작 단조 시각(SystemClock.elapsedRealtime). */
        val startedAtMs: Long = 0L,
        /** 세션 폴더 이름(`yyyyMMdd-HHmmss`). 끝난 뒤에도 마지막 세션을 보인다. */
        val session: String? = null,
        val hsEnabled: Boolean = false,
        /** [BioStream.COUNTED] 순서의 누적 건수. */
        val counts: List<Long> = BioStream.COUNTED.map { 0L },
        /** 가장 최근 심박 한 줄(출처 · 값 · 상태) — debug 화면 전용. */
        val lastHr: String? = null,
        val batteryPct: Int? = null,
        /** Sensor SDK 연결 상태 한 줄(AAR 없음 · 연결 중 · 지원 트래커 n개 · 실패). */
        val sdk: String? = null,
        /** 마지막 사건(끝 사유 · 오류) 한 줄. */
        val notice: String? = null,
    )

    @Volatile
    var state = State()
        private set

    fun update(f: (State) -> State) {
        synchronized(this) { state = f(state) }
    }
}
