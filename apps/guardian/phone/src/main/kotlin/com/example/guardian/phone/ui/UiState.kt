// 서비스(엔진 스레드)가 화면에 알리는 상태 — 값은 늘 메인 스레드에서 바꾼다
package com.example.guardian.phone.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import com.example.guardian.phone.core.Body119
import com.example.guardian.phone.core.EngineStatus
import com.example.guardian.phone.core.PendingSms

/** 서버 연결 상태(P08 연결 줄 · P19 띠). */
enum class ServerLink { NOT_PAIRED, NO_RELAY, OK, FAILED, EXPIRED }

/**
 * 보호자 문자 기록 한 줄(P12 · P14). [kind] 는 EVENT · CANCEL · ACCIDENT · OBSERVE. [sent] 보냄 true · 보내지 못함 false · 확인 못 함 null.
 * [eventId] 는 그 문자의 사건 — 화면은 지금 사건의 기록만 보인다(B1 결함 6).
 */
data class SmsLogItem(val kind: String, val atWall: Long, val sent: Boolean?, val eventId: String)

object UiState {
    private val main = Handler(Looper.getMainLooper())

    val status = mutableStateOf<EngineStatus?>(null)
    /** 마지막 안내 한 줄(SHOW 지시 또는 시험 중 차 화면 문구). */
    val line = mutableStateOf<String?>(null)
    val notice = mutableStateOf<String?>(null)
    val pendingSms = mutableStateOf<List<PendingSms>>(emptyList())
    /** 테스트 · previewOnly 119 미리보기(P18). */
    val preview119 = mutableStateOf<Body119?>(null)
    /** 정차 뒤 무응답(P12)에 보일 119 본문 — 작성 화면은 사람이 버튼으로 연다. */
    val noResponse = mutableStateOf<Body119?>(null)
    val composeOpenedAt = mutableStateOf<Long?>(null)
    val smsLog = mutableStateOf<List<SmsLogItem>>(emptyList())
    val server = mutableStateOf(ServerLink.NOT_PAIRED)
    val running = mutableStateOf(false)

    /** 화면이 보이는 중인가 — 보일 때만 액티비티를 바로 연다(안드로이드 백그라운드 실행 제한). */
    @Volatile var foreground = false

    fun post(block: () -> Unit) { main.post(block) }
}
