// 위치 「항상 허용」 판단 시험 — P05 줄 상태(앞쪽 위치 먼저 · 이유 뒤 요청 · 거절 뒤 설정 열기)와 백그라운드에서 되살아난 감시의 상태 알림 판단
package com.example.guardian.phone.core

import com.example.guardian.phone.core.BackgroundLocation.Step
import com.example.guardian.phone.ui.PhoneTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackgroundLocationTest {
    @Test fun stepNeedsFineFirstThenAsksThenSettings() {
        assertEquals(Step.NEED_FINE, BackgroundLocation.step(fine = false, background = false, askedAndDenied = false))
        assertEquals(Step.NEED_FINE, BackgroundLocation.step(fine = false, background = false, askedAndDenied = true))
        assertEquals(Step.ASK, BackgroundLocation.step(fine = true, background = false, askedAndDenied = false))
        assertEquals(Step.OPEN_SETTINGS, BackgroundLocation.step(fine = true, background = false, askedAndDenied = true))
        assertEquals(Step.DONE, BackgroundLocation.step(fine = true, background = true, askedAndDenied = true))
    }

    /** 시스템 재시작(intent null) · 부팅 · 업데이트 수신자는 백그라운드 시작, 앱 화면(켜기 · 앱 열기)은 아니다. */
    @Test fun backgroundStartSources() {
        assertTrue(BackgroundLocation.startedInBackground(intentIsNull = true, fromResumeReceiver = false))
        assertTrue(BackgroundLocation.startedInBackground(intentIsNull = false, fromResumeReceiver = true))
        assertFalse(BackgroundLocation.startedInBackground(intentIsNull = false, fromResumeReceiver = false))
    }

    /** 「항상 허용」이 없고 백그라운드에서 되살아났을 때만 — 실내라 위치가 안 오는 것은 보지 않는다. */
    @Test fun blockedOnlyWhenBackgroundStartWithoutAlways() {
        assertTrue(BackgroundLocation.locationBlocked(startedInBackground = true, background = false))
        assertFalse(BackgroundLocation.locationBlocked(startedInBackground = true, background = true))
        assertFalse(BackgroundLocation.locationBlocked(startedInBackground = false, background = false))
        assertFalse(BackgroundLocation.locationBlocked(startedInBackground = false, background = true))
    }

    /** 상태 알림 문구 — 막혔을 때 「보내는 중」이라고 하지 않고, 앱을 열면 원래 문구로 돌아간다. */
    @Test fun statusNotificationText() {
        assertEquals(PhoneTexts.SERVICE_NOTIFY_BODY, PhoneTexts.serviceNotifyBody(false))
        val blocked = PhoneTexts.serviceNotifyBody(true)
        assertEquals(PhoneTexts.SERVICE_NOTIFY_BODY_NO_LOCATION, blocked)
        assertFalse(blocked.contains("보내는 중"))
        assertTrue(blocked.contains("앱을 열어"))
        for (s in listOf(PhoneTexts.SERVICE_NOTIFY_BODY_NO_LOCATION, PhoneTexts.PERM_BG_LOCATION_WHY, PhoneTexts.PERM_BG_LOCATION_ASK, PhoneTexts.PERM_BG_LOCATION_NEED_FINE)) {
            assertTrue(s, s in PhoneTexts.all)
            assertFalse(s, s.trimEnd().endsWith(":"))
        }
    }

    /** 매니페스트 — 「항상 허용」 권한을 선언한다. */
    @Test fun manifestDeclaresBackgroundLocation() {
        val text = File("src/main/AndroidManifest.xml").readText()
        assertTrue(text.contains("<uses-permission android:name=\"android.permission.ACCESS_BACKGROUND_LOCATION\" />"))
    }
}
