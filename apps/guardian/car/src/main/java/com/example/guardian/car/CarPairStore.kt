// 차의 짝 저장 — 중계 토큰은 TokenCipher(앱은 Android Keystore AES-GCM)로 감싸 저장하고, 옛 평문 토큰은 처음 읽을 때 옮긴 뒤 지운다(보안 감사 하1 · Pleos 보안 원칙)
package com.example.guardian.car

/** 저장소 경계 — 앱은 SharedPreferences([PrefsKeyValue]), 시험은 메모리 지도. */
interface KeyValue {
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun remove(vararg keys: String)
}

/** 암 · 복호화 경계 — 앱은 Android Keystore 키([KeystoreTokenCipher]), 시험은 가짜. 실패하면 예외를 던진다. */
interface TokenCipher {
    fun encrypt(plain: String): String
    fun decrypt(sealed: String): String
}

/** 감싸기 다시 시도 규칙(안드로이드 의존 없음 — 시험용으로 뗐다). */
object TokenSeal {
    /**
     * [seal] 이 예외로 실패하면 [resetKey](키 지우기) 뒤 **한 번만** 다시 감싼다. 그래도 실패하면 두 번째 예외를 던진다
     * (부르는 쪽 [CarPairStore] 는 그때 토큰을 메모리에만 둔다). 풀기 쪽은 키를 새로 만들지 않는다 — 여기를 쓰지 않는다.
     */
    fun <T> withOneKeyReset(seal: () -> T, resetKey: () -> Unit): T =
        try {
            seal()
        } catch (e: Exception) {
            resetKey()
            seal()
        }
}

/**
 * 차가 저장하는 것은 중계 토큰과 「마지막 흐름 켬」(wasOn, 개인 정보 아님 — 평문 그대로) 둘뿐이다.
 * - 토큰은 감싼 값만 저장한다. 키가 사라졌거나(앱 데이터 일부 손상 · 키 저장소 초기화) 복호화가 실패하면 토큰을 버린다 — 차는 정차 중에
 *   스스로 새 짝짓기를 한다(앱이 죽지 않게). 감싸기 자체가 실패하면 그 실행 동안 메모리에만 두고 저장하지 않는다(평문을 남기지 않는다).
 * - 옛 빌드의 평문 토큰([KEY_TOKEN_PLAIN])은 처음 읽을 때 감싸 옮기고 평문은 지운다. 감싸기가 실패해도 평문은 지운다(새 짝짓기).
 * - [log] 에는 값을 싣지 않는다(예외 종류만).
 * 한 스레드(차는 `guardian-tick`)에서만 부른다. 복호화는 처음 한 번만 하고 메모리에 둔다(2초 주기마다 Keystore 를 부르지 않게).
 */
class CarPairStore(private val kv: KeyValue, private val cipher: TokenCipher, private val log: (String) -> Unit = {}) {
    private var loaded = false
    private var cached: String? = null

    /** 마지막으로 저장한 흐름의 켬(부팅 · 업데이트 뒤 되살리기, [CarResume]). */
    var wasOn: Boolean = kv.getBoolean(KEY_WAS_ON)
        private set

    fun token(): String? {
        if (!loaded) load()
        return cached
    }

    fun saveToken(token: String) {
        cached = token
        loaded = true
        try {
            kv.putString(KEY_TOKEN_SEALED, cipher.encrypt(token))
        } catch (e: Exception) {
            kv.remove(KEY_TOKEN_SEALED)
            log("토큰 감싸기 실패 ${e.javaClass.simpleName} — 이번 실행 동안만 메모리에 둔다")
        }
    }

    /** 토큰만 지운다(401 — 짝이 끝남 · 만료). */
    fun forgetToken() {
        kv.remove(KEY_TOKEN_SEALED, KEY_TOKEN_PLAIN)
        cached = null
        loaded = true
    }

    /** 휴대폰 연결 끊기 — 토큰과 켬 기억을 모두 지운다. */
    fun clear() {
        forgetToken()
        kv.remove(KEY_WAS_ON)
        wasOn = false
    }

    /**
     * 흐름 켬을 기억한다 — 휴대폰 설정을 받은 뒤([linkOk] = sync 성공 10초 안)에 값이 바뀔 때만 쓴다.
     * 시작 직후 아직 sync 전의 꺼짐(기본값)으로 저장값을 덮지 않게. 썼으면 true.
     */
    fun rememberPower(linkOk: Boolean, powerOn: Boolean): Boolean {
        if (!linkOk || powerOn == wasOn) return false
        wasOn = powerOn
        kv.putBoolean(KEY_WAS_ON, powerOn)
        return true
    }

    private fun load() {
        loaded = true
        val legacy = kv.getString(KEY_TOKEN_PLAIN)
        if (legacy != null) {
            try {
                kv.putString(KEY_TOKEN_SEALED, cipher.encrypt(legacy))
                cached = legacy
                log("평문 토큰을 암호화 저장으로 옮김")
            } catch (e: Exception) {
                kv.remove(KEY_TOKEN_SEALED)
                cached = null
                log("평문 토큰 옮기기 실패 ${e.javaClass.simpleName} — 버리고 다시 짝짓기")
            }
            kv.remove(KEY_TOKEN_PLAIN)
            return
        }
        val sealed = kv.getString(KEY_TOKEN_SEALED) ?: return
        cached = try {
            cipher.decrypt(sealed)
        } catch (e: Exception) {
            kv.remove(KEY_TOKEN_SEALED)
            log("토큰 복호화 실패 ${e.javaClass.simpleName} — 버리고 다시 짝짓기")
            null
        }
    }

    companion object {
        /** 옛 빌드의 평문 토큰 — 읽어 옮긴 뒤 지운다. */
        const val KEY_TOKEN_PLAIN = "carToken"
        /** Keystore 키로 감싼 토큰. */
        const val KEY_TOKEN_SEALED = "carTokenSealed"
        /** 마지막으로 본 흐름의 켬. 짝을 잃으면 토큰이 지워져 이 값과 상관없이 되살리지 않는다. */
        const val KEY_WAS_ON = "wasOn"

        /** 다시 띄우기 판단용 저장값 — (마지막 흐름 켬, 짝 토큰 있음). 복호화하지 않고 있는지만 본다(수신자가 Keystore 를 부르지 않게). */
        fun resumeState(kv: KeyValue): Pair<Boolean, Boolean> =
            kv.getBoolean(KEY_WAS_ON) to (kv.getString(KEY_TOKEN_SEALED) != null || kv.getString(KEY_TOKEN_PLAIN) != null)
    }
}
