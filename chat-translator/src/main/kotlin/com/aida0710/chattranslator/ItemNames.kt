package com.aida0710.chattranslator

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Hypixel SkyBlock のアイテム名一覧 (公開 API, キー不要)。
 * 固有名詞であるアイテム名は直訳されると何のアイテムか分からなくなるため、翻訳から保護する。
 */
class ItemNames(private val file: Path) {
    /** 先頭の単語 → その単語で始まるアイテム名 (長い順) */
    @Volatile private var byFirstWord: Map<String, List<String>> = emptyMap()
    @Volatile private var all: Set<String> = emptySet()

    /**
     * キャッシュキー用の目印。アイテム一覧の読み込み済みかどうかと keep 語の内容で変わる
     * (読み込み前に保護なしで訳した結果や、keep 追加前の訳が、後から使われないように)
     */
    val protectionTag: String get() = (if (all.isNotEmpty()) "items" else "") + ":" + Integer.toHexString(extra.hashCode())

    /** 保護対象の語数 (アイテム名 + keep) */
    val size get() = all.size + extra.size

    private val loading = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 有効なアイテム名を消す (Hypixel 辞書を無効にしたとき)。keep 語は残す */
    fun clear() {
        all = emptySet()
        rebuild()
        loading.set(false)
    }

    fun loadAsync(enabled: Boolean) {
        if (!enabled || all.isNotEmpty() || !loading.compareAndSet(false, true)) return
        Thread({
            runCatching {
                if (Files.exists(file)) set(read())
                val stale = !Files.exists(file) || System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() > REFRESH_MS
                if (stale) {
                    val names = fetch()
                    write(names)
                    set(names)
                }
                ChatTranslator.LOGGER.info("SkyBlock item names loaded: {}", all.size)
            }.onFailure { ChatTranslator.LOGGER.warn("Failed to load SkyBlock item names", it) }
            loading.set(false)
        }, "ChatTranslator-items").apply { isDaemon = true }.start()
    }

    /**
     * ツールチップ 1 行目 (アイテム名) が既知のアイテムか。
     * "Heroic Aspect of the Dragons ✪✪✪✪✪ ➊" や "[Lvl 100] Golden Dragon" のような装飾付きも判定する。
     */
    fun isItemName(line: String): Boolean {
        if (all.isEmpty()) return false
        val s = line.replace(PET_LEVEL, "").trimEnd { !it.isLetterOrDigit() && it != ')' && it != '\'' }.trim()
        if (s in all) return true
        // 先頭のリフォージ名 (1〜2 単語) を除いて一致するか
        val words = s.split(' ')
        return (1..2).any { n -> words.size > n && words.drop(n).joinToString(" ") in all }
    }

    data class Protected(val text: String, val names: List<String>)

    /** 文中の既知アイテム名を ITEM1, ITEM2… に置き換える */
    fun protect(text: String): Protected {
        if (byFirstWord.isEmpty()) return Protected(text, emptyList())
        val out = StringBuilder()
        val names = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val atWordStart = text[i].isLetter() && (i == 0 || !text[i - 1].isLetterOrDigit())
            if (atWordStart) {
                val word = text.substring(i).takeWhile { it.isLetterOrDigit() || it == '\'' }
                val match = byFirstWord[word.lowercase()]?.firstOrNull { name ->
                    // プレイヤーは小文字で打つことが多いので大文字小文字を無視する (短い 1 単語の名前は誤検出を避けて完全一致のみ)
                    val ignoreCase = name.contains(' ') || name.length >= 8
                    text.regionMatches(i, name, 0, name.length, ignoreCase) &&
                        (i + name.length == text.length || !text[i + name.length].isLetterOrDigit())
                }
                if (match != null) {
                    var idx = names.indexOf(match)
                    if (idx < 0) { names += match; idx = names.size - 1 }
                    out.append("ITEM").append(idx + 1)
                    i += match.length
                    continue
                }
            }
            out.append(text[i])
            i++
        }
        return Protected(out.toString(), names)
    }

    /** 訳文中の ITEMn を元の名前に戻す。欠けたプレースホルダーがあれば null */
    fun restore(translated: String, names: List<String>): String? {
        var result = translated
        // ITEM1 が ITEM10 の一部を置換しないよう大きい番号から
        for (n in names.indices.reversed()) {
            // "ITEM1" のほか、モデルが変えてしまった "ＩＴＥＭ１" "アイテム1" "[ITEM1]" も受け付ける
            val digits = (n + 1).toString().map { d -> "[$d${(d + 0xFEE0)}]" }.joinToString("")
            val token = Regex("[\\[［]?(?:[IＩ][TＴ][EＥ][MＭ]|アイテム) ?$digits(?![0-9０-９])[\\]］]?")
            if (!token.containsMatchIn(result)) return null
            result = token.replace(result, Regex.escapeReplacement(names[n]))
        }
        return result
    }

    private fun set(names: Collection<String>) {
        // 一般的な短い単語 ("Stick", "Stone" など) まで保護すると不自然になるため、複数語か 7 文字以上のみ
        all = names.map { TextUtil.stripFormatting(it).trim() }.filter { it.contains(' ') || it.length >= 7 }.toSet()
        rebuild()
    }

    /** glossary.json の "keep" (地名などの固有名詞)。長さに関係なく文中で保護する */
    fun setExtra(terms: Collection<String>) {
        extra = terms.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        rebuild()
    }

    @Volatile private var extra: Set<String> = emptySet()

    private fun rebuild() {
        byFirstWord = (all + extra).groupBy { it.takeWhile { c -> c.isLetterOrDigit() || c == '\'' }.lowercase() }
            .mapValues { (_, v) -> v.sortedByDescending { it.length } }
    }

    private fun fetch(): List<String> {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val res = http.send(
            HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(60)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) { "HTTP ${res.statusCode()}" }
        return JsonParser.parseString(res.body()).asJsonObject.getAsJsonArray("items")
            .mapNotNull { it.asJsonObject["name"]?.asString }
            .distinct()
    }

    private fun read(): List<String> =
        Files.newBufferedReader(file).use { Gson().fromJson(it, object : TypeToken<List<String>>() {}.type) }

    private fun write(names: List<String>) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, Gson().toJson(names))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** テスト用 */
    internal fun setForTest(names: Collection<String>) = set(names)

    private companion object {
        const val API = "https://api.hypixel.net/v2/resources/skyblock/items"
        const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        val PET_LEVEL = Regex("^\\[Lvl \\d+] ")
    }
}
