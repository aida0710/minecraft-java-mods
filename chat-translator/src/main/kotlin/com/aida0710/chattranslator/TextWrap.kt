package com.aida0710.chattranslator

/** 訳文を元の lore の幅に合わせて折り返す (Minecraft 非依存なのでテスト可能) */
object TextWrap {
    /** 英数字の連続 (数値・英単語・"12,000" や "+50%" など) は途中で折り返さない */
    private val ATOM = Regex("[A-Za-z0-9][A-Za-z0-9,.%+\\-'/]*|\\s+|.")

    /** 行頭に来ると読みにくい文字 (句読点・閉じ括弧など) */
    private const val NO_LINE_START = "、。，．,.!！?？）)」』】〉》ー・:：;；%"

    fun wrap(text: String, maxWidth: Int, width: (String) -> Int): List<String> {
        val lines = mutableListOf<String>()
        val cur = StringBuilder()
        for (m in ATOM.findAll(text)) {
            val atom = m.value
            if (cur.isEmpty() && atom.isBlank()) continue
            if (cur.isNotEmpty() && width(cur.toString() + atom) > maxWidth && atom[0] !in NO_LINE_START && !atom.isBlank()) {
                lines += cur.toString().trimEnd()
                cur.clear()
            }
            cur.append(atom)
        }
        if (cur.isNotBlank()) lines += cur.toString().trimEnd()
        return lines
    }
}
