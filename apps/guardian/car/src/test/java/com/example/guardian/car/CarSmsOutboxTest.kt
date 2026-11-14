// 차 문자 보낼 함 · 문자 음성 시험 — 휴대폰 확인(smsDone)까지 같은 지시 id 로 다시 싣기, 주인 아님 · 401 뒤에도 남김, 휴대폰이 안 보이면 「늦어질 수 있습니다」(A-2 · A-5)
package com.example.guardian.car

import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.protocol.CommandType
import com.example.guardian.shared.protocol.FlowStatus
import com.example.guardian.shared.protocol.GuardianSmsWireKind
import com.example.guardian.shared.protocol.SmsDone
import com.example.guardian.shared.protocol.SmsDoneState
import com.example.guardian.shared.text.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSmsOutboxTest {
    private fun smsIn(r: Rig) = r.lastRequest!!.commands.filter { it.type == CommandType.SEND_GUARDIAN_SMS }

    /** 주행 중 경보 → 35초 무응답 → S3(사건 문자). 2초마다 sync, 휴대폰 상태 나이 [phoneAge]. 마지막 시각을 돌려준다. */
    private fun driveToS3(r: Rig, phoneAge: Long = 1_000): Long {
        r.arm(MOVING)
        var t = 2_000L
        while (r.hub.snapshot.state != FlowState.S3) {
            r.hub.onReading(MOVING, t)
            r.sync(t, watch(WristVerdict.PULSE_WEAK), peerAgeMs = phoneAge)
            t += 2_000
            check(t < 60_000)
        }
        return t
    }

    private fun done(eventId: String, state: SmsDoneState): FlowStatus =
        settings().copy(smsDone = listOf(SmsDone(eventId, GuardianSmsWireKind.EVENT, state)))

    @Test fun eventSmsIsResentWithSameIdUntilPhoneConfirms() {
        val r = Rig()
        var t = driveToS3(r)
        val eventId = r.hub.snapshot.facts.eventId!!
        val ids = ArrayList<String>()
        repeat(4) {
            r.hub.onReading(MOVING, t)
            r.sync(t, watch(WristVerdict.PULSE_WEAK))
            ids += smsIn(r).map { it.id }
            t += 2_000
        }
        assertTrue("실렸다", ids.isNotEmpty())
        assertEquals("같은 지시 id 로 다시 싣는다", 1, ids.toSet().size)
        assertTrue("두 번 넘게 실림", ids.size >= 2)
        r.hub.onReading(MOVING, t)
        r.sync(t, watch(WristVerdict.PULSE_WEAK), peerFlow = done(eventId, SmsDoneState.SENT))
        r.hub.onReading(MOVING, t + 2_000)
        r.sync(t + 2_000, watch(WristVerdict.PULSE_WEAK))
        assertTrue("확인되면 더 싣지 않는다", smsIn(r).isEmpty())
        assertEquals("차 화면 줄 — 보호자에게 알림 보냄", SmsStatus.CONFIRMED, r.hub.smsStatus())
    }

    @Test fun smsSurvivesPairLossAndIsCarriedAfterRepair() {
        val r = Rig()
        var t = driveToS3(r)
        r.hub.onPairLost(t)
        t += 2_000
        r.hub.onReading(MOVING, t)
        r.sync(t, watch(WristVerdict.PULSE_WEAK))
        assertTrue("401 뒤 다시 짝지은 첫 요청부터 싣는다", smsIn(r).isNotEmpty())
    }

    @Test fun smsMadeWhileNotOwnerIsKeptAndCarriedOnReconnect() {
        val r = Rig()
        r.arm(MOVING)
        r.sync(2_000, watch(WristVerdict.PULSE_WEAK))
        var t = 4_000L
        while (r.hub.snapshot.state != FlowState.S3) { r.hub.onReading(MOVING, t); r.hub.onSyncFailure(t); t += 2_000 }
        assertTrue(!r.hub.carIsOwner(t))
        assertTrue("주인이 아니어도 남긴다", r.hub.pendingCommands.any { it.type == CommandType.SEND_GUARDIAN_SMS })
        r.hub.onReading(MOVING, t)
        r.sync(t, watch(WristVerdict.PULSE_WEAK), peerFlow = settings())
        r.hub.onReading(MOVING, t + 2_000)
        r.sync(t + 2_000, watch(WristVerdict.PULSE_WEAK), peerFlow = settings())
        assertTrue("다시 붙은 첫 응답을 처리한 뒤(보류 4초 규칙) 싣는다", smsIn(r).isNotEmpty())
    }

    @Test fun voiceSaysSendingOnlyWhenOwnerSeesPhone() {
        val seen = Rig()
        driveToS3(seen)
        assertEquals(Phrase.VOICE_S3_NO_RESPONSE.ko, seen.ports.spoken.last())

        val unseen = Rig()
        driveToS3(unseen, phoneAge = 20_000)
        assertEquals("휴대폰이 10초 넘게 안 보임", Phrase.VOICE_S3_SMS_DELAYED.ko, unseen.ports.spoken.last())
    }

    @Test fun notSentIsShownAndNothingBeforeAnswer() {
        val r = Rig()
        val t = driveToS3(r)
        assertNull("확인 전에는 줄 없음", r.hub.smsStatus())
        r.hub.onReading(MOVING, t)
        r.sync(t, watch(WristVerdict.PULSE_WEAK), peerFlow = done(r.hub.snapshot.facts.eventId!!, SmsDoneState.NOT_SENT))
        assertEquals(SmsStatus.NOT_SENT, r.hub.smsStatus())
    }
}
