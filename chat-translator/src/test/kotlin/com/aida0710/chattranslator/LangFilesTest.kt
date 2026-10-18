package com.aida0710.chattranslator

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 言語ファイル (日本語・英語・中国語) の抜け漏れを検出する */
class LangFilesTest {
    private val languages = listOf("en_us", "ja_jp", "zh_cn")

    private fun load(lang: String): Map<String, String> {
        val stream = javaClass.getResourceAsStream("/assets/chattranslator/lang/$lang.json") ?: error("missing $lang.json")
        return stream.reader(Charsets.UTF_8).use { r -> JsonParser.parseReader(r).asJsonObject.entrySet().associate { it.key to it.value.asString } }
    }

    /** ソース中の T.tr("…") / T.s("…") のキー (動的に組み立てるキーは列挙して補う) */
    private fun keysInSource(): Set<String> {
        val src = Path.of("src/main/kotlin")
        val code = Files.walk(src).use { s -> s.filter { it.extension == "kt" }.toList() }.joinToString("\n") { it.readText() }
        val direct = Regex("""T\.(?:tr|s)\("([a-z0-9_.\-]+)"""").findAll(code).map { it.groupValues[1] }
        val conditional = Regex("""T\.tr\(if \([^)]*\) "([a-z0-9_.]+)" else "([a-z0-9_.]+)"""").findAll(code).flatMap { listOf(it.groupValues[1], it.groupValues[2]) }
        val dynamic = ProviderType.entries.map { "provider.${it.name.lowercase()}" } +
            ChatDisplayMode.entries.map { "settings.display.${it.name.lowercase()}" } +
            ModelPreset.entries.flatMap { listOf("model.${it.key}", "model.${it.key}.short") } +
            listOf("on", "off")
        return (direct + conditional + dynamic).map { "chattranslator.$it" }.toSet()
    }

    @Test
    fun everyKeyUsedInCodeExistsInAllLanguages() {
        val used = keysInSource()
        assertTrue(used.size > 100, "keys found: ${used.size}")
        for (lang in languages) {
            val missing = used - load(lang).keys
            assertTrue(missing.isEmpty(), "$lang is missing: $missing")
        }
    }

    @Test
    fun allLanguagesHaveTheSameKeysAndPlaceholders() {
        val en = load("en_us")
        for (lang in languages - "en_us") {
            val other = load(lang)
            assertEquals(en.keys, other.keys, "$lang keys differ from en_us")
            for ((key, value) in en) {
                fun count(s: String) = Regex("%(\\d+\\$)?s").findAll(s).count()
                assertEquals(count(value), count(other.getValue(key)), "$lang: placeholder count differs for $key")
            }
        }
    }
}
