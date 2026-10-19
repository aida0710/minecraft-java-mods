package com.aida0710.chattranslator

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {
    private class FakeStore : Backup.Store {
        override var source = "en"
        override var target = "ja"
        override var ui = T.AUTO
        override var enabledDictionaries = listOf("hypixel-skyblock", "user")
    }

    private class Env(root: Path) {
        val store = FakeStore()
        val cache = TranslationCache(root.resolve("cache.json"))
        val dictionaries = Dictionaries(root.resolve("dictionaries"), ItemNames(root.resolve("items.json"))).also { it.load() }
        val backup = Backup(root.resolve("exports"), cache, dictionaries, store)
    }

    @Test
    fun exportThenImportRestoresLanguageDictionariesAndCache() {
        val a = Env(Files.createTempDirectory("backupA"))
        a.store.source = "auto"
        a.store.target = "zh-Hans"
        a.store.ui = "en_us"
        a.dictionaries.addKeep("Mayor Diana")
        a.dictionaries.addTerm("gg", "おつかれ")
        a.cache.put("k1", "訳1")
        a.cache.put("k2", "訳2")
        val file = a.backup.export(Backup.Sections(), "my backup")
        assertEquals("my_backup.json", file.fileName.toString())
        // 同梱辞書は入れない
        assertTrue("hypixel-skyblock" !in Files.readString(file).substringAfter("\"packs\"").substringBefore("\"cache\""))

        val bRoot = Files.createTempDirectory("backupB")
        val b = Env(bRoot)
        b.dictionaries.addKeep("Crimson Isle")
        Files.createDirectories(b.backup.dir)
        Files.copy(file, b.backup.dir.resolve(file.fileName))
        val r = b.backup.import(b.backup.resolve("my_backup.json")!!)

        assertEquals(Backup.ImportResult(language = true, dictionaries = 1, cacheEntries = 2), r)
        assertEquals("auto", b.store.source)
        assertEquals("zh-Hans", b.store.target)
        assertEquals("en_us", b.store.ui)
        // ユーザー辞書は足し合わせる
        assertEquals(listOf("Crimson Isle", "Mayor Diana"), b.dictionaries.userPack!!.keep)
        assertEquals("おつかれ", b.dictionaries.userPack!!.terms["gg"])
        assertEquals("訳1", b.cache.get("k1"))
    }

    @Test
    fun importOnlySelectedSections() {
        val a = Env(Files.createTempDirectory("backupC"))
        a.store.target = "ko"
        a.cache.put("k", "v")
        val file = a.backup.export(Backup.Sections())
        val b = Env(Files.createTempDirectory("backupD"))
        val r = b.backup.import(file, Backup.Sections(language = false, dictionaries = false, cache = true))
        assertEquals("ja", b.store.target)
        assertEquals(1, r.cacheEntries)
        assertEquals("v", b.cache.get("k"))
    }

    @Test
    fun rejectsOtherFilesAndPathsOutsideTheFolder() {
        val env = Env(Files.createTempDirectory("backupE"))
        Files.createDirectories(env.backup.dir)
        val other = env.backup.dir.resolve("other.json")
        Files.writeString(other, """{"keep":["x"]}""")
        assertFailsWith<IllegalArgumentException> { env.backup.import(other) }
        Files.writeString(env.backup.dir.resolveSibling("outside.json"), "{}")
        assertNull(env.backup.resolve("../outside.json"))
        assertNull(env.backup.resolve("missing.json"))
    }

    @Test
    fun importIgnoresUnsafePackIds() {
        val env = Env(Files.createTempDirectory("backupF"))
        Files.createDirectories(env.backup.dir)
        val file = env.backup.dir.resolve("evil.json")
        Files.writeString(file, """{"format":"chattranslator-export","version":1,"dictionaries":{"packs":{"../../evil":{"keep":["a"]},"hypixel-skyblock":{"keep":[]},"mine":{"keep":["b"]}}}}""")
        val r = env.backup.import(file)
        assertEquals(1, r.dictionaries)
        assertTrue(env.dictionaries.packs.any { it.id == "mine" && it.keep == listOf("b") })
        assertTrue(env.dictionaries.packs.first { it.id == "hypixel-skyblock" }.keep.isNotEmpty())
    }
}
