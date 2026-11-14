// debug 전용 생체 기록 — Sensor SDK 점의 필드를 하나씩 안전하게 읽는 보조(키가 없으면 getValue 가 NPE 를 던진다)와 종류별 1회 사건 도구(AAR 없이 시험 가능)
package com.example.guardian.watch.probe

import java.util.Collections

/** 같은 (종류, 상세) 사건은 한 번만 남긴다 — 25Hz 스트림에서 같은 오류가 운전 내내 events.csv 를 채우지 않게. */
class OnceEvents(private val emit: (kind: String, detail: String) -> Unit) {
    private val seen = Collections.synchronizedSet(HashSet<String>())

    fun once(kind: String, detail: String) {
        if (seen.add("$kind\u0000$detail")) emit(kind, detail)
    }
}

/**
 * `DataPoint.getValue(key)` 는 키가 없을 때 null 이 아니라 NPE 를 던진다(PM javap -c 확인 11-07). 그래서 필드별로 [read] 를 감싸,
 * 없는 필드만 빈칸(null)으로 두고 같은 점의 나머지 필드는 살린다. 빠진 키는 이름별로 1회 `sdk_key_missing` 사건을 남긴다.
 */
class SdkFieldReader(private val once: OnceEvents) {
    fun <T : Any> read(key: String, get: () -> T?): T? = try {
        get()
    } catch (e: RuntimeException) {
        once.once(KIND_KEY_MISSING, "$key ${e.javaClass.simpleName}")
        null
    }

    companion object {
        const val KIND_KEY_MISSING = "sdk_key_missing"
    }
}
