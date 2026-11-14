// 차 토큰 암호화의 Android 쪽 — Android Keystore 의 AES-256-GCM 키로 감싸고 푸는 TokenCipher 와 SharedPreferences 를 잇는 KeyValue(새 의존성 없음)
package com.example.guardian.car

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 키는 앱 밖으로 나가지 않는 Keystore 키다(`androidx.security-crypto` 는 지원 중단이라 쓰지 않는다 — 감사 하1).
 * 저장 형식 `v1:<IV base64>:<암호문+태그 base64>`. IV 는 Keystore 가 매번 새로 만든다(무작위 암호화 강제 기본값).
 * 푸는 쪽은 키를 새로 만들지 않는다 — 키가 사라졌으면 예외를 던지고 [CarPairStore] 가 토큰을 버린다.
 * [미검증] AAOS 실차 · 에뮬레이터에서 키 생성 · 앱 업데이트 뒤 복호화.
 */
class KeystoreTokenCipher(private val alias: String = ALIAS) : TokenCipher {
    /** 있는 키로 감싸다 실패하면(키는 있으나 쓸 수 없음 — 키 저장소 손상 등) 그 키를 지우고 새 키로 한 번만 다시 감싼다(app-qa 하1). */
    override fun encrypt(plain: String): String =
        TokenSeal.withOneKeyReset(seal = { sealOnce(plain) }, resetKey = {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
        })

    private fun sealOnce(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, existingKey() ?: newKey())
        val sealed = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "$VERSION:${b64(c.iv)}:${b64(sealed)}"
    }

    override fun decrypt(sealed: String): String {
        val parts = sealed.split(':')
        require(parts.size == 3 && parts[0] == VERSION) { "형식 아님" }
        val key = existingKey() ?: throw IllegalStateException("키 없음")
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(parts[1])))
        return String(c.doFinal(Base64.getDecoder().decode(parts[2])), Charsets.UTF_8)
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null) as? SecretKey

    private fun newKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "guardian_car_relay_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION = "v1"
    }
}

/**
 * SharedPreferences 를 [KeyValue] 로. 저장하는 값은 토큰 · 켬 기억(wasOn) 둘뿐이라 쓰기는 commit(동기 디스크 쓰기) —
 * 짝짓기 직후 전원이 꺼져도 남게(코드 리뷰 11-09). 부르는 곳은 `guardian-tick` 스레드다(화면 스레드 아님). 지우기는 apply 그대로.
 */
class PrefsKeyValue(private val prefs: SharedPreferences) : KeyValue {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getBoolean(key: String): Boolean = prefs.getBoolean(key, false)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).commit() }
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).commit() }
    override fun remove(vararg keys: String) = prefs.edit().apply { keys.forEach { remove(it) } }.apply()
}
