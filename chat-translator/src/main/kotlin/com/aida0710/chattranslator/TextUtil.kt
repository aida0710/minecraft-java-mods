package com.aida0710.chattranslator

object TextUtil {
    private val FORMAT_CODE = Regex("§[0-9a-fk-orA-FK-OR]")
    private val JAPANESE = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\uff66-\\uff9f]")
    private val LATIN_WORD = Regex("[A-Za-z]{2,}")

    fun stripFormatting(text: String): String = FORMAT_CODE.replace(text, "")

    /** 翻訳する価値がある英文かどうか（日本語を含む・英単語が無い・短すぎる・数値だけ等は除外） */
    fun looksTranslatable(text: String, minLength: Int): Boolean {
        val t = text.trim()
        if (t.length < minLength) return false
        if (JAPANESE.containsMatchIn(t)) return false
        val words = LATIN_WORD.findAll(t).count()
        if (words == 0) return false
        // "1,234/5,000 ❤" のような記号・数値主体の行は除外
        val letters = t.count { it.isLetter() }
        return letters * 2 >= t.count { !it.isWhitespace() }
    }

    /**
     * Hypixel の "[MVP+] Name: message" / "Party > Name: message" 形式を
     * (プレフィックス, 本文) に分ける。分けられなければ prefix は空。
     */
    fun splitChatPrefix(text: String): Pair<String, String> {
        val idx = text.indexOf(": ")
        if (idx in 1..48) {
            val head = text.substring(0, idx)
            // プレフィックスにはランク表記・名前・チャンネル名程度しか来ない想定
            if (head.count { it == ' ' } <= 5) return head to text.substring(idx + 2)
        }
        return "" to text
    }
}
