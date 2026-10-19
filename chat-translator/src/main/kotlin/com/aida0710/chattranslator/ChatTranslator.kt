package com.aida0710.chattranslator

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
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

        private val TOGGLE_TOAST = net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(2000L)

        /** 設定画面などから各機能に触るための入口 */
        lateinit var instance: ChatTranslator
            private set
    }

    val dataDir = FMLPaths.CONFIGDIR.get().resolve(MODID)!!
    val itemNames = ItemNames(dataDir.resolve("skyblock_items.json"))
    val dictionaries = Dictionaries(dataDir.resolve("dictionaries"), itemNames)
    val cache = TranslationCache(dataDir.resolve("cache.json"))
    val backup = Backup(dataDir.resolve("exports"), cache, dictionaries)
    val ollama = OllamaManager { message, progress -> queueNotice(message, progress) }
    val llama = LlamaServerManager(dataDir.resolve("llama")) { message, progress -> queueNotice(message, progress) }
    lateinit var translator: Translator
        private set
    lateinit var chat: ChatHandler
        private set
    lateinit var tooltip: TooltipHandler
        private set
    val keys = Keybinds(this)

    private var selfTest: SelfTest? = null
    private val notices = ConcurrentLinkedQueue<Pair<Component, Boolean>>()
    /** 未設定の案内を 1 セッションに 1 回だけ出す */
    private var notifiedNotReady = false

    init {
        instance = this
        context.registerConfig(ModConfig.Type.CLIENT, TranslatorConfig.SPEC)
        val modBus = context.modBusGroup
        ModConfigEvent.Loading.getBus(modBus).addListener { if (it.config.modId == MODID) onConfigLoaded() }
        ModConfigEvent.Reloading.getBus(modBus).addListener { if (it.config.modId == MODID) TranslatorConfig.reload() }
        SettingsScreen.registerConfigButton(context)

        RegisterKeyMappingsEvent.BUS.addListener { keys.register(it) }
        RegisterClientCommandsEvent.BUS.addListener { Commands.register(it.dispatcher, this) }
        keys.registerScreenListener()

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
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(Consumer { onLoggedOut() })
        ItemTooltipEvent.BUS.addListener(Consumer { if (ready()) tooltip.onTooltip(it.toolTip) })
        TickEvent.ClientTickEvent.Post.BUS.addListener(Consumer { onClientTick() })
    }

    fun ready() = ::translator.isInitialized

    private fun onConfigLoaded() {
        TranslatorConfig.reload()
        if (ready()) return
        val cfg = TranslatorConfig.current
        cache.maxEntries = cfg.maxCacheEntries
        cache.persist = cfg.cachePersist
        cache.load()
        dictionaries.load()
        translator = Translator(
            mapOf(
                ProviderType.BUILTIN to BuiltinProvider({ llama.endpoint }, { llama.isInstalled(TranslatorConfig.current) }),
                ProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleProvider(),
                ProviderType.CLAUDE to ClaudeProvider(),
                ProviderType.DEEPL to DeepLProvider(),
                ProviderType.GOOGLE to GoogleProvider(),
            ),
            cache, dictionaries, itemNames,
        )
        chat = ChatHandler(translator)
        LOGGER.info("Chat replace mode available: {}", chat.canReplace)
        tooltip = TooltipHandler(translator) { keys.showOriginal() }
        llama.refresh(cfg)
        TranslatorConfig.onChange(::onSettingsChanged)
        System.getProperty("chattranslator.selftest")?.let { dir ->
            selfTest = SelfTest(java.nio.file.Path.of(dir), chat) { translator.isReady() }
            LOGGER.warn("SELFTEST enabled: {}", dir)
        }

        val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ChatTranslator-cache").apply { isDaemon = true } }
        saver.scheduleWithFixedDelay({ cache.saveIfDirty() }, 60, 60, TimeUnit.SECONDS)
        Runtime.getRuntime().addShutdownHook(Thread({
            translator.shutdown()
            llama.stop()
        }, "ChatTranslator-shutdown"))
        LOGGER.info("ChatTranslator ready: provider={} model={} cache={} entries", cfg.provider, cfg.modelKey, cache.size())
    }

    private var lastProvider: ProviderType? = null

    /** 設定画面などで値が変わったときに各部へ反映する */
    private fun onSettingsChanged(cfg: TranslatorConfig.Values) {
        if (!ready()) return
        cache.maxEntries = cfg.maxCacheEntries
        if (cache.persist != cfg.cachePersist) {
            cache.persist = cfg.cachePersist
            cache.markDirty()
            cache.saveIfDirty()
        }
        dictionaries.apply()
        llama.refresh(cfg)
        if (lastProvider != null && lastProvider != cfg.provider) {
            // 内蔵 LLM 以外に切り替えたら VRAM を空ける
            if (cfg.provider != ProviderType.BUILTIN) llama.stop()
            else if (Minecraft.getInstance().player != null) llama.startAsync(cfg)
            notifiedNotReady = false
        }
        lastProvider = cfg.provider
    }

    /** ワールド/サーバーに入ったときに翻訳エンジンを起動する (ゲーム起動時は何もしないので軽い) */
    private fun onLoggedIn() {
        if (!ready()) return
        val cfg = TranslatorConfig.current
        lastProvider = cfg.provider
        when (cfg.provider) {
            ProviderType.BUILTIN -> llama.startAsync(cfg)
            ProviderType.OPENAI_COMPATIBLE -> ollama.checkAsync(cfg)
            else -> {}
        }
        val notReady = if (cfg.provider == ProviderType.BUILTIN) !llama.isInstalled(cfg) else !translator.isReady(cfg)
        if (notReady && !notifiedNotReady) {
            notifiedNotReady = true
            queueNotice(translator.notReadyReason(cfg), false)
        }
    }

    private fun onLoggedOut() {
        // 遊んでいない間は VRAM を使わない
        if (ready()) llama.stop()
    }

    /** 任意のスレッドから呼べる。ワールド参加前ならログだけ残し、参加後にチャットへ出す */
    fun queueNotice(message: Component, progress: Boolean) {
        if (!progress) LOGGER.info(message.string)
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
            val component = Component.literal("[ChatTranslator] ").append(message).withStyle(ChatFormatting.AQUA)
            if (progress) mc.gui.hud.setOverlayMessage(component, false) else mc.gui.hud.chat.addClientSystemMessage(component)
        }
    }

    private fun onClientTick() {
        if (!ready()) return
        flushNotices()
        selfTest?.onTick()
        keys.onTick()
    }

    /** ON/OFF 切り替えの結果を画面下部に表示する */
    fun showToggle(label: Component, on: Boolean) {
        val mc = Minecraft.getInstance()
        val component = Component.literal("[ChatTranslator] ").append(label).append(": ").withStyle(ChatFormatting.AQUA)
            .append(T.onOff(on).withStyle(if (on) ChatFormatting.GREEN else ChatFormatting.RED))
        // インベントリなどの画面を開いているとアクションバーは隠れるので、トーストで知らせる
        if (mc.gui.screen() != null) {
            net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(
                mc.gui.toastManager(), TOGGLE_TOAST, Component.literal("Chat Translator"),
                label.copy().append(": ").append(T.onOff(on).withStyle(if (on) ChatFormatting.GREEN else ChatFormatting.RED)),
            )
        } else if (mc.player != null) mc.gui.hud.setOverlayMessage(component, false)
    }

    /** 状態表示用 */
    fun engineStatus(cfg: TranslatorConfig.Values = TranslatorConfig.current): Component = when (cfg.provider) {
        ProviderType.BUILTIN -> when (llama.state) {
            LlamaServerManager.State.NOT_INSTALLED -> T.tr("status.not_installed")
            LlamaServerManager.State.INSTALLING -> T.tr("status.installing", (llama.progressFraction * 100).toInt())
            LlamaServerManager.State.STOPPED -> T.tr("status.installed")
            LlamaServerManager.State.STARTING -> T.tr("status.starting")
            LlamaServerManager.State.READY -> T.tr("status.running")
            LlamaServerManager.State.FAILED -> T.tr("status.error", llama.lastError ?: "?")
        }
        ProviderType.OPENAI_COMPATIBLE -> Component.literal("${cfg.openaiEndpoint} (${ollama.state})")
        else -> T.tr(if (translator.isReady(cfg)) "status.key_set" else "status.key_missing")
    }
}
