package com.aida0710.chattranslator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoreParagraphsTest {
    // 1 文字 = 幅 1 として扱う。行は既定ですべて文章・同じ色
    private fun split(vararg lines: String, wrapWidth: Int = lines.maxOf { it.length }) =
        LoreParagraphs.split(lines.map { LoreParagraphs.Line(it, prose = true, color = "gray") }, wrapWidth) { it.length }

    @Test
    fun joinsSentenceWrappedOntoNextLine() {
        assertEquals(
            listOf(0..1),
            split("Teleport 8 blocks ahead of you and", "gain +50 Speed for 3 seconds."),
        )
    }

    @Test
    fun keepsLabelLinesApart() {
        assertEquals(listOf(0..0, 1..1), split("Amount: 3x", "Total: 1,153,433 coins"))
        assertEquals(listOf(0..0, 1..1), split("Lowest sell price: 1,372.4 coins", "Highest buy price: 1,350.7 coins"))
    }

    @Test
    fun labelLineDoesNotSwallowFollowingDescription() {
        assertEquals(
            listOf(0..0, 1..2),
            split("Fuel Tank: Not Installed", "Increases fuel capacity with part", "installed."),
        )
    }

    @Test
    fun keepsListItemsApart() {
        assertEquals(listOf(0..0, 1..1, 2..2), split("✔ Ability Chat", "✔ Ability Cooldown Chat", "✔ Pristine Chat"))
        assertEquals(listOf(0..0, 1..1), split("▶ Divan Fragment", "▶ Corleonite"))
        assertEquals(listOf(0..0, 1..1), split("∙ Zombies", "∙ Hide and Seek"))
    }

    @Test
    fun listItemKeepsItsWrappedContinuation() {
        assertEquals(
            listOf(0..1, 2..2),
            split("■ Gain +15% more Powder while", "mining.", "■ -20% Pickaxe Ability cooldowns."),
        )
    }

    @Test
    fun keepsSentencesThatEndAtLineEndApart() {
        assertEquals(listOf(0..0, 1..1), split("Hits 3 mobs on impact.", "Can damage endermen.", wrapWidth = 34))
    }

    @Test
    fun keepsShortHeadingApartFromCapitalizedLine() {
        assertEquals(
            listOf(0..0, 1..1),
            split("Your Other Crystals", "Jasper ✖ Not Found", wrapWidth = 34),
        )
    }

    @Test
    fun joinsCapitalizedContinuationWhenPreviousLineIsFull() {
        // "Grants a bonus to all nearby Zombies" は折り返し幅に収まらないので、次の行は折り返しの続き
        assertEquals(listOf(0..1), split("Grants a bonus to all nearby", "Zombies and Skeletons.", wrapWidth = 30))
    }

    @Test
    fun joinsCapitalizedContinuationAfterDanglingWord() {
        // "to" や "," で終わる行は文の途中なので、次の行が大文字 (固有名詞など) で始まっても続き
        assertEquals(listOf(0..1), split("Deals +50% damage to", "Withers. Grants +1 Damage", wrapWidth = 34))
        assertEquals(listOf(0..1), split("Increases damage by 10%,", "Strength by 5.", wrapWidth = 34))
        // 語の一部の記号や、大文字の 1 文字では続きとみなさない
        assertEquals(listOf(0..0, 1..1), split("Your rank is MVP+", "Click to view!", wrapWidth = 34))
        assertEquals(listOf(0..0, 1..1), split("Grade: A", "Click to view!", wrapWidth = 34))
    }

    @Test
    fun doesNotJoinAcrossColorsOrNonProseLines() {
        val lines = listOf(
            LoreParagraphs.Line("Gives a bonus to all", true, "gray"),
            LoreParagraphs.Line("nearby players.", true, "gold"),
            LoreParagraphs.Line("Something else that wraps", true, "gray"),
            LoreParagraphs.Line("", false, null),
            LoreParagraphs.Line("onto another line.", true, "gray"),
        )
        assertEquals(listOf(0..0, 1..1, 2..2, 3..3, 4..4), LoreParagraphs.split(lines, 30) { it.length })
    }

    @Test
    fun detectsListItemsAndLabels() {
        assertTrue(LoreParagraphs.isListItem("✔ Ability Chat"))
        assertTrue(LoreParagraphs.isListItem("+50 Strength"))
        assertTrue(LoreParagraphs.isListItem("6 Attack Damage"))
        assertFalse(LoreParagraphs.isListItem("\"A quote that"))
        assertFalse(LoreParagraphs.isListItem("(Right-click to use)"))
        assertFalse(LoreParagraphs.isListItem(" Speed for 3 seconds."))

        assertTrue(LoreParagraphs.isLabel("Amount: 3x"))
        assertTrue(LoreParagraphs.isLabel("Time Between Actions: 48.5s ➜ 41s"))
        assertTrue(LoreParagraphs.isLabel("ダメージ：+100"))
        assertFalse(LoreParagraphs.isLabel("Teleport 8 blocks ahead of you and"))
        assertFalse(LoreParagraphs.isLabel("Next Event:"))
        assertFalse(LoreParagraphs.isLabel("This sentence is long. Then: something"))
    }
}
