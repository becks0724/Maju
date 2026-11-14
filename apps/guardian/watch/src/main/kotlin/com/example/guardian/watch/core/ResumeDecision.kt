// 감시 다시 켜기 판단 — 저장된 켜짐 × 동의 × 짝(토큰) × 권한 × 시작 자리(부팅 · 업데이트 · 화면)를 순수 함수로 고른다
package com.example.guardian.watch.core

/**
 * 감시 켜짐은 메모리 값이라 재부팅 · 앱 업데이트 · 프로세스 종료 뒤 사라진다(PM 실측 11-01). 사용자가 켜 둔 상태를
 * 저장해 두고, 아래 판단으로 다시 켤지 정한다. Android 호출(권한 확인 · 서비스 시작 · 알림)은 서비스 쪽 [ResumeReceiver] 가 한다.
 *
 * 백그라운드 시작(부팅 · 업데이트 수신자) 근거 — developer.android.com 2026-11-01 확인.
 * - BOOT_COMPLETED · MY_PACKAGE_REPLACED 수신자는 백그라운드 FGS 시작 제한의 예외다(fgs/restrictions-bg-start).
 * - Android 15 의 BOOT_COMPLETED 수신자 FGS 금지 형식(dataSync · camera · mediaPlayback · phoneCall · mediaProjection ·
 *   microphone)에 health 는 없다(about/versions/15/behavior-changes-15).
 * - 그러나 BODY_SENSORS · 심박 READ 권한은 while-in-use 제약을 받아, 백그라운드에서 몸 센서를 쓰는 health FGS 는
 *   BODY_SENSORS_BACKGROUND(API 33~35) 또는 READ_HEALTH_DATA_IN_BACKGROUND(API 36+)가 있어야 만들 수 있다
 *   (fgs/service-types Health 의 Note). 그래서 백그라운드 자리는 [Inputs.backgroundSensor] 도 본다.
 */
object ResumeDecision {
    enum class Trigger { BOOT, PACKAGE_REPLACED, APP_OPEN }

    /** 다시 켜지 못하게 막는 빠진 조건. 화면은 이것으로 무엇이 빠졌는지 보인다. */
    enum class Missing { CONSENT, PAIR, PERMISSION, BACKGROUND_PERMISSION }

    data class Inputs(
        /** 사용자가 켜 둔 상태(저장값). 끄기 · 동의 철회 때만 지운다. */
        val wanted: Boolean,
        val consent: Boolean,
        val paired: Boolean,
        /** 감시에 꼭 필요한 런타임 권한([requiredPermissions])이 모두 허용됐는가. */
        val permissions: Boolean,
        /** 백그라운드 몸 센서 권한([backgroundSensorPermission])이 허용됐거나 필요 없는 판인가. 화면 자리에서는 보지 않는다. */
        val backgroundSensor: Boolean,
    )

    sealed interface Decision {
        /** 켜 둔 적이 없다 — 아무것도 하지 않는다. */
        data object Stay : Decision
        data object Start : Decision
        /** 시작하지 않는다. 저장된 켜짐은 그대로 두고, 백그라운드 자리면 「다시 켜기」 알림을 띄운다. */
        data class Blocked(val missing: Set<Missing>) : Decision
    }

    fun decide(trigger: Trigger, i: Inputs): Decision {
        if (!i.wanted) return Decision.Stay
        val missing = buildSet {
            if (!i.consent) add(Missing.CONSENT)
            if (!i.paired) add(Missing.PAIR)
            if (!i.permissions) add(Missing.PERMISSION)
            if (trigger != Trigger.APP_OPEN && !i.backgroundSensor) add(Missing.BACKGROUND_PERMISSION)
        }
        return if (missing.isEmpty()) Decision.Start else Decision.Blocked(missing)
    }

    /** 수신자가 받은 방송 → 시작 자리. 매니페스트 intent-filter 의 두 동작 밖은 null(아무것도 하지 않음). */
    fun triggerOf(action: String?): Trigger? = when (action) {
        ACTION_BOOT_COMPLETED -> Trigger.BOOT
        ACTION_MY_PACKAGE_REPLACED -> Trigger.PACKAGE_REPLACED
        else -> null
    }

    /** 감시에 꼭 필요한 런타임 권한. API 36+ 는 심박 권한이 세분화된다(S0 권한 표). ACTIVITY_RECOGNITION 은 Sensor SDK 가속도 연속. */
    fun requiredPermissions(sdkInt: Int, sensorSdk: Boolean): List<String> = buildList {
        if (sdkInt >= 36) {
            add(HEALTH_READ_HEART_RATE)
            if (sensorSdk) add(SAMSUNG_READ_ADDITIONAL_HEALTH_DATA) // PPG 연속
        } else {
            add(BODY_SENSORS)
        }
        add(ACTIVITY_RECOGNITION)
    }

    /**
     * 백그라운드에서 몸 센서 health FGS 를 만들 때 필요한 권한. API 32 이하는 백그라운드 몸 센서 권한이 따로 없어 null.
     * API 33~35 의 BODY_SENSORS_BACKGROUND 는 매니페스트에 선언하지 않았으므로 늘 거부로 나와 알림 경로로 간다(보고서 [PM 결정]).
     */
    fun backgroundSensorPermission(sdkInt: Int): String? = when {
        sdkInt >= 36 -> HEALTH_READ_IN_BACKGROUND
        sdkInt >= 33 -> BODY_SENSORS_BACKGROUND
        else -> null
    }

    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    const val BODY_SENSORS = "android.permission.BODY_SENSORS"
    const val BODY_SENSORS_BACKGROUND = "android.permission.BODY_SENSORS_BACKGROUND"
    const val ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"
    const val HEALTH_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    const val HEALTH_READ_IN_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    const val SAMSUNG_READ_ADDITIONAL_HEALTH_DATA = "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
}
