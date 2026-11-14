// debug 전용 「생체 기록」 화면 — 권한 요청 → 기록 서비스 시작 · 끝, 경과 시간 · 스트림별 건수 · 최근 심박 · 배터리 표시(제품 기능 아님)
package com.example.guardian.watch.probe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.example.guardian.watch.core.ResumeDecision
import com.example.guardian.watch.service.WatchHub
import com.example.guardian.watch.ui.GuardianWearTheme
import com.example.guardian.watch.ui.WatchColors
import kotlinx.coroutines.delay

/**
 * 런처의 「생체 기록」으로 연다(debug 만). 시작 → 건강 · 위치 권한 → [BioProbeService]. 화면을 나가도 서비스는 끝 버튼까지 계속된다.
 * 감시가 켜져 있으면 시작하지 않고 「감시를 끄고 시작하세요」를 보인다(트래커 종류당 리스너 하나 · 신호 간섭 방지). 감시를 대신 끄지 않는다.
 */
class BioProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GuardianWearTheme { Screen() } }
    }

    private fun missing(perms: List<String>) =
        perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    @Composable
    private fun Screen() {
        var state by remember { mutableStateOf(BioProbeHub.state) }
        var monitoring by remember { mutableStateOf(WatchHub.state.monitoring) }
        var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        var hsEnabled by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            while (true) {
                state = BioProbeHub.state
                monitoring = WatchHub.state.monitoring
                nowMs = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }

        // API 36+ 백그라운드 건강 권한은 앞의 건강 권한이 허용된 뒤 따로 묻는다(감시 화면과 같은 2단계). 거절해도 기록은 시작한다
        val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        fun launch() {
            message = if (missing(BioProbeService.LOCATION_PERMISSIONS).isNotEmpty()) "위치 권한 없음 — 속도는 기록하지 않습니다" else null
            BioProbeService.start(this, hsEnabled)
            if (Build.VERSION.SDK_INT >= 36 && missing(listOf(ResumeDecision.HEALTH_READ_IN_BACKGROUND)).isNotEmpty()) {
                background.launch(ResumeDecision.HEALTH_READ_IN_BACKGROUND)
            }
        }
        val health = BioProbeService.healthPermissions(Build.VERSION.SDK_INT)
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (missing(health).isEmpty()) launch() else message = "건강 권한이 없어 기록할 수 없습니다"
        }
        fun startRecording() {
            if (WatchHub.state.monitoring) { message = "감시를 끄고 시작하세요"; return }
            val ask = health + BioProbeService.LOCATION_PERMISSIONS +
                (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            val need = missing(ask)
            if (need.isEmpty()) launch() else permissions.launch(need.toTypedArray())
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("생체 기록(debug)", fontSize = 14.sp, color = WatchColors.Fg2)
            Text(state.sdk ?: if (SdkRecorderProvider.AVAILABLE) "Sensor SDK 포함 빌드" else "Sensor SDK 없는 빌드", fontSize = 12.sp, color = WatchColors.Mute, textAlign = TextAlign.Center)
            if (monitoring) Text("감시 켜짐", fontSize = 12.sp, color = WatchColors.Caution)

            if (state.running) {
                val elapsedS = ((nowMs - state.startedAtMs) / 1000).coerceAtLeast(0)
                Text("%d:%02d".format(elapsedS / 60, elapsedS % 60), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            state.session?.let { Text(it, fontSize = 11.sp, color = WatchColors.Mute) }
            Text("배터리 ${state.batteryPct ?: "-"}%", fontSize = 14.sp)
            state.lastHr?.let { Text(it, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Counts(state.counts)
            state.notice?.let { Text(it, fontSize = 12.sp, color = WatchColors.Fg2, textAlign = TextAlign.Center) }
            message?.let { Text(it, fontSize = 13.sp, color = WatchColors.Caution, textAlign = TextAlign.Center) }

            if (!state.running) {
                Button(onClick = { hsEnabled = !hsEnabled }, modifier = Modifier.fillMaxWidth()) {
                    Text("Health Services 심박 ${if (hsEnabled) "켬" else "끔"}", fontSize = 14.sp)
                }
            }
            Button(
                onClick = { if (state.running) BioProbeService.stop(this@BioProbeActivity) else startRecording() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.running) "기록 끝" else "기록 시작", fontSize = 16.sp) }
        }
    }

    @Composable
    private fun Counts(counts: List<Long>) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BioStream.COUNTED.forEachIndexed { i, s ->
                Text("${s.file.removeSuffix(".csv")} ${counts.getOrNull(i) ?: 0}", fontSize = 13.sp, color = WatchColors.Fg2)
            }
        }
    }
}
