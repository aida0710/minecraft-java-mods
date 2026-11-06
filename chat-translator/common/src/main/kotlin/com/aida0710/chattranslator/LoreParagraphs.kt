package com.aida0710.chattranslator

/**
 * ツールチップの説明文を、まとめて翻訳する段落に分ける (Minecraft 非依存なのでテスト可能)。
 *
 * SkyBlock の lore は 1 つの文を短い行に折り返して書くので、折り返しの続きの行は前の行とつなげて訳す。
 * 一方で「Amount: 3x」「✔ Ability Chat」のような 1 行ずつの項目をつなげると、訳文を折り返し直したときに
 * 元の改行が失われるため、折り返しの続きと分かる行だけをつなげる。
 */
object LoreParagraphs {
    /** text は前後の空白を除いた行。color が同じ文章の行だけをつなげる */
    class Line(val text: String, val prose: Boolean, val color: Any?)

    /** 文の終わり (この後の行は、小文字で始まらない限り新しい文) */
    private const val SENTENCE_END = ".!?。！？:：…"
    /** 文末の閉じ引用符・閉じ括弧 (文の終わりを判定するときに読み飛ばす) */
    private const val CLOSERS = "\"')]」』”’）"
    /** 行頭にあっても箇条書きの記号ではないもの (引用符・括弧の始まり) */
    private const val OPENERS = "\"'([「『“‘（"
    /** 文の途中でしか行末に来ない文字 ("MVP+" のように語の一部になる記号は含めない) */
    private const val DANGLING_END = ",、，"
    /** 文の途中でしか行末に来ない英語の語 (前置詞・接続詞・冠詞など。"Grade: A" と区別するため小文字のときだけ) */
    private val DANGLING_WORDS = setOf(
        "a", "an", "the", "to", "of", "for", "with", "by", "in", "on", "at", "from", "into", "onto",
        "and", "or", "but", "nor", "your", "their", "its", "per", "than", "that", "which", "while", "when", "if", "as",
    )

    /** "Amount: 3x" "Time Between Actions: 48.5s" のような「ラベル: 値」の行 (ラベルは 4 語まで) */
    private val LABEL = Regex("^([^\\s:：][^:：.!?。！？]{0,30})[:：]\\s*\\S")

    /** 段落 (行番号の範囲) に分ける。文章でない行は 1 行の範囲になる */
    fun split(lines: List<Line>, wrapWidth: Int, width: (String) -> Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < lines.size) {
            var j = i + 1
            if (lines[i].prose) {
                while (j < lines.size && lines[j].prose && lines[j].color == lines[i].color &&
                    continues(lines[j - 1].text, lines[j].text, wrapWidth, width)
                ) j++
            }
            out += i until j
            i = j
        }
        return out
    }

    /**
     * next が prev の折り返しの続きか。
     * wrapWidth は元の lore の折り返し幅の目安 (ツールチップで最も長い文章の行の幅)
     */
    fun continues(prev: String, next: String, wrapWidth: Int, width: (String) -> Int): Boolean {
        if (prev.isEmpty() || next.isEmpty() || isListItem(next)) return false
        val endsSentence = prev.trimEnd { it in CLOSERS }.lastOrNull()?.let { it in SENTENCE_END } ?: false
        // 小文字で始まる行 ("when held." など) は文の途中で折り返した続き
        if (next[0].isLowerCase()) return !endsSentence
        if (endsSentence || isLabel(prev) || isLabel(next)) return false
        // "damage to" "+10%," で終わる行は文の途中 (次の行は "Withers." のような固有名詞で始まる続き)
        if (prev.last() in DANGLING_END || prev.substringAfterLast(' ') in DANGLING_WORDS) return true
        // 大文字などで始まる行は、前の行に次の語が入りきらなかった (= 幅で折り返した) ときだけ続きとみなす
        return width(prev + " " + next.substringBefore(' ')) > wrapWidth
    }

    /** 記号や数字で始まる行 ("✔ Ability Chat", "+50 Strength", "6 Attack Damage", "▶ Item") は箇条書き */
    fun isListItem(text: String): Boolean {
        val c = text.firstOrNull() ?: return false
        if (c.isLetter() || c in OPENERS) return false
        // SkyBlock のステータスのアイコン (私用領域の文字) は文中にも現れる
        return Character.getType(c) != Character.PRIVATE_USE.toInt()
    }

    fun isLabel(text: String): Boolean {
        val label = LABEL.find(text)?.groupValues?.get(1) ?: return false
        return label.count { it == ' ' } <= 3
    }
}
