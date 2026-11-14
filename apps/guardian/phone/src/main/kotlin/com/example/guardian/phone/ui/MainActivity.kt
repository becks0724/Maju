// 휴대폰 앱 화면(설계 spec P01~P09 · P15~P17, 11-01 Pleos 양식) — 시작 · 운전자 · 보호자 · 동의 · 권한 · 차와 연결 · 홈(주행 중 입력 막음) · 테스트 모드
package com.example.guardian.phone.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.guardian.phone.core.BackgroundLocation
import com.example.guardian.phone.core.Missing
import com.example.guardian.phone.core.Motion
import com.example.guardian.phone.core.PermState
import com.example.guardian.phone.core.PhoneStore
import com.example.guardian.phone.core.RemoteSmsLimit
import com.example.guardian.phone.core.PhoneEngine
import com.example.guardian.phone.core.PhoneNumbers
import com.example.guardian.phone.core.Readiness
import com.example.guardian.phone.core.Registration
import com.example.guardian.phone.core.TestMode
import com.example.guardian.phone.core.TestStart
import com.example.guardian.phone.core.Withdrawal
import com.example.guardian.phone.core.EngineStatus
import com.example.guardian.phone.data.Prefs
import com.example.guardian.phone.net.OffSender
import com.example.guardian.phone.net.PairEnder
import com.example.guardian.phone.net.RelayClient
import com.example.guardian.phone.net.RelayResult
import com.example.guardian.phone.service.GuardianService
import com.example.guardian.phone.service.Resume
import com.example.guardian.shared.bio.Scenario
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.flow.Owner
import com.example.guardian.shared.report.Consents
import com.example.guardian.shared.report.Reports
import com.example.guardian.shared.text.Phrase
import com.example.guardian.shared.text.SmsText
import java.time.LocalDate

private enum class Screen { START, PROFILE, GUARDIAN, CONSENT, PERMISSION, PAIR, HOME, TEST }

/** 처음 설정 단계 번호(앱 막대 오른쪽 「n / 5」). */
private val SETUP_STEPS = listOf(Screen.PROFILE, Screen.GUARDIAN, Screen.CONSENT, Screen.PERMISSION, Screen.PAIR)

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private val relay = RelayClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // 켜 둔 채로 앱이 죽었으면 서비스를 다시 띄운다(부팅 · 업데이트 수신자와 같은 판단, ResumeDecision)
        Resume.onAppOpen(this, prefs)
        setContent { GuardianTheme { App() } }
    }

    /** 설정 화면(전체 화면 알림 · 방해 금지)에서 돌아오면 권한 상태를 다시 읽게 한다. */
    private val resumeTick = mutableStateOf(0)

    override fun onResume() {
        super.onResume(); UiState.foreground = true; resumeTick.value++
        PairEnder.retry(this, relay) // 앞선 철회에서 끝내지 못한 짝이 있으면 다시 끝낸다(감사 하2)
        OffSender.retry(this, relay) // 앞선 끄기에서 차에 전달하지 못한 꺼짐을 보낸다(C-6)
    }
    override fun onPause() { UiState.foreground = false; super.onPause() }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** 지금 권한 상태 — 다시 켜기 판단과 같은 함수([Resume.permState]). */
    private fun permState(): PermState = Resume.permState(this)

    private fun openSettings(action: String, withPackage: Boolean) {
        val i = Intent(action)
        if (withPackage) i.data = Uri.parse("package:$packageName")
        runCatching { startActivity(i) }
    }

    private fun turnOn() {
        prefs.setPower(true)
        if (prefs.registration.powerOn) GuardianService.start(this)
    }

    /** 끄기 — 짝은 남기고 서비스가 마지막 sync(켬 false)를 보낸 뒤 멈춘다. */
    private fun turnOff() {
        prefs.setPower(false)
        GuardianService.poke()
    }

    /** 실패(네트워크 · 5xx · 시간 초과)하면 토큰만 「끝낼 토큰」으로 남겨 다음 실행 · 서비스 시작 · 중계 다시 닿음 때 다시 부른다(감사 하2 · 계약 §3.2). */
    private fun endPair(token: String) = PairEnder.end(this, token, relay)

    /**
     * 「동의 철회 · 기록 지우기」와 동의 거부(「동의하지 않고 나가기」, 재동의 아님) — 감시 끄기 · pair/end · 저장 전부 지우기
     * (등록 정보 · 보호자 · 동의 · 켬 · 토큰 · 문자 기록, 계약 §3.2 · §6.2 · G2), 그 뒤 처음 설정으로.
     * 동의 거부도 철회로 본다 — 그 전에 넣은 이름 · 생년월일 · 차량 · 보호자 이름과 번호까지 지운다(PM 결정 2026-11-09, 코드 리뷰 하).
     */
    private fun withdrawAll() {
        Withdrawal.run(prefs, lastSync = GuardianService::stopWithLastSync, endPair = ::endPair)
        UiState.smsLog.value = emptyList()
        UiState.noResponse.value = null
        UiState.preview119.value = null
        UiState.notice.value = null
        UiState.line.value = null
    }

    // ── 뼈대 ──

    @Composable
    private fun App() {
        val c = LocalPleosColors.current
        // 동의 문구 판이 올라 다시 받아야 하는 사용자는 P04 부터(ConsentVersion — 판 1 에 ② 를 동의한 사용자는 다시 동의할 때까지 차 화면 표시만 막히고, 판 2 · 3 은 그대로 동작)
        var screen by remember {
            mutableStateOf(when { !prefs.setupDone -> Screen.START; prefs.consentOutdated -> Screen.CONSENT; else -> Screen.HOME })
        }
        var reg by remember { mutableStateOf(prefs.registration) }
        val refresh = { reg = prefs.registration }
        val status by UiState.status
        val inSetup = !prefs.setupDone
        fun next(s: Screen) { refresh(); screen = s }
        val canGoBack = screen != Screen.HOME && screen != Screen.START
        val back = { next(if (inSetup) Screen.START else Screen.HOME) }
        BackHandler(enabled = canGoBack) { back() }

        Column(Modifier.fillMaxSize().background(c.bg)) {
            if (status?.flowTest == true) TestBand()
            val title = when (screen) {
                Screen.START, Screen.HOME -> UiRules.topBarTitle(screen == Screen.START, Phrase.APP_NAME.ko)
                Screen.PROFILE -> PhoneTexts.PROFILE_TITLE
                Screen.GUARDIAN -> PhoneTexts.GUARDIAN_TITLE
                Screen.CONSENT -> PhoneTexts.CONSENT_TITLE
                Screen.PERMISSION -> PhoneTexts.PERM_TITLE
                Screen.PAIR -> PhoneTexts.PAIR_TITLE
                Screen.TEST -> PhoneTexts.TEST_ENTRY
            }
            val step = SETUP_STEPS.indexOf(screen).takeIf { inSetup && it >= 0 }?.let { "${it + 1} / ${SETUP_STEPS.size}" }
            PleosTopBar(title, onBack = if (canGoBack) back else null, step = step, logo = UiRules.topBarLogo(canGoBack, screen == Screen.START))
            // 잠금은 「주행」일 때만. 차 연결도 GPS 속도도 없으면(「모름」) 잠그지 않고 경고 띠만 둔다(사용자 결정 2026-11-01)
            val motion = status?.motion
            val driving = motion == Motion.DRIVING
            MotionBanner(motion)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (screen) {
                    Screen.START -> StartScreen { next(Screen.PROFILE) }
                    Screen.PROFILE -> ProfileScreen(reg, !driving) { prefs.saveProfile(it); next(if (inSetup) Screen.GUARDIAN else Screen.HOME) }
                    Screen.GUARDIAN -> GuardianScreen(reg, !driving) { prefs.saveProfile(it); next(if (inSetup) Screen.CONSENT else Screen.HOME) }
                    Screen.CONSENT -> {
                        val changed = !inSetup && prefs.consentOutdated
                        ConsentScreen(reg.consents, !driving, changed, ConsentRules.changes(prefs.consentVersion, changed), PhoneTexts.consentChanged(prefs.consentVersion),
                            onAgree = { prefs.saveConsents(it); next(if (inSetup) Screen.PERMISSION else Screen.HOME) },
                            // 재동의 거절은 철회가 아니다 — 저장된 동의를 두고 홈으로(동의 판이 낮아 차 표시는 계속 막히고 다음 실행 때 다시 P04).
                            // 그 밖의 거절은 철회와 같다 — 저장을 모두 지우고 처음 설정으로(PM 결정 2026-11-09, G2)
                            onDecline = {
                                if (ConsentRules.declineWithdraws(changed)) { withdrawAll(); next(Screen.START) }
                                else { prefs.setupDone = true; next(Screen.HOME) }
                            })
                    }
                    Screen.PERMISSION -> PermissionScreen(!driving) { next(if (inSetup) Screen.PAIR else Screen.HOME) }
                    Screen.PAIR -> PairScreen(!driving) { prefs.setupDone = true; next(Screen.HOME) }
                    Screen.HOME -> HomeScreen(reg, driving, refresh, onWithdrawn = { next(Screen.START) }) { next(it) }
                    Screen.TEST -> TestScreen { next(Screen.HOME) }
                }
                if (screen == Screen.START || screen == Screen.HOME) Notices()
            }
        }
        Dialogs()
    }

    @Composable
    private fun Title(text: String) {
        Text(text, color = LocalPleosColors.current.text1, fontSize = Dims.titleL, fontWeight = FontWeight.Bold, modifier = Gutter)
    }

    @Composable
    private fun Body(text: String) {
        Text(text, color = LocalPleosColors.current.text1, fontSize = Dims.bodyL, modifier = Gutter)
    }

    @Composable
    private fun Sub(text: String) {
        Text(text, color = LocalPleosColors.current.text2, fontSize = Dims.bodyS, modifier = Gutter)
    }

    /** ▲ 오류 · 안내 줄 — 글리프만 lost, 글자는 text1(빨간 글자 아님). */
    @Composable
    private fun Lost(text: String) {
        val c = LocalPleosColors.current
        Row(Gutter) {
            Text("▲", color = c.lost, fontSize = Dims.bodyL, modifier = Modifier.width(24.dp))
            Text(text, color = c.text1, fontSize = Dims.bodyL)
        }
    }

    /** G1 고지 — 첫 화면 · 홈에 늘 둔다. */
    @Composable
    private fun Notices() {
        Spacer(Modifier.height(8.dp))
        HLine()
        Sub(Phrase.NOTICE_LIMITS.ko)
        Sub(Phrase.NOTICE_NOT_MEDICAL.ko)
    }

    // ── P01 ~ P06 ──

    @Composable
    private fun StartScreen(next: () -> Unit) {
        MajuLockup(Phrase.APP_NAME.ko)
        Body(PhoneTexts.START_INTRO)
        PleosButton(PhoneTexts.START, onClick = next)
    }

    @Composable
    private fun ProfileScreen(reg: Registration, enabled: Boolean, save: (Registration) -> Unit) {
        Sub(PhoneTexts.PROFILE_WHY)
        var name by remember { mutableStateOf(reg.name) }
        var birth by remember { mutableStateOf(reg.birthDate?.toString().orEmpty()) }
        var color by remember { mutableStateOf(reg.carColor) }
        var other by remember { mutableStateOf(reg.carColor.isNotEmpty() && reg.carColor !in PhoneTexts.CAR_COLORS) }
        var plate by remember { mutableStateOf(reg.carPlate) }
        val birthDate = runCatching { LocalDate.parse(birth.trim()) }.getOrNull()

        PleosField(PhoneTexts.FIELD_NAME, name, enabled) { name = it }
        PleosField("${PhoneTexts.FIELD_BIRTH} (${PhoneTexts.FIELD_BIRTH_HINT})", birth, enabled, KeyboardType.Number) { birth = it }
        birthDate?.let { Sub(PhoneTexts.AGE.format(Reports.manAge(it, System.currentTimeMillis()))) }
        Body(PhoneTexts.FIELD_CAR_COLOR)
        // 초단문 판은 색의 첫 글자만 쓰므로 고르기를 권한다(설계 §6-2). 분할 단추처럼 모서리 0 · 높이 48
        val options = PhoneTexts.CAR_COLORS + PhoneTexts.COLOR_OTHER
        PleosSegments(options, if (other) PhoneTexts.COLOR_OTHER else color, { it }, enabled, wrap = true) { o ->
            if (o == PhoneTexts.COLOR_OTHER) { other = true; color = "" } else { color = o; other = false }
        }
        if (other) PleosField(PhoneTexts.FIELD_CAR_COLOR, color, enabled) { color = it }
        PleosField(PhoneTexts.FIELD_CAR_PLATE, plate, enabled) { plate = it }
        val ok = name.isNotBlank() && birthDate != null && color.isNotBlank() && plate.isNotBlank()
        PleosButton(PhoneTexts.NEXT, enabled = enabled && ok) {
            save(reg.copy(name = name.trim(), birthDate = birthDate, carColor = color.trim(), carPlate = plate.trim()))
        }
    }

    @Composable
    private fun GuardianScreen(reg: Registration, enabled: Boolean, save: (Registration) -> Unit) {
        Sub(PhoneTexts.GUARDIAN_WHY)
        var gName by remember { mutableStateOf(reg.guardianName) }
        var gPhone by remember { mutableStateOf(reg.guardianPhone) }
        var agreed by remember { mutableStateOf(reg.guardianName.isNotBlank()) }
        PleosField(PhoneTexts.FIELD_GUARDIAN_NAME, gName, enabled) { gName = it }
        PleosField(PhoneTexts.FIELD_GUARDIAN_PHONE, gPhone, enabled, KeyboardType.Phone) { gPhone = it }
        if (gPhone.isNotBlank() && !PhoneNumbers.isGuardianNumber(gPhone)) Sub(PhoneTexts.GUARDIAN_PHONE_RULE)
        Row(Gutter.fillMaxWidth().toggleable(agreed, enabled, Role.Checkbox) { agreed = it }.heightIn(min = Dims.rowH), verticalAlignment = Alignment.CenterVertically) {
            PleosCheckbox(agreed, enabled)
            Text(PhoneTexts.GUARDIAN_AGREED, color = LocalPleosColors.current.text1, fontSize = Dims.bodyL)
        }
        PleosButton(PhoneTexts.NEXT, enabled = enabled && agreed && gName.isNotBlank() && PhoneNumbers.isGuardianNumber(gPhone)) {
            save(reg.copy(guardianName = gName.trim(), guardianPhone = PhoneNumbers.digits(gPhone)))
        }
    }

    /** 왼쪽 4dp text1 막대 + surfaceHigh 면 — 재동의 띠 · 바뀐 곳 표시(spec 5-1). */
    @Composable
    private fun ChangedBar(text: String, bold: Boolean, gutter: Boolean = true) {
        val c = LocalPleosColors.current
        Row((if (gutter) Gutter else Modifier).fillMaxWidth().height(IntrinsicSize.Min).background(c.surfaceHigh)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(c.text1))
            Text(text, color = c.text1, fontSize = if (bold) Dims.bodyL else Dims.bodyS, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(12.dp))
        }
    }

    /** P04 — 세 상자를 하나씩(「모두 동의」 묶음 없음, 건강 관련은 별도 동의 — G2). */
    @Composable
    private fun ConsentScreen(
        current: Consents, enabled: Boolean, changed: Boolean, marks: ConsentRules.Changes, changedText: String,
        onAgree: (Consents) -> Unit, onDecline: () -> Unit,
    ) {
        // 동의 문구 판이 올라 다시 보일 때만(처음 설정에는 없음) — 저장된 판에 맞는 안내([PhoneTexts.consentChanged])
        if (changed) ChangedBar(changedText, bold = true)
        Text(PhoneTexts.CONSENT_ALL_NEEDED, color = LocalPleosColors.current.text2, fontSize = Dims.bodyL, modifier = Gutter)
        val start = ConsentRules.initial(current, changed)
        var e by remember { mutableStateOf(start.emergencyInfo) }
        var h by remember { mutableStateOf(start.health) }
        var l by remember { mutableStateOf(start.location) }
        ListSection {
            ConsentSection("① ${PhoneTexts.CONSENT_1}", PhoneTexts.CONSENT_BODY_1, null, e, enabled) { e = it }
            ConsentSection("② ${PhoneTexts.CONSENT_2}", PhoneTexts.CONSENT_BODY_2, PhoneTexts.CONSENT_RAW_STAYS, h, enabled, PhoneTexts.CONSENT_CAR_DISPLAY, marks.carNote,
                changedRows = if (marks.healthItems) setOf(0, 3) else emptySet()) { h = it }
            ConsentSection("③ ${PhoneTexts.CONSENT_3}", PhoneTexts.CONSENT_BODY_3, PhoneTexts.CONSENT_NOTIFY_EACH, l, enabled,
                changedRows = if (marks.location) PhoneTexts.CONSENT_BODY_3.indices.toSet() else emptySet()) { l = it }
        }
        Sub(PhoneTexts.DEFAULT_OFF)
        PleosButton(PhoneTexts.CONSENT_AGREE, enabled = enabled && ConsentRules.canAgree(Consents(e, h, l))) { onAgree(Consents(e, h, l)) }
        PleosButton(PhoneTexts.CONSENT_DECLINE, ButtonKind.Basic, enabled, onClick = onDecline)
    }

    /** 동의 한 칸 — surface 면, 칸 사이 1px line(카드 테두리 없음). 줄 56 전체가 toggleable. */
    @Composable
    private fun ConsentSection(
        title: String, rows: List<String>, extra: String?, checked: Boolean, enabled: Boolean,
        note: String? = null, noteChanged: Boolean = false, changedRows: Set<Int> = emptySet(), onChange: (Boolean) -> Unit,
    ) {
        val c = LocalPleosColors.current
        val labels = listOf(PhoneTexts.ROW_ITEMS, PhoneTexts.ROW_TO, PhoneTexts.ROW_WHY, PhoneTexts.ROW_KEEP)
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Checkbox, onValueChange = onChange).heightIn(min = Dims.rowH).padding(end = Dims.gutter, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PleosCheckbox(checked, enabled)
                Text(title, color = c.text1, fontSize = Dims.titleS, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(PhoneTexts.REQUIRED, color = c.text3, fontSize = Dims.labelM)
            }
            rows.forEachIndexed { i, r ->
                // 재동의에서 바뀐 줄은 「바뀐 곳」 표시(ChangedBar 와 같은 왼쪽 막대 + surfaceHigh 면)
                val changedRow = i in changedRows
                Row(if (changedRow) Gutter.fillMaxWidth().height(IntrinsicSize.Min).background(c.surfaceHigh) else Gutter) {
                    if (changedRow) Box(Modifier.width(4.dp).fillMaxHeight().background(c.text1))
                    Text(labels[i], color = c.text3, fontSize = Dims.bodyS, modifier = Modifier.width(64.dp).padding(start = if (changedRow) 8.dp else 0.dp))
                    Text(r, color = c.text1, fontSize = Dims.bodyS)
                }
            }
            extra?.let { Text(it, color = c.text1, fontSize = Dims.bodyS, modifier = Gutter) }
            note?.let { if (noteChanged) ChangedBar(it, bold = false) else Text(it, color = c.text2, fontSize = Dims.bodyS, modifier = Gutter) }
            Text(PhoneTexts.CONSENT_RIGHT, color = c.text2, fontSize = Dims.bodyS, modifier = Gutter)
        }
        HLine()
    }

    @Composable
    private fun PermissionScreen(enabled: Boolean, next: () -> Unit) {
        val c = LocalPleosColors.current
        var tick by remember { mutableStateOf(0) }
        val resumed by resumeTick
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
        // 「항상 허용」은 따로 묻는다(Android 11 부터 앞쪽 위치와 함께 물으면 안 된다). 허용되지 않으면 다음 누름은 앱 설정 화면을 연다
        var bgDenied by remember { mutableStateOf(false) }
        var bgExplain by remember { mutableStateOf(false) }
        val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> bgDenied = !ok; tick++ }
        // tick · resumed 를 읽어 권한이 바뀌면 다시 그린다
        val p = permState().takeIf { tick >= 0 && resumed >= 0 } ?: permState()
        /** [needed] false 면 이 휴대폰은 따로 허용하지 않아도 되는 항목. */
        @Composable fun PermRow(name: String, why: String, ok: Boolean, needed: Boolean, allowLabel: String = PhoneTexts.PERM_ALLOW, canAllow: Boolean = true, allow: () -> Unit) {
            ListRow {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(name, color = c.text1, fontSize = Dims.titleS, fontWeight = FontWeight.Bold)
                    Text(if (!needed) PhoneTexts.PERM_NOT_NEEDED else why, color = c.text2, fontSize = Dims.bodyS)
                }
                if (needed) {
                    if (ok) {
                        Text("●", color = c.ok, fontSize = Dims.bodyL)
                        Text(" ${PhoneTexts.PERM_GRANTED}", color = c.text2, fontSize = Dims.bodyL)
                    } else {
                        Text("▲", color = c.lost, fontSize = Dims.bodyL, modifier = Modifier.padding(end = 8.dp))
                        PleosButton(allowLabel, ButtonKind.Basic, enabled && canAllow, modifier = Modifier, fillWidth = false, onClick = allow)
                    }
                }
            }
        }
        ListSection {
            PermRow(PhoneTexts.PERM_SMS, PhoneTexts.PERM_SMS_WHY, p.sms, true) { launcher.launch(arrayOf(Manifest.permission.SEND_SMS)) }
            PermRow(PhoneTexts.PERM_LOCATION, PhoneTexts.PERM_LOCATION_WHY, p.location, true) {
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            // 위치 항상 허용 — 앞쪽 위치가 허용된 뒤에만, 이유를 먼저 보이고 따로 묻는다. 켜기를 막지 않는다
            val bgStep = BackgroundLocation.step(p.location, p.backgroundLocation, bgDenied)
            PermRow(
                PhoneTexts.PERM_BG_LOCATION,
                if (bgStep == BackgroundLocation.Step.NEED_FINE) PhoneTexts.PERM_BG_LOCATION_NEED_FINE else PhoneTexts.PERM_BG_LOCATION_WHY,
                p.backgroundLocation, true,
                allowLabel = if (bgStep == BackgroundLocation.Step.OPEN_SETTINGS) PhoneTexts.PERM_OPEN_SETTINGS else PhoneTexts.PERM_ALLOW,
                canAllow = bgStep != BackgroundLocation.Step.NEED_FINE,
            ) {
                if (bgStep == BackgroundLocation.Step.OPEN_SETTINGS) openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true)
                else bgExplain = true
            }
            // 알림 — Android 13 이상은 런타임 권한, 그 아래는 앱 알림 설정(꺼 두었을 때만)
            PermRow(PhoneTexts.PERM_NOTIFY, PhoneTexts.PERM_NOTIFY_WHY, p.notify, true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                }
            }
            // 전체 화면 알림 — Android 14 부터 특수 앱 접근(spec 562줄). 13 이하는 따로 허용하지 않아도 된다
            PermRow(PhoneTexts.PERM_FULLSCREEN, PhoneTexts.PERM_FULLSCREEN_WHY, p.fullScreen, Build.VERSION.SDK_INT >= 34) {
                openSettings("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", withPackage = true)
            }
            // 방해 금지 접근 — 있으면 좋지만 켜기를 막지 않는다
            PermRow(PhoneTexts.PERM_DND, PhoneTexts.PERM_DND_WHY, p.dnd, true) {
                openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, withPackage = false)
            }
        }
        if (bgExplain) PleosDialog(
            onDismiss = { bgExplain = false },
            text = { Text(PhoneTexts.PERM_BG_LOCATION_ASK, fontSize = Dims.bodyL) },
            confirm = { PleosButton(PhoneTexts.PERM_CONTINUE, modifier = Modifier, fillWidth = false) { bgExplain = false; bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) } },
            dismiss = { PleosButton(PhoneTexts.BACK, ButtonKind.Basic, modifier = Modifier, fillWidth = false) { bgExplain = false } },
        )
        PleosButton(PhoneTexts.NEXT, enabled = enabled, onClick = next)
    }

    /** 코드 칸 6개(48×60, 사이 8, 3칸 뒤 16, 모서리 0) — 숨긴 입력 하나에 숫자 키보드. 지금 칸은 테두리 3 text1. */
    @Composable
    private fun CodeBoxes(code: String, enabled: Boolean, onChange: (String) -> Unit) {
        val c = LocalPleosColors.current
        BasicTextField(
            value = code, onValueChange = onChange, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Gutter.semantics { contentDescription = PhoneTexts.PAIR_CODE_LABEL },
            decorationBox = { inner ->
              Box {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until 6) {
                        if (i == 3) Spacer(Modifier.width(8.dp))
                        val now = i == code.length
                        Box(
                            Modifier.size(48.dp, 60.dp).background(c.surface).border(if (now) 3.dp else 2.dp, if (now) c.text1 else c.text3, RectangleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(code.getOrNull(i)?.toString().orEmpty(), color = c.text1,
                                style = TextStyle(fontSize = Dims.headlineM, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"))
                        }
                    }
                }
                // 숨은 입력 — 칸 묶음 전체와 겹쳐 탭하면 포커스 · 숫자 키보드가 뜬다. decorationBox 계약대로 정확히 한 번 부른다
                Box(Modifier.matchParentSize().alpha(0f)) { inner() }
              }
            },
        )
    }

    @Composable
    private fun PairScreen(enabled: Boolean, done: () -> Unit) {
        val c = LocalPleosColors.current
        var code by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf<String?>(null) }
        var paired by remember { mutableStateOf(false) }
        var joining by remember { mutableStateOf(false) }
        if (!relay.configured) {
            Sub(PhoneTexts.RELAY_MISSING)
            PleosButton(PhoneTexts.NEXT, onClick = done)
            return
        }
        if (paired) {
            Row(Gutter) {
                Text("●", color = c.ok, fontSize = Dims.titleS, modifier = Modifier.width(24.dp))
                Text(PhoneTexts.PAIR_DONE, color = c.text1, fontSize = Dims.titleS, fontWeight = FontWeight.Bold)
            }
            Text(PhoneTexts.PAIR_WATCH_NEXT, color = c.text2, fontSize = Dims.bodyL, modifier = Gutter)
            PleosButton(PhoneTexts.NEXT, onClick = done)
            return
        }
        Title(PhoneTexts.PAIR_HINT)
        CodeBoxes(code, enabled) { v -> code = v.filter { it in '0'..'9' }.take(6) }
        Sub(PhoneTexts.PAIR_EXPIRE)
        // 중계 답을 기다리는 동안만 기다림 로고 72 + 문구. 답이 오면 결과(연결됨 · ▲ 오류)로 바뀌며 사라진다(motion.md 5절)
        if (joining) Row(Gutter, verticalAlignment = Alignment.CenterVertically) {
            MajuSpinner(waiting = true, size = 72.dp)
            Spacer(Modifier.width(16.dp))
            Text(PhoneTexts.PAIR_JOINING, color = c.text1, fontSize = Dims.bodyL)
        }
        else msg?.let { Lost(it) }
        PleosButton(PhoneTexts.PAIR_BUTTON, enabled = enabled && code.length == 6) {
            val cd = code
            joining = true; msg = null
            Thread {
                val r = relay.join(cd)
                runOnUiThread {
                    joining = false
                    when (r) {
                        is RelayResult.Ok -> { prefs.token = r.value.token; prefs.pairId = r.value.pairId; prefs.pairJoinedAtServerMs = PhoneStore.JOIN_PENDING; paired = true; msg = null; GuardianService.poke() }
                        is RelayResult.NetworkError -> msg = PhoneTexts.PAIR_NETWORK
                        else -> msg = PhoneTexts.PAIR_INVALID
                    }
                }
            }.start()
        }
        if (prefs.setupDone) PleosButton(PhoneTexts.CLOSE, ButtonKind.Basic, onClick = done)
        else PleosButton(PhoneTexts.NEXT, ButtonKind.Text, onClick = done)
    }

    // ── P07 · P08 · P09 홈 ──

    @Composable
    private fun HomeScreen(reg: Registration, driving: Boolean, refresh: () -> Unit, onWithdrawn: () -> Unit, go: (Screen) -> Unit) {
        val c = LocalPleosColors.current
        val running by UiState.running
        val status by UiState.status
        val server by UiState.server
        val notice by UiState.notice
        val resumed by resumeTick
        val perms = permState().takeIf { resumed >= 0 } ?: permState()
        val canOn = Readiness.canTurnOn(reg, perms)

        // 켜짐 칸 — Headline S 28 + 스위치
        ListSection {
            Column(Modifier.fillMaxWidth().padding(Dims.gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (reg.powerOn) PhoneTexts.HOME_ON else PhoneTexts.HOME_OFF, color = c.text1, fontSize = Dims.headlineS, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    PleosSwitch(reg.powerOn, !driving && (canOn || reg.powerOn)) { on -> if (on) turnOn() else turnOff(); refresh() }
                }
                Text(if (reg.powerOn) PhoneTexts.HOME_ON_SUB else UiRules.homeOffSub(canOn), color = c.text2, fontSize = Dims.bodyL)
            }
            HLine()
        }
        // 빠진 조건과 바로 가기 — 꺼짐일 때, 그리고 켜 뒀는데 감시가 돌지 않을 때(권한이 빠져 다시 켜지 못한 경우).
        // 감시가 돌고 있으면 위치 「항상 허용」 한 줄만([Readiness.homeShown])
        run {
            val missing = Readiness.homeShown(Readiness.missing(reg, perms, paired = prefs.token != null, relayConfigured = relay.configured), reg.powerOn, running)
            if (missing.isNotEmpty()) ListSection {
                for (m in missing) {
                    val (label, target) = when (m) {
                        Missing.PROFILE -> PhoneTexts.PROFILE_TITLE to Screen.PROFILE
                        Missing.GUARDIAN -> PhoneTexts.GUARDIAN_TITLE to Screen.GUARDIAN
                        Missing.CONSENT -> PhoneTexts.CONSENT_TITLE to Screen.CONSENT
                        Missing.LOCATION -> PhoneTexts.PERM_LOCATION to Screen.PERMISSION
                        Missing.SMS -> PhoneTexts.PERM_SMS to Screen.PERMISSION
                        Missing.NOTIFY -> PhoneTexts.PERM_NOTIFY to Screen.PERMISSION
                        Missing.FULL_SCREEN -> PhoneTexts.PERM_FULLSCREEN to Screen.PERMISSION
                        Missing.DND -> PhoneTexts.PERM_DND to Screen.PERMISSION
                        Missing.BACKGROUND_LOCATION -> PhoneTexts.PERM_BG_LOCATION to Screen.PERMISSION
                        Missing.PAIR -> PhoneTexts.PAIR_TITLE to Screen.PAIR
                    }
                    ListRow {
                        // 켜기를 막는 것은 ▲(lost 글리프), 표시만 하는 것(방해 금지 · 차와 연결)은 ○(text3)
                        Text(if (m.blocking) "▲" else "○", color = if (m.blocking) c.lost else c.text3, fontSize = Dims.bodyL, modifier = Modifier.width(24.dp))
                        Text(label, color = c.text1, fontSize = Dims.bodyL, modifier = Modifier.weight(1f))
                        PleosButton("${PhoneTexts.GO} →", ButtonKind.Text, !driving, modifier = Modifier) { go(target) }
                    }
                }
            }
        }
        // 연결 줄
        if (running) status?.let { s ->
            SectionHead(PhoneTexts.LINK_HEAD)
            LinkRows(linkRows(s, server))
            PairEntry.label(server)?.let { label -> PleosButton(label, ButtonKind.Basic, PairEntry.enabled(driving)) { go(Screen.PAIR) } }
        }
        // 차 화면 — 심박 숨기기(기본 꺼짐 = 표시). 주행 중에는 다른 홈 조작처럼 막는다(G5). 다음 sync(2초 안)에 차로 간다
        CarScreenSection(reg.carDisplayConsented, reg.hideHrOnCar, !driving, onToggle = { on -> prefs.setHideHrOnCar(on); refresh() }, onGoConsent = { go(Screen.CONSENT) })
        if (reg.guardianName.isNotBlank()) {
            SectionHead(PhoneTexts.GUARDIAN_TITLE)
            ListSection { ListRow { Text("${reg.guardianName} · ${reg.guardianPhone}", color = c.text1, fontSize = Dims.bodyL) } }
        }
        // 보낸 문자 기록(D-8 · 보안 7) — 저장된 최근 24시간 기록이라 앱이 다시 떠도 남는다. 새 결과가 오면(smsLog) 다시 읽는다
        run {
            val log by UiState.smsLog
            val lines = remember(log, resumed) { UiRules.smsHistory(
                prefs.smsRecords, System.currentTimeMillis(), RemoteSmsLimit.decode(prefs.remoteSmsLimit).blockedKeys,
            ) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(it)) } }
            SectionHead(PhoneTexts.SMS_HISTORY_HEAD)
            ListSection {
                if (lines.isEmpty()) ListRow { Text(PhoneTexts.SMS_HISTORY_EMPTY, color = c.text2, fontSize = Dims.bodyL) }
                for (l in lines) ListRow { Text(l, color = c.text1, fontSize = Dims.bodyL) }
            }
        }
        // 테스트 — 주행이 아니고 사건 없음일 때만 들어간다(「모름」이면 테스트 화면에서 「정차 중입니다」를 눌러야 시작)
        val testOk = running && status?.let { it.motion != Motion.DRIVING && it.shownState in setOf(null, FlowState.OFF, FlowState.S0) } == true
        Spacer(Modifier.height(4.dp))
        PleosButton(PhoneTexts.TEST_ENTRY, ButtonKind.Basic, testOk && !driving) { go(Screen.TEST) }
        Sub(PhoneTexts.TEST_ONLY_PARKED)
        notice?.let { Body(it) }
        // 동의 철회 · 기록 지우기 — 정차 중에만 보이는 작은 줄 하나 → 확인 대화(G2 · G5)
        var confirmWithdraw by remember { mutableStateOf(false) }
        if (Withdrawal.visible(status)) PleosButton(PhoneTexts.WITHDRAW, ButtonKind.Text, modifier = Gutter) { confirmWithdraw = true }
        if (confirmWithdraw) PleosDialog(
            onDismiss = { confirmWithdraw = false },
            text = { Text(PhoneTexts.WITHDRAW_CONFIRM, fontSize = Dims.bodyL) },
            confirm = { PleosButton(PhoneTexts.WITHDRAW_OK, modifier = Modifier, fillWidth = false) { confirmWithdraw = false; withdrawAll(); onWithdrawn() } },
            dismiss = { PleosButton(PhoneTexts.BACK, ButtonKind.Basic, modifier = Modifier, fillWidth = false) { confirmWithdraw = false } },
        )
        // 정차 뒤 사건 화면으로 돌아가기(경보 중이면 경보 화면을 다시 연다)
        if (status?.shownState in PhoneEngine.ALARM_STATES || status?.strandedAlarm == true) PleosButton(PhoneTexts.ALARM_OPEN, ButtonKind.Basic, !driving) {
            startActivity(Intent(this, AlarmActivity::class.java))
        }
    }

    /** 연결 줄 세 줄(차 · 워치 · 서버) — 글자는 지금 구현 그대로, 글리프는 [UiRules]. */
    private fun linkRows(s: EngineStatus, server: ServerLink): List<LinkRowUi> = listOf(
        LinkRowUi(
            UiRules.carGlyph(s.carFresh, s.owner), PhoneTexts.LINK_CAR,
            when {
                s.carFresh -> PhoneTexts.LINK_OK
                s.owner == Owner.PHONE -> SmsText.CAR_STATE_MISSING
                else -> PhoneTexts.LINK_CHECKING
            },
            PhoneTexts.CAR_MISSING_WHY.takeIf { s.owner == Owner.PHONE },
        ),
        // 워치 지시(경보 · 테스트)는 중계 직접 경로로만 닿는다(계약 §4.3) — 표시만, 켜기를 막지 않는다
        LinkRowUi(
            UiRules.watchGlyph(s.watchStale), PhoneTexts.LINK_WATCH,
            if (s.watchStale) Phrase.SCREEN_WATCH_NO_SIGNAL.ko else "${PhoneTexts.LINK_OK} · ${PhoneTexts.WORN_NOW}",
            // 워치 값은 오는데 직접 연결이 30초 없으면 그 결과(경보가 가지 않음)를 말한다(C-7)
            if (s.watchAlertUnreachable) PhoneTexts.WATCH_ALERT_UNREACHABLE else PhoneTexts.WATCH_DIRECT_MISSING.takeIf { !s.watchDirect },
        ),
        LinkRowUi(
            UiRules.serverGlyph(server), PhoneTexts.LINK_SERVER,
            when (server) {
                ServerLink.OK -> PhoneTexts.LINK_OK
                ServerLink.FAILED -> PhoneTexts.SERVER_LOST
                ServerLink.EXPIRED -> PhoneTexts.PAIR_EXPIRED
                ServerLink.NO_RELAY -> PhoneTexts.RELAY_MISSING
                ServerLink.NOT_PAIRED -> PhoneTexts.NOT_PAIRED
            },
        ),
    )

    // ── P15 · P16 테스트 ──

    @Composable
    private fun TestScreen(done: () -> Unit) {
        val status by UiState.status
        var msg by remember { mutableStateOf<String?>(null) }
        val s = status
        if (s?.test == null) TestSetupScreen(s, done) { msg = it } else TestProgressScreen(s)
        msg?.let { Lost(it) }
    }

    /** P15 — 시나리오 · 실행 기기 · 「정차 중입니다」(모름일 때만) → 「테스트 시작」. */
    @Composable
    private fun TestSetupScreen(status: EngineStatus?, done: () -> Unit, setMsg: (String?) -> Unit) {
        val c = LocalPleosColors.current
        var scenario by remember { mutableStateOf(Scenario.PULSE_LOST) }
        var phoneOnly by remember { mutableStateOf(false) }
        Sub(PhoneTexts.TEST_ONLY_PARKED)
        SectionHead(PhoneTexts.TEST_SCENARIO)
        ListSection {
            for (sc in Scenario.entries) ListRow(Modifier.selectable(scenario == sc, role = Role.RadioButton) { scenario = sc }) {
                PleosRadio(scenario == sc)
                Text(PhoneTexts.scenario(sc), color = c.text1, fontSize = Dims.bodyL)
            }
        }
        SectionHead(PhoneTexts.TEST_RUN_ON)
        PleosSegments(listOf(false, true), phoneOnly, { if (it) PhoneTexts.TEST_PHONE_ONLY else PhoneTexts.TEST_ON_WATCH }) { phoneOnly = it }
        Sub(PhoneTexts.TEST_ASKS_SMS)
        Sub(PhoneTexts.TEST_119_PREVIEW_ONLY)
        // 주행 중에는 시작하지 못한다(G5). 정차 여부를 모르면 「정차 중입니다」를 먼저 누른다(10분 유효)
        val motion = status?.motion
        val confirmed = status?.stopConfirmed == true
        if (UiRules.showStopConfirm(motion, confirmed)) {
            PleosButton(PhoneTexts.TEST_CONFIRM_STOPPED, ButtonKind.Basic) { GuardianService.withEngine { it.confirmStopped(SystemClock.elapsedRealtime()) } }
        }
        PleosButton(PhoneTexts.TEST_START, enabled = UiRules.testStartEnabled(motion, confirmed), reason = UiRules.testStartReason(motion, confirmed)) {
            val sc = scenario; val po = phoneOnly
            GuardianService.withEngine { e ->
                val r = e.startTest(sc, po, SystemClock.elapsedRealtime())
                UiState.post {
                    setMsg(when (r) {
                        TestStart.WATCH, TestStart.LOCAL -> null
                        TestStart.NEED_SETUP -> PhoneTexts.TEST_NEEDS_SETUP
                        TestStart.NOT_STOPPED -> PhoneTexts.TEST_ONLY_PARKED
                        TestStart.BUSY -> PhoneTexts.TEST_BUSY
                    })
                }
            }
        }
        PleosButton(PhoneTexts.CLOSE, ButtonKind.Basic, onClick = done)
    }

    /** P16 진행 — 지금 단계에 점을 찍는다. 차 화면에서 시작했으면 「차 화면에서 시작함」. */
    @Composable
    private fun TestProgressScreen(s: EngineStatus) {
        val c = LocalPleosColors.current
        val line by UiState.line
        if (s.testFromCar) Text(PhoneTexts.TEST_FROM_CAR, color = c.text3, fontSize = Dims.bodyS, modifier = Gutter)
        val now = UiRules.testStep(s.shownState, s.stage)
        val steps = listOf(
            PhoneTexts.TEST_STEP_WATCH.format(PhoneTexts.scenario(s.testScenario ?: Scenario.NORMAL)),
            PhoneTexts.TEST_STEP_1, PhoneTexts.TEST_STEP_2, PhoneTexts.TEST_STEP_3, PhoneTexts.TEST_STEP_4,
        )
        ListSection {
            steps.forEachIndexed { i, t ->
                ListRow {
                    val mark = when { i < now -> "✓"; i == now -> "●"; else -> "○" }
                    Text(mark, color = if (i > now) c.text3 else c.text1, fontSize = Dims.bodyL, modifier = Modifier.width(24.dp))
                    Text(t, color = if (i > now) c.text2 else c.text1, fontSize = Dims.bodyL, fontWeight = if (i == now) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (i == now) Text(PhoneTexts.TEST_NOW, color = c.text1, fontSize = Dims.labelM, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (s.test == TestMode.LOCAL) line?.let { Text(it, color = c.text2, fontSize = Dims.bodyL, modifier = Gutter) }
        PleosButton(PhoneTexts.TEST_STOP, ButtonKind.Basic) { GuardianService.withEngine { it.stopTest() } }
    }

    /** P17 테스트 문자 확인 · P18 119 미리보기. */
    @Composable
    private fun Dialogs() {
        val c = LocalPleosColors.current
        val pending by UiState.pendingSms
        val reg = prefs.registration
        pending.firstOrNull()?.let { p ->
            PleosDialog(
                onDismiss = {},
                title = { Text(PhoneTexts.TEST_SMS_ASK, fontSize = Dims.titleS, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${reg.guardianName} · ${p.to}", fontWeight = FontWeight.Bold, fontSize = Dims.bodyL)
                        Text(p.body, color = c.text1, fontFamily = FontFamily.Monospace, fontSize = Dims.bodyS,
                            modifier = Modifier.fillMaxWidth().background(c.surfaceHigh).padding(12.dp))
                    }
                },
                confirm = { PleosButton(PhoneTexts.TEST_SMS_SEND, modifier = Modifier, fillWidth = false) { answer(p.key, true) } },
                dismiss = { PleosButton(PhoneTexts.TEST_SMS_SKIP, ButtonKind.Basic, modifier = Modifier, fillWidth = false) { answer(p.key, false) } },
            )
        }
        if (pending.isEmpty()) PreviewDialog()
    }

    private fun answer(key: String, send: Boolean) {
        UiState.pendingSms.value = UiState.pendingSms.value.filterNot { it.key == key }
        GuardianService.withEngine { it.confirmTestSms(key, send) }
    }
}
