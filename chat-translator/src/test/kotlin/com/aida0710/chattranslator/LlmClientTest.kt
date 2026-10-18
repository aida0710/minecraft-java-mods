package com.aida0710.chattranslator

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmClientTest {
    /** モック LLM サーバーを立てて、受け取ったリクエストを返す */
    private fun withServer(block: (endpoint: String, lastRequest: () -> JsonObject) -> Unit) {
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            requestBody = ex.requestBody.readAllBytes().decodeToString()
            val res = """{"choices":[{"message":{"role":"assistant","content":"<think>x</think>\n「こんにちは」\n"}}]}"""
                .toByteArray()
            ex.sendResponseHeaders(200, res.size.toLong())
            ex.responseBody.use { it.write(res) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/v1") { JsonParser.parseString(requestBody).asJsonObject }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun externalModeUsesPromptTemplate() = withServer { endpoint, req ->
        val cfg = TranslatorConfig.current.copy(backendMode = BackendMode.EXTERNAL, endpoint = endpoint)
        assertEquals("こんにちは", LlmClient().translate("Hello", cfg))

        assertEquals("translategemma:4b", req()["model"].asString)
        val prompt = req().getAsJsonArray("messages")[0].asJsonObject["content"].asString
        assertTrue(prompt.endsWith("into Japanese:\n\n\nHello"), prompt)
        assertFalse(req().has("chat_template_kwargs"))
    }

    @Test
    fun builtinModeSendsRawTextWithLanguageCodes() = withServer { endpoint, req ->
        val cfg = TranslatorConfig.current.copy(backendMode = BackendMode.BUILTIN)
        val client = LlmClient { endpoint }
        assertTrue(client.isReady(cfg))
        assertEquals("こんにちは", client.translate("Hello", cfg))

        assertEquals("Hello", req().getAsJsonArray("messages")[0].asJsonObject["content"].asString)
        val kwargs = req().getAsJsonObject("chat_template_kwargs")
        assertEquals("en", kwargs["source_lang_code"].asString)
        assertEquals("ja", kwargs["target_lang_code"].asString)
    }

    @Test
    fun builtinModeIsNotReadyBeforeServerStarts() {
        val cfg = TranslatorConfig.current.copy(backendMode = BackendMode.BUILTIN)
        assertFalse(LlmClient { null }.isReady(cfg))
    }
}
