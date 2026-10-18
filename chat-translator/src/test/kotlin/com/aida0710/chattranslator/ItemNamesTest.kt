package com.aida0710.chattranslator

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemNamesTest {
    private val names = ItemNames(Files.createTempDirectory("items").resolve("items.json")).apply {
        setForTest(listOf("Aspect of the Dragons", "Aspect of the End", "Hyperion", "Summoning Eye", "Golden Dragon", "Stick", "SkyBlock Menu"))
    }

    @Test
    fun recognisesDecoratedItemNames() {
        assertTrue(names.isItemName("Aspect of the Dragons"))
        assertTrue(names.isItemName("Heroic Aspect of the Dragons ✪✪✪✪✪ ➊"))
        assertTrue(names.isItemName("Very Withered Hyperion ✪✪"))
        assertTrue(names.isItemName("[Lvl 100] Golden Dragon"))
        assertFalse(names.isItemName("Auction House"))
        // 短い一般語は対象外
        assertFalse(names.isItemName("Stick"))
    }

    @Test
    fun protectsAndRestoresNamesInSentences() {
        val p = names.protect("Your Aspect of the Dragons has been reforged! Also selling Hyperion and Aspect of the Dragons")
        assertEquals("Your ITEM1 has been reforged! Also selling ITEM2 and ITEM1", p.text)
        assertEquals(listOf("Aspect of the Dragons", "Hyperion"), p.names)
        assertEquals(
            "あなたの Aspect of the Dragons がリフォージされました！Hyperion と Aspect of the Dragons も売ります",
            names.restore("あなたの ITEM1 がリフォージされました！ITEM2 と ITEM1 も売ります", p.names),
        )
    }

    @Test
    fun prefersLongestMatchAndWordBoundaries() {
        assertEquals("ITEM1", names.protect("Aspect of the End").text)
        assertEquals("Hyperions are cool", names.protect("Hyperions are cool").text)
    }

    @Test
    fun matchesLowercaseChatAndRestoresCanonicalName() {
        val p = names.protect("anyone selling a hyperion? or aspect of the dragons")
        assertEquals("anyone selling a ITEM1? or ITEM2", p.text)
        assertEquals("Hyperion を売っている人", names.restore("ITEM1 を売っている人ITEM2", p.names)?.substringBefore("Aspect"))
        // 短い 1 単語の名前は小文字では一致させない
        assertEquals("a stick", names.protect("a stick").text)
    }

    @Test
    fun restoreFailsWhenPlaceholderIsLost() {
        val p = names.protect("RARE DROP! Summoning Eye (+150% Magic Find)")
        assertNull(names.restore("貴重なアイテム！ (魔法の発見力 +150%)", p.names))
    }

    @Test
    fun restoreToleratesModelVariants() {
        val p = names.protect("per Hyperion level")
        assertEquals("Hyperion レベル！", names.restore("ＩＴＥＭ１ レベル！", p.names))
        assertEquals("Hyperionレベル", names.restore("アイテム1レベル", p.names))
        assertEquals("Hyperion のレベル？", names.restore("[ITEM1] のレベル？", p.names))
    }

    @Test
    fun protectionTagChangesWithItemListAndKeepTerms() {
        val n = ItemNames(Files.createTempDirectory("items").resolve("items.json"))
        val empty = n.protectionTag
        n.setExtra(listOf("Catacombs"))
        val withKeep = n.protectionTag
        n.setForTest(listOf("Hyperion"))
        val withItems = n.protectionTag
        assertTrue(empty != withKeep && withKeep != withItems && withItems.startsWith("items"))
    }
}
