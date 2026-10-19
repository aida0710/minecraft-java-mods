package com.aida0710.chattranslator

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.resources.Identifier

/**
 * キー割り当て (設定 > 操作 > Chat Translator で変更可)。
 * バニラはインベントリなどの画面を開いている間 KeyMapping を処理しないため、
 * 画面中のキー入力は各ローダーの画面のキー入力イベントから [onScreenKey] に渡し、どこでも効くようにしている。
 */
class Keybinds(private val mod: ChatTranslator) {
    /**
     * キー設定画面の「Chat Translator」の見出し。登録は各ローダーの入口で行う
     * (NeoForge はバニラの KeyMapping.Category.register ではなく専用の登録イベントを使う)
     */
    val category = KeyMapping.Category(Identifier.fromNamespaceAndPath(ChatTranslator.MODID, "main"))

    /** 押している間ツールチップを原文表示 (既定: J) */
    val holdOriginal = KeyMapping("key.chattranslator.show_original", InputConstants.KEY_J, category)
    /** 押すたびにツールチップの原文表示を切り替え */
    val toggleOriginal = KeyMapping("key.chattranslator.toggle_original", InputConstants.UNKNOWN.value, category)
    val toggleChat = KeyMapping("key.chattranslator.toggle_chat", InputConstants.UNKNOWN.value, category)
    val toggleTooltip = KeyMapping("key.chattranslator.toggle_tooltip", InputConstants.UNKNOWN.value, category)
    val toggleSend = KeyMapping("key.chattranslator.toggle_send", InputConstants.UNKNOWN.value, category)
    val openSettings = KeyMapping("key.chattranslator.open_settings", InputConstants.UNKNOWN.value, category)

    /** 機能の ON/OFF を切り替えるキー */
    private val featureKeys = mapOf(toggleChat to Feature.CHAT, toggleTooltip to Feature.TOOLTIP, toggleSend to Feature.SEND)

    /** 各ローダーの登録処理に渡す */
    val mappings get() = listOf(holdOriginal, toggleOriginal, toggleChat, toggleTooltip, toggleSend, openSettings)

    /** toggleOriginal で切り替える原文表示の状態 (保存しない) */
    @Volatile var originalToggled = false
        private set

    /** ツールチップを原文で表示するか */
    fun showOriginal(): Boolean = originalToggled || isHeld(holdOriginal)

    /** インベントリ画面中は KeyMapping の状態が更新されないため、キーボードの生の状態を見る */
    private fun isHeld(key: KeyMapping): Boolean {
        val k = boundKey(key)
        return k.type == InputConstants.Type.KEYBOARD && k.value > 0 && InputConstants.isKeyDown(k.value)
    }

    /** ワールド内 (画面を開いていないとき) */
    fun onTick() {
        while (toggleOriginal.consumeClick()) doToggleOriginal()
        for ((key, feature) in featureKeys) while (key.consumeClick()) mod.toggle(feature)
        while (openSettings.consumeClick()) SettingsScreen.open()
    }

    /**
     * 画面を開いているとき (インベントリ・チェストなど) のキー入力。
     * @return true ならこのキーはここで使った (各ローダーの入口は画面の既定の処理を止める)
     */
    fun onScreenKey(screen: net.minecraft.client.gui.screens.Screen, event: KeyEvent): Boolean {
        if (!mod.ready()) return false
        // チャット欄の Enter は、送信するチャットの翻訳に回す
        if (screen is ChatScreen && event.isConfirmation) return mod.send.onEnter(screen)
        // 文字入力中 (チャット欄・検索欄など) はショートカットとして扱わない
        if (screen is ChatScreen || screen.focused is EditBox || screen is SettingsScreen) return false
        // ショートカットはキーを消費しない (インベントリなどの画面の操作もそのまま効く)
        when {
            isPressed(toggleOriginal, event) -> doToggleOriginal()
            isPressed(openSettings, event) -> Minecraft.getInstance().execute { SettingsScreen.open(screen) }
            else -> featureKeys.entries.firstOrNull { isPressed(it.key, event) }?.let { mod.toggle(it.value) }
        }
        return false
    }

    private fun isPressed(key: KeyMapping, event: KeyEvent) = !key.isUnbound && key.matches(event)

    private fun doToggleOriginal() {
        originalToggled = !originalToggled
        mod.showToggle(T.tr("toggle.original"), originalToggled)
    }

    companion object {
        /** 割り当て中のキー。それを返す getter はバニラに無い (Forge だけが足している) ため、保存用の名前から引く */
        internal fun boundKey(mapping: KeyMapping): InputConstants.Key = InputConstants.getKey(mapping.saveString())
    }
}
