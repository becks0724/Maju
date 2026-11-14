// 감시 다시 켜기 판단 — 저장된 켜짐 × 빠진 조건(등록 · 동의 · 권한)을 순수 함수로 고른다(워치 ResumeDecision 과 같은 꼴, 실측 11-02 업데이트 뒤 감시 꺼짐)
package com.example.guardian.phone.core

/**
 * 켜짐(`powerOn`)은 저장값이지만 포그라운드 서비스는 프로세스와 함께 사라진다 — 앱 업데이트 · 재부팅 뒤 사용자가 다시 켜기 전까지
 * 차가 휴대폰을 「끊김」으로 봤다(실측 11-02). 사용자가 켜 둔 상태만 되살리고(끈 것은 켜지 않는다), 켤 수 없으면 조용히 실패하지 않고
 * 「다시 켜기」 알림을 남긴다. Android 호출(권한 확인 · 서비스 시작 · 알림)은 서비스 쪽 `Resume` 이 한다.
 *
 * 백그라운드 시작 근거 — BOOT_COMPLETED · MY_PACKAGE_REPLACED 수신자는 백그라운드 FGS 시작 제한의 예외다(워치 ResumeDecision 과 같은 출처).
 * 위치 유형 FGS 를 백그라운드에서 띄웠을 때 위치 접근이 그대로 되는지는 [미검증] — 안 되면 「모름」 이동 판단으로 떨어지고 중계 sync 는 계속된다.
 */
object ResumeDecision {
    enum class Trigger { BOOT, PACKAGE_REPLACED, APP_OPEN }

    data class Inputs(
        /** 사용자가 켜 둔 상태(저장값 `powerOn`). 끄기 · 동의 철회 때만 꺼진다. */
        val wanted: Boolean,
        /** 홈 「빠진 조건」과 같은 목록([Readiness.missing]) — 켜기를 막는 항목([Missing.blocking])만 본다. */
        val missing: List<Missing>,
    )

    sealed interface Decision {
        /** 켜 둔 적이 없다 — 아무것도 하지 않는다. */
        data object Stay : Decision
        data object Start : Decision
        /** 시작하지 않는다. 저장된 켜짐은 그대로 두고 「감시가 멈췄습니다 · 앱을 열어 다시 켜세요」 알림을 띄운다. */
        data class Blocked(val missing: Set<Missing>) : Decision
    }

    fun decide(i: Inputs): Decision {
        if (!i.wanted) return Decision.Stay
        val blocking = i.missing.filter { it.blocking }.toSet()
        return if (blocking.isEmpty()) Decision.Start else Decision.Blocked(blocking)
    }

    /** 수신자가 받은 방송 → 시작 자리. 매니페스트 intent-filter 의 두 동작 밖은 null(아무것도 하지 않음). */
    fun triggerOf(action: String?): Trigger? = when (action) {
        ACTION_BOOT_COMPLETED -> Trigger.BOOT
        ACTION_MY_PACKAGE_REPLACED -> Trigger.PACKAGE_REPLACED
        else -> null
    }

    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
}
