package com.aida0710.chattranslator

/**
 * ON/OFF を切り替えられる翻訳の機能。キー割り当てとコマンド「/chattranslator toggle <id>」から使う。
 * 表示名は言語ファイルの chattranslator.toggle.<id>
 */
enum class Feature(val id: String, val setting: BooleanSetting) {
    CHAT("chat", TranslatorConfig.chatEnabled),
    TOOLTIP("tooltip", TranslatorConfig.tooltipEnabled),
    BOOK("book", TranslatorConfig.bookEnabled),
    SEND("send", TranslatorConfig.sendEnabled);

    val label get() = T.tr("toggle.$id")

    companion object {
        fun byId(id: String): Feature? = entries.firstOrNull { it.id == id }
    }
}
