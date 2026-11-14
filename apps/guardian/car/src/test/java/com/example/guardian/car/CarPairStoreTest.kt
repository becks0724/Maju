// 차 짝 저장 시험 — 토큰 암호화 저장 · 평문 이주 · 복호화 실패 시 새 짝짓기 · 켬 기억(wasOn) 저장 · 복원 · 연결 끊기 지움(Keystore 는 가짜 암호로 갈음)
package com.example.guardian.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 메모리 저장소 — 앱의 SharedPreferences 자리. */
class MemKeyValue : KeyValue {
    val map = HashMap<String, Any>()
    override fun getString(key: String) = map[key] as? String
    override fun getBoolean(key: String) = map[key] as? Boolean ?: false
    override fun putString(key: String, value: String) { map[key] = value }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun remove(vararg keys: String) { keys.forEach { map.remove(it) } }
}

/** 가짜 암호 — 키 번호가 같아야 풀린다(키가 바뀌면 = Keystore 키가 사라진 것과 같다). [broken] 이면 감싸기도 실패. */
class FakeCipher(private val keyId: Int = 1, private val broken: Boolean = false) : TokenCipher {
    override fun encrypt(plain: String): String {
        check(!broken) { "키 저장소 고장" }
        return "k$keyId:" + plain.reversed().map { (it.code + keyId).toChar() }.joinToString("")
    }
    override fun decrypt(sealed: String): String {
        val prefix = "k$keyId:"
        require(sealed.startsWith(prefix)) { "다른 키" }
        return sealed.removePrefix(prefix).map { (it.code - keyId).toChar() }.joinToString("").reversed()
    }
}

class CarPairStoreTest {
    private val token = "0123456789abcdef0123456789abcdef"

    private fun MemKeyValue.holdsPlain(t: String) = map.values.any { it is String && it.contains(t) }

    @Test fun tokenIsStoredSealedAndReadBackAfterRestart() {
        val kv = MemKeyValue()
        CarPairStore(kv, FakeCipher()).saveToken(token)
        assertFalse("저장소에 평문 토큰이 없다", kv.holdsPlain(token))
        assertNull(kv.getString(CarPairStore.KEY_TOKEN_PLAIN))
        assertTrue(kv.getString(CarPairStore.KEY_TOKEN_SEALED) != null)
        // 다시 켠 앱(새 인스턴스)이 풀어 읽는다
        assertEquals(token, CarPairStore(kv, FakeCipher()).token())
    }

    @Test fun legacyPlainTokenIsMigratedAndPlainRemoved() {
        val kv = MemKeyValue().apply { putString(CarPairStore.KEY_TOKEN_PLAIN, token) }
        val logs = ArrayList<String>()
        val store = CarPairStore(kv, FakeCipher()) { logs += it }
        assertEquals("짝은 그대로 이어 간다", token, store.token())
        assertNull("평문은 지운다", kv.getString(CarPairStore.KEY_TOKEN_PLAIN))
        assertFalse(kv.holdsPlain(token))
        assertEquals(token, CarPairStore(kv, FakeCipher()).token())
        assertFalse("기록에 토큰 값이 없다", logs.any { it.contains(token) })
    }

    @Test fun legacyMigrationFailureDropsThePlainTokenForNewPairing() {
        val kv = MemKeyValue().apply { putString(CarPairStore.KEY_TOKEN_PLAIN, token) }
        val store = CarPairStore(kv, FakeCipher(broken = true))
        assertNull("감싸지 못하면 버리고 새 짝짓기", store.token())
        assertTrue("평문도 남기지 않는다", kv.map.isEmpty())
        assertEquals(false to false, CarPairStore.resumeState(kv))
    }

    @Test fun decryptFailureDropsTheTokenWithoutCrashing() {
        val kv = MemKeyValue()
        CarPairStore(kv, FakeCipher(keyId = 1)).saveToken(token)
        val logs = ArrayList<String>()
        // 키가 사라져 새 키(2)로 바뀐 것과 같다
        val store = CarPairStore(kv, FakeCipher(keyId = 2)) { logs += it }
        assertNull("토큰을 버린다 → 정차 중 새 짝짓기", store.token())
        assertNull("못 푸는 값은 지운다", kv.getString(CarPairStore.KEY_TOKEN_SEALED))
        assertFalse(logs.any { it.contains(token) })
        // 깨진 값도 같다
        kv.putString(CarPairStore.KEY_TOKEN_SEALED, "깨진 값")
        assertNull(CarPairStore(kv, FakeCipher()).token())
        assertNull(kv.getString(CarPairStore.KEY_TOKEN_SEALED))
    }

    @Test fun encryptFailureKeepsTokenInMemoryOnly() {
        val kv = MemKeyValue()
        val store = CarPairStore(kv, FakeCipher(broken = true))
        store.saveToken(token)
        assertEquals("이번 실행은 짝을 쓴다", token, store.token())
        assertFalse("평문으로 저장하지 않는다", kv.holdsPlain(token))
        assertNull(CarPairStore(kv, FakeCipher()).token())
    }

    /** 401 — 토큰만 지운다(켬 기억은 남지만 토큰이 없어 되살리지 않는다). */
    @Test fun forgetTokenKeepsWasOnButResumeNeedsAPair() {
        val kv = MemKeyValue()
        val store = CarPairStore(kv, FakeCipher())
        store.saveToken(token)
        store.rememberPower(linkOk = true, powerOn = true)
        store.forgetToken()
        assertNull(store.token())
        val (wasOn, paired) = CarPairStore.resumeState(kv)
        assertTrue(wasOn)
        assertFalse(CarResume.shouldStart(wasOn, paired))
    }

    /**
     * 리뷰 3차 — 켬 기억(wasOn) 저장 · 복원 경로. 차가 저장한 값을 다시 켠 프로세스(새 인스턴스 · 수신자 판단)가 그대로 읽어
     * [CarResume.shouldStart] 로 되살린다. sync 전(linkOk 거짓)의 꺼짐은 저장값을 덮지 않는다.
     */
    @Test fun wasOnIsSavedAndRestoredForResume() {
        val kv = MemKeyValue()
        val store = CarPairStore(kv, FakeCipher())
        store.saveToken(token)
        assertFalse("링크 전에는 쓰지 않는다", store.rememberPower(linkOk = false, powerOn = true))
        assertEquals(false to true, CarPairStore.resumeState(kv))
        assertTrue(store.rememberPower(linkOk = true, powerOn = true))
        assertFalse("같은 값은 다시 쓰지 않는다", store.rememberPower(linkOk = true, powerOn = true))

        // 재부팅 · 업데이트 뒤 — 수신자가 읽는 값
        val (wasOn, paired) = CarPairStore.resumeState(kv)
        assertTrue(wasOn); assertTrue(paired)
        assertTrue(CarResume.shouldStart(wasOn, paired))

        // 다시 켠 차 — 시작 직후(sync 전) 꺼짐 기본값으로 덮지 않는다
        val restarted = CarPairStore(kv, FakeCipher())
        assertTrue(restarted.wasOn)
        assertFalse(restarted.rememberPower(linkOk = false, powerOn = false))
        assertTrue(CarPairStore.resumeState(kv).first)
        // 휴대폰 설정을 받은 뒤 꺼짐이면 저장 → 되살리지 않음
        assertTrue(restarted.rememberPower(linkOk = true, powerOn = false))
        assertFalse(CarResume.shouldStart(CarPairStore.resumeState(kv).first, true))
    }

    /** 옛 빌드의 평문 토큰만 있는 상태로 업데이트되어도(MY_PACKAGE_REPLACED) 짝 있음으로 본다 — 이주는 서비스가 뜬 뒤 한다. */
    @Test fun resumeSeesLegacyPlainTokenAsPaired() {
        val kv = MemKeyValue().apply { putString(CarPairStore.KEY_TOKEN_PLAIN, token); putBoolean(CarPairStore.KEY_WAS_ON, true) }
        assertEquals(true to true, CarPairStore.resumeState(kv))
    }

    /** app-qa 하1 — 있는 키로 감싸기가 실패하면 키를 지우고 새 키로 한 번만 다시 감싼다. */
    @Test fun sealRetriesOnceAfterKeyReset() {
        val order = ArrayList<String>()
        var attempts = 0
        val sealed = TokenSeal.withOneKeyReset(
            seal = { attempts++; order += "감싸기$attempts"; if (attempts == 1) throw IllegalStateException("키 쓸 수 없음") else "ok" },
            resetKey = { order += "키 지움" },
        )
        assertEquals("ok", sealed)
        assertEquals(listOf("감싸기1", "키 지움", "감싸기2"), order)

        // 두 번 다 실패하면 한 번만 다시 시도하고 예외 → 저장소는 메모리에만 둔다
        var tries = 0
        var resets = 0
        val failed = runCatching { TokenSeal.withOneKeyReset(seal = { tries++; error("고장") }, resetKey = { resets++ }) }
        assertTrue(failed.isFailure)
        assertEquals(2, tries)
        assertEquals(1, resets)

        // 첫 시도가 되면 키를 건드리지 않는다
        var touched = false
        assertEquals("a", TokenSeal.withOneKeyReset(seal = { "a" }, resetKey = { touched = true }))
        assertFalse(touched)
    }

    @Test fun clearRemovesTokenAndWasOn() {
        val kv = MemKeyValue()
        val store = CarPairStore(kv, FakeCipher())
        store.saveToken(token)
        store.rememberPower(linkOk = true, powerOn = true)
        store.clear()
        assertNull(store.token())
        assertFalse(store.wasOn)
        assertTrue("저장소가 비었다", kv.map.isEmpty())
        assertNull(CarPairStore(kv, FakeCipher()).token())
    }
}
