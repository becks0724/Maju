// 끄기 전달(C-6) 규칙 — 꺼짐 스냅숏을 중계에 전달하지 못했으면 남기고 다음 기회에 반드시 보낸다, 그 전에 다시 켜면 버린다(안드로이드 의존 없음)
package com.example.guardian.phone.core

import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.protocol.CarDisplay
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.PhoneSyncRequest

/** 전달하지 못한 꺼짐이 남아 있는가 — 등록 정보와 다른 파일에 둔다(짝 끝내기의 [PendingEndStore] 와 같은 꼴). */
interface OffDeliveryStore {
    var offPending: Boolean
}

/**
 * 끄기의 꺼짐 sync 가 차에 닿지 않으면 차는 휴대폰을 켬으로 알고 감시를 이어 간다(레드 중7 · 합동 C-6). 지하 주차장처럼 데이터가 없는 곳은
 * 몇십 초를 넘기므로 재시도 몇 번으로는 닫히지 않는다 — 전달할 때까지 남겨 두고, 앱 열기 · 부팅 수신 · 중계 다시 닿음에 다시 보낸다.
 * 전달 전까지 차가 휴대폰을 켬으로 보는 것은 한계다(차 쪽 문턱은 감시를 끄는 데 쓰지 않는다 — app-qa 2차).
 * 철회는 이 길을 타지 않는다(짝을 끝내므로 [LastSyncWait] · 짝 끝내기가 맡는다).
 */
class OffDelivery(private val store: OffDeliveryStore) {
    enum class Result {
        /** 중계가 받았다(200). */
        DELIVERED,
        /** 401 — 짝이 없어졌다. 전달할 곳이 없다. */
        GONE,
        /** 네트워크 오류 · 그 밖의 HTTP 오류. */
        FAILED,
        /** 보내지 않았다(짝 · 중계 주소 없음). */
        NOT_SENT,
    }

    private var firstFailAt: Long? = null

    /** 끄기 주기의 시작 — 짝이 있으면 보내기 전에 먼저 남긴다(보내는 도중 프로세스가 죽어도 잃지 않게). */
    fun begin(paired: Boolean) {
        if (paired) store.offPending = true
    }

    /** 끄기 주기의 꺼짐 sync 결과. 서비스를 멈춰도 되면 true — 실패면 [RETRY_MS] 동안 주기마다 다시 보내고, 그 뒤에는 남긴 채 멈춘다. */
    fun onShutdownSync(r: Result, nowMono: Long): Boolean {
        if (r != Result.FAILED) { store.offPending = false; firstFailAt = null; return true }
        store.offPending = true
        val first = firstFailAt ?: nowMono.also { firstFailAt = it }
        return nowMono - first >= RETRY_MS
    }

    /** 다시 켰다 — 전달하지 못한 꺼짐은 버린다(켬 sync 가 그 뒤 사실이다). */
    fun onPowerOn() {
        store.offPending = false
        firstFailAt = null
    }

    /** 앱 열기 · 부팅 수신 · 중계 다시 닿음 — 남긴 꺼짐을 지금 보낼 때인가. 켜져 있거나 짝이 없으면 버린다. */
    fun due(powerOn: Boolean, hasToken: Boolean): Boolean {
        if (!store.offPending) return false
        if (powerOn || !hasToken) { store.offPending = false; return false }
        return true
    }

    /** [due] 뒤 보낸 결과 — 실패만 남긴다. */
    fun onSent(r: Result) {
        if (r != Result.FAILED) store.offPending = false
    }

    companion object {
        /** 끄기 주기에서 다시 보내는 창 — 잠깐 끊긴 망을 덮는 값. 그 뒤는 남겨 두고 다음 기회에 보낸다 [추정]. */
        const val RETRY_MS = 20_000L

        /**
         * 서비스 밖에서 보내는 꺼짐 sync — 설정만 담은 OFF 스냅숏(owner CAR · 내 단조 시각, 엔진의 「주인 아님 · 보정 없음」과 같은 꼴).
         * 워치 값 · 지시 · ack · 응답 시각은 싣지 않는다(G2 최소). seq 는 지금 벽시계 — 앞선 엔진의 seq(시작 시각 + 주기 수)보다 크다.
         */
        fun request(token: String, wallNow: Long, monoNow: Long, registration: Registration): PhoneSyncRequest {
            val snap = FlowSnapshot(state = FlowState.OFF, sinceMs = monoNow, test = false, powerOn = false, consents = registration.consents)
            return PhoneSyncRequest(
                token = token, seq = wallNow, sentAt = wallNow, watch = null, flow = FlowStatus.forSync(snap, Owner.CAR, null),
                carDisplay = CarDisplay(hideHr = registration.hideHrForCar), healthConsent = registration.consents.health,
            )
        }
    }
}
