// Pleos 양식 공통 컴포넌트(설계 spec 5-0 · 8-1) — 머리 막대 · 버튼 · 묶음 머리 · 목록 줄 · 연결 줄 · 스위치 · 주행/모름 띠 · 분할 단추 · 탭 · 테스트 띠 · 대화창
package com.example.guardian.phone.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.guardian.phone.R
import com.example.guardian.phone.core.Motion

/** 화면 좌우 여백 16 — 칸(surface 면)은 화면 끝까지, 글자 · 버튼은 여백 안. */
val Gutter = Modifier.padding(horizontal = Dims.gutter)

/** 1px `line` 구분선. */
@Composable
fun HLine(color: Color = LocalPleosColors.current.line) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * 앱 막대 56 — 홈(← 없음)은 Maju 아이콘 판 32 + 글자 로고 「Maju」 높이 16 + 부제([title], Label M 14 text3) 한 줄,
 * 하위 화면은 ←(터치 48) + 화면 제목 + 오른쪽 단계(「3 / 5」). [logo] 가 거짓이면(처음 화면) ← 도 로고도 없이 제목만.
 * 로고 배치 기준은 팀 내부 로고 메모(비공개) 6절(그림은 logo-20261109/maju-final), 처음 화면 막대 로고 생략은 PM 결정(2026-11-08).
 */
@Composable
fun PleosTopBar(title: String, onBack: (() -> Unit)? = null, step: String? = null, logo: Boolean = true) {
    val c = LocalPleosColors.current
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Row(Modifier.fillMaxWidth().height(Dims.topBarH).padding(start = if (onBack == null) Dims.gutter else 4.dp, end = Dims.gutter), verticalAlignment = Alignment.CenterVertically) {
            if (onBack == null && !logo) {
                // 빈 제목(처음 화면)은 글자 칸을 두지 않는다 — TalkBack 이 빈 줄을 읽지 않게
                if (title.isEmpty()) Spacer(Modifier.weight(1f))
                else Text(title, color = c.text1, fontSize = Dims.titleS, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            } else if (onBack == null) {
                Image(painterResource(R.drawable.ic_logo_tile), null, Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Icon(painterResource(R.drawable.ic_wordmark_maju), PhoneTexts.BRAND_SPOKEN, Modifier.size(width = 35.82.dp, height = 16.dp), tint = c.text1)
                Spacer(Modifier.width(8.dp))
                Text(title, color = c.text3, fontSize = Dims.labelM, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Box(Modifier.size(Dims.touch).clickable(role = Role.Button, onClickLabel = PhoneTexts.BACK, onClick = onBack), contentAlignment = Alignment.Center) {
                    Text("←", color = c.text1, fontSize = Dims.titleL)
                }
                Spacer(Modifier.width(4.dp))
                Text(title, color = c.text1, fontSize = Dims.titleS, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            }
            step?.let { Text(it, color = c.text3, fontSize = Dims.labelM) }
        }
        HLine()
    }
}

/** 처음 화면 가로 조합 — 아이콘 판 48 + 글자 로고 「Maju」 높이 24, 그 아래 부제([subtitle], Title S 20 text2). */
@Composable
fun MajuLockup(subtitle: String) {
    val c = LocalPleosColors.current
    Row(Gutter, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_logo_tile), null, Modifier.size(48.dp))
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(R.drawable.ic_wordmark_maju), PhoneTexts.BRAND_SPOKEN, Modifier.size(width = 53.73.dp, height = 24.dp), tint = c.text1)
            Text(subtitle, color = c.text2, fontSize = Dims.titleS)
        }
    }
}

enum class ButtonKind { Filled, Basic, Text }

/**
 * Pleos 버튼 — 모서리 0 · 높이 56 · Title S 20 Bold. Filled = inverse, Basic = surfaceHigh, Text = 바탕 없음(터치 48).
 * 비활성이면 disabled 바탕 + text4 글자, [reason] 이 있으면 아래에 사유 한 줄(Body S 15 text2).
 */
@Composable
fun PleosButton(
    text: String, kind: ButtonKind = ButtonKind.Filled, enabled: Boolean = true, reason: String? = null,
    modifier: Modifier = Gutter, fillWidth: Boolean = true, onClick: () -> Unit,
) {
    val c = LocalPleosColors.current
    val (bg, fg) = when {
        kind == ButtonKind.Text -> Color.Transparent to (if (enabled) c.text1 else c.text4)
        !enabled -> (if (kind == ButtonKind.Filled) c.disabled else c.surfaceHigh) to c.text4
        kind == ButtonKind.Filled -> c.inverse to c.onInverse
        else -> c.surfaceHigh to c.text1
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val size = when {
            kind == ButtonKind.Text -> Modifier.heightIn(min = Dims.touch)
            fillWidth -> Modifier.fillMaxWidth().heightIn(min = Dims.buttonH)
            else -> Modifier.heightIn(min = Dims.buttonH)
        }
        Box(
            size.background(bg).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = if (kind == ButtonKind.Text) 4.dp else Dims.gutter, vertical = 8.dp),
            contentAlignment = if (kind == ButtonKind.Text) Alignment.CenterStart else Alignment.Center,
        ) {
            Text(text, color = fg, fontSize = if (kind == ButtonKind.Text) Dims.bodyL else Dims.titleS, fontWeight = if (kind == ButtonKind.Text) FontWeight.Normal else FontWeight.Bold)
        }
        if (!enabled) reason?.let { Text(it, color = c.text2, fontSize = Dims.bodyS) }
    }
}

/** 묶음 머리 — Label M 14 text3, 위 24 · 아래 8. */
@Composable
fun SectionHead(text: String) {
    Text(text, color = LocalPleosColors.current.text3, fontSize = Dims.labelM, modifier = Modifier.padding(start = Dims.gutter, end = Dims.gutter, top = 24.dp, bottom = 8.dp))
}

/** 목록 칸 — surface 면, 위 1px line(칸 테두리 상자 없음). 안의 [ListRow] 가 아래 선을 긋는다. */
@Composable
fun ListSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(LocalPleosColors.current.surface)) {
        HLine()
        content()
    }
}

/** 목록 줄 — 최소 56, 왼쪽 여백 16, 아래 1px line. */
@Composable
fun ListRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = Dims.rowH).padding(horizontal = Dims.gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    HLine()
}

/** 연결 줄 한 줄의 표시 값. [extra] 는 상태 열 둘째 줄(Body S 15 text3). */
data class LinkRowUi(val glyph: LinkGlyph, val name: String, val detail: String, val extra: String? = null)

/**
 * 연결 줄 — 열 고정(글리프 24 · 이름 64 · 상태 나머지, 왼쪽 맞춤 · 기준선 맞춤).
 * ▲ 는 글리프만 lost, 사유 글자는 text2. ○ 는 text3.
 */
@Composable
fun LinkRows(rows: List<LinkRowUi>) {
    val c = LocalPleosColors.current
    ListSection {
        for (r in rows) ListRow {
            val (g, gc) = when (r.glyph) {
                LinkGlyph.OK -> "●" to c.ok
                LinkGlyph.LOST -> "▲" to c.lost
                LinkGlyph.PENDING -> "○" to c.text3
            }
            Row(Modifier.fillMaxWidth()) {
                Text(g, color = gc, fontSize = Dims.titleS, modifier = Modifier.width(24.dp).alignByBaseline())
                Text(r.name, color = c.text1, fontSize = Dims.titleS, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp).padding(start = 4.dp).alignByBaseline())
                Column(Modifier.weight(1f).alignByBaseline()) {
                    Text(r.detail, color = if (r.glyph == LinkGlyph.PENDING) c.text3 else c.text2, fontSize = Dims.bodyL)
                    r.extra?.let { Text(it, color = c.text3, fontSize = Dims.bodyS) }
                }
            }
        }
    }
}

/** Pleos 스위치 — 켬 트랙 active + 흰 손잡이 안 ✓(#131417), 끔 트랙 surfaceHigh + 테두리 · 손잡이 text3. */
@Composable
fun PleosSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalPleosColors.current
    Switch(
        checked, onChange, enabled = enabled,
        thumbContent = if (checked) ({ Text("✓", color = Color(0xFF131417), fontSize = Dims.labelM, fontWeight = FontWeight.Bold) }) else null,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.active, checkedThumbColor = Color.White, checkedBorderColor = c.active, checkedIconColor = Color(0xFF131417),
            uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.text3, uncheckedThumbColor = c.text3,
            disabledCheckedTrackColor = c.disabled, disabledCheckedThumbColor = c.text4, disabledCheckedBorderColor = c.text4,
            disabledUncheckedTrackColor = c.surfaceHigh, disabledUncheckedBorderColor = c.text4, disabledUncheckedThumbColor = c.text4,
        ),
    )
}

/** 체크 상자 24 — 켬 = text1 채움 + ✓(색이 아니라 채움으로). */
@Composable
fun PleosCheckbox(checked: Boolean, enabled: Boolean) {
    val c = LocalPleosColors.current
    Checkbox(checked, null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = c.text1, uncheckedColor = c.text1, checkmarkColor = c.surface))
}

@Composable
fun PleosRadio(selected: Boolean) {
    val c = LocalPleosColors.current
    RadioButton(selected, null, colors = RadioButtonDefaults.colors(selectedColor = c.text1, unselectedColor = c.text3))
}

/** P09 · P09' 띠 — surfaceHigh 바탕 + 아이콘 24 · Title S 20 Bold text1(상태색 없음). 앱 막대 바로 아래, 스크롤되지 않는다. */
@Composable
fun MotionBanner(motion: Motion?) {
    val kind = UiRules.banner(motion) ?: return
    val c = LocalPleosColors.current
    val (icon, text) = when (kind) {
        BannerKind.DRIVING_LOCK -> R.drawable.ic_lock to PhoneTexts.DRIVING_LOCK
        BannerKind.MOTION_UNKNOWN -> R.drawable.ic_help_circle to PhoneTexts.MOTION_UNKNOWN
    }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = Dims.rowH).background(c.surfaceHigh).padding(horizontal = Dims.gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(Dims.icon), tint = c.text1)
            Spacer(Modifier.width(12.dp))
            Text(text, color = c.text1, fontSize = Dims.titleS, fontWeight = FontWeight.Bold)
        }
        HLine()
    }
}

/** 분할 단추(모서리 0 · 높이 48) — 고른 칸 inverse, 아닌 칸 basic. 차량 색상은 줄바꿈 칩으로 쓴다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> PleosSegments(options: List<T>, selected: T?, label: (T) -> String, enabled: Boolean = true, wrap: Boolean = false, onSelect: (T) -> Unit) {
    val c = LocalPleosColors.current
    @Composable fun Seg(o: T, m: Modifier) {
        val on = o == selected
        Box(
            m.heightIn(min = Dims.touch).background(if (on) c.inverse else c.surfaceHigh)
                .selectable(on, enabled, Role.RadioButton) { onSelect(o) }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label(o), color = if (!enabled) c.text4 else if (on) c.onInverse else c.text1, fontSize = Dims.bodyL, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
    }
    if (wrap) FlowRow(Gutter, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) Seg(o, Modifier.widthIn(min = 64.dp))
    } else Row(Gutter.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (o in options) Seg(o, Modifier.weight(1f))
    }
}

/** Pleos 탭(P18) — 글자 Title S 20, 고른 탭 text1 Bold + 밑줄 3 text1, 아닌 탭 text3, 아래 1px line. */
@Composable
fun PleosTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalPleosColors.current
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            titles.forEachIndexed { i, t ->
                val on = i == selected
                Column(Modifier.width(IntrinsicTabWidth).selectable(on, role = Role.Tab) { onSelect(i) }) {
                    Box(Modifier.heightIn(min = Dims.touch - 3.dp), contentAlignment = Alignment.CenterStart) {
                        Text(t, color = if (on) c.text1 else c.text3, fontSize = Dims.titleS, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                    }
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) c.text1 else Color.Transparent))
                }
            }
        }
        HLine()
    }
}

/** 탭 한 칸 폭 — 「초단문 판」(Title S 20 다섯 글자)이 한 줄에 들어가는 폭. */
private val IntrinsicTabWidth: Dp = 104.dp

/** 입력칸 — 모서리 0, 테두리 text3 · 포커스 text1. */
@Composable
fun PleosField(label: String, value: String, enabled: Boolean, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    val c = LocalPleosColors.current
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Gutter.fillMaxWidth(), shape = RectangleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.text1, unfocusedBorderColor = c.text3, focusedLabelColor = c.text1, unfocusedLabelColor = c.text3,
            focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, disabledContainerColor = c.surface, cursorColor = c.text1,
        ),
    )
}

/** 테스트 띠 40 — testTint 바탕, testInk 「T」 네모 20(테두리 2) + 「테스트 · 실제 상황 아님」. 경보 화면에서는 어두운 값. */
@Composable
fun TestBand() {
    val c = LocalPleosColors.current
    Row(Modifier.fillMaxWidth().height(Dims.testBandH).background(c.testTint).padding(horizontal = Dims.gutter), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).border(2.dp, c.testInk, RectangleShape), contentAlignment = Alignment.Center) {
            Text("T", color = c.testInk, fontWeight = FontWeight.Bold, fontSize = Dims.labelM)
        }
        Spacer(Modifier.width(8.dp))
        Text(PhoneTexts.TEST_BAND, color = c.testInk, fontWeight = FontWeight.Bold, fontSize = Dims.titleS)
    }
}

/** 대화창 — 모서리 0, 그림자 Level 2(대화창만 예외), 바탕 popup(경보 화면은 [container] 로 alarmSurface). */
@Composable
fun PleosDialog(
    onDismiss: () -> Unit, confirm: @Composable () -> Unit, dismiss: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, container: Color = LocalPleosColors.current.popup, text: @Composable () -> Unit,
) {
    val c = LocalPleosColors.current
    AlertDialog(
        onDismissRequest = onDismiss, confirmButton = confirm, dismissButton = dismiss, title = title, text = text,
        modifier = Modifier.shadow(8.dp, RectangleShape), shape = RectangleShape, containerColor = container, tonalElevation = 0.dp,
        titleContentColor = c.text1, textContentColor = c.text1,
    )
}

/**
 * 홈 「차 화면」 묶음(car-spec 7-5 · 8-1) — 동의했으면 「차 화면에 심박 숨기기」 스위치 줄, 재동의 전이면 안내 + 「바로 가기」.
 * 주행 중에는 다른 홈 조작처럼 막는다(G5, [enabled] = !driving).
 */
@Composable
fun CarScreenSection(consented: Boolean, hideHr: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit, onGoConsent: () -> Unit) {
    val c = LocalPleosColors.current
    SectionHead(PhoneTexts.CAR_SCREEN_HEAD)
    ListSection {
        if (consented) ListRow {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(PhoneTexts.HIDE_HR_ON_CAR, color = c.text1, fontSize = Dims.titleS)
                Text(PhoneTexts.HIDE_HR_ON_CAR_SUB, color = c.text2, fontSize = Dims.bodyS)
            }
            PleosSwitch(hideHr, enabled, onToggle)
        } else ListRow {
            // 재동의 전 — 감시는 그대로, 차 화면 표시만 막혀 있다(늘 숨김으로 보냄)
            Text(PhoneTexts.CAR_SCREEN_NEEDS_CONSENT, color = c.text1, fontSize = Dims.bodyL, modifier = Modifier.weight(1f))
            PleosButton(PhoneTexts.GO, ButtonKind.Text, enabled, modifier = Modifier, onClick = onGoConsent)
        }
    }
}
