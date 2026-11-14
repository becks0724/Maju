// G1 금지어 공개 목록과 고지 문장 예외 규칙 — :shared 와 앱 모듈 시험이 같은 목록으로 화면 · 음성 · 문자 문구를 검사한다
package com.example.guardian.shared.text

/**
 * 화면 · 음성 · 문자에 쓰지 않는 말(G1 — 판정어 · 질병 이름 · 차량 제어 · 효과 약속).
 * 시험 전용이 아니라 앱 모듈 시험도 가져다 쓰도록 main 소스에 둔다.
 */
object ForbiddenWords {
    val words: List<String> = listOf(
        "실신", "기절", "의식 소실", "의식소실", "심정지", "맥박 소실", "맥박소실", "감지", "예측", "진단",
        "사고 예방", "사고를 막", "생명을 지켜", "자동으로 119", "차를 세워", "비상등을 켜",
        "비정상", "위험", "이상",
        // 질병 이름(G1 — 화면 · 문자에 보이지 않는다)
        "부정맥", "서맥", "빈맥", "심방세동", "심실세동", "저혈압", "고혈압", "뇌졸중", "심근경색", "협심증", "당뇨", "저혈당", "뇌전증", "간질", "발작",
    )

    /** G1 이 그대로 두라고 한 고지 문구. 「진단 · 응급 대응을 대신하지 않습니다」 처럼 부정문으로만 금지어를 품는다. */
    val exemptPhrases: Set<Phrase> = setOf(Phrase.NOTICE_NOT_MEDICAL)

    /** 예외 고지 문장 원문. 긴 글 안에 이 문장이 그대로 있으면 그 부분만 빼고 검사한다. */
    val exemptSentences: List<String> = exemptPhrases.map { it.ko }

    /** [text] 에 든 금지어(예외 고지 문장을 지운 나머지 기준). 비어 있으면 통과. */
    fun violations(text: String): List<String> {
        val rest = exemptSentences.fold(text) { acc, s -> acc.replace(s, " ") }
        return words.filter { rest.contains(it) }
    }

    /** 문구 표 한 줄 검사 — 예외 문구는 늘 통과. */
    fun violations(p: Phrase): List<String> = if (p in exemptPhrases) emptyList() else violations(p.ko)
}
