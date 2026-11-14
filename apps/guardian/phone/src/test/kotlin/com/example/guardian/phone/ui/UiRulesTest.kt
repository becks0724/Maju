// 화면 표시 판단 시험 — 주행 · 모름 띠, 연결 줄 글리프, 경보 단계 띠, 테스트 시작 버튼 활성 · 사유, 진행 단계, 미리보기 탭
package com.example.guardian.phone.ui

import com.example.guardian.phone.core.Motion
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.report.Consents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiRulesTest {
    @Test fun topBarLogoOnlyOnHome() {
        assertTrue(UiRules.topBarLogo(canGoBack = false, isStart = false))   // 홈
        assertFalse(UiRules.topBarLogo(canGoBack = false, isStart = true))   // 처음 화면 — 본문 가로 조합만
        assertFalse(UiRules.topBarLogo(canGoBack = true, isStart = false))   // 하위 화면
    }

    @Test fun startTopBarTitleIsEmpty() {
        assertEquals("", UiRules.topBarTitle(isStart = true, appName = "운전자 무응답 확인"))
        assertEquals("운전자 무응답 확인", UiRules.topBarTitle(isStart = false, appName = "운전자 무응답 확인"))
    }

    @Test fun bannerOnlyForDrivingOrUnknown() {
        assertEquals(BannerKind.DRIVING_LOCK, UiRules.banner(Motion.DRIVING))
        assertEquals(BannerKind.MOTION_UNKNOWN, UiRules.banner(Motion.UNKNOWN))
        assertNull(UiRules.banner(Motion.STOPPED))
        assertNull(UiRules.banner(null))
    }

    @Test fun linkGlyphs() {
        assertEquals(LinkGlyph.OK, UiRules.carGlyph(carFresh = true, owner = Owner.CAR))
        assertEquals(LinkGlyph.LOST, UiRules.carGlyph(carFresh = false, owner = Owner.PHONE))
        assertEquals(LinkGlyph.PENDING, UiRules.carGlyph(carFresh = false, owner = null))
        assertEquals(LinkGlyph.LOST, UiRules.watchGlyph(watchStale = true))
        assertEquals(LinkGlyph.OK, UiRules.watchGlyph(watchStale = false))
        assertEquals(LinkGlyph.OK, UiRules.serverGlyph(ServerLink.OK))
        assertEquals(LinkGlyph.LOST, UiRules.serverGlyph(ServerLink.FAILED))
        assertEquals(LinkGlyph.LOST, UiRules.serverGlyph(ServerLink.EXPIRED))
        assertEquals(LinkGlyph.PENDING, UiRules.serverGlyph(ServerLink.NOT_PAIRED))
        assertEquals(LinkGlyph.PENDING, UiRules.serverGlyph(ServerLink.NO_RELAY))
    }

    /** P14 상담 권유 — 응답 결과(S1R · S2)이고 정차했을 때만. 주행 중(G5) · S5 변형 · 경보 중에는 없다. */
    @Test fun consultLineOnlyInParkedResult() {
        assertTrue(UiRules.showConsultLine(FlowState.S1R, stopped = true))
        assertTrue(UiRules.showConsultLine(FlowState.S2, stopped = true))
        assertFalse("주행 중 결과 화면에는 없다", UiRules.showConsultLine(FlowState.S1R, stopped = false))
        assertFalse("에어백 전개 변형에는 없다", UiRules.showConsultLine(FlowState.S5, stopped = true))
        for (s in listOf(FlowState.S1, FlowState.AP, FlowState.S3, FlowState.S4, FlowState.S0, FlowState.OFF, null)) {
            assertFalse("$s", UiRules.showConsultLine(s, stopped = true))
        }
    }

    @Test fun alarmStageBand() {
        assertEquals(AlarmStage.STAGE1, UiRules.alarmStage(FlowState.S1, 1))
        assertEquals(AlarmStage.STAGE2, UiRules.alarmStage(FlowState.S1, 2))
        assertEquals(AlarmStage.NO_RESPONSE, UiRules.alarmStage(FlowState.S3, 2))
        assertEquals(AlarmStage.NO_RESPONSE, UiRules.alarmStage(FlowState.S4, 1))
        assertEquals(AlarmStage.STAGE1, UiRules.alarmStage(FlowState.AP, 0))
    }

    /** 「정차 중입니다」 → 「테스트 시작」 순서 — 활성 조건은 지금 동작(MotionRules.canStartTest) 그대로. */
    @Test fun testStartOrder() {
        // 모름 · 확인 전: 확인 버튼 보임, 시작 비활성 + 「먼저 눌러 주세요」
        assertTrue(UiRules.showStopConfirm(Motion.UNKNOWN, false))
        assertFalse(UiRules.testStartEnabled(Motion.UNKNOWN, false))
        assertEquals(PhoneTexts.TEST_CONFIRM_FIRST, UiRules.testStartReason(Motion.UNKNOWN, false))
        // 모름 · 확인 뒤: 확인 버튼 사라짐, 시작 활성
        assertFalse(UiRules.showStopConfirm(Motion.UNKNOWN, true))
        assertTrue(UiRules.testStartEnabled(Motion.UNKNOWN, true))
        assertNull(UiRules.testStartReason(Motion.UNKNOWN, true))
        // 정차: 확인 없이 시작
        assertFalse(UiRules.showStopConfirm(Motion.STOPPED, false))
        assertTrue(UiRules.testStartEnabled(Motion.STOPPED, false))
        // 주행: 확인이 있어도 안 됨, 확인 버튼도 없음
        assertFalse(UiRules.showStopConfirm(Motion.DRIVING, true))
        assertFalse(UiRules.testStartEnabled(Motion.DRIVING, true))
        assertEquals(PhoneTexts.TEST_ONLY_PARKED, UiRules.testStartReason(Motion.DRIVING, true))
        // 상태를 아직 모름(서비스 전)
        assertFalse(UiRules.testStartEnabled(null, false))
    }

    @Test fun testProgressStep() {
        assertEquals(0, UiRules.testStep(FlowState.S0, 0))
        assertEquals(1, UiRules.testStep(FlowState.S1, 1))
        assertEquals(2, UiRules.testStep(FlowState.S1, 2))
        assertEquals(3, UiRules.testStep(FlowState.S3, 2))
        assertEquals(3, UiRules.testStep(FlowState.S4, 2))
    }

    @Test fun previewTabFallsBackToFull() {
        assertEquals(1, UiRules.previewTab(1, hasUltra = true))
        assertEquals(0, UiRules.previewTab(1, hasUltra = false))
        assertEquals(0, UiRules.previewTab(0, hasUltra = true))
    }

    /** 재동의(changed)면 바뀐 ② · ③ 은 꺼진 채로 열고 ① 은 그대로(판 3), 처음 설정 · 설정에서 다시는 저장값 그대로. */
    @Test fun reconsentStartsWithHealthAndLocationOff() {
        val all = Consents(emergencyInfo = true, health = true, location = true)
        assertEquals(Consents(emergencyInfo = true, health = false, location = false), ConsentRules.initial(all, changed = true))
        assertEquals(all, ConsentRules.initial(all, changed = false))
        val none = Consents()
        assertEquals(none, ConsentRules.initial(none, changed = false))
    }

    /** 「바뀐 곳」 표시 — 판 1 이면 차 화면 고지 · ② 항목 · ③, 판 2 면 ② 항목 · ③, 재동의가 아니면 없음. */
    @Test fun reconsentMarksByStoredVersion() {
        assertEquals(ConsentRules.Changes(carNote = true, healthItems = true, location = true), ConsentRules.changes(1, changed = true))
        assertEquals(ConsentRules.Changes(carNote = false, healthItems = true, location = true), ConsentRules.changes(2, changed = true))
        assertEquals(ConsentRules.Changes(false, false, false), ConsentRules.changes(1, changed = false))
        assertEquals(ConsentRules.Changes(false, false, false), ConsentRules.changes(3, changed = false))
        assertEquals(PhoneTexts.CONSENT_CHANGED_FROM_1, PhoneTexts.consentChanged(1))
        assertEquals(PhoneTexts.CONSENT_CHANGED, PhoneTexts.consentChanged(2))
        assertTrue(PhoneTexts.CONSENT_CHANGED_FROM_1.contains("차 화면 표시"))
        assertFalse(PhoneTexts.CONSENT_CHANGED.contains("차 화면 표시"))
    }

    /** 재동의 거절은 철회가 아니다. 처음 설정 · 설정에서 다시의 거절은 지금처럼 철회. */
    @Test fun reconsentDeclineKeepsConsents() {
        assertFalse(ConsentRules.declineWithdraws(changed = true))
        assertTrue(ConsentRules.declineWithdraws(changed = false))
    }

    @Test fun agreeNeedsAllThree() {
        assertTrue(ConsentRules.canAgree(Consents(emergencyInfo = true, health = true, location = true)))
        assertFalse(ConsentRules.canAgree(Consents(emergencyInfo = true, health = false, location = true)))
        assertFalse(ConsentRules.canAgree(Consents(emergencyInfo = false, health = true, location = true)))
        assertFalse(ConsentRules.canAgree(Consents(emergencyInfo = true, health = true, location = false)))
    }

    @Test fun newTextsAreScanned() {
        assertTrue(PhoneTexts.all.containsAll(listOf(PhoneTexts.TEST_CONFIRM_FIRST, PhoneTexts.TEST_FROM_CAR, PhoneTexts.PAIR_CODE_LABEL)))
    }
}
