package com.aida0710.chattranslator

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** 受信チャットを翻訳し、訳文をチャット欄に追加する */
class ChatHandler(private val translator: Translator) {
    @Volatile var enabled = true

    /** @return true なら元メッセージをキャンセル (訳文のみ表示モード) */
    fun onMessage(message: Component, isSystem: Boolean): Boolean {
        val cfg = TranslatorConfig.current
        if (!enabled || !cfg.chatEnabled) return false
        if (isSystem && !cfg.chatTranslateSystemMessages) return false

        val raw = TextUtil.stripFormatting(message.string)
        val lines = raw.split('\n')
            .map { TextUtil.splitChatPrefix(it) }
            .filter { (_, body) -> TextUtil.looksTranslatable(body, cfg.minLength) && !translator.isSkipped(body) }
        if (lines.isEmpty()) return false

        // 訳文のみモードは単一行メッセージに限る (複数行は一部だけ翻訳されると崩れるため追加表示にする)
        val replace = !cfg.chatShowOriginal && !raw.contains('\n')
        for ((prefix, body) in lines) {
            translator.translateChat(
                body,
                onSuccess = { translated -> post(if (replace) replaced(prefix, translated) else appended(translated)) },
                onFailure = { if (replace) post(message) },
            )
        }
        return replace
    }

    private fun appended(translated: String): Component =
        Component.literal(" ⤷ ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(translated).withStyle(ChatFormatting.GRAY))

    private fun replaced(prefix: String, translated: String): Component =
        if (prefix.isEmpty()) Component.literal(translated)
        else Component.literal("$prefix: ").withStyle(ChatFormatting.GRAY).append(Component.literal(translated).withStyle(ChatFormatting.WHITE))

    /** addClientSystemMessage は Forge のチャットイベントを発火しないので自分の出力を再翻訳しない */
    private fun post(component: Component) {
        val mc = Minecraft.getInstance()
        mc.execute { mc.gui.hud.chat.addClientSystemMessage(component) }
    }
}
