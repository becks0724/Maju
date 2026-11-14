// 워치 화면(Wear Compose M3, spec §5-9 · §8-2) — W01~W03 코드 · W04 켜짐/꺼짐 · W05 착용 확인 · W06/W07/W09 경보 · W08 응답 확인됨 · W10 테스트 · W11 상태
package com.example.guardian.watch.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.PickerGroup
import androidx.wear.compose.material3.PickerState
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.example.guardian.shared.text.Phrase
import com.example.guardian.shared.text.SmsText
import com.example.guardian.watch.service.WatchHub
import com.example.guardian.watch.text.WatchText
import com.example.guardian.watch.text.WatchTexts
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun rememberHubState(): State<WatchHub.UiState> {
    val s = remember { mutableStateOf(WatchHub.state) }
    DisposableEffect(Unit) {
        val stop = WatchHub.listen { s.value = it }
        onDispose { stop() }
    }
    return s
}

/** 단조 시각(ms) — 고리 · 남은 초를 0.5초마다 다시 그린다. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    return now
}

// ── 모양 · 공통 ──

private val Triangle: Shape = GenericShape { s, _ ->
    moveTo(s.width / 2, 0f); lineTo(s.width, s.height); lineTo(0f, s.height); close()
}

private val Octagon: Shape = GenericShape { s, _ ->
    val r = s.minDimension / 2
    for (i in 0 until 8) {
        val a = Math.PI / 8 + i * Math.PI / 4
        val x = (s.width / 2 + r * cos(a)).toFloat(); val y = (s.height / 2 + r * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** 단계 글리프 — 색 + 모양 + 글자(spec §7-3). 경보 「!」는 검정 글자, 응답 확인됨 「✓」는 흰 글자. */
@Composable
private fun Glyph(shape: Shape, color: Color, mark: String, size: Dp, markColor: Color = WatchColors.OnFilled) {
    Box(Modifier.size(size).clip(shape).background(color), contentAlignment = Alignment.BottomCenter) {
        Text(mark, color = markColor, fontSize = (size.value * 0.6f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Line(text: String, size: Int, color: Color = WatchColors.Fg, weight: FontWeight = FontWeight.Normal, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text, color = color, fontSize = size.sp, fontWeight = weight, textAlign = TextAlign.Center,
        maxLines = maxLines, modifier = Modifier.fillMaxWidth(),
    )
}

/** 위 곡선 띠 대신 위쪽 알약 띠(높이 28) — spec §5-10 은 알약으로 확정, 폭 0.5d 이하는 [Round] 가 지킨다. */
@Composable
private fun TestBand() {
    Box(
        Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(WatchColors.TestTint).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(WatchText.TEST.ko, color = WatchColors.TestInk, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

/**
 * 원형 화면 틀(spec §5-10 가장자리 규칙, d = 지름) — 가운데 글자 칸 0.8d(좌우 0.1d), 맨 위 요소는 0.08d 아래 · 폭 0.5d 이하,
 * 맨 위 요소가 없으면 위 여백 0.1d, 아래 가장자리 버튼과 내용 사이 8 이상. 바탕은 늘 검정이고 클릭을 두지 않는다(화면 전체 탭은 응답이 아님).
 */
@Composable
private fun Round(
    top: (@Composable () -> Unit)? = null,
    edge: (@Composable () -> Unit)? = null,
    edgeHeight: Dp = 56.dp,
    overlay: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(WatchColors.Bg)) {
        val side = maxWidth * 0.10f
        val topPad = maxHeight * (if (top != null) 0.08f else 0.10f)
        val topMax = maxWidth * 0.5f
        overlay?.invoke()
        Column(
            Modifier.fillMaxSize().padding(start = side, end = side, top = topPad, bottom = if (edge != null) edgeHeight + 8.dp else topPad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (top != null) Box(Modifier.widthIn(max = topMax)) { top() }
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) { content() }
        }
        if (edge != null) Box(Modifier.align(Alignment.BottomCenter)) { edge() }
    }
}

/** 가장자리 버튼 — 평소 Pleos 채운 버튼(#E0E1E6), 경보 「괜찮아요」는 흰 채움(spec §8-2). 글자 Headline S 20 Bold. */
@Composable
private fun Edge(label: String, size: EdgeButtonSize, onClick: () -> Unit, alarm: Boolean = false) {
    EdgeButton(
        onClick = onClick,
        buttonSize = size,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (alarm) WatchColors.AlarmButton else WatchColors.Filled,
            contentColor = WatchColors.OnFilled,
        ),
    ) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}

// ── W06 · W07 · W09 경보, W08 응답 확인됨 ──

/**
 * 경보 — 배지 · 한 줄 · 가장자리 버튼 「괜찮아요」(Medium 70) 하나. 단계 시간은 테두리 고리(durationMs 표시용 —
 * 다 차도 경보는 ALERT_STOP 까지 계속, 고리는 가득 찬 채). 바탕은 단계와 상관없이 검정이고, 단계는 고리 색 + 글리프 모양 + 배지 글자로
 * 나눈다(spec §5-9 [11-01 Pleos 양식]). 경보 2 창 밝기는 액티비티가 1f 로.
 */
@Composable
fun AlertScreen(alert: WatchHub.AlertView, now: Long, onRespond: () -> Unit) {
    val noResponse = WatchTexts.isNoResponse(alert.stage)
    val (color, shape) = when {
        noResponse -> WatchColors.Stage3 to Octagon
        alert.level >= 2 -> WatchColors.Stage2 to Triangle
        else -> WatchColors.Stage1 to CircleShape
    }
    val progress = ((now - alert.startedAtMs).toFloat() / alert.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    Round(
        top = if (alert.test) ({ TestBand() }) else null,
        edge = { Edge(Phrase.BUTTON_OK.ko, EdgeButtonSize.Medium, alarm = true, onClick = onRespond) },
        edgeHeight = 70.dp,
        overlay = {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize().padding(2.dp),
                colors = ProgressIndicatorDefaults.colors(indicatorColor = color, trackColor = WatchColors.Hair),
                strokeWidth = 4.dp,
            )
        },
    ) {
        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(shape, color, "!", 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(WatchTexts.alertBadge(alert.stage, alert.level), color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Line(WatchTexts.alertLine(alert.stage), 22, weight = FontWeight.Bold, maxLines = 2)
        if (alert.responded) Line(WatchText.RESPONDED_WAITING.ko, 13, WatchColors.Fg2)
    }
}

@Composable
fun RespondedScreen() = Round {
    Glyph(CircleShape, WatchColors.Ok, "✓", 40.dp, WatchColors.Fg)
    Line(WatchTexts.head(Phrase.SCREEN_S1R), 20, weight = FontWeight.Bold)
    Line(WatchTexts.tail(Phrase.SCREEN_S1R), 20, weight = FontWeight.Bold)
}

// ── W04 · W05 · W10 · W11 ──

/** 연결 한 줄의 글리프(spec §7-3) — 연결됨 ● `Ok` · 끊김 · 연결 안 됨 ▲ `Lost` · 확인 중 ○ `Fg3`. 글자는 늘 `Fg2`. */
enum class LinkMark { OK, LOST, CHECKING }

/**
 * W04 · W00 — 원 32(켜짐 `Ok` 채움 · 꺼짐 `Fg3` 테두리 3) + 「켜짐 · 꺼짐」 + 연결 한 줄(글리프만 색) + 「상태 보기」 흰 글자 버튼.
 * [notice] 는 연결 줄 아래 `Fg2` 13 한 줄(신호 없음 — 상태색 · 글리프 없음, G1).
 */
@Composable
fun StatusScreen(
    ui: WatchHub.UiState, linkMark: LinkMark, linkLine: String, edgeLabel: String, onEdge: () -> Unit, onStatus: (() -> Unit)?,
    notice: String? = null,
) =
    Round(edge = { Edge(edgeLabel, EdgeButtonSize.Small, onEdge) }) {
        if (ui.monitoring) Box(Modifier.size(32.dp).clip(CircleShape).background(WatchColors.Ok))
        else Box(Modifier.size(32.dp).border(3.dp, WatchColors.Fg3, CircleShape))
        Line(if (ui.monitoring) WatchText.HOME_ON.ko else WatchText.HOME_OFF.ko, 22, weight = FontWeight.Bold)
        val (mark, markColor) = when (linkMark) {
            LinkMark.OK -> "●" to WatchColors.Ok
            LinkMark.LOST -> "▲" to WatchColors.Lost
            LinkMark.CHECKING -> "○" to WatchColors.Fg3
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = markColor)) { append(mark) }
                append(" ")
                append(linkLine)
            },
            color = WatchColors.Fg2, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        if (notice != null) Line(notice, 13, WatchColors.Fg2, maxLines = 2)
        if (onStatus != null) {
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onStatus).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(WatchText.STATUS.ko, color = WatchColors.Fg, fontSize = 13.sp, textDecoration = TextDecoration.Underline) }
        }
    }

/** 손목시계 선 아이콘(24 격자 · 선 굵기 2, spec §7-6) — 늘 글자와 함께 쓴다. */
@Composable
private fun WatchIcon(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = 2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, topLeft = Offset(6 * u, 6 * u), size = Size(12 * u, 12 * u), cornerRadius = CornerRadius(3 * u), style = stroke)
        val lines = Path().apply {
            moveTo(9 * u, 6 * u); lineTo(9.5f * u, 2 * u); lineTo(14.5f * u, 2 * u); lineTo(15 * u, 6 * u) // 위 줄
            moveTo(9 * u, 18 * u); lineTo(9.5f * u, 22 * u); lineTo(14.5f * u, 22 * u); lineTo(15 * u, 18 * u) // 아래 줄
            moveTo(12 * u, 9.5f * u); lineTo(12 * u, 12 * u); lineTo(13.5f * u, 13.5f * u) // 바늘
        }
        drawPath(lines, color, style = stroke)
    }
}

/** W05 — 손목시계 선 아이콘 40(`Fg`) + 「착용 확인」 + 힌트 + 가장자리 「상태 보기」(끄기 · 동의 철회로 가는 출구, 코드 리뷰 11-02 · 11-08 하). */
@Composable
fun CheckWearScreen(onStatus: () -> Unit) = Round(edge = { Edge(WatchText.STATUS.ko, EdgeButtonSize.Small, onStatus) }) {
    WatchIcon(40.dp, WatchColors.Fg)
    Line(Phrase.SCREEN_CHECK_WEAR.ko, 17, weight = FontWeight.Bold)
    Line(WatchText.WEAR_HINT.ko, 13, WatchColors.Fg2)
}

@Composable
fun TestRunningScreen(test: WatchHub.TestView, now: Long, onStop: () -> Unit) =
    Round(top = { TestBand() }, edge = { Edge(WatchText.STOP.ko, EdgeButtonSize.Small, onStop) }) {
        Line(WatchTexts.scenario(test.scenario), 16, weight = FontWeight.Bold, maxLines = 2)
        Line(SmsText.TEST_NOTICE, 13, WatchColors.Fg2)
        Line(WatchText.REMAIN.ko.format(((test.endsAtMs - now).coerceAtLeast(0) + 999) / 1000), 13, WatchColors.Fg2)
    }

/**
 * 세로 목록 틀(spec §5-10) — `ScreenScaffold` + `TransformingLazyColumn`. 좌우 0.1d(가운데 0.8d) · 첫 줄 위 0.1d · 줄 사이 4,
 * 위 · 아래 줄은 화면 곡선에서 줄어든다. 크라운 · 베젤 회전 스크롤은 목록 기본 동작.
 */
@Composable
private fun RoundList(content: TransformingLazyColumnScope.(TransformationSpec) -> Unit) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    BoxWithConstraints(Modifier.fillMaxSize().background(WatchColors.Bg)) {
        val padding = PaddingValues(horizontal = maxWidth * 0.10f, vertical = maxHeight * 0.10f)
        ScreenScaffold(scrollState = state, contentPadding = padding) { contentPadding ->
            TransformingLazyColumn(
                state = state,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content(spec) }
        }
    }
}

/** 목록 항목의 높이 · 크기 · 투명도를 화면 위 · 아래 곡선에 맞춰 줄인다(버튼이 아닌 줄 · 글자). */
private fun Modifier.morph(scope: TransformingLazyColumnItemScope, spec: TransformationSpec): Modifier =
    transformedHeight(scope, spec).graphicsLayer { with(scope) { with(spec) { applyContainerTransformation(scrollProgress) } } }

/** 목록 끝 알약 버튼(높이 52) — `Filled`(동의) 또는 `Basic`(끄기 · 동의 철회). */
@Composable
private fun TransformingLazyColumnItemScope.ListButton(label: String, spec: TransformationSpec, container: Color, content: Color, onClick: () -> Unit) =
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).transformedHeight(this, spec),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        transformation = SurfaceTransformation(spec),
    ) { Text(label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }

/** W11 상태 — 세로 목록(줄 52) + 조작 버튼 + G1 고지(설정 화면 고지). 심박 · 맥박 신호 상태는 보이지 않고, 측정 값에도 상태색을 쓰지 않는다. */
@Composable
fun SignalStatusScreen(
    rows: List<Pair<String, String>>,
    lost: String?,
    actions: List<Pair<String, () -> Unit>>,
) = RoundList { spec ->
    item { Box(Modifier.morph(this, spec)) { Line(WatchText.STATUS_HEAD.ko, 16, weight = FontWeight.Bold) } }
    if (lost != null) item {
        Row(Modifier.morph(this, spec), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Triangle, WatchColors.Lost, "!", 18.dp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(lost.substringBefore(" · "), color = WatchColors.Fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(lost.substringAfter(" · "), color = WatchColors.Fg2, fontSize = 12.sp)
            }
        }
    }
    items(rows) { (k, v) ->
        Column(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).morph(this, spec).clip(RoundedCornerShape(26.dp)).background(WatchColors.Surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(k, color = WatchColors.Fg3, fontSize = 12.sp)
            Text(v, color = WatchColors.Fg, fontSize = 15.sp)
        }
    }
    items(actions) { (label, onClick) -> ListButton(label, spec, WatchColors.Basic, WatchColors.Fg, onClick) }
    item { Box(Modifier.morph(this, spec)) { Line(Phrase.NOTICE_LIMITS.ko, 12, WatchColors.Fg2) } }
    item { Box(Modifier.morph(this, spec)) { Line(Phrase.NOTICE_NOT_MEDICAL.ko, 12, WatchColors.Fg2) } }
}

// ── 동의 · 코드 ──

/** W12 — 세로 목록, 「동의」(`Filled` 알약 52)는 목록 끝에만 있어 끝까지 읽어야 닿는다. */
@Composable
fun ConsentScreen(onAgree: () -> Unit) = RoundList { spec ->
    item { Box(Modifier.morph(this, spec)) { Line(WatchText.CONSENT_TITLE.ko, 16, weight = FontWeight.Bold) } }
    item { Box(Modifier.morph(this, spec)) { Line(WatchText.CONSENT_BODY.ko, 13) } }
    item { Box(Modifier.morph(this, spec)) { Line(WatchText.CONSENT_RAW_STAYS.ko, 13) } }
    item { Box(Modifier.morph(this, spec)) { Line(WatchText.CONSENT_RIGHT.ko, 13, WatchColors.Fg2) } }
    item { Box(Modifier.morph(this, spec)) { Line(Phrase.NOTICE_LIMITS.ko, 12, WatchColors.Fg2) } }
    item { Box(Modifier.morph(this, spec)) { Line(Phrase.NOTICE_NOT_MEDICAL.ko, 12, WatchColors.Fg2) } }
    item { ListButton(WatchText.CONSENT_AGREE.ko, spec, WatchColors.Filled, WatchColors.OnFilled, onAgree) }
}

/** W01 · W02 — 숫자 고르개 3칸(0~9, 베젤 · 크라운 회전, 칸 바탕 `Surface`) + 가장자리 버튼. 6칸을 한 화면에 넣지 않고 3 + 3 으로 나눈다. */
@Composable
fun PairCodeScreen(step: Int, pickers: List<PickerState>, selected: Int, onSelect: (Int) -> Unit, onNext: () -> Unit) =
    Round(edge = { Edge(if (step == 1) WatchText.NEXT.ko else WatchText.CONNECT.ko, EdgeButtonSize.Small, onNext) }) {
        Line("${if (step == 1) WatchText.STEP1.ko else WatchText.STEP2.ko} $step/2", 17, weight = FontWeight.Bold)
        PickerGroup(selectedPickerState = pickers[selected]) {
            pickers.forEachIndexed { i, st ->
                PickerGroupItem(
                    pickerState = st,
                    selected = i == selected,
                    onSelected = { onSelect(i) },
                    modifier = Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(12.dp)).background(WatchColors.Surface),
                    option = { index, _ -> Text("$index", fontSize = 34.sp, fontWeight = FontWeight.Bold) },
                )
            }
        }
    }

enum class PairOutcome { CHECKING, DONE, INVALID, NETWORK }

/** 짝짓기 결과 화면 위쪽 기다림 로고 — 중계 확인을 기다리는 동안(CHECKING)만 둔다. 결과가 나오면 화면 내용이 바뀌어 사라진다(motion.md 5절). */
fun pairSpinnerShown(outcome: PairOutcome): Boolean = outcome == PairOutcome.CHECKING

/**
 * W03 — 확인 중이면 위쪽 안전 영역에 기다림 로고 48(도는 테두리) + 「확인 중」, 맞으면 2초 뒤 홈,
 * 틀리면 ▲ `Lost` 글리프 + 흰 글자 + 「다시 넣기」(경보 단계색은 쓰지 않는다).
 */
@Composable
fun PairResultScreen(outcome: PairOutcome, onRetry: () -> Unit) {
    val retry = outcome == PairOutcome.INVALID || outcome == PairOutcome.NETWORK
    Round(
        top = if (pairSpinnerShown(outcome)) ({ MajuSpinner(waiting = true, size = 48.dp) }) else null,
        edge = if (retry) ({ Edge(WatchText.RETRY.ko, EdgeButtonSize.Small, onRetry) }) else null,
    ) {
        when (outcome) {
            PairOutcome.CHECKING -> Line(WatchText.LINK_CHECKING.ko, 17, WatchColors.Fg2, FontWeight.Bold)
            PairOutcome.DONE -> {
                Glyph(CircleShape, WatchColors.Ok, "✓", 40.dp, WatchColors.Fg)
                Line(WatchText.PAIR_DONE.ko, 17, weight = FontWeight.Bold)
            }
            PairOutcome.INVALID -> {
                Glyph(Triangle, WatchColors.Lost, "!", 24.dp)
                Line(WatchText.PAIR_INVALID.ko, 17, weight = FontWeight.Bold)
            }
            PairOutcome.NETWORK -> {
                Glyph(Triangle, WatchColors.Lost, "!", 24.dp)
                Line(WatchText.PAIR_NETWORK.ko.substringBefore(" · "), 17, weight = FontWeight.Bold)
                Line(WatchText.PAIR_NETWORK.ko.substringAfter(" · "), 13, WatchColors.Fg2)
            }
        }
    }
}
