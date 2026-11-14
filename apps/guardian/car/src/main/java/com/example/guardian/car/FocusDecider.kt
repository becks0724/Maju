// 차 음성의 오디오 포커스 판단(순수) — 요청 · 변화 · 반납을 상태로 다루고 할 일 목록을 돌려준다. CarVoice 가 실행하고 JVM 시험이 판단을 확인한다(Pleos I-3 · I-7 · I-9)
package com.example.guardian.car

/**
 * 규칙(레드블루 1차 설계 + app-qa 조건).
 * - 요청 결과 GRANTED 면 말한다. DELAYED 면 기다렸다가, 마지막 줄이 온 지 [resumeWindowMs] 안에 GAIN 이 오면 그 줄을 말한다.
 *   넘으면 말하지 않고 반납한다 — 사건이 끝나 say 가 더 오지 않을 때 낡은 경보를 말하지 않게(app-qa 관문 2).
 * - FAILED 면 [retryDelayMs] 뒤 한 번만 다시 요청한다. 그사이 온 새 줄은 바꿔 끼우기만 한다(새 것만 말함). 두 번째도 거부면 버린다(화면 줄은 남는다).
 * - LOSS_TRANSIENT 면 멈추고 포커스는 쥔 채 그 줄을 보류한다. [resumeWindowMs] 안에 GAIN 이 오면 다시 말하고, 넘으면 버리고 반납한다.
 * - LOSS 면 멈추고 반납한다. 다음 단계 음성(say)은 새로 요청한다.
 * - CAN_DUCK 은 시스템 감쇄에 맡기고 기록만 한다(AAOS 자동 감쇄 여부는 [미검증]).
 * 로그에는 문구 원문 대신 이름([label], Phrase 이름)만 남긴다(G2 — 개인 값 없음).
 */
class FocusDecider(private val retryDelayMs: Long = 3_000L, private val resumeWindowMs: Long = 10_000L) {
    enum class Result { GRANTED, FAILED, DELAYED }
    enum class Change { GAIN, LOSS, LOSS_TRANSIENT, CAN_DUCK }

    sealed interface Act {
        /** 포커스를 요청하고 결과를 [onRequest] 로 돌려준다. */
        data object Request : Act
        data class Speak(val text: String) : Act
        /** 지금 말하던 것을 멈춘다. */
        data object Stop : Act
        data class Abandon(val reason: String) : Act
        /** [ms] 뒤 [onRetryTimer] 를 부른다. */
        data class RetryIn(val ms: Long) : Act
        data class Log(val line: String) : Act
    }

    private enum class State { IDLE, REQUESTING, WAIT_DELAYED, WAIT_RETRY, SPEAKING, PAUSED }

    private var state = State.IDLE
    private var text: String? = null
    private var label: String = ""
    private var attempt = 0
    private var retryAtMs = 0L
    private var pausedAtMs = 0L
    /** 마지막 say 시각 — 지연 포커스를 늦게 얻었을 때 줄이 낡았는지 본다. */
    private var saidAtMs = 0L

    fun say(text: String, label: String, nowMs: Long): List<Act> {
        this.text = text
        this.label = label
        saidAtMs = nowMs
        return when (state) {
            State.IDLE -> { state = State.REQUESTING; attempt = 1; listOf(Act.Request) }
            State.SPEAKING -> listOf(Act.Speak(text))
            State.PAUSED -> { pausedAtMs = nowMs; listOf(Act.Log("포커스 일시 손실 중 — 보류 문구=$label")) }
            State.REQUESTING, State.WAIT_DELAYED, State.WAIT_RETRY -> emptyList()
        }
    }

    fun onRequest(result: Result, nowMs: Long): List<Act> {
        if (state != State.REQUESTING) return emptyList()
        val log = Act.Log("포커스 요청 usage=NAVIGATION_GUIDANCE gain=TRANSIENT 결과=${result.name} 시도=$attempt 문구=$label")
        return when (result) {
            Result.GRANTED -> { state = State.SPEAKING; listOf(log, Act.Speak(text!!)) }
            Result.DELAYED -> { state = State.WAIT_DELAYED; listOf(log) }
            Result.FAILED -> if (attempt < 2) {
                state = State.WAIT_RETRY
                retryAtMs = nowMs + retryDelayMs
                listOf(log, Act.RetryIn(retryDelayMs))
            } else {
                clear()
                listOf(log, Act.Log("포커스 다시 거부 — 말하지 않음(화면 줄은 남음)"))
            }
        }
    }

    fun onRetryTimer(nowMs: Long): List<Act> {
        if (state != State.WAIT_RETRY || nowMs < retryAtMs) return emptyList()
        state = State.REQUESTING
        attempt = 2
        return listOf(Act.Request)
    }

    fun onChange(change: Change, nowMs: Long): List<Act> {
        val log = Act.Log("포커스 변화 ${change.name} 상태=${state.name} 문구=$label")
        return when (change) {
            Change.CAN_DUCK -> listOf(log)
            Change.LOSS_TRANSIENT -> if (state == State.SPEAKING) {
                state = State.PAUSED
                pausedAtMs = nowMs
                listOf(log, Act.Stop)
            } else listOf(log)
            Change.LOSS -> when (state) {
                State.SPEAKING, State.PAUSED -> { clear(); listOf(log, Act.Stop, Act.Abandon("영구 손실")) }
                State.WAIT_DELAYED -> { clear(); listOf(log, Act.Abandon("영구 손실")) }
                else -> listOf(log)
            }
            Change.GAIN -> when (state) {
                State.WAIT_DELAYED -> if (nowMs - saidAtMs <= resumeWindowMs) {
                    state = State.SPEAKING
                    listOf(log, Act.Speak(text!!))
                } else {
                    clear()
                    listOf(log, Act.Abandon("지연 포커스 ${resumeWindowMs / 1_000}초 넘음"), Act.Log("포커스 반납(늦게 얻어 보류 문구 버림)"))
                }
                State.PAUSED -> if (nowMs - pausedAtMs <= resumeWindowMs) {
                    state = State.SPEAKING
                    listOf(log, Act.Speak(text!!))
                } else {
                    clear()
                    listOf(log, Act.Abandon("일시 손실 ${resumeWindowMs / 1_000}초 넘음"), Act.Log("포커스 반납(일시 손실이 길어 보류 문구 버림)"))
                }
                else -> listOf(log)
            }
        }
    }

    /** TTS 완료 · 중지 · 오류. 일시 손실로 멈춘 것의 중지 콜백은 무시한다(포커스를 쥐고 돌아오기를 기다림). */
    fun onSpeechEnd(reason: String): List<Act> {
        if (state != State.SPEAKING) return emptyList()
        clear()
        return listOf(Act.Abandon(reason), Act.Log("발화 끝 · 포커스 반납($reason)"))
    }

    /** 앱 종료 — 쥐고 있거나 기다리던 포커스를 놓는다. */
    fun shutdown(): List<Act> {
        val had = state != State.IDLE
        clear()
        return if (had) listOf(Act.Stop, Act.Abandon("종료")) else listOf(Act.Stop)
    }

    private fun clear() {
        state = State.IDLE
        text = null
        attempt = 0
    }
}
