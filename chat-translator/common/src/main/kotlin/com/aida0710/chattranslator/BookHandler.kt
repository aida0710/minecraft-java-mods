package com.aida0710.chattranslator

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import java.util.Optional

/**
 * 本 (記入済みの本・書見台) の中身を翻訳する。本の画面を開いている間、毎 tick 呼ばれる。
 *  - ページの全行の訳が揃ったものから訳文に差し替える (今のページ → 次のページ → 残りの順に依頼)
 *  - 原文表示のキー (既定: J) を押している間は原文に戻す
 *  - クリックできる文字を含むページ (サーバーのメニューなど) は動作を壊さないよう原文のまま
 */
class BookHandler(private val translator: Translator, private val showOriginal: () -> Boolean) {
    /** ゲームが画面に渡した本の中身 */
    private var original: BookViewScreen.BookAccess? = null
    /** 最後に画面に入れた中身 (これと違うものが入っていたら、書見台の本が替わったなどの理由でゲームが入れ直した) */
    private var shown: BookViewScreen.BookAccess? = null

    /** アクセスの書き換え (accesstransformer.cfg / classtweaker) が効いていなければ何もしない */
    val canTranslate: Boolean = runCatching {
        val cls = BookViewScreen::class.java
        listOf("bookAccess", "currentPage").all { java.lang.reflect.Modifier.isPublic(cls.getDeclaredField(it).modifiers) }
    }.getOrDefault(false).also { if (!it) ChatTranslator.LOGGER.warn("BookViewScreen access not widened; books are not translated") }

    fun onTick() {
        val screen = Minecraft.getInstance().gui.screen() as? BookViewScreen
        if (screen == null || !canTranslate) {
            original = null
            shown = null
            return
        }
        val current = screen.bookAccess
        if (current !== shown) {
            original = current
            shown = current
        }
        val book = original ?: return
        val cfg = TranslatorConfig.current
        val wanted = if (!cfg.bookEnabled || showOriginal()) book else translate(book, screen.currentPage, cfg)
        // 中身が変わったときだけ入れ直す (入れ直すと画面が行の折り返しを計算し直す)
        if (wanted != shown) {
            screen.setBookAccess(wanted)
            shown = wanted
        }
    }

    private fun translate(book: BookViewScreen.BookAccess, currentPage: Int, cfg: TranslatorConfig.Values): BookViewScreen.BookAccess {
        val pages = book.pages
        val order = (listOf(currentPage, currentPage + 1) + pages.indices).filter { it in pages.indices }.distinct()
        val translated = arrayOfNulls<Component>(pages.size)
        for (i in order) translated[i] = translatePage(pages[i], cfg)
        return BookViewScreen.BookAccess(pages.mapIndexed { i, page -> translated[i] ?: page })
    }

    /** 全行の訳が揃っていれば訳文のページ、まだ無ければ (翻訳を依頼して) null */
    private fun translatePage(page: Component, cfg: TranslatorConfig.Values): Component? {
        if (hasClickEvent(page)) return null
        val lines = TextUtil.stripFormatting(page.string).split('\n')
        var waiting = false
        val result = lines.map { line ->
            if (!TextUtil.looksTranslatable(line, cfg.minLength) || translator.isSkipped(line)) line
            else translator.translateInBackground(line.trim()) ?: line.also { waiting = true }
        }
        if (waiting || result == lines) return null
        return Component.literal(result.joinToString("\n")).withStyle(baseStyle(page))
    }

    private fun hasClickEvent(page: Component): Boolean =
        page.visit<Unit>({ style, _ -> if (style.clickEvent != null) Optional.of(Unit) else Optional.empty() }, Style.EMPTY).isPresent

    /** 文字のある最初の部分の装飾 (色など)。ページ全体の訳文に使う */
    private fun baseStyle(page: Component): Style =
        page.visit<Style>({ style, text -> if (text.isNotBlank()) Optional.of(style) else Optional.empty() }, Style.EMPTY).orElse(Style.EMPTY)
}
