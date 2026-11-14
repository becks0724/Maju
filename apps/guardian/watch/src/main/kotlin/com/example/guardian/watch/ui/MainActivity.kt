// 워치 첫 화면 — 건강 관련 동의 → W04 켜짐/꺼짐 · W01~W03 차와 연결(코드 → /v1/pair/join role WATCH) · W05 착용 확인 · W10 테스트 · W11 상태(끄기 — 짝 유지 · 동의 철회 — pair/end)
package com.example.guardian.watch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.rememberPickerState
import com.example.guardian.shared.bio.WristVerdict
import com.example.guardian.shared.text.SmsText
import com.example.guardian.watch.core.ResumeDecision
import com.example.guardian.watch.net.RelayResult
import com.example.guardian.watch.sensor.SensorSdkProvider
import com.example.guardian.watch.service.MonitorOff
import com.example.guardian.watch.service.MonitorService
import com.example.guardian.watch.service.PairEnd
import com.example.guardian.watch.service.PairStore
import com.example.guardian.watch.service.RelayNet
import com.example.guardian.watch.service.Resume
import com.example.guardian.watch.service.WatchHub
import com.example.guardian.watch.text.WatchText
import com.example.guardian.watch.text.WatchTexts
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var store: PairStore
    /** 화면이 앞으로 올 때마다 늘린다 — 켜 둔 감시가 안 돌고 있으면 다시 켤지 판단하는 열쇠(ResumeDecision APP_OPEN). */
    private val resumed = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PairStore(this)
        PairEnd.kick(this) // 앞서 철회 때 못 끝낸 워치 연결이 있으면 다시 끝낸다
        setContent { GuardianWearTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        resumed.intValue++
    }

    /** 감시에 꼭 필요한 런타임 권한(부팅 · 업데이트 뒤 다시 켜기 판단과 같은 목록). */
    private fun requiredPermissions(): List<String> =
        ResumeDecision.requiredPermissions(Build.VERSION.SDK_INT, SensorSdkProvider.AVAILABLE)

    private fun missing(perms: List<String>) =
        perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    @Composable
    private fun App() {
        var consent by remember { mutableStateOf(store.healthConsent) }
        var paired by remember { mutableStateOf(store.token != null) }
        var pairStep by remember { mutableIntStateOf(0) } // 0 없음 · 1 앞 3자리 · 2 뒤 3자리 · 3 결과
        var firstHalf by remember { mutableStateOf("") }
        var outcome by remember { mutableStateOf(PairOutcome.CHECKING) }
        var showStatus by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val ui by rememberHubState()
        val now = rememberNow()
        // 중계가 401 을 주면 서비스가 토큰을 지운다 — 화면 상태가 바뀔 때마다 저장값으로 다시 맞춘다
        val isPaired = paired && store.token != null

        // API 36+ 백그라운드 건강 권한은 앞의 건강 권한이 허용된 뒤 따로 묻는다(위치 백그라운드와 같은 2단계 [미검증]).
        // 거절해도 감시는 켠다 — 화면이 앞에 있을 때 시작한 포그라운드 서비스라 측정은 이어질 수 있다 [미검증]
        val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        fun startWithBackgroundAsk() {
            message = null
            store.setMonitorWanted(true) // 재부팅 · 업데이트 뒤 다시 켜기(ResumeReceiver)
            MonitorService.start(this)
            if (Build.VERSION.SDK_INT >= 36 && missing(listOf(HEALTH_READ_IN_BACKGROUND)).isNotEmpty()) background.launch(HEALTH_READ_IN_BACKGROUND)
        }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (missing(requiredPermissions()).isEmpty()) startWithBackgroundAsk() else message = WatchText.PERMISSION_NEEDED.ko
        }
        fun turnOn() {
            val ask = requiredPermissions() + (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            val need = missing(ask)
            if (need.isEmpty()) startWithBackgroundAsk() else permissions.launch(need.toTypedArray())
        }
        // 앱을 열었을 때(또는 동의 · 짝이 갖춰졌을 때) 켜 둔 감시가 안 돌고 있으면 다시 켠다. 화면이 앞이라 백그라운드 권한은 보지 않는다.
        // 빠진 조건이 있으면 저장된 켜짐은 두고 무엇이 빠졌는지 첫 화면 한 줄로 보인다(동의는 동의 화면, 짝은 「차와 연결」)
        val opened by resumed
        LaunchedEffect(opened, consent, isPaired, ui.monitoring) {
            if (ui.monitoring) return@LaunchedEffect
            when (val d = ResumeDecision.decide(ResumeDecision.Trigger.APP_OPEN, Resume.inputs(this@MainActivity))) {
                ResumeDecision.Decision.Stay -> Unit
                ResumeDecision.Decision.Start -> { message = null; MonitorService.start(this@MainActivity) }
                is ResumeDecision.Decision.Blocked -> message = when {
                    ResumeDecision.Missing.PAIR in d.missing -> WatchText.RESUME_NEED_PAIR.ko
                    ResumeDecision.Missing.PERMISSION in d.missing -> WatchText.PERMISSION_NEEDED.ko
                    else -> message
                }
            }
        }
        fun startPair() {
            if (!RelayNet(this).configured) { message = WatchText.PAIR_NO_RELAY.ko; return }
            message = null; firstHalf = ""; pairStep = 1
        }

        val alert = ui.alert
        val cleared = ui.respondedClearedAtMs
        when {
            alert != null -> {
                // 경보 화면은 잠금 위로 켜지는 AlertActivity 가 맡는다. 이 화면이 앞에 있으면 같은 화면을 그리고 그쪽을 연다
                LaunchedEffect(alert.startedAtMs) {
                    startActivity(Intent(this@MainActivity, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                BackHandler { }
                AlertScreen(alert, now) { MonitorService.respond(this) }
            }
            cleared != null && now - cleared < AlertActivity.RESPONDED_SHOW_MS -> RespondedScreen()
            !consent -> ConsentScreen {
                store.setConsent(System.currentTimeMillis())
                consent = true
            }
            pairStep == 1 || pairStep == 2 -> key(pairStep) {
                val pickers = listOf(rememberPickerState(10, 0, true), rememberPickerState(10, 0, true), rememberPickerState(10, 0, true))
                var selected by remember { mutableIntStateOf(0) }
                BackHandler { pairStep = if (pairStep == 2) 1 else 0 }
                PairCodeScreen(pairStep, pickers, selected, { selected = it }) {
                    val half = pickers.joinToString("") { it.selectedOptionIndex.toString() }
                    if (pairStep == 1) {
                        firstHalf = half; pairStep = 2
                    } else {
                        outcome = PairOutcome.CHECKING; pairStep = 3
                        join(firstHalf + half) { result ->
                            outcome = result
                            if (result == PairOutcome.DONE) {
                                paired = true
                                WatchHub.clearRepair()
                            }
                        }
                    }
                }
            }
            pairStep == 3 -> {
                if (outcome == PairOutcome.DONE) LaunchedEffect(Unit) {
                    delay(2_000)
                    pairStep = 0
                    MonitorService.syncNow(this@MainActivity)
                }
                PairResultScreen(outcome) { firstHalf = ""; pairStep = 1 }
            }
            ui.monitoring && ui.test != null -> TestRunningScreen(ui.test!!, now) { MonitorService.stopTest(this) }
            showStatus -> {
                BackHandler { showStatus = false }
                SignalStatusScreen(
                    rows = statusRows(ui, isPaired, now),
                    lost = when {
                        !isPaired && ui.link == WatchHub.Link.REPAIR -> WatchText.PAIR_EXPIRED.ko
                        isPaired && ui.monitoring && ui.link == WatchHub.Link.RETRY -> WatchText.LINK_LOST.ko
                        else -> null
                    },
                    actions = buildList {
                        if (ui.monitoring) add(WatchText.TURN_OFF.ko to { MonitorOff.run(this@MainActivity) })
                        else if (isPaired) add(WatchText.TURN_ON.ko to { turnOn() })
                        // 「연결 끊기」는 두지 않는다 — 계약 §3.2: pair/end 는 동의 철회 · 모두 지우기 때만, 끄기는 짝 유지
                        if (!isPaired) add(WatchText.PAIR.ko to { showStatus = false; startPair() })
                        add(WatchText.CONSENT_WITHDRAW.ko to {
                            // G2 철회 — 감시를 끄고, 중계의 워치 연결을 지우고, 동의 기록을 지운다
                            MonitorOff.run(this@MainActivity)
                            unpair()
                            store.setConsent(null)
                            WatchHub.clearRepair()
                            paired = false; consent = false; showStatus = false
                        })
                    },
                )
            }
            ui.monitoring && ui.verdict == WristVerdict.CHECK_WEAR -> CheckWearScreen { showStatus = true }
            else -> StatusScreen(
                ui,
                linkMark = if (message != null) LinkMark.LOST else homeLinkMark(ui, isPaired),
                linkLine = message ?: homeLinkLine(ui, isPaired),
                edgeLabel = when {
                    !isPaired -> WatchText.PAIR.ko
                    !ui.monitoring -> WatchText.TURN_ON.ko
                    else -> WatchText.STATUS.ko
                },
                onEdge = {
                    when {
                        !isPaired -> startPair()
                        !ui.monitoring -> turnOn()
                        else -> showStatus = true
                    }
                },
                onStatus = if (ui.monitoring) null else ({ showStatus = true }),
                // 신호 없음(Health Services 유효 심박 10초 공백 · 등록 실패) — 앞부분 한 줄. 착용 안 함은 위 CheckWearScreen 이 맡는다
                notice = if (ui.monitoring && ui.noSignal) WatchTexts.headOf(WatchText.NO_SIGNAL) else null,
            )
        }
    }

    private fun carLink(ui: WatchHub.UiState, paired: Boolean): String = when {
        !paired && ui.link == WatchHub.Link.REPAIR -> WatchTexts.headOf(WatchText.PAIR_EXPIRED)
        !paired -> WatchText.LINK_NONE.ko
        !ui.monitoring -> WatchText.PAIR_DONE.ko
        ui.link == WatchHub.Link.OK -> WatchText.LINK_OK.ko
        ui.link == WatchHub.Link.RETRY -> WatchTexts.headOf(WatchText.LINK_LOST)
        else -> WatchText.LINK_CHECKING.ko
    }

    /** 연결 한 줄의 글리프 — [carLink] 와 같은 갈래(연결됨 · 짝 맺음 ●, 끊김 · 연결 안 됨 · 기간 끝 ▲, 확인 중 ○). */
    private fun homeLinkMark(ui: WatchHub.UiState, paired: Boolean): LinkMark = when {
        !paired -> LinkMark.LOST
        !ui.monitoring || ui.link == WatchHub.Link.OK -> LinkMark.OK
        ui.link == WatchHub.Link.RETRY -> LinkMark.LOST
        else -> LinkMark.CHECKING
    }

    private fun homeLinkLine(ui: WatchHub.UiState, paired: Boolean): String {
        val v = carLink(ui, paired)
        return if (v == WatchText.PAIR_DONE.ko) v else "${WatchText.LINK_CAR.ko} · $v"
    }

    private fun statusRows(ui: WatchHub.UiState, paired: Boolean, now: Long): List<Pair<String, String>> {
        val measure = when {
            !ui.monitoring -> WatchText.HOME_OFF.ko
            ui.noSignal -> WatchText.NO_SIGNAL.ko // 신호를 못 잡는 동안은 착용 · 출처 대신 이 줄
            else -> {
                val worn = if (ui.verdict == WristVerdict.CHECK_WEAR) SmsText.NOT_WORN else SmsText.WORN
                "$worn · ${ui.source?.let(WatchTexts::source) ?: ""}"
            }
        }
        val last = ui.lastSyncOkAtMs
        val send = when {
            !paired -> WatchText.LINK_NONE.ko
            ui.monitoring && last != null && ui.link == WatchHub.Link.OK ->
                "${if (ui.viaWifi) WatchText.VIA_WIFI.ko else WatchText.VIA_PHONE.ko} · ${WatchText.LINK_AGO.ko.format((now - last).coerceAtLeast(0) / 1000)}"
            else -> WatchText.LINK_CHECKING.ko
        }
        return listOf(WatchText.MEASURE.ko to measure, WatchText.SEND.ko to send, WatchText.LINK_CAR.ko to carLink(ui, paired))
    }

    /** 짝 참여를 백그라운드에서 보낸다. 끝나면 메인 스레드에서 [done]. */
    private fun join(code: String, done: (PairOutcome) -> Unit) {
        val net = RelayNet(this)
        Thread {
            // IOException 밖의 예외(URL 형식 등)에서도 Wi-Fi 요청을 놓고 화면에 실패를 알린다
            val outcome = try {
                net.acquire()
                net.awaitWifi(3_000)
                when (val r = net.api.join(code)) {
                    is RelayResult.Ok -> { store.savePair(r.value.token, r.value.pairId); PairOutcome.DONE }
                    RelayResult.InvalidCode -> PairOutcome.INVALID
                    else -> PairOutcome.NETWORK
                }
            } catch (e: RuntimeException) {
                PairOutcome.NETWORK
            } finally {
                net.release()
            }
            runOnUiThread { done(outcome) }
        }.start()
    }

    /**
     * 동의 철회 때만 — 워치 토큰으로 pair/end, 워치 연결만 지운다(계약 §3.2). 이 기기의 짝은 바로 지우고 토큰은 「끝낼 토큰」으로만
     * 남긴다. 실패하면 앱 실행 · 감시 시작 · 네트워크 복구 때 다시 부르고, 성공 · 401 · 24시간이면 지운다(보안 감사 하2).
     */
    private fun unpair() {
        store.moveTokenToEndPending(System.currentTimeMillis()) ?: return
        PairEnd.kick(this)
    }

    private companion object {
        const val HEALTH_READ_IN_BACKGROUND = ResumeDecision.HEALTH_READ_IN_BACKGROUND
    }
}
