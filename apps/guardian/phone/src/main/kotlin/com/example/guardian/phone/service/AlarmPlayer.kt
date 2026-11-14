// 휴대폰 경보 소리 — USAGE_ALARM 으로 알람 음량을 최대로 올려 반복 재생하고, 멈추면 원래 음량으로 되돌린다(G6 · 설계 §6)
package com.example.guardian.phone.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager

/** ALERT_START 의 durationMs 는 표시용이라 스스로 멈추지 않는다 — [stop](ALERT_STOP) 까지 계속한다. */
class AlarmPlayer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var savedVolume: Int? = null

    /** 사람이 119 작성 화면을 열면 소리만 줄인다 — ALERT_STOP([stop]) 이 올 때까지 다시 울리지 않는다. */
    private var muted = false

    fun mute() {
        muted = true
        player?.runCatching { stop(); release() }
        player = null
        restoreVolume()
    }

    fun start() {
        if (player != null || muted) return
        try {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: SecurityException) {
            // 방해 금지 등으로 음량을 못 바꾸면 지금 음량으로 울린다
            savedVolume = null
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            restoreVolume()
            null
        }
    }

    fun stop() {
        muted = false
        player?.runCatching { stop(); release() }
        player = null
        restoreVolume()
    }

    private fun restoreVolume() {
        val v = savedVolume ?: return
        savedVolume = null
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
    }
}
