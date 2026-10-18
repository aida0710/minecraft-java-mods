package com.aida0710.chattranslator

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * ユーザー辞書 (config/chattranslator/glossary.json)。
 *  - "terms": 完全一致で置き換える訳語。値を原文と同じにすれば「翻訳しない」指定になる。
 *  - "skip":  この正規表現にマッチする行は翻訳しない。
 */
class Glossary(private val file: Path) {
    @Volatile private var terms: Map<String, String> = emptyMap()
    @Volatile private var skip: List<Regex> = emptyList()

    fun lookup(text: String): String? = terms[text.trim()]

    fun shouldSkip(text: String): Boolean = skip.any { it.containsMatchIn(text) }

    fun load() {
        if (!Files.exists(file)) writeDefault()
        runCatching {
            val json = Files.newBufferedReader(file).use { GsonBuilder().create().fromJson(it, JsonObject::class.java) }
            terms = json.getAsJsonObject("terms")?.entrySet()?.associate { it.key to it.value.asString } ?: emptyMap()
            skip = json.getAsJsonArray("skip")?.mapNotNull { e ->
                runCatching { Regex(e.asString) }.onFailure {
                    ChatTranslator.LOGGER.warn("Invalid skip regex in glossary: {}", e.asString)
                }.getOrNull()
            } ?: emptyList()
            ChatTranslator.LOGGER.info("Glossary loaded: {} terms, {} skip patterns", terms.size, skip.size)
        }.onFailure { ChatTranslator.LOGGER.warn("Failed to load glossary {}", file, it) }
    }

    private fun writeDefault() {
        Files.createDirectories(file.parent)
        Files.writeString(file, DEFAULT)
    }

    private companion object {
        val DEFAULT = """
            {
              "terms": {
                "Hyperion": "Hyperion",
                "Aspect of the End": "Aspect of the End",
                "Skyblock Menu (Click)": "スカイブロックメニュー (クリック)",
                "Click to view!": "クリックして表示！",
                "Right-click to view recipes!": "右クリックでレシピ表示！"
              },
              "skip": [
                "^\\s*(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL)\\b.*$",
                "^\\s*(Damage|Strength|Defense|Health|Speed|Crit Chance|Crit Damage|Intelligence|Ferocity|Attack Speed|Mining Speed|Mining Fortune|Magic Find|Pet Luck|True Defense|Sea Creature Chance|Ability Damage|Farming Fortune|Foraging Fortune|Health Regen|Vitality|Mending): .*$",
                "^\\s*[-=_]{4,}\\s*$"
              ]
            }
        """.trimIndent()
    }
}
