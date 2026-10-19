package com.aida0710.chattranslator

object TextUtil {
    private val FORMAT_CODE = Regex("§[0-9a-fk-orA-FK-OR]")
    private val LATIN_WORD = Regex("[A-Za-z\\u00C0-\\u024F]{2,}")
    /** バニラのチャットの "<Name> " (名前は 3〜16 文字の英数字と _) */
    private val VANILLA_CHAT_PREFIX = Regex("^<[A-Za-z0-9_]{3,16}> ")

    fun stripFormatting(text: String): String = FORMAT_CODE.replace(text, "")

    /**
     * 翻訳する価値がある文かどうか。
     * 短すぎる・数値や記号が主体・すでに翻訳先の言語で書かれている・翻訳元の言語の文字を含まない、ものは除外する。
     */
    fun looksTranslatable(
        text: String,
        minLength: Int,
        source: String = TranslatorConfig.current.sourceCode,
        target: String = TranslatorConfig.current.targetCode,
    ): Boolean {
        val t = text.trim()
        if (t.length < minLength) return false
        // "1,234/5,000 ❤" のような記号・数値主体の行は除外
        val letters = t.count { it.isLetter() }
        if (letters == 0 || letters * 2 < t.count { !it.isWhitespace() }) return false

        val targetScript = Languages.get(target)?.script
        if (targetScript != null && alreadyInScript(t, targetScript)) return false

        val sourceScript = Languages.get(source)?.script ?: return true // 自動検出
        return when (sourceScript) {
            Script.LATIN -> LATIN_WORD.containsMatchIn(t)
            // 日本語は漢字だけの文もある
            Script.JAPANESE -> t.any { Languages.inScript(it, Script.JAPANESE) || Languages.inScript(it, Script.HAN) }
            else -> t.any { Languages.inScript(it, sourceScript) }
        }
    }

    /** ラテン文字の言語どうしは文字では区別できないので判定しない */
    private fun alreadyInScript(t: String, script: Script): Boolean = when (script) {
        Script.LATIN -> false
        // 中国語は漢字のみ。かなを含めば日本語とみなす
        Script.HAN -> t.any { Languages.inScript(it, Script.HAN) } && t.none { Languages.inScript(it, Script.JAPANESE) }
        else -> t.any { Languages.inScript(it, script) }
    }

    /**
     * Hypixel の "[MVP+] Name: message" / "Party > Name: message" 形式と、バニラの "<Name> message" 形式を
     * (プレフィックス, 本文) に分ける。prefix は本文の前の区切り (": " や " ") まで含む。分けられなければ prefix は空。
     */
    fun splitChatPrefix(text: String): Pair<String, String> {
        VANILLA_CHAT_PREFIX.find(text)?.let { return it.value to text.substring(it.value.length) }
        val idx = text.indexOf(": ")
        if (idx in 1..48) {
            val head = text.substring(0, idx)
            // プレフィックスにはランク表記・名前・チャンネル名程度しか来ない想定
            if (head.count { it == ' ' } <= 5) return text.substring(0, idx + 2) to text.substring(idx + 2)
        }
        return "" to text
    }
}
