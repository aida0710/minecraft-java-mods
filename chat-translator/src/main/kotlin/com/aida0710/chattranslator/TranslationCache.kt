package com.aida0710.chattranslator

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * スレッドセーフな LRU キャッシュ。
 * ツールチップは毎フレーム描画されるためメモリ上のキャッシュは常に使い、
 * persist が ON ならファイルにも保存して次回起動時から即時表示する。
 */
class TranslationCache(private val file: Path) {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    @Volatile var maxEntries: Int = 20000
        set(value) {
            field = value
            synchronized(this) { trim() }
        }
    @Volatile var persist: Boolean = true
    private val map = LinkedHashMap<String, String>(256, 0.75f, true)
    @Volatile private var dirty = false

    @Synchronized fun get(key: String): String? = map[key]

    @Synchronized fun put(key: String, value: String) {
        map[key] = value
        trim()
        dirty = true
    }

    @Synchronized fun clear() {
        map.clear()
        dirty = true
    }

    @Synchronized fun size() = map.size

    /** エクスポート用の複製 (古い順) */
    @Synchronized fun snapshot(): Map<String, String> = LinkedHashMap(map)

    /** インポート。最大件数を超えた分は古いものから捨てる */
    @Synchronized fun putAll(entries: Map<String, String>) {
        map.putAll(entries)
        trim()
        dirty = true
    }

    /** 保存しているファイルの大きさ (バイト) */
    fun fileSize(): Long = runCatching { Files.size(file) }.getOrDefault(0L)

    private fun trim() {
        val it = map.entries.iterator()
        while (map.size > maxEntries && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    fun load() {
        if (!persist || !Files.exists(file)) return
        runCatching {
            val type = object : TypeToken<LinkedHashMap<String, String>>() {}.type
            val loaded: Map<String, String> = Files.newBufferedReader(file).use { gson.fromJson(it, type) } ?: emptyMap()
            synchronized(this) {
                map.putAll(loaded)
                trim()
            }
        }.onFailure { ChatTranslator.LOGGER.warn("Failed to load translation cache", it) }
    }

    fun saveIfDirty() {
        if (!dirty) return
        if (!persist) {
            // 保存しない設定なら古いファイルも消す
            dirty = false
            runCatching { Files.deleteIfExists(file) }
            return
        }
        val snapshot = synchronized(this) { dirty = false; LinkedHashMap(map) }
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.newBufferedWriter(tmp).use { gson.toJson(snapshot, it) }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { ChatTranslator.LOGGER.warn("Failed to save translation cache", it) }
    }

    /** persist を切り替えたときに即反映する */
    fun markDirty() {
        dirty = true
    }
}
