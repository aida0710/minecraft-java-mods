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
        assertTrue(TextUtil.looksTranslatable("Anyone selling a Hyperion?", 3, "en", "ja"))
        assertFalse(TextUtil.looksTranslatable("こんにちは everyone", 3, "en", "ja"))
        assertFalse(TextUtil.looksTranslatable("1,234/5,000❤ 300✎", 3, "en", "ja"))
        assertFalse(TextUtil.looksTranslatable("ok", 3, "en", "ja"))
    }

    @Test
    fun supportsOtherLanguagePairs() {
        // 日本語 → 英語: 日本語の文だけ訳す
        assertTrue(TextUtil.looksTranslatable("一緒にダンジョン行こう", 3, "ja", "en"))
        assertTrue(TextUtil.looksTranslatable("漢字だけの文章", 3, "ja", "en"))
        assertFalse(TextUtil.looksTranslatable("let us go dungeon", 3, "ja", "en"))
        // 英語 → 韓国語: すでにハングルなら訳さない
        assertFalse(TextUtil.looksTranslatable("안녕하세요 친구", 3, "en", "ko"))
        // 中国語向け: かなを含む日本語は中国語ではないので訳す対象 (自動検出)
        assertTrue(TextUtil.looksTranslatable("こんにちは世界", 3, Languages.AUTO, "zh-Hans"))
        assertFalse(TextUtil.looksTranslatable("你好世界朋友", 3, Languages.AUTO, "zh-Hans"))
        // ロシア語 → 英語
        assertTrue(TextUtil.looksTranslatable("Привет всем", 3, "ru", "en"))
    }

    @Test
    fun splitsHypixelChatPrefix() {
        assertEquals("[MVP+] Steve" to "anyone want to do dungeons?", TextUtil.splitChatPrefix("[MVP+] Steve: anyone want to do dungeons?"))
        assertEquals("Party > [VIP] Alex" to "ready", TextUtil.splitChatPrefix("Party > [VIP] Alex: ready"))
        assertEquals("" to "You found a Rare Drop!", TextUtil.splitChatPrefix("You found a Rare Drop!"))
    }
}
