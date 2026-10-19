package com.aida0710.chattranslator.fabric

import com.aida0710.chattranslator.ChatTranslator
import com.aida0710.chattranslator.Commands
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping

/** Fabric の入口。Fabric API のイベントを [ChatTranslator] に渡す */
class FabricEntry : ClientModInitializer {
    override fun onInitializeClient() {
        val mod = ChatTranslator(FabricLoader.getInstance().configDir)

        KeyMapping.Category.register(mod.keys.category.id())
        mod.keys.mappings.forEach { KeyMappingHelper.registerKeyMapping(it) }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            // false を返すと画面の既定の処理 (チャット欄の送信など) が動かない
            ScreenKeyboardEvents.allowKeyPress(screen).register { s, event -> !mod.keys.onScreenKey(s, event) }
        }
        val commands = Commands<FabricClientCommandSource> { source, message -> source.sendFeedback(message) }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ -> commands.register(dispatcher, mod) }

        // システムメッセージ (Hypixel はプレイヤー発言もこちらで送る) は表示前に差し替えられる
        ClientReceiveMessageEvents.MODIFY_GAME.register { message, _ ->
            mod.onChatReceived(message, isSystem = true) ?: message
        }
        // 署名付きのプレイヤー発言は表示前に書き換える手段が無いため、表示後に同じ行を差し替える
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            mod.onChatReceived(message, isSystem = false)?.let { mod.chat.replaceAfterShown(message, it) }
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> mod.onLoggedIn() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> mod.onLoggedOut() }
        ItemTooltipCallback.EVENT.register { _, _, _, lines -> mod.onTooltip(lines) }
        ClientTickEvents.END_CLIENT_TICK.register { mod.onClientTick() }
    }
}
