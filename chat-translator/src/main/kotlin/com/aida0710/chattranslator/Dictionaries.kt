package com.aida0710.chattranslator

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * 辞書 (config/chattranslator/dictionaries/<id>.json)。複数を有効/無効にでき、有効なものを合成して使う。
 *  - "keep":  文中に出てきても翻訳せず原文のまま残す語 (地名・固有名詞など)
 *  - "terms": 行 (段落) 全体が完全一致したときに使う訳語
 *  - "skip":  この正規表現にマッチする行は翻訳しない
 *  - "hypixelItems": true なら Hypixel SkyBlock の全アイテム名 (公開 API) も保護する
 *
 * 同梱辞書 (builtin) は起動のたびに mod 内の最新版で上書きする。自分で編集する語は "user" 辞書に入れる。
 */
class Dictionaries(private val dir: Path, private val itemNames: ItemNames) {
    data class Pack(
        val id: String,
        val name: String,
        val description: String,
        val builtin: Boolean,
        val keep: List<String>,
        val terms: Map<String, String>,
        val skip: List<Regex>,
        val hypixelItems: Boolean,
    )

    @Volatile var packs: List<Pack> = emptyList()
        private set
    @Volatile private var terms: Map<String, String> = emptyMap()
    @Volatile private var skip: List<Regex> = emptyList()
    @Volatile var itemNamesActive = false
        private set

    val userPack: Pack? get() = packs.firstOrNull { it.id == USER }

    /** 表示名。言語ファイルに chattranslator.dict.<id>.name があればそれを使い、無ければ JSON の name */
    fun displayName(pack: Pack): net.minecraft.network.chat.Component =
        T.trOr("dict.${pack.id}.name", pack.name)

    fun description(pack: Pack): net.minecraft.network.chat.Component =
        T.trOr("dict.${pack.id}.desc", pack.description)

    fun lookup(text: String): String? = terms[text.trim()]

    fun shouldSkip(text: String): Boolean = skip.any { it.containsMatchIn(text) }

    fun isEnabled(id: String) = id in TranslatorConfig.current.enabledDictionaries

    fun load() {
        Files.createDirectories(dir)
        migrateOldGlossary()
        BUILTIN.forEach { id -> copyBuiltin(id) }
        if (!Files.exists(file(USER))) write(USER, JsonObject().apply {
            addProperty("name", "User dictionary")
            addProperty("description", "Your own terms")
            add("keep", JsonArray())
            add("terms", JsonObject())
            add("skip", JsonArray())
        })
        packs = Files.list(dir).use { s -> s.filter { it.extension == "json" }.sorted().toList() }
            .mapNotNull { path -> runCatching { parse(path) }.onFailure { ChatTranslator.LOGGER.warn("Invalid dictionary {}", path, it) }.getOrNull() }
        apply()
    }

    /** 有効な辞書を合成する (設定変更時にも呼ぶ) */
    fun apply() {
        val active = packs.filter { isEnabled(it.id) }
        terms = active.flatMap { it.terms.entries }.associate { it.key to it.value }
        skip = active.flatMap { it.skip }
        itemNames.setExtra(active.flatMap { it.keep })
        itemNamesActive = active.any { it.hypixelItems }
        if (itemNamesActive) itemNames.loadAsync(true) else itemNames.clear()
        ChatTranslator.LOGGER.info("Dictionaries: {} active of {} ({} keep, {} terms, {} skip)",
            active.size, packs.size, active.sumOf { it.keep.size }, terms.size, skip.size)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val list = TranslatorConfig.current.enabledDictionaries.toMutableList()
        if (enabled) { if (id !in list) list += id } else list.remove(id)
        TranslatorConfig.update(TranslatorConfig.enabledDictionaries, list)
        apply()
    }

    // ---- ユーザー辞書の編集 ----

    fun addKeep(term: String): Boolean = editUser { json ->
        val arr = json.getAsJsonArray("keep") ?: JsonArray().also { json.add("keep", it) }
        if (arr.any { it.asString == term }) false else { arr.add(term); true }
    }

    fun removeKeep(term: String): Boolean = editUser { json ->
        val arr = json.getAsJsonArray("keep") ?: return@editUser false
        val idx = arr.indexOfFirst { it.asString == term }
        if (idx < 0) false else { arr.remove(idx); true }
    }

    fun addTerm(source: String, translation: String): Boolean = editUser { json ->
        val obj = json.getAsJsonObject("terms") ?: JsonObject().also { json.add("terms", it) }
        obj.addProperty(source, translation)
        true
    }

    fun removeTerm(source: String): Boolean = editUser { json ->
        val obj = json.getAsJsonObject("terms") ?: return@editUser false
        obj.remove(source) != null
    }

    private fun editUser(edit: (JsonObject) -> Boolean): Boolean {
        val json = runCatching { read(file(USER)) }.getOrElse { JsonObject() }
        if (!edit(json)) return false
        write(USER, json)
        load()
        return true
    }

    // ---- エクスポート / インポート ----

    /** 同梱以外の辞書ファイルの中身 (id → JSON) */
    fun exportPacks(): JsonObject = JsonObject().apply {
        packs.filter { !it.builtin }.forEach { pack -> runCatching { add(pack.id, read(file(pack.id))) } }
    }

    /**
     * 辞書を取り込む。ユーザー辞書は今の内容に足し合わせ、それ以外は同じ id のファイルを置き換える。
     * 同梱辞書と、ファイル名に使えない id は無視する。戻り値は取り込んだ辞書の数
     */
    fun importPacks(imported: JsonObject): Int {
        var count = 0
        for ((id, value) in imported.entrySet()) {
            if (id in BUILTIN || !ID_PATTERN.matches(id) || !value.isJsonObject) continue
            val json = value.asJsonObject
            if (id == USER) {
                val user = runCatching { read(file(USER)) }.getOrElse { JsonObject() }
                for (key in listOf("keep", "skip")) {
                    val arr = user.getAsJsonArray(key) ?: JsonArray().also { user.add(key, it) }
                    json.getAsJsonArray(key)?.forEach { e -> if (arr.none { it == e }) arr.add(e) }
                }
                val terms = user.getAsJsonObject("terms") ?: JsonObject().also { user.add("terms", it) }
                json.getAsJsonObject("terms")?.entrySet()?.forEach { (k, v) -> terms.add(k, v) }
                write(USER, user)
            } else {
                write(id, json)
            }
            count++
        }
        load()
        return count
    }

    // ---- ファイル入出力 ----

    private fun file(id: String) = dir.resolve("$id.json")

    private fun read(path: Path): JsonObject = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }

    private fun write(id: String, json: JsonObject) {
        val tmp = dir.resolve("$id.json.tmp")
        Files.writeString(tmp, GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json))
        Files.move(tmp, file(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun parse(path: Path): Pack {
        val json = read(path)
        val id = path.nameWithoutExtension
        return Pack(
            id = id,
            name = json["name"]?.asString ?: id,
            description = json["description"]?.asString ?: "",
            builtin = id in BUILTIN,
            keep = json.getAsJsonArray("keep")?.map { it.asString } ?: emptyList(),
            terms = json.getAsJsonObject("terms")?.entrySet()?.associate { it.key to it.value.asString } ?: emptyMap(),
            skip = json.getAsJsonArray("skip")?.mapNotNull { e ->
                runCatching { Regex(e.asString) }.onFailure { ChatTranslator.LOGGER.warn("Invalid skip regex in {}: {}", path.name, e.asString) }.getOrNull()
            } ?: emptyList(),
            hypixelItems = json["hypixelItems"]?.asBoolean ?: false,
        )
    }

    private fun copyBuiltin(id: String) {
        val stream = Dictionaries::class.java.getResourceAsStream("/assets/chattranslator/dictionaries/$id.json") ?: return
        stream.use { Files.copy(it, file(id), StandardCopyOption.REPLACE_EXISTING) }
    }

    /** 旧バージョンの glossary.json は同梱辞書に置き換わったので退避する */
    private fun migrateOldGlossary() {
        val old = dir.parent.resolve("glossary.json")
        if (Files.exists(old)) Files.move(old, dir.parent.resolve("glossary.json.old"), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val USER = "user"
        val BUILTIN = listOf("hypixel-skyblock")
        private val ID_PATTERN = Regex("[A-Za-z0-9_\\-]{1,64}")
    }
}
