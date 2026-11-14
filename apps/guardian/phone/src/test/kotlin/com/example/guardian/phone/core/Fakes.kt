// 시험용 가짜 저장소 · 효과 — 엔진과 실행기가 바깥에 한 일을 기록한다
package com.example.guardian.phone.core

import com.example.guardian.shared.protocol.GuardianSmsDedup
import com.example.guardian.shared.protocol.Party
import com.example.guardian.shared.protocol.Payload
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.report.ReportFailure
import com.example.guardian.shared.report.ReportResult
import java.time.LocalDate

const val GUARDIAN = "01000000001"

fun fullRegistration(consents: Consents = Consents(true, true, true), powerOn: Boolean = true) = Registration(
    name = "홍길동", birthDate = LocalDate.of(1968, 3, 15), carColor = "흰색", carPlate = "12가3456",
    guardianName = "김보호", guardianPhone = GUARDIAN, consents = consents, powerOn = powerOn,
)

class MemoryStore(override var registration: Registration = fullRegistration()) : PhoneStore {
    val sent = LinkedHashSet<String>()
    val done = HashSet<String>()
    override fun isSmsSent(key: String) = key in sent
    override fun markSmsSent(key: String) { sent += key }
    override var smsRecords: List<GuardianSmsDedup.Record> = emptyList()
    override fun isCommandDone(id: String) = id in done
    override fun markCommandDone(id: String) { done += id }
    /** 저장 · 읽기를 글자로 거쳐 실제 저장소처럼 굴린다(encode · decode 까지 시험). */
    private var savedText: String? = null
    override var pairJoinedAtServerMs: Long? = null
    override var mutedEventId: String? = null
    override var remoteSmsLimit: String? = null
    override var carOriginEventIds: List<String> = emptyList()
    override var adoptedOwnEventIds: List<String> = emptyList()
    override var savedFlow: SavedFlow?
        get() = savedText?.let(SavedFlow::decode)
        set(v) { savedText = v?.encode() }
}

/** 실행기 시험용 본문 조립 — 119 본문은 eventId 를 그대로, 보호자 문자는 eventId 별로 정한 글(없으면 「[앱 안내] 본문」). */
class FakeBodies : BodySource {
    val guardian = HashMap<String, String>()
    var fail = false
    var ultra: String? = null
    override fun guardianBody(p: Payload.SendGuardianSms, from: Party): ReportResult =
        if (fail) ReportResult.Fail(ReportFailure.GUARDIAN_MISSING) else ReportResult.Ok(guardian[p.eventId] ?: "[앱 안내] 본문")
    override fun compose119Body(p: Payload.Open119Compose, from: Party): ReportResult =
        if (fail) ReportResult.Fail(ReportFailure.GUARDIAN_MISSING) else ReportResult.Ok(p.eventId)
    override fun ultra119(p: Payload.Open119Compose, from: Party) = ultra
    override fun guardianPowerOff(offAtWallMs: Long): ReportResult =
        if (fail) ReportResult.Fail(ReportFailure.GUARDIAN_MISSING) else ReportResult.Ok("[앱 안내] 꺼짐")
}

class FakeEffects : PhoneEffects {
    val alarms = ArrayList<String>()
    var alarmStops = 0
    val sms = ArrayList<Pair<String, String>>()
    var smsResult = true
    val asked = ArrayList<PendingSms>()
    val composed = ArrayList<String>()
    val noResponses = ArrayList<Body119>()
    val previews = ArrayList<Body119>()
    var mutes = 0
    val smsLog = ArrayList<Pair<String, Boolean?>>()
    /** 보내기 요청 뒤 결과 — true · false 면 바로 돌려주고, null 이면 [heldResults] 에 쥐고 있다가 시험이 부른다. */
    var smsDelivery: Boolean? = true
    val heldResults = ArrayList<(Boolean) -> Unit>()
    val lines = ArrayList<String>()
    val notices = ArrayList<Notice>()

    override fun startAlarm(stage: String, level: Int, test: Boolean) { alarms += stage }
    override fun stopAlarm() { alarmStops++ }
    override fun muteAlarm() { mutes++ }
    /** 문자 결과마다 사건 id(화면이 이번 사건 것만 고르는 데 쓴다). */
    val smsEvents = ArrayList<String>()
    override fun guardianSmsResult(kind: String, eventId: String, sent: Boolean?) { smsLog += kind to sent; smsEvents += eventId }
    override fun showNoResponse(body: Body119) { noResponses += body }
    override fun sendSms(to: String, body: String, done: (Boolean) -> Unit): Boolean {
        if (!smsResult) return false
        sms += to to body
        smsDelivery?.let(done) ?: heldResults.add(done)
        return true
    }
    override fun askTestSms(pending: PendingSms) { asked += pending }
    override fun open119Compose(body: String) { composed += body }
    override fun show119Preview(body: Body119) { previews += body }
    override fun showLine(line: String, button: String?, test: Boolean) { lines += line }
    override fun notice(n: Notice) { notices += n }
    var testScreensCleared = 0
    override fun clearTestScreens() { testScreensCleared++ }
    /** 위치 제공 통보(보안 7) — 보냄 true · 확인 못 함 null. */
    val locationNotices = ArrayList<Boolean?>()
    override fun locationSmsSettled(sent: Boolean?) { locationNotices += sent }
    var testSmsAskCleared = 0
    override fun clearTestSmsAsk() { testSmsAskCleared++ }
}
