// 시험 공용 — PairStore 가 쓰는 만큼만 흉내 낸 메모리 SharedPreferences(문자열 · 불 · 수 · 삭제 · commit)
package com.example.guardian.watch

import android.content.SharedPreferences

internal class MemoryPrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    override fun getAll(): Map<String, *> = map
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val put = HashMap<String, Any?>()
        val drop = HashSet<String>()
        override fun putString(key: String, value: String?) = apply { put[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { put[key] = values }
        override fun putInt(key: String, value: Int) = apply { put[key] = value }
        override fun putLong(key: String, value: Long) = apply { put[key] = value }
        override fun putFloat(key: String, value: Float) = apply { put[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { put[key] = value }
        override fun remove(key: String) = apply { drop += key }
        override fun clear() = apply { drop += map.keys }
        override fun commit(): Boolean { drop.forEach(map::remove); map.putAll(put); return true }
        override fun apply() { commit() }
    }
}
