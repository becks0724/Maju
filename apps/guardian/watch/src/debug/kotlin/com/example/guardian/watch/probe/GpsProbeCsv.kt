// debug 전용 GPS 측정 — 위치 한 건을 CSV 한 줄 · 제공자별 누계 · 요약 한 줄로 바꾸는 순수 함수(위도 · 경도는 담지 않는다, G3)
package com.example.guardian.watch.probe

import java.util.Locale

/**
 * 위치 한 건에서 측정에 필요한 값만 옮긴 것. 위도 · 경도 필드는 일부러 두지 않는다 — 개인 위치이고
 * 「워치가 속도를 직접 받는가」를 재는 데 필요 없다(G3). 값이 없으면(has* = false) null 이다.
 */
data class ProbeFix(
    /** 받은 시각 — SystemClock.elapsedRealtime() ms(단조). */
    val recvElapsedMs: Long,
    val provider: String,
    /** 받은 시각 − 위치의 elapsedRealtimeNanos. 크면 저장해 둔 옛 위치를 다시 준 것이다. */
    val fixAgeMs: Long,
    val speedMps: Float?,
    val speedAccMps: Float?,
    val horizAccM: Float?,
    val hasAltitude: Boolean,
    /** GnssStatus 마지막 값(위치 계산에 쓴 위성 · 보이는 위성). GNSS 엔진 전체 값이라 제공자와 무관하다. */
    val satUsed: Int?,
    val satVisible: Int?,
)

/** 제공자 하나의 누계 — 화면 표시와 요약에 쓴다. */
data class ProviderStats(
    val provider: String,
    val subscribedAtMs: Long,
    val firstFixAtMs: Long? = null,
    val count: Int = 0,
    val last: ProbeFix? = null,
) {
    /** 구독부터 첫 위치까지(TTFF) ms. 아직 없으면 null. */
    val ttffMs: Long? get() = firstFixAtMs?.minus(subscribedAtMs)

    fun with(fix: ProbeFix): ProviderStats =
        copy(firstFixAtMs = firstFixAtMs ?: fix.recvElapsedMs, count = count + 1, last = fix)
}

object GpsProbeCsv {
    const val HEADER =
        "recv_elapsed_ms,provider,fix_age_ms,has_speed,speed_mps,has_speed_acc,speed_acc_mps," +
            "has_h_acc,h_acc_m,sat_used,sat_visible,has_altitude"

    fun row(f: ProbeFix): String = listOf(
        f.recvElapsedMs.toString(),
        f.provider,
        f.fixAgeMs.toString(),
        flag(f.speedMps != null),
        num(f.speedMps, 2),
        flag(f.speedAccMps != null),
        num(f.speedAccMps, 2),
        flag(f.horizAccM != null),
        num(f.horizAccM, 1),
        f.satUsed?.toString() ?: "",
        f.satVisible?.toString() ?: "",
        flag(f.hasAltitude),
    ).joinToString(",")

    /** 측정 끝에 로그 · 파일에 남기는 한 줄. */
    fun summary(elapsedMs: Long, batteryStart: Int?, batteryEnd: Int?, stats: Collection<ProviderStats>): String {
        val perProvider = stats.joinToString(" ; ") { s ->
            val l = s.last
            "${s.provider}: ttff_ms=${s.ttffMs ?: "-"} n=${s.count} " +
                "last_speed=${num(l?.speedMps, 2).ifEmpty { "-" }} " +
                "last_speed_acc=${num(l?.speedAccMps, 2).ifEmpty { "-" }} " +
                "last_h_acc=${num(l?.horizAccM, 1).ifEmpty { "-" }}"
        }
        return "SUMMARY elapsed_s=${elapsedMs / 1000} battery=${batteryStart ?: "-"}->${batteryEnd ?: "-"} | " +
            perProvider.ifEmpty { "no_provider" }
    }

    fun num(v: Float?, digits: Int): String = v?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: ""

    private fun flag(b: Boolean) = if (b) "1" else "0"
}
