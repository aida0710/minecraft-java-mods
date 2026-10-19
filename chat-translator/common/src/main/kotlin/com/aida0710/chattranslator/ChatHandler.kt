package com.aida0710.chattranslator

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.util.Optional

/**
 * 受信チャットを翻訳する。
 *  - REPLACE: 原文をまず表示し、訳文が届いたら同じ行を訳文に書き換える (順序は崩れない)。
 *             行末の [原文] / [訳] をクリックすると切り替わる。
 *  - APPEND:  原文の下に訳文を追加する。
 */
class ChatHandler(private val translator: Translator) {

    /** 切り替え用に原文と訳文を保持する。描画スレッドからのみ触る */
    private class Entry(val original: Component, val translated: Component, var current: Component)

    private val entries = object : LinkedHashMap<Int, Entry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>?) = size > MAX_ENTRIES
    }
    private var nextId = 0

    /** アクセスの書き換え (Forge / NeoForge の accesstransformer.cfg、Fabric の classtweaker) が効いていなければ (他 mod との競合など) 追加表示にフォールバックする */
    val canReplace: Boolean = runCatching {
        val cls = net.minecraft.client.gui.components.ChatComponent::class.java
        java.lang.reflect.Modifier.isPublic(cls.getDeclaredField("allMessages").modifiers) &&
            java.lang.reflect.Modifier.isPublic(cls.getDeclaredMethod("refreshTrimmedMessages").modifiers)
    }.getOrDefault(false).also { if (!it) ChatTranslator.LOGGER.warn("ChatComponent access not widened; chat REPLACE mode falls back to APPEND") }

    /**
     * @return 表示するメッセージを差し替える場合はその Component、差し替えない場合は null
     */
    fun onMessage(message: Component, isSystem: Boolean): Component? {
        val cfg = TranslatorConfig.current
        if (!cfg.chatEnabled) return null
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
                onFailure = { _ -> },
            )
            return null
        }

        for ((_, body) in lines) {
            translator.translateChat(body, onSuccess = { onRenderThread { addLine(appended(it)) } }, onFailure = { _ -> })
        }
        return null
    }

    /**
     * 表示前に差し替えられないメッセージ (Fabric のプレイヤー発言) 用。
     * [onMessage] が返した差し替え後の内容を、チャット欄に載った後の同じ行へ反映する
     */
    fun replaceAfterShown(original: Component, replacement: Component) {
        // execute は描画スレッド上ではその場で動き、まだチャット欄に載っていない行を探してしまうため schedule
        Minecraft.getInstance().schedule { replaceInChat(original, replacement) }
    }

    /** [原文] / [訳] ボタンから呼ばれる */
    fun swap(id: Int): Boolean {
        val entry = entries[id] ?: return false
        val next = if (entry.current === entry.translated) entry.original.copy().append(button(id, T.tr("chat.show_translation"), T.tr("chat.show_translation.tooltip")))
        else entry.translated
        if (!replaceInChat(entry.current, next)) return false
        entry.current = next
        return true
    }

    private fun register(original: Component, prefix: String, translated: String): Component {
        val id = nextId++
        val hover = HoverEvent.ShowText(T.tr("chat.original_prefix").withStyle(ChatFormatting.GRAY).append(original.copy()))
        // "[MVP+] Name: " の部分は原文の装飾のまま残し、本文は原文本文の先頭の装飾を引き継ぐ
        val (head, bodyStyle, bodyCodes) = splitStyled(original, if (prefix.isEmpty()) 0 else prefix.length + 2)
        val component = Component.empty()
            .append(head)
            .append(Component.literal(bodyCodes + translated).withStyle(bodyStyle.withHoverEvent(hover)))
            .append(button(id, T.tr("chat.show_original"), T.tr("chat.show_original.tooltip")))
        entries[id] = Entry(original, component, component)
        return component
    }

    private data class Split(val head: MutableComponent, val bodyStyle: Style, val bodyCodes: String)

    /**
     * 原文の先頭 visibleChars 文字 (§ 書式コードを除いた文字数) を装飾付きのまま切り出し、
     * それに続く本文の最初の文字の装飾 (Style と有効な § コード) を返す。
     */
    private fun splitStyled(original: Component, visibleChars: Int): Split {
        val head = Component.empty()
        var count = 0
        var bodyStyle: Style? = null
        var bodyCodes = ""
        original.visit<Unit>({ style, contents ->
            val sb = StringBuilder()
            var codes = ""
            var i = 0
            while (i < contents.length) {
                val c = contents[i]
                if (c == '§' && i + 1 < contents.length) {
                    val code = contents.substring(i, i + 2)
                    // 色コードはそれまでの書式をリセットする
                    codes = if (code[1].lowercaseChar() in "0123456789abcdefr") code else codes + code
                    if (count < visibleChars) sb.append(code)
                    i += 2
                    continue
                }
                if (count < visibleChars) {
                    sb.append(c)
                    count++
                } else if (!c.isWhitespace()) {
                    bodyStyle = style
                    bodyCodes = codes
                    break
                }
                i++
            }
            if (sb.isNotEmpty()) head.append(Component.literal(sb.toString()).withStyle(style))
            if (bodyStyle != null) Optional.of(Unit) else Optional.empty()
        }, Style.EMPTY)
        return Split(head, bodyStyle ?: Style.EMPTY, bodyCodes)
    }

    private fun button(id: Int, label: Component, tooltip: Component): Component =
        Component.literal(" ").append(label).withStyle {
            it.withColor(ChatFormatting.DARK_GRAY)
                .withClickEvent(ClickEvent.RunCommand("chattranslator swap $id"))
                .withHoverEvent(HoverEvent.ShowText(tooltip))
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

    /** addClientSystemMessage はどのローダーでも受信チャットのイベントを発火しないので、自分の出力を再翻訳しない */
    private fun addLine(component: Component) = Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(component)

    private fun onRenderThread(block: () -> Unit) {
        val mc = Minecraft.getInstance()
        mc.execute(block)
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
