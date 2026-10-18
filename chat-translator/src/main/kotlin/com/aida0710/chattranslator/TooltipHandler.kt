package com.aida0710.chattranslator

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import java.util.Optional

/**
 * アイテムのツールチップ (名前・説明文) を行ごとに翻訳する。
 * 毎フレーム呼ばれるため、未翻訳の行は原文のまま表示しつつ裏で翻訳を予約し、
 * キャッシュに入った時点で訳文に切り替わる。
 */
class TooltipHandler(private val translator: Translator) {
    @Volatile var enabled = true

    private val identifier = Regex("^[a-z0-9_.-]+:[a-z0-9_/.-]+$")

    fun onTooltip(lines: MutableList<Component>) {
        val cfg = TranslatorConfig.current
        if (!enabled || !cfg.tooltipEnabled) return

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (i == 0 && !cfg.tooltipTranslateItemName) { i++; continue }
            val text = TextUtil.stripFormatting(line.string).trim()
            if (!TextUtil.looksTranslatable(text, cfg.minLength) || identifier.matches(text) || translator.isSkipped(text)) {
                i++; continue
            }
            val translated = translator.translateTooltipLine(text)
            if (translated == null || translated == text) { i++; continue }

            lines[i] = styled(line, translated)
            if (cfg.tooltipShowOriginal) {
                lines.add(i + 1, Component.literal(text).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC))
                i++
            }
            i++
        }
    }

    private val leadingCodes = Regex("^(?:§[0-9a-fk-orA-FK-OR])+")

    private fun styled(original: Component, translated: String): Component {
        // サーバーが § コード入りのリテラルを送ってくる場合は先頭のコードを引き継ぐ
        val codes = leadingCodes.find(original.string)?.value
        return if (codes != null) Component.literal(codes + translated)
        else Component.literal(translated).withStyle(firstStyle(original))
    }

    /** 行内で最初に文字を持つセグメントのスタイル (色・太字など) を訳文全体に引き継ぐ */
    private fun firstStyle(component: Component): Style =
        component.visit({ style, contents ->
            if (contents.isNotBlank()) Optional.of(style) else Optional.empty()
        }, Style.EMPTY).orElse(component.style)
}
