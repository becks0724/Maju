// 차 지시 · 대조 길로 나가는 보호자 문자의 원격 상한(D-6, 사용자 확정 11-14) — 문자 수로 일반 · 사고 칸 각각 10분 3통 · 24시간 10통, 저장 형식
package com.example.guardian.phone.core

import com.example.guardian.shared.protocol.GuardianSmsWireKind
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 차 토큰을 가진 쪽이 보호자에게 위치가 든 문자를 잇달아 보내게 하지 못하게 한다(합동 점검 D-6).
 * - 세는 것 — 차가 보낸 지시 · 대조 길(A-3) · 넘겨받은 사건으로 나가는 실제 문자 한 통마다 1(PM 결정 22 — 사용자 확정값의 말이 「통」).
 *   일반 칸은 사건 · 정차 관찰 문자, 사고 칸은 사고 문자이고 두 칸이 같은 값(10분 3통 · 24시간 10통)을 따로 센다(PM 결정 27)
 *   — 사고 문자는 일반 칸에 세지도 막히지도 않는다.
 *   같은 문자(종류 · 사건 id 가 같음)의 다시 보내기 · 다시 싣기는 한 번만 센다.
 * - 세지 않는 것 — 휴대폰이 스스로 연 사건의 문자, 취소 문자(위치 없음), 테스트 문자. 사람이 누른 「다시 보내기」는 막지 않지만 칸에는 센다.
 * - 시계 — 벽시계. 기록보다 앞으로 되돌린 시각에서는 그 기록을 「지금」 것으로 친다(되돌려도 풀리지 않음). 앱을 다시 띄워도 남게 저장한다.
 * 상한에 걸려 보내지 않은 문자는 키(`KIND:eventId`)를 [State.blockedKeys] 에 남겨 홈 기록이 이유를 말하게 한다.
 */
object RemoteSmsLimit {
    const val SHORT_MS = 10 * 60 * 1000L
    const val SHORT_MAX = 3
    const val DAY_MS = 24 * 60 * 60 * 1000L
    const val DAY_MAX = 10

    data class Entry(val kind: GuardianSmsWireKind, val eventId: String, val atWall: Long)
    data class State(val sent: List<Entry> = emptyList(), val blockedKeys: Set<String> = emptySet())

    /** 세는 종류인가 — 위치가 실리는 사건 · 정차 관찰 · 사고 문자. */
    fun counted(kind: GuardianSmsWireKind) = kind != GuardianSmsWireKind.CANCEL

    private fun age(e: Entry, nowWall: Long) = (nowWall - e.atWall).coerceAtLeast(0)

    private fun accident(k: GuardianSmsWireKind) = k == GuardianSmsWireKind.ACCIDENT
    private fun same(st: State, kind: GuardianSmsWireKind, eventId: String) = st.sent.any { it.kind == kind && it.eventId == eventId }

    /** 이 문자를 보내도 되는가. 같은 문자를 이미 셌으면(다시 보내기) 늘 된다. */
    fun allows(st: State, kind: GuardianSmsWireKind, eventId: String, nowWall: Long): Boolean {
        if (!counted(kind) || same(st, kind, eventId)) return true
        val bucket = st.sent.filter { accident(it.kind) == accident(kind) }
        return bucket.count { age(it, nowWall) <= SHORT_MS } < SHORT_MAX && bucket.count { age(it, nowWall) <= DAY_MS } < DAY_MAX
    }

    /** 보내기로 정한 문자를 센다(같은 문자를 이미 셌으면 그대로). 하루 지난 기록은 버린다. */
    fun record(st: State, kind: GuardianSmsWireKind, eventId: String, nowWall: Long): State {
        if (!counted(kind) || same(st, kind, eventId)) return st
        return st.copy(sent = (st.sent + Entry(kind, eventId, nowWall)).filter { age(it, nowWall) <= DAY_MS })
    }

    /** 보내지 못함으로 끝난 문자를 칸에서 뺀다(같은 문자의 자동 다시 보내기가 나가면 그때 다시 센다). */
    fun unrecord(st: State, kind: GuardianSmsWireKind, eventId: String): State =
        st.copy(sent = st.sent.filterNot { it.kind == kind && it.eventId == eventId })

    fun block(st: State, kind: GuardianSmsWireKind, eventId: String): State = st.copy(blockedKeys = st.blockedKeys + "${kind.name}:$eventId")

    /** 저장 형식 — 한 줄에 `S|KIND|atWall|eventId` 또는 `B|KIND:eventId`(사건 id 는 URL 인코딩). 깨진 줄은 버린다. */
    fun encode(st: State): String = (
        st.sent.map { "S|${it.kind.name}|${it.atWall}|${URLEncoder.encode(it.eventId, "UTF-8")}" } +
            st.blockedKeys.takeLastN(MAX_BLOCKED).map { "B|${URLEncoder.encode(it, "UTF-8")}" }
        ).joinToString("\n")

    fun decode(s: String?): State {
        if (s.isNullOrEmpty()) return State()
        val sent = ArrayList<Entry>()
        val blocked = LinkedHashSet<String>()
        for (line in s.split("\n")) runCatching {
            val f = line.split("|")
            when (f[0]) {
                "S" -> sent += Entry(GuardianSmsWireKind.valueOf(f[1]), URLDecoder.decode(f[3], "UTF-8"), f[2].toLong())
                "B" -> blocked += URLDecoder.decode(f[1], "UTF-8")
            }
        }
        return State(sent, blocked)
    }

    /** 홈 기록에 이유를 보일 막힌 키 수 — 기록 24시간에 넉넉히 [추정]. */
    private const val MAX_BLOCKED = 64

    private fun Set<String>.takeLastN(n: Int) = toList().takeLast(n)
}
