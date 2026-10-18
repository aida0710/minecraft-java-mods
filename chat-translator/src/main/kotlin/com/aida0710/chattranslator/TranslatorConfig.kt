package com.aida0710.chattranslator

import net.minecraftforge.common.ForgeConfigSpec

enum class ChatDisplayMode { REPLACE, APPEND }

/** BUILTIN: llama.cpp を mod が自動で導入・起動 / EXTERNAL: 自分で立てた OpenAI 互換サーバー (Ollama など) を使う */
enum class BackendMode { BUILTIN, EXTERNAL }

/** config/chattranslator-client.toml の定義。読み込み/リロードのたびに [current] を差し替える。 */
object TranslatorConfig {
    data class Values(
        val chatEnabled: Boolean,
        val chatDisplayMode: ChatDisplayMode,
        val chatTranslateSystemMessages: Boolean,
        val tooltipEnabled: Boolean,
        val tooltipTranslateItemName: Boolean,
        val tooltipShowOriginal: Boolean,
        val minLength: Int,
        val sourceLang: String,
        val sourceCode: String,
        val targetLang: String,
        val targetCode: String,
        val temperature: Double,
        val timeoutSeconds: Int,
        val backendMode: BackendMode,
        // builtin
        val llamaBuild: String,
        val gpuBackend: GpuBackend,
        val modelUrl: String,
        val modelSha256: String,
        val builtinGpuLayers: Int,
        val builtinContextSize: Int,
        val builtinPort: Int,
        val builtinExtraArgs: String,
        // external
        val endpoint: String,
        val model: String,
        val apiKey: String,
        val systemPrompt: String,
        val promptTemplate: String,
        val autoPull: Boolean,
        val protectItemNames: Boolean,
        val maxCacheEntries: Int,
    ) {
        /** キャッシュのキーに使うモデル識別子 (モデルを変えたら訳し直すため) */
        val modelKey: String
            get() = if (backendMode == BackendMode.BUILTIN) modelUrl.substringAfterLast('/') else model

        fun buildPrompt(text: String): String = promptTemplate
            .replace("\\n", "\n")
            .replace("{source_lang}", sourceLang)
            .replace("{source_code}", sourceCode)
            .replace("{target_lang}", targetLang)
            .replace("{target_code}", targetCode)
            .replace("{text}", text)
    }

    /** TranslateGemma の公式プロンプト形式 (本文の前に空行2つ)。Ollama 等で使う */
    const val TRANSLATEGEMMA_PROMPT =
        "You are a professional {source_lang} ({source_code}) to {target_lang} ({target_code}) translator. " +
            "Your goal is to accurately convey the meaning and nuances of the original {source_lang} text " +
            "while adhering to {target_lang} grammar, vocabulary, and cultural sensitivities.\\n" +
            "Produce only the {target_lang} translation, without any additional explanations or commentary. " +
            "Please translate the following {source_lang} text into {target_lang}:\\n\\n\\n{text}"

    const val DEFAULT_MODEL_URL = "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/main/translategemma-4b-it.Q4_K_M.gguf"
    const val DEFAULT_MODEL_SHA256 = "81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330"

    private val b = ForgeConfigSpec.Builder()

    private val chatEnabled: ForgeConfigSpec.BooleanValue
    private val chatDisplayMode: ForgeConfigSpec.EnumValue<ChatDisplayMode>
    private val chatSystem: ForgeConfigSpec.BooleanValue
    private val tooltipEnabled: ForgeConfigSpec.BooleanValue
    private val tooltipItemName: ForgeConfigSpec.BooleanValue
    private val tooltipShowOriginal: ForgeConfigSpec.BooleanValue
    private val minLength: ForgeConfigSpec.IntValue
    private val sourceLang: ForgeConfigSpec.ConfigValue<String>
    private val sourceCode: ForgeConfigSpec.ConfigValue<String>
    private val targetLang: ForgeConfigSpec.ConfigValue<String>
    private val targetCode: ForgeConfigSpec.ConfigValue<String>
    private val temperature: ForgeConfigSpec.DoubleValue
    private val timeout: ForgeConfigSpec.IntValue
    private val backendMode: ForgeConfigSpec.EnumValue<BackendMode>
    private val llamaBuild: ForgeConfigSpec.ConfigValue<String>
    private val gpuBackend: ForgeConfigSpec.EnumValue<GpuBackend>
    private val modelUrl: ForgeConfigSpec.ConfigValue<String>
    private val modelSha256: ForgeConfigSpec.ConfigValue<String>
    private val gpuLayers: ForgeConfigSpec.IntValue
    private val contextSize: ForgeConfigSpec.IntValue
    private val port: ForgeConfigSpec.IntValue
    private val extraArgs: ForgeConfigSpec.ConfigValue<String>
    private val endpoint: ForgeConfigSpec.ConfigValue<String>
    private val model: ForgeConfigSpec.ConfigValue<String>
    private val apiKey: ForgeConfigSpec.ConfigValue<String>
    private val systemPrompt: ForgeConfigSpec.ConfigValue<String>
    private val promptTemplate: ForgeConfigSpec.ConfigValue<String>
    private val autoPull: ForgeConfigSpec.BooleanValue
    private val protectItemNames: ForgeConfigSpec.BooleanValue
    private val maxCache: ForgeConfigSpec.IntValue

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
        tooltipItemName = b.comment("アイテム名 (1行目) も翻訳する").define("translateItemName", true)
        tooltipShowOriginal = b.comment("訳文の下に原文も残す").define("showOriginal", false)
        b.pop()

        b.push("filter")
        minLength = b.comment("これより短い文は翻訳しない").defineInRange("minLength", 3, 1, 1000)
        b.pop()

        b.push("language")
        sourceLang = b.define("sourceLang", "English")
        sourceCode = b.define("sourceCode", "en")
        targetLang = b.define("targetLang", "Japanese")
        targetCode = b.define("targetCode", "ja")
        b.pop()

        b.push("backend")
        backendMode = b.comment(
            "BUILTIN:  llama.cpp とモデルを mod が自動でダウンロードし、ゲーム起動中だけ動かす (おすすめ)",
            "EXTERNAL: 自分で起動した OpenAI 互換サーバー (Ollama / LM Studio など) を使う。[external] を設定",
        ).defineEnum("mode", BackendMode.BUILTIN)
        temperature = b.defineInRange("temperature", 0.0, 0.0, 2.0)
        timeout = b.comment("1リクエストのタイムアウト秒").defineInRange("timeoutSeconds", 30, 1, 600)
        b.pop()

        b.push("builtin")
        gpuBackend = b.comment(
            "VULKAN: NVIDIA / AMD / Intel の GPU で動作 (ダウンロード約 30MB、おすすめ)",
            "CUDA:   NVIDIA 専用。CUDA 13 (ドライバ 580 以降)。約 580MB",
            "CUDA12: NVIDIA 専用。古いドライバ向け",
            "CPU:    GPU を使わない (遅い)",
            "※ macOS は設定に関わらず Metal 版を使う",
        ).defineEnum("gpu", GpuBackend.VULKAN)
        modelUrl = b.comment(
            "GGUF モデルのダウンロード URL (品質重視なら translategemma-12b-it の GGUF。VRAM 10GB 以上推奨)",
        ).define("modelUrl", DEFAULT_MODEL_URL)
        modelSha256 = b.comment("モデルの SHA-256 (空なら検証しない。modelUrl を変えたら空にするか正しい値に)").define("modelSha256", DEFAULT_MODEL_SHA256)
        llamaBuild = b.comment("llama.cpp のリリースタグ (https://github.com/ggml-org/llama.cpp/releases)").define("llamaBuild", "b11719")
        gpuLayers = b.comment("GPU に載せる層数 (99 = 全部)").defineInRange("gpuLayers", 99, 0, 999)
        contextSize = b.defineInRange("contextSize", 4096, 512, 131072)
        port = b.comment("待ち受けポート (0 = 空いているポートを自動選択)").defineInRange("port", 0, 0, 65535)
        extraArgs = b.comment("llama-server に追加で渡す引数 (空白区切り)").define("extraArgs", "")
        b.pop()

        b.push("external")
        endpoint = b.comment(
            "OpenAI 互換 API のベース URL",
            "  Ollama:    http://localhost:11434/v1",
            "  LM Studio: http://localhost:1234/v1",
            "  llama.cpp: http://localhost:8080/v1",
        ).define("endpoint", "http://localhost:11434/v1")
        model = b.comment("モデル名 (例: translategemma:4b, translategemma:12b)").define("model", "translategemma:4b")
        apiKey = b.comment("API キー (ローカルなら空でよい)").define("apiKey", "")
        systemPrompt = b.comment("システムプロンプト (TranslateGemma では空推奨)").define("systemPrompt", "")
        promptTemplate = b.comment(
            "ユーザープロンプト。{source_lang} {source_code} {target_lang} {target_code} {text} が置換され、\\n は改行になる",
        ).define("promptTemplate", TRANSLATEGEMMA_PROMPT)
        autoPull = b.comment("Ollama 使用時、モデルが未ダウンロードなら起動時に自動で pull する").define("autoPull", true)
        b.pop()

        b.push("skyblock")
        protectItemNames = b.comment(
            "Hypixel SkyBlock のアイテム名 (公開 API から取得) を翻訳せず英語のまま残す",
            "ツールチップのアイテム名行は英語のまま、文中のアイテム名も英語で残す",
        ).define("protectItemNames", true)
        b.pop()

        b.push("cache")
        maxCache = b.comment("翻訳キャッシュの最大件数 (再起動後に反映)").defineInRange("maxEntries", 20000, 100, 1_000_000)
        b.pop()

        SPEC = b.build()
    }

    @Volatile var current: Values = read(defaults = true)
        private set

    fun reload() {
        current = read(defaults = false)
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
            sourceLang = v(sourceLang),
            sourceCode = v(sourceCode),
            targetLang = v(targetLang),
            targetCode = v(targetCode),
            temperature = v(temperature),
            timeoutSeconds = v(timeout),
            backendMode = v(backendMode),
            llamaBuild = v(llamaBuild),
            gpuBackend = v(gpuBackend),
            modelUrl = v(modelUrl),
            modelSha256 = v(modelSha256),
            builtinGpuLayers = v(gpuLayers),
            builtinContextSize = v(contextSize),
            builtinPort = v(port),
            builtinExtraArgs = v(extraArgs),
            endpoint = v(endpoint),
            model = v(model),
            apiKey = v(apiKey),
            systemPrompt = v(systemPrompt),
            promptTemplate = v(promptTemplate),
            autoPull = v(autoPull),
            protectItemNames = v(protectItemNames),
            maxCacheEntries = v(maxCache),
        )
    }
}
