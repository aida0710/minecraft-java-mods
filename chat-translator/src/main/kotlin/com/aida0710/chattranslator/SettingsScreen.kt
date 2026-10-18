package com.aida0710.chattranslator

import com.mojang.blaze3d.Blaze3D
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ContainerObjectSelectionList
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.components.tabs.GridLayoutTab
import net.minecraft.client.gui.components.tabs.MenuTabBar
import net.minecraft.client.gui.components.tabs.TabManager
import net.minecraft.client.gui.layouts.CommonLayouts
import net.minecraft.client.gui.layouts.GridLayout
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

/** 内蔵 LLM で選べるモデル */
enum class ModelPreset(val key: String, val url: String, val sha256: String, val bytes: Long) {
    GEMMA_4B("4b", TranslatorConfig.DEFAULT_MODEL_URL, TranslatorConfig.DEFAULT_MODEL_SHA256, 2_489_909_760),
    GEMMA_12B(
        "12b",
        "https://huggingface.co/mradermacher/translategemma-12b-it-GGUF/resolve/main/translategemma-12b-it.Q4_K_M.gguf",
        "b7aac4b4be7ab0c49b6556c29c4467e74313df7f1e95d9f9676bb2adf0afa528", 7_300_794_112,
    ),
    ;

    val label get() = T.tr("model.$key")

    companion object {
        fun of(url: String) = entries.firstOrNull { it.url == url }
    }
}

private fun gb(bytes: Long) = "%.1f".format(bytes / 1_000_000_000.0)

/**
 * 設定画面 (/chattranslator config、mod 一覧の「設定」ボタン、またはキー割り当てから開く)。
 * タブ: 一般 / 翻訳エンジン / 辞書 / キャッシュ
 */
class SettingsScreen(private val parent: Screen?, private val initialTab: Int = 0) : Screen(T.tr("settings.title")) {
    private val mod get() = ChatTranslator.instance
    private val layout = HeaderAndFooterLayout(this)
    private val tabManager = TabManager({ addRenderableWidget(it) }, { removeWidget(it) })
    private var bar: MenuTabBar? = null

    /** 入力欄の内容は画面を閉じるとき (またはテスト時) に保存する */
    private val pendingEdits = mutableListOf<() -> Unit>()
    /** 毎 tick 更新する表示 */
    private val refreshers = mutableListOf<() -> Unit>()

    override fun init() {
        pendingEdits.clear()
        refreshers.clear()
        val b = MenuTabBar.builder(tabManager, width).addTabs(GeneralTab(), EngineTab(), DictionaryTab(), CacheTab()).build()
        bar = b
        addRenderableWidget(b)
        val footer = layout.addToFooter(LinearLayout.horizontal().spacing(8))
        footer.addChild(Button.builder(T.tr("settings.keybinds")) {
            commitEdits()
            minecraft.gui.setScreen(KeyBindsScreen(this, minecraft.options))
        }.width(150).build())
        footer.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(150).build())
        layout.visitWidgets { addRenderableWidget(it) }
        b.selectTab(initialTab.coerceIn(0, 3), false)
        repositionElements()
    }

    override fun repositionElements() {
        val b = bar ?: return
        b.arrangeElements(width)
        val top = b.rectangle.bottom()
        tabManager.setTabArea(ScreenRectangle(0, top, width, height - layout.footerHeight - top))
        layout.headerHeight = top
        layout.arrangeElements()
    }

    override fun keyPressed(event: KeyEvent): Boolean = bar?.keyPressed(event) == true || super.keyPressed(event)

    override fun tick() {
        super.tick()
        refreshers.forEach { it() }
    }

    override fun removed() {
        commitEdits()
        super.removed()
    }

    override fun onClose() {
        commitEdits()
        minecraft.gui.setScreen(parent)
    }

    override fun isPauseScreen() = false

    private fun commitEdits() {
        pendingEdits.forEach { it() }
    }

    /** 同じタブを開き直す (エンジンの種類を変えたときなど、表示項目が変わる場合) */
    private fun reopen(tab: Int) {
        commitEdits()
        minecraft.gui.setScreen(SettingsScreen(parent, tab))
    }

    // ---- 部品 ----

    private fun cfg() = TranslatorConfig.current

    private fun toggle(label: Component, value: Boolean, width: Int = HALF, tooltip: Component? = null, onChange: (Boolean) -> Unit): CycleButton<Boolean> =
        CycleButton.onOffBuilder(value)
            .apply { if (tooltip != null) withTooltip { Tooltip.create(tooltip) } }
            .create(0, 0, width, 20, label) { _, v -> onChange(v) }

    private fun <T : Any> cycle(label: Component, values: List<T>, value: T, name: (T) -> Component, width: Int = HALF, onChange: (T) -> Unit): CycleButton<T> =
        CycleButton.builder(name, value)
            .withValues(values)
            .create(0, 0, width, 20, label) { _, v -> onChange(v) }

    private fun button(label: Component, width: Int = HALF, onPress: () -> Unit): Button =
        Button.builder(label) { onPress() }.width(width).build()

    /** 毎 tick 内容を更新する 1 行の文字 */
    private fun label(text: () -> Component, width: Int = FULL): StringWidget {
        val w = StringWidget(width, 12, text(), font)
        refreshers += { w.message = text() }
        return w
    }

    /** 灰色の説明文 (折り返し) */
    private fun note(text: Component): MultiLineTextWidget =
        MultiLineTextWidget(text.copy().withStyle(ChatFormatting.GRAY), font).setMaxWidth(FULL)

    /** 文字列の設定欄 (上にラベル) */
    private fun field(label: Component, value: String, width: Int, maxLength: Int = 512, onCommit: (String) -> Unit): LayoutElement {
        val box = EditBox(font, width, 20, label)
        box.setMaxLength(maxLength)
        box.value = value
        pendingEdits += { if (box.value != value) onCommit(box.value) }
        return CommonLayouts.labeledElement(font, box, label)
    }

    /** API キー欄。既存のキーは表示せず、入力したときだけ上書きする */
    private fun secretField(label: Component, current: String, width: Int = FULL, onCommit: (String) -> Unit): LayoutElement {
        val box = EditBox(font, width, 20, label)
        box.setMaxLength(512)
        val hint = if (current.isBlank()) T.tr("settings.key.unset") else T.tr("settings.key.set", current.takeLast(4))
        box.setHint(hint.withStyle(ChatFormatting.DARK_GRAY))
        pendingEdits += { if (box.value.isNotBlank()) onCommit(box.value.trim()) }
        return CommonLayouts.labeledElement(font, box, label)
    }

    private fun GridLayout.RowHelper.full(w: LayoutElement) = addChild(w, 2)

    // ---- タブ ----

    private inner class GeneralTab : GridLayoutTab(T.tr("settings.tab.general")) {
        init {
            val c = cfg()
            val rows = layout.rowSpacing(4).columnSpacing(10).createRowHelper(2)
            val sources = listOf(Languages.AUTO) + Languages.ALL.map { it.code }
            val langName = { code: String -> Component.literal(Languages.displayName(code)) }
            rows.addChild(cycle(T.tr("settings.source"), sources, c.sourceCode, langName) { TranslatorConfig.update(TranslatorConfig.sourceCode, it) })
            rows.addChild(cycle(T.tr("settings.target"), Languages.ALL.map { it.code }, c.targetCode, langName) { TranslatorConfig.update(TranslatorConfig.targetCode, it) })
            rows.addChild(toggle(T.tr("settings.chat"), c.chatEnabled) { TranslatorConfig.update(TranslatorConfig.chatEnabled, it) })
            rows.addChild(cycle(T.tr("settings.display"), ChatDisplayMode.entries, c.chatDisplayMode, { T.tr("settings.display.${it.name.lowercase()}") }) {
                TranslatorConfig.update(TranslatorConfig.chatDisplayMode, it)
            })
            rows.addChild(toggle(T.tr("settings.system"), c.chatTranslateSystemMessages, tooltip = T.tr("settings.system.tooltip")) {
                TranslatorConfig.update(TranslatorConfig.chatSystem, it)
            })
            rows.addChild(toggle(T.tr("settings.tooltip"), c.tooltipEnabled) { TranslatorConfig.update(TranslatorConfig.tooltipEnabled, it) })
            rows.addChild(toggle(T.tr("settings.item_name"), c.tooltipTranslateItemName, tooltip = T.tr("settings.item_name.tooltip")) {
                TranslatorConfig.update(TranslatorConfig.tooltipItemName, it)
            })
            rows.addChild(toggle(T.tr("settings.show_original"), c.tooltipShowOriginal, tooltip = T.tr("settings.show_original.tooltip")) {
                TranslatorConfig.update(TranslatorConfig.tooltipShowOriginal, it)
            })
            rows.full(note(T.tr("settings.general.note")))
        }
    }

    private inner class EngineTab : GridLayoutTab(T.tr("settings.tab.engine")) {
        @Volatile private var testResult: Component? = null

        init {
            val c = cfg()
            val rows = layout.rowSpacing(4).columnSpacing(10).createRowHelper(2)
            rows.full(cycle(T.tr("settings.engine"), ProviderType.entries, c.provider, { it.label }, FULL) {
                TranslatorConfig.update(TranslatorConfig.provider, it)
                reopen(1)
            })
            rows.full(label({
                val status = T.tr("settings.status", mod.engineStatus())
                testResult?.let { status.append("  |  ").append(it) } ?: status
            }))
            when (c.provider) {
                ProviderType.BUILTIN -> builtin(rows, c)
                ProviderType.OPENAI_COMPATIBLE -> {
                    rows.full(field(T.tr("settings.openai.endpoint"), c.openaiEndpoint, FULL) { TranslatorConfig.update(TranslatorConfig.openaiEndpoint, it) })
                    rows.addChild(field(T.tr("settings.model"), c.openaiModel, HALF) { TranslatorConfig.update(TranslatorConfig.openaiModel, it) })
                    rows.addChild(secretField(T.tr("settings.openai.key"), c.openaiApiKey, HALF) { TranslatorConfig.update(TranslatorConfig.openaiApiKey, it) })
                    rows.full(toggle(T.tr("settings.openai.template"), c.openaiUsePromptTemplate, FULL, T.tr("settings.openai.template.tooltip")) {
                        TranslatorConfig.update(TranslatorConfig.openaiUsePromptTemplate, it)
                    })
                }
                ProviderType.CLAUDE -> {
                    rows.full(secretField(T.tr("settings.claude.key"), c.claudeApiKey) { TranslatorConfig.update(TranslatorConfig.claudeApiKey, it) })
                    rows.full(field(T.tr("settings.claude.model"), c.claudeModel, FULL) { TranslatorConfig.update(TranslatorConfig.claudeModel, it) })
                }
                ProviderType.DEEPL -> rows.full(secretField(T.tr("settings.deepl.key"), c.deeplApiKey) { TranslatorConfig.update(TranslatorConfig.deeplApiKey, it) })
                ProviderType.GOOGLE -> rows.full(secretField(T.tr("settings.google.key"), c.googleApiKey) { TranslatorConfig.update(TranslatorConfig.googleApiKey, it) })
            }
            rows.full(button(T.tr("settings.test"), FULL) { test() })
            if (c.provider != ProviderType.BUILTIN) rows.full(note(T.tr("settings.cloud.note")))
        }

        private fun builtin(rows: GridLayout.RowHelper, c: TranslatorConfig.Values) {
            val llama = mod.llama
            rows.addChild(cycle(T.tr("settings.model"), ModelPreset.entries, ModelPreset.of(c.modelUrl) ?: ModelPreset.GEMMA_4B, { T.tr("model.${it.key}.short") }) {
                TranslatorConfig.update(TranslatorConfig.modelUrl, it.url)
                TranslatorConfig.update(TranslatorConfig.modelSha256, it.sha256)
                llama.stop()
                llama.refresh(cfg())
            }.also { it.setTooltip(Tooltip.create(ModelPreset.entries.map { p -> p.label }.reduce { a, b -> a.copy().append("\n").append(b) })) })
            rows.addChild(cycle(T.tr("settings.gpu"), GpuBackend.entries, c.gpuBackend, { Component.literal(it.name) }) {
                TranslatorConfig.update(TranslatorConfig.gpuBackend, it)
                llama.stop()
                llama.refresh(cfg())
            }.also { it.setTooltip(Tooltip.create(T.tr("settings.gpu.tooltip"))) })

            val install = button(T.tr("settings.install", "?")) {
                val cur = cfg()
                when (llama.state) {
                    LlamaServerManager.State.NOT_INSTALLED, LlamaServerManager.State.FAILED ->
                        if (!llama.isInstalled(cur)) llama.installAsync(cur, startAfter = minecraft.player != null) else llama.startAsync(cur)
                    LlamaServerManager.State.STOPPED -> llama.startAsync(cur)
                    LlamaServerManager.State.READY -> { llama.stop(); llama.refresh(cur) }
                    else -> {}
                }
            }
            val remove = button(T.tr("settings.uninstall", "0.0")) { llama.uninstall(cfg()) }
            refreshers += {
                val cur = cfg()
                val size = gb(ModelPreset.of(cur.modelUrl)?.bytes ?: 0L)
                install.message = when (llama.state) {
                    LlamaServerManager.State.NOT_INSTALLED -> T.tr("settings.install", size)
                    LlamaServerManager.State.INSTALLING -> T.tr("status.installing", (llama.progressFraction * 100).toInt())
                    LlamaServerManager.State.STOPPED -> T.tr("settings.start")
                    LlamaServerManager.State.STARTING -> T.tr("status.starting")
                    LlamaServerManager.State.READY -> T.tr("settings.stop")
                    LlamaServerManager.State.FAILED -> T.tr(if (llama.isInstalled(cur)) "settings.restart" else "settings.reinstall")
                }
                install.active = llama.state != LlamaServerManager.State.INSTALLING && llama.state != LlamaServerManager.State.STARTING
                remove.active = llama.isInstalled(cur) && llama.state != LlamaServerManager.State.INSTALLING
                remove.message = T.tr("settings.uninstall", gb(llama.installedBytes()))
            }
            rows.addChild(install)
            rows.addChild(remove)
            rows.full(label({ if (llama.state == LlamaServerManager.State.INSTALLING) llama.progressText else T.tr("settings.builtin.where") }))
        }

        private fun test() {
            commitEdits()
            testResult = T.tr("settings.test.running")
            mod.translator.translateChat(
                "Hello, how are you?",
                onSuccess = { testResult = T.tr("settings.test.ok", it) },
                onFailure = { testResult = T.tr("settings.test.failed", it).withStyle(ChatFormatting.RED) },
            )
        }
    }

    private inner class DictionaryTab : GridLayoutTab(T.tr("settings.tab.dictionary")) {
        init {
            val d = mod.dictionaries
            val rows = layout.rowSpacing(4).columnSpacing(10).createRowHelper(2)
            d.packs.take(6).forEach { pack ->
                val counts = T.tr("settings.dict.counts", pack.keep.size, pack.terms.size, pack.skip.size)
                if (pack.hypixelItems) counts.append(T.tr("settings.dict.items", mod.itemNames.size))
                rows.full(toggle(d.displayName(pack), d.isEnabled(pack.id), FULL, d.description(pack).copy().append("\n").append(counts)) { d.setEnabled(pack.id, it) })
            }
            rows.addChild(button(T.tr("settings.dict.edit")) {
                commitEdits()
                minecraft.gui.setScreen(UserDictionaryScreen(this@SettingsScreen))
            })
            rows.addChild(button(T.tr("settings.dict.folder")) { Blaze3D.openPath(mod.dataDir.resolve("dictionaries")) })
            rows.full(button(T.tr("settings.dict.reload"), FULL) {
                d.load()
                reopen(2)
            })
            rows.full(note(T.tr("settings.dict.note")))
        }
    }

    private inner class CacheTab : GridLayoutTab(T.tr("settings.tab.cache")) {
        init {
            val c = cfg()
            val rows = layout.rowSpacing(4).columnSpacing(10).createRowHelper(2)
            rows.full(toggle(T.tr("settings.cache.persist"), c.cachePersist, FULL, T.tr("settings.cache.persist.tooltip")) {
                TranslatorConfig.update(TranslatorConfig.cachePersist, it)
            })
            val sizes = listOf(1_000, 5_000, 20_000, 100_000, 500_000)
            rows.full(cycle(T.tr("settings.cache.max"), (sizes + c.maxCacheEntries).distinct().sorted(), c.maxCacheEntries, { T.tr("settings.cache.entries", "%,d".format(it)) }, FULL) {
                TranslatorConfig.update(TranslatorConfig.maxCache, it)
            })
            rows.full(label({ T.tr("settings.cache.now", "%,d".format(mod.cache.size()), "%,d".format(mod.cache.fileSize() / 1024)) }))
            rows.full(button(T.tr("settings.cache.clear"), FULL) { mod.translator.clearCache() })
            rows.full(note(T.tr("settings.cache.note")))
        }
    }

    companion object {
        const val FULL = 310
        const val HALF = 150

        /** mod 一覧の「設定」ボタンから開けるようにする */
        fun registerConfigButton(context: FMLJavaModLoadingContext) {
            context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory::class.java) {
                ConfigScreenHandler.ConfigScreenFactory { _: Minecraft, parent: Screen -> SettingsScreen(parent) }
            }
        }

        fun open(parent: Screen? = Minecraft.getInstance().gui.screen(), tab: Int = 0) {
            Minecraft.getInstance().gui.setScreen(SettingsScreen(parent, tab))
        }

        /** コマンドから開く場合は、チャット画面が閉じた次のフレームで開く (execute はその場で実行されてしまうため schedule) */
        fun openLater() {
            val mc = Minecraft.getInstance()
            mc.schedule { open(null) }
        }
    }
}

/** ユーザー辞書の編集画面 (翻訳しない語・訳語の追加と削除) */
class UserDictionaryScreen(private val parent: Screen) : Screen(T.tr("dictedit.title")) {
    private val mod get() = ChatTranslator.instance
    private val layout = HeaderAndFooterLayout(this, 64, 33)
    private lateinit var list: EntryList
    private lateinit var termBox: EditBox
    private lateinit var translationBox: EditBox

    override fun init() {
        val header = layout.addToHeader(LinearLayout.vertical().spacing(6)) { it.alignHorizontallyCenter() }
        header.addChild(StringWidget(title, font)) { it.alignHorizontallyCenter() }
        val inputs = header.addChild(LinearLayout.horizontal().spacing(6))
        termBox = EditBox(font, 130, 20, T.tr("dictedit.term")).also {
            it.setMaxLength(200)
            it.setHint(T.tr("dictedit.term.hint").withStyle(ChatFormatting.DARK_GRAY))
        }
        translationBox = EditBox(font, 130, 20, T.tr("dictedit.translation")).also {
            it.setMaxLength(200)
            it.setHint(T.tr("dictedit.translation.hint").withStyle(ChatFormatting.DARK_GRAY))
        }
        inputs.addChild(termBox)
        inputs.addChild(translationBox)
        inputs.addChild(Button.builder(T.tr("dictedit.add")) { add() }.width(50).build())

        list = layout.addToContents(EntryList())
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(200).build())
        layout.visitWidgets { addRenderableWidget(it) }
        reloadEntries()
        repositionElements()
        setInitialFocus(termBox)
    }

    override fun repositionElements() {
        layout.arrangeElements()
        if (::list.isInitialized) list.updateSize(width, layout)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isConfirmation && (termBox.isFocused || translationBox.isFocused)) {
            add()
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    override fun isPauseScreen() = false

    private fun add() {
        val term = termBox.value.trim()
        if (term.isEmpty()) return
        val translation = translationBox.value.trim()
        if (translation.isEmpty()) mod.dictionaries.addKeep(term) else mod.dictionaries.addTerm(term, translation)
        termBox.value = ""
        translationBox.value = ""
        reloadEntries()
    }

    private fun reloadEntries() {
        val user = mod.dictionaries.userPack
        val entries = (user?.keep?.map { Row(it, null) } ?: emptyList()) + (user?.terms?.map { Row(it.key, it.value) } ?: emptyList())
        list.setRows(entries)
    }

    private inner class Row(val term: String, val translation: String?) : ContainerObjectSelectionList.Entry<Row>() {
        private val delete = Button.builder(T.tr("dictedit.delete")) {
            if (translation == null) mod.dictionaries.removeKeep(term) else mod.dictionaries.removeTerm(term)
            reloadEntries()
        }.size(50, 20).build()

        override fun extractContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, a: Float) {
            val text = if (translation == null) Component.literal(term).append(Component.literal("  ").append(T.tr("dictedit.keep_mark")).withStyle(ChatFormatting.GRAY))
            else Component.literal(term).append(Component.literal("  →  ").withStyle(ChatFormatting.DARK_GRAY)).append(Component.literal(translation).withStyle(ChatFormatting.YELLOW))
            g.text(font, text, contentX, contentYMiddle - 4, -1)
            delete.setPosition(contentRight - delete.width, contentYMiddle - 10)
            delete.extractRenderState(g, mouseX, mouseY, a)
        }

        override fun children(): List<GuiEventListener> = listOf(delete)
        override fun narratables(): List<NarratableEntry> = listOf(delete)
    }

    private inner class EntryList :
        ContainerObjectSelectionList<Row>(Minecraft.getInstance(), width, layout.contentHeight, layout.headerHeight, 24) {
        fun setRows(rows: List<Row>) = replaceEntries(rows)
        override fun getRowWidth() = 320
    }
}
