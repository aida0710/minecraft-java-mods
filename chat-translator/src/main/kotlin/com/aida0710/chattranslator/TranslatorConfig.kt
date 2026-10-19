package com.aida0710.chattranslator

import net.minecraftforge.common.ForgeConfigSpec

enum class ChatDisplayMode { REPLACE, APPEND }

/**
 * config/chattranslator-client.toml の定義。読み込み/リロード/変更のたびに [current] を差し替える。
 * 設定画面からは [update] で値を書き換える (即保存)。
 */
object TranslatorConfig {
    data class Values(
        val chatEnabled: Boolean,
        val chatDisplayMode: ChatDisplayMode,
        val chatTranslateSystemMessages: Boolean,
        val tooltipEnabled: Boolean,
        val tooltipTranslateItemName: Boolean,
        val tooltipShowOriginal: Boolean,
        val minLength: Int,
        val sourceCode: String,
        val targetCode: String,
        val uiLanguage: String,
        val provider: ProviderType,
        val temperature: Double,
        val timeoutSeconds: Int,
        val llmContext: String,
        // builtin
        val llamaBuild: String,
        val gpuBackend: GpuBackend,
        val modelUrl: String,
        val modelSha256: String,
        val builtinGpuLayers: Int,
        val builtinContextSize: Int,
        val builtinPort: Int,
        val builtinExtraArgs: String,
        // openai compatible
        val openaiEndpoint: String,
        val openaiModel: String,
        val openaiApiKey: String,
        val openaiSystemPrompt: String,
        val openaiUsePromptTemplate: Boolean,
        val openaiPromptTemplate: String,
        val autoPull: Boolean,
        // cloud
        val claudeApiKey: String,
        val claudeModel: String,
        val deeplApiKey: String,
        val googleApiKey: String,
        // cache
        val cachePersist: Boolean,
        val maxCacheEntries: Int,
        // dictionaries
        val enabledDictionaries: List<String>,
    ) {
        /** キャッシュのキーに使う識別子 (エンジン・モデル・言語を変えたら訳し直す) */
        val modelKey: String
            get() {
                val model = when (provider) {
                    ProviderType.BUILTIN -> modelUrl.substringAfterLast('/')
                    ProviderType.OPENAI_COMPATIBLE -> openaiModel
                    ProviderType.CLAUDE -> claudeModel
                    ProviderType.DEEPL, ProviderType.GOOGLE -> ""
                }
                return "$provider:$model:$sourceCode>$targetCode"
            }

        fun buildPrompt(text: String): String = openaiPromptTemplate
            .replace("\\n", "\n")
            .replace("{source_lang}", Languages.englishName(sourceCode))
            .replace("{source_code}", sourceCode)
            .replace("{target_lang}", Languages.englishName(targetCode))
            .replace("{target_code}", targetCode)
            .replace("{text}", text)
    }

    /** TranslateGemma の公式プロンプト形式 (本文の前に空行2つ)。Ollama の translategemma で使う */
    const val TRANSLATEGEMMA_PROMPT =
        "You are a professional {source_lang} ({source_code}) to {target_lang} ({target_code}) translator. " +
            "Your goal is to accurately convey the meaning and nuances of the original {source_lang} text " +
            "while adhering to {target_lang} grammar, vocabulary, and cultural sensitivities.\\n" +
            "Produce only the {target_lang} translation, without any additional explanations or commentary. " +
            "Please translate the following {source_lang} text into {target_lang}:\\n\\n\\n{text}"

    const val DEFAULT_MODEL_URL = "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/main/translategemma-4b-it.Q4_K_M.gguf"
    const val DEFAULT_MODEL_SHA256 = "81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330"
    const val DEFAULT_LLM_CONTEXT =
        "The text comes from Minecraft multiplayer chat and item tooltips. Keep player names, numbers, and symbols unchanged, " +
            "and translate gaming slang naturally."

    private val b = ForgeConfigSpec.Builder()

    val chatEnabled: ForgeConfigSpec.BooleanValue
    val chatDisplayMode: ForgeConfigSpec.EnumValue<ChatDisplayMode>
    val chatSystem: ForgeConfigSpec.BooleanValue
    val tooltipEnabled: ForgeConfigSpec.BooleanValue
    val tooltipItemName: ForgeConfigSpec.BooleanValue
    val tooltipShowOriginal: ForgeConfigSpec.BooleanValue
    val minLength: ForgeConfigSpec.IntValue
    val sourceCode: ForgeConfigSpec.ConfigValue<String>
    val targetCode: ForgeConfigSpec.ConfigValue<String>
    val uiLanguage: ForgeConfigSpec.ConfigValue<String>
    val provider: ForgeConfigSpec.EnumValue<ProviderType>
    val temperature: ForgeConfigSpec.DoubleValue
    val timeout: ForgeConfigSpec.IntValue
    val llmContext: ForgeConfigSpec.ConfigValue<String>
    val llamaBuild: ForgeConfigSpec.ConfigValue<String>
    val gpuBackend: ForgeConfigSpec.EnumValue<GpuBackend>
    val modelUrl: ForgeConfigSpec.ConfigValue<String>
    val modelSha256: ForgeConfigSpec.ConfigValue<String>
    val gpuLayers: ForgeConfigSpec.IntValue
    val contextSize: ForgeConfigSpec.IntValue
    val port: ForgeConfigSpec.IntValue
    val extraArgs: ForgeConfigSpec.ConfigValue<String>
    val openaiEndpoint: ForgeConfigSpec.ConfigValue<String>
    val openaiModel: ForgeConfigSpec.ConfigValue<String>
    val openaiApiKey: ForgeConfigSpec.ConfigValue<String>
    val openaiSystemPrompt: ForgeConfigSpec.ConfigValue<String>
    val openaiUsePromptTemplate: ForgeConfigSpec.BooleanValue
    val openaiPromptTemplate: ForgeConfigSpec.ConfigValue<String>
    val autoPull: ForgeConfigSpec.BooleanValue
    val claudeApiKey: ForgeConfigSpec.ConfigValue<String>
    val claudeModel: ForgeConfigSpec.ConfigValue<String>
    val deeplApiKey: ForgeConfigSpec.ConfigValue<String>
    val googleApiKey: ForgeConfigSpec.ConfigValue<String>
    val cachePersist: ForgeConfigSpec.BooleanValue
    val maxCache: ForgeConfigSpec.IntValue
    val enabledDictionaries: ForgeConfigSpec.ConfigValue<MutableList<out String>>

    val SPEC: ForgeConfigSpec

    init {
        b.push("chat")
        chatEnabled = b.comment("チャットを翻訳する").define("enabled", true)
        chatDisplayMode = b.comment(
            "REPLACE: 原文を訳文に置き換える (行末の [原文] をクリックで切り替え、訳文にカーソルを合わせても原文を表示)",
            "APPEND:  原文の下に訳文を追加表示する",
        ).defineEnum("displayMode", ChatDisplayMode.REPLACE)
        chatSystem = b.comment("プレイヤー発言以外のシステムメッセージも翻訳する").define("translateSystemMessages", true)
        b.pop()

        b.push("tooltip")
        tooltipEnabled = b.comment("アイテムのツールチップ (説明文) を翻訳する").define("enabled", true)
        tooltipItemName = b.comment("アイテム名 (1行目) も翻訳する (辞書で保護されたアイテム名は英語のまま)").define("translateItemName", true)
        tooltipShowOriginal = b.comment("訳文の下に原文も残す").define("showOriginal", false)
        b.pop()

        b.push("filter")
        minLength = b.comment("これより短い文は翻訳しない").defineInRange("minLength", 3, 1, 1000)
        b.pop()

        b.push("language")
        sourceCode = b.comment("翻訳元の言語コード (auto = 自動検出。内蔵 LLM では英語として扱う)").define("source", "en")
        targetCode = b.comment("翻訳先の言語コード").define("target", "ja")
        uiLanguage = b.comment("mod の画面の表示言語 (auto = Minecraft に合わせる / en_us / ja_jp / zh_cn)").define("ui", T.AUTO)
        b.pop()

        b.push("engine")
        provider = b.comment(
            "BUILTIN:           内蔵 LLM (設定画面からインストール。ゲーム中だけ動く)",
            "OPENAI_COMPATIBLE: Ollama / LM Studio / OpenAI / Gemini / OpenRouter など",
            "CLAUDE:            Anthropic Claude API",
            "DEEPL:             DeepL API",
            "GOOGLE:            Google Cloud Translation API",
        ).defineEnum("provider", ProviderType.BUILTIN)
        temperature = b.defineInRange("temperature", 0.0, 0.0, 2.0)
        timeout = b.comment("1リクエストのタイムアウト秒").defineInRange("timeoutSeconds", 30, 1, 600)
        llmContext = b.comment("LLM (Claude / OpenAI 互換) への追加の指示").define("llmContext", DEFAULT_LLM_CONTEXT)
        b.pop()

        b.push("builtin")
        gpuBackend = b.comment(
            "VULKAN: NVIDIA / AMD / Intel の GPU で動作 (ダウンロード約 30MB、おすすめ)",
            "CUDA:   NVIDIA 専用。CUDA 13 (ドライバ 580 以降)。約 580MB",
            "CUDA12: NVIDIA 専用。古いドライバ向け",
            "CPU:    GPU を使わない (遅い)",
            "※ macOS は設定に関わらず Metal 版を使う",
        ).defineEnum("gpu", GpuBackend.VULKAN)
        modelUrl = b.comment("GGUF モデルのダウンロード URL").define("modelUrl", DEFAULT_MODEL_URL)
        modelSha256 = b.comment("モデルの SHA-256 (空なら検証しない)").define("modelSha256", DEFAULT_MODEL_SHA256)
        llamaBuild = b.comment("llama.cpp のリリースタグ").define("llamaBuild", "b11719")
        gpuLayers = b.comment("GPU に載せる層数 (99 = 全部)").defineInRange("gpuLayers", 99, 0, 999)
        contextSize = b.defineInRange("contextSize", 4096, 512, 131072)
        port = b.comment("待ち受けポート (0 = 自動)").defineInRange("port", 0, 0, 65535)
        extraArgs = b.comment("llama-server に追加で渡す引数 (空白区切り)").define("extraArgs", "")
        b.pop()

        b.push("openai")
        openaiEndpoint = b.comment(
            "OpenAI 互換 API のベース URL",
            "  Ollama:     http://localhost:11434/v1",
            "  LM Studio:  http://localhost:1234/v1",
            "  OpenAI:     https://api.openai.com/v1",
            "  Gemini:     https://generativelanguage.googleapis.com/v1beta/openai",
            "  OpenRouter: https://openrouter.ai/api/v1",
        ).define("endpoint", "http://localhost:11434/v1")
        openaiModel = b.comment("モデル名").define("model", "translategemma:4b")
        openaiApiKey = b.comment("API キー (ローカルなら空でよい)").define("apiKey", "")
        openaiSystemPrompt = b.comment("システムプロンプト (空なら自動)").define("systemPrompt", "")
        openaiUsePromptTemplate = b.comment(
            "true: 下の promptTemplate で本文を包んで送る (Ollama の translategemma など翻訳専用モデル向け)",
            "false: 指示はシステムプロンプトで渡し、本文だけ送る (GPT / Gemini など汎用モデル向け)",
        ).define("usePromptTemplate", true)
        openaiPromptTemplate = b.comment(
            "{source_lang} {source_code} {target_lang} {target_code} {text} が置換され、\\n は改行になる",
        ).define("promptTemplate", TRANSLATEGEMMA_PROMPT)
        autoPull = b.comment("Ollama の場合、モデルが無ければ自動で pull する").define("autoPull", true)
        b.pop()

        b.push("claude")
        claudeApiKey = b.comment("Anthropic API キー (https://platform.claude.com)").define("apiKey", "")
        claudeModel = b.comment("モデル ID (例: claude-haiku-5-5, claude-sonnet-5-5)").define("model", "claude-haiku-5-5")
        b.pop()

        b.push("deepl")
        deeplApiKey = b.comment("DeepL API キー (無料版は末尾が :fx)").define("apiKey", "")
        b.pop()

        b.push("google")
        googleApiKey = b.comment("Google Cloud Translation API キー").define("apiKey", "")
        b.pop()

        b.push("cache")
        cachePersist = b.comment("翻訳結果のキャッシュをファイルに保存し、次回起動時も使う (false ならゲーム終了で消える)").define("persist", true)
        maxCache = b.comment("キャッシュの最大件数").defineInRange("maxEntries", 20000, 100, 1_000_000)
        b.pop()

        b.push("dictionaries")
        enabledDictionaries = b.comment("有効な辞書 (config/chattranslator/dictionaries/<id>.json)")
            .defineListAllowEmpty("enabled", listOf("hypixel-skyblock", "user")) { it is String }
        b.pop()

        SPEC = b.build()
    }

    @Volatile var current: Values = read(defaults = true)
        private set

    private val listeners = mutableListOf<(Values) -> Unit>()

    /** 設定が変わったときに呼ばれる */
    fun onChange(listener: (Values) -> Unit) {
        listeners += listener
    }

    fun reload() {
        current = read(defaults = false)
        T.setUiLanguage(current.uiLanguage)
        listeners.forEach { it(current) }
    }

    /** 値を変更して即保存する (設定画面・キー操作・コマンドから) */
    fun <T : Any> update(value: ForgeConfigSpec.ConfigValue<T>, newValue: T) {
        if (value.get() == newValue) return
        value.set(newValue)
        value.save()
        reload()
    }

    private fun read(defaults: Boolean): Values {
        fun <T : Any> v(c: ForgeConfigSpec.ConfigValue<T>): T = if (defaults) c.default else c.get()
        return Values(
            chatEnabled = v(chatEnabled),
            chatDisplayMode = v(chatDisplayMode),
            chatTranslateSystemMessages = v(chatSystem),
            tooltipEnabled = v(tooltipEnabled),
            tooltipTranslateItemName = v(tooltipItemName),
            tooltipShowOriginal = v(tooltipShowOriginal),
            minLength = v(minLength),
            sourceCode = v(sourceCode),
            targetCode = v(targetCode),
            uiLanguage = v(uiLanguage),
            provider = v(provider),
            temperature = v(temperature),
            timeoutSeconds = v(timeout),
            llmContext = v(llmContext),
            llamaBuild = v(llamaBuild),
            gpuBackend = v(gpuBackend),
            modelUrl = v(modelUrl),
            modelSha256 = v(modelSha256),
            builtinGpuLayers = v(gpuLayers),
            builtinContextSize = v(contextSize),
            builtinPort = v(port),
            builtinExtraArgs = v(extraArgs),
            openaiEndpoint = v(openaiEndpoint),
            openaiModel = v(openaiModel),
            openaiApiKey = v(openaiApiKey),
            openaiSystemPrompt = v(openaiSystemPrompt),
            openaiUsePromptTemplate = v(openaiUsePromptTemplate),
            openaiPromptTemplate = v(openaiPromptTemplate),
            autoPull = v(autoPull),
            claudeApiKey = v(claudeApiKey),
            claudeModel = v(claudeModel),
            deeplApiKey = v(deeplApiKey),
            googleApiKey = v(googleApiKey),
            cachePersist = v(cachePersist),
            maxCacheEntries = v(maxCache),
            enabledDictionaries = v(enabledDictionaries).map { it },
        )
    }
}
