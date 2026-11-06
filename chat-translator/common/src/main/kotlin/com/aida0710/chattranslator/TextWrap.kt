package com.aida0710.chattranslator

/** 訳文を元の lore の幅に合わせて折り返す (Minecraft 非依存なのでテスト可能) */
object TextWrap {
    /** 英数字の連続 (数値・英単語・"12,000" や "+50%" など) は途中で折り返さない */
    private val ATOM = Regex("[A-Za-z0-9][A-Za-z0-9,.%+\\-'/]*|\\s+|.")

    /** 行頭に来ると読みにくい文字 (句読点・閉じ括弧など) */
    private const val NO_LINE_START = "、。，．,.!！?？）)」』】〉》ー・:：;；%"

    /**
     * 訳文を折り返す幅。原文の段落と同じ行数 ([lines]) に収まる幅を、minWidth (元の幅) から maxWidth の間で選ぶ。
     * 訳文は原文より横に長くなりやすく、元の幅のままだと最後の 1〜2 文字だけが次の行に回る。
     * 行ごとの幅のばらつきで行が増えないよう 1 割の余裕を持たせる
     */
    fun fitWidth(textWidth: Int, lines: Int, minWidth: Int, maxWidth: Int): Int {
        val perLine = if (lines <= 1) textWidth else (textWidth * 11 + lines * 10 - 1) / (lines * 10)
        return perLine.coerceIn(minWidth, maxOf(minWidth, maxWidth))
    }

    fun wrap(text: String, maxWidth: Int, width: (String) -> Int): List<String> =
        ranges(text, maxWidth, width).map { text.substring(it) }

    /** スタイル付きの区間の列を折り返す。行の前後の空白は除く */
    fun <S> wrapSpans(spans: List<Span<S>>, maxWidth: Int, width: (String) -> Int): List<List<Span<S>>> {
        val text = spans.joinToString("") { it.text }
        return ranges(text, maxWidth, width).map { StyleMarkup.slice(spans, it) }
    }

    /** 各行の text 中の範囲 */
    private fun ranges(text: String, maxWidth: Int, width: (String) -> Int): List<IntRange> {
        val lines = mutableListOf<IntRange>()
        var start = -1
        fun close(end: Int) {
            val trimmed = text.substring(start, end).trimEnd()
            if (trimmed.isNotEmpty()) lines += start until start + trimmed.length
        }
        for (m in ATOM.findAll(text)) {
            val atom = m.value
            if (start < 0 && atom.isBlank()) continue
            if (start >= 0 && width(text.substring(start, m.range.last + 1)) > maxWidth && atom[0] !in NO_LINE_START && !atom.isBlank()) {
                close(m.range.first)
                start = -1
            }
            if (start < 0) start = m.range.first
        }
        if (start >= 0) close(text.length)
        return lines
    }
}
