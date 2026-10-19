package com.aida0710.chattranslator

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsFileTest {
    private fun tempFile(text: String): Path =
        Files.createTempDirectory("settings").resolve("chattranslator-client.toml").also { Files.writeString(it, text) }

    @Test
    fun readsFileWrittenByForgeConfigSpec() {
        // Forge 版 (ForgeConfigSpec) が書いた形: タブでインデントし、Range / Allowed Values のコメントが付く
        val path = tempFile(
            """
            [chat]
            	#チャットを翻訳する
            	enabled = false
            	displayMode = "APPEND"
            [engine]
            	#Allowed Values: BUILTIN, OPENAI_COMPATIBLE, CLAUDE, DEEPL, GOOGLE
            	provider = "OPENAI_COMPATIBLE"
            	#Range: 0.0 ~ 2.0
            	temperature = 0.3
            [openai]
            	endpoint = "http://127.0.0.1:18434/v1"
            	promptTemplate = "line1\\nline2"
            [claude]
            	apiKey = "sk-test"
            [cache]
            	maxEntries = 500
            [dictionaries]
            	enabled = ["user"]
            """.trimIndent(),
        )
        val file = SettingsFile(path, TranslatorConfig.all).also { it.load() }

        assertEquals(false, file[TranslatorConfig.chatEnabled])
        assertEquals(ChatDisplayMode.APPEND, file[TranslatorConfig.chatDisplayMode])
        assertEquals(ProviderType.OPENAI_COMPATIBLE, file[TranslatorConfig.provider])
        assertEquals(0.3, file[TranslatorConfig.temperature])
        assertEquals("http://127.0.0.1:18434/v1", file[TranslatorConfig.openaiEndpoint])
        assertEquals("line1\\nline2", file[TranslatorConfig.openaiPromptTemplate])
        assertEquals("sk-test", file[TranslatorConfig.claudeApiKey])
        assertEquals(500, file[TranslatorConfig.maxCache])
        assertEquals(listOf("user"), file[TranslatorConfig.enabledDictionaries])
    }

    @Test
    fun replacesMissingAndInvalidValuesWithDefaultsAndWritesThemBack() {
        val path = tempFile(
            """
            [chat]
            	displayMode = "SIDEWAYS"
            [filter]
            	minLength = 0
            [engine]
            	temperature = "hot"
            """.trimIndent(),
        )
        val file = SettingsFile(path, TranslatorConfig.all).also { it.load() }

        assertEquals(ChatDisplayMode.REPLACE, file[TranslatorConfig.chatDisplayMode])
        assertEquals(3, file[TranslatorConfig.minLength])
        assertEquals(0.0, file[TranslatorConfig.temperature])
        assertEquals("ja", file[TranslatorConfig.targetCode])
        val text = Files.readString(path)
        assertTrue("minLength = 3" in text, text)
        assertTrue("target = \"ja\"" in text, text)
        assertTrue("#Allowed Values: REPLACE, APPEND" in text, text)
    }

    @Test
    fun savedValueIsReadBackAfterReopening() {
        val path = tempFile("")
        SettingsFile(path, TranslatorConfig.all).also { it.load() }[TranslatorConfig.uiLanguage] = "zh_cn"

        val reopened = SettingsFile(path, TranslatorConfig.all).also { it.load() }
        assertEquals("zh_cn", reopened[TranslatorConfig.uiLanguage])
    }
}
