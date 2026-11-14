// 차 허브 상태를 화면 판단(CarScreen)에 잇는 확장 — 워치 칸 · 테스트 입구 · 테스트 요청 조건(:integration 이 묶는 CarHub.kt 를 화면 코드에 기대지 않게 따로 둔다)
package com.example.guardian.car

import com.example.guardian.shared.protocol.TestAction

/**
 * 워치 칸 — 서버가 끊겼으면(차가 주인 아님) 값을 보이지 않는다.
 * 심박은 건강 관련 동의와 휴대폰 표시 설정(모르면 숨김)을 따른다(계약 §4.1 `carDisplay`).
 */
fun CarHub.bio(nowMs: Long): BioView {
    val now = watchNow(nowMs)?.takeIf { carIsOwner(nowMs) }
    return CarScreen.bio(now?.first, now?.second, snapshot.consents.health, carDisplay?.hideHr)
}

/** 정차 중 테스트 입구(car-spec 5절). */
fun CarHub.testEntry(nowMs: Long): TestEntryView =
    CarScreen.testEntry(carIsOwner(nowMs), links(nowMs).phone, snapshot.state, snapshot.test, testPending, nowMs, phoneLocalTest)

/**
 * 차 화면 「테스트 시작」 · 「테스트 멈추기」 — 정차(NO_SETUP 제한 없음)이고 입구가 그 동작으로 열려 있을 때만 휴대폰에 요청한다(G5).
 */
fun CarHub.requestTest(action: TestAction, nowMs: Long): Boolean {
    if (!setupOk()) return false
    val entry = testEntry(nowMs)
    if (!entry.enabled || entry.action != action) return false
    sendTestRequest(action, nowMs)
    return true
}
