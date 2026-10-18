package com.aida0710.chattranslator

import com.mojang.blaze3d.platform.InputConstants
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
            "tooltip" -> {
                val stack = ItemStack(Items.DIAMOND_SWORD).apply {
                    set(DataComponents.CUSTOM_NAME, Component.literal("§6Aspect of the Dragons"))
                    set(DataComponents.LORE, ItemLore(listOf(
                        "§7Damage: §c+225",
                        "§6Ability: Dragon Rage §e§lRIGHT CLICK",
                        "§7All monsters in front of you take §a12,000 §7damage.",
                        "§8This item can be reforged!",
                        "§6§lLEGENDARY SWORD",
                    ).map { Component.literal(it) }))
                }
                tooltip(stack).forEachIndexed { i, l -> log("tooltip-before[$i] $l") }
                tooltipWatch = stack to 200
            }
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
            "openchat" ->mc.gui.setScreen(ChatScreen("", false))
            "close" -> mc.gui.setScreen(null)
            "screenshot" -> Screenshot.grab(mc.gameDirectory, args.getOrElse(1) { "selftest" } + ".png", mc.gameRenderer.mainRenderTarget(), 1) { log("screenshot: ${it.string}") }
            "holdkey" -> log("J held=${InputConstants.isKeyDown(InputConstants.KEY_J)}")
            "quit" -> mc.stop()
            else -> log("unknown command")
        }
    }

    private fun tooltip(stack: ItemStack): List<String> {
        val mc = Minecraft.getInstance()
        return stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.Default.NORMAL).map { it.string }
    }

    private fun log(message: String) = ChatTranslator.LOGGER.info("[SELFTEST] {}", message)
}
