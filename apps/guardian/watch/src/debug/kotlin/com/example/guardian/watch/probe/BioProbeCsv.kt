// debug 전용 생체 기록 — 스트림(파일) 목록과 샘플 한 건을 CSV 한 줄로 바꾸는 순수 함수(원시값 그대로, 위도 · 경도 없음 G3)
package com.example.guardian.watch.probe

/**
 * 기록 세션 한 폴더(`files/bio_probe/<yyyyMMdd-HHmmss>/`)에 들어가는 파일들. 열 정의는 계획(plan-20261107-bio.md 「기록 항목」)을 따른다.
 * [COUNTED] 는 state.csv 의 누적 건수 열 순서이자 화면의 건수 표시 순서다.
 */
enum class BioStream(val file: String) {
    SDK_PPG("sdk_ppg.csv"),
    SDK_ACC("sdk_acc.csv"),
    SDK_HR("sdk_hr.csv"),
    AND_ACC("and_acc.csv"),
    AND_GYRO("and_gyro.csv"),
    HS_HR("hs_hr.csv"),
    SPEED("speed.csv"),
    STATE("state.csv"),
    EVENTS("events.csv"),
    /** 자유 형식 글(머리글 없음) — 기기 · 지원 트래커 · 센서 목록 · 시계 대응. */
    META("meta.txt");

    companion object {
        val COUNTED: List<BioStream> = listOf(SDK_PPG, SDK_ACC, SDK_HR, AND_ACC, AND_GYRO, HS_HR, SPEED)
    }
}

/**
 * 위치 한 건에서 속도 관련 값만 옮긴 것. 위도 · 경도 · 고도 필드는 일부러 두지 않는다(G3) — 이동 · 정지 구분에만 쓴다.
 * 값이 없으면(has* = false) null.
 */
data class SpeedFix(
    /** 받은 시각 — SystemClock.elapsedRealtime() ms(단조). */
    val recvMs: Long,
    /** 받은 시각 − 위치의 elapsedRealtimeNanos. 크면 저장해 둔 옛 위치를 다시 준 것이다. */
    val fixAgeMs: Long,
    val speedMps: Float?,
    val speedAccMps: Float?,
    val horizAccM: Float?,
)

object BioProbeCsv {
    const val SDK_PPG_HEADER = "recv_ms,sample_epoch_ms,green,ir,red,green_status,ir_status,red_status"
    const val SDK_ACC_HEADER = "recv_ms,sample_epoch_ms,raw_x,raw_y,raw_z"
    const val SDK_HR_HEADER = "recv_ms,sample_epoch_ms,hr,hr_status,ibi_list,ibi_status_list"
    const val AND_SENSOR_HEADER = "recv_ms,sample_boot_ms,x,y,z,accuracy"
    const val HS_HR_HEADER = "recv_ms,sample_boot_ms,bpm,sensor_status"
    const val SPEED_HEADER = "recv_ms,fix_age_ms,speed_mps,speed_acc_mps,horiz_acc_m"
    const val STATE_HEADER = "recv_ms,wall_ms,screen_on,battery_pct,n_sdk_ppg,n_sdk_acc,n_sdk_hr,n_and_acc,n_and_gyro,n_hs_hr,n_speed,monitoring,io_errors"
    const val EVENTS_HEADER = "recv_ms,kind,detail"

    /** IBI 목록 · 상태 목록을 한 칸에 잇는 구분자(쉼표와 겹치지 않게). */
    const val LIST_SEP = "|"

    /** 스트림의 첫 줄. meta.txt 는 머리글이 없다(null). */
    fun header(s: BioStream): String? = when (s) {
        BioStream.SDK_PPG -> SDK_PPG_HEADER
        BioStream.SDK_ACC -> SDK_ACC_HEADER
        BioStream.SDK_HR -> SDK_HR_HEADER
        BioStream.AND_ACC, BioStream.AND_GYRO -> AND_SENSOR_HEADER
        BioStream.HS_HR -> HS_HR_HEADER
        BioStream.SPEED -> SPEED_HEADER
        BioStream.STATE -> STATE_HEADER
        BioStream.EVENTS -> EVENTS_HEADER
        BioStream.META -> null
    }

    /** Sensor SDK PPG_CONTINUOUS 한 점 — 세 파장과 세 상태를 모두 원시값 그대로(거르지 않는다). */
    fun sdkPpg(recvMs: Long, sampleEpochMs: Long, green: Int?, ir: Int?, red: Int?, greenStatus: Int?, irStatus: Int?, redStatus: Int?): String =
        listOf(recvMs, sampleEpochMs, green, ir, red, greenStatus, irStatus, redStatus).joinToString(",") { it?.toString() ?: "" }

    /** Sensor SDK ACCELEROMETER_CONTINUOUS 한 점 — 환산하지 않은 정수 원시값. */
    fun sdkAcc(recvMs: Long, sampleEpochMs: Long, rawX: Int?, rawY: Int?, rawZ: Int?): String =
        listOf(recvMs, sampleEpochMs, rawX, rawY, rawZ).joinToString(",") { it?.toString() ?: "" }

    /**
     * Sensor SDK HEART_RATE_CONTINUOUS 한 점. IBI 는 상태와 함께 전부 남긴다 — 「상태 0 = 유효」가 아직 [미검증]이라
     * 여기서 거르면 자료를 잃는다. 목록은 [LIST_SEP] 로 잇는다.
     */
    fun sdkHr(recvMs: Long, sampleEpochMs: Long, hr: Int?, hrStatus: Int?, ibiList: List<Int>?, ibiStatusList: List<Int>?): String =
        listOf(
            recvMs.toString(), sampleEpochMs.toString(), hr?.toString() ?: "", hrStatus?.toString() ?: "",
            ibiList.orEmpty().joinToString(LIST_SEP), ibiStatusList.orEmpty().joinToString(LIST_SEP),
        ).joinToString(",")

    /** Android TYPE_ACCELEROMETER · TYPE_GYROSCOPE 한 건. 값은 Float.toString 그대로(반올림하지 않는다). */
    fun andSensor(recvMs: Long, sampleBootMs: Long, x: Float, y: Float, z: Float, accuracy: Int): String =
        listOf(recvMs.toString(), sampleBootMs.toString(), x.toString(), y.toString(), z.toString(), accuracy.toString()).joinToString(",")

    /** Health Services 심박 한 건. [sensorStatus] 는 HeartRateAccuracy.SensorStatus 의 이름(없으면 빈칸). */
    fun hsHr(recvMs: Long, sampleBootMs: Long, bpm: Double, sensorStatus: String?): String =
        listOf(recvMs.toString(), sampleBootMs.toString(), bpm.toString(), cell(sensorStatus ?: "")).joinToString(",")

    fun speed(f: SpeedFix): String = listOf(
        f.recvMs.toString(), f.fixAgeMs.toString(),
        f.speedMps?.toString() ?: "", f.speedAccMps?.toString() ?: "", f.horizAccM?.toString() ?: "",
    ).joinToString(",")

    /**
     * 1초마다 — [counts] 는 [BioStream.COUNTED] 순서의 누적 건수. [monitoring] 은 감시(MonitorService)가 같이 돌고 있는가,
     * [ioErrors] 는 쓰기 실패 누적 수(자료가 조용히 사라지는 구간을 뒤에 찾기 위해).
     */
    fun state(recvMs: Long, wallMs: Long, screenOn: Boolean, batteryPct: Int?, counts: List<Long>, monitoring: Boolean, ioErrors: Long): String {
        require(counts.size == BioStream.COUNTED.size) { "counts ${counts.size} != ${BioStream.COUNTED.size}" }
        return (listOf(recvMs.toString(), wallMs.toString(), if (screenOn) "1" else "0", batteryPct?.toString() ?: "") +
            counts.map { it.toString() } + listOf(if (monitoring) "1" else "0", ioErrors.toString())).joinToString(",")
    }

    /** 사건 한 줄. [detail] 에 쉼표 · 따옴표 · 줄바꿈이 있어도 열이 깨지지 않게 [cell] 로 감싼다. */
    fun event(recvMs: Long, kind: String, detail: String): String =
        listOf(recvMs.toString(), cell(kind), cell(detail)).joinToString(",")

    /** CSV 칸 하나 — 쉼표 · 따옴표가 있으면 따옴표로 감싸고 안의 따옴표는 두 번 쓴다. 줄바꿈은 공백으로 바꾼다. */
    fun cell(s: String): String {
        val flat = s.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
        return if (flat.any { it == ',' || it == '"' }) "\"" + flat.replace("\"", "\"\"") + "\"" else flat
    }
}
