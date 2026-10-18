package com.aida0710.chattranslator

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** スレッドセーフな LRU キャッシュ。ディスクに永続化してアイテム名などを次回から即時表示する。 */
class TranslationCache(private val file: Path, private val maxEntries: Int) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > maxEntries
    }
    @Volatile private var dirty = false

    @Synchronized fun get(key: String): String? = map[key]

    @Synchronized fun put(key: String, value: String) {
        map[key] = value
        dirty = true
    }

    @Synchronized fun clear() {
        map.clear()
        dirty = true
    }

    @Synchronized fun size() = map.size

    fun load() {
        if (!Files.exists(file)) return
        runCatching {
            val type = object : TypeToken<LinkedHashMap<String, String>>() {}.type
            val loaded: Map<String, String> = Files.newBufferedReader(file).use { gson.fromJson(it, type) } ?: emptyMap()
            synchronized(this) { map.putAll(loaded) }
        }.onFailure { ChatTranslator.LOGGER.warn("Failed to load translation cache", it) }
    }

    fun saveIfDirty() {
        if (!dirty) return
        val snapshot = synchronized(this) { dirty = false; LinkedHashMap(map) }
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.newBufferedWriter(tmp).use { gson.toJson(snapshot, it) }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { ChatTranslator.LOGGER.warn("Failed to save translation cache", it) }
    }
}
