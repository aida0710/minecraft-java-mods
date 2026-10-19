package com.aida0710.chattranslator

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.network.chat.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 翻訳エンジンの種類 */
enum class ProviderType {
    BUILTIN,
    OPENAI_COMPATIBLE,
    CLAUDE,
    DEEPL,
    GOOGLE,
    ;

    /** 画面表示用の名前 */
    val label get() = T.tr("provider.${name.lowercase()}")
}

/** 1 つの文を翻訳するエンジン */
interface TranslationProvider {
    /** 翻訳リクエストを送れる状態か (未インストール・API キー未設定・起動待ちなら false) */
    fun isReady(cfg: TranslatorConfig.Values): Boolean

    /** 準備ができていない理由 (案内表示用) */
    fun notReadyReason(cfg: TranslatorConfig.Values): Component

    fun translate(text: String, cfg: TranslatorConfig.Values): String
}

/** 共通の HTTP 処理 */
abstract class HttpProvider : TranslationProvider {
    protected val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    protected fun post(url: String, body: String, cfg: TranslatorConfig.Values, vararg headers: Pair<String, String>): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds.toLong()))
            .header("Content-Type", "application/json")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        return response.body()
    }

    /** LLM 向けの指示文 (クラウド LLM / OpenAI 互換で共通) */
    protected fun llmInstructions(cfg: TranslatorConfig.Values): String = buildString {
        append("Translate the user's text from ${Languages.englishName(cfg.sourceCode)} to ${Languages.englishName(cfg.targetCode)}. ")
        append("Output only the translation, with no explanations, quotes, or notes. ")
        append("Keep placeholder tokens such as ITEM1 and ITEM2 exactly as they are. ")
        if (cfg.llmContext.isNotBlank()) append(cfg.llmContext.trim())
    }

    companion object {
        /** 推論モデルの <think> ブロックや前後の引用符・改行を除去して 1 行にする */
        fun clean(raw: String): String =
            raw.replace(Regex("(?s)<think>.*?</think>"), "")
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("「", "」")
                .lines().map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(" ")

        fun message(role: String, content: String) = JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }
    }
}

/** 内蔵 llama-server。TranslateGemma のテンプレートに言語コードを渡すので本文だけ送る */
class BuiltinProvider(private val endpoint: () -> String?, private val installed: () -> Boolean) : HttpProvider() {
    override fun isReady(cfg: TranslatorConfig.Values) = endpoint() != null

    override fun notReadyReason(cfg: TranslatorConfig.Values) =
        if (!installed()) T.tr("notready.builtin_not_installed")
        else T.tr("notready.builtin_starting")

    override fun translate(text: String, cfg: TranslatorConfig.Values): String {
        val url = endpoint() ?: throw IllegalStateException("built-in server is not ready")
        val body = JsonObject().apply {
            addProperty("model", cfg.modelKey)
            addProperty("temperature", cfg.temperature)
            addProperty("stream", false)
            add("messages", JsonArray().apply { add(message("user", text)) })
            add("chat_template_kwargs", JsonObject().apply {
                // TranslateGemma は自動検出に対応しないため英語として扱う
                addProperty("source_lang_code", if (cfg.sourceCode == Languages.AUTO) "en" else cfg.sourceCode)
                addProperty("target_lang_code", cfg.targetCode)
            })
        }
        return parseOpenAi(post("$url/chat/completions", body.toString(), cfg))
    }
}

/** OpenAI 互換の /v1/chat/completions (Ollama / LM Studio / llama.cpp / OpenAI / Gemini / OpenRouter など) */
class OpenAiCompatibleProvider : HttpProvider() {
    override fun isReady(cfg: TranslatorConfig.Values) = cfg.openaiEndpoint.isNotBlank()

    override fun notReadyReason(cfg: TranslatorConfig.Values) = T.tr("notready.openai")

    override fun translate(text: String, cfg: TranslatorConfig.Values): String {
        val body = JsonObject().apply {
            addProperty("model", cfg.openaiModel)
            addProperty("temperature", cfg.temperature)
            addProperty("stream", false)
            add("messages", JsonArray().apply {
                val system = cfg.openaiSystemPrompt.ifBlank { if (cfg.openaiUsePromptTemplate) "" else llmInstructions(cfg) }
                if (system.isNotBlank()) add(message("system", system))
                add(message("user", if (cfg.openaiUsePromptTemplate) cfg.buildPrompt(text) else text))
            })
        }
        val headers = if (cfg.openaiApiKey.isNotBlank()) arrayOf("Authorization" to "Bearer ${cfg.openaiApiKey}") else emptyArray()
        return parseOpenAi(post(cfg.openaiEndpoint.trimEnd('/') + "/chat/completions", body.toString(), cfg, *headers))
    }
}

private fun parseOpenAi(body: String): String =
    HttpProvider.clean(
        JsonParser.parseString(body).asJsonObject
            .getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("message")
            .get("content").asString,
    )

/**
 * Anthropic Messages API (POST /v1/messages)。
 * 依存ライブラリを mod に同梱すると Minecraft 本体のライブラリと衝突しやすいため、公式 SDK ではなく HTTP で直接呼ぶ。
 */
class ClaudeProvider(private val baseUrl: String = "https://api.anthropic.com") : HttpProvider() {
    override fun isReady(cfg: TranslatorConfig.Values) = cfg.claudeApiKey.isNotBlank()

    override fun notReadyReason(cfg: TranslatorConfig.Values) = T.tr("notready.api_key", "Claude")

    override fun translate(text: String, cfg: TranslatorConfig.Values): String {
        val body = JsonObject().apply {
            addProperty("model", cfg.claudeModel)
            addProperty("max_tokens", 1024)
            addProperty("system", llmInstructions(cfg))
            add("messages", JsonArray().apply { add(message("user", text)) })
            // 短い翻訳なので思考は最小限にして速度とコストを抑える (Haiku 4.5 は effort 非対応)
            if (!cfg.claudeModel.startsWith("claude-haiku-4")) {
                add("output_config", JsonObject().apply { addProperty("effort", "low") })
            }
        }
        val response = JsonParser.parseString(
            post(
                "$baseUrl/v1/messages", body.toString(), cfg,
                "x-api-key" to cfg.claudeApiKey,
                "anthropic-version" to "2023-06-01",
            ),
        ).asJsonObject
        if (response["stop_reason"]?.asString == "refusal") throw IllegalStateException("Claude declined to translate this text")
        val text = response.getAsJsonArray("content")
            .map { it.asJsonObject }
            .filter { it["type"]?.asString == "text" }
            .joinToString("") { it["text"].asString }
        return clean(text)
    }
}

/** DeepL API (無料版のキーは末尾が ":fx") */
class DeepLProvider(private val baseUrlOverride: String? = null) : HttpProvider() {
    override fun isReady(cfg: TranslatorConfig.Values) = cfg.deeplApiKey.isNotBlank()

    override fun notReadyReason(cfg: TranslatorConfig.Values) = T.tr("notready.api_key", "DeepL")

    override fun translate(text: String, cfg: TranslatorConfig.Values): String {
        val target = Languages.get(cfg.targetCode)?.deeplTarget
            ?: throw IllegalStateException(T.s("error.deepl_unsupported", Languages.displayName(cfg.targetCode)))
        val body = JsonObject().apply {
            add("text", JsonArray().apply { add(text) })
            addProperty("target_lang", target)
            Languages.get(cfg.sourceCode)?.deeplSource?.let { addProperty("source_lang", it) }
        }
        val host = if (cfg.deeplApiKey.trim().endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val response = post("${baseUrlOverride ?: "https://$host"}/v2/translate", body.toString(), cfg, "Authorization" to "DeepL-Auth-Key ${cfg.deeplApiKey.trim()}")
        return JsonParser.parseString(response).asJsonObject.getAsJsonArray("translations")[0].asJsonObject["text"].asString.trim()
    }
}

/** Google Cloud Translation API (Basic / v2) */
class GoogleProvider(private val baseUrl: String = "https://translation.googleapis.com") : HttpProvider() {
    override fun isReady(cfg: TranslatorConfig.Values) = cfg.googleApiKey.isNotBlank()

    override fun notReadyReason(cfg: TranslatorConfig.Values) = T.tr("notready.api_key", "Google")

    override fun translate(text: String, cfg: TranslatorConfig.Values): String {
        val body = JsonObject().apply {
            addProperty("q", text)
            addProperty("target", googleCode(cfg.targetCode))
            if (cfg.sourceCode != Languages.AUTO) addProperty("source", googleCode(cfg.sourceCode))
            addProperty("format", "text")
        }
        val key = URLEncoder.encode(cfg.googleApiKey.trim(), Charsets.UTF_8)
        val response = post("$baseUrl/language/translate/v2?key=$key", body.toString(), cfg)
        return JsonParser.parseString(response).asJsonObject.getAsJsonObject("data")
            .getAsJsonArray("translations")[0].asJsonObject["translatedText"].asString.trim()
    }

    private fun googleCode(code: String) = when (code) {
        "zh-Hans" -> "zh-CN"
        "zh-Hant" -> "zh-TW"
        else -> code
    }
}
