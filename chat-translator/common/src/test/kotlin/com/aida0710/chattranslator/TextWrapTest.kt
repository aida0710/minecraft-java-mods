package com.aida0710.chattranslator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextWrapTest {
    // 1 文字 = 幅 1 として扱う
    private fun wrap(text: String, max: Int) = TextWrap.wrap(text, max) { it.length }

    @Test
    fun wrapsJapaneseByWidth() {
        assertEquals(listOf("あいうえ", "おかきく", "け"), wrap("あいうえおかきくけ", 4))
    }

    @Test
    fun keepsNumbersAndWordsTogether() {
        val lines = wrap("周囲の敵に12,000のダメージを与える", 6)
        assertTrue(lines.any { it.contains("12,000") }, lines.toString())
        assertTrue(lines.none { it.endsWith("12,") }, lines.toString())
    }

    @Test
    fun avoidsPunctuationAtLineStart() {
        val lines = wrap("あいうえ。かきくけ", 4)
        assertTrue(lines.none { it.startsWith("。") }, lines.toString())
    }
}
