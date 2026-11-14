// 위치 「항상 허용」 판단 — P05 권한 줄의 다음 동작과, 백그라운드에서 되살아난 감시가 위치를 받지 못하는지(상태 알림 문구)를 순수 함수로 고른다
package com.example.guardian.phone.core

/**
 * 근거(실측 11-13, 노트10 Android 12) — 「앱 사용 중에만」 위치 권한으로 감시가 백그라운드에서 되살아나면(업데이트 수신자)
 * 포그라운드 서비스여도 위치를 받지 못했다(`allowWhileInUsePermissionInFgs=false`, GNSS 꺼짐). 앱 화면을 열어 서비스가 앞에서 다시
 * 시작되면 회복했다. 「항상 허용」(`ACCESS_BACKGROUND_LOCATION`)은 켜기를 막지 않는 표시 항목이다([Missing.BACKGROUND_LOCATION]).
 */
object BackgroundLocation {
    /** P05 「위치 항상 허용」 줄의 상태. */
    enum class Step {
        /** 앞쪽 위치(`ACCESS_FINE_LOCATION`)가 아직 없다 — 그것부터 허용해야 따로 물을 수 있다(Android 11 부터 함께 물으면 안 된다). */
        NEED_FINE,
        /** 이유 안내 대화 → 시스템 요청(Android 11 부터는 시스템이 설정 화면으로 보낸다). */
        ASK,
        /** 이번 화면에서 이미 물었는데 허용되지 않았다(거절 · 다시 묻지 않음) — 앱 설정 화면을 연다. */
        OPEN_SETTINGS,
        DONE,
    }

    fun step(fine: Boolean, background: Boolean, askedAndDenied: Boolean): Step = when {
        background -> Step.DONE
        !fine -> Step.NEED_FINE
        askedAndDenied -> Step.OPEN_SETTINGS
        else -> Step.ASK
    }

    /**
     * 서비스를 띄운 시작이 백그라운드에서 왔는가 — 시스템의 START_STICKY 재시작(intent null)이거나 부팅 · 업데이트 수신자([fromResumeReceiver]).
     * 앱 화면(켜기 · 앱 열기)에서 온 시작은 false 다.
     */
    fun startedInBackground(intentIsNull: Boolean, fromResumeReceiver: Boolean): Boolean = intentIsNull || fromResumeReceiver

    /**
     * 상태 알림이 「위치를 받지 못하고 있습니다」를 보일지. 위치가 실제로 오는지(실내라 GPS 가 안 잡힘)는 보지 않는다 —
     * 백그라운드에서 되살아났고 「항상 허용」이 없을 때만 true. 가장 최근 시작이 앱 화면에서 오면 false 로 돌아간다.
     */
    fun locationBlocked(startedInBackground: Boolean, background: Boolean): Boolean = startedInBackground && !background
}
