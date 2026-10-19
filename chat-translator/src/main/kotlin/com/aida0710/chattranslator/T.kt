package com.aida0710.chattranslator

import com.google.gson.JsonParser
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import java.util.concurrent.ConcurrentHashMap

/**
 * 表示文字列 (assets/chattranslator/lang の各言語ファイル)。
 * 既定では Minecraft の言語設定に合わせて日本語・英語・中国語で表示され、未翻訳の言語は英語になる。
 * 設定で mod の表示言語を固定した場合は、その言語ファイルを直接読んで使う。
 */
object T {
    /** mod の表示言語の選択肢 (auto = Minecraft に合わせる) */
    const val AUTO = "auto"
    val UI_LANGUAGES = listOf("en_us", "ja_jp", "zh_cn")
    /** 選択肢の表示名 (それぞれの言語で書く) */
    val UI_LANGUAGE_NAMES = mapOf("en_us" to "English", "ja_jp" to "日本語", "zh_cn" to "简体中文")

    private val tables = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile private var forced: Map<String, String>? = null

    /** 表示言語を固定する (auto または未対応の言語なら Minecraft に合わせる) */
    fun setUiLanguage(code: String) {
        forced = if (code in UI_LANGUAGES) table(code) else null
    }

    /** 翻訳キー chattranslator.<key> の Component */
    fun tr(key: String, vararg args: Any): MutableComponent = raw("chattranslator.$key", null, *args)

    /** 言語ファイルに無ければ fallback を使う (辞書の表示名など) */
    fun trOr(key: String, fallback: String, vararg args: Any): MutableComponent = raw("chattranslator.$key", fallback, *args)

    /** 現在の言語で文字列にする (ログなど Component を使えない場所用) */
    fun s(key: String, vararg args: Any): String = tr(key, *args).string

    fun onOff(on: Boolean): MutableComponent = tr(if (on) "on" else "off")

    private fun raw(fullKey: String, fallback: String?, vararg args: Any): MutableComponent {
        val f = forced
            ?: return if (fallback == null) Component.translatable(fullKey, *args) else Component.translatableWithFallback(fullKey, fallback, *args)
        val pattern = f[fullKey] ?: fallback ?: table("en_us")[fullKey] ?: fullKey
        // 存在しないキーに書式を fallback として渡すと、引数 (Component も可) の埋め込みは TranslatableContents が行う
        return Component.translatableWithFallback(FORCED_KEY, pattern, *args)
    }

    private const val FORCED_KEY = "chattranslator.\u0000forced"

    private fun table(code: String): Map<String, String> = tables.computeIfAbsent(code) {
        val stream = T::class.java.getResourceAsStream("/assets/chattranslator/lang/$code.json") ?: return@computeIfAbsent emptyMap()
        runCatching {
            stream.reader(Charsets.UTF_8).use { r -> JsonParser.parseReader(r).asJsonObject.entrySet().associate { it.key to it.value.asString } }
        }.getOrElse { emptyMap() }
    }
}
