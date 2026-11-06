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

    @Test
    fun fitWidthKeepsOriginalLineCountWithinLimit() {
        // 1 行の原文は、上限までなら 1 行に収める
        assertEquals(150, TextWrap.fitWidth(textWidth = 150, lines = 1, minWidth = 120, maxWidth = 180))
        assertEquals(180, TextWrap.fitWidth(textWidth = 400, lines = 1, minWidth = 120, maxWidth = 180))
        // 2 行の原文は 2 行に収まる幅 (少し余裕を持たせる)。元の幅より狭くはしない
        assertEquals(165, TextWrap.fitWidth(textWidth = 300, lines = 2, minWidth = 120, maxWidth = 180))
        assertEquals(120, TextWrap.fitWidth(textWidth = 100, lines = 2, minWidth = 120, maxWidth = 180))
    }

    @Test
    fun wrapsStyledSpansKeepingStyles() {
        val spans = listOf(Span("gray", "前方の"), Span("green", "8ブロック"), Span("gray", "を移動"))
        assertEquals(
            listOf(
                listOf(Span("gray", "前方の"), Span("green", "8ブ")),
                listOf(Span("green", "ロック"), Span("gray", "を移")),
                listOf(Span("gray", "動")),
            ),
            TextWrap.wrapSpans(spans, 5) { it.length },
        )
    }

    @Test
    fun wrapSpansDropsSpacesAtLineEdges() {
        val spans = listOf(Span("gray", "Gain "), Span("green", "+50"), Span("gray", " Speed now"))
        assertEquals(
            listOf(listOf(Span("gray", "Gain "), Span("green", "+50")), listOf(Span("gray", "Speed now"))),
            TextWrap.wrapSpans(spans, 9) { it.length },
        )
    }
}
