// 휴대폰에 온 지시 실행기 — 알람 · 보호자 문자(중복 막기 · 테스트 확인) · 119(정차 뒤 무응답 화면 → 사람이 작성 화면을 연다 · 테스트는 미리보기) · 안내
package com.example.guardian.phone.core

import com.example.guardian.shared.protocol.Command
import com.example.guardian.shared.protocol.GuardianSmsDedup
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.protocol.PhoneSmsGuard
import com.example.guardian.shared.protocol.SnapshotClock
import com.example.guardian.shared.report.ReportFailure
import com.example.guardian.shared.report.ReportResult
import com.example.guardian.shared.text.SmsText

/**
 * 실행할 때의 주변 사실. [stopped] 는 차(또는 차 상태 없음이면 휴대폰 GPS) 정지 판정.
 * [testSession] 은 **워치 시뮬레이션 세션(WATCH)** 중인가 — 휴대폰 안 시험(LOCAL)은 아니다. LOCAL 의 시뮬레이션은 휴대폰 밖으로 나가지 않아
 * 그동안 오는 test=false 지시는 실제 워치 값의 실제 사건이고, 실제 문자 · 실제 119 화면으로 처리해야 한다(2026-11-07).
 * [offsetMs] · [nowLocal] · [nowWall] 은 보호자 문자 기록(:shared `GuardianSmsDedup.record`)에 쓰는 휴대폰 시계 보정값 · 같은 순간의 단조 · 벽시계.
 */
data class ExecContext(
    val stopped: Boolean,
    val testSession: Boolean,
    val offsetMs: Long? = null,
    val nowLocal: Long = 0,
    val nowWall: Long = 0,
    /** 원격 사건 문자 방어(A-2 · :shared PhoneSmsGuard) — 묵은 사건 · 참여 전 본 적 없는 사건이면 보내지 않는다. 휴대폰이 연 사건에는 걸지 않는다. */
    val smsGuard: (GuardianSmsWireKind, String, Long?) -> PhoneSmsGuard.Verdict = { _, _, _ -> PhoneSmsGuard.Verdict.ALLOW },
    /** 차에서 넘겨받은 사건 id 인가 — 휴대폰 상태 기계가 낸 문자라도 원격 문자로 본다(PM 결정 19 · sec-audit). */
    val carOriginEvent: (String) -> Boolean = { false },
    /** 넘겨받은 기계가 휴대폰에서 새로 만든 사건 id 인가 — 「참여 전 사건」 방어에서 빼고 원격 상한만 건다(독립 검토 ①). */
    val adoptedOwnEvent: (String) -> Boolean = { false },
    /**
     * 그 사건이 아직 진행 중인가(위임 결정 3) — 내 상태 기계 또는 신선한 차 흐름의 같은 사건 id 가 경보 · 사고 상태이고 응답 시각이 없다.
     * 보내지 못한 사건 · 사고 문자를 휴대폰 안에서 다시 보내는 동안만 참이어야 한다. 모르면 거짓(다시 보내지 않음).
     */
    val incidentOngoing: (String) -> Boolean = { false },
)

/** 119 본문 — 전체 판(LMS)과, 휴대폰이 만들었을 때만 있는 초단문 판(SMS). */
data class Body119(val full: String, val ultra: String?)

/**
 * 문자 본문을 만드는 곳. 지시에는 사건 사실만 실리고(계약 §4.6, G2) 본문은 휴대폰이 등록 정보로 만든다 —
 * 엔진 구현이 :shared `IncidentComposer` 를 부른다. [from] 은 지시를 낸 쪽(자기 지시면 PHONE).
 * 실패(보호자 미등록 · 동의 없음)면 보내지 않고 화면 · 기록에 남긴다.
 */
interface BodySource {
    fun guardianBody(p: Payload.SendGuardianSms, from: Party): ReportResult
    fun compose119Body(p: Payload.Open119Compose, from: Party): ReportResult

    /** 같은 사건의 119 초단문 판(P18 탭). 못 만들면 null. */
    fun ultra119(p: Payload.Open119Compose, from: Party): String? = null

    /** 보호자 「응답 확인 꺼짐」 문자 본문(:shared `Reports.guardianPowerOff`). [offAtWallMs] 는 끈 시각(휴대폰 벽시계). */
    fun guardianPowerOff(offAtWallMs: Long): ReportResult
}

/**
 * 지시 하나를 실행하고 ack 할지 돌려준다. 같은 id 는 두 번 실행하지 않는다(계약 §4.6 「한 번 이상」 전달).
 *
 * 지킬 것.
 * - 보호자 문자 — 같은 사건은 :shared `GuardianSmsDedup`(계약 §6.2)으로 막는다 — 보내기 전 `decideAndRecord`, 보내기 요청 때 `afterSend`(보냄).
 *   실제 결과(모든 조각 성공)가 오기 전에는 ack 하지 않는다. 보내지 못함이면 기록을 「보내지 않음」으로 고쳐 지시가 다시 올 때 다시 보내고,
 *   [SMS_RESULT_TIMEOUT_MS] 안에 결과가 없으면 「확인 못 함」으로 두고 다시 보내지 않는다(코드 리뷰 중 5).
 *   긴급 연락 정보 · 위치 동의와 기능 켬이 없으면 보내지 않는다.
 *   테스트(지시의 test)면 보내기 직전 사람이 확인한다. 워치 시뮬레이션 세션(WATCH) 중 테스트 표시 없는 문자는 보내지 않는다 —
 *   표시가 늦은 시뮬레이션 사건일 수 있어서다(EVENT · CANCEL · OBSERVE 만 — 에어백 사고 ACCIDENT 는 늘 실제라 막지 않는다, 계약 §4.6).
 *   휴대폰 안 시험(LOCAL)은 [ExecContext.testSession] 이 거짓이라 실제 지시를 막지 않는다.
 * - 119 — previewOnly · 테스트 · 워치 시뮬레이션 세션 중이면 앱 안 미리보기만. 그 밖에는 정지일 때 정차 뒤 무응답 화면(P12)을 띄울 뿐,
 *   문자 앱 작성 화면은 사람이 「119 문자 작성 화면 열기」를 눌렀을 때만 연다([openCompose], G4 · 설계 spec §4).
 * - ALERT_START — 소리 경보면 알람을 켠다. durationMs 는 표시용이라 ALERT_STOP 까지 계속한다(설계 §14).
 */
class CommandExecutor(
    private val store: PhoneStore,
    private val effects: PhoneEffects,
    private val bodies: BodySource,
) {
    /** 확인을 기다리는 테스트 문자와 그 기록(확인 결과로 `afterSend`). */
    private val pendingSms = HashMap<String, Pair<PendingSms, GuardianSmsDedup.Record>>()

    private enum class SmsState { WAITING, SENT, FAILED, UNCONFIRMED }

    /**
     * 보내기를 요청하고 결과를 기다리는(또는 결과가 났지만 지시를 아직 다시 받지 않은) 보호자 문자. 키는 `kind:eventId`.
     * [retry] 면 지시가 다시 올 때 결과로 ack 하거나(보냄 · 확인 못 함) 다시 보낸다(보내지 못함). 테스트 문자는 결과가 나면 바로 지운다.
     * [startedAt] 은 결과 기다림을 시작한 단조 시각 — 모르면 다음 [onTick] 에서 정한다.
     * 결과가 난 항목은 같은 지시가 더 오지 않아도 [settledAt] 부터 지시 ttl([ttlMs])이 지나면 [onTick] 이 지운다.
     * [refail] 은 보내지 못한 같은 문자를 다시 보낸 것 — 또 보내지 못하면 안내 · 문자 기록을 다시 내지 않는다(코드 리뷰 2026-11-08 하5).
     */
    private class InFlight(
        val key: String, val rec: GuardianSmsDedup.Record, val retry: Boolean, var startedAt: Long?, val ttlMs: Long, val refail: Boolean = false,
        /** 본문에 실제 위치 줄(좌표 · 주소)이 들어갔는가 — 위치 제공 통보는 이 값으로만 낸다(app-qa 관문 2 조건 1). */
        val withLocation: Boolean = false,
        /** 다시 보낼 때 본문을 다시 만들 지시 내용과 낸 쪽 — 실제 사건 · 사고 문자만(위임 결정 3). */
        val payload: Payload.SendGuardianSms? = null,
        val from: Party = Party.PHONE,
        /** 이 문자를 처음 보내려 한 단조 시각 — 다시 보내기 상한([RESEND_MAX_MS])의 기준. */
        val firstAt: Long? = null,
        /** 원격 문자(D-6 상한 칸을 먹음) — 보내지 못함으로 끝나면 칸에서 뺀다. */
        val remote: Boolean = false,
    ) {
        var state = SmsState.WAITING
        var settledAt: Long? = null
    }
    private val inFlight = HashMap<String, InFlight>()

    /**
     * 취소 문자(CANCEL) 지시를 받은 사건 id → 처음 받은 단조 시각(PM 결정 2026-11-02, 운전자가 이미 응답했으면 보호자는 받을 필요가 없다).
     * 이 사건의 사건 문자(EVENT)는 그 뒤로 다시 보내지 않는다 — 보내지 못함 뒤 재전달 · 취소 뒤 늦게 온 재전달 모두.
     * [CANCEL_SEEN_KEEP_MS] 가 지나면 [onTick] 이 지운다.
     */
    private val cancelSeen = HashMap<String, Long>()

    /** 마지막으로 받은 119 본문과 미리보기 여부 — P12 · 「119 작성 다시 열기」가 쓴다. */
    var last119: Pair<Body119, Boolean>? = null
        private set

    /** 주행 중에 받은 119 지시 — 정지로 바뀌는 순간 P12 를 띄운다([onTick]). ALERT_STOP 이 먼저 오면 버린다. */
    private var waitingStop: Body119? = null

    fun execute(cmd: Command, ctx: ExecContext): Boolean {
        if (store.isCommandDone(cmd.id)) return true
        val payload = try {
            Payload.fromJson(cmd.type, cmd.payload)
        } catch (e: RuntimeException) {
            // 형식이 틀린 지시는 실행하지 않고 ack 해 큐에서 지운다
            null
        }
        val done = when (payload) {
            null -> true
            is Payload.AlertStart -> { if (payload.sound) effects.startAlarm(payload.stage.name, payload.level, cmd.test); true }
            is Payload.AlertStop -> { waitingStop = null; effects.stopAlarm(); true }
            is Payload.Show -> { effects.showLine(payload.line, payload.button, cmd.test); true }
            is Payload.Open119Compose -> { open119(payload, cmd.test || payload.test, cmd.from, ctx); true }
            is Payload.SendGuardianSms -> guardianSms(payload, cmd.test || payload.test, cmd.from, ctx, cmd.ttlMs)
            // SPEAK · AMBIENT · OPEN_SEARCH 는 차, TEST_SCENARIO 는 워치 몫. FLOW_SNAPSHOT 은 엔진이 먼저 가져간다
            else -> true
        }
        if (done) store.markCommandDone(cmd.id)
        return done
    }

    private fun open119(p: Payload.Open119Compose, test: Boolean, from: Party, ctx: ExecContext) {
        val full = when (val r = bodies.compose119Body(p, from)) {
            is ReportResult.Ok -> r.text
            is ReportResult.Fail -> { effects.notice(Notice.REPORT_FAILED); return }
        }
        val body = Body119(full, bodies.ultra119(p, from))
        val preview = p.previewOnly || test || ctx.testSession
        last119 = body to preview
        when {
            preview -> effects.show119Preview(body)
            ctx.stopped -> { waitingStop = null; effects.showNoResponse(body) }
            else -> { waitingStop = body; effects.notice(Notice.COMPOSE_WAITING_STOP) }
        }
    }

    /** 주기마다 — 주행 중에 받아 둔 119 지시가 있고 이제 정지면 P12 를 띄운다(app-qa 1차 4). */
    fun onTick(ctx: ExecContext) {
        // 결과가 오지 않은 문자 — 시간이 지나면 「확인 못 함」. 다시 보내지 않는다(중복 문자가 더 나쁘다, 코드 리뷰 중 5)
        for (f in inFlight.values.toList()) {
            if (f.state == SmsState.FAILED && f.payload != null && resendTick(f, ctx)) continue
            if (f.state != SmsState.WAITING) {
                // 결과가 났는데 같은 지시가 다시 오지 않는 항목 — 지시 ttl 이 지나면 지운다
                val done = f.settledAt ?: run { f.settledAt = ctx.nowLocal; continue }
                if (ctx.nowLocal - done > f.ttlMs) inFlight.remove(f.key)
                continue
            }
            val start = f.startedAt ?: run { f.startedAt = ctx.nowLocal; continue }
            if (ctx.nowLocal - start >= SMS_RESULT_TIMEOUT_MS) settle(f, null)
        }
        cancelSeen.values.removeAll { ctx.nowLocal - it > CANCEL_SEEN_KEEP_MS }
        val body = waitingStop ?: return
        if (!ctx.stopped || ctx.testSession) return
        waitingStop = null
        effects.showNoResponse(body)
    }

    /**
     * 보내지 못한 실제 사건 · 사고 문자를 휴대폰 안에서 다시 보낸다(위임 결정 3 — 「놓침보다 중복」). 사건이 진행 중인 동안([ExecContext.incidentOngoing])
     * [RESEND_GAP_MS] 마다, 처음 시도부터 [RESEND_MAX_MS] 까지. 같은 사건 취소를 받았거나 · 응답 시각이 생겼거나(진행 중 아님) · 꺼짐 · 철회면 멈춘다.
     * 지시 ttl(30초)은 늘리지 않는다 — 다시 보내기는 휴대폰 안 일이다. 같은 키라 중복 막기는 그대로다.
     */
    /** 다시 보낼 차례면 보내고, 다시 보내기를 맡았으면 true. 멈출 조건이면 false — 그 항목은 원래 규칙(지시 ttl 뒤 지움 · 취소 규칙 3)을 따른다. */
    private fun resendTick(f: InFlight, ctx: ExecContext, now: Boolean = false): Boolean {
        val p = f.payload ?: return false
        val first = f.firstAt ?: f.startedAt ?: ctx.nowLocal
        val reg = store.registration
        if (ctx.nowLocal - first > RESEND_MAX_MS || p.eventId in cancelSeen || !reg.powerOn || !reg.consents.autoSmsAllowed || !ctx.incidentOngoing(p.eventId)) return false
        val failedAt = f.settledAt ?: ctx.nowLocal.also { f.settledAt = it }
        if (!now && ctx.nowLocal - failedAt < RESEND_GAP_MS) return true
        // 자동 다시 보내기도 원격 상한(D-6) 칸이 남았을 때만 — 보내지 못함은 칸에서 빠지므로 검사 없이 보내면 실패 뒤 한꺼번에 나간다(sec-audit).
        // 칸이 없으면 이 차례만 건너뛰고 항목은 남긴다. 사람이 누른 「다시 보내기」(now)는 막지 않는다
        if (!now && f.remote && !RemoteSmsLimit.allows(RemoteSmsLimit.decode(store.remoteSmsLimit), f.rec.kind, f.rec.eventId, ctx.nowWall)) return true
        val to = PhoneNumbers.digits(reg.guardianPhone)
        val body = (bodies.guardianBody(p, f.from) as? ReportResult.Ok)?.text
        if (!PhoneNumbers.isGuardianNumber(to) || body == null) return false
        inFlight.remove(f.key)
        val next = send(
            f.key, f.rec, to, body, retry = true, startedAt = ctx.nowLocal, ttlMs = f.ttlMs, refail = true, payload = p, from = f.from, firstAt = first,
            remote = f.remote, nowWall = ctx.nowWall,
        )
        if (next.state == SmsState.FAILED) next.settledAt = ctx.nowLocal
        return true
    }

    /** 이 사건에 보내지 못해 다시 보낼 문자가 있는가 — 경보 화면 「보호자 문자 다시 보내기」. */
    fun canResend(eventId: String?): Boolean =
        eventId != null && inFlight.values.any { it.state == SmsState.FAILED && it.payload?.eventId == eventId }

    /** 「보호자 문자 다시 보내기」 — 정지일 때만(G5), 간격을 기다리지 않고 지금 다시 보낸다. 보냈으면 true. */
    fun resendNow(eventId: String, ctx: ExecContext): Boolean {
        if (!ctx.stopped) return false
        val fs = inFlight.values.filter { it.state == SmsState.FAILED && it.payload?.eventId == eventId }
        fs.forEach { resendTick(it, ctx, now = true) }
        return fs.isNotEmpty()
    }

    /**
     * 사람이 「119 문자 작성 화면 열기」(또는 「다시 열기」)를 눌렀다 — 정지일 때만 작성 화면을 연다.
     * 미리보기 본문(테스트)이면 미리보기만 다시 보인다. 연 뒤에는 알람 소리만 줄인다(상태 기계 · 문자 · 응답은 그대로).
     */
    fun openCompose(ctx: ExecContext): Boolean {
        val (body, preview) = last119 ?: return false
        if (preview || ctx.testSession) { effects.show119Preview(body); return true }
        if (!ctx.stopped) return false
        effects.open119Compose(body.full)
        effects.muteAlarm()
        return true
    }

    private fun guardianSms(p: Payload.SendGuardianSms, test: Boolean, from: Party, ctx: ExecContext, ttlMs: Long): Boolean {
        val key = "${p.kind.name}:${p.eventId}"
        if (key in pendingSms) return true
        // 사건 문자와 취소 문자의 순서(PM 결정 2026-11-02) — 보호자가 「취소」 뒤에 「사건 + 위치」를 받지 않게 한다
        val eventKey = "${GuardianSmsWireKind.EVENT.name}:${p.eventId}"
        when (p.kind) {
            GuardianSmsWireKind.CANCEL -> {
                cancelSeen.getOrPut(p.eventId) { ctx.nowLocal }
                val ev = inFlight[eventKey]
                // 규칙 1 — 사건 문자 결과를 기다리는 중이면 취소는 보내지도 ack 하지도 않고 기다린다
                if (ev?.state == SmsState.WAITING) return false
                // 규칙 3 — 사건 문자를 보내지 못했고 운전자가 응답했다 — 둘 다 보내지 않는다(취소는 「보내지 않음」 기록 · 안내)
                if (ev?.state == SmsState.FAILED) {
                    inFlight.remove(eventKey)
                    val rec = GuardianSmsDedup.record(p, from, ctx.offsetMs, ctx.nowLocal, ctx.nowWall, sameDevice = from == Party.PHONE, test = test)
                    store.smsRecords = GuardianSmsDedup.afterSend(store.smsRecords, rec, sent = false)
                    effects.notice(Notice.SMS_SKIPPED_RESPONDED)
                    return true
                }
                // 규칙 2 — 보냄 · 확인 못 함(갔을 수 있음)이면 아래 판단대로 보낸다
            }
            // 규칙 3 · 4 — 같은 사건 취소를 이미 받았으면 사건 문자는 다시 보내지 않고 ack 한다
            GuardianSmsWireKind.EVENT -> if (p.eventId in cancelSeen) {
                if (inFlight[key]?.state != SmsState.WAITING) inFlight.remove(key)
                return true
            }
            else -> {}
        }
        // 앞서 보낸 같은 문자 — 결과를 기다리는 중이면 ack 하지 않고(다시 오면 또 본다), 보냄 · 확인 못 함이면 ack, 보내지 못함이면 다시 보낸다.
        // 다시 보내기는 지시가 다시 올 때뿐이라 ttl 끝 무렵(마지막 주기 안)에 실패하면 다시 보낼 기회가 없다(app-qa 하 1, 고치지 않음).
        // 휴대폰 주인 경로는 같은 지시를 매 틱 다시 부른다 — 보내지 못함을 처음 본 때부터 [SMS_RETRY_GAP_MS] 가 지나야 다시 보낸다(코드 리뷰 2026-11-08 하5)
        var refail = false
        var firstAt: Long? = null
        inFlight[key]?.let { f ->
            firstAt = f.firstAt
            if (f.state == SmsState.WAITING) return false
            if (f.state == SmsState.FAILED) {
                val failedAt = f.settledAt ?: ctx.nowLocal.also { f.settledAt = it }
                if (ctx.nowLocal - failedAt < SMS_RETRY_GAP_MS) return false
            }
            inFlight.remove(key)
            if (f.state != SmsState.FAILED) return true
            refail = true
        }
        // 테스트 문자는 확인 대화에 한 번 답했으면 다시 묻지 않는다
        if (test && store.isSmsSent(key)) return true
        val reg = store.registration
        if (!reg.powerOn || !reg.consents.autoSmsAllowed) { effects.notice(Notice.SMS_SKIPPED_NO_CONSENT); return true }
        val to = PhoneNumbers.digits(reg.guardianPhone)
        if (!PhoneNumbers.isGuardianNumber(to)) { effects.notice(Notice.SMS_SKIPPED_NO_GUARDIAN); return true }
        // 워치 시뮬레이션 세션 중 표시 없는 문자(표시가 늦은 시뮬레이션 사건일 수 있음)만 막는다. 에어백 사고는 늘 실제(계약 §4.6) — ACCIDENT · test=false 는 막지 않는다
        if (ctx.testSession && !test && p.kind != GuardianSmsWireKind.ACCIDENT) { effects.notice(Notice.SMS_SKIPPED_UNMARKED_TEST); return true }
        // 같은 사건 묶기(계약 §6.2) — 같은 사건에 이미 보냈거나(DUPLICATE) 사건 문자 없이 온 취소(NO_EVENT_SENT)면 보내지 않고 ack 한다
        val rec = GuardianSmsDedup.record(p, from, ctx.offsetMs, ctx.nowLocal, ctx.nowWall, sameDevice = from == Party.PHONE, test = test)
        // 차가 다시 실은 문자(A-2) · 대조로 낸 문자(A-3) — 10분 넘게 묵은 사건, 참여 전에 시작됐고 내가 본 적 없는 사건이면 보내지 않는다.
        // 같은 문자를 이미 보낸 기록이 없으면 「보내지 않음」으로 남겨 flow.smsDone 으로 차가 다시 싣기를 멈추게 한다
        // 원격 문자 — 차가 낸 지시 · 대조 길, 그리고 차에서 넘겨받은 사건을 휴대폰 상태 기계가 이어 낸 문자(PM 결정 19). 휴대폰이 처음부터 연 사건만 밖이다
        val remote = (from != Party.PHONE || ctx.carOriginEvent(p.eventId)) && !test
        // 지시 사실 시각이 중계 시계(SERVER)면 사건 시작 시각으로 넘긴다 — 시각을 읽을 수 없는 id(…-L…)의 참여 전 판정(공용 carFlowStartServerMs)
        val payloadStart = (if (p.kind == GuardianSmsWireKind.ACCIDENT) p.facts.accidentAt else p.facts.firstAlertAt)
            ?.takeIf { p.facts.clock == SnapshotClock.SERVER }
        if (remote && !ctx.adoptedOwnEvent(p.eventId) && ctx.smsGuard(p.kind, p.eventId, payloadStart) != PhoneSmsGuard.Verdict.ALLOW) {
            // 보내지 않은 사건 문자 기록은 중복 판단에 쓰이지 않는다(GuardianSmsDedup 은 보낸 기록만 본다) — smsDone 의 NOT_SENT 원본으로만 쓴다
            if (store.smsRecords.none { it.eventId == p.eventId && it.kind == p.kind }) {
                store.smsRecords = store.smsRecords + rec.copy(sent = false)
                effects.guardianSmsResult(p.kind.name, p.eventId, sent = false)
            }
            return true
        }
        // 원격 문자 상한(D-6) — 차 지시 · 대조 길의 실제 문자만, 사건 id 단위. 걸리면 「보내지 않음」으로 남겨 차가 다시 싣기를 멈추게 한다
        val limit = if (remote) RemoteSmsLimit.decode(store.remoteSmsLimit) else null
        if (limit != null && store.smsRecords.none { it.eventId == p.eventId && it.kind == p.kind && it.sent } &&
            !RemoteSmsLimit.allows(limit, p.kind, p.eventId, ctx.nowWall)
        ) {
            if (store.smsRecords.none { it.eventId == p.eventId && it.kind == p.kind }) store.smsRecords = store.smsRecords + rec.copy(sent = false)
            store.remoteSmsLimit = RemoteSmsLimit.encode(RemoteSmsLimit.block(limit, p.kind, p.eventId))
            effects.notice(Notice.SMS_SKIPPED_LIMIT)
            effects.guardianSmsResult(p.kind.name, p.eventId, sent = false)
            return true
        }
        val outcome = GuardianSmsDedup.decideAndRecord(store.smsRecords, rec, ctx.nowWall)
        store.smsRecords = outcome.records
        if (outcome.decision != GuardianSmsDedup.Decision.SEND) return true
        val body = when (val r = bodies.guardianBody(p, from)) {
            is ReportResult.Ok -> r.text
            is ReportResult.Fail -> {
                // 보내지 않고 화면 · 기록에 남긴다
                store.smsRecords = GuardianSmsDedup.afterSend(store.smsRecords, rec, sent = false)
                effects.notice(failureNotice(r))
                effects.guardianSmsResult(p.kind.name, p.eventId, sent = false)
                return true
            }
        }
        if (test) {
            val pending = PendingSms(key, to, body)
            pendingSms[key] = pending to rec
            effects.askTestSms(pending)
            return true
        }
        val resendable = !test && (p.kind == GuardianSmsWireKind.EVENT || p.kind == GuardianSmsWireKind.ACCIDENT)
        val f = send(
            key, rec, to, body, retry = true, startedAt = ctx.nowLocal, ttlMs = ttlMs, refail = refail,
            payload = p.takeIf { resendable }, from = from, firstAt = firstAt ?: ctx.nowLocal, remote = remote, nowWall = ctx.nowWall,
        )
        // 보내지 못함은 항목을 남겨 둔다 — 같은 사건 취소가 오면 규칙 3, 이 지시가 다시 오면 간격을 두고 다시 보낸다
        if (f.state == SmsState.FAILED) f.settledAt = ctx.nowLocal
        if (f.state == SmsState.WAITING || f.state == SmsState.FAILED) return false
        inFlight.remove(key)
        return true
    }

    /**
     * 보내기 요청. 결과를 기다리는 동안 같은 사건 문자가 또 나가지 않게 보낸 기록을 먼저 남기고(`afterSend` sent=true),
     * 보내지 못함이면 그 기록을 「보내지 않음」으로 고친다([settle]). 결과가 바로 오면(요청 거절 · 시험용 가짜) 돌아올 때 이미 정해져 있다.
     */
    private fun send(
        key: String, rec: GuardianSmsDedup.Record, to: String, body: String, retry: Boolean, startedAt: Long?, ttlMs: Long, refail: Boolean = false,
        payload: Payload.SendGuardianSms? = null, from: Party = Party.PHONE, firstAt: Long? = null, remote: Boolean = false, nowWall: Long = rec.recordedAtWallMs,
    ): InFlight {
        val f = InFlight(key, rec, retry, startedAt, ttlMs, refail, withLocation(rec.kind, body), payload, from, firstAt, remote)
        inFlight[key] = f
        // 원격 상한(D-6)은 보내는 중 · 보냄 · 확인 못 함만 칸을 먹는다 — 보내지 못함으로 끝나면 [settle] 이 뺀다(app-qa 상: 실패가 칸을 먹어 그 뒤 실제 문자가 막히던 것)
        if (remote) store.remoteSmsLimit = RemoteSmsLimit.encode(RemoteSmsLimit.record(RemoteSmsLimit.decode(store.remoteSmsLimit), rec.kind, rec.eventId, nowWall))
        store.smsRecords = GuardianSmsDedup.afterSend(store.smsRecords, rec, sent = true)
        if (!effects.sendSms(to, body) { ok -> settle(f, ok) }) settle(f, false)
        return f
    }

    /** 문자 결과 — [ok] 는 보냄 true · 보내지 못함 false · 시간 초과(확인 못 함) null. 이미 정해진 문자의 늦은 결과는 버린다. */
    private fun settle(f: InFlight, ok: Boolean?) {
        if (f.state != SmsState.WAITING || inFlight[f.key] !== f) return
        val kind = f.key.substringBefore(':')
        when (ok) {
            true -> {
                f.state = SmsState.SENT
                store.markSmsSent(f.key)
                effects.notice(Notice.SMS_SENT)
            }
            false -> {
                f.state = SmsState.FAILED
                store.smsRecords = GuardianSmsDedup.afterSend(store.smsRecords - f.rec.copy(sent = true), f.rec, sent = false)
                if (f.remote) store.remoteSmsLimit = RemoteSmsLimit.encode(RemoteSmsLimit.unrecord(RemoteSmsLimit.decode(store.remoteSmsLimit), f.rec.kind, f.rec.eventId))
                // 같은 문자를 다시 보내다 또 못 보냄 — 안내 · 문자 기록은 첫 실패 때 이미 냈다(하5)
                if (f.refail) { if (!f.retry) inFlight.remove(f.key); return }
                effects.notice(Notice.SMS_FAILED)
            }
            // 보낸 기록은 그대로 둔다 — 다시 보내지 않는다
            null -> {
                f.state = SmsState.UNCONFIRMED
                effects.notice(Notice.SMS_UNCONFIRMED)
            }
        }
        effects.guardianSmsResult(kind, f.key.substringAfter(':'), ok)
        // 위치가 든 문자가 보냄 · 확인 못 함(갔을 수 있음)으로 정해질 때마다 통보한다(보안 7 · D-8). 테스트 문자도 위치가 실리므로 같다
        if (ok != false && f.withLocation) effects.locationSmsSettled(ok)
        if (!f.retry) inFlight.remove(f.key)
    }

    /**
     * 테스트 문자 확인 결과. 보내든 취소하든 같은 키로 다시 묻지 않는다. 기록은 실제로 보냈는지로 남긴다(계약 §6.2).
     * 보내기 직전에 켬 · 동의(긴급 연락 정보 포함) · 보호자 번호(물을 때와 같은 번호)를 다시 확인한다.
     */
    fun confirmTestSms(key: String, send: Boolean) {
        val (p, rec) = pendingSms.remove(key) ?: return
        effects.clearTestSmsAsk()
        store.markSmsSent(key)
        // 대화를 띄운 뒤 끄기 · 철회 · 보호자 번호 바꿈이 있었으면 보내지 않는다 — 대화만 닫는다(코드 리뷰 2026-11-09 새로 찾은 것, G2)
        val reg = store.registration
        val stillOk = reg.powerOn && reg.consents.autoSmsAllowed && PhoneNumbers.digits(reg.guardianPhone).let { PhoneNumbers.isGuardianNumber(it) && it == p.to }
        if (!send || !stillOk) { store.smsRecords = GuardianSmsDedup.afterSend(store.smsRecords, rec, sent = false); return }
        // 테스트 문자는 다시 보내지 않는다 — 결과(보냄 · 보내지 못함 · 확인 못 함)만 남긴다
        send(key, rec, p.to, p.body, retry = false, startedAt = null, ttlMs = 0)
    }

    /**
     * 보호자 「응답 확인 꺼짐」 문자 한 통 — 보낼지는 엔진이 끄기 때 정했다([PhoneEngine.shutdown]). 지시가 아니어서 다시 보내지 않고
     * 결과도 기다리지 않는다(서비스가 곧 멈춘다). 보호자 번호가 없거나 본문을 못 만들면 보내지 않는다. 보내기 요청이 바로 거절되면(권한 없음 ·
     * 문자 서비스 없음 · 예외, 동기)만 「보내지 못함」 안내 · 문자 기록이 남는다 — 늦게 오는 실패 결과(SIM 없음 · 신호 없음 등)는 서비스가 멈추며
     * 결과 수신기를 풀어 받지 못한다(app-qa 하, PM 결정 — 기다리지 않음 유지). 보냄은 화면에 남기지 않는다 — 위치를 담지 않아 위치 제공 통보(G3)가 아니다.
     */
    fun guardianPowerOff(eventId: String, offAtWall: Long) {
        val to = PhoneNumbers.digits(store.registration.guardianPhone)
        if (!PhoneNumbers.isGuardianNumber(to)) { effects.notice(Notice.SMS_SKIPPED_NO_GUARDIAN); return }
        val body = when (val r = bodies.guardianPowerOff(offAtWall)) {
            is ReportResult.Ok -> r.text
            is ReportResult.Fail -> {
                effects.notice(failureNotice(r))
                return
            }
        }
        fun failed() { effects.notice(Notice.SMS_FAILED); effects.guardianSmsResult(POWER_OFF_KIND, eventId, sent = false) }
        if (!effects.sendSms(to, body) { ok -> if (!ok) failed() }) failed()
    }

    /** 본문을 못 만든 사유 → 안내 — 보호자 번호가 없으면 「번호 없음」, 그 밖은 「동의 없음」. */
    private fun failureNotice(r: ReportResult.Fail) =
        if (r.reason == ReportFailure.GUARDIAN_MISSING) Notice.SMS_SKIPPED_NO_GUARDIAN else Notice.SMS_SKIPPED_NO_CONSENT

    /**
     * 보낼 본문에 실제 위치가 들어갔는가 — 위치 줄을 싣는 종류(사건 · 사고 · 정차 관찰)이고, 위치 줄이 「동의 없음」 · 「위치 확인 안 됨」이 아니다
     * (:shared Reports.locationLine 의 두 문구). 보낼 때의 본문으로 정하므로 뒤에 동의가 바뀌어도 사실과 같다.
     */
    private fun withLocation(kind: GuardianSmsWireKind, body: String) =
        kind in setOf(GuardianSmsWireKind.EVENT, GuardianSmsWireKind.ACCIDENT, GuardianSmsWireKind.OBSERVE) &&
            !body.contains(SmsText.LOCATION_NO_CONSENT) && !body.contains(SmsText.LOCATION_UNKNOWN)

    fun pending(): List<PendingSms> = pendingSms.values.map { it.first }

    /** 결과를 기다리거나(보내는 중) 다시 보낼 문자 키(KIND:eventId) — 흐름 smsDone 의 PENDING(A-1). */
    fun pendingKeys(): Set<String> = inFlight.values.filter { it.state == SmsState.WAITING || (it.state == SmsState.FAILED && it.retry) }.map { it.key }.toSet()

    /**
     * 테스트가 실제 사고로 끝났다 — 묻던 테스트 문자(P17)는 보내지 않은 채 다시 묻지 않게 하고, 테스트 119 미리보기 본문(P18 다시 열기)을 버린다.
     * 실제 119 본문(미리보기 아님)과 정지 대기는 그대로 둔다.
     */
    fun dropTestState() {
        for ((key, v) in pendingSms) {
            store.markSmsSent(key)
            store.smsRecords = GuardianSmsDedup.afterSend(store.smsRecords, v.second, sent = false)
        }
        pendingSms.clear()
        if (last119?.second == true) last119 = null
    }

    companion object {
        /** 보내기 요청 뒤 결과(조각별 sentIntent)를 기다리는 시간. 지나면 「확인 못 함」으로 두고 다시 보내지 않는다. 지시 ttl(30초)과 같다. */
        const val SMS_RESULT_TIMEOUT_MS = 30_000L

        /** 보내지 못한 보호자 문자를 다시 보내기까지의 최소 간격 — 지시 ttl(30초) 동안 첫 시도 포함 최대 네 번(PM 결정 2026-11-08). */
        const val SMS_RETRY_GAP_MS = 10_000L

        /** 보내지 못한 사건 · 사고 문자를 휴대폰 안에서 다시 보내는 간격과 상한(위임 결정 3). */
        const val RESEND_GAP_MS = 60_000L
        const val RESEND_MAX_MS = 600_000L

        /** 취소를 받은 사건을 기억하는 시간 — 결과 기다림(30초) + 지시 ttl(30초)보다 넉넉히. */
        const val CANCEL_SEEN_KEEP_MS = 120_000L

        /** 「응답 확인 꺼짐」 문자의 휴대폰 안 기록 종류 — 계약의 문자 종류(`GuardianSmsWireKind`)가 아니다. 한 번 표시 키 `POWER_OFF:<사건 id>` 에도 쓴다. */
        const val POWER_OFF_KIND = "POWER_OFF"
    }
}
