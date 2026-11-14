// 보호자 문자 전송 — SmsManager 다중 전송 + 조각별 sentIntent 결과 모으기, SEND_SMS 런타임 권한, 보호자 휴대폰 번호(01X)로만 보낸다(119 · 특수번호 경로 없음, G4)
package com.example.guardian.phone.service

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.guardian.phone.core.PhoneNumbers
import com.example.guardian.phone.core.SmsParts
import java.util.UUID

/**
 * 결과 수신기는 앱 안에서만 받는다 — 명시적 패키지 · FLAG_IMMUTABLE PendingIntent, 수신기는 `ContextCompat.registerReceiver`
 * RECEIVER_NOT_EXPORTED(Android 12 이하는 core 라이브러리의 서명 수준 권한 `<패키지>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 으로 막는다).
 * 결과는 [handler](엔진 스레드)에서 받아 [send] 의 done 을 한 번 부른다. 다 쓰면 [close].
 */
class SmsSender(private val context: Context, handler: Handler) {
    private val action = "${context.packageName}.GUARDIAN_SMS_SENT"
    private val parts = SmsParts()
    private val callbacks = HashMap<String, (Boolean) -> Unit>()
    /** 조각별 PendingIntent 번호 — 서비스를 다시 띄워도 옛 PendingIntent 와 겹치지 않게 부팅 뒤 경과 시간에서 시작한다. */
    private var requestCode = (SystemClock.elapsedRealtime() % Int.MAX_VALUE).toInt()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val ok = parts.onPart(id, resultCode == Activity.RESULT_OK) ?: return
            callbacks.remove(id)?.invoke(ok)
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun send(to: String, body: String, done: (Boolean) -> Unit): Boolean {
        val number = PhoneNumbers.digits(to)
        if (!PhoneNumbers.isGuardianNumber(number)) return false
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return false
        val id = UUID.randomUUID().toString()
        return try {
            val texts = sms.divideMessage(body)
            val sent = ArrayList(texts.map {
                PendingIntent.getBroadcast(
                    context, requestCode++, Intent(action).setPackage(context.packageName).putExtra(EXTRA_ID, id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            })
            parts.expect(id, texts.size)
            callbacks[id] = done
            sms.sendMultipartTextMessage(number, null, texts, sent, null)
            true
        } catch (e: RuntimeException) {
            parts.forget(id)
            callbacks.remove(id)
            false
        }
    }

    fun close() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private companion object {
        const val EXTRA_ID = "id"
    }
}
