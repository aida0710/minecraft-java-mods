package com.aida0710.chattranslator.neoforge

import com.aida0710.chattranslator.ChatTranslator
import com.aida0710.chattranslator.Commands
import com.aida0710.chattranslator.SettingsScreen
import net.minecraft.commands.CommandSourceStack
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent

/**
 * NeoForge の入口。NeoForge のイベントを [ChatTranslator] に渡す。
 * イベントの型はラムダから推定できない (Kotlin のラムダは型引数が消える) ため、addListener に型を明示する
 */
@Mod(value = ChatTranslator.MODID, dist = [Dist.CLIENT])
class NeoForgeEntry(modBus: IEventBus, container: ModContainer) {
    private val mod = ChatTranslator(FMLPaths.CONFIGDIR.get())

    init {
        container.registerExtensionPoint(IConfigScreenFactory::class.java, IConfigScreenFactory { _, parent -> SettingsScreen(parent) })

        modBus.addListener(RegisterKeyMappingsEvent::class.java) { ev ->
            ev.registerCategory(mod.keys.category)
            mod.keys.mappings.forEach { ev.register(it) }
        }
        val gameBus = NeoForge.EVENT_BUS
        gameBus.addListener(ScreenEvent.KeyPressed.Pre::class.java) { ev ->
            // 取り消すと画面の既定の処理 (チャット欄の送信など) が動かない
            if (mod.keys.onScreenKey(ev.screen, ev.keyEvent)) ev.isCanceled = true
        }
        val commands = Commands<CommandSourceStack> { source, message -> source.sendSystemMessage(message) }
        gameBus.addListener(RegisterClientCommandsEvent::class.java) { commands.register(it.dispatcher, mod) }

        // Player (プレイヤー発言)・System (システムメッセージ) のどちらもこのイベントの派生として届く
        gameBus.addListener(ClientChatReceivedEvent::class.java) { ev ->
            mod.onChatReceived(ev.message, isSystem = ev is ClientChatReceivedEvent.System)?.let { ev.message = it }
        }
        gameBus.addListener(ClientPlayerNetworkEvent.LoggingIn::class.java) { mod.onLoggedIn() }
        gameBus.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { mod.onLoggedOut() }
        gameBus.addListener(ItemTooltipEvent::class.java) { mod.onTooltip(it.toolTip) }
        gameBus.addListener(ClientTickEvent.Post::class.java) { mod.onClientTick() }
    }
}
