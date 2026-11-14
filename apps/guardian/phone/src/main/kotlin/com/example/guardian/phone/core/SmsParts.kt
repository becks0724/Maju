// 여러 조각으로 나눠 보낸 문자의 조각별 전송 결과를 모아 한 통의 결과로 만든다 — 하나라도 실패면 실패, 모든 조각이 성공이어야 성공
package com.example.guardian.phone.core

/** 한 스레드에서만 쓴다(문자 결과 수신기와 보내기가 같은 엔진 스레드). */
class SmsParts {
    private val left = HashMap<String, Int>()

    /** 문자 [id] 를 [parts] 조각으로 보냈다. */
    fun expect(id: String, parts: Int) { left[id] = parts }

    /** 보내기 요청이 거절됐다 — 결과를 기다리지 않는다. */
    fun forget(id: String) { left.remove(id) }

    /** 조각 하나의 결과. 그 문자의 결과가 이번에 정해지면 true(모두 성공) · false(실패), 아직이거나 이미 정해졌으면 null. */
    fun onPart(id: String, ok: Boolean): Boolean? {
        val n = left[id] ?: return null
        if (!ok) { left.remove(id); return false }
        if (n <= 1) { left.remove(id); return true }
        left[id] = n - 1
        return null
    }
}
