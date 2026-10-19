package com.aida0710.chattranslator

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * ボタンの下 (入りきらなければ上) に開く選択リスト。画面 ([SettingsScreen]) が最前面に描画し、
 * 開いている間のマウス・キー入力はすべてこちらに渡す。
 *  - クリック / Enter で選択、Esc やリストの外をクリックで閉じる
 *  - 項目が多いときは文字を打つと絞り込める (Backspace で 1 文字消す)
 */
class DropdownPopup<T : Any>(
    val anchor: AbstractWidget,
    private val values: List<T>,
    private val selected: T?,
    private val name: (T) -> Component,
    private val searchText: (T) -> String = { name(it).string },
    private val onSelect: (T) -> Unit,
) {
    private val font = Minecraft.getInstance().font
    private val searchable = values.size > SEARCH_THRESHOLD
    private var filter = ""
    private var shown = values
    private var scroll = 0
    private var highlighted = max(0, values.indexOf(selected))
    private var x = 0
    private var y = 0
    private var w = 0
    private var rows = 0
    private val headerH get() = if (searchable) ROW_H + 2 else 0
    private val h get() = headerH + rows * ROW_H + 2

    /** 位置を決める (開いたときと画面サイズが変わったとき) */
    fun layout(screenWidth: Int, screenHeight: Int) {
        w = min(max(anchor.width, values.maxOfOrNull { font.width(name(it)) + 16 } ?: 0), screenWidth - 8)
        x = anchor.x.coerceIn(4, max(4, screenWidth - w - 4))
        val below = screenHeight - anchor.bottom - 4
        val above = anchor.y - 4
        val space = max(below, above)
        rows = min(min(values.size, MAX_ROWS), max(1, (space - headerH - 2) / ROW_H))
        y = if (below >= headerH + rows * ROW_H + 2 || below >= above) anchor.bottom else anchor.y - h
        ensureVisible()
    }

    override fun toString() = "popup x=$x y=$y w=$w h=$h rows=$rows shown=${shown.size} scroll=$scroll hi=$highlighted filter=$filter"

    fun isMouseOver(mx: Double, my: Double) = mx >= x && mx < x + w && my >= y && my < y + h

    private fun rowAt(mx: Double, my: Double): Int? {
        if (!isMouseOver(mx, my)) return null
        val r = ((my - y - 1 - headerH) / ROW_H).toInt()
        if (my < y + 1 + headerH || r !in 0 until rows) return null
        return (scroll + r).takeIf { it < shown.size }
    }

    fun extract(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        g.fill(x, y, x + w, y + h, 0xFF101010.toInt())
        g.outline(x, y, w, h, 0xFFA0A0A0.toInt())
        if (searchable) {
            val text = if (filter.isEmpty()) T.tr("dropdown.search").withStyle(ChatFormatting.DARK_GRAY)
            else Component.literal("> ${filter}_").withStyle(ChatFormatting.WHITE)
            g.text(font, text, x + 4, y + 3, -1)
            g.fill(x + 1, y + headerH, x + w - 1, y + headerH + 1, 0xFF505050.toInt())
        }
        val hoveredRow = rowAt(mouseX.toDouble(), mouseY.toDouble())
        if (hoveredRow != null) highlighted = hoveredRow
        val top = y + 1 + headerH
        if (shown.isEmpty()) g.text(font, T.tr("dropdown.none").withStyle(ChatFormatting.GRAY), x + 4, top + 2, -1)
        for (i in 0 until rows) {
            val idx = scroll + i
            if (idx >= shown.size) break
            val value = shown[idx]
            val rowY = top + i * ROW_H
            if (idx == highlighted) g.fill(x + 1, rowY, x + w - 1, rowY + ROW_H, 0xFF3C5A8C.toInt())
            val color = if (value == selected) 0xFFFFFF55.toInt() else -1
            g.text(font, name(value), x + 4, rowY + 2, color)
        }
        if (shown.size > rows) {
            // スクロールバー
            val trackH = rows * ROW_H
            val barH = max(8, trackH * rows / shown.size)
            val barY = top + (trackH - barH) * scroll / (shown.size - rows)
            g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFFA0A0A0.toInt())
        }
    }

    /** クリック。戻り値 false なら閉じる */
    fun mouseClicked(mx: Double, my: Double): Boolean {
        val row = rowAt(mx, my)
        if (row != null) {
            onSelect(shown[row])
            return false
        }
        return isMouseOver(mx, my)
    }

    fun mouseScrolled(dy: Double) {
        scroll = (scroll - dy.sign.toInt() * 3).coerceIn(0, max(0, shown.size - rows))
    }

    /** キー入力。戻り値 false なら閉じる */
    fun keyPressed(key: Int): Boolean {
        when (key) {
            InputConstants.KEY_ESCAPE -> return false
            InputConstants.KEY_UP -> move(-1)
            InputConstants.KEY_DOWN -> move(1)
            InputConstants.KEY_PAGEUP -> move(-rows)
            InputConstants.KEY_PAGEDOWN -> move(rows)
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                shown.getOrNull(highlighted)?.let { onSelect(it); return false }
            }
            InputConstants.KEY_BACKSPACE -> if (searchable && filter.isNotEmpty()) setFilter(filter.dropLast(1))
        }
        return true
    }

    fun charTyped(text: String) {
        if (searchable) setFilter(filter + text)
    }

    private fun setFilter(value: String) {
        filter = value
        val q = value.trim().lowercase()
        shown = if (q.isEmpty()) values else values.filter { searchText(it).lowercase().contains(q) }
        highlighted = if (q.isEmpty()) max(0, values.indexOf(selected)) else 0
        scroll = 0
        ensureVisible()
    }

    private fun move(delta: Int) {
        if (shown.isEmpty()) return
        highlighted = (highlighted + delta).coerceIn(0, shown.size - 1)
        ensureVisible()
    }

    private fun ensureVisible() {
        if (highlighted < scroll) scroll = highlighted
        if (highlighted >= scroll + rows) scroll = highlighted - rows + 1
        scroll = scroll.coerceIn(0, max(0, shown.size - rows))
    }

    companion object {
        const val ROW_H = 12
        const val MAX_ROWS = 12
        const val SEARCH_THRESHOLD = 8
    }
}
