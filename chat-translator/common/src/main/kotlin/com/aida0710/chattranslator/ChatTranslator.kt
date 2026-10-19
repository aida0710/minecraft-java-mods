package com.aida0710.chattranslator

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * mod の本体。ローダー (Forge / NeoForge / Fabric) ごとの入口がこれを作り、
 * 各ローダーのイベントから on〜 の関数を呼ぶ。
 *
 * @param configDir .minecraft/config
 */
class ChatTranslator(configDir: Path) {
    companion object {
        const val MODID = "chattranslator"
        @JvmField val LOGGER: Logger = LoggerFactory.getLogger("ChatTranslator")

        private val TOGGLE_TOAST = net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(2000L)

        /** 設定画面などから各機能に触るための入口 */
        lateinit var instance: ChatTranslator
            private set
    }

    val dataDir: Path = configDir.resolve(MODID)
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
    private var lastProvider: ProviderType? = null

    // 上のプロパティの初期化が済んでから起動する (Kotlin は宣言順に初期化するため、init はプロパティより後に置く)
    init {
        instance = this
        TranslatorConfig.load(configDir.resolve("$MODID-client.toml"))
        start()
    }

    fun ready() = ::translator.isInitialized

    private fun start() {
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

    // ---- 各ローダーのイベントから呼ぶ ----

    /**
     * 受信したチャット・システムメッセージ。元メッセージはキャンセルせず、必要なら差し替えるだけ
     * (Hypixel はプレイヤー発言も含めほぼ全てシステムメッセージとして送ってくる)。
     * @return 表示するメッセージを差し替える場合はその Component
     */
    fun onChatReceived(message: Component, isSystem: Boolean): Component? =
        if (ready()) chat.onMessage(message, isSystem) else null

    /** アイテムのツールチップ。lines を書き換える */
    fun onTooltip(lines: MutableList<Component>) {
        if (ready()) tooltip.onTooltip(lines)
    }

    /** ワールド/サーバーに入ったときに翻訳エンジンを起動する (ゲーム起動時は何もしないので軽い) */
    fun onLoggedIn() {
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

    fun onLoggedOut() {
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

    fun onClientTick() {
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
