package com.aida0710.chattranslator

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvidersTest {
    private class Captured {
        var body: JsonObject = JsonObject()
        var headers: Map<String, String> = emptyMap()
        var query: String? = null
    }

    /** 指定パスで固定のレスポンスを返すモックサーバー */
    private fun withServer(path: String, response: String, block: (base: String, captured: Captured) -> Unit) {
        val captured = Captured()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { ex ->
            captured.body = JsonParser.parseString(ex.requestBody.readAllBytes().decodeToString()).asJsonObject
            captured.headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            captured.query = ex.requestURI.query
            val bytes = response.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", captured)
        } finally {
            server.stop(0)
        }
    }

    private val openAiResponse = """{"choices":[{"message":{"role":"assistant","content":"<think>x</think>\n「こんにちは」\n"}}]}"""
    private val base = TranslatorConfig.current

    @Test
    fun builtinSendsRawTextWithLanguageCodes() = withServer("/v1/chat/completions", openAiResponse) { url, c ->
        val p = BuiltinProvider({ "$url/v1" }, { true })
        assertTrue(p.isReady(base))
        assertEquals("こんにちは", p.translate("Hello", base.copy(sourceCode = "en", targetCode = "ko")))
        assertEquals("Hello", c.body.getAsJsonArray("messages")[0].asJsonObject["content"].asString)
        assertEquals("ko", c.body.getAsJsonObject("chat_template_kwargs")["target_lang_code"].asString)
    }

    @Test
    fun builtinTreatsAutoSourceAsEnglish() = withServer("/v1/chat/completions", openAiResponse) { url, c ->
        BuiltinProvider({ "$url/v1" }, { true }).translate("Hello", base.copy(sourceCode = Languages.AUTO))
        assertEquals("en", c.body.getAsJsonObject("chat_template_kwargs")["source_lang_code"].asString)
    }

    @Test
    fun builtinIsNotReadyBeforeServerStarts() {
        assertFalse(BuiltinProvider({ null }, { false }).isReady(base))
    }

    @Test
    fun openAiCompatibleUsesPromptTemplate() = withServer("/v1/chat/completions", openAiResponse) { url, c ->
        val cfg = base.copy(openaiEndpoint = "$url/v1", openaiApiKey = "sk-test")
        assertEquals("こんにちは", OpenAiCompatibleProvider().translate("Hello", cfg))
        val prompt = c.body.getAsJsonArray("messages")[0].asJsonObject["content"].asString
        assertTrue(prompt.endsWith("into Japanese:\n\n\nHello"), prompt)
        assertEquals("Bearer sk-test", c.headers["authorization"])
    }

    @Test
    fun openAiCompatibleWithoutTemplateUsesSystemInstructions() = withServer("/v1/chat/completions", openAiResponse) { url, c ->
        val cfg = base.copy(openaiEndpoint = "$url/v1", openaiUsePromptTemplate = false, openaiModel = "gpt-x", targetCode = "fr")
        OpenAiCompatibleProvider().translate("Hello", cfg)
        val messages = c.body.getAsJsonArray("messages")
        assertEquals("system", messages[0].asJsonObject["role"].asString)
        assertTrue(messages[0].asJsonObject["content"].asString.contains("to French"))
        assertEquals("Hello", messages[1].asJsonObject["content"].asString)
    }

    @Test
    fun claudeUsesMessagesApi() = withServer(
        "/v1/messages",
        """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"こんにちは"}],"stop_reason":"end_turn"}""",
    ) { url, c ->
        val cfg = base.copy(claudeApiKey = "sk-ant-test", claudeModel = "claude-haiku-5-5")
        assertEquals("こんにちは", ClaudeProvider(url).translate("Hello", cfg))
        assertEquals("sk-ant-test", c.headers["x-api-key"])
        assertEquals("2023-06-01", c.headers["anthropic-version"])
        assertEquals("claude-haiku-5-5", c.body["model"].asString)
        assertTrue(c.body["system"].asString.contains("to Japanese"))
        assertFalse(c.body.has("temperature"))
    }

    @Test
    fun claudeRefusalIsAnError() = withServer("/v1/messages", """{"content":[],"stop_reason":"refusal"}""") { url, _ ->
        assertFailsWith<IllegalStateException> { ClaudeProvider(url).translate("Hello", base.copy(claudeApiKey = "k")) }
    }

    @Test
    fun deeplSendsLanguageCodesAndAuthHeader() = withServer("/v2/translate", """{"translations":[{"text":"こんにちは"}]}""") { url, c ->
        val cfg = base.copy(deeplApiKey = "abc:fx", targetCode = "ja", sourceCode = "en")
        assertEquals("こんにちは", DeepLProvider(url).translate("Hello", cfg))
        assertEquals("DeepL-Auth-Key abc:fx", c.headers["authorization"])
        assertEquals("JA", c.body["target_lang"].asString)
        assertEquals("EN", c.body["source_lang"].asString)
    }

    @Test
    fun deeplOmitsSourceForAuto() = withServer("/v2/translate", """{"translations":[{"text":"x"}]}""") { url, c ->
        DeepLProvider(url).translate("Hello", base.copy(deeplApiKey = "k", sourceCode = Languages.AUTO))
        assertFalse(c.body.has("source_lang"))
    }

    @Test
    fun googleUsesKeyAndLanguageCodes() = withServer(
        "/language/translate/v2",
        """{"data":{"translations":[{"translatedText":"你好"}]}}""",
    ) { url, c ->
        val cfg = base.copy(googleApiKey = "gkey", targetCode = "zh-Hans")
        assertEquals("你好", GoogleProvider(url).translate("Hello", cfg))
        assertEquals("key=gkey", c.query)
        assertEquals("zh-CN", c.body["target"].asString)
        assertEquals("text", c.body["format"].asString)
    }
}
