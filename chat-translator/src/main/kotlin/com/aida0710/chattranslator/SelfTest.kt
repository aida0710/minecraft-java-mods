package com.aida0710.chattranslator

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.ItemLore
import java.nio.file.Files
import java.nio.file.Path

/**
 * 開発用の自動テスト。-Dchattranslator.selftest=<ディレクトリ> を付けたときだけ有効。
 * <ディレクトリ>/commands.txt に追記されたコマンドを 1 行ずつ実行し、結果を [SELFTEST] としてログに出す。
 */
class SelfTest(private val dir: Path, private val chat: ChatHandler, private val ready: () -> Boolean) {
    private var processedLines = 0
    private var tick = 0
    private var reportedReady = false
    private var tooltipWatch: Pair<ItemStack, Int>? = null

    fun onTick() {
        val mc = Minecraft.getInstance()
        if (!reportedReady && mc.player != null && ready()) {
            reportedReady = true
            log("ready")
        }
        tooltipWatch?.let { (stack, remaining) ->
            val lines = tooltip(stack)
            // ツールチップ表示中と同じく毎 tick 呼び、10 秒後の状態を記録する
            if (remaining <= 0) {
                lines.forEachIndexed { i, l -> log("tooltip[$i] $l") }
                tooltipWatch = null
            } else tooltipWatch = stack to remaining - 1
        }
        if (++tick % 20 != 0) return
        // 起動時のリソース読み込み中 (レジストリ未確定) はコマンドを実行しない
        if (mc.gui.overlay() != null) return
        val file = dir.resolve("commands.txt")
        if (!Files.exists(file)) return
        val lines = Files.readAllLines(file)
        while (processedLines < lines.size) {
            val cmd = lines[processedLines++].trim()
            if (cmd.isNotEmpty()) runCatching { run(mc, cmd) }.onFailure { log("error in '$cmd': $it") }
        }
    }

    private fun run(mc: Minecraft, cmd: String) {
        log("> $cmd")
        val args = cmd.split(' ', limit = 2)
        when (args[0]) {
            "dump" -> mc.gui.hud.chat.allMessages.take(args.getOrNull(1)?.toInt() ?: 10).reversed()
                .forEach { log("chat| ${it.content().string}") }
            "swap" -> log("swap=${chat.swap(args[1].toInt())}")
            // [原文] ボタンをクリックしたときと同じ経路 (ClickEvent.RunCommand → クライアントコマンド) で実行する
            "click" -> mc.player!!.connection.sendUnattendedCommand("chattranslator swap ${args[1]}", null)
            "tooltip" -> {
                val stack = hyperion()
                tooltip(stack).forEachIndexed { i, l -> log("tooltip-before[$i] $l") }
                tooltipWatch = stack to 200
            }
            // ツールチップを実際に画面へ描画する (スクリーンショット確認用)
            "showtooltip" -> mc.gui.setScreen(TooltipPreview(hyperion()))
            // 開発環境ではバニラサーバーに接続すると Forge 自身の設定未読込エラーになるため、シングルプレイで試す
            "world" -> {
                // 既存ワールドを開くと「実験的設定」の確認画面で止まるので毎回新規作成する
                val name = args.getOrElse(1) { "selftest" } + "-" + System.currentTimeMillis()
                if (mc.levelSource.levelExists(name)) mc.createWorldOpenFlows().openWorld(name) { mc.gui.setScreen(TitleScreen()) }
                else mc.createWorldOpenFlows().createFreshLevel(
                    name,
                    LevelSettings(name, GameType.CREATIVE, LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT),
                    WorldOptions(0L, false, false),
                    WorldPresets::createTestWorldDimensions,
                    TitleScreen(),
                )
            }
            "cmd" -> mc.player!!.connection.sendCommand(args[1])
            // サーバーから § コード入りのシステムメッセージを受け取ったのと同じ経路で処理させる (Hypixel の再現)
            "inject" -> mc.gui.chatListener().handleSystemMessage(Component.literal(args[1].replace("\\n", "\n")), true)
            "openchat" -> mc.gui.setScreen(ChatScreen("", false))
            // 設定画面を開く ("settings 1" で 2 番目のタブ)。"dictedit" はユーザー辞書の編集画面
            "settings" -> SettingsScreen.open(null, args.getOrNull(1)?.toIntOrNull() ?: 0)
            "dictedit" -> mc.gui.setScreen(UserDictionaryScreen(SettingsScreen(null, 2)))
            "keep" -> log("keep add=${ChatTranslator.instance.dictionaries.addKeep(args[1])}")
            // 設定値を直接変える: "set provider CLAUDE" など (画面の操作を模擬)
            "lang" -> { val (s, t) = args[1].split(" "); TranslatorConfig.update(TranslatorConfig.sourceCode, s); TranslatorConfig.update(TranslatorConfig.targetCode, t) }
            "provider" -> TranslatorConfig.update(TranslatorConfig.provider, ProviderType.valueOf(args[1]))
            "install" -> ChatTranslator.instance.llama.installAsync(TranslatorConfig.current, startAfter = true)
            // Minecraft の表示言語を切り替える (mod の画面の多言語表示を確認する用)
            "uilang" -> { mc.languageManager.setSelected(args[1]); mc.options.languageCode = args[1]; mc.reloadResourcePacks() }
            // インベントリを開いた状態でショートカットキーを押したときと同じイベントを送る (K = チャット翻訳 ON/OFF に割り当て)
            "screenkey" -> {
                val keys = ChatTranslator.instance.keys
                keys.toggleChat.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_K))
                KeyMapping.resetMapping()
                val inv = net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player!!)
                mc.gui.setScreen(inv)
                net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre.BUS.post(net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre(inv, net.minecraft.client.input.KeyEvent(InputConstants.KEY_K, 0, 0)))
                log("after K in inventory: chatEnabled=${TranslatorConfig.current.chatEnabled}")
            }
            "status" -> log("engine=${ChatTranslator.instance.engineStatus()} ready=${ChatTranslator.instance.translator.isReady()}")
            "close" -> mc.gui.setScreen(null)
            "screenshot" -> Screenshot.grab(mc.gameDirectory, args.getOrElse(1) { "selftest" } + ".png", mc.gameRenderer.mainRenderTarget(), 1) { log("screenshot: ${it.string}") }
            "holdkey" -> log("J held=${InputConstants.isKeyDown(InputConstants.KEY_J)}")
            "quit" -> mc.stop()
            else -> log("unknown command")
        }
    }


    /** Hypixel SkyBlock の実際の lore に近いアイテム (1 文が複数行に折り返されている) */
    private fun hyperion() = ItemStack(Items.IRON_SWORD).apply {
        set(DataComponents.CUSTOM_NAME, Component.literal("§dHeroic Hyperion §6✪✪✪✪✪"))
        set(DataComponents.LORE, ItemLore(listOf(
            "§7Gear Score: §d1002",
            "§7Damage: §c+270",
            "§7Strength: §c+150",
            "§7Intelligence: §a+350",
            "",
            "§7Deals §c+50% §7damage to",
            "§7Withers. Grants §c+1 §c❁ Damage",
            "§7and §a+2 §b✎ Intelligence",
            "§7per §cCatacombs §7level.",
            "",
            "§6Ability: Wither Impact  §e§lRIGHT CLICK",
            "§7Teleport §a10 blocks§7 ahead of you.",
            "§7Then implode dealing §c10,000",
            "§7damage to nearby enemies. Also",
            "§7applies the wither shield scroll",
            "§7ability reducing damage taken",
            "§7and granting an absorption",
            "§7shield for §e5 §7seconds.",
            "§8Mana Cost: §3300",
            "",
            "§7This item can be reforged!",
            "§d§lMYTHIC DUNGEON SWORD",
        ).map { Component.literal(it) }))
    }

    private class TooltipPreview(private val stack: ItemStack) : net.minecraft.client.gui.screens.Screen(Component.literal("preview")) {
        override fun extractRenderState(graphics: net.minecraft.client.gui.GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
            super.extractRenderState(graphics, mouseX, mouseY, a)
            graphics.setTooltipForNextFrame(font, stack, 8, 16)
        }
    }
    private fun tooltip(stack: ItemStack): List<String> {
        val mc = Minecraft.getInstance()
        return stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.Default.NORMAL).map { it.string }
    }

    private fun log(message: String) = ChatTranslator.LOGGER.info("[SELFTEST] {}", message)
}
