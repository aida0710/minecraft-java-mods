package com.aida0710.chattranslator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextUtilTest {
    @Test
    fun stripsLegacyFormatting() {
        assertEquals("Hyperion", TextUtil.stripFormatting("§6§lHyperion"))
    }

    @Test
    fun detectsTranslatableEnglish() {
        assertTrue(TextUtil.looksTranslatable("Anyone selling a Hyperion?", 3))
        assertFalse(TextUtil.looksTranslatable("こんにちは everyone", 3))
        assertFalse(TextUtil.looksTranslatable("1,234/5,000❤ 300✎", 3))
        assertFalse(TextUtil.looksTranslatable("ok", 3))
    }

    @Test
    fun splitsHypixelChatPrefix() {
        assertEquals("[MVP+] Steve" to "anyone want to do dungeons?", TextUtil.splitChatPrefix("[MVP+] Steve: anyone want to do dungeons?"))
        assertEquals("Party > [VIP] Alex" to "ready", TextUtil.splitChatPrefix("Party > [VIP] Alex: ready"))
        assertEquals("" to "You found a Rare Drop!", TextUtil.splitChatPrefix("You found a Rare Drop!"))
    }
}
