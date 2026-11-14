// 워치 경보 출력 — 1단계 최대 진동(진폭 255 반복), 2단계 진동 + 알람 스트림 소리(USAGE_ALARM, 필요하면 최대 음량 후 복원)
package com.example.guardian.watch.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.guardian.shared.protocol.Payload

/**
 * ALERT_STOP 이 올 때까지 계속한다 — durationMs 는 화면 표시용이라 스스로 끄지 않는다(계약 §4.6).
 * 같은 경보가 다시 오거나 단계가 올라가면(1 → 2) 새 설정으로 바꿔 건다.
 */
class Alerter(context: Context) {
    private val ctx = context.applicationContext
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var savedAlarmVolume: Int? = null

    @Synchronized
    fun start(p: Payload.AlertStart) {
        stopOutputs()
        startOutputs(p, ::vibrate, ::raiseAlarmVolume, ::play)
    }

    @Synchronized
    fun stop() {
        stopOutputs()
    }

    private fun vibrate() {
        val v = vibrator ?: return
        // 0.8초 켬 · 0.3초 쉼을 반복. 진폭 제어가 없는 기기는 기본 세기로 돈다
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 300), intArrayOf(0, 255, 0), 1)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun raiseAlarmVolume() {
        if (savedAlarmVolume != null) return
        val before = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        savedAlarmVolume = before // 올린 뒤에만 기억한다 — 올리기가 실패했으면 되돌릴 것도 없다
    }

    private fun play() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        player = uri?.let {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(ctx, it)
                    isLooping = true
                    prepare()
                    start()
                }
            }.onFailure { e -> Log.w(TAG, "알람 소리 실패 ${e::class.java.simpleName}") }.getOrNull()
        }
        if (player == null) {
            // 워치에 기본 알람음이 없으면 알람 스트림 톤으로 대신한다
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
            tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)
        }
    }

    private fun stopOutputs() {
        step("진동 끄기") { vibrator?.cancel() }
        player?.runCatching { stop(); release() }
        player = null
        tone?.runCatching { stopTone(); release() }
        tone = null
        savedAlarmVolume?.let { v -> step("알람 음량 복원") { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        savedAlarmVolume = null
    }

    companion object {
        private const val TAG = "GuardianWatch"

        /**
         * 경보 출력 순서 — 진동 · (경보 2) 최대 음량 · 재생을 따로 감싼다. 방해 금지 등으로 음량 변경이 예외를 내도
         * 재생 · 호출한 쪽의 경보 상태 · 화면은 이어진다(코드 리뷰 11-08 중4).
         */
        internal fun startOutputs(p: Payload.AlertStart, vibrate: () -> Unit, raiseVolume: () -> Unit, play: () -> Unit) {
            if (p.vibrate || p.level >= 2) step("진동", vibrate)
            if (p.sound) {
                if (p.maxVolume) step("최대 음량", raiseVolume)
                step("알람 소리", play)
            }
        }

        /** 한 단계를 감싼다 — 실패는 값 없이 예외 이름만 남긴다. */
        internal fun step(name: String, block: () -> Unit) {
            runCatching(block).onFailure { Log.w(TAG, "$name 실패 ${it::class.java.simpleName}") }
        }
    }
}
