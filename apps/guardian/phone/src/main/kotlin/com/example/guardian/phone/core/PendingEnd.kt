// 동의 철회 때 실패한 pair/end 를 다시 부르는 「끝낼 토큰」 규칙(감사 하2 · 계약 §3.2) — 토큰과 저장 시각만 남기고 개인정보는 남기지 않는다
package com.example.guardian.phone.core

/** 끝낼 토큰 저장소 — 「모두 지우기」([ResetStore.clearAll])와 따로 둔다(철회가 저장을 지운 뒤에 남겨야 해서). */
interface PendingEndStore {
    var pendingEnds: List<PendingEnd.Entry>
}

object PendingEnd {
    /** [savedAtWallMs] 는 휴대폰 벽시계 — 앱 재시작 · 재부팅을 넘어 24시간을 재야 해서 단조 시계를 쓸 수 없다. */
    data class Entry(val token: String, val savedAtWallMs: Long)

    /** pair/end 한 번의 결과. [ENDED] 성공 · [GONE] 401(이미 없는 짝) · [FAILED] 그 밖(네트워크 · 시간 초과 · 5xx 등). */
    enum class Result { ENDED, GONE, FAILED }

    /** 중계의 짝 수명(계약 §3) — 이보다 오래된 토큰은 중계에서도 이미 사라졌다. */
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** 끝내지 못한 토큰을 몇 개까지 둘지 — 철회를 거듭하는 일은 드물다. */
    const val MAX_ENTRIES = 8

    /**
     * 짝 끝내기를 한 번 부른다. 토큰을 **부르기 전에** 남기고 성공 · 401 이면 그 토큰만 지운다 — 호출 도중 프로세스가 죽어도
     * 다음 실행에서 다시 부를 수 있게(코드 리뷰 2026-11-09 하). 실패면 그대로 남는다.
     */
    fun attempt(store: PendingEndStore, token: String, nowWallMs: Long, call: (String) -> Result): Result {
        add(store, token, nowWallMs)
        return call(token).also { r -> if (r != Result.FAILED) store.pendingEnds = store.pendingEnds.filter { it.token != token } }
    }

    fun add(store: PendingEndStore, token: String, nowWallMs: Long) {
        store.pendingEnds = (store.pendingEnds.filter { it.token != token } + Entry(token, nowWallMs)).takeLast(MAX_ENTRIES)
    }

    /**
     * 남은 토큰마다 다시 부른다(앱 실행 · 서비스 시작 · 중계 다시 닿음). 성공 · 401 이면 지우고, 24시간이 지났으면 부르지 않고 지운다.
     * [activeToken](지금 짝의 토큰)과 같은 토큰은 부르지 않고 지운다 — 새 짝을 끝내는 일이 없게.
     */
    fun retry(store: PendingEndStore, activeToken: String?, nowWallMs: Long, call: (String) -> Result): List<Result> {
        val results = ArrayList<Result>()
        val keep = ArrayList<Entry>()
        for (e in store.pendingEnds) {
            if (nowWallMs - e.savedAtWallMs >= MAX_AGE_MS || e.token == activeToken) continue
            val r = call(e.token)
            results += r
            if (r == Result.FAILED) keep += e
        }
        store.pendingEnds = keep
        return results
    }

    /** 저장 형식 — 한 줄에 `토큰 저장시각`. 깨진 줄은 버린다. */
    fun encode(list: List<Entry>): String = list.joinToString("\n") { "${it.token} ${it.savedAtWallMs}" }

    fun decode(text: String?): List<Entry> = text.orEmpty().lines().mapNotNull { line ->
        val parts = line.split(' ')
        val at = parts.getOrNull(1)?.toLongOrNull()
        if (parts.size == 2 && parts[0].isNotEmpty() && at != null) Entry(parts[0], at) else null
    }
}
