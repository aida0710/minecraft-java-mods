package com.aida0710.chattranslator

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.util.Optional

/**
 * アイテムのツールチップ (名前・説明文) を翻訳する。
 *
 * SkyBlock の lore は 1 つの文を短い行に折り返して書いているため、
 * 同じ色で続く行を 1 段落にまとめて翻訳し、訳文を元の幅で折り返し直す。
 * 毎フレーム呼ばれるため、未翻訳の段落は原文のまま表示しつつ裏で翻訳を予約し、
 * キャッシュに入った時点で訳文に切り替わる。
 */
class TooltipHandler(private val translator: Translator, private val showOriginalHeld: () -> Boolean) {
    @Volatile var enabled = true

    private val identifier = Regex("^[a-z0-9_.-]+:[a-z0-9_/.-]+$")

    /** 段落の組み立て結果 (折り返し計算は重いので描画スレッド内で再利用する) */
    private val rendered = object : LinkedHashMap<String, List<Component>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Component>>?) = size > 512
    }

    private data class Run(val style: Style, val text: String)

    private class Line(val component: Component) {
        val runs = runs(component)
        val text = runs.joinToString("") { it.text }
        val trimmed = text.trim()
        /** 文字のある最初のセグメントのスタイル = その行の基本の色 */
        val baseStyle: Style = runs.firstOrNull { it.text.isNotBlank() }?.style ?: Style.EMPTY
    }

    fun onTooltip(lines: MutableList<Component>) {
        val cfg = TranslatorConfig.current
        if (!enabled || !cfg.tooltipEnabled || showOriginalHeld() || lines.isEmpty()) return

        val parsed = lines.map { Line(it) }
        val out = ArrayList<Component>(lines.size + 4)

        // 1 行目はアイテム名。固有名詞なので既知の SkyBlock アイテムなら英語のまま
        val name = parsed[0]
        val keepName = !cfg.tooltipTranslateItemName || (cfg.protectItemNames && translator.itemNames.isItemName(name.trimmed))
        if (keepName || !isProse(name.trimmed, cfg)) out.add(name.component) else out.addAll(translateParagraph(listOf(name), cfg))

        var i = 1
        while (i < parsed.size) {
            val line = parsed[i]
            if (!isProse(line.trimmed, cfg)) {
                out.add(line.component)
                i++
                continue
            }
            // 同じ基本色の文章行が続く限り 1 段落とみなす (見出しやステータス行は色や形式が違うので区切られる)
            var j = i + 1
            if (!isListItem(line.trimmed)) {
                while (j < parsed.size && isProse(parsed[j].trimmed, cfg) && !isListItem(parsed[j].trimmed) && sameColor(parsed[j].baseStyle, line.baseStyle)) j++
            }
            out.addAll(translateParagraph(parsed.subList(i, j), cfg))
            i = j
        }

        lines.clear()
        lines.addAll(out)
    }

    private fun isProse(text: String, cfg: TranslatorConfig.Values) =
        TextUtil.looksTranslatable(text, cfg.minLength) && !identifier.matches(text) && !translator.isSkipped(text)

    private fun sameColor(a: Style, b: Style) = a.color == b.color

    /** 数値や記号で始まる行 ("6 Attack Damage", "+50 Strength", "• Line") は箇条書きなのでつなげない */
    private fun isListItem(text: String) = text.firstOrNull()?.let { it.isDigit() || it in "+-•▸◆►✦" } ?: false

    private fun translateParagraph(para: List<Line>, cfg: TranslatorConfig.Values): List<Component> {
        val originals = para.map { it.component }
        val joined = para.joinToString(" ") { it.trimmed }
        val translated = translator.translateTooltipLine(joined)
        if (translated == null || translated == joined) return originals

        val key = "${cfg.tooltipShowOriginal}\u0000$joined\u0000$translated"
        rendered[key]?.let { return it }

        val font = Minecraft.getInstance().font
        val indent = para[0].text.takeWhile { it == ' ' }
        // 元の行幅に合わせる (極端に狭いと読みにくいので下限あり)
        val maxWidth = maxOf(para.maxOf { font.width(it.component) }, 120) - font.width(indent)
        val base = para[0].baseStyle
        val highlights = highlights(para, base)

        val result = TextWrap.wrap(translated, maxWidth) { font.width(it) }
            .map { styledLine(indent + it, base, highlights) }
            .toMutableList<Component>()
        if (cfg.tooltipShowOriginal) {
            originals.forEach { result += Component.literal(TextUtil.stripFormatting(it.string)).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC) }
        }
        rendered[key] = result
        return result
    }

    /** 原文で色付けされていた短い語句 (数値・ステータス名など)。訳文にそのまま残っていれば同じ色を付け直す */
    private fun highlights(para: List<Line>, base: Style): List<Pair<String, Style>> =
        para.flatMap { it.runs }
            .filter { it.style != base && it.text.isNotBlank() }
            .map { it.text.trim() to it.style }
            .filter { (t, _) -> t.length in 1..24 }
            .distinctBy { it.first }
            .sortedByDescending { it.first.length }

    private fun styledLine(text: String, base: Style, highlights: List<Pair<String, Style>>): Component {
        val root: MutableComponent = Component.empty()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) root.append(Component.literal(plain.toString()).withStyle(base))
            plain.clear()
        }
        var i = 0
        while (i < text.length) {
            val hit = highlights.firstOrNull { (token, _) ->
                text.startsWith(token, i) &&
                    // "1" が "10" の一部に一致しないよう英数字の境界を確認する
                    !(token.first().isLetterOrDigit() && i > 0 && text[i - 1].isLetterOrDigit()) &&
                    !(token.last().isLetterOrDigit() && i + token.length < text.length && text[i + token.length].isLetterOrDigit())
            }
            if (hit != null) {
                flush()
                root.append(Component.literal(hit.first).withStyle(hit.second))
                i += hit.first.length
            } else {
                plain.append(text[i])
                i++
            }
        }
        flush()
        return root
    }

    private companion object {
        /** Component を (スタイル, 文字列) の列に平坦化する。文字列中の § 書式コードもスタイルに変換する */
        fun runs(component: Component): List<Run> {
            val out = mutableListOf<Run>()
            component.visit<Unit>({ style, contents ->
                var cur = style
                val sb = StringBuilder()
                var k = 0
                while (k < contents.length) {
                    val c = contents[k]
                    val format = if (c == '§' && k + 1 < contents.length) ChatFormatting.getByCode(contents[k + 1]) else null
                    if (format != null) {
                        if (sb.isNotEmpty()) { out += Run(cur, sb.toString()); sb.clear() }
                        cur = if (format == ChatFormatting.RESET) style else cur.applyLegacyFormat(format)
                        k += 2
                        continue
                    }
                    sb.append(c)
                    k++
                }
                if (sb.isNotEmpty()) out += Run(cur, sb.toString())
                Optional.empty()
            }, Style.EMPTY)
            return out
        }

    }
}
