package com.aida0710.chattranslator

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RegisterClientCommandsEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.SystemMessageReceivedEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.ItemTooltipEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.event.config.ModConfigEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.fml.loading.FMLPaths
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

@Mod(ChatTranslator.MODID)
class ChatTranslator(context: FMLJavaModLoadingContext) {
    companion object {
        const val MODID = "chattranslator"
        @JvmField val LOGGER: Logger = LoggerFactory.getLogger("ChatTranslator")
    }

    private val dataDir = FMLPaths.CONFIGDIR.get().resolve(MODID)
    private val glossary = Glossary(dataDir.resolve("glossary.json"))
    private lateinit var cache: TranslationCache
    private lateinit var translator: Translator
    private lateinit var chat: ChatHandler
    private lateinit var tooltip: TooltipHandler
    private val notices = ConcurrentLinkedQueue<Pair<String, Boolean>>()
    private val ollama = OllamaManager { message, progress -> queueNotice(message, progress) }

    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MODID, "main"))
    private val toggleChatKey = KeyMapping("key.chattranslator.toggle_chat", InputConstants.UNKNOWN.value, category)
    private val toggleTooltipKey = KeyMapping("key.chattranslator.toggle_tooltip", InputConstants.UNKNOWN.value, category)
    private val showOriginalKey = KeyMapping("key.chattranslator.show_original", InputConstants.KEY_J, category)

    init {
        context.registerConfig(ModConfig.Type.CLIENT, TranslatorConfig.SPEC)
        val modBus = context.modBusGroup
        ModConfigEvent.Loading.getBus(modBus).addListener { if (it.config.modId == MODID) onConfigLoaded() }
        ModConfigEvent.Reloading.getBus(modBus).addListener { if (it.config.modId == MODID) TranslatorConfig.reload() }

        RegisterKeyMappingsEvent.BUS.addListener { it.register(toggleChatKey); it.register(toggleTooltipKey); it.register(showOriginalKey) }
        RegisterClientCommandsEvent.BUS.addListener { registerCommands(it.dispatcher) }

        // Hypixel はプレイヤー発言も含めほぼ全てシステムメッセージとして送ってくる。
        // 元メッセージはキャンセルせず、必要なら差し替えるだけ
        SystemMessageReceivedEvent.BUS.addListener(Consumer { ev ->
            if (ready()) chat.onMessage(ev.message, isSystem = true)?.let { ev.message = it }
        })
        ClientChatReceivedEvent.Player.BUS.addListener(Consumer { ev ->
            if (ready()) chat.onMessage(ev.message, isSystem = false)?.let { ev.message = it }
        })
        ClientChatReceivedEvent.BUS.addListener(Consumer { ev ->
            // 継承イベントとして Player が届いた場合は上のリスナーで処理済み
            if (ev !is ClientChatReceivedEvent.Player && ready()) chat.onMessage(ev.message, isSystem = false)?.let { ev.message = it }
        })
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(Consumer { onLoggedIn() })
        ItemTooltipEvent.BUS.addListener(Consumer { if (ready()) tooltip.onTooltip(it.toolTip) })
        TickEvent.ClientTickEvent.Post.BUS.addListener(Consumer { onClientTick() })
    }

    private fun ready() = ::translator.isInitialized

    /** インベントリ画面中は KeyMapping の状態が更新されないため、キーボードの生の状態を見る */
    private fun isHeld(key: KeyMapping): Boolean {
        val k = key.key
        return k.type == InputConstants.Type.KEYBOARD && k.value > 0 && InputConstants.isKeyDown(k.value)
    }

    private fun onConfigLoaded() {
        TranslatorConfig.reload()
        if (ready()) return
        glossary.load()
        cache = TranslationCache(dataDir.resolve("cache.json"), TranslatorConfig.current.maxCacheEntries).also { it.load() }
        translator = Translator(LlmClient(), cache, glossary)
        chat = ChatHandler(translator)
        LOGGER.info("Chat replace mode available: {}", chat.canReplace)
        tooltip = TooltipHandler(translator) { isHeld(showOriginalKey) }

        val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ChatTranslator-cache").apply { isDaemon = true } }
        saver.scheduleWithFixedDelay({ cache.saveIfDirty() }, 60, 60, TimeUnit.SECONDS)
        Runtime.getRuntime().addShutdownHook(Thread({ translator.shutdown() }, "ChatTranslator-shutdown"))
        LOGGER.info("ChatTranslator ready: model={} endpoint={} cache={} entries",
            TranslatorConfig.current.model, TranslatorConfig.current.endpoint, cache.size())
        // 起動直後にモデル確認 (Ollama なら未取得時に自動 pull)
        ollama.checkAsync(TranslatorConfig.current)
    }


    /** 任意のスレッドから呼べる。ワールド参加前ならログだけ残し、参加後にチャットへ出す */
    private fun queueNotice(message: String, progress: Boolean) {
        if (!progress) LOGGER.info(message)
        notices.add(message to progress)
    }

    private fun flushNotices() {
        val mc = Minecraft.getInstance()
        if (mc.player == null) {
            // 進捗表示は溜めても意味がないので捨てる
            notices.removeIf { it.second }
            return
        }
        while (true) {
            val (message, progress) = notices.poll() ?: break
            val component = Component.literal("[ChatTranslator] $message").withStyle(ChatFormatting.AQUA)
            if (progress) mc.gui.hud.setOverlayMessage(component, false) else mc.gui.hud.chat.addClientSystemMessage(component)
        }
    }

    private fun onLoggedIn() {
        // 起動時に LLM が落ちていた場合などに備えて再確認
        if (ready() && ollama.state in setOf(OllamaManager.State.NOT_RUNNING, OllamaManager.State.FAILED)) {
            ollama.checkAsync(TranslatorConfig.current)
        }
    }

    private fun onClientTick() {
        if (!ready()) return
        flushNotices()
        while (toggleChatKey.consumeClick()) {
            chat.enabled = !chat.enabled
            notify("チャット翻訳", chat.enabled)
        }
        while (toggleTooltipKey.consumeClick()) {
            tooltip.enabled = !tooltip.enabled
            notify("ツールチップ翻訳", tooltip.enabled)
        }
    }

    private fun notify(label: String, on: Boolean) {
        Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
            Component.literal("[ChatTranslator] $label: ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(if (on) "ON" else "OFF").withStyle(if (on) ChatFormatting.GREEN else ChatFormatting.RED)),
        )
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        fun reply(src: CommandSourceStack, text: String) = src.sendSystemMessage(Component.literal("[ChatTranslator] $text").withStyle(ChatFormatting.AQUA))

        dispatcher.register(
            Commands.literal("chattranslator")
                .then(Commands.literal("status").executes { ctx ->
                    val c = TranslatorConfig.current
                    reply(ctx.source, "chat=${chat.enabled && c.chatEnabled} (${c.chatDisplayMode}) tooltip=${tooltip.enabled && c.tooltipEnabled} model=${c.model} (${ollama.state}) endpoint=${c.endpoint} cache=${cache.size()}")
                    1
                })
                .then(Commands.literal("swap").then(Commands.argument("id", IntegerArgumentType.integer(0)).executes { ctx ->
                    // チャットの [原文] / [訳] ボタンから実行される
                    if (!chat.swap(IntegerArgumentType.getInteger(ctx, "id"))) reply(ctx.source, "このメッセージは古いため切り替えられません")
                    1
                }))
                .then(Commands.literal("pull").executes { ctx ->
                    reply(ctx.source, "${TranslatorConfig.current.model} を確認/ダウンロードします")
                    ollama.checkAsync(TranslatorConfig.current, forcePull = true)
                    1
                })
                .then(Commands.literal("reload").executes { ctx ->
                    glossary.load()
                    reply(ctx.source, "glossary.json を再読み込みしました")
                    1
                })
                .then(Commands.literal("clearcache").executes { ctx ->
                    translator.clearCache()
                    cache.saveIfDirty()
                    reply(ctx.source, "翻訳キャッシュを削除しました")
                    1
                })
                .then(Commands.literal("test").then(Commands.argument("text", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes { ctx ->
                    val text = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "text")
                    val mc = Minecraft.getInstance()
                    translator.translateChat(
                        text,
                        onSuccess = { tr -> mc.execute { reply(ctx.source, "$text → $tr") } },
                        onFailure = { mc.execute { reply(ctx.source, "翻訳に失敗しました。LLM サーバーの起動とモデル名を確認してください (ログに詳細)") } },
                    )
                    1
                })),
        )
    }
}
