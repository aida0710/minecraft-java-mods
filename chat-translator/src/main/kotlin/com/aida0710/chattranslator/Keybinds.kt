package com.aida0710.chattranslator

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.resources.Identifier
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.ScreenEvent
import java.util.function.Consumer

/**
 * キー割り当て (設定 > 操作 > Chat Translator で変更可)。
 * バニラはインベントリなどの画面を開いている間 KeyMapping を処理しないため、
 * 画面中のキー入力は ScreenEvent で拾い、どこでも効くようにしている。
 */
class Keybinds(private val mod: ChatTranslator) {
    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ChatTranslator.MODID, "main"))

    /** 押している間ツールチップを原文表示 (既定: J) */
    val holdOriginal = KeyMapping("key.chattranslator.show_original", InputConstants.KEY_J, category)
    /** 押すたびにツールチップの原文表示を切り替え */
    val toggleOriginal = KeyMapping("key.chattranslator.toggle_original", InputConstants.UNKNOWN.value, category)
    val toggleChat = KeyMapping("key.chattranslator.toggle_chat", InputConstants.UNKNOWN.value, category)
    val toggleTooltip = KeyMapping("key.chattranslator.toggle_tooltip", InputConstants.UNKNOWN.value, category)
    val openSettings = KeyMapping("key.chattranslator.open_settings", InputConstants.UNKNOWN.value, category)

    private val all get() = listOf(holdOriginal, toggleOriginal, toggleChat, toggleTooltip, openSettings)

    /** toggleOriginal で切り替える原文表示の状態 (保存しない) */
    @Volatile var originalToggled = false
        private set

    fun register(event: RegisterKeyMappingsEvent) = all.forEach { event.register(it) }

    /** ツールチップを原文で表示するか */
    fun showOriginal(): Boolean = originalToggled || isHeld(holdOriginal)

    /** インベントリ画面中は KeyMapping の状態が更新されないため、キーボードの生の状態を見る */
    private fun isHeld(key: KeyMapping): Boolean {
        val k = key.key
        return k.type == InputConstants.Type.KEYBOARD && k.value > 0 && InputConstants.isKeyDown(k.value)
    }

    /** ワールド内 (画面を開いていないとき) */
    fun onTick() {
        while (toggleOriginal.consumeClick()) doToggleOriginal()
        while (toggleChat.consumeClick()) doToggleChat()
        while (toggleTooltip.consumeClick()) doToggleTooltip()
        while (openSettings.consumeClick()) SettingsScreen.open()
    }

    /** 画面を開いているとき (インベントリ・チェストなど) */
    fun registerScreenListener() {
        ScreenEvent.KeyPressed.Pre.BUS.addListener(Consumer { ev -> onScreenKey(ev.screen, ev.info) })
    }

    private fun onScreenKey(screen: net.minecraft.client.gui.screens.Screen, event: KeyEvent) {
        if (!mod.ready()) return
        // 文字入力中 (チャット欄・検索欄など) はショートカットとして扱わない
        if (screen is ChatScreen || screen.focused is EditBox || screen is SettingsScreen) return
        when {
            !toggleOriginal.isUnbound && toggleOriginal.matches(event) -> doToggleOriginal()
            !toggleChat.isUnbound && toggleChat.matches(event) -> doToggleChat()
            !toggleTooltip.isUnbound && toggleTooltip.matches(event) -> doToggleTooltip()
            !openSettings.isUnbound && openSettings.matches(event) -> Minecraft.getInstance().execute { SettingsScreen.open(screen) }
        }
    }

    private fun doToggleOriginal() {
        originalToggled = !originalToggled
        mod.showToggle(T.tr("toggle.original"), originalToggled)
    }

    private fun doToggleChat() {
        val on = !TranslatorConfig.current.chatEnabled
        TranslatorConfig.update(TranslatorConfig.chatEnabled, on)
        mod.showToggle(T.tr("toggle.chat"), on)
    }

    private fun doToggleTooltip() {
        val on = !TranslatorConfig.current.tooltipEnabled
        TranslatorConfig.update(TranslatorConfig.tooltipEnabled, on)
        mod.showToggle(T.tr("toggle.tooltip"), on)
    }
}
