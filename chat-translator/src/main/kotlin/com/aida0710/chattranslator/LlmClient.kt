package com.aida0710.chattranslator

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * OpenAI 互換の /v1/chat/completions を叩くクライアント。
 * Ollama / LM Studio / llama.cpp server / vLLM などで動作する。
 */
class LlmClient {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun translate(text: String, cfg: TranslatorConfig.Values = TranslatorConfig.current): String {
        val body = JsonObject().apply {
            addProperty("model", cfg.model)
            addProperty("temperature", cfg.temperature)
            addProperty("stream", false)
            add("messages", JsonArray().apply {
                if (cfg.systemPrompt.isNotBlank()) add(message("system", cfg.systemPrompt))
                add(message("user", cfg.buildPrompt(text)))
            })
        }
        val request = HttpRequest.newBuilder(URI.create(cfg.endpoint.trimEnd('/') + "/chat/completions"))
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds.toLong()))
            .header("Content-Type", "application/json")
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("LLM HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        val content = JsonParser.parseString(response.body()).asJsonObject
            .getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("message")
            .get("content").asString
        return clean(content)
    }

    private fun message(role: String, content: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    /** 推論モデルの <think> ブロックや前後の引用符・改行を除去して1行にする */
    private fun clean(raw: String): String =
        raw.replace(Regex("(?s)<think>.*?</think>"), "")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("「", "」")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ")
}
