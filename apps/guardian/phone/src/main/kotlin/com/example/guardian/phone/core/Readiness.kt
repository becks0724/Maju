// 기능 켜기 조건과 홈 「빠진 조건」 — 등록 · 동의 · 권한(위치 · 문자 · 알림 · 전체 화면 알림) · 방해 금지 접근 · 차와 연결
package com.example.guardian.phone.core

/**
 * 기기 권한 상태. 이 휴대폰에서 필요 없는 항목(Android 13 이하의 알림 런타임 권한, Android 13 이하의 전체 화면 알림)은 true 로 넣는다.
 * [dnd] 는 방해 금지 접근(알람 음량을 방해 금지 중에도 올리기) — 있으면 좋지만 켜기를 막지 않는다.
 * [backgroundLocation] 은 위치 「항상 허용」 — 백그라운드에서 되살아난 감시도 위치를 받는다. 켜기를 막지 않는다([BackgroundLocation]).
 */
data class PermState(
    val location: Boolean,
    val sms: Boolean,
    val notify: Boolean,
    val fullScreen: Boolean,
    val dnd: Boolean,
    val backgroundLocation: Boolean,
)

/** 빠진 조건. [blocking] 이면 켤 수 없다. */
enum class Missing(val blocking: Boolean) {
    PROFILE(true), GUARDIAN(true), CONSENT(true),
    LOCATION(true), SMS(true),
    /** 알림이 없으면 백그라운드에서 경보 화면 · 알림이 전혀 뜨지 않는다(app-qa 1차 3). */
    NOTIFY(true),
    /** Android 14 이상 — 전체 화면 알림이 막히면 잠금 · 화면 꺼짐에서 경보 화면(P10 · P12)이 뜨지 않는다(app-qa 1차 1). */
    FULL_SCREEN(true),
    /** 표시만 — 켜기를 막지 않는다(app-qa 1차 2). */
    DND(false),
    /** 표시만 — 위치 「항상 허용」. 없으면 부팅 · 업데이트로 되살아난 감시가 앱을 열 때까지 위치를 받지 못한다(실측 11-13). 앞쪽 위치가 있을 때만 보인다. */
    BACKGROUND_LOCATION(false),
    /** 표시만 — 중계 없이 휴대폰 혼자도 돈다. */
    PAIR(false),
}

object Readiness {
    fun missing(reg: Registration, perms: PermState, paired: Boolean, relayConfigured: Boolean): List<Missing> = buildList {
        if (reg.name.isBlank() || reg.birthDate == null || reg.carColor.isBlank() || reg.carPlate.isBlank()) add(Missing.PROFILE)
        if (reg.guardianName.isBlank() || !PhoneNumbers.isGuardianNumber(reg.guardianPhone)) add(Missing.GUARDIAN)
        if (!reg.consents.all) add(Missing.CONSENT)
        if (!perms.location) add(Missing.LOCATION)
        if (perms.location && !perms.backgroundLocation) add(Missing.BACKGROUND_LOCATION)
        if (!perms.sms) add(Missing.SMS)
        if (!perms.notify) add(Missing.NOTIFY)
        if (!perms.fullScreen) add(Missing.FULL_SCREEN)
        if (!perms.dnd) add(Missing.DND)
        if (!paired && relayConfigured) add(Missing.PAIR)
    }

    fun canTurnOn(reg: Registration, perms: PermState): Boolean =
        missing(reg, perms, paired = true, relayConfigured = false).none { it.blocking }

    /**
     * 홈에 보일 빠진 조건. 꺼짐이거나 켜 뒀는데 감시가 돌지 않으면 [missing] 전부, 감시가 돌고 있으면 켜기를 막는 것(감시 중에 문자 · 알림 ·
     * 전체 화면 알림을 끈 경우 — 보호자 문자 · 경보 화면이 막힌다, 중6)과 위치 「항상 허용」(실측 11-13 15:54). 방해 금지 · 차와 연결은 켜짐 때 보이지 않는다.
     */
    fun homeShown(missing: List<Missing>, powerOn: Boolean, running: Boolean): List<Missing> =
        if (!powerOn || !running) missing else missing.filter { it.blocking || it == Missing.BACKGROUND_LOCATION }
}
