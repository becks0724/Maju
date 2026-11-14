// 앱 내부 저장(SharedPreferences) — 등록 정보 · 동의 · 켬/끔 · 중계 토큰 · 문자 중복 막기(보낸 기록 24시간) · 처리한 지시 id · 진행 중 사건 스냅숏. 자동 백업 · 기기 이전에서 뺀다(매니페스트 규칙)
package com.example.guardian.phone.data

import android.content.Context
import android.content.SharedPreferences
import com.example.guardian.phone.core.ConsentVersion
import com.example.guardian.phone.core.PhoneStore
import com.example.guardian.phone.core.Registration
import com.example.guardian.phone.core.ResetStore
import com.example.guardian.phone.core.SavedFlow
import com.example.guardian.shared.protocol.GuardianSmsDedup
import com.example.guardian.shared.report.Consents
import java.time.LocalDate

class Prefs(context: Context) : PhoneStore, ResetStore {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 저장된 동의 문구 판 — 없으면 1(판을 두기 전에 동의한 사용자). */
    val consentVersion: Int get() = sp.getInt("consentVersion", 1)

    /** 지금 판보다 낮은 판으로 ② 에 동의해 둔 사용자 — 앱을 열면 P04 를 보인다. 판 1 이면 다시 동의하기 전까지 차 화면 표시만 막는다([ConsentVersion]). */
    val consentOutdated: Boolean get() = ConsentVersion.outdated(consentVersion, sp.getBoolean("consentHealth", false))

    override val registration: Registration
        get() = Registration(
            name = sp.getString("name", "").orEmpty(),
            birthDate = sp.getString("birthDate", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            carColor = sp.getString("carColor", "").orEmpty(),
            carPlate = sp.getString("carPlate", "").orEmpty(),
            guardianName = sp.getString("guardianName", "").orEmpty(),
            guardianPhone = sp.getString("guardianPhone", "").orEmpty(),
            consents = Consents(
                emergencyInfo = sp.getBoolean("consentEmergency", false),
                health = sp.getBoolean("consentHealth", false),
                location = sp.getBoolean("consentLocation", false),
            ),
            powerOn = sp.getBoolean("powerOn", false),
            hideHrOnCar = sp.getBoolean("hideHrOnCar", false),
            carDisplayConsented = ConsentVersion.carDisplayConsented(consentVersion, sp.getBoolean("consentHealth", false)),
        )

    fun saveProfile(r: Registration) = sp.edit()
        .putString("name", r.name).putString("birthDate", r.birthDate?.toString())
        .putString("carColor", r.carColor).putString("carPlate", r.carPlate)
        .putString("guardianName", r.guardianName).putString("guardianPhone", r.guardianPhone)
        .apply()

    /** 동의를 바꾼다. 하나라도 빠지면 기능을 끄고 진행 중 사건 스냅숏도 지운다(끄기와 같게, G2). 지금 판으로 기록한다(차 화면 표시 동의 포함). */
    fun saveConsents(c: Consents) = sp.edit()
        .putBoolean("consentEmergency", c.emergencyInfo).putBoolean("consentHealth", c.health).putBoolean("consentLocation", c.location)
        .putInt("consentVersion", ConsentVersion.CURRENT)
        .apply { if (!c.all) { putBoolean("powerOn", false); remove("savedFlow") } }
        .apply()

    /** 「차 화면에 심박 숨기기」. */
    fun setHideHrOnCar(on: Boolean) = sp.edit().putBoolean("hideHrOnCar", on).apply()

    /** 끄기는 진행 중 사건 스냅숏도 지운다 — 사용자가 끈 뒤 다시 켤 때 옛 사건을 되살리지 않는다. */
    fun setPower(on: Boolean) = sp.edit().putBoolean("powerOn", on && registration.canTurnOn).apply { if (!on) remove("savedFlow") }.apply()

    override var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    /** 처음 설정(P01~P06)을 한 번 끝냈는가 — 끝냈으면 앱을 열 때 홈부터 보인다. */
    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) = sp.edit().putBoolean("setupDone", v).apply()

    override var pairJoinedAtServerMs: Long?
        get() = if (sp.contains("pairJoinedAt")) sp.getLong("pairJoinedAt", 0L) else null
        set(v) { sp.edit().apply { if (v == null) remove("pairJoinedAt") else putLong("pairJoinedAt", v) }.commit() }

    override var mutedEventId: String?
        get() = sp.getString("mutedEventId", null)
        set(v) { sp.edit().apply { if (v == null) remove("mutedEventId") else putString("mutedEventId", v) }.commit() }

    override var remoteSmsLimit: String?
        get() = sp.getString("remoteSmsLimit", null)
        set(v) { sp.edit().apply { if (v == null) remove("remoteSmsLimit") else putString("remoteSmsLimit", v) }.commit() }

    override var adoptedOwnEventIds: List<String>
        get() = list("adoptedOwnEventIds")
        set(v) { sp.edit().putString("adoptedOwnEventIds", v.joinToString("\n")).commit() }

    override var carOriginEventIds: List<String>
        get() = list("carOriginEventIds")
        set(v) { sp.edit().putString("carOriginEventIds", v.joinToString("\n")).commit() }

    var pairId: String?
        get() = sp.getString("pairId", null)
        set(v) = sp.edit().putString("pairId", v).apply()

    // 문자 키 · 지시 id 는 순서를 지키려고 줄바꿈으로 이어 저장하고 최근 [MAX_KEYS] 개만 둔다

    override fun isSmsSent(key: String) = key in list("smsSent")

    /** 보낸 문자 표시는 바로 디스크에 쓴다(commit) — 앱이 죽어도 두 번 보내지 않게. */
    override fun markSmsSent(key: String) {
        sp.edit().putString("smsSent", (list("smsSent") + key).takeLast(MAX_KEYS).joinToString("\n")).commit()
    }

    /** 보호자 문자 기록 — `GuardianSmsDedup.encode` 한 줄씩. 깨진 줄은 버리고 나머지를 읽는다. 앱이 죽어도 남게 바로 쓴다(commit). */
    override var smsRecords: List<GuardianSmsDedup.Record>
        get() = list("smsRecords").mapNotNull(GuardianSmsDedup::decode)
        set(v) { sp.edit().putString("smsRecords", v.joinToString("\n", transform = GuardianSmsDedup::encode)).commit() }

    override fun isCommandDone(id: String) = id in list("doneIds")

    override fun markCommandDone(id: String) {
        sp.edit().putString("doneIds", (list("doneIds") + id).takeLast(MAX_KEYS).joinToString("\n")).apply()
    }

    /** 진행 중 사건 스냅숏 — 앱이 죽어도 남게 바로 쓴다(commit). 깨진 값은 버린다. */
    override var savedFlow: SavedFlow?
        get() = sp.getString("savedFlow", null)?.let(SavedFlow::decode)
        set(v) { sp.edit().apply { if (v == null) remove("savedFlow") else putString("savedFlow", v.encode()) }.commit() }

    private fun list(k: String): List<String> = sp.getString(k, "").orEmpty().split("\n").filter { it.isNotEmpty() }

    /** 「모두 지우기」 — 이 기기에 남긴 것을 전부 지운다(G2 철회 시 삭제). */
    override fun clearAll() { sp.edit().clear().commit() }

    companion object {
        const val FILE = "guardian"
        const val MAX_KEYS = 256
    }
}
