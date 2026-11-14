// 운전자 무응답 확인 상태 기계(설계 §4) — 손목 판정 · 응답 · 차량 상태 · 시각을 받아 경보 · 문자 · 화면 지시를 낸다
package com.example.guardian.shared.flow

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.text.Phrase

/** 판정 주인 규칙 — 차 앱 마지막 sync 가 10초 안이면 CAR, 아니면 PHONE. */
object Ownership {
    const val CAR_FRESH_MS = 10_000L

    fun decide(nowMs: Long, carLastSyncAtMs: Long?): Owner =
        if (carLastSyncAtMs != null && nowMs - carLastSyncAtMs in 0..CAR_FRESH_MS) Owner.CAR else Owner.PHONE

    /**
     * 판정 주인(합동 점검 A-4) — 차 요청이 10초 안에 닿았고 차가 흐름에 스스로 주인이라고 실었을 때만 CAR([carClaimsOwner] = 차 `flow.owner == CAR`).
     * 차가 내려놓았으면(`owner = PHONE`) 신선해도 휴대폰이 주인이다. 넘겨받기 세부는 [Handover].
     */
    fun decide(nowMs: Long, carLastSyncAtMs: Long?, carClaimsOwner: Boolean): Owner =
        if (carClaimsOwner && decide(nowMs, carLastSyncAtMs) == Owner.CAR) Owner.CAR else Owner.PHONE
}

/**
 * 순수 상태 기계. [handle] 에 입력을 넣으면 지시 목록을 돌려준다. 같은 입력열이면 같은 출력열이다.
 *
 * 사건 규칙(PM 결정 2026-10-30).
 * - 경보가 새로 시작될 때마다 새 사건이다(첫 경보 시각 = 사건 id). 사건마다 보호자 문자 1회 + 취소 1회.
 * - 한 사건 안에서 S1 ↔ AP 를 오가도 경보 타이머(첫 경보부터 35초)와 단계는 이어 간다.
 * - 무응답 타이머는 응답(워치 버튼 · 손목 · 차 화면 · 정지 때 휴대폰)만 멈춘다. 판정이 NORMAL 로 돌아오거나 워치 신호가 끊겨도 계속한다.
 * - 35초가 되면 차가 안정적으로 정지해 있으면 S4, 아니면 S3. S3 에서 정지하면 S4, S4 에서 움직이면 S3(주행 중 119 작성 없음).
 * - S2 는 「응답한 뒤 정차 관찰」 전용이다. S2 에서 다시 출발하면 S1R 로 돌아가 나쁜 판정 지속 · 재경보 단계를 이어 간다(S1R ↔ S2 대칭, 2026-11-02).
 * - 정지 · 주행 판정은 5초 디바운스, 속도 null 한 번은 직전 판정 유지.
 * - S1R · S2 에서 PULSE_ABSENT 로 나빠지면 즉시, 나쁜 판정(PULSE_WEAK 포함)이 30초 이어지면 새 사건으로 다시 경보한다.
 *   나쁜 판정 시작 시각은 NORMAL 이 10초 이어져야 지우고, 지속 재경보는 마지막 NORMAL 뒤 나쁜 판정이 8초 이어질 때만 낸다(두 경로 모두,
 *   합동 점검 B-2) — NORMAL 한 번이 재경보를 지우지도, 나쁜 판정 한 번이 재경보를 내지도 않게.
 *   단 워치 값이 낡으면(30초) 재경보하지 않고 「워치 신호 없음」을 한 번 안내하며 지속 시각을 비운다 — 다시 신선한 나쁜 판정이 오면
 *   그때부터 다시 센다(사용자 결정 2026-11-02 (가)). 10분 휴식(S1R)은 그대로고, 정차 관찰 만료(S2)는 아래 줄(공통 하1)을 따른다.
 *   응답 뒤 안내를 낸 채 S0 으로 돌아가면 S0 에서 다시 안내한다(2026-11-08 리뷰 2차).
 * - 감시 중(S0)과 응답 뒤(S1R · S2)에 미착용(CHECK_WEAR) 판정이 10초 이어지면 꺼짐 + 「착용 확인」이다(S0 은 합동 점검 B-6)(그 전부터 이어진 미착용도 센다). 10초가 안 된
 *   깜박임은 휴식 · 재경보 단계를 그대로 두고, 그동안은 지속 재경보를 내지 않는다(코드 리뷰 11-09 공통 하1, PM 결정 (a)). S2 정차 관찰은 10분 뒤 판정이
 *   나쁘지 않거나(정상 · 미착용) 워치 값이 낡았으면 끝난다 — 신선한 나쁜 판정만 재경보로 이어진다(공통 하1, PM 결정 2026-11-08).
 * - 119 작성 · 응급실 검색은 사건마다 첫 S4 진입에 한 번만 자동으로 연다.
 * - 에어백 사고(S5)는 테스트 모드와 상관없이 늘 실제 사건(test=false)이다. 테스트 사건 도중이면 그 사건을 끝내고 새 사고를 연다.
 * - 테스트 표시는 OFF · S0 에서만 켠다. 끄기는 거기에 더해 테스트 사건의 응답 뒤(S1R · S2)에서도 받아 S0 으로 깨끗이 돌아간다([endTestRest]) —
 *   테스트 세션이 끝났는데 차 화면에 테스트 띠 · 「테스트 멈추기」가 남고, 테스트의 휴식이 실제 경보를 늦추지 않게(사용자 지시 2026-11-07).
 *   경보 중(S1 · AP · S3 · S4 · S5)의 끄기는 받지 않는다 — 앱이 「워치 값 없음」에도 끄기를 넣기 때문이다.
 * - 테스트 흐름 거두기([FlowInput.WithdrawTest])는 테스트 흐름이면 경보 중(S1 · AP · S3 · S4)에도 경보를 멈추고 S0 으로 간다(합동 점검 B-7 —
 *   거두지 않은 테스트 사건이 그 뒤 실제 사건을 막지 않게). 실제 흐름과 S5(늘 실제)는 어떤 경우에도 거두지 않는다 —
 *   실제 사건을 테스트로, 실제 경보를 테스트 종료로 바꾸는 길이 없다.
 *
 * 시각 규칙 — 앱은 모든 입력 atMs 와 [com.example.guardian.shared.protocol.ClockOffset] 에 같은 단조 시계
 * (안드로이드 `SystemClock.elapsedRealtime()`)를 쓴다. 벽시계는 사용자가 바꾸거나 뒤로 갈 수 있어 쓰지 않는다.
 * - 워치 값이 30초 넘게 지나면 판정 입력으로 쓰지 않는다. S0 이면 안내(SHOW)를 한 번 낸다.
 *
 * 처리 순서 — 차량 입력 반영 → 디바운스 확정 · 정지/주행 전이 → 타이머 → 나머지 입력.
 * - 응답은 같은 ms 의 만료보다 먼저다 — 응답 1ms 전까지의 타이머 → 응답 → 남은 타이머(PM 결정 2026-11-08). 같은 ms 에 INCIDENT 와 CANCEL 이 함께 나가지 않는다.
 * - 첫 차량 입력은 디바운스 없이 그 값을 쓴다. 사건 도중(OFF · S0 밖)이면 확정 정지(기어 P)만 바로 정차 전이를 내고,
 *   속도만 · 시동 꺼짐으로 본 정지는 5초 디바운스를 거친다. 주차 브레이크는 정지 판정에 쓰지 않는다(사용자 결정 2026-11-13, 합동 점검 B-4)(S3 → S4 는 119 작성 화면 — 정차가 확실할 때만, G5, PM 결정 2026-11-08). 스냅숏에 정지가
 *   실려 왔으면(원신호 없음) 반대 판정도 5초 디바운스를 거친다 — 복원한 정차가 첫 입력 하나로 뒤집히지 않게.
 */
class FlowMachine(val params: FlowParams = FlowParams(), initial: FlowSnapshot = FlowSnapshot()) {
    private var s = initial
    private var out = ArrayList<Directive>()

    val state: FlowState get() = s.state

    fun snapshot(): FlowSnapshot = s

    fun handle(input: FlowInput): List<Directive> {
        out = ArrayList()
        val at = input.atMs
        if (input is FlowInput.Vehicle) applyVehicle(input.state, at)
        commitStop(at)
        // 응답은 같은 ms 의 만료보다 먼저 반영한다(PM 결정 응답 우선) — 그 전 ms 까지의 타이머만 돌리고, 응답 뒤 남은 만료를 본다
        checkTimers(if (input is FlowInput.Response) at - 1 else at)
        when (input) {
            is FlowInput.Power -> {
                s = s.copy(powerOn = input.on)
                if (!input.on && s.state != FlowState.OFF) goOff(at)
            }
            is FlowInput.ConsentsChanged -> {
                s = s.copy(consents = input.consents)
                if (!input.consents.all && s.state != FlowState.OFF) goOff(at)
            }
            is FlowInput.TestMode -> when {
                s.state == FlowState.OFF || s.state == FlowState.S0 -> s = s.copy(test = input.on)
                !input.on && s.test && (s.state == FlowState.S1R || s.state == FlowState.S2) -> endTestRest(at)
            }
            // 테스트 흐름 거두기 — 경보 중이어도 테스트 경보를 멈추고 S0 으로. S5 는 늘 실제라 test 가 false 지만 상태로도 막는다(B-7)
            is FlowInput.WithdrawTest ->
                if (s.test && s.state != FlowState.OFF && s.state != FlowState.S0 && s.state != FlowState.S5) { stopAlerts(); endTestRest(at) }
            is FlowInput.Link ->
                s = s.copy(facts = s.facts.copy(carStateMissing = Ownership.decide(at, input.carLastSyncAtMs) == Owner.PHONE))
            is FlowInput.Vehicle -> onAirbag(input.state.airbagDeployed, at)
            is FlowInput.Wrist -> onWrist(input, at)
            is FlowInput.Response -> { onResponse(input.source, at); checkTimers(at) }
            is FlowInput.Tick -> Unit
            is FlowInput.ClockSync -> s = s.copy(serverOffsetMs = input.offsetMs)
        }
        // 워치 신호 없음 — S0 과 응답 뒤(S1R · S2)에서 한 번 안내한다. 응답 뒤에는 나쁜 판정 지속 시각도 비워 재경보를 멈춘다(리뷰 중 1 (가))
        if (s.state in NO_SIGNAL_NOTICE_STATES && !watchFresh(at) && !s.noSignalNotified) {
            s = s.copy(noSignalNotified = true, weakSinceMs = null)
            emit(Directive.Show(Phrase.SCREEN_WATCH_NO_SIGNAL, s.test))
        }
        if (s.state == FlowState.OFF && s.powerOn && s.consents.all && s.worn && watchFresh(at)) enterS0(at)
        return out
    }

    // ── 차량 · 디바운스 ──

    private fun applyVehicle(v: VehicleState, at: Long) {
        val nulls = if (v.speedKmh == null) s.nullSpeedCount + 1 else 0
        val raw: Boolean = when {
            v.stopped(params.stopSpeedKmh) -> true // 속도를 몰라도 기어 P · 시동 꺼짐이면 정지([VehicleState.stopped])
            v.speedKmh != null -> false
            // 속도는 모르지만 기어가 P 가 아님을 안다 — 직전 정지를 유지하지 않는다(be-qa 지적, B-4 「속도를 모를 때 기어 P 는 정지」와 대칭)
            v.gear != null && v.gear != Gear.UNKNOWN -> false
            // GPS 속도 없음도 「모름」이다 — 정지로 오래 쥐지 않는다(G5, 2026-11-01). 넘겨받은 정지를 GPS 가 움직일 때까지 유지하는 것(B-4 끝 문장)은
            // 휴대폰 엔진이 그동안 Vehicle 입력을 넣지 않는 것으로 맡는다(holdStopUntilMove, PM 결정 24 — 상태 기계에 두면 스스로 본 GPS 정지까지 쥔다)
            nulls == 1 -> s.rawStopped ?: false // 기어도 모르면 한 번은 직전 판정 유지
            else -> false // 속도를 계속 모르면 주행으로 본다
        }
        // 사건 도중의 정지는 확정 입력(기어 P)일 때만 즉시 — 속도만 · 시동 꺼짐으로 본 정지는 디바운스
        val inIncident = s.state != FlowState.OFF && s.state != FlowState.S0
        val immediateOk = !raw || !inIncident || v.confirmedStop(params.stopSpeedKmh)
        if (s.rawStopped == null && !s.stopped && immediateOk) {
            // 첫 차량 입력 — 디바운스 없이 그 값을 쓰고, 사건 도중 확정 정지면 전이를 낸다(PM 결정 2026-11-08).
            // S3 → S4 는 119 작성 화면을 열므로 정차가 확실할 때만 즉시다(G5). 감시 중(OFF · S0)은 전처럼 바로 쓴다
            s = s.copy(rawStopped = raw, stopped = raw, stopPendingSinceMs = null, nullSpeedCount = nulls)
            if (raw) onStopped(at, at)
        } else {
            // 속도만으로 정지인 첫 입력, 원신호 없이 정지가 실려 온 스냅숏(넘겨받음 · 저장 복원)의 첫 입력도 여기 — 5초 디바운스를 거친다
            val pending = if (raw == s.stopped) null else (s.stopPendingSinceMs ?: at)
            s = s.copy(rawStopped = raw, stopPendingSinceMs = pending, nullSpeedCount = nulls)
        }
    }

    /** 디바운스가 차면 정지 판정을 바꾸고 전이를 낸다. */
    private fun commitStop(at: Long) {
        val since = s.stopPendingSinceMs ?: return
        if (at - since < params.stopDebounceMs) return
        val stopped = s.rawStopped == true
        s = s.copy(stopped = stopped, stopPendingSinceMs = null)
        if (stopped) onStopped(since, at) else onMoving(at)
    }

    /** 정지가 확정됐고 반대 판정이 기다리지 않는다 — 119 작성 화면을 열어도 된다. */
    private fun stableStopped() = s.stopped && s.stopPendingSinceMs == null

    private fun onStopped(stopAt: Long, at: Long) {
        if (s.incidentActive) s = s.copy(facts = s.facts.copy(stoppedAtMs = s.facts.stoppedAtMs ?: stopAt))
        when (s.state) {
            FlowState.S1 -> enterAP(at, fresh = false)
            FlowState.S1R -> enterS2(at)
            FlowState.S3 -> enterS4(at)
            else -> Unit
        }
    }

    private fun onMoving(at: Long) {
        when (s.state) {
            FlowState.AP -> enterS1(at, fresh = false)
            FlowState.S2 -> resumeS1R(at)
            FlowState.S4 -> enterS3(at)
            else -> Unit
        }
    }

    /** 에어백 `null` 은 「모름」(읽기 실패 · 휴대폰 주인) — 직전 사실을 그대로 둔다. 명시적 false 만 되돌려 다음 true 가 새 사고가 된다(리뷰 중 3). */
    private fun onAirbag(airbag: Boolean?, at: Long) {
        if (airbag == null) return
        val edge = airbag && !s.airbag
        s = s.copy(airbag = airbag)
        if (edge && s.state != FlowState.OFF && s.state != FlowState.S5) enterS5(at)
    }

    // ── 타이머 ──

    private fun checkTimers(at: Long) {
        if (s.state in UNWORN_OFF_STATES && unwornExpired(at)) { goOff(at); return }
        when (s.state) {
            FlowState.S1, FlowState.AP -> {
                val el = at - s.alertStartMs
                if (s.stage == 1 && el >= params.alert1Ms) startStage2()
                if (el >= params.alert1Ms + params.alert2Ms) {
                    val expiry = s.alertStartMs + params.alert1Ms + params.alert2Ms
                    if (stableStopped()) enterS4(expiry) else enterS3(expiry)
                }
            }
            FlowState.S1R -> {
                if (realertDue(at)) realertPersistent(at)
                else if (at - s.sinceMs >= params.restMs) enterS0(at)
            }
            FlowState.S2 -> {
                if (realertDue(at)) realertPersistent(at)
                // 관찰 만료 — 나쁜 판정이 아니거나(정상 · 미착용) 워치 값이 낡았으면 끝낸다. 신선한 나쁜 판정은 위 재경보가 맡는다(공통 하1)
                else if (at - s.sinceMs >= params.observeMs && (severity(s.verdict) == 0 || !watchFresh(at))) enterS0(at)
            }
            else -> Unit
        }
    }

    // ── 손목 · 응답 ──

    private fun watchFresh(at: Long): Boolean {
        val seen = s.watchSeenAtMs ?: return false
        return !WatchFreshness.isStale(at - seen, params.watchStaleMs)
    }

    private fun onWrist(w: FlowInput.Wrist, at: Long) {
        val seen = at - w.ageMs
        if (s.watchSeenAtMs != null && seen < s.watchSeenAtMs!!) return // 이미 받은 것보다 오래된 값
        s = s.copy(watchSeenAtMs = seen)
        if (!watchFresh(at)) return // 낡은 값은 판정 입력으로 쓰지 않는다
        s = s.copy(noSignalNotified = false)
        onVerdict(w.verdict, at)
    }

    /** 미착용이 [FlowParams.unwornOffMs] 이어졌다 — 꺼짐으로 본다(S0 · S1R · S2). */
    private fun unwornExpired(at: Long): Boolean {
        val since = s.unwornSinceMs ?: return false
        return s.verdict == WristVerdict.CHECK_WEAR && at - since >= params.unwornOffMs
    }

    /** 지속 재경보를 낼 수 있다 — 워치 값이 신선하고, 미착용 깜박임 중이 아니다(나쁜 판정 시작 시각은 깜박임 동안 이어 간다). */
    private fun realertReady(at: Long) = watchFresh(at) && s.verdict != WristVerdict.CHECK_WEAR

    /** S1R · S2 공통 — 지금 나쁜 판정이고, 나쁜 판정이 이어지고, 재경보 간격이 지났음. */
    private fun realertDue(at: Long): Boolean {
        val weak = s.weakSinceMs ?: return false
        return realertReady(at) && severity(s.verdict) >= 1 && at - weak >= realertInterval() && badLongEnough(at)
    }

    /** 마지막 NORMAL 뒤 나쁜 판정이 [FlowParams.realertMinBadMs] 이어졌다 — 재경보 두 경로([realertDue] · [onVerdict]) 모두에 건다(B-2). */
    private fun badLongEnough(at: Long): Boolean = s.lastNormalAtMs?.let { at - it >= params.realertMinBadMs } ?: true

    private fun onVerdict(v: WristVerdict, at: Long) {
        val worsened = severity(v) > severity(s.verdict)
        s = s.copy(verdict = v, worn = v != WristVerdict.CHECK_WEAR)
        // NORMAL 이 30초 이어지면 재경보 간격을 처음으로
        val normalSince = if (v == WristVerdict.NORMAL) (s.normalSinceMs ?: at) else null
        val unwornSince = if (v == WristVerdict.CHECK_WEAR) (s.unwornSinceMs ?: at) else null
        val lastNormal = if (v == WristVerdict.NORMAL) at else s.lastNormalAtMs
        s = s.copy(normalSinceMs = normalSince, unwornSinceMs = unwornSince, lastNormalAtMs = lastNormal)
        if (normalSince != null && at - normalSince >= params.realertResetNormalMs) s = s.copy(realertLevel = 0)
        when (s.state) {
            FlowState.S0 -> when {
                // 감시 중 미착용 — 10초 이어져야 꺼짐(B-6). 그동안 미착용은 severity 0 이라 사건을 열지 않는다
                v == WristVerdict.CHECK_WEAR -> if (unwornExpired(at)) goOff(at)
                severity(v) >= 1 -> trigger(at)
            }
            FlowState.S1R, FlowState.S2 -> when {
                // 응답 뒤 미착용 — 10초 이어지면 꺼짐 + 착용 확인(PM 결정 2026-11-08 · 11-09). 그 전 깜박임은 휴식 · 나쁜 판정 시작 시각을 그대로 둔다
                v == WristVerdict.CHECK_WEAR -> if (unwornExpired(at)) goOff(at)
                v == WristVerdict.PULSE_ABSENT && worsened -> trigger(at)
                severity(v) >= 1 -> {
                    s = s.copy(weakSinceMs = s.weakSinceMs ?: at)
                    if (at - s.weakSinceMs!! >= realertInterval() && badLongEnough(at)) realertPersistent(at)
                }
                // NORMAL — 10초 이어져야 나쁜 판정 시작 시각을 지운다(B-2)
                else -> if (at - normalSince!! >= params.realertClearNormalMs) s = s.copy(weakSinceMs = null)
            }
            else -> Unit
        }
    }

    private fun onResponse(source: ResponseSource, at: Long) {
        if (source == ResponseSource.PHONE_SCREEN && !s.stopped) return
        when (s.state) {
            FlowState.S1, FlowState.S3 -> enterS1R(at)
            FlowState.AP, FlowState.S4 -> {
                s = s.copy(facts = s.facts.copy(responseAtMs = at))
                enterS2(at)
            }
            FlowState.S5 -> { s = s.copy(accidentSent = false); enterS0(at) }
            else -> Unit
        }
    }

    // ── 상태 진입 ──

    private fun goOff(at: Long) {
        stopAlerts()
        setAmbient(null)
        // 사고 문자 1회 표시도 되돌린다. 같은 사고가 다시 문자로 나가지 않는 것은 에어백 false → true 전이 규칙([onAirbag])이 지킨다
        s = s.copy(state = FlowState.OFF, sinceMs = at, stage = 0, incidentActive = false, weakSinceMs = null, accidentSent = false)
        if (!s.worn) emit(Directive.Show(Phrase.SCREEN_CHECK_WEAR, s.test))
    }

    private fun enterS0(at: Long) {
        stopAlerts()
        setAmbient(null)
        // 안내 표시 값을 비운다 — 응답 뒤 「워치 신호 없음」을 낸 채 S0 으로 와도 낡았으면 S0 에서 다시 안내한다
        s = s.copy(state = FlowState.S0, sinceMs = at, stage = 0, incidentActive = false, weakSinceMs = null, noSignalNotified = false)
        when {
            s.verdict == WristVerdict.CHECK_WEAR -> if (unwornExpired(at)) goOff(at) // 10초가 안 됐으면 S0 에서 마저 센다(B-6)
            severity(s.verdict) >= 1 && watchFresh(at) -> trigger(at)
        }
    }

    /**
     * 테스트 세션이 끝났는데 테스트 사건의 응답 뒤 상태(S1R · S2)다 — 표시를 바로 끄고 S0 으로 깨끗이 돌아간다(실주행 3 새 결함, 사용자 지시 2026-11-07).
     * 테스트의 휴식 · 정차 관찰 · 재경보 단계가 실제 경보를 늦추지 않게 나쁜 판정 시작 시각 · 재경보 단계를 버린다.
     * 마지막 판정은 시뮬레이션 값일 수 있어 NORMAL 로 되돌린다 — 남은 시뮬레이션 판정으로 실제 경보를 열지 않고, 다음에 들어오는 실제 판정이 정한다.
     * 테스트로 켠 실내 조명은 테스트 표시로 끈다. 켜기(on=true)와 경보 중(S1 · AP · S3 · S4 · S5)은 그대로 받지 않는다.
     */
    private fun endTestRest(at: Long) {
        setAmbient(null)
        s = s.copy(test = false, verdict = WristVerdict.NORMAL, realertLevel = 0, normalSinceMs = null)
        enterS0(at)
    }

    private fun realertInterval(): Long = params.realertStepsMs[s.realertLevel.coerceAtMost(params.realertStepsMs.lastIndex)]

    /** 나쁜 판정 지속으로 다시 경보 — 다음 간격을 한 단계 늘린다. */
    private fun realertPersistent(at: Long) {
        val next = (s.realertLevel + 1).coerceAtMost(params.realertStepsMs.lastIndex)
        trigger(at)
        s = s.copy(realertLevel = next)
    }

    /** 경보가 새로 시작된다 — 늘 새 사건. */
    private fun trigger(at: Long) {
        s = s.copy(
            incidentActive = true, incidentSmsSent = false, cancelSent = false, observingSent = false, weakSinceMs = null,
            composeOpened = false, alertStartMs = at, stage = 1, alerting = true,
            facts = IncidentFacts(firstAlertAtMs = at, carStateMissing = s.facts.carStateMissing, eventId = newId("event", at)),
        )
        if (s.stopped) {
            s = s.copy(facts = s.facts.copy(stoppedAtMs = at))
            enterAP(at, fresh = true)
        } else {
            enterS1(at, fresh = true)
        }
    }

    private fun enterS1(at: Long, fresh: Boolean) {
        s = s.copy(state = FlowState.S1, sinceMs = at)
        if (fresh) emit(Directive.AlertStart(setOf(AlertTarget.WATCH_VIBRATION), FlowState.S1, 1, params.alert1Ms, s.test, s.facts.eventId))
        emit(Directive.Speak(Phrase.VOICE_S1_PULL_OVER, s.test))
        emit(Directive.Show(Phrase.SCREEN_S1, s.test))
        setAmbient(AmbientMode.INTERIOR_ALERT)
    }

    private fun enterAP(at: Long, fresh: Boolean) {
        s = s.copy(state = FlowState.AP, sinceMs = at)
        if (fresh) emit(Directive.AlertStart(setOf(AlertTarget.WATCH_VIBRATION), FlowState.AP, 1, params.alert1Ms, s.test, s.facts.eventId))
        if (s.stage == 2) apStage2Screen()
        setAmbient(AmbientMode.INTERIOR_ALERT)
    }

    private fun startStage2() {
        s = s.copy(stage = 2)
        emit(Directive.AlertStart(setOf(AlertTarget.WATCH_SOUND, AlertTarget.PHONE_ALARM), s.state, 2, params.alert2Ms, s.test, s.facts.eventId))
        if (s.state == FlowState.AP) apStage2Screen()
    }

    private fun apStage2Screen() {
        emit(Directive.Speak(Phrase.VOICE_AP_CHECK, s.test))
        emit(Directive.Show(Phrase.SCREEN_AP, s.test))
    }

    private fun enterS1R(at: Long) {
        stopAlerts()
        setAmbient(null)
        s = s.copy(
            state = FlowState.S1R, sinceMs = at, stage = 0,
            weakSinceMs = if (severity(s.verdict) >= 1) at else null,
            facts = s.facts.copy(responseAtMs = at),
        )
        emit(Directive.Speak(Phrase.VOICE_S1R_REST, s.test))
        emit(Directive.Show(Phrase.SCREEN_S1R, s.test))
        sendCancelIfNeeded()
    }

    /**
     * S2 에서 다시 출발 — 응답 뒤 휴식(S1R)으로 돌아간다(S1R → S2 의 반대, 리뷰 중 2). 나쁜 판정 시작 시각 · 재경보 단계는 그대로 이어 가
     * 몇 초의 나쁜 판정이 바로 새 사건이 되지 않는다. 10분 휴식은 출발 시각부터 다시 센다. 음성은 내지 않는다(S1R → S2 와 대칭).
     */
    private fun resumeS1R(at: Long) {
        s = s.copy(state = FlowState.S1R, sinceMs = at, stage = 0)
        emit(Directive.Show(Phrase.SCREEN_S1R, s.test))
        setAmbient(null)
    }

    /** 응답한 뒤 정차 관찰. S1R 에서 오면 나쁜 판정 시작 시각을 이어 간다. */
    private fun enterS2(at: Long) {
        stopAlerts()
        val weak = if (s.state == FlowState.S1R) s.weakSinceMs else if (severity(s.verdict) >= 1) at else null
        s = s.copy(
            state = FlowState.S2, sinceMs = at, stage = 0, weakSinceMs = weak,
            facts = s.facts.copy(stoppedAtMs = s.facts.stoppedAtMs ?: at),
        )
        emit(Directive.Show(Phrase.SCREEN_S2, s.test))
        setAmbient(AmbientMode.PARKED_RESCUE)
        sendCancelIfNeeded()
        if (params.sendObservingSms && !s.observingSent) {
            s = s.copy(observingSent = true)
            emit(Directive.SendGuardianSms(GuardianSmsKind.OBSERVING, s.facts, s.test))
        }
    }

    private fun enterS3(at: Long) {
        s = s.copy(state = FlowState.S3, sinceMs = at, stage = 0, facts = s.facts.copy(noResponseAtMs = s.facts.noResponseAtMs ?: at))
        sendIncidentIfNeeded()
        emit(Directive.Speak(Phrase.VOICE_S3_NO_RESPONSE, s.test))
        emit(Directive.Show(Phrase.SCREEN_S3, s.test))
        setAmbient(AmbientMode.INTERIOR_ALERT)
    }

    private fun enterS4(at: Long) {
        // 119 작성 화면을 열기 직전에 정지를 다시 확인한다
        if (!stableStopped()) { enterS3(at); return }
        s = s.copy(
            state = FlowState.S4, sinceMs = at, stage = 0,
            facts = s.facts.copy(noResponseAtMs = s.facts.noResponseAtMs ?: at, stoppedAtMs = s.facts.stoppedAtMs ?: at),
        )
        sendIncidentIfNeeded()
        emit(Directive.Speak(Phrase.VOICE_S4_NO_RESPONSE, s.test))
        emit(Directive.Show(Phrase.SCREEN_S4, s.test))
        setAmbient(AmbientMode.PARKED_RESCUE)
        if (!s.composeOpened) {
            s = s.copy(composeOpened = true)
            emit(Directive.OpenSearch(Phrase.SEARCH_EMERGENCY_ROOM, s.test))
            emit(Directive.Open119Compose(s.facts, previewOnly = s.test, test = s.test))
        }
    }

    /**
     * 에어백 전개는 차의 실제 하드웨어 사실이라 테스트 모드와 상관없이 늘 실제 사고다(PM 결정 2026-10-30).
     * 테스트 중이면 테스트 경보를 테스트 표시로 정리하고, 테스트 사건의 사실은 버린 채 새 실제 사고를 연다.
     * 테스트 표시는 S5 를 벗어날 때까지 다시 켜지지 않는다([FlowInput.TestMode] 켜기는 OFF · S0 에서만 받는다).
     */
    private fun enterS5(at: Long) {
        stopAlerts()
        setAmbient(null)
        val carry = s.incidentActive && !s.test
        s = s.copy(test = false)
        val facts = if (carry) s.facts.copy(accidentAtMs = at, eventId = newId("accident", at))
        else IncidentFacts(carStateMissing = s.facts.carStateMissing, accidentAtMs = at, eventId = newId("accident", at))
        s = s.copy(state = FlowState.S5, sinceMs = at, stage = 0, weakSinceMs = null, incidentActive = carry, facts = facts)
        if (!s.accidentSent) {
            s = s.copy(accidentSent = true)
            emit(Directive.SendGuardianSms(GuardianSmsKind.ACCIDENT, s.facts, s.test))
        }
        emit(Directive.Speak(Phrase.VOICE_S5_ACCIDENT, s.test))
        emit(Directive.Show(Phrase.SCREEN_S5, s.test))
    }

    // ── 도움 ──

    private fun stopAlerts() {
        // 사건 id 는 멈추는 경보의 것 — enterS5 는 사고 id 로 바꾸기 전에 부른다(옛 사건 id 로 정지, app-qa)
        if (s.alerting) { s = s.copy(alerting = false); emit(Directive.AlertStop(s.test, s.facts.eventId)) }
    }

    /** 앰비언트는 [FlowParams.ambientEnabled] 일 때만, 바뀔 때만 낸다. BLUE_RED_BLINK 는 막는다(G6 · 설계 §13). */
    private fun setAmbient(mode: AmbientMode?) {
        check(mode != AmbientMode.BLUE_RED_BLINK) { "BLUE_RED_BLINK 는 법률 검토 전 금지" }
        val target = if (params.ambientEnabled) mode else null
        if (target == s.ambientMode) return
        s = s.copy(ambientMode = target)
        emit(Directive.Ambient(target, s.test))
    }

    private fun sendIncidentIfNeeded() {
        if (s.incidentSmsSent) return
        s = s.copy(incidentSmsSent = true)
        emit(Directive.SendGuardianSms(GuardianSmsKind.INCIDENT, s.facts, s.test))
    }

    private fun sendCancelIfNeeded() {
        if (!s.incidentSmsSent || s.cancelSent) return
        s = s.copy(cancelSent = true)
        emit(Directive.SendGuardianSms(GuardianSmsKind.CANCEL, s.facts, s.test))
    }

    private fun emit(d: Directive) { out += d }

    /** 사건 id — 시계 보정값이 있으면 중계 시각, 없으면 로컬 표시(L). */
    private fun newId(kind: String, at: Long): String =
        s.serverOffsetMs?.let { "$kind-${at + it}" } ?: "$kind-L$at"

    private fun severity(v: WristVerdict) = when (v) {
        WristVerdict.PULSE_WEAK -> 1
        WristVerdict.PULSE_ABSENT -> 2
        else -> 0
    }

    companion object {
        /** 「워치 신호 없음」 안내를 내는 상태 — 감시 중과 응답 뒤. 경보 중(S1 · AP · S3 · S4)에는 신호가 끊겨도 경보를 이어 가므로 내지 않는다. */
        private val NO_SIGNAL_NOTICE_STATES = setOf(FlowState.S0, FlowState.S1R, FlowState.S2)

        /** 미착용이 [FlowParams.unwornOffMs] 이어지면 꺼지는 상태 — 감시 중과 응답 뒤. */
        private val UNWORN_OFF_STATES = setOf(FlowState.S0, FlowState.S1R, FlowState.S2)

        /** 다른 쪽에서 받은 스냅숏으로 이어서 돌린다(주인 넘김). */
        fun restore(snapshot: FlowSnapshot, params: FlowParams = FlowParams()) = FlowMachine(params, snapshot)
    }
}
