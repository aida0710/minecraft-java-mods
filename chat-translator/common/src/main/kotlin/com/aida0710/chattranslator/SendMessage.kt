package com.aida0710.chattranslator

/**
 * 送信するチャットの分解と組み立て (画面に依存しない部分)。
 * 「/msg Steve こんにちは」なら、宛先までを残して「こんにちは」だけを翻訳する。
 */
object SendMessage {
    /** Minecraft がチャット・コマンドとして送れる最大の文字数 */
    const val MAX_LENGTH = 256

    /** 翻訳せずに残す先頭部分 ([prefix]) と、翻訳する本文 ([body]) */
    data class Parts(val prefix: String, val body: String)

    /**
     * 翻訳する部分を取り出す。コマンドは [commands] にあるものだけ対象にし、それ以外は null。
     * [commands] の各要素は "pc" (後ろをすべて翻訳) や "msg 1" (最初の 1 語を残して翻訳) の形。
     */
    fun split(text: String, commands: List<String>): Parts? {
        if (!text.startsWith("/")) return Parts("", text)
        val name = text.drop(1).substringBefore(' ').lowercase()
        val keep = commands.firstNotNullOfOrNull { spec -> commandArguments(spec, name) } ?: return null
        // コマンド名と、残す引数の後ろまでを prefix にする
        var end = 1 + name.length
        repeat(keep) {
            end = text.indexOfNonSpace(end)
            end = text.indexOf(' ', end).let { if (it < 0) text.length else it }
        }
        val bodyStart = text.indexOfNonSpace(end)
        if (bodyStart >= text.length) return null
        return Parts(text.substring(0, bodyStart), text.substring(bodyStart))
    }

    /** spec ("msg 1" など) が name のコマンドなら、残す引数の数を返す */
    private fun commandArguments(spec: String, name: String): Int? {
        val words = spec.trim().split(Regex("\\s+"))
        if (words.first().removePrefix("/").lowercase() != name) return null
        return words.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun String.indexOfNonSpace(from: Int): Int {
        var i = from
        while (i < length && this[i] == ' ') i++
        return i
    }

    /** 送る文を組み立てる。[MAX_LENGTH] を超える場合は原文を付けず、それでも超えれば切り詰める */
    fun compose(parts: Parts, translated: String, format: SendFormat): String {
        val translatedOnly = parts.prefix + translated.replace('\n', ' ').trim()
        val withOriginal = "$translatedOnly (${parts.body})"
        val message = if (format == SendFormat.TRANSLATION_AND_ORIGINAL && withOriginal.length <= MAX_LENGTH) withOriginal else translatedOnly
        return message.take(MAX_LENGTH)
    }
}
