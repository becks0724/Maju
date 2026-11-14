// 서비스와 화면 사이의 상태 다리 — 감시 서비스가 상태를 쓰고 화면(Compose)이 메인 스레드에서 듣는다(같은 프로세스 안)
package com.example.guardian.watch.service

import android.os.Handler
import android.os.Looper
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.WatchSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 화면에 심박 수치 · 맥박 신호 상태를 상시 보이지 않는다(spec §5-9 W11, G1 — 건강 측정 화면으로 읽히지 않게).
 * [verdict] 는 착용 확인 화면(W05)을 고르는 데만 쓴다. 시각은 모두 단조 ms.
 */
object WatchHub {
    enum class Link {
        NONE, OK, RETRY, REPAIR;

        /**
         * 토큰이 없을 때의 연결 상태 — 401 로 지운 뒤의 「연결 기간이 끝났습니다」(REPAIR)는 2초 sync 가 덮지 않고
         * 다시 짝을 맺거나 동의를 철회할 때([clearRepair])까지 둔다(코드 리뷰 11-02 · 11-08 하).
         */
        fun withoutToken(): Link = if (this == REPAIR) REPAIR else NONE
    }

    data class AlertView(
        val stage: FlowState, val level: Int, val test: Boolean, val durationMs: Long, val startedAtMs: Long, val responded: Boolean,
        /** 응답 뒤 연결 증거가 없어 출력(진동 · 소리 · 최대 밝기)을 멈췄다(C-3). 화면 · 버튼은 그대로다. */
        val muted: Boolean = false,
    )

    data class TestView(val scenario: Scenario, val endsAtMs: Long)

    data class UiState(
        val monitoring: Boolean = false,
        val verdict: WristVerdict? = null,
        val source: WatchSource? = null,
        val lowConfidence: Boolean = false,
        /** 실제 출처가 신호를 못 잡고 있다(Health Services 유효 심박 10초 공백 · 등록 실패) — 첫 화면 · 상태 화면 한 줄. */
        val noSignal: Boolean = false,
        val alert: AlertView? = null,
        val test: TestView? = null,
        val link: Link = Link.NONE,
        val lastSyncOkAtMs: Long? = null,
        val viaWifi: Boolean = false,
        /** 응답한 경보가 ALERT_STOP 으로 끝난 시각 — 응답 확인됨(W08)을 5초 보인다. */
        val respondedClearedAtMs: Long? = null,
    )

    @Volatile
    var state = UiState()
        private set

    private val listeners = CopyOnWriteArrayList<(UiState) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /**
     * 상태가 그대로면(data class 비교) 메인 스레드를 깨우지 않는다 — 1초 tick 마다 같은 값을 알리지 않게(배터리).
     * 듣는 쪽(화면 mutableStateOf)은 같은 값이면 원래 아무것도 하지 않는다. post 는 잠금 안에서 해 알림 순서가
     * 상태 순서와 같다 — 건너뛴 뒤에도 마지막으로 알린 값이 지금 상태다.
     */
    fun update(f: (UiState) -> UiState) {
        synchronized(this) {
            val old = state
            val s = f(old)
            state = s
            if (s != old) main.post { listeners.forEach { it(s) } }
        }
    }

    /** 연결 기간 끝 안내를 거둔다 — 다시 짝을 맺었거나 동의를 철회했을 때. */
    fun clearRepair() = update { if (it.link == Link.REPAIR) it.copy(link = Link.NONE) else it }

    /** 듣기 시작하고, 끊는 함수를 돌려준다. */
    fun listen(l: (UiState) -> Unit): () -> Unit {
        listeners += l
        l(state)
        return { listeners -= l }
    }
}
