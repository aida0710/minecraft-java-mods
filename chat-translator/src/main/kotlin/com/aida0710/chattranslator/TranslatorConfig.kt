package com.aida0710.chattranslator

import net.minecraftforge.common.ForgeConfigSpec

/** config/chattranslator-client.toml の定義。読み込み/リロードのたびに [current] を差し替える。 */
enum class ChatDisplayMode { REPLACE, APPEND }

object TranslatorConfig {
    data class Values(
        val chatEnabled: Boolean,
        val chatDisplayMode: ChatDisplayMode,
        val chatTranslateSystemMessages: Boolean,
        val tooltipEnabled: Boolean,
        val tooltipTranslateItemName: Boolean,
        val tooltipShowOriginal: Boolean,
        val minLength: Int,
        val endpoint: String,
        val model: String,
        val apiKey: String,
        val temperature: Double,
        val timeoutSeconds: Int,
        val systemPrompt: String,
        val promptTemplate: String,
        val sourceLang: String,
        val sourceCode: String,
        val targetLang: String,
        val targetCode: String,
        val autoPull: Boolean,
        val maxCacheEntries: Int,
    ) {
        fun buildPrompt(text: String): String = promptTemplate
            .replace("\\n", "\n")
            .replace("{source_lang}", sourceLang)
            .replace("{source_code}", sourceCode)
            .replace("{target_lang}", targetLang)
            .replace("{target_code}", targetCode)
            .replace("{text}", text)
    }

    /** TranslateGemma の公式プロンプト形式 (本文の前に空行2つ) */
    const val TRANSLATEGEMMA_PROMPT =
        "You are a professional {source_lang} ({source_code}) to {target_lang} ({target_code}) translator. " +
            "Your goal is to accurately convey the meaning and nuances of the original {source_lang} text " +
            "while adhering to {target_lang} grammar, vocabulary, and cultural sensitivities.\\n" +
            "Produce only the {target_lang} translation, without any additional explanations or commentary. " +
            "Please translate the following {source_lang} text into {target_lang}:\\n\\n\\n{text}"

    private val builder = ForgeConfigSpec.Builder()

    private val chatEnabled: ForgeConfigSpec.BooleanValue
    private val chatDisplayMode: ForgeConfigSpec.EnumValue<ChatDisplayMode>
    private val chatSystem: ForgeConfigSpec.BooleanValue
    private val tooltipEnabled: ForgeConfigSpec.BooleanValue
    private val tooltipItemName: ForgeConfigSpec.BooleanValue
    private val tooltipShowOriginal: ForgeConfigSpec.BooleanValue
    private val minLength: ForgeConfigSpec.IntValue
    private val endpoint: ForgeConfigSpec.ConfigValue<String>
    private val model: ForgeConfigSpec.ConfigValue<String>
    private val apiKey: ForgeConfigSpec.ConfigValue<String>
    private val temperature: ForgeConfigSpec.DoubleValue
    private val timeout: ForgeConfigSpec.IntValue
    private val systemPrompt: ForgeConfigSpec.ConfigValue<String>
    private val promptTemplate: ForgeConfigSpec.ConfigValue<String>
    private val sourceLang: ForgeConfigSpec.ConfigValue<String>
    private val sourceCode: ForgeConfigSpec.ConfigValue<String>
    private val targetLang: ForgeConfigSpec.ConfigValue<String>
    private val targetCode: ForgeConfigSpec.ConfigValue<String>
    private val autoPull: ForgeConfigSpec.BooleanValue
    private val maxCache: ForgeConfigSpec.IntValue

    val SPEC: ForgeConfigSpec

    init {
        builder.push("chat")
        chatEnabled = builder.comment("チャットを翻訳する").define("enabled", true)
        chatDisplayMode = builder.comment(
            "REPLACE: 原文を訳文に置き換える (行末の [原文] をクリックで切り替え、訳文にカーソルを合わせても原文を表示)",
            "APPEND:  原文の下に訳文を追加表示する",
        )
            .defineEnum("displayMode", ChatDisplayMode.REPLACE)
        chatSystem = builder.comment("プレイヤー発言以外のシステムメッセージも翻訳する").define("translateSystemMessages", true)
        builder.pop()

        builder.push("tooltip")
        tooltipEnabled = builder.comment("アイテムのツールチップ (説明文) を翻訳する").define("enabled", true)
        tooltipItemName = builder.comment("アイテム名 (1行目) も翻訳する").define("translateItemName", true)
        tooltipShowOriginal = builder.comment("訳文の下に原文も残す").define("showOriginal", false)
        builder.pop()

        builder.push("filter")
        minLength = builder.comment("これより短い文は翻訳しない").defineInRange("minLength", 3, 1, 1000)
        builder.pop()

        builder.push("llm")
        endpoint = builder.comment(
            "OpenAI 互換 API のベース URL",
            "  Ollama:    http://localhost:11434/v1",
            "  LM Studio: http://localhost:1234/v1",
            "  llama.cpp: http://localhost:8080/v1",
        ).define("endpoint", "http://localhost:11434/v1")
        model = builder.comment("モデル名 (例: translategemma:4b, translategemma:12b)").define("model", "translategemma:4b")
        apiKey = builder.comment("API キー (ローカルなら空でよい)").define("apiKey", "")
        temperature = builder.defineInRange("temperature", 0.0, 0.0, 2.0)
        timeout = builder.comment("1リクエストのタイムアウト秒").defineInRange("timeoutSeconds", 30, 1, 600)
        systemPrompt = builder.comment("システムプロンプト (TranslateGemma では空推奨)").define("systemPrompt", "")
        promptTemplate = builder.comment(
            "ユーザープロンプト。{source_lang} {source_code} {target_lang} {target_code} {text} が置換され、\\n は改行になる",
        ).define("promptTemplate", TRANSLATEGEMMA_PROMPT)
        sourceLang = builder.define("sourceLang", "English")
        sourceCode = builder.define("sourceCode", "en")
        targetLang = builder.define("targetLang", "Japanese")
        targetCode = builder.define("targetCode", "ja")
        autoPull = builder.comment("Ollama 使用時、モデルが未ダウンロードなら起動時に自動で pull する").define("autoPull", true)
        builder.pop()

        builder.push("cache")
        maxCache = builder.comment("翻訳キャッシュの最大件数 (再起動後に反映)").defineInRange("maxEntries", 20000, 100, 1_000_000)
        builder.pop()

        SPEC = builder.build()
    }

    @Volatile var current: Values = defaults()
        private set

    fun reload() {
        current = Values(
            chatEnabled = chatEnabled.get(),
            chatDisplayMode = chatDisplayMode.get(),
            chatTranslateSystemMessages = chatSystem.get(),
            tooltipEnabled = tooltipEnabled.get(),
            tooltipTranslateItemName = tooltipItemName.get(),
            tooltipShowOriginal = tooltipShowOriginal.get(),
            minLength = minLength.get(),
            endpoint = endpoint.get(),
            model = model.get(),
            apiKey = apiKey.get(),
            temperature = temperature.get(),
            timeoutSeconds = timeout.get(),
            systemPrompt = systemPrompt.get(),
            promptTemplate = promptTemplate.get(),
            sourceLang = sourceLang.get(),
            sourceCode = sourceCode.get(),
            targetLang = targetLang.get(),
            targetCode = targetCode.get(),
            autoPull = autoPull.get(),
            maxCacheEntries = maxCache.get(),
        )
    }

    private fun defaults() = Values(
        chatEnabled = true, chatDisplayMode = ChatDisplayMode.REPLACE, chatTranslateSystemMessages = true,
        tooltipEnabled = true, tooltipTranslateItemName = true, tooltipShowOriginal = false,
        minLength = 3, endpoint = "http://localhost:11434/v1", model = "translategemma:4b", apiKey = "",
        temperature = 0.0, timeoutSeconds = 30, systemPrompt = "", promptTemplate = TRANSLATEGEMMA_PROMPT,
        sourceLang = "English", sourceCode = "en", targetLang = "Japanese", targetCode = "ja", autoPull = true,
        maxCacheEntries = 20000,
    )
}
