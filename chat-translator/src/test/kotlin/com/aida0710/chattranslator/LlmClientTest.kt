package com.aida0710.chattranslator

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmClientTest {
    @Test
    fun parsesOpenAiCompatibleResponse() {
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
            val endpoint = "http://127.0.0.1:${server.address.port}/v1"
            val result = LlmClient().translate("Hello", TranslatorConfig.current.copy(endpoint = endpoint))
            assertEquals("こんにちは", result)

            val req = JsonParser.parseString(requestBody).asJsonObject
            assertEquals("translategemma:4b", req["model"].asString)
            val prompt = req.getAsJsonArray("messages")[0].asJsonObject["content"].asString
            assertTrue(prompt.endsWith("into Japanese:\n\n\nHello"), prompt)
        } finally {
            server.stop(0)
        }
    }
}
