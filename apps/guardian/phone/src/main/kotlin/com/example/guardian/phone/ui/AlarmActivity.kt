// 전체 화면 경보(설계 spec P10 · P10' · P11 · P12 · P14, 11-01 Pleos 양식) — 잠금 화면 위, 늘 어두움. 주행 중에는 버튼 0개, 정차 뒤에만 「괜찮아요」 · 119 작성 화면 열기
package com.example.guardian.phone.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guardian.phone.R
import com.example.guardian.phone.core.EngineStatus
import com.example.guardian.phone.core.PhoneEngine
import com.example.guardian.phone.data.Prefs
import com.example.guardian.phone.service.Emergency119
import com.example.guardian.phone.service.GuardianService
import com.example.guardian.shared.flow.FlowState
import com.example.guardian.shared.report.Reports
import com.example.guardian.shared.text.Phrase
import com.example.guardian.shared.text.SmsText
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = Prefs(this)
        // 늘 어두움 — 테스트 띠 · 미리보기 대화창이 어두운 값을 쓰게 dark 테마로 감싼다
        setContent { GuardianTheme(dark = true) { AlarmRoot(prefs) { finish() } } }
    }

    override fun onResume() { super.onResume(); UiState.foreground = true }
    override fun onPause() { UiState.foreground = false; super.onPause() }
}

/** 시:분 표시 — 시간대는 부를 때마다 기기 시간대를 읽는다(바꾸면 바로 따른다). */
private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA)

private fun hm(ms: Long) = HM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** 「응답 확인 필요 · 워치 버튼을 누르세요」 → 두 줄(설계 §6-1 「 · 」에서 나눔). */
private fun split(p: Phrase): Pair<String, String> = p.ko.substringBefore(" · ") to p.ko.substringAfter(" · ", "")

/** 고정폭 숫자(tnum) — 경과 초가 바뀌어도 글자 폭이 흔들리지 않게. */
private val Tnum = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun AlarmRoot(prefs: Prefs, close: () -> Unit) {
    val c = AlarmColors
    val status by UiState.status
    val running by UiState.running
    var last by remember { mutableStateOf<EngineStatus?>(null) }
    var everAlarm by remember { mutableStateOf(false) }
    // 서비스가 멈춰 상태가 지워졌거나 경보를 본 적 없이 상태가 없으면 빈 화면으로 두지 않고 닫는다(코드 리뷰 2026-11-08 하2)
    val raw = UiRules.alarmStatus(status, last, running, everAlarm) ?: run { LaunchedEffect(Unit) { close() }; return }
    last = raw
    // 상태 모름(차 링크가 끊겨 휴대폰이 이어받기 전의 틈)은 끝난 것이 아니다 — 직전 화면을 그대로 그린다(app-qa 불합격 · PM 결정 2026-11-08)
    var held by remember { mutableStateOf<FlowState?>(null) }
    held = UiRules.heldState(held, raw.shownState)
    // 실제 알람만 남아 울리면(이어받기에서 차의 경보 끝을 못 받음) 닫지 않고 끌 수단이 있는 경보 화면으로 그린다(사용자 결정 2026-11-08). 소리 경보라 단계 2
    val s = if (raw.strandedAlarm) raw.copy(shownState = UiRules.alarmView(held, true, raw.stopped), stage = 2) else raw.copy(shownState = held)
    val alarming = s.shownState in PhoneEngine.ALARM_STATES
    if (alarming) everAlarm = true
    // 이번 경보에 응답(S1R · S2)을 보았는가 — 응답 없이 끝난 경보에 「응답 확인됨」을 보이지 않게(B1 결함 1)
    var responded by remember { mutableStateOf(false) }
    responded = UiRules.alarmResponded(responded, s.shownState) || s.alarmAnswered
    // 이번 사건 id — P14 가 앞 사건의 문자 기록을 섞지 않게(B1 결함 6)
    var incidentId by remember { mutableStateOf<String?>(null) }
    incidentId = UiRules.incidentEventId(incidentId, s.shownState, s.eventId)
    // 뒤로 가기는 응답이 아니다 — 경보 중에는 닫지 않는다
    BackHandler(enabled = alarming) {}

    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())) {
        if (s.flowTest) TestBand()
        when (s.shownState) {
            FlowState.S1, FlowState.S3 -> DrivingAlarm(s)
            FlowState.AP -> ParkedAlarm(s)
            FlowState.S4 -> if (s.stopped) NoResponseParked(s, prefs) else DrivingAlarm(s.copy(shownState = FlowState.S3))
            else -> if (everAlarm && UiRules.resultKind(s.shownState, responded) != ResultKind.CLOSE) Result(s, incidentId, close)
            else LaunchedEffect(Unit) { close() }
        }
    }
    PreviewDialog()
}

/** 단계 글리프 24 — 경보 1 원 · 경보 2 삼각형 · 응답 없음 팔각형, 단계색 채움 + 어두운 「!」. */
@Composable
private fun StageGlyph(stage: AlarmStage, color: Color) {
    Box(Modifier.size(Dims.icon), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(Dims.icon)) {
            val w = size.width
            when (stage) {
                AlarmStage.STAGE1 -> drawCircle(color)
                AlarmStage.STAGE2 -> drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w, w); lineTo(0f, w); close() }, color)
                AlarmStage.NO_RESPONSE -> drawPath(Path().apply {
                    for (i in 0 until 8) {
                        val a = Math.toRadians(22.5 + 45.0 * i)
                        val p = Offset(w / 2 + w / 2 * cos(a).toFloat(), w / 2 + w / 2 * sin(a).toFloat())
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                    close()
                }, color)
            }
        }
        Text("!", color = AlarmColors.bg, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = if (stage == AlarmStage.STAGE2) Modifier.padding(top = 5.dp) else Modifier)
    }
}

/** 단계 띠 56 — alarmSurface 바탕, 왼쪽 막대 8 · 글리프는 규제색, 글자는 흰색(글자에 색을 칠하지 않음). */
@Composable
private fun StageBand(s: EngineStatus) {
    val c = AlarmColors
    val stage = UiRules.alarmStage(s.shownState, s.stage)
    val (color, text) = when (stage) {
        AlarmStage.NO_RESPONSE -> c.stage3 to SmsText.NO_RESPONSE + (s.noResponseSec?.let { " ${Reports.duration(it)}" } ?: "")
        AlarmStage.STAGE2 -> c.stage2 to PhoneTexts.ALARM_STAGE2
        AlarmStage.STAGE1 -> c.stage1 to PhoneTexts.ALARM_STAGE1
    }
    Row(Modifier.fillMaxWidth().height(Dims.stageH).background(c.surface), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(8.dp).fillMaxHeight().background(color))
        Spacer(Modifier.width(Dims.gutter))
        StageGlyph(stage, color)
        Spacer(Modifier.width(12.dp))
        Text(text, color = c.text, fontWeight = FontWeight.Bold, fontSize = Dims.titleS, style = Tnum)
    }
}

/** 경보 화면 버튼 — 모서리 0. [white] 면 흰 채운 버튼(괜찮아요 · 119 작성), 아니면 alarmBasic. */
@Composable
private fun AlarmButton(
    text: String, white: Boolean, height: Dp = Dims.buttonH, fontSize: TextUnit = Dims.titleS, icon: Int? = null,
    modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit,
) {
    val c = AlarmColors
    val (bg, fg) = if (white) c.button to c.onButton else c.basic to c.onBasic
    Row(
        modifier.heightIn(min = height).background(bg).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Dims.gutter),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(painterResource(it), null, Modifier.size(Dims.icon), tint = fg); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AlarmLine() = HLine(AlarmColors.line)

/** P10 · P10' — 주행 중. 버튼 없음, 버튼 자리 72 는 비워 둔다(G5). */
@Composable
private fun DrivingAlarm(s: EngineStatus) {
    val c = AlarmColors
    StageBand(s)
    Column(Modifier.padding(Dims.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val (big, second) = if (s.shownState == FlowState.S3) split(Phrase.SCREEN_S3) else split(Phrase.SCREEN_S1)
        Text(big, color = c.text, fontSize = Dims.headlineL, fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = 48.sp)
        Text(second, color = c.text2, fontSize = Dims.titleL, maxLines = 2)
        if (s.shownState == FlowState.S3) Text(split(Phrase.SCREEN_S1).second, color = c.text2, fontSize = Dims.titleS)
        AlarmLine()
        Text(PhoneTexts.PHONE_HANDS_OFF, color = c.text2, fontSize = Dims.titleS)
        if (UiRules.showCarButtonHint(s.strandedAlarm)) Text(PhoneTexts.CAR_BUTTON_TOO, color = c.text2, fontSize = Dims.titleS)
        s.noResponseSec?.let { Text("${SmsText.NO_RESPONSE} ${Reports.duration(it)}", color = c.text2, fontSize = Dims.titleS, style = Tnum) }
        Spacer(Modifier.height(Dims.okButtonH))
    }
}

/** 「괜찮아요」(P11) — 흰 채운 각진 버튼. 휴대폰이 주인(또는 휴대폰 안 시험)이면 상태 기계로, 차가 주인이면 누른 시각을 sync responseAt 으로 차에 보낸다. */
@Composable
private fun OkButton() {
    AlarmButton(Phrase.BUTTON_OK.ko, white = true, height = Dims.okButtonH, fontSize = Dims.titleL) {
        GuardianService.withEngine { it.phoneResponse(SystemClock.elapsedRealtime()) }
    }
}

/** P11 — 정차 중 경보(AP). 정지일 때만 「괜찮아요」 하나. */
@Composable
private fun ParkedAlarm(s: EngineStatus) {
    val c = AlarmColors
    StageBand(s)
    Column(Modifier.padding(Dims.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val (big, second) = split(Phrase.SCREEN_AP)
        Text(big, color = c.text, fontSize = Dims.headlineL, fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = 48.sp)
        Text(second, color = c.text2, fontSize = Dims.titleL, maxLines = 2)
        s.noResponseSec?.let { Text("${SmsText.NO_RESPONSE} ${Reports.duration(it)}", color = c.text2, fontSize = Dims.titleS, style = Tnum) }
        if (s.responsePending) Text(PhoneTexts.RESPONSE_SENT, color = c.text2, fontSize = Dims.titleS)
        if (s.stopped) OkButton() else Spacer(Modifier.height(Dims.okButtonH))
    }
}

/** P12 · P13 — 정차 뒤 무응답(S4). 119 작성 화면은 사람이 눌러야 열린다(G4). 흰 채운 버튼은 119 작성 · 괜찮아요 둘(PM 허용). */
@Composable
private fun NoResponseParked(s: EngineStatus, prefs: Prefs) {
    val c = AlarmColors
    val body by UiState.noResponse
    val openedAt by UiState.composeOpenedAt
    val log by UiState.smsLog
    val pending by UiState.pendingSms
    var confirm by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val reg = prefs.registration
    val ctx = LocalContext.current

    StageBand(s)
    Column(Modifier.padding(Dims.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s.stoppedSinceWall?.let { Text(SmsText.STOPPED_SINCE.format(hm(it)), color = c.text2, fontSize = Dims.bodyL) }
        AlarmLine()
        // 보호자
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(PhoneTexts.GUARDIAN_TITLE, color = c.text3, fontSize = Dims.labelM)
            Text("${reg.guardianName} · ${reg.guardianPhone}", color = c.text, fontSize = Dims.titleS, fontWeight = FontWeight.Bold)
            UiRules.incidentSmsLog(log, s.eventId).lastOrNull { it.kind == "EVENT" }?.let {
                Row {
                    Text(smsMark(it.sent), color = if (it.sent == true) c.text2 else c.lost, fontSize = Dims.bodyL, modifier = Modifier.width(24.dp))
                    Text(
                        when (it.sent) {
                            true -> PhoneTexts.GUARDIAN_SENT.format(hm(it.atWall))
                            false -> PhoneTexts.GUARDIAN_FAILED
                            null -> PhoneTexts.GUARDIAN_UNCONFIRMED
                        },
                        color = c.text2, fontSize = Dims.bodyL,
                    )
                }
            }
        }
        AlarmLine()
        // 테스트 문자 확인(P17) — 홈 창 · 알림과 같은 확인을 이 화면에서도 답한다(B1 결함 4)
        UiRules.alarmSmsAsk(pending, s.flowTest)?.let { p ->
            Text(PhoneTexts.TEST_SMS_ASK, color = c.text, fontSize = Dims.titleS, fontWeight = FontWeight.Bold)
            Text(p.body, color = c.text, fontSize = Dims.bodyS, fontFamily = FontFamily.Monospace, maxLines = 3,
                modifier = Modifier.fillMaxWidth().background(c.surface).padding(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AlarmButton(PhoneTexts.TEST_SMS_SEND, white = true, modifier = Modifier.weight(1f)) { answerTestSms(p.key, true) }
                AlarmButton(PhoneTexts.TEST_SMS_SKIP, white = false, modifier = Modifier.weight(1f)) { answerTestSms(p.key, false) }
            }
            AlarmLine()
        }
        body?.let {
            openedAt?.let { Text(PhoneTexts.COMPOSE_OPENED.format(hm(it)), color = c.text2, fontSize = Dims.bodyL) }
            AlarmButton(
                if (openedAt == null) PhoneTexts.COMPOSE_119 else PhoneTexts.COMPOSE_119_REOPEN, white = true,
                height = Dims.composeButtonH, fontSize = Dims.titleM, icon = R.drawable.ic_chat,
            ) { GuardianService.withEngine { it.openCompose(SystemClock.elapsedRealtime()) } }
            Text(PhoneTexts.COMPOSE_119_HINT, color = c.text2, fontSize = Dims.bodyS)
        }
        if (UiRules.showOpen119App(s.flowTest)) AlarmButton(PhoneTexts.OPEN_119_APP, white = false) { Emergency119.openReportApp(ctx) }
        // 보내지 못한 보호자 문자 — 정지일 때만 바로 다시 보낸다(위임 결정 3, G5). 휴대폰은 그사이 60초마다 스스로 다시 보낸다
        if (UiRules.showResendSms(s.canResendSms, s.stopped)) AlarmButton(PhoneTexts.RESEND_GUARDIAN_SMS, white = false) {
            GuardianService.withEngine { it.resendGuardianSms(SystemClock.elapsedRealtime()) }
        }
        body?.let { b ->
            Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Row(Modifier.heightIn(min = Dims.touch), verticalAlignment = Alignment.CenterVertically) {
                    Text(PhoneTexts.PREVIEW_HEAD, color = c.text3, fontSize = Dims.labelM, modifier = Modifier.weight(1f))
                    if (!expanded) Text(PhoneTexts.EXPAND, color = c.text, fontSize = Dims.bodyS)
                }
                Text(b.full, color = c.text, fontSize = Dims.bodyS, fontFamily = FontFamily.Monospace, maxLines = if (expanded) Int.MAX_VALUE else 3,
                    modifier = Modifier.fillMaxWidth().background(c.surface).padding(12.dp))
            }
        }
        if (s.responsePending) Text(PhoneTexts.RESPONSE_SENT, color = c.text2, fontSize = Dims.titleS)
        // 119 작성과 괜찮아요 사이를 1px 선 + 32 간격으로 뗀다(spec 5-6)
        AlarmLine()
        Spacer(Modifier.height(20.dp))
        AlarmButton(Phrase.BUTTON_OK.ko, white = true) { confirm = true }
        Text(Phrase.NOTICE_LIMITS.ko, color = c.text2, fontSize = Dims.bodyS)
    }
    // P12' — 운전자가 아닌 사람이 누르면 취소 문자가 가므로 한 번 묻는다
    if (confirm) PleosDialog(
        onDismiss = { confirm = false },
        container = c.surface,
        text = { Text(PhoneTexts.OK_CONFIRM, color = c.text, fontSize = Dims.bodyL) },
        confirm = {
            AlarmButton(Phrase.BUTTON_OK.ko, white = true, modifier = Modifier) {
                confirm = false
                GuardianService.withEngine { it.phoneResponse(SystemClock.elapsedRealtime()) }
            }
        },
        dismiss = { AlarmButton(PhoneTexts.BACK, white = false, modifier = Modifier) { confirm = false } },
    )
}

/** P14 — 응답 결과(S1R · S2, 또는 응답을 본 뒤 테스트가 끝난 S0) · 에어백 전개(S5). 경보와 같은 어두운 배색. 주행 중이면 「닫기」 없이 5초 뒤 닫는다. */
@Composable
private fun Result(s: EngineStatus, incidentId: String?, close: () -> Unit) {
    val c = AlarmColors
    val all by UiState.smsLog
    val log = UiRules.incidentSmsLog(all, incidentId)
    Column(Modifier.padding(Dims.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (s.shownState) {
            FlowState.S5 -> Text(Phrase.SCREEN_S5.ko, color = c.text, fontSize = Dims.headlineS, fontWeight = FontWeight.Bold)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(c.ok, CircleShape), contentAlignment = Alignment.Center) {
                        Text("✓", color = Color.White, fontSize = Dims.titleL, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(Phrase.SCREEN_S1R.ko, color = c.text, fontSize = Dims.headlineS, fontWeight = FontWeight.Bold)
                }
                if (s.stopped) Text(Phrase.SCREEN_S2.ko, color = c.text2, fontSize = Dims.titleS)
            }
        }
        if (log.isNotEmpty()) {
            AlarmLine()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(PhoneTexts.RESULT_HEAD, color = c.text3, fontSize = Dims.labelM)
                for (i in log) {
                    val t = hm(i.atWall)
                    if (i.sent != true) Row {
                        Text(smsMark(i.sent), color = c.lost, fontSize = Dims.bodyL, modifier = Modifier.width(24.dp))
                        Text(if (i.sent == false) PhoneTexts.GUARDIAN_FAILED else PhoneTexts.GUARDIAN_UNCONFIRMED, color = c.text, fontSize = Dims.bodyL)
                    } else Text(
                        if (i.kind == "CANCEL") PhoneTexts.RESULT_CANCEL_SENT.format(t) else PhoneTexts.RESULT_INCIDENT_SENT.format(t),
                        color = c.text, fontSize = Dims.bodyL,
                    )
                    if (i.sent == true && i.kind != "CANCEL") Text(PhoneTexts.LOCATION_GIVEN.format(t), color = c.text2, fontSize = Dims.bodyS)
                    if (i.sent == null) Text(PhoneTexts.LOCATION_MAYBE_GIVEN, color = c.text2, fontSize = Dims.bodyS)
                }
            }
            AlarmLine()
        }
        // 상담 권유 — 문자 기록 · 「닫기」보다 눈에 띄지 않게 작은 보조색 글씨 한 줄(S1R · S2 · 정차 중에만, UiRules)
        if (UiRules.showConsultLine(s.shownState, s.stopped)) Text(PhoneTexts.RESULT_CONSULT, color = c.text2, fontSize = Dims.bodyS)
        if (s.stopped) AlarmButton(PhoneTexts.CLOSE, white = false, onClick = close)
        else LaunchedEffect(Unit) { delay(5_000); close() }
    }
}

/** P18 — 119 미리보기. 보내기 · 복사 · 문자 앱 열기 없음(G4). 탭은 글자 + 밑줄 3. */
@Composable
fun PreviewDialog() {
    val preview by UiState.preview119
    val b = preview ?: return
    val c = LocalPleosColors.current
    var tab by remember(b) { mutableStateOf(0) }
    PleosDialog(
        onDismiss = { UiState.preview119.value = null },
        title = {
            Text(PhoneTexts.PREVIEW_ONLY, color = c.testInk, fontSize = Dims.titleS, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().background(c.testTint).padding(horizontal = 12.dp, vertical = 8.dp))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shown = UiRules.previewTab(tab, b.ultra != null)
                if (b.ultra != null) PleosTabs(listOf(PhoneTexts.TAB_FULL, PhoneTexts.TAB_SHORT), shown) { tab = it }
                val ultra = b.ultra
                val text = if (shown == 1 && ultra != null) ultra else b.full
                Text(text, color = c.text1, fontFamily = FontFamily.Monospace, fontSize = Dims.bodyS,
                    modifier = Modifier.fillMaxWidth().background(c.surface).padding(12.dp))
                Text(
                    if (shown == 1 && ultra != null) PhoneTexts.COUNT_SMS.format(Reports.eucKrBytes(ultra)) else PhoneTexts.COUNT_LMS.format(b.full.length),
                    color = c.text2, fontSize = Dims.bodyS,
                )
            }
        },
        confirm = { PleosButton(PhoneTexts.CLOSE, ButtonKind.Basic, modifier = Modifier, fillWidth = false) { UiState.preview119.value = null } },
    )
}

/** 테스트 문자 확인에 답한다 — 홈 창(MainActivity)과 같은 경로(confirmTestSms). */
private fun answerTestSms(key: String, send: Boolean) {
    UiState.pendingSms.value = UiState.pendingSms.value.filterNot { it.key == key }
    GuardianService.withEngine { it.confirmTestSms(key, send) }
}

/** 문자 기록 앞 표시 — 보냄 ✓ · 보내지 못함 ▲ · 확인 못 함 ?. */
private fun smsMark(sent: Boolean?): String = when (sent) { true -> "✓"; false -> "▲"; null -> "?" }
