package com.aida0710.chattranslator

/** 同じスタイルの文字列の区間。S は Minecraft では Style (テストでは色の名前など) */
data class Span<S>(val style: S, val text: String)

/**
 * ツールチップの色の付いた区間を <s1>…</s1> のタグで囲んで翻訳エンジンに渡し、訳文のタグから色を付け直す
 * (Minecraft 非依存なのでテスト可能)。
 * § の書式コードは訳す前に取り除くため、タグが無いと訳された語句 ("8 blocks" → "8ブロック") の色が失われる。
 */
object StyleMarkup {
    class Encoded<S>(val text: String, val styles: List<S>)

    /** <s1> </s1> のほか、モデルが崩した "<S1 >" "</ s1>" も受け付ける */
    private val TAG = Regex("<\\s*(/?)\\s*[sS](\\d+)\\s*>")

    /** 原文にあると、タグや HTML/XML の文字参照と区別できなくなる文字 */
    private const val MARKUP_CHARS = "<>&"

    fun hasTags(text: String): Boolean = TAG.containsMatchIn(text)

    /**
     * base 以外のスタイルの区間をタグで囲む。色の区間が無い、またはタグと紛らわしい文字を含むなら null (平文で訳す)。
     * 空白だけの区間は色が見えないので、前後が同じスタイルならその区間に含め、そうでなければ base にする。
     */
    fun <S> encode(spans: List<Span<S>>, base: S): Encoded<S>? {
        if (spans.any { s -> s.text.any { it in MARKUP_CHARS } }) return null
        val merged = normalize(spans, base)
        if (merged.none { it.style != base }) return null
        val styles = mutableListOf<S>()
        val text = buildString {
            for (s in merged) {
                if (s.style == base) { append(s.text); continue }
                // 前後の空白はタグの外に出す (normalize の後なので、色の区間は空白だけではない)
                styles += s.style
                val n = styles.size
                append(s.text.takeWhile { it.isWhitespace() })
                append("<s").append(n).append('>').append(s.text.trim()).append("</s").append(n).append('>')
                append(s.text.takeLastWhile { it.isWhitespace() })
            }
        }
        return Encoded(text, styles)
    }

    /**
     * 訳文のタグをスタイルに戻す。タグの入れ子・閉じ忘れ・知らない番号があれば null。
     * 一部のタグが消えただけならその語句を base にして受け入れるが、すべて消えていれば null
     */
    fun <S> decode(translated: String, encoded: Encoded<S>, base: S): List<Span<S>>? {
        val text = unescape(translated)
        val out = mutableListOf<Span<S>>()
        var open = 0
        var seen = false
        var pos = 0
        for (m in TAG.findAll(text)) {
            val id = m.groupValues[2].toIntOrNull() ?: return null
            if (id !in 1..encoded.styles.size) return null
            out += Span(if (open == 0) base else encoded.styles[open - 1], text.substring(pos, m.range.first))
            if (m.groupValues[1] == "/") {
                if (open != id) return null
                open = 0
            } else {
                if (open != 0) return null
                open = id
                seen = true
            }
            pos = m.range.last + 1
        }
        if (open != 0 || !seen) return null
        out += Span(base, text.substring(pos))
        return out.filter { it.text.isNotEmpty() }
    }

    /** タグを取り除いた訳文 (タグが崩れたときに平文として使う) */
    fun strip(translated: String): String = unescape(TAG.replace(translated, ""))

    /** 文字の数が最も多いスタイル (訳文でタグの外に出た語句の色に使う) */
    fun <S> dominant(spans: List<Span<S>>): S {
        val letters = LinkedHashMap<S, Int>()
        for (s in spans) letters.merge(s.style, s.text.count { it.isLetter() }, Int::plus)
        return letters.maxBy { it.value }.key
    }

    /** spans を連結した文字列のうち range の部分 */
    fun <S> slice(spans: List<Span<S>>, range: IntRange): List<Span<S>> {
        val out = mutableListOf<Span<S>>()
        var offset = 0
        for (s in spans) {
            val from = maxOf(range.first, offset)
            val to = minOf(range.last + 1, offset + s.text.length)
            if (from < to) out += Span(s.style, s.text.substring(from - offset, to - offset))
            offset += s.text.length
        }
        return out
    }

    private fun <S> normalize(spans: List<Span<S>>, base: S): List<Span<S>> {
        val parts = spans.filter { it.text.isNotEmpty() }
        val styled = parts.mapIndexed { i, s ->
            if (s.text.isNotBlank()) return@mapIndexed s
            val prev = parts.subList(0, i).lastOrNull { it.text.isNotBlank() }?.style
            val next = parts.subList(i + 1, parts.size).firstOrNull { it.text.isNotBlank() }?.style
            Span(if (prev != null && prev == next) prev else base, s.text)
        }
        val out = mutableListOf<Span<S>>()
        for (s in styled) {
            val last = out.lastOrNull()
            if (last != null && last.style == s.style) out[out.size - 1] = Span(s.style, last.text + s.text) else out += s
        }
        return out
    }

    /** DeepL (XML) と Google (HTML) が訳文に入れる文字参照を戻す。原文には & が無いので、訳文の & は文字参照だけ */
    private fun unescape(text: String): String =
        if ('&' !in text) text
        else Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);").replace(text) { m ->
            when (val name = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> runCatching {
                    val code = if (name.startsWith("#x")) name.substring(2).toInt(16) else name.substring(1).toInt()
                    String(Character.toChars(code))
                }.getOrDefault(m.value)
            }
        }
}
