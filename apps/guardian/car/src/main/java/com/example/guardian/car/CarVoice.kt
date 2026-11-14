// 차 안 음성 — Pleos TextToSpeech ON_DEVICE 로 말하고, 말하는 동안만 오디오 포커스 GAIN_TRANSIENT 를 잡았다 놓는다. 포커스 판단은 FocusDecider(순수)가 하고 여기서는 실행만 한다
package com.example.guardian.car

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.guardian.car.FocusDecider.Act
import com.example.guardian.shared.text.Phrase
import java.util.Locale

/**
 * 새 안내가 오면 말하던 것을 끊고(flush) 새 것을 말한다 — 경보는 최신 상태가 중요하다.
 * 시스템 음량을 올리거나 긴급음 용도를 쓰지 않는다(G6). 권한이 없거나 SDK 가 실패하면 「음성 없음」으로 두고 앱은 계속 돈다.
 * 경보 음성은 GAIN_TRANSIENT 다 — 원칙의 MAY_DUCK 에서 벗어난 예외로 원칙 문서 I 절에 적었다(I-2).
 * 일시 손실 · 거부 · 지연 포커스 · 영구 손실 처리는 [FocusDecider] 의 규칙을 따른다. 모든 판단 · 실행은 메인 스레드에서 한다.
 */
class CarVoice(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attrs)
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, main)
        .build()
    private val decider = FocusDecider()
    private val tts: TextToSpeech? = create()
    private var ready = false
    private var pending: String? = null

    private val events = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = onMain { run(decider.onSpeechEnd("완료")) }
        override fun onStop() = onMain { run(decider.onSpeechEnd("중지")) }
        override fun onError(errorType: ErrorType) = onMain { run(decider.onSpeechEnd("오류 ${errorType.name}")) }
    }

    init {
        tts?.let { t ->
            sdk("initialize") {
                t.initialize(object : OnInitializeListener {
                    override fun onSuccess() = onMain {
                        // 문서 순서 — initialize 뒤에 리스너 · 언어 · 오디오 속성(먼저 부르면 SDK 내부 lateinit 로 죽는다, Prenim 실측)
                        ready = sdk("addEventListener") { t.addEventListener(events) } &&
                            sdk("setLocale") { t.setLocale(Locale.KOREA) } &&
                            sdk("setAudioAttributes") { t.setAudioAttributes(attrs) }
                        pending?.let { pending = null; say(it) }
                    }
                    override fun onFailure(errorType: ErrorType) = onMain { Log.w(TAG, "TTS 초기화 실패 ${errorType.name} — 음성 없음") }
                })
            }
        }
    }

    /** 어느 스레드에서 불러도 된다. 준비 전이면 마지막 한 줄만 기억했다가 준비되면 말한다. */
    fun say(text: String) = onMain {
        val t = tts
        if (t == null) { Log.i(TAG, "음성 없음 — 화면만"); return@onMain }
        if (!ready) { pending = text; return@onMain }
        run(decider.say(text, label(text), now()))
    }

    fun shutdown() = onMain {
        run(decider.shutdown())
        tts?.let { t -> sdk("removeEventListener") { t.removeEventListener(events) }; sdk("release") { t.release() } }
        ready = false
    }

    private fun onFocusChange(change: Int) {
        val c = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusDecider.Change.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> FocusDecider.Change.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusDecider.Change.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusDecider.Change.CAN_DUCK
            else -> { Log.i(TAG, "포커스 변화 $change(처리 없음)"); return }
        }
        run(decider.onChange(c, now()))
    }

    /** 판단이 돌려준 할 일을 차례로 실행한다(메인 스레드). 요청 결과 · 재시도 시각은 다시 판단으로 돌려준다. */
    private fun run(acts: List<Act>) {
        for (a in acts) when (a) {
            Act.Request -> {
                val r = when (audio.requestAudioFocus(focus)) {
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> FocusDecider.Result.GRANTED
                    AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> FocusDecider.Result.DELAYED
                    else -> FocusDecider.Result.FAILED
                }
                run(decider.onRequest(r, now()))
            }
            is Act.Speak -> {
                val t = tts
                if (t == null || !sdk("speak") { t.speak(a.text, true) }) run(decider.onSpeechEnd("speak 실패"))
            }
            Act.Stop -> tts?.let { t -> sdk("stop") { t.stop() } }
            is Act.Abandon -> audio.abandonAudioFocusRequest(focus)
            is Act.RetryIn -> main.postDelayed({ run(decider.onRetryTimer(now())) }, a.ms)
            is Act.Log -> Log.i(TAG, a.line)
        }
    }

    /** 로그용 문구 이름 — 원문 대신 Phrase 이름(G2). 표에 없는 문장은 「기타」. */
    private fun label(text: String): String = Phrase.entries.firstOrNull { it.ko == text }?.name ?: "기타"

    private fun now() = SystemClock.elapsedRealtime()

    private fun create(): TextToSpeech? {
        if (app.checkSelfPermission(TTS_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "$TTS_PERMISSION 없음 — 음성 없음")
            return null
        }
        return runCatching { TextToSpeech(app, Mode.ON_DEVICE) }
            .onFailure { Log.w(TAG, "TTS 생성 실패 ${it.javaClass.simpleName} — 음성 없음") }
            .getOrNull()
    }

    private inline fun sdk(name: String, block: () -> Unit): Boolean = try {
        block(); true
    } catch (e: Throwable) {
        Log.w(TAG, "TTS $name 실패 ${e.javaClass.simpleName}")
        false
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val TAG = "CarVoice"
        const val TTS_PERMISSION = "pleos.car.permission.TTS_SERVICE"
    }
}
