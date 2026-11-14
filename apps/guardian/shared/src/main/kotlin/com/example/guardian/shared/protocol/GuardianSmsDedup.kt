// 보호자 문자 「같은 사건」 판단 — 사건 id 에 더해 받은 차 지시가 휴대폰이 스스로 연 같은 사건과 겹치면(첫 경보 60초 안) 묶어 두 통이 나가지 않게 한다(계약 §6.2)
package com.example.guardian.shared.protocol

import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 휴대폰만 중계에 못 닿으면 휴대폰과 차가 둘 다 주인이 되어 같은 사건을 따로 연다(사건 id 가 1초쯤 어긋남, 계약 §6.2).
 * 문자를 실제로 보내는 곳은 휴대폰 하나라 휴대폰이 보내기 직전에 이 판단으로 막는다(PM 결정 2026-10-30).
 * 원칙 — **놓침이 중복보다 나쁘다.** 묶기는 좁게 한다.
 *
 * 같은 사건(이미 보낸 기록 `sent` 와 보내려는 `next`) — 테스트 표시([Record.test])가 같고, 아래 하나라도 맞으면.
 * 1. 사건 id 가 같다.
 * 2. **`next` 가 받은 원격 지시(from 이 PHONE 아님)이고 `sent` 가 휴대폰이 스스로 연 사건(from PHONE)** 이며, 기준 시각
 *    (EVENT · CANCEL · OBSERVE 는 첫 경보, ACCIDENT 는 사고 시각)이 둘 다 있고 차이가 [SAME_INCIDENT_MS] 이하이고,
 *    두 기준 시각 사이(앞 시각 초과 ~ 뒤 시각 이하)에 이미 보낸 취소 문자의 응답 시각이 끼어 있지 않다.
 *
 * 휴대폰이 스스로 연 사건의 문자는 시각으로 막지 않는다(같은 id 만) — 차의 취소가 늦게 오는 동안 휴대폰이 연 진짜 새 사건을
 * 삼키지 않게(be-qa 재현 A). 그래서 차 EVENT 가 먼저 오고 휴대폰 자기 EVENT 가 나중이면 두 통이 갈 수 있다(놓침보다 중복).
 * 테스트 기록과 실제 기록은 절대 묶지 않는다(재현 C).
 *
 * 시계 — 기록의 시각은 모두 **휴대폰 벽시계**(에포크 ms)다. 받은 사실을 [WireFacts.toLocal] 로 휴대폰 단조 시각으로 되돌린 뒤
 * 같은 순간의 (벽시계 − 단조) 차이를 더한다. 휴대폰 자기 사건(LOCAL · 같은 기기)과 차 지시(SERVER + 휴대폰 보정값)가 같은 길을 지나므로
 * 중계 시계와 휴대폰 벽시계가 어긋나도 그 차이는 상쇄된다. 되돌린 시각이 근사(「시계 미확인」)이면 시각으로 묶지 않고 id 만 본다.
 *
 * 저장 — 휴대폰은 기록을 영구 저장소에 [KEEP_MS](24시간, 짝 보관과 같음) 남긴다(재현 B — 재시작 뒤 취소가 빠지지 않게).
 * 형식은 [encode] · [decode], 정리는 [prune].
 *
 * 3. 사건 문자(EVENT)인데 같은 사건 id 의 취소 기록(보냄 · 보내지 않음)이 이미 있다 — [Decision.DUPLICATE](합동 점검 A-3, 같은 id 에만).
 *
 * 「보내지 않음」 기록(be-qa 4-2) — 같은 사건이라 안 보낸 CANCEL 도 응답 시각과 함께 [Record.sent] = false 로 남긴다.
 * 이 기록은 「사이에 낀 취소」 판단에만 쓰고, 중복 · [Decision.NO_EVENT_SENT] 판단에는 세지 않는다(보낸 것만 센다).
 *
 * 앱이 부르는 순서(이 둘만 쓴다) —
 * 1. [decideAndRecord] — 정리 · 판단, 보내지 않을 CANCEL 이면 「보내지 않음」 기록까지. 결과가 [Decision.SEND] 일 때만 보내기를 시도한다.
 * 2. SEND 였으면 보내기 시도 뒤 [afterSend] — 실제로 보냈으면 보낸 기록, 못 보낸 CANCEL 이면 「보내지 않음」 기록.
 */
object GuardianSmsDedup {
    /** 같은 사건으로 묶는 기준 시각 차이(이하). PM 결정 2026-10-30. */
    const val SAME_INCIDENT_MS = 60_000L

    /** 보낸 기록을 남기는 기간(이하) — 짝 보관과 같은 24시간. */
    const val KEEP_MS = 24 * 60 * 60 * 1000L

    /**
     * 보호자 문자 기록 하나. 시각은 모두 휴대폰 벽시계, 모르면 null. 보통 [record] 로 만든다.
     * [anchorWallMs] 기준 시각, [responseWallMs] 응답 시각(취소 문자에서 쓴다), [test] 테스트 문자,
     * [recordedAtWallMs] 기록한 시각(24시간 정리용 — 기본값이 없다, 0 을 넣으면 바로 정리된다), [sent] 실제로 보냈는지(false = 「보내지 않음」).
     */
    data class Record @JvmOverloads constructor(
        val kind: GuardianSmsWireKind,
        val eventId: String,
        val from: Party,
        val anchorWallMs: Long?,
        val responseWallMs: Long?,
        val test: Boolean,
        val recordedAtWallMs: Long,
        val sent: Boolean = true,
    )

    /** [decideAndRecord] 결과 — 판단과 갱신된 기록 목록(앱은 이 목록을 그대로 저장한다). */
    data class Outcome(val decision: Decision, val records: List<Record>)

    enum class Decision {
        /** 보낸다. 실제로 보낸 뒤 기록에 더한다. */
        SEND,
        /** 같은 사건에 같은 kind 를 이미 보냈다 — 보내지 않고 ack 한다. */
        DUPLICATE,
        /** 취소 문자인데 같은 사건의 사건 문자(EVENT)를 보낸 기록이 없다 — 보내지 않고 ack 한다. */
        NO_EVENT_SENT,
    }

    /**
     * 문자 지시 → 기록. [offsetMs] 는 휴대폰 시계 보정값([ClockOffset.offsetAt]), [nowLocalMs] · [nowWallMs] 는 같은 순간의
     * 휴대폰 단조 · 벽시계, [sameDevice] 는 휴대폰이 자기에게 낸 지시(`from == PHONE`)인지. [test] 는 지시 · payload 의 테스트 표시.
     */
    fun record(
        p: Payload.SendGuardianSms, from: Party, offsetMs: Long?, nowLocalMs: Long, nowWallMs: Long, sameDevice: Boolean,
        test: Boolean = p.test,
    ): Record {
        val r = p.facts.toLocal(p.eventId, offsetMs, nowLocalMs, sameDevice)
        fun wall(local: Long?) = local?.takeIf { r.clockVerified }?.plus(nowWallMs - nowLocalMs)
        val anchor = if (p.kind == GuardianSmsWireKind.ACCIDENT) r.facts.accidentAtMs else r.facts.firstAlertAtMs
        return Record(p.kind, p.eventId, from, wall(anchor), wall(r.facts.responseAtMs), test, nowWallMs)
    }

    /**
     * 이미 보낸 [sent] 와 보내려는 [next] 가 같은 사건인가(kind 는 보지 않는다, 방향이 있다 — 위 규칙 2).
     * [cancels] 는 이미 보낸 취소 문자 기록.
     */
    fun sameIncident(sent: Record, next: Record, cancels: Collection<Record> = emptyList()): Boolean {
        if (sent.test != next.test) return false
        if (sent.eventId == next.eventId) return true
        if (sent.from != Party.PHONE || next.from == Party.PHONE) return false
        if ((sent.kind == GuardianSmsWireKind.ACCIDENT) != (next.kind == GuardianSmsWireKind.ACCIDENT)) return false // 기준 시각의 뜻이 다르다
        val x = sent.anchorWallMs ?: return false
        val y = next.anchorWallMs ?: return false
        if (abs(x - y) > SAME_INCIDENT_MS) return false
        val lo = min(x, y)
        val hi = max(x, y)
        return cancels.none { c -> c.test == next.test && c.responseWallMs?.let { it > lo && it <= hi } == true }
    }

    /**
     * 기록 [records] 에 비추어 [next] 를 보낼지(기록은 바꾸지 않는다 — 앱은 [decideAndRecord] 를 쓴다).
     * 중복 · EVENT 보냄 여부는 보낸 기록([Record.sent])만, 「사이에 낀 취소」는 보내지 않은 CANCEL 까지 모두 본다.
     */
    fun decide(records: Collection<Record>, next: Record): Decision {
        val cancels = records.filter { it.kind == GuardianSmsWireKind.CANCEL }
        val sent = records.filter { it.sent }
        if (sent.any { it.kind == next.kind && sameIncident(it, next, cancels) }) return Decision.DUPLICATE
        // 같은 사건 id 의 취소 기록(보냄 · 보내지 않음) 뒤에 온 사건 문자는 보내지 않는다 — 취소가 먼저 닿고 사건 문자가 늦게 다시 실린 경우(A-3).
        // 같은 사건 id 에만 건다 — 규칙 2(60초 묶기)까지 넓히면 진짜 새 사건을 삼킨다(be-qa 3차)
        if (next.kind == GuardianSmsWireKind.EVENT && cancels.any { it.test == next.test && it.eventId == next.eventId }) return Decision.DUPLICATE
        if (next.kind == GuardianSmsWireKind.CANCEL && sent.none { it.kind == GuardianSmsWireKind.EVENT && sameIncident(it, next, cancels) }) {
            return Decision.NO_EVENT_SENT
        }
        return Decision.SEND
    }

    /**
     * 정리([prune]) · 판단 · 기록 갱신을 한 번에. SEND 가 아닌 CANCEL 은 응답 시각과 함께 「보내지 않음」으로 남긴다(응답 시각이 있을 때).
     * SEND 면 기록은 정리만 된 채 돌아온다 — 보내기를 시도한 뒤 [afterSend] 를 부른다.
     */
    fun decideAndRecord(records: List<Record>, next: Record, nowWallMs: Long): Outcome {
        val kept = prune(records, nowWallMs)
        val d = decide(kept, next)
        return Outcome(d, if (d == Decision.SEND) kept else withUnsentCancel(kept, next))
    }

    /** [decideAndRecord] 가 SEND 였던 [next] 의 보내기 시도 뒤 — [sent] 면 보낸 기록, 아니면(실패 · 테스트 문자 거절) CANCEL 만 「보내지 않음」. */
    fun afterSend(records: List<Record>, next: Record, sent: Boolean): List<Record> =
        if (sent) records + next.copy(sent = true) else withUnsentCancel(records, next)

    private fun withUnsentCancel(records: List<Record>, next: Record): List<Record> =
        if (next.kind == GuardianSmsWireKind.CANCEL && next.responseWallMs != null) records + next.copy(sent = false) else records

    /** [nowWallMs] 기준 24시간([KEEP_MS]) 안 기록만 남긴다. 벽시계가 뒤로 가 기록 시각이 미래인 것은 버리지 않는다. */
    fun prune(records: List<Record>, nowWallMs: Long): List<Record> = records.filter { nowWallMs - it.recordedAtWallMs <= KEEP_MS }

    /**
     * 저장 형식 한 줄 — `v2|KIND|FROM|test(0·1)|기준|응답|기록시각|eventId(URL 인코딩)|sent(0·1)`. 모르는 시각은 `-`. 줄바꿈 · `|` 가 없다.
     * (옛 `v1` 은 sent 칸이 없는 8칸 — [decode] 가 보낸 기록으로 읽는다.)
     */
    fun encode(r: Record): String = listOf(
        "v2", r.kind.name, r.from.name, if (r.test) "1" else "0", r.anchorWallMs?.toString() ?: "-",
        r.responseWallMs?.toString() ?: "-", r.recordedAtWallMs.toString(), URLEncoder.encode(r.eventId, "UTF-8"),
        if (r.sent) "1" else "0",
    ).joinToString("|")

    /** [encode] 의 역(`v1` 도 읽는다 — sent=true). 형식이 틀리면 null(그 기록은 버린다). */
    fun decode(s: String): Record? {
        val f = s.split('|')
        val sent = when {
            f.size == 8 && f[0] == "v1" -> true
            f.size == 9 && f[0] == "v2" -> bit(f[8]) ?: return null
            else -> return null
        }
        fun time(v: String): Long? = if (v == "-") null else v.toLongOrNull() ?: throw IllegalArgumentException(v)
        return try {
            Record(
                GuardianSmsWireKind.valueOf(f[1]), URLDecoder.decode(f[7], "UTF-8"), Party.valueOf(f[2]),
                time(f[4]), time(f[5]), bit(f[3]) ?: return null, f[6].toLongOrNull() ?: return null, sent,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun bit(v: String): Boolean? = when (v) { "1" -> true; "0" -> false; else -> null }
}
