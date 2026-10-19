package com.aida0710.chattranslator

/** 文字体系。「すでに翻訳先の言語で書かれているか」の簡易判定に使う */
enum class Script { LATIN, JAPANESE, HAN, HANGUL, CYRILLIC, GREEK, ARABIC, HEBREW, THAI, DEVANAGARI }

/**
 * 翻訳できる言語。
 * @param code      ISO 639-1 (TranslateGemma・Google 翻訳・LLM のプロンプトで使用)
 * @param english   英語名 (LLM へのプロンプトで使用)
 * @param native    画面表示用の名前
 * @param deeplSource / deeplTarget DeepL の言語コード (未対応なら null)
 */
data class Language(
    val code: String,
    val english: String,
    val native: String,
    val script: Script,
    val deeplSource: String?,
    val deeplTarget: String?,
)

object Languages {
    const val AUTO = "auto"

    val ALL: List<Language> = listOf(
        Language("en", "English", "English", Script.LATIN, "EN", "EN-US"),
        Language("ja", "Japanese", "日本語", Script.JAPANESE, "JA", "JA"),
        Language("zh-Hans", "Simplified Chinese", "简体中文", Script.HAN, "ZH", "ZH-HANS"),
        Language("zh-Hant", "Traditional Chinese", "繁體中文", Script.HAN, "ZH", "ZH-HANT"),
        Language("ko", "Korean", "한국어", Script.HANGUL, "KO", "KO"),
        Language("es", "Spanish", "Español", Script.LATIN, "ES", "ES"),
        Language("fr", "French", "Français", Script.LATIN, "FR", "FR"),
        Language("de", "German", "Deutsch", Script.LATIN, "DE", "DE"),
        Language("it", "Italian", "Italiano", Script.LATIN, "IT", "IT"),
        Language("pt", "Portuguese", "Português", Script.LATIN, "PT", "PT-BR"),
        Language("nl", "Dutch", "Nederlands", Script.LATIN, "NL", "NL"),
        Language("pl", "Polish", "Polski", Script.LATIN, "PL", "PL"),
        Language("sv", "Swedish", "Svenska", Script.LATIN, "SV", "SV"),
        Language("da", "Danish", "Dansk", Script.LATIN, "DA", "DA"),
        Language("fi", "Finnish", "Suomi", Script.LATIN, "FI", "FI"),
        Language("no", "Norwegian", "Norsk", Script.LATIN, "NB", "NB"),
        Language("cs", "Czech", "Čeština", Script.LATIN, "CS", "CS"),
        Language("hu", "Hungarian", "Magyar", Script.LATIN, "HU", "HU"),
        Language("ro", "Romanian", "Română", Script.LATIN, "RO", "RO"),
        Language("tr", "Turkish", "Türkçe", Script.LATIN, "TR", "TR"),
        Language("id", "Indonesian", "Bahasa Indonesia", Script.LATIN, "ID", "ID"),
        Language("vi", "Vietnamese", "Tiếng Việt", Script.LATIN, "VI", "VI"),
        Language("ru", "Russian", "Русский", Script.CYRILLIC, "RU", "RU"),
        Language("uk", "Ukrainian", "Українська", Script.CYRILLIC, "UK", "UK"),
        Language("el", "Greek", "Ελληνικά", Script.GREEK, "EL", "EL"),
        Language("ar", "Arabic", "العربية", Script.ARABIC, "AR", "AR"),
        Language("he", "Hebrew", "עברית", Script.HEBREW, "HE", "HE"),
        Language("th", "Thai", "ไทย", Script.THAI, "TH", "TH"),
        Language("hi", "Hindi", "हिन्दी", Script.DEVANAGARI, null, null),
    )

    private val byCode = ALL.associateBy { it.code }

    fun get(code: String): Language? = byCode[code]

    fun displayName(code: String): String = if (code == AUTO) T.s("lang.auto") else byCode[code]?.native ?: code

    /** 選択リスト用の名前 (例: 日本語 — Japanese) */
    fun menuName(code: String): String {
        val lang = byCode[code] ?: return displayName(code)
        return if (lang.native == lang.english) lang.native else "${lang.native} — ${lang.english}"
    }

    fun englishName(code: String): String = if (code == AUTO) "the source language" else byCode[code]?.english ?: code

    /** 文字 c がその文字体系に属するか */
    fun inScript(c: Char, script: Script): Boolean = when (script) {
        Script.LATIN -> c in 'A'..'Z' || c in 'a'..'z' || c in 'À'..'ɏ'
        Script.JAPANESE -> c in '぀'..'ヿ' || c in 'ｦ'..'ﾟ'
        Script.HAN -> c in '一'..'鿿' || c in '㐀'..'䶿'
        Script.HANGUL -> c in '가'..'힯' || c in 'ᄀ'..'ᇿ'
        Script.CYRILLIC -> c in 'Ѐ'..'ӿ'
        Script.GREEK -> c in 'Ͱ'..'Ͽ'
        Script.ARABIC -> c in '؀'..'ۿ'
        Script.HEBREW -> c in '֐'..'׿'
        Script.THAI -> c in '฀'..'๿'
        Script.DEVANAGARI -> c in 'ऀ'..'ॿ'
    }
}
