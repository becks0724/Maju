// 최소 JSON 인코더 · 파서 — 외부 라이브러리 없이 프로토콜 직렬화에 쓴다(객체는 LinkedHashMap, 정수는 Long, 실수는 Double)
package com.example.guardian.shared.protocol

class JsonException(message: String) : RuntimeException(message)

object Json {
    /** 파서가 받는 최대 중첩 깊이 — 넘으면 [JsonException](스택 넘침 방지). */
    const val MAX_DEPTH = 64

    /**
     * null · Boolean · Number · String · Map<String, *> · List<*> 만 받는다.
     * NaN · 무한은 null 로, 짝 없는 서로게이트는 U+FFFD 로 내보낸다(한 값 때문에 sync 전체가 깨지지 않게).
     */
    fun encode(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (!p.end()) throw JsonException("끝에 남은 글자 ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("객체가 아님")

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> number(sb, v)
            is Float -> number(sb, v.toDouble())
            is String -> string(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    string(sb, k as? String ?: throw JsonException("키는 문자열"))
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }
                sb.append(']')
            }
            else -> throw JsonException("지원하지 않는 형 ${v::class.simpleName}")
        }
    }

    private fun number(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()).append(".0") else sb.append(d.toString())
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                sb.append(c).append(s[i + 1]); i += 2; continue
            }
            i++
            if (Character.isSurrogate(c)) { sb.append('\uFFFD'); continue }
            when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0
        private var depth = 0
        private fun enter() { if (++depth > MAX_DEPTH) err("중첩이 ${MAX_DEPTH} 단계를 넘음") }
        fun end() = pos >= s.length
        fun ws() { while (pos < s.length && s[pos] in " \t\r\n") pos++ }
        private fun err(msg: String): Nothing = throw JsonException("$msg (위치 $pos)")

        fun value(): Any? {
            if (end()) err("값이 없음")
            return when (s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) err("알 수 없는 값")
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            enter()
            pos++; ws()
            if (pos < s.length && s[pos] == '}') { pos++; depth--; return m }
            while (true) {
                ws()
                if (end() || s[pos] != '"') err("키가 필요")
                val k = str()
                ws()
                if (end() || s[pos] != ':') err("콜론이 필요")
                pos++; ws()
                m[k] = value()
                ws()
                if (end()) err("객체가 닫히지 않음")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; depth--; return m }
                    else -> err("쉼표나 닫는 괄호가 필요")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            enter()
            pos++; ws()
            if (pos < s.length && s[pos] == ']') { pos++; depth--; return l }
            while (true) {
                ws()
                l += value()
                ws()
                if (end()) err("배열이 닫히지 않음")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; depth--; return l }
                    else -> err("쉼표나 닫는 괄호가 필요")
                }
            }
        }

        private fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (end()) err("문자열이 닫히지 않음")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (end()) err("이스케이프가 끊김")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) err("\\u 가 짧음")
                                val hex = s.substring(pos, pos + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) err("\\u 형식")
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> err("알 수 없는 이스케이프 $e")
                        }
                    }
                    c.code < 0x20 -> err("문자열 안 제어 문자")
                    else -> sb.append(c)
                }
            }
        }

        /** RFC 8259 수 문법 그대로 — `.5` · `01` · `1.` · `-` · `1e` 는 거부. */
        private fun num(): Any {
            val start = pos
            fun digits(): Int { val b = pos; while (pos < s.length && s[pos].isAsciiDigit()) pos++; return pos - b }
            if (pos < s.length && s[pos] == '-') pos++
            if (pos >= s.length || !s[pos].isAsciiDigit()) err("수가 아님")
            if (s[pos] == '0') {
                pos++
                if (pos < s.length && s[pos].isAsciiDigit()) err("앞자리 0")
            } else {
                digits()
            }
            var isDouble = false
            if (pos < s.length && s[pos] == '.') {
                isDouble = true; pos++
                if (digits() == 0) err("소수점 뒤 숫자가 없음")
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true; pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (digits() == 0) err("지수 숫자가 없음")
            }
            val t = s.substring(start, pos)
            val v: Any = if (isDouble) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
            if (v is Double && v.isInfinite()) err("무한이 되는 수")
            return v
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}

/** 객체 맵에서 형을 확인하며 꺼낸다. 형이 틀리거나 필수 필드가 없으면 [JsonException]. */
class JsonObj(val map: Map<String, Any?>) {
    private fun missing(k: String): Nothing = throw JsonException("필드 없음 $k")

    fun str(k: String): String = strOrNull(k) ?: missing(k)
    fun strOrNull(k: String): String? = when (val v = map[k]) { null -> null; is String -> v; else -> throw JsonException("$k 는 문자열") }
    fun long(k: String): Long = longOrNull(k) ?: missing(k)
    fun longOrNull(k: String): Long? = when (val v = map[k]) {
        null -> null
        is Long -> v
        is Int -> v.toLong()
        is Double -> if (v == Math.floor(v)) v.toLong() else throw JsonException("$k 는 정수")
        else -> throw JsonException("$k 는 정수")
    }
    fun int(k: String): Int = long(k).toInt()
    fun intOrNull(k: String): Int? = longOrNull(k)?.toInt()
    fun double(k: String): Double = doubleOrNull(k) ?: missing(k)
    fun doubleOrNull(k: String): Double? = when (val v = map[k]) { null -> null; is Number -> v.toDouble(); else -> throw JsonException("$k 는 수") }
    fun bool(k: String): Boolean = boolOrNull(k) ?: missing(k)
    fun boolOrNull(k: String): Boolean? = when (val v = map[k]) { null -> null; is Boolean -> v; else -> throw JsonException("$k 는 참거짓") }

    @Suppress("UNCHECKED_CAST")
    fun objOrNull(k: String): JsonObj? = when (val v = map[k]) { null -> null; is Map<*, *> -> JsonObj(v as Map<String, Any?>); else -> throw JsonException("$k 는 객체") }
    fun obj(k: String): JsonObj = objOrNull(k) ?: missing(k)
    fun list(k: String): List<Any?> = when (val v = map[k]) { null -> missing(k); is List<*> -> v; else -> throw JsonException("$k 는 배열") }
    fun listOrEmpty(k: String): List<Any?> = if (map[k] == null) emptyList() else list(k)

    inline fun <reified E : Enum<E>> enum(k: String): E = enumOrNull<E>(k) ?: throw JsonException("필드 없음 $k")
    inline fun <reified E : Enum<E>> enumOrNull(k: String): E? =
        strOrNull(k)?.let { s -> enumValues<E>().firstOrNull { it.name == s } ?: throw JsonException("$k 값 $s") }
}
