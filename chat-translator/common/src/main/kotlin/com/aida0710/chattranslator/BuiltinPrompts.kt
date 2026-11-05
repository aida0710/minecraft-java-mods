package com.aida0710.chattranslator

/** 推論サーバーの Completions API に渡す、組み立て済みのプロンプトと停止文字列 */
data class CompletionPrompt(val prompt: String, val stop: List<String>)

/**
 * 内蔵 LLM のプロンプトを組み立てる。
 * チャットテンプレートの扱いが llama.cpp・mlx-lm・vLLM で違い、TranslateGemma の変換版にはテンプレートが使えないものもある。
 * プロンプトを mod 側で作って Completions API に渡すと、どの推論サーバーでも同じ入力になる。
 * 文頭のトークンは 3 つのサーバーとも自動で付けるので、ここでは書かない。
 *
 * [text] は他のプレイヤーが書いた文でもある。特殊トークンの文字列がそのまま入ると、
 * サーバーがトークンとして解釈して指示を書き換えられるので、取り除いてから埋め込む。
 */
object BuiltinPrompts {
    fun build(family: ModelFamily, text: String, sourceCode: String, targetCode: String): CompletionPrompt = when (family) {
        ModelFamily.TRANSLATE_GEMMA -> translateGemma(text, sourceCode, targetCode)
        ModelFamily.PLAMO_TRANSLATE -> plamoTranslate(text, sourceCode, targetCode)
    }

    /** code (翻訳元・翻訳先の言語コード) をこのモデルで扱えるか */
    fun supportsLanguage(family: ModelFamily, code: String): Boolean = when (family) {
        ModelFamily.TRANSLATE_GEMMA -> true
        ModelFamily.PLAMO_TRANSLATE -> code == Languages.AUTO || plamoLanguageCode(code) in PLAMO_LANGUAGE_CODES
    }

    private fun translateGemma(text: String, sourceCode: String, targetCode: String): CompletionPrompt {
        // TranslateGemma は自動検出に対応しないため英語として扱う
        val source = if (sourceCode == Languages.AUTO) "en" else sourceCode
        val body = TranslatorConfig.TRANSLATEGEMMA_PROMPT
            .replace("\\n", "\n")
            .replace("{source_lang}", translateGemmaLanguageName(source))
            .replace("{source_code}", source)
            .replace("{target_lang}", translateGemmaLanguageName(targetCode))
            .replace("{target_code}", targetCode)
            .replace("{text}", text.replace(TRANSLATE_GEMMA_SPECIAL_TOKEN, ""))
        return CompletionPrompt("<start_of_turn>user\n$body<end_of_turn>\n<start_of_turn>model\n", listOf("<end_of_turn>"))
    }

    /** 公式のチャットテンプレートは、簡体字・繁体字のどちらも "Chinese" と書く */
    private fun translateGemmaLanguageName(code: String) = if (code.startsWith("zh-")) "Chinese" else Languages.englishName(code)

    /** PFML3 形式。形式の説明は pfnet/plamo-3-translate-* のモデルカードにある */
    private fun plamoTranslate(text: String, sourceCode: String, targetCode: String): CompletionPrompt {
        val sourceAttribute = if (sourceCode == Languages.AUTO) "" else "<|plamo:key|>lang<|plamo:val|>${plamoLanguageCode(sourceCode)}"
        val prompt = "<|plamo:tag|>dataset<|plamo:msg|>translation" +
            "<|plamo:tag|>input$sourceAttribute<|plamo:msg|>${text.replace(PLAMO_SPECIAL_TOKEN_PREFIX, "")}" +
            "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>${plamoLanguageCode(targetCode)}<|plamo:msg|>"
        return CompletionPrompt(prompt, listOf("<|plamo:tag|>", "<|plamo:bos|>"))
    }

    /** PLaMo の言語コードはすべて小文字 (zh-Hans → zh-hans) */
    private fun plamoLanguageCode(code: String) = code.lowercase()

    private val TRANSLATE_GEMMA_SPECIAL_TOKEN = Regex("<(start_of_turn|end_of_turn|bos|eos|pad)>")
    private const val PLAMO_SPECIAL_TOKEN_PREFIX = "<|plamo:"

    /** モデルカードの「対応言語」の表 */
    private val PLAMO_LANGUAGE_CODES = setOf(
        "ja", "en", "zh-hans", "zh-hant", "ko", "ar", "az", "be", "bg", "bn", "ca", "ceb", "de", "es", "et", "eu", "fa", "fr",
        "gu", "he", "hi", "hr", "hu", "hy", "id", "it", "jv", "ka", "kk", "km", "lt", "lv", "ml", "mr", "ms", "my", "ne", "nl",
        "pa", "pl", "ru", "si", "sk", "sl", "sr", "sw", "ta", "te", "th", "tl", "ur", "uz", "vi",
    )
}
