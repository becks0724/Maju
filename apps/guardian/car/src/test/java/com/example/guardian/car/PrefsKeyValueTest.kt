// 차 저장소 쓰기 방식 시험 — 토큰 · 켬 기억(wasOn) 저장은 commit(동기 디스크 쓰기)으로, 짝짓기 직후 전원이 꺼져도 남게(코드 리뷰 11-09 차 하(의심))
package com.example.guardian.car

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsKeyValueTest {
    /** 쓰기 끝맺음(apply · commit)을 기록하는 가짜. 값은 끝맺을 때 반영한다. */
    private class FakePrefs : SharedPreferences {
        val values = HashMap<String, Any?>()
        val finishes = ArrayList<String>()

        inner class Ed : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { values.clear() }
            private fun finish(how: String) { removes.forEach { values.remove(it) }; values.putAll(puts); finishes += how }
            override fun commit(): Boolean { finish("commit"); return true }
            override fun apply() { finish("apply") }
        }

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as MutableSet<String>? ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String) = key in values
        override fun edit(): SharedPreferences.Editor = Ed()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = "s:$plain"
        override fun decrypt(sealed: String) = sealed.removePrefix("s:")
    }

    @Test fun tokenAndWasOnAreCommitted() {
        val prefs = FakePrefs()
        val store = CarPairStore(PrefsKeyValue(prefs), PlainCipher())
        store.saveToken("tok")
        assertEquals("토큰 저장은 commit", listOf("commit"), prefs.finishes)
        assertEquals("s:tok", prefs.values[CarPairStore.KEY_TOKEN_SEALED])
        prefs.finishes.clear()
        store.rememberPower(linkOk = true, powerOn = true)
        assertEquals("켬 기억 저장은 commit", listOf("commit"), prefs.finishes)
        assertEquals(true, prefs.values[CarPairStore.KEY_WAS_ON])
    }
}
