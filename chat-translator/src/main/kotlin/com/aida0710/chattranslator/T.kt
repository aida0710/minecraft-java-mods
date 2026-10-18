package com.aida0710.chattranslator

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * 表示文字列 (assets/chattranslator/lang の各言語ファイル)。
 * Minecraft の言語設定に合わせて日本語・英語・中国語で表示され、未翻訳の言語は英語になる。
 */
object T {
    /** 翻訳キー chattranslator.<key> の Component */
    fun tr(key: String, vararg args: Any): MutableComponent = Component.translatable("chattranslator.$key", *args)

    /** 現在の言語で文字列にする (ログなど Component を使えない場所用) */
    fun s(key: String, vararg args: Any): String = tr(key, *args).string

    fun onOff(on: Boolean): MutableComponent = tr(if (on) "on" else "off")
}
