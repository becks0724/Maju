// 119 반자동 문자 · 보호자 문자 본문 작성(G9 순서 · 동의 조합 · 테스트 접두 · 바이트 한도) — 보내는 함수는 두지 않는다(G4)
package com.example.guardian.shared.report

import com.example.guardian.shared.text.Phrase
import com.example.guardian.shared.text.SmsText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 본문만 만든다. 119 본문은 휴대폰의 반자동 작성 화면(사람이 「보내기」)에만 쓴다.
 *
 * 공통 실패.
 * - 긴급 연락 정보 동의 없음 → EMERGENCY_INFO_CONSENT_MISSING(이름 · 나이 · 차량 · 보호자 줄이 필수라 만들 수 없음).
 * - 보호자 미등록 → GUARDIAN_MISSING(보호자 줄 필수 — 사용자 지시).
 * - 보호자 문자(자동)는 위치 동의가 없으면 AUTO_SMS_NOT_ALLOWED(G9 — 자동 문자 없이 반자동만).
 *
 * 동의별 줄.
 * - 건강 관련 없음 → 워치 줄 없음.
 * - 위치 없음 → 첫 줄 좌표 없음, 위치 줄은 「직접 입력 필요」(위치를 맨 앞에 두는 자리는 지킨다).
 *
 * 위치 줄 시각은 위치를 잰 시각이다. 잰 지 30초가 넘은 위치는 없는 위치와 같이 「위치 확인 안 됨 · 직접 입력 필요」(첫 줄 좌표도 없음) —
 * 그때와 동의 없음의 시각은 작성 시각이다(공통 하4, PM 결정 2026-11-08).
 */
object Reports {
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** SMS 한 통(80byte) · LMS 한 통(2,000byte · 한글 1,000자) — 국내 규격(G9). */
    const val SMS_BYTES = 80
    const val LMS_BYTES = 2_000
    const val LMS_CHARS = 1_000

    /** 문자에 쓸 위치의 최대 나이(휴대폰 GPS · 차 내비 모두). */
    const val LOCATION_FRESH_MS = 30_000L

    /** 만 나이(한국 시각 기준 날짜). */
    fun manAge(birth: LocalDate, nowMs: Long): Int {
        val today = Instant.ofEpochMilli(nowMs).atZone(SEOUL).toLocalDate()
        return ChronoUnit.YEARS.between(birth, today).toInt()
    }

    /** EUC-KR 바이트 수 — ASCII 1, 그 밖(한글 · 기호) 2. */
    fun eucKrBytes(s: String): Int {
        var n = 0
        for (c in s) n += if (c.code < 0x80) 1 else 2
        return n
    }

    /** 119 전체 판(LMS 한 통, 나누지 않는다). */
    fun full119(r: ReportInput): ReportResult {
        precheck(r)?.let { return it }
        val first = "${SmsText.HEADER_119} ${coordOrEmpty(r)}${SmsText.NO_RESPONSE_DRIVER}"
        val lines = listOf(first) + bodyLines(r) + SmsText.TAIL_119.format(Phrase.APP_NAME.ko)
        return ReportResult.Ok(withTest(r.test, lines))
    }

    /**
     * 119 초단문 판(SMS 한 통, EUC-KR 80byte 이내) — 장문이 실패할 때의 예비.
     * 형식 `[119]{위도},{경도} 운전자무응답 {색 첫 글자}{모델}{번호}`. 넘치면 색 → 모델 → 번호 순으로 뺀다.
     */
    fun ultraShort119(r: ReportInput): ReportResult {
        precheck(r)?.let { return it }
        val prefix = if (r.test) SmsText.TEST_PREFIX else ""
        val head = "$prefix${SmsText.ULTRA_HEADER}" + coordOrEmpty(r) + SmsText.NO_RESPONSE_ULTRA
        val color = r.profile.carColor.take(1)
        val model = r.vehicle.model.orEmpty().replace(" ", "")
        val plate = r.profile.carPlate.replace(" ", "")
        val candidates = listOf("$color$model$plate", "$model$plate", plate, "")
        for (c in candidates) {
            val text = if (c.isEmpty()) head else "$head $c"
            if (eucKrBytes(text) <= SMS_BYTES) return ReportResult.Ok(text)
        }
        return ReportResult.Ok(head)
    }

    /** 보호자 사건 문자 — 119 전체 판과 같은 본문, 머리는 「앱이 보낸 안내」, 119 전화 안내를 더한다. */
    fun guardianIncident(r: ReportInput): ReportResult {
        guardianPrecheck(r)?.let { return it }
        val lines = listOf(guardianFirst(r, SmsText.NO_RESPONSE_DRIVER)) + bodyLines(r) +
            SmsText.GUARDIAN_CALL_119 + guardianTail()
        return ReportResult.Ok(withTest(r.test, lines))
    }

    /** 보호자 취소 문자(한 사건 한 번). */
    fun guardianCancel(r: ReportInput, responseAtMs: Long): ReportResult {
        guardianPrecheck(r)?.let { return it }
        val lines = listOf(
            "${SmsText.HEADER_GUARDIAN} ${hm(r.nowMs)}",
            SmsText.CANCEL_BODY.format(hm(responseAtMs)),
            guardianTail(),
        )
        return ReportResult.Ok(withTest(r.test, lines))
    }

    /**
     * 보호자 「응답 확인 꺼짐」 문자 — 사건 문자를 보낸 사건에서 취소 문자 없이 휴대폰에서 켬을 껐을 때 한 번(사용자 결정 2026-11-08).
     * 취소 문자와 같은 꼴(머리 · 본문 한 줄 · 꼬리)이고, 위치 · 건강 값은 싣지 않는다(G2 최소). [offAtMs] 는 끈 시각(벽시계).
     */
    fun guardianPowerOff(r: ReportInput, offAtMs: Long): ReportResult {
        guardianPrecheck(r)?.let { return it }
        val lines = listOf(
            "${SmsText.HEADER_GUARDIAN} ${hm(r.nowMs)}",
            SmsText.POWER_OFF_BODY.format(hm(offAtMs)),
            guardianTail(),
        )
        return ReportResult.Ok(withTest(r.test, lines))
    }

    /** 보호자 사고(에어백 전개) 문자. */
    fun guardianAccident(r: ReportInput): ReportResult {
        guardianPrecheck(r)?.let { return it }
        val lines = listOf(
            guardianFirst(r, ""), locationLine(r), SmsText.ACCIDENT_BODY, SmsText.GUARDIAN_CALL_119, vehicleLine(r), guardianTail(),
        )
        return ReportResult.Ok(withTest(r.test, lines))
    }

    /** 보호자 정차 관찰 안내(선택). */
    fun guardianObserving(r: ReportInput): ReportResult {
        guardianPrecheck(r)?.let { return it }
        val lines = listOf(guardianFirst(r, ""), locationLine(r), SmsText.OBSERVE_BODY, guardianTail())
        return ReportResult.Ok(withTest(r.test, lines))
    }

    // ── 검사 ──

    private fun precheck(r: ReportInput): ReportResult.Fail? = when {
        !r.consents.emergencyInfo -> ReportResult.Fail(ReportFailure.EMERGENCY_INFO_CONSENT_MISSING)
        !r.profile.hasGuardian -> ReportResult.Fail(ReportFailure.GUARDIAN_MISSING)
        else -> null
    }

    private fun guardianPrecheck(r: ReportInput): ReportResult.Fail? =
        precheck(r) ?: if (!r.consents.location) ReportResult.Fail(ReportFailure.AUTO_SMS_NOT_ALLOWED) else null

    // ── 줄 ──

    private fun guardianFirst(r: ReportInput, tail: String): String {
        val head = "${SmsText.HEADER_GUARDIAN} ${coordOrEmpty(r)}$tail"
        return head.trimEnd()
    }

    private fun guardianTail() = SmsText.TAIL_GUARDIAN.format(Phrase.APP_NAME.ko)

    /** G9 2~8 줄 — 위치 · 상태 · 경보 · 워치 · 운전자 · 차량 · 보호자. */
    private fun bodyLines(r: ReportInput): List<String> = buildList {
        add(locationLine(r))
        add(stateLine(r))
        alertLine(r)?.let { add(it) }
        if (r.consents.health) r.watch?.let { add(watchLine(it)) }
        add("${SmsText.DRIVER}: ${r.profile.name} · ${SmsText.AGE.format(manAge(r.profile.birthDate, r.nowMs))}")
        add(vehicleLine(r))
        val contacted = if (r.guardianContacted) " ${SmsText.GUARDIAN_CONTACTED}" else ""
        add("${SmsText.GUARDIAN}: ${r.profile.guardianName} ${r.profile.guardianPhone}$contacted")
    }

    /** 문자에 실을 위치 — 위치 동의가 있고 30초 안에 잰 것만. */
    private fun usableLocation(r: ReportInput): LocationInfo? = r.location?.takeIf { r.consents.location && it.freshAt(r.nowMs) }

    private fun coordOrEmpty(r: ReportInput): String {
        val loc = usableLocation(r) ?: return ""
        return "${coord(loc.lat)},${coord(loc.lon)} "
    }

    /** 「위치: 주소 부근(±8m) · 10:42」 — 시각은 잰 시각. */
    private fun locationLine(r: ReportInput): String {
        val now = hm(r.nowMs)
        if (!r.consents.location) return "${SmsText.LOCATION}: ${SmsText.LOCATION_NO_CONSENT} · $now"
        val loc = usableLocation(r) ?: return "${SmsText.LOCATION}: ${SmsText.LOCATION_UNKNOWN} · $now"
        val addr = loc.address?.let { "$it ${SmsText.LOCATION_NEAR}" } ?: SmsText.LOCATION_NO_ADDRESS
        val acc = loc.accuracyM?.let { "(±${Math.round(it)}m)" } ?: ""
        return "${SmsText.LOCATION}: $addr$acc · ${hm(loc.measuredAtMs)}"
    }

    private fun stateLine(r: ReportInput): String {
        val d = r.driving
        val motion = when {
            d.stopped -> d.stoppedAtMs?.let { SmsText.STOPPED_SINCE.format(hm(it)) } ?: SmsText.STOPPED
            d.speedKmh == null -> SmsText.SPEED_UNKNOWN // 속도를 모르면 주행 중이라고 쓰지 않는다(G9, 코드 리뷰 11-09)
            else -> "${SmsText.DRIVING} ${Math.round(d.speedKmh)}km/h"
        }
        val prefix = if (d.carStateMissing) "${SmsText.CAR_STATE_MISSING} " else ""
        return "${SmsText.STATE}: $prefix$motion · ${SmsText.NO_RESPONSE} ${duration(r.noResponseSec)}"
    }

    private fun alertLine(r: ReportInput): String? {
        val t = r.timeline
        val steps = listOfNotNull(
            t.firstAlertAtMs?.let { "${hm(it)} ${SmsText.ALERT_FIRST}" },
            t.stoppedAtMs?.let { "${hm(it)} ${SmsText.ALERT_STOPPED}" },
            t.noResponseAtMs?.let { "${hm(it)} ${SmsText.ALERT_NO_RESPONSE}" },
        )
        return if (steps.isEmpty()) null else "${SmsText.ALERTS}: ${steps.joinToString(" → ")}"
    }

    private fun watchLine(w: WatchFacts): String {
        val parts = mutableListOf<String>()
        val hr = when {
            w.hrStart != null && w.hrLatest != null -> "${w.hrStart}→${w.hrLatest}"
            w.hrLatest != null -> "${w.hrLatest}"
            else -> null
        }
        hr?.let { parts += "${SmsText.HEART_RATE} $it${SmsText.PER_MIN}" }
        parts += when {
            w.pulseLostAtMs != null -> "${hms(w.pulseLostAtMs)}${SmsText.SINCE} ${SmsText.PULSE_NOT_FOUND}"
            w.pulseWeak -> SmsText.PULSE_WEAK
            else -> SmsText.PULSE_NORMAL
        }
        parts += if (w.worn) SmsText.WORN else SmsText.NOT_WORN
        return "${SmsText.WATCH}(${hm(w.fromMs)}~${hm(w.toMs)}): ${parts.joinToString(", ")}"
    }

    private fun vehicleLine(r: ReportInput): String {
        val v = r.vehicle
        val model = listOfNotNull(v.make, v.model).joinToString(" ").ifEmpty { SmsText.VEHICLE_UNKNOWN }
        val year = v.year?.let { "($it)" } ?: ""
        return "${SmsText.VEHICLE}: $model$year ${r.profile.carColor} ${r.profile.carPlate}"
    }

    // ── 형식 ──

    /** 테스트면 첫 줄 맨 앞에 짧은 접두, 둘째 줄에 「실제 상황 아님」(좌표는 첫 줄 40자 안에 남는다). */
    private fun withTest(test: Boolean, lines: List<String>): String {
        val l = lines.toMutableList()
        if (test) { l[0] = "${SmsText.TEST_PREFIX} ${l[0]}"; l.add(1, SmsText.TEST_NOTICE) }
        return l.joinToString("\n")
    }

    private fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(SEOUL).format(HM)
    private fun hms(ms: Long) = Instant.ofEpochMilli(ms).atZone(SEOUL).format(HMS)
    private fun coord(v: Double) = String.format(Locale.ROOT, "%.5f", v)

    /** 80 → 「1분 20초」, 45 → 「45초」, 120 → 「2분」. */
    fun duration(sec: Int): String {
        val m = sec / 60; val s = sec % 60
        return when {
            m == 0 -> "$s${SmsText.SECOND}"
            s == 0 -> "$m${SmsText.MINUTE}"
            else -> "$m${SmsText.MINUTE} $s${SmsText.SECOND}"
        }
    }
}
