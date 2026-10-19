package com.aida0710.chattranslator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendMessageTest {
    private val commands = listOf("pc", "gc", "msg 1", "w 1")

    @Test
    fun plainChatIsTranslatedWhole() {
        assertEquals(SendMessage.Parts("", "こんにちは"), SendMessage.split("こんにちは", commands))
    }

    @Test
    fun listedCommandKeepsTheCommandAndTranslatesTheRest() {
        assertEquals(SendMessage.Parts("/pc ", "よろしく"), SendMessage.split("/pc よろしく", commands))
        assertEquals(SendMessage.Parts("/PC  ", "よろしく"), SendMessage.split("/PC  よろしく", commands))
    }

    @Test
    fun commandWithRecipientKeepsTheRecipient() {
        assertEquals(SendMessage.Parts("/msg Steve ", "またね"), SendMessage.split("/msg Steve またね", commands))
        assertEquals(SendMessage.Parts("/w  Alex  ", "ありがとう"), SendMessage.split("/w  Alex  ありがとう", commands))
    }

    @Test
    fun unlistedCommandOrCommandWithoutTextIsNotTranslated() {
        assertNull(SendMessage.split("/warp hub", commands))
        assertNull(SendMessage.split("/pc", commands))
        assertNull(SendMessage.split("/msg Steve", commands))
        // 前方一致では拾わない (/pcx は /pc ではない)
        assertNull(SendMessage.split("/pcx こんにちは", commands))
    }

    @Test
    fun composeAddsTheOriginalOnlyWhenItFits() {
        val parts = SendMessage.Parts("/pc ", "よろしく")
        assertEquals("/pc Nice to meet you", SendMessage.compose(parts, "Nice to meet you", SendFormat.TRANSLATION))
        assertEquals("/pc Nice to meet you (よろしく)", SendMessage.compose(parts, "Nice to meet you", SendFormat.TRANSLATION_AND_ORIGINAL))

        val long = "a".repeat(SendMessage.MAX_LENGTH)
        assertEquals("/pc " + "a".repeat(SendMessage.MAX_LENGTH - 4), SendMessage.compose(parts, long, SendFormat.TRANSLATION_AND_ORIGINAL))
    }

    @Test
    fun composeKeepsTheTranslationOnOneLine() {
        assertEquals("Hello there", SendMessage.compose(SendMessage.Parts("", "やあ"), "Hello\nthere", SendFormat.TRANSLATION))
    }
}
