package com.aida0710.chattranslator.forge

import com.aida0710.chattranslator.ChatTranslator
import com.aida0710.chattranslator.Commands
import com.aida0710.chattranslator.SettingsScreen
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.commands.CommandSourceStack
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RegisterClientCommandsEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.client.event.SystemMessageReceivedEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.ItemTooltipEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.fml.loading.FMLPaths
import java.util.function.Consumer
import java.util.function.Predicate

/** Forge の入口。Forge のイベントを [ChatTranslator] に渡す */
@Mod(ChatTranslator.MODID)
class ForgeEntry(context: FMLJavaModLoadingContext) {
    private val mod = ChatTranslator(FMLPaths.CONFIGDIR.get())

    init {
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory::class.java) {
            ConfigScreenHandler.ConfigScreenFactory { _: Minecraft, parent: Screen -> SettingsScreen(parent) }
        }

        KeyMapping.Category.register(mod.keys.category.id())
        RegisterKeyMappingsEvent.BUS.addListener { ev -> mod.keys.mappings.forEach { ev.register(it) } }
        // true を返すと取り消しになり、画面の既定の処理 (チャット欄の送信など) が動かない
        ScreenEvent.KeyPressed.Pre.BUS.addListener(Predicate { ev -> mod.keys.onScreenKey(ev.screen, ev.info) })
        val commands = Commands<CommandSourceStack> { source, message -> source.sendSystemMessage(message) }
        RegisterClientCommandsEvent.BUS.addListener { commands.register(it.dispatcher, mod) }

        SystemMessageReceivedEvent.BUS.addListener(Consumer { ev ->
            mod.onChatReceived(ev.message, isSystem = true)?.let { ev.message = it }
        })
        ClientChatReceivedEvent.Player.BUS.addListener(Consumer { ev ->
            mod.onChatReceived(ev.message, isSystem = false)?.let { ev.message = it }
        })
        ClientChatReceivedEvent.BUS.addListener(Consumer { ev ->
            // 継承イベントとして Player が届いた場合は上のリスナーで処理済み
            if (ev !is ClientChatReceivedEvent.Player) mod.onChatReceived(ev.message, isSystem = false)?.let { ev.message = it }
        })
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(Consumer { mod.onLoggedIn() })
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(Consumer { mod.onLoggedOut() })
        ItemTooltipEvent.BUS.addListener(Consumer { mod.onTooltip(it.toolTip) })
        TickEvent.ClientTickEvent.Post.BUS.addListener(Consumer { mod.onClientTick() })
    }
}
