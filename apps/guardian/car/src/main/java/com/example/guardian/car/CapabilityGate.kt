// Pleos Vehicle 능력 확인 결과를 담아 질의를 할지 · 다시 확인할지 · 에어백 안내를 띄울지 정하는 순수 판단(Pleos F-2 「지원 확인 후 사용」)
package com.example.guardian.car

/**
 * 규칙(레드블루 1차 설계 + app-qa 조건).
 * - Vehicle 초기화 뒤 에어백 · 브레이크 페달 · 착석 능력을 한 번씩 확인한다. 답이 오기 전에는 지금처럼 묻는다.
 * - 읽기 권한이 없거나 운전석(ROW_1_LEFT)이 목록에 없으면 「미지원」, 확인 자체가 실패하면 「오류」 — 둘 다 그 질의를 멈춘다.
 * - 오류(또는 답 없음)는 [recheckMs](10분)마다 다시 확인한다. 미지원은 차가 바뀌지 않으니 다음 시작 때 다시 확인한다(상태를 저장하지 않음).
 * - 에어백을 못 읽으면 정차 화면의 상세 안내(고지 자리)에 한 줄 — 1~2초 파악 원칙에 따라 상태 줄에는 두지 않는다.
 * 여러 스레드(메인 · SDK 콜백)에서 불릴 수 있어 동기화한다.
 */
class CapabilityGate(private val recheckMs: Long = 10 * 60_000L) {
    enum class Signal { AIRBAG, BRAKE, SEAT }
    enum class Cap { UNCHECKED, OK, UNSUPPORTED, ERROR }

    private val caps = Signal.entries.associateWith { Cap.UNCHECKED }.toMutableMap()
    private var lastCheckMs: Long? = null

    @Synchronized fun status(s: Signal): Cap = caps.getValue(s)

    @Synchronized fun shouldQuery(s: Signal): Boolean = caps.getValue(s) == Cap.UNCHECKED || caps.getValue(s) == Cap.OK

    @Synchronized fun checkDue(nowMs: Long): Boolean {
        val last = lastCheckMs ?: return true
        val open = caps.values.any { it == Cap.ERROR || it == Cap.UNCHECKED }
        return open && nowMs - last >= recheckMs
    }

    @Synchronized fun onChecking(nowMs: Long) { lastCheckMs = nowMs }

    /** [readable] — accessType 이 READ · READ_WRITE. [driverSeat] — 영역 목록에 운전석이 있음(영역이 없는 브레이크는 참). */
    @Synchronized fun onResult(s: Signal, readable: Boolean, driverSeat: Boolean) {
        caps[s] = if (readable && driverSeat) Cap.OK else Cap.UNSUPPORTED
    }

    @Synchronized fun onError(s: Signal) { caps[s] = Cap.ERROR }

    /** 상세 안내 — 에어백 전개 신호를 읽지 못함(미지원 · 오류). 순정 SOS 를 약속하는 말은 쓰지 않는다(G7). */
    @Synchronized fun airbagUnreadable(): Boolean = caps.getValue(Signal.AIRBAG).let { it == Cap.UNSUPPORTED || it == Cap.ERROR }
}
