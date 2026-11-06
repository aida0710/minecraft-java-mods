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
 * SkyBlock の lore は 1 つの文を短い行に折り返して書いているため、折り返しの続きの行を 1 段落にまとめて翻訳し、
 * 訳文を元の幅で折り返し直す (どの行をつなげるかは [LoreParagraphs])。
 * 色の付いた語句はタグで囲んで翻訳エンジンに渡し、訳文の対応する語句に同じ色を付ける ([StyleMarkup])。
 * 毎フレーム呼ばれるため、未翻訳の段落は原文のまま表示しつつ裏で翻訳を予約し、
 * キャッシュに入った時点で訳文に切り替わる。
 */
class TooltipHandler(private val translator: Translator, private val showOriginalHeld: () -> Boolean) {

    private val identifier = Regex("^[a-z0-9_.-]+:[a-z0-9_/.-]+$")

    /** 段落の組み立て結果 (折り返し計算は重いので描画スレッド内で再利用する) */
    private val rendered = object : LinkedHashMap<String, List<Component>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Component>>?) = size > 512
    }

    private class Line(val component: Component) {
        val runs = runs(component)
        val text = runs.joinToString("") { it.text }
        val trimmed = text.trim()
        /** 文字のある最初のセグメントのスタイル = その行の基本の色 */
        val baseStyle: Style = runs.firstOrNull { it.text.isNotBlank() }?.style ?: Style.EMPTY
        /** 前後の空白を除いた部分の区間 */
        val trimmedRuns: List<Span<Style>> get() {
            val lead = text.length - text.trimStart().length
            return StyleMarkup.slice(runs, lead until lead + trimmed.length)
        }
    }

    fun onTooltip(lines: MutableList<Component>) {
        val cfg = TranslatorConfig.current
        if (!cfg.tooltipEnabled || showOriginalHeld() || lines.isEmpty()) return

        val parsed = lines.map { Line(it) }
        val out = ArrayList<Component>(lines.size + 4)

        // 1 行目はアイテム名。固有名詞なので既知の SkyBlock アイテムなら英語のまま
        val name = parsed[0]
        val keepName = !cfg.tooltipTranslateItemName || (translator.dictionaries.itemNamesActive && translator.itemNames.isItemName(name.trimmed))
        val lore = parsed.subList(1, parsed.size)
        val font = Minecraft.getInstance().font
        val items = lore.map { LoreParagraphs.Line(it.trimmed, isProse(it.trimmed, cfg), it.baseStyle.color) }
        // 元の lore の折り返し幅の目安 (最も長い文章の行)
        val wrapWidth = items.filter { it.prose }.maxOfOrNull { font.width(it.text) } ?: 0

        if (keepName || !isProse(name.trimmed, cfg)) out.add(name.component) else out.addAll(translateParagraph(listOf(name), wrapWidth, cfg))
        for (range in LoreParagraphs.split(items, wrapWidth) { font.width(it) }) {
            if (items[range.first].prose) out.addAll(translateParagraph(lore.subList(range.first, range.last + 1), wrapWidth, cfg))
            else out.add(lore[range.first].component)
        }

        lines.clear()
        lines.addAll(out)
    }

    private fun isProse(text: String, cfg: TranslatorConfig.Values) =
        TextUtil.looksTranslatable(text, cfg.minLength) && !identifier.matches(text) && !translator.isSkipped(text)

    /** loreWidth は元の lore の折り返し幅の目安。訳文はこの幅まで使って折り返す (1 行の項目が訳で長くなっても折り返さないように) */
    private fun translateParagraph(para: List<Line>, loreWidth: Int, cfg: TranslatorConfig.Values): List<Component> {
        val originals = para.map { it.component }
        val joined = para.joinToString(" ") { it.trimmed }
        // 行をつなげた区間の列 (行の間は空白)
        val spans = para.flatMapIndexed { i, line -> if (i == 0) line.trimmedRuns else listOf(Span(line.baseStyle, " ")) + line.trimmedRuns }
        val base = StyleMarkup.dominant(spans)
        // 辞書の訳語は原文と完全一致で引くので、色の区間があっても平文で訳す
        val markup = if (translator.dictionaries.lookup(joined) == null) StyleMarkup.encode(spans, base) else null
        val source = markup?.text ?: joined
        val translated = translator.translateInBackground(source)
        if (translated == null || translated == source) return originals

        val key = "${cfg.tooltipShowOriginal}\u0000$loreWidth\u0000$source\u0000$translated"
        rendered[key]?.let { return it }

        val font = Minecraft.getInstance().font
        val indent = para[0].text.takeWhile { it == ' ' }
        // タグが崩れていたら、タグを除いた訳文に原文の色付きの語句を探して色を付け直す
        val styled = markup?.let { StyleMarkup.decode(translated, it, base) }
            ?: highlighted(if (markup != null) StyleMarkup.strip(translated) else translated, base, highlights(para, base))

        // 元の行幅に合わせる (極端に狭いと読みにくいので下限あり)。訳文が長ければ、元の行数に収まるよう lore の 1.5 倍まで広げる
        val minWidth = maxOf(para.maxOf { font.width(it.component) }, loreWidth, 120) - font.width(indent)
        val textWidth = font.width(styled.joinToString("") { it.text })
        val maxWidth = TextWrap.fitWidth(textWidth, para.size, minWidth, minWidth * 3 / 2)
        val result = TextWrap.wrapSpans(styled, maxWidth) { font.width(it) }
            .map { component(listOf(Span(base, indent)) + it) }
            .toMutableList()
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

    private fun highlighted(text: String, base: Style, highlights: List<Pair<String, Style>>): List<Span<Style>> {
        val out = mutableListOf<Span<Style>>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) out += Span(base, plain.toString())
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
                out += Span(hit.second, hit.first)
                i += hit.first.length
            } else {
                plain.append(text[i])
                i++
            }
        }
        flush()
        return out
    }

    private fun component(spans: List<Span<Style>>): Component {
        val root: MutableComponent = Component.empty()
        for (s in spans) if (s.text.isNotEmpty()) root.append(Component.literal(s.text).withStyle(s.style))
        return root
    }

    private companion object {
        /** Component を (スタイル, 文字列) の列に平坦化する。文字列中の § 書式コードもスタイルに変換する */
        fun runs(component: Component): List<Span<Style>> {
            val out = mutableListOf<Span<Style>>()
            component.visit<Unit>({ style, contents ->
                var cur = style
                val sb = StringBuilder()
                var k = 0
                while (k < contents.length) {
                    val c = contents[k]
                    val format = if (c == '§' && k + 1 < contents.length) ChatFormatting.getByCode(contents[k + 1]) else null
                    if (format != null) {
                        if (sb.isNotEmpty()) { out += Span(cur, sb.toString()); sb.clear() }
                        cur = if (format == ChatFormatting.RESET) style else cur.applyLegacyFormat(format)
                        k += 2
                        continue
                    }
                    sb.append(c)
                    k++
                }
                if (sb.isNotEmpty()) out += Span(cur, sb.toString())
                Optional.empty()
            }, Style.EMPTY)
            return out
        }
    }
}
