// 차 앱 화면(Compose, Pleos 양식) — 머리 막대 · 테스트 띠 · 경보(늘 어두움, 정지 화면만 「괜찮아요」) · 짝짓기 번호 · 정차 두 칸(상태 · 연결 줄 / 워치 칸 · 테스트 입구) · 주행 두 줄
package com.example.guardian.car

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guardian.shared.protocol.TestAction
import com.example.guardian.shared.text.Phrase
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var controller: CarController
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = CarController.get(this)
        setContent {
            val ui = controller.ui
            // 차속 권한(위치 그룹 대화상자)과 알림 권한은 NO_SETUP 제한이 없는 정차 중에 한 번만 묻는다(G5 · CarUxRestrictions)
            // 이유 줄(G-2)을 먼저 보이고 2초 뒤 창을 띄운다. 그사이 주행으로 바뀌면 효과가 취소되어 묻지 않는다
            LaunchedEffect(ui.setupOk, ui.speedGranted) {
                if (ui.setupOk && !ui.speedGranted && !asked) { delay(SPEED_REASON_LEAD_MS); askOnce() }
            }
            // 짝이 생기거나 사건이 열려 서비스가 필요해지면 창이 보이는 지금 켠다(뒤에서는 시작할 수 없다)
            LaunchedEffect(ui.serviceWanted) { if (ui.serviceWanted) GuardianService.start(this@MainActivity) }
            CarApp(
                ui, onOk = controller::pressOk, onNewCode = controller::requestNewCode, onTest = controller::requestTest,
                onUnpair = controller::disconnectPhone,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // 창이 보일 때만 포그라운드 서비스를 켤 수 있다. 짝 없음 + OFF · S0 이면 켜지 않는다(C-7 — 컨트롤러가 정함)
        if (controller.ui.serviceWanted) GuardianService.start(this)
    }

    private fun askOnce() {
        if (asked) return
        asked = true
        val wanted = buildList {
            add(CarSignalsSource.PERMISSION_CAR_SPEED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        controller.ui.speedAsked = true
    }

    private companion object {
        /** 차속 권한 창 앞에 이유 줄을 읽을 시간. */
        const val SPEED_REASON_LEAD_MS = 2_000L
    }
}

/** 두 칸으로 그릴 앱 영역 최소 폭(car-spec 0절 — weather-split 과 같은 문턱). */
private val TWO_PANE_MIN = 1200.dp
private val PAD = 48.dp
private val TNUM = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun CarApp(ui: CarUiState, onOk: () -> Unit, onNewCode: () -> Unit, onTest: (TestAction) -> Unit, onUnpair: () -> Unit) {
    val c = if (isSystemInDarkTheme()) PleosColors.DARK else PleosColors.LIGHT
    var confirmOpen by remember { mutableStateOf(false) }
    var unpairOpen by remember { mutableStateOf(false) }
    // 경보 상태(S1 · AP · S3 · S4 · S5)의 한 줄만 화면을 덮는다. 감시 · 응답 뒤 안내는 상태 한 줄 자리로 간다(CarScreen.noticeLine)
    val alarmLine = CarScreen.alarmLine(ui.flowState, ui.screen)
    val page = CarScreen.page(alarmLine, ui.relayConfigured, ui.paired, ui.pairCode, ui.links, ui.setupOk)
    // 확인 창은 정차 중 상태 화면에서 입구가 열려 있을 때만 — 주행 · 경보 · 입구 막힘으로 바뀌면 바로 걷는다(요청은 보내지 않음).
    // 열림 여부와 따로 「열 수 있음」을 키로 본다 — 누른 프레임에 막혀도 열림 표시가 남아 나중에 저절로 뜨지 않게
    val canConfirm = CarScreen.confirmAllowed(page, ui.idle)
    LaunchedEffect(canConfirm) { if (!canConfirm) confirmOpen = false }
    val confirmVisible = confirmOpen && canConfirm
    // 「휴대폰 연결 끊기」 확인 창도 같다 — 정차 · 번호 화면이고 문(확정 정지 · 사건 없음)이 열려 있을 때만, 닫히면 바로 걷는다(G5)
    val canUnpair = CarScreen.unpairAllowed(page, ui.unpairOk)
    LaunchedEffect(canUnpair) { if (!canUnpair) unpairOpen = false }
    val unpairVisible = unpairOpen && canUnpair

    if (alarmLine != null) {
        AlarmScreen(alarmLine, ui.screen.button, ui.screen.test, onOk, CarScreen.smsLine(ui.smsStatus, alarm = true))
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val wide = maxWidth >= TWO_PANE_MIN
        Column(Modifier.fillMaxSize()) {
            PleosHeader(c)
            if (ui.screen.test) TestBand(c.testInk, c.testTint)
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    CarPage.ALARM, CarPage.RELAY_MISSING -> Centered { StatusLine(CarTexts.RELAY_MISSING, c, TextAlign.Center) }
                    CarPage.PAIRING -> Centered { Pairing(ui, c, canUnpair, onUnpair = { unpairOpen = true }) }
                    CarPage.DRIVING -> Centered { DriveLines(ui.idle, c) }
                    CarPage.PARKED -> Parked(
                        ui, c, wide, onNewCode, onStart = { confirmOpen = true }, onStop = { onTest(TestAction.STOP) },
                        onUnpair = { unpairOpen = true },
                    )
                }
            }
        }
        if (confirmVisible) {
            ConfirmDialog(
                c, CarTexts.TEST_CONFIRM_TITLE, CarTexts.TEST_CONFIRM_BODY, CarTexts.TEST_CONFIRM_SCENARIO, CarTexts.TEST_CONFIRM_OK,
                onCancel = { confirmOpen = false }, onOk = { confirmOpen = false; onTest(TestAction.START) },
            )
        }
        if (unpairVisible) {
            ConfirmDialog(
                c, CarTexts.UNPAIR_CONFIRM_TITLE, CarTexts.UNPAIR_CONFIRM_BODY, CarTexts.UNPAIR_CONFIRM_NOTE, CarTexts.UNPAIR_CONFIRM_OK,
                onCancel = { unpairOpen = false }, onOk = { unpairOpen = false; onUnpair() },
            )
        }
    }
}

// ── 공통 틀 ──

/**
 * 머리 막대 99 — Maju 아이콘 판 56 + 글자 로고 「Maju」 높이 28(text1) + 부제 「운전자 무응답 확인」 Label M 24 text2 한 줄 + 아래 1px 선.
 * 누를 것은 없고 주행 중에도 그대로다(주행 두 줄은 막대 아래 본문이 맡는다). 배치 기준 팀 내부 로고 메모(비공개) 6절(그림은 logo-20261109/maju-final).
 */
@Composable
private fun PleosHeader(c: PleosColors) {
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Row(Modifier.fillMaxWidth().height(99.dp).padding(horizontal = PAD), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo_tile), null, Modifier.size(56.dp))
            Spacer(Modifier.width(24.dp))
            Icon(painterResource(R.drawable.ic_wordmark_maju), CarTexts.BRAND_SPOKEN, Modifier.size(width = 62.69.dp, height = 28.dp), tint = c.text1)
            Spacer(Modifier.width(20.dp))
            Text(CarTexts.APP_TITLE, color = c.text2, fontSize = CarType.LABEL_M.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HLine(c.line)
    }
}

/** 테스트 띠 — 높이 64, 「T」 네모 + 「테스트 · 실제 상황 아님」(우리 고유 청록, 경보 화면에서도 단다). */
@Composable
private fun TestBand(ink: Color, tint: Color) {
    Row(Modifier.fillMaxWidth().height(64.dp).background(tint).padding(horizontal = PAD), verticalAlignment = Alignment.CenterVertically) {
        TMark(ink, 40.dp, 26)
        Spacer(Modifier.width(16.dp))
        Text(CarTexts.TEST_BAND, color = ink, fontSize = CarType.BODY_L.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TMark(color: Color, size: Dp, fontSize: Int) {
    Box(Modifier.size(size).border(3.dp, color), contentAlignment = Alignment.Center) {
        Text("T", color = color, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}

/** 손목시계 선 아이콘(머리 막대 · 워치 칸 머리) — 그림 파일 없이 그린다. */
@Composable
private fun WatchIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = Stroke(width = 2 * u, cap = StrokeCap.Round)
        drawRoundRect(color, Offset(6 * u, 6 * u), Size(12 * u, 12 * u), CornerRadius(3 * u), style = stroke)
        drawLine(color, Offset(9 * u, 6 * u), Offset(9 * u, 3 * u), 2 * u, StrokeCap.Round)
        drawLine(color, Offset(9 * u, 3 * u), Offset(15 * u, 3 * u), 2 * u, StrokeCap.Round)
        drawLine(color, Offset(15 * u, 3 * u), Offset(15 * u, 6 * u), 2 * u, StrokeCap.Round)
        drawLine(color, Offset(9 * u, 18 * u), Offset(9 * u, 21 * u), 2 * u, StrokeCap.Round)
        drawLine(color, Offset(9 * u, 21 * u), Offset(15 * u, 21 * u), 2 * u, StrokeCap.Round)
        drawLine(color, Offset(15 * u, 21 * u), Offset(15 * u, 18 * u), 2 * u, StrokeCap.Round)
        drawLine(color, Offset(10 * u, 12 * u), Offset(11.5f * u, 13.5f * u), 2 * u, StrokeCap.Round)
        drawLine(color, Offset(11.5f * u, 13.5f * u), Offset(14 * u, 11 * u), 2 * u, StrokeCap.Round)
    }
}

@Composable
private fun HLine(color: Color) = Box(Modifier.fillMaxWidth().height(1.dp).background(color))

@Composable
private fun VLine(color: Color) = Box(Modifier.width(1.dp).fillMaxHeight().background(color))

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(PAD),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content,
    )
}

/**
 * 각진 채운 버튼(모서리 0 · 그림자 없음). [enabled] 가 거짓이면 Pleos button_filled_disabled(옅은 바탕 + text4)로 그리고 누를 수 없다.
 * 눌림 물결은 쓰지 않는다(움직임 지양).
 */
@Composable
private fun FlatButton(
    text: String, bg: Color, fg: Color, height: Dp, modifier: Modifier = Modifier, enabled: Boolean = true,
    disabledBg: Color = Color.Transparent, disabledFg: Color = fg, fontSize: Int = CarType.TITLE_S,
    leading: (@Composable (Color) -> Unit)? = null, onClick: () -> Unit,
) {
    val ink = if (enabled) fg else disabledFg
    Row(
        modifier.height(height).background(if (enabled) bg else disabledBg)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        leading?.let { it(ink); Spacer(Modifier.width(16.dp)) }
        Text(text, color = ink, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusLine(text: String, c: PleosColors, align: TextAlign = TextAlign.Start) {
    Text(text, color = c.text1, fontSize = CarType.HEADLINE_S.sp, fontWeight = FontWeight.Bold, textAlign = align, lineHeight = 58.sp)
}

/** 첫 화면 고지(G1 문장 그대로) — Label M 24 · text2, 고지에만 쓰는 최소 크기. */
@Composable
private fun Notices(c: PleosColors, align: TextAlign = TextAlign.Start, airbagUnreadable: Boolean = false) {
    // 상세 안내 — 상태 줄이 아니라 고지 자리에 둔다(F-2, 1~2초 파악)
    if (airbagUnreadable) Text(CarTexts.AIRBAG_UNREADABLE, color = c.text2, fontSize = CarType.LABEL_M.sp, textAlign = align, lineHeight = 36.sp)
    Text(Phrase.NOTICE_LIMITS.ko, color = c.text2, fontSize = CarType.LABEL_M.sp, textAlign = align, lineHeight = 36.sp)
    Text(Phrase.NOTICE_NOT_MEDICAL.ko, color = c.text2, fontSize = CarType.LABEL_M.sp, textAlign = align, lineHeight = 36.sp)
}

// ── 경보(C3) ──

/** 경보 — 앱 영역 전체를 늘 어두운 바탕으로 덮는다(머리 막대 · 연결 줄 · 워치 칸 · 입구 없음). 버튼은 정지 화면이고 정지 중일 때만 실려 온다. */
@Composable
private fun AlarmScreen(line: String, button: String?, test: Boolean, onOk: () -> Unit, smsLine: String? = null) {
    Column(Modifier.fillMaxSize().background(AlarmColors.bg)) {
        if (test) TestBand(AlarmColors.testInk, AlarmColors.testTint)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(
                line, color = AlarmColors.text, fontSize = CarType.HEADLINE_M.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, lineHeight = 68.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            // 휴대폰이 보호자 문자를 보냈다고 확인함(A-5) — 경보 줄 아래 한 줄
            smsLine?.let { Text(it, color = AlarmColors.text, fontSize = CarType.TITLE_S.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp)) }
            if (button != null) {
                Spacer(Modifier.height(48.dp))
                FlatButton(
                    button, AlarmColors.button, AlarmColors.onButton, 128.dp, Modifier.widthIn(min = 480.dp),
                    fontSize = CarType.HEADLINE_S, onClick = onOk,
                )
            }
        }
    }
}

// ── 짝짓기 ──

@Composable
private fun ColumnScope.Pairing(ui: CarUiState, c: PleosColors, canUnpair: Boolean, onUnpair: () -> Unit) {
    val code = ui.pairCode
    // 기다림 로고 96 — 정차 중 기다리는 동안만 돌고, 주행 제한이면 감속 없이 정지 로고(motion.md 5절). 세로 자리가 빠듯해 제목 왼쪽에 둔다
    Row(verticalAlignment = Alignment.CenterVertically) {
        MajuSpinner(
            waiting = CarScreen.pairingWaiting(code, ui.unpairRelayLeft, ui.pairFailed), restricted = !ui.setupOk, size = 96.dp,
        )
        Spacer(Modifier.width(24.dp))
        if (!ui.setupOk) StatusLine(CarTexts.PAIR_DRIVING, c, TextAlign.Center)
        else Text(CarTexts.PAIR_TITLE, color = c.text2, fontSize = CarType.TITLE_M.sp)
    }
    if (!ui.setupOk) return
    Spacer(Modifier.height(24.dp))
    when {
        code != null -> {
            Text(
                code.chunked(3).joinToString(" "), color = c.text1, fontSize = 120.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp, style = TNUM,
            )
            Spacer(Modifier.height(16.dp))
            Text(CarTexts.PAIR_HINT, color = c.text2, fontSize = CarType.BODY_L.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(CarScreen.pairingLine(ui.links), color = c.text1, fontSize = CarType.TITLE_S.sp, textAlign = TextAlign.Center)
        }
        ui.unpairRelayLeft -> Text(CarTexts.UNPAIR_RELAY_LEFT, color = c.text2, fontSize = CarType.TITLE_S.sp, textAlign = TextAlign.Center)
        ui.pairFailed -> Text(CarTexts.PAIR_FAILED, color = c.text2, fontSize = CarType.TITLE_S.sp, textAlign = TextAlign.Center)
        else -> Text(CarTexts.PAIR_WAITING, color = c.text2, fontSize = CarType.TITLE_S.sp)
    }
    if (canUnpair) Unpair(c, onUnpair)
    // 첫 실행은 이 화면에서 차속 권한 창이 뜬다 — 이유 · 결과 줄을 여기에도(G-2)
    CarScreen.speedLine(ui.speedGranted, ui.speedAsked)?.let {
        Spacer(Modifier.height(16.dp))
        Text(it, color = c.text2, fontSize = CarType.BODY_S.sp, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(40.dp))
    Notices(c, TextAlign.Center, ui.airbagUnreadable)
}

// ── 주행(C2) ──

/** 주행 중 — 상태 한 줄(주행 열) + 워치 한 줄(10초 묶음). 누를 것 없음(G5). */
@Composable
private fun DriveLines(view: IdleView, c: PleosColors) {
    StatusLine(view.line, c, TextAlign.Center)
    view.driveBio?.let {
        Spacer(Modifier.height(16.dp))
        Text(it, color = c.text2, fontSize = CarType.BODY_L.sp, textAlign = TextAlign.Center, style = TNUM)
    }
}

// ── 정차(C1) ──

/** 정차 중 — 폭이 넓으면 두 칸(왼쪽 상태 · 연결 줄 / 오른쪽 워치 칸 · 테스트 입구), 좁으면 한 칸으로 쌓고 세로 스크롤. */
@Composable
private fun Parked(
    ui: CarUiState, c: PleosColors, wide: Boolean, onNewCode: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onUnpair: () -> Unit,
) {
    val view = ui.idle
    if (wide) {
        Column(Modifier.fillMaxSize().padding(start = PAD, end = PAD, top = 40.dp, bottom = 40.dp)) {
            Row(Modifier.weight(1f).widthIn(max = 1608.dp)) {
                Column(Modifier.weight(760f).verticalScroll(rememberScrollState())) {
                    StatusBlock(ui, c)
                    Spacer(Modifier.height(40.dp))
                    LinkRows(view.rows, c)
                    NewCode(view, c, onNewCode)
                    if (ui.unpairOk) Unpair(c, onUnpair)
                }
                Spacer(Modifier.width(PAD))
                Column(Modifier.weight(800f).verticalScroll(rememberScrollState())) {
                    view.bio?.let { BioCard(it, c) }
                    view.test?.let { TestEntry(it, c, onStart, onStop) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Notices(c, airbagUnreadable = ui.airbagUnreadable)
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PAD, vertical = 40.dp)) {
            StatusBlock(ui, c)
            Spacer(Modifier.height(32.dp))
            view.bio?.let { BioCard(it, c) }
            view.test?.let { TestEntry(it, c, onStart, onStop) }
            Spacer(Modifier.height(40.dp))
            LinkRows(view.rows, c)
            NewCode(view, c, onNewCode)
            if (ui.unpairOk) Unpair(c, onUnpair)
            Spacer(Modifier.height(40.dp))
            Notices(c, airbagUnreadable = ui.airbagUnreadable)
        }
    }
}

@Composable
private fun StatusBlock(ui: CarUiState, c: PleosColors) {
    StatusLine(ui.idle.line, c)
    CarScreen.speedLine(ui.speedGranted, ui.speedAsked)?.let {
        Text(it, color = c.text2, fontSize = CarType.BODY_S.sp, modifier = Modifier.padding(top = 8.dp))
    }
    CarScreen.smsLine(ui.smsStatus, alarm = false)?.let {
        Text(it, color = c.text1, fontSize = CarType.BODY_L.sp, modifier = Modifier.padding(top = 8.dp))
    }
    ui.idle.sub?.let { Text(it, color = c.text2, fontSize = CarType.BODY_S.sp, modifier = Modifier.padding(top = 8.dp)) }
    if (!ui.clockVerified) Text(CarTexts.STATUS_CLOCK_UNVERIFIED, color = c.text3, fontSize = CarType.BODY_S.sp, modifier = Modifier.padding(top = 8.dp))
}

/**
 * 연결 줄 — 한 덩어리, 왼쪽 맞춤, 열 고정(글리프 40 · 이름 160 · 상태 나머지), 줄 높이 88, 줄 사이 1px 선(car-spec 3-2).
 * 글리프 · 이름 · 상태는 같은 기준선에 둔다. 상태 글자 · 점은 늘 surface 위에(대비).
 */
@Composable
private fun LinkRows(rows: List<LinkRowView>, c: PleosColors) {
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        HLine(c.line)
        Text(CarTexts.LINK_HEAD, color = c.text3, fontSize = CarType.BODY_S.sp, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp))
        for (row in rows) {
            Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (row.ok) "●" else "▲", color = if (row.ok) c.ok else c.lost, fontSize = 28.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.width(40.dp).alignByBaseline(),
                )
                Text(
                    row.name, color = c.text1, fontSize = CarType.TITLE_S.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(160.dp).padding(start = 12.dp).alignByBaseline(),
                )
                Text(row.detail, color = c.text2, fontSize = CarType.BODY_L.sp, modifier = Modifier.weight(1f).alignByBaseline())
            }
            HLine(c.line)
        }
    }
}

/** 「새 연결 번호」(button_basic 높이 80) 또는 받은 번호. */
@Composable
private fun NewCode(view: IdleView, c: PleosColors, onNewCode: () -> Unit) {
    view.code?.let { code ->
        Spacer(Modifier.height(32.dp))
        Text(
            "${CarTexts.NEW_CODE}  ${code.chunked(3).joinToString(" ")}", color = c.text1, fontSize = 40.sp,
            fontWeight = FontWeight.Bold, style = TNUM,
        )
    }
    if (view.newCode) {
        Spacer(Modifier.height(32.dp))
        FlatButton("↻ ${CarTexts.NEW_CODE}", c.basic, c.text1, 80.dp, onClick = onNewCode)
    }
}

/** 「휴대폰 연결 끊기」(button_basic 높이 80) — 확정 정지 · 사건 없음일 때만 그린다. 누르면 확인 창. */
@Composable
private fun Unpair(c: PleosColors, onUnpair: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    FlatButton(CarTexts.UNPAIR, c.basic, c.text1, 80.dp, onClick = onUnpair)
}

/**
 * 워치 칸 — surface 바탕 2×2 격자(심박 · 맥박 신호 / 착용 · 마지막 갱신), 칸 사이 1px 선, 아이콘 없이 라벨 + 값.
 * 생체 값은 text1 하나로만 그리고 상태색 · 경보색을 쓰지 않는다(G1). 숫자는 보간 · 깜빡임 없이 제자리에서 바뀐다.
 */
@Composable
private fun BioCard(bio: BioView, c: PleosColors) {
    Column(Modifier.fillMaxWidth().background(c.surface).border(1.dp, c.line)) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            WatchIcon(c.text1, 40.dp)
            Spacer(Modifier.width(16.dp))
            Text(CarTexts.BIO_TITLE, color = c.text1, fontSize = CarType.TITLE_S.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (bio.testTag) {
                Text(
                    CarTexts.BIO_TEST_VALUE, color = c.testInk, fontSize = CarType.BODY_S.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(c.testTint).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        HLine(c.line)
        if (bio.noSignal) {
            Text(
                CarTexts.BIO_NO_SIGNAL, color = c.text1, fontSize = CarType.TITLE_M.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 40.dp),
            )
            HLine(c.line)
        }
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            BioCell(CarTexts.BIO_HR, c, Modifier.weight(1f)) {
                if (bio.hr != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(bio.hr.toString(), color = c.text1, fontSize = CarType.HEADLINE_L.sp, fontWeight = FontWeight.ExtraBold, style = TNUM, modifier = Modifier.alignByBaseline())
                        Spacer(Modifier.width(8.dp))
                        Text(CarTexts.BIO_HR_UNIT, color = c.text2, fontSize = CarType.BODY_L.sp, modifier = Modifier.alignByBaseline())
                    }
                } else MutedValue(bio.hrText, c)
            }
            VLine(c.line)
            BioCell(CarTexts.BIO_PULSE, c, Modifier.weight(1f)) {
                val g = bio.pulse.glyph
                if (g != null) CellValue("$g ${bio.pulse.text}", c) else MutedValue(bio.pulse.text, c)
            }
        }
        HLine(c.line)
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            BioCell(CarTexts.BIO_WORN, c, Modifier.weight(1f)) { if (bio.noSignal) MutedValue(bio.wornText, c) else CellValue(bio.wornText, c) }
            VLine(c.line)
            BioCell(CarTexts.BIO_UPDATED, c, Modifier.weight(1f)) { if (bio.noSignal) MutedValue(bio.updated, c) else CellValue(bio.updated, c) }
        }
    }
}

@Composable
private fun BioCell(label: String, c: PleosColors, modifier: Modifier, value: @Composable () -> Unit) {
    Column(modifier.fillMaxHeight().padding(horizontal = 32.dp, vertical = 28.dp)) {
        Text(label, color = c.text3, fontSize = CarType.BODY_S.sp)
        Spacer(Modifier.height(12.dp))
        value()
    }
}

@Composable
private fun CellValue(text: String, c: PleosColors) = Text(text, color = c.text1, fontSize = CarType.TITLE_M.sp, style = TNUM)

@Composable
private fun MutedValue(text: String, c: PleosColors) = Text(text, color = c.text2, fontSize = CarType.BODY_L.sp)

/** 테스트 입구 — 워치 칸 바로 아래, 칸 폭 전체, 높이 96 inverse 채운 버튼. 막히면 흐림 + 아래 사유 한 줄. */
@Composable
private fun TestEntry(view: TestEntryView, c: PleosColors, onStart: () -> Unit, onStop: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    FlatButton(
        view.label, c.inverse, c.onInverse, 96.dp, Modifier.fillMaxWidth(), enabled = view.enabled,
        disabledBg = c.disabled, disabledFg = c.text4, leading = { ink -> TMark(ink, 32.dp, 22) },
        onClick = if (view.action == TestAction.START) onStart else onStop,
    )
    view.reason?.let { Text(it, color = c.text2, fontSize = CarType.BODY_S.sp, modifier = Modifier.padding(top = 12.dp)) }
}

/** 확인 창(테스트 시작 C1' · 휴대폰 연결 끊기) — 폭 880 · 모서리 0, 뒤를 64% 로 덮는다. 바깥을 눌러도 닫지 않는다(「취소」로만). */
@Composable
private fun ConfirmDialog(c: PleosColors, title: String, body: String, note: String, okLabel: String, onCancel: () -> Unit, onOk: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(AlarmColors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(880.dp).background(c.popup)) {
            Column(Modifier.padding(40.dp)) {
                Text(title, color = c.text1, fontSize = CarType.TITLE_M.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Text(body, color = c.text2, fontSize = CarType.BODY_L.sp, lineHeight = 42.sp)
                Spacer(Modifier.height(12.dp))
                Text(note, color = c.text3, fontSize = CarType.BODY_S.sp)
            }
            HLine(c.line)
            Row(Modifier.fillMaxWidth()) {
                FlatButton(CarTexts.TEST_CONFIRM_CANCEL, c.basic, c.text1, 96.dp, Modifier.weight(1f), onClick = onCancel)
                FlatButton(okLabel, c.inverse, c.onInverse, 96.dp, Modifier.weight(1f), onClick = onOk)
            }
        }
    }
}
