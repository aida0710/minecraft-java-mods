package com.aida0710.chattranslator

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent

/**
 * 受信チャットを翻訳する。
 *  - REPLACE: 原文をまず表示し、訳文が届いたら同じ行を訳文に書き換える (順序は崩れない)。
 *             行末の [原文] / [訳] をクリックすると切り替わる。
 *  - APPEND:  原文の下に訳文を追加する。
 */
class ChatHandler(private val translator: Translator) {
    @Volatile var enabled = true

    /** 切り替え用に原文と訳文を保持する。描画スレッドからのみ触る */
    private class Entry(val original: Component, val translated: Component, var current: Component)

    private val entries = object : LinkedHashMap<Int, Entry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>?) = size > MAX_ENTRIES
    }
    private var nextId = 0

    /** accesstransformer.cfg が適用されていなければ (他 mod との競合など) 追加表示にフォールバックする */
    val canReplace: Boolean = runCatching {
        val cls = net.minecraft.client.gui.components.ChatComponent::class.java
        java.lang.reflect.Modifier.isPublic(cls.getDeclaredField("allMessages").modifiers) &&
            java.lang.reflect.Modifier.isPublic(cls.getDeclaredMethod("refreshTrimmedMessages").modifiers)
    }.getOrDefault(false).also { if (!it) ChatTranslator.LOGGER.warn("Access transformer not applied; chat REPLACE mode falls back to APPEND") }

    /**
     * @return 表示するメッセージを差し替える場合はその Component、差し替えない場合は null
     */
    fun onMessage(message: Component, isSystem: Boolean): Component? {
        val cfg = TranslatorConfig.current
        if (!enabled || !cfg.chatEnabled) return null
        if (isSystem && !cfg.chatTranslateSystemMessages) return null

        val raw = TextUtil.stripFormatting(message.string)
        val lines = raw.split('\n')
            .map { TextUtil.splitChatPrefix(it) }
            .filter { (_, body) -> TextUtil.looksTranslatable(body, cfg.minLength) && !translator.isSkipped(body) }
        if (lines.isEmpty()) return null

        // 複数行メッセージは一部の行だけ書き換えると装飾が崩れるため追加表示にする
        if (cfg.chatDisplayMode == ChatDisplayMode.REPLACE && canReplace && !raw.contains('\n')) {
            val (prefix, body) = lines.single()
            // キャッシュ済みならその場で差し替え (ちらつき無し)
            translator.cached(body)?.let { return register(message, prefix, it) }
            translator.translateChat(
                body,
                onSuccess = { translated -> onRenderThread { replaceInChat(message, register(message, prefix, translated)) } },
                onFailure = {},
            )
            return null
        }

        for ((_, body) in lines) {
            translator.translateChat(body, onSuccess = { onRenderThread { addLine(appended(it)) } }, onFailure = {})
        }
        return null
    }

    /** [原文] / [訳] ボタンから呼ばれる */
    fun swap(id: Int): Boolean {
        val entry = entries[id] ?: return false
        val next = if (entry.current === entry.translated) entry.original.copy().append(button(id, "[訳]", "クリックで訳文を表示"))
        else entry.translated
        if (!replaceInChat(entry.current, next)) return false
        entry.current = next
        return true
    }

    private fun register(original: Component, prefix: String, translated: String): Component {
        val id = nextId++
        val hover = HoverEvent.ShowText(Component.literal("原文: ").withStyle(ChatFormatting.GRAY).append(original.copy()))
        val body: MutableComponent =
            if (prefix.isEmpty()) Component.literal(translated)
            else Component.literal("$prefix: ").withStyle(ChatFormatting.GRAY).append(Component.literal(translated).withStyle(ChatFormatting.WHITE))
        val component = body.withStyle { it.withHoverEvent(hover) }.append(button(id, "[原文]", "クリックで原文を表示"))
        entries[id] = Entry(original, component, component)
        return component
    }

    private fun button(id: Int, label: String, tooltip: String): Component =
        Component.literal(" $label").withStyle {
            it.withColor(ChatFormatting.DARK_GRAY)
                .withClickEvent(ClickEvent.RunCommand("chattranslator swap $id"))
                .withHoverEvent(HoverEvent.ShowText(Component.literal(tooltip)))
        }

    /** チャット履歴の中から target (同一インスタンス) を探して置き換える */
    private fun replaceInChat(target: Component, replacement: Component): Boolean {
        val chat = Minecraft.getInstance().gui.hud.chat
        val it = chat.allMessages.listIterator()
        while (it.hasNext()) {
            val msg = it.next()
            if (msg.content() === target) {
                it.set(GuiMessage(msg.addedTime(), replacement, msg.signature(), msg.source(), msg.tag()))
                chat.refreshTrimmedMessages()
                return true
            }
        }
        return false
    }

    private fun appended(translated: String): Component =
        Component.literal(" ⤷ ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(translated).withStyle(ChatFormatting.GRAY))

    /** addClientSystemMessage は Forge のチャットイベントを発火しないので自分の出力を再翻訳しない */
    private fun addLine(component: Component) = Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(component)

    private fun onRenderThread(block: () -> Unit) {
        val mc = Minecraft.getInstance()
        mc.execute(block)
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
