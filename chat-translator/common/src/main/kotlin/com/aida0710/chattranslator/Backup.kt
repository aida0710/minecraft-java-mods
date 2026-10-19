package com.aida0710.chattranslator

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name

/**
 * 設定のエクスポート / インポート (config/chattranslator/exports 内の JSON)。
 * 対象は言語設定・辞書・翻訳キャッシュ。API キーやエンジンの設定、同梱辞書は含めない。
 */
class Backup(
    val dir: Path,
    private val cache: TranslationCache,
    private val dictionaries: Dictionaries,
    private val store: Store = ConfigStore,
) {
    data class Sections(val language: Boolean = true, val dictionaries: Boolean = true, val cache: Boolean = true) {
        val any get() = language || dictionaries || cache
    }

    data class ImportResult(val language: Boolean, val dictionaries: Int, val cacheEntries: Int)

    /** 言語設定と有効な辞書の読み書き (テストでは差し替える) */
    interface Store {
        var source: String
        var target: String
        var ui: String
        var enabledDictionaries: List<String>
    }

    object ConfigStore : Store {
        private val c get() = TranslatorConfig.current
        override var source: String
            get() = c.sourceCode
            set(v) = TranslatorConfig.update(TranslatorConfig.sourceCode, v)
        override var target: String
            get() = c.targetCode
            set(v) = TranslatorConfig.update(TranslatorConfig.targetCode, v)
        override var ui: String
            get() = c.uiLanguage
            set(v) = TranslatorConfig.update(TranslatorConfig.uiLanguage, v)
        override var enabledDictionaries: List<String>
            get() = c.enabledDictionaries
            set(v) = TranslatorConfig.update(TranslatorConfig.enabledDictionaries, v.toMutableList())
    }

    /** 新しい順のエクスポートファイル */
    fun files(): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.filter { it.extension == "json" }.toList() }.sortedByDescending { it.getLastModifiedTime() }
    }

    /** エクスポートしてファイルの場所を返す。name を省略すると日時から付ける */
    fun export(sections: Sections, name: String? = null): Path {
        val json = JsonObject()
        json.addProperty("format", FORMAT)
        json.addProperty("version", VERSION)
        json.addProperty("exportedAt", OffsetDateTime.now().toString())
        if (sections.language) json.add("language", JsonObject().apply {
            addProperty("source", store.source)
            addProperty("target", store.target)
            addProperty("ui", store.ui)
        })
        if (sections.dictionaries) json.add("dictionaries", JsonObject().apply {
            add("enabled", JsonArray().apply { store.enabledDictionaries.forEach { add(it) } })
            add("packs", dictionaries.exportPacks())
        })
        if (sections.cache) json.add("cache", JsonObject().apply { cache.snapshot().forEach { (k, v) -> addProperty(k, v) } })

        Files.createDirectories(dir)
        val base = name?.trim()?.removeSuffix(".json")?.replace(Regex("[^A-Za-z0-9_\\-.]"), "_")?.takeIf { it.isNotBlank() && it.any { c -> c != '.' } }
            ?: "chattranslator-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = dir.resolve("$base.json")
        val tmp = dir.resolve("$base.json.tmp")
        // キャッシュが大きくなるので整形はしない
        Files.writeString(tmp, GsonBuilder().disableHtmlEscaping().create().toJson(json))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return file
    }

    /** エクスポートフォルダ内のファイル名からパスを得る (フォルダの外は指せない) */
    fun resolve(fileName: String): Path? {
        val root = dir.toAbsolutePath().normalize()
        val path = root.resolve(fileName).normalize()
        return path.takeIf { it.parent == root && Files.isRegularFile(it) }
    }

    /** sections で選んだ部分だけ取り込む。形式が違えば IllegalArgumentException */
    fun import(file: Path, sections: Sections = Sections()): ImportResult {
        val json = Files.newBufferedReader(file).use { JsonParser.parseReader(it) }
            .takeIf { it.isJsonObject }?.asJsonObject ?: throw IllegalArgumentException("not a JSON object")
        require(json["format"]?.asString == FORMAT) { "not a Chat Translator export" }
        require((json["version"]?.asInt ?: 0) in 1..VERSION) { "unsupported version ${json["version"]}" }

        var language = false
        if (sections.language) json.getAsJsonObject("language")?.let { lang ->
            lang["source"]?.asString?.takeIf { it == Languages.AUTO || Languages.get(it) != null }?.let { store.source = it }
            lang["target"]?.asString?.takeIf { Languages.get(it) != null }?.let { store.target = it }
            lang["ui"]?.asString?.takeIf { it == T.AUTO || it in T.UI_LANGUAGES }?.let { store.ui = it }
            language = true
        }

        var packs = 0
        if (sections.dictionaries) json.getAsJsonObject("dictionaries")?.let { d ->
            d.getAsJsonObject("packs")?.let { packs = dictionaries.importPacks(it) }
            d.getAsJsonArray("enabled")?.let { arr ->
                val known = dictionaries.packs.map { it.id }.toSet()
                store.enabledDictionaries = arr.map { it.asString }.filter { it in known }
                dictionaries.apply()
            }
        }

        var entries = 0
        if (sections.cache) json.getAsJsonObject("cache")?.let { c ->
            val map = LinkedHashMap<String, String>()
            c.entrySet().forEach { (k, v) -> if (v.isJsonPrimitive) map[k] = v.asString }
            cache.putAll(map)
            cache.saveIfDirty()
            entries = map.size
        }
        return ImportResult(language, packs, entries)
    }

    companion object {
        const val FORMAT = "chattranslator-export"
        const val VERSION = 1

        fun displayName(path: Path) = path.name
    }
}
