package com.aida0710.chattranslator

enum class ChatDisplayMode { REPLACE, APPEND }

/**
 * config/chattranslator-client.toml の定義。読み込み・変更のたびに [current] を差し替える。
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

    val chatEnabled = BooleanSetting("chat.enabled", true, "チャットを翻訳する")
    val chatDisplayMode = EnumSetting(
        "chat.displayMode", ChatDisplayMode.REPLACE, ChatDisplayMode.entries,
        "REPLACE: 原文を訳文に置き換える (行末の [原文] をクリックで切り替え、訳文にカーソルを合わせても原文を表示)",
        "APPEND:  原文の下に訳文を追加表示する",
    )
    val chatSystem = BooleanSetting("chat.translateSystemMessages", true, "プレイヤー発言以外のシステムメッセージも翻訳する")

    val tooltipEnabled = BooleanSetting("tooltip.enabled", true, "アイテムのツールチップ (説明文) を翻訳する")
    val tooltipItemName = BooleanSetting("tooltip.translateItemName", true, "アイテム名 (1行目) も翻訳する (辞書で保護されたアイテム名は英語のまま)")
    val tooltipShowOriginal = BooleanSetting("tooltip.showOriginal", false, "訳文の下に原文も残す")

    val minLength = IntSetting("filter.minLength", 3, 1..1000, "これより短い文は翻訳しない")

    val sourceCode = StringSetting("language.source", "en", "翻訳元の言語コード (auto = 自動検出。内蔵 LLM では英語として扱う)")
    val targetCode = StringSetting("language.target", "ja", "翻訳先の言語コード")
    val uiLanguage = StringSetting("language.ui", T.AUTO, "mod の画面の表示言語 (auto = Minecraft に合わせる / en_us / ja_jp / zh_cn)")

    val provider = EnumSetting(
        "engine.provider", ProviderType.BUILTIN, ProviderType.entries,
        "BUILTIN:           内蔵 LLM (設定画面からインストール。ゲーム中だけ動く)",
        "OPENAI_COMPATIBLE: Ollama / LM Studio / OpenAI / Gemini / OpenRouter など",
        "CLAUDE:            Anthropic Claude API",
        "DEEPL:             DeepL API",
        "GOOGLE:            Google Cloud Translation API",
    )
    val temperature = DoubleSetting("engine.temperature", 0.0, 0.0..2.0)
    val timeout = IntSetting("engine.timeoutSeconds", 30, 1..600, "1リクエストのタイムアウト秒")
    val llmContext = StringSetting("engine.llmContext", DEFAULT_LLM_CONTEXT, "LLM (Claude / OpenAI 互換) への追加の指示")

    val gpuBackend = EnumSetting(
        "builtin.gpu", GpuBackend.VULKAN, GpuBackend.entries,
        "VULKAN: NVIDIA / AMD / Intel の GPU で動作 (ダウンロード約 30MB、おすすめ)",
        "CUDA:   NVIDIA 専用。CUDA 13 (ドライバ 580 以降)。約 580MB",
        "CUDA12: NVIDIA 専用。古いドライバ向け",
        "CPU:    GPU を使わない (遅い)",
        "※ macOS は設定に関わらず Metal 版を使う",
    )
    val modelUrl = StringSetting("builtin.modelUrl", DEFAULT_MODEL_URL, "GGUF モデルのダウンロード URL")
    val modelSha256 = StringSetting("builtin.modelSha256", DEFAULT_MODEL_SHA256, "モデルの SHA-256 (空なら検証しない)")
    val llamaBuild = StringSetting("builtin.llamaBuild", "b11719", "llama.cpp のリリースタグ")
    val gpuLayers = IntSetting("builtin.gpuLayers", 99, 0..999, "GPU に載せる層数 (99 = 全部)")
    val contextSize = IntSetting("builtin.contextSize", 4096, 512..131072)
    val port = IntSetting("builtin.port", 0, 0..65535, "待ち受けポート (0 = 自動)")
    val extraArgs = StringSetting("builtin.extraArgs", "", "llama-server に追加で渡す引数 (空白区切り)")

    val openaiEndpoint = StringSetting(
        "openai.endpoint", "http://localhost:11434/v1",
        "OpenAI 互換 API のベース URL",
        "  Ollama:     http://localhost:11434/v1",
        "  LM Studio:  http://localhost:1234/v1",
        "  OpenAI:     https://api.openai.com/v1",
        "  Gemini:     https://generativelanguage.googleapis.com/v1beta/openai",
        "  OpenRouter: https://openrouter.ai/api/v1",
    )
    val openaiModel = StringSetting("openai.model", "translategemma:4b", "モデル名")
    val openaiApiKey = StringSetting("openai.apiKey", "", "API キー (ローカルなら空でよい)")
    val openaiSystemPrompt = StringSetting("openai.systemPrompt", "", "システムプロンプト (空なら自動)")
    val openaiUsePromptTemplate = BooleanSetting(
        "openai.usePromptTemplate", true,
        "true: 下の promptTemplate で本文を包んで送る (Ollama の translategemma など翻訳専用モデル向け)",
        "false: 指示はシステムプロンプトで渡し、本文だけ送る (GPT / Gemini など汎用モデル向け)",
    )
    val openaiPromptTemplate = StringSetting(
        "openai.promptTemplate", TRANSLATEGEMMA_PROMPT,
        "{source_lang} {source_code} {target_lang} {target_code} {text} が置換され、\\n は改行になる",
    )
    val autoPull = BooleanSetting("openai.autoPull", true, "Ollama の場合、モデルが無ければ自動で pull する")

    val claudeApiKey = StringSetting("claude.apiKey", "", "Anthropic API キー (https://platform.claude.com)")
    val claudeModel = StringSetting("claude.model", "claude-haiku-5-5", "モデル ID (例: claude-haiku-5-5, claude-sonnet-5-5)")
    val deeplApiKey = StringSetting("deepl.apiKey", "", "DeepL API キー (無料版は末尾が :fx)")
    val googleApiKey = StringSetting("google.apiKey", "", "Google Cloud Translation API キー")

    val cachePersist = BooleanSetting("cache.persist", true, "翻訳結果のキャッシュをファイルに保存し、次回起動時も使う (false ならゲーム終了で消える)")
    val maxCache = IntSetting("cache.maxEntries", 20000, 100..1_000_000, "キャッシュの最大件数")

    val enabledDictionaries = StringListSetting(
        "dictionaries.enabled", listOf("hypixel-skyblock", "user"),
        "有効な辞書 (config/chattranslator/dictionaries/<id>.json)",
    )

    /** ファイルに書く順 (表ごとにまとまるよう定義順に並べる) */
    internal val all: List<Setting<*>> = listOf(
        chatEnabled, chatDisplayMode, chatSystem,
        tooltipEnabled, tooltipItemName, tooltipShowOriginal,
        minLength,
        sourceCode, targetCode, uiLanguage,
        provider, temperature, timeout, llmContext,
        gpuBackend, modelUrl, modelSha256, llamaBuild, gpuLayers, contextSize, port, extraArgs,
        openaiEndpoint, openaiModel, openaiApiKey, openaiSystemPrompt, openaiUsePromptTemplate, openaiPromptTemplate, autoPull,
        claudeApiKey, claudeModel, deeplApiKey, googleApiKey,
        cachePersist, maxCache,
        enabledDictionaries,
    )

    /** 読み込み前 (テストなど) は null で、[current] は既定値のまま */
    private var file: SettingsFile? = null

    @Volatile var current: Values = read()
        private set

    private val listeners = mutableListOf<(Values) -> Unit>()

    /** 設定が変わったときに呼ばれる */
    fun onChange(listener: (Values) -> Unit) {
        listeners += listener
    }

    /** 設定ファイルを読み込む (mod の起動時に 1 回) */
    fun load(path: java.nio.file.Path) {
        file = SettingsFile(path, all).also { it.load() }
        reload()
    }

    private fun reload() {
        current = read()
        T.setUiLanguage(current.uiLanguage)
        listeners.forEach { it(current) }
    }

    /** 値を変更して即保存する (設定画面・キー操作・コマンドから) */
    @Synchronized
    fun <T : Any> update(setting: Setting<T>, newValue: T) {
        val f = checkNotNull(file) { "TranslatorConfig.load has not been called" }
        if (f[setting] == newValue) return
        f[setting] = newValue
        reload()
    }

    private fun read(): Values {
        val f = file
        fun <T : Any> v(setting: Setting<T>): T = f?.get(setting) ?: setting.default
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
            enabledDictionaries = v(enabledDictionaries),
        )
    }
}
