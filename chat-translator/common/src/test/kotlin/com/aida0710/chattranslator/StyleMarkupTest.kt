package com.aida0710.chattranslator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StyleMarkupTest {
    // スタイルの代わりに色の名前 (文字列) を使う
    private fun spans(vararg parts: Pair<String, String>) = parts.map { (style, text) -> Span(style, text) }

    @Test
    fun wrapsColoredSpansInTags() {
        val encoded = assertNotNull(
            StyleMarkup.encode(spans("gray" to "Teleport ", "green" to "8 blocks", "gray" to " ahead of you"), "gray"),
        )
        assertEquals("Teleport <s1>8 blocks</s1> ahead of you", encoded.text)
        assertEquals(listOf("green"), encoded.styles)
    }

    @Test
    fun keepsSurroundingSpacesOutsideTags() {
        val encoded = assertNotNull(StyleMarkup.encode(spans("gray" to "Gain", "green" to " +50 ", "gray" to "Speed"), "gray"))
        assertEquals("Gain <s1>+50</s1> Speed", encoded.text)
    }

    @Test
    fun mergesSameStyleAcrossWhitespace() {
        // 行をつなげた空白や、色の付いていない空白をはさんだ同じ色の区間は 1 つのタグにする
        val encoded = assertNotNull(
            StyleMarkup.encode(spans("gray" to "for ", "green" to "3", "white" to " ", "green" to "seconds", "gray" to "."), "gray"),
        )
        assertEquals("for <s1>3 seconds</s1>.", encoded.text)
    }

    @Test
    fun noTagsWhenEverythingIsBaseStyle() {
        assertNull(StyleMarkup.encode(spans("gray" to "Can damage ", "gray" to "endermen."), "gray"))
    }

    @Test
    fun noTagsWhenTextHasMarkupCharacters() {
        assertNull(StyleMarkup.encode(spans("gray" to "Click ", "green" to "<here>"), "gray"))
        assertNull(StyleMarkup.encode(spans("gray" to "Salt & ", "green" to "Pepper"), "gray"))
    }

    @Test
    fun decodesTagsInTranslatedOrder() {
        val encoded = assertNotNull(
            StyleMarkup.encode(spans("gray" to "Teleport ", "green" to "8 blocks", "gray" to " and gain ", "aqua" to "+50"), "gray"),
        )
        assertEquals(
            spans("aqua" to "+50", "gray" to "を得て前方の", "green" to "8ブロック", "gray" to "をテレポート"),
            StyleMarkup.decode("<s2>+50</s2>を得て前方の<s1>8ブロック</s1>をテレポート", encoded, "gray"),
        )
    }

    @Test
    fun decodeAcceptsMissingTagsAndUnescapesEntities() {
        val encoded = assertNotNull(StyleMarkup.encode(spans("gray" to "A ", "green" to "B", "gray" to " ", "red" to "C"), "gray"))
        assertEquals(
            spans("gray" to "x & ", "green" to "y", "gray" to " z"),
            StyleMarkup.decode("x &amp; <s1>y</s1> z", encoded, "gray"),
        )
    }

    @Test
    fun decodeRejectsBrokenTags() {
        val encoded = assertNotNull(StyleMarkup.encode(spans("gray" to "A ", "green" to "B", "gray" to " ", "red" to "C"), "gray"))
        assertNull(StyleMarkup.decode("<s1>y z", encoded, "gray"), "unclosed")
        assertNull(StyleMarkup.decode("<s1>y <s2>z</s2></s1>", encoded, "gray"), "nested")
        assertNull(StyleMarkup.decode("<s3>y</s3>", encoded, "gray"), "unknown id")
        assertNull(StyleMarkup.decode("y</s1>", encoded, "gray"), "stray close")
        assertNull(StyleMarkup.decode("y z", encoded, "gray"), "all tags dropped")
    }

    @Test
    fun stripRemovesTags() {
        assertEquals("前方の8ブロック & x", StyleMarkup.strip("前方の<s1>8ブロック</s1> &amp; <S2 >x</ s2>"))
    }

    @Test
    fun detectsTags() {
        assertEquals(true, StyleMarkup.hasTags("Teleport <s1>8 blocks</s1>"))
        assertEquals(false, StyleMarkup.hasTags("<Steve> hello"))
    }

    @Test
    fun dominantStyleHasMostLetters() {
        assertEquals("red", StyleMarkup.dominant(spans("darkred" to "❣ ", "red" to "Requires ", "purple" to "HotM 7", "red" to ".")))
    }

    @Test
    fun slicesSpansByRange() {
        val s = spans("gray" to "abc", "green" to "def", "gray" to "gh")
        assertEquals(spans("gray" to "c", "green" to "def", "gray" to "g"), StyleMarkup.slice(s, 2..6))
    }
}
