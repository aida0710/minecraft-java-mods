package com.aida0710.chattranslator

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictionariesTest {
    private fun newDictionaries(): Pair<Dictionaries, ItemNames> {
        val dir = Files.createTempDirectory("dict")
        val items = ItemNames(dir.resolve("items.json"))
        // Hypixel のアイテム一覧取得 (ネットワーク) はテストでは不要
        return Dictionaries(dir.resolve("dictionaries"), items) to items
    }

    @Test
    fun loadsBuiltinHypixelPackAndCreatesUserPack() {
        val (d, _) = newDictionaries()
        d.load()
        val hypixel = d.packs.first { it.id == "hypixel-skyblock" }
        assertTrue(hypixel.builtin && hypixel.hypixelItems && "Catacombs" in hypixel.keep)
        assertNotNull(d.userPack)
        // 既定で両方有効
        assertEquals("このアイテムはリフォージできます！", d.lookup("This item can be reforged!"))
        assertTrue(d.shouldSkip("Damage: +270"))
        assertFalse(d.shouldSkip("Teleport 10 blocks ahead of you."))
    }

    @Test
    fun userKeepTermsAreProtected() {
        val (d, items) = newDictionaries()
        d.load()
        assertTrue(d.addKeep("Mayor Diana"))
        assertFalse(d.addKeep("Mayor Diana"))
        assertEquals("ITEM1 is the mayor", items.protect("Mayor Diana is the mayor").text)
        assertTrue(d.removeKeep("Mayor Diana"))
        assertEquals("Mayor Diana is the mayor", items.protect("Mayor Diana is the mayor").text)
    }

    @Test
    fun userTermsOverrideTranslation() {
        val (d, _) = newDictionaries()
        d.load()
        d.addTerm("gg", "おつかれ")
        assertEquals("おつかれ", d.lookup("gg"))
        assertTrue(d.removeTerm("gg"))
        assertNull(d.lookup("gg"))
    }

    @Test
    fun droppedInPackIsLoaded() {
        val (d, _) = newDictionaries()
        d.load()
        val dir = d.javaClass.getDeclaredField("dir").apply { isAccessible = true }.get(d) as java.nio.file.Path
        Files.writeString(dir.resolve("my-pack.json"), """{"name":"My pack","keep":["Foo Bar"],"terms":{"hi":"やあ"}}""")
        d.load()
        val pack = d.packs.first { it.id == "my-pack" }
        assertEquals("My pack", pack.name)
        // 有効リストに無い辞書は使われない
        assertNull(d.lookup("hi"))
    }
}
