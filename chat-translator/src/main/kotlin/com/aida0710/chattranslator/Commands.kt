package com.aida0710.chattranslator

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component

/** クライアントコマンド /chattranslator (サーバーのコマンドと衝突しないよう短縮形は作らない) */
object Commands {
    private fun reply(src: CommandSourceStack, text: Component, color: ChatFormatting = ChatFormatting.AQUA) =
        src.sendSystemMessage(Component.literal("[ChatTranslator] ").withStyle(ChatFormatting.AQUA).append(text.copy().withStyle(color)))

    private val languages = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(Languages.ALL.map { it.code } + Languages.AUTO, builder)
    }

    private fun dictionaryIds(mod: ChatTranslator) = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(mod.dictionaries.packs.map { it.id }, builder)
    }

    private fun keepTerms(mod: ChatTranslator) = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(mod.dictionaries.userPack?.keep ?: emptyList(), builder)
    }

    private fun exportFiles(mod: ChatTranslator) = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(mod.backup.files().map { it.fileName.toString() }, builder)
    }

    private fun export(src: CommandSourceStack, mod: ChatTranslator, name: String?) {
        runCatching { mod.backup.export(Backup.Sections(), name) }
            .onSuccess { reply(src, T.tr("settings.backup.exported", it.fileName.toString())) }
            .onFailure { reply(src, T.tr("settings.backup.failed", it.message ?: it.javaClass.simpleName), ChatFormatting.RED) }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, mod: ChatTranslator) {
        fun ok(block: (CommandContext<CommandSourceStack>) -> Unit): com.mojang.brigadier.Command<CommandSourceStack> =
            com.mojang.brigadier.Command { ctx -> block(ctx); 1 }

        val root = literal("chattranslator")
            .executes(ok { SettingsScreen.openLater() })
            .then(literal("config").executes(ok { SettingsScreen.openLater() }))
            .then(literal("status").executes(ok { ctx ->
                val c = TranslatorConfig.current
                val d = mod.dictionaries
                reply(ctx.source, T.tr("cmd.status.engine", c.provider.label, mod.engineStatus(c)))
                reply(ctx.source, T.tr("cmd.status.lang", Languages.displayName(c.sourceCode), Languages.displayName(c.targetCode), T.onOff(c.chatEnabled), T.onOff(c.tooltipEnabled)))
                val active = d.packs.filter { d.isEnabled(it.id) }.map { d.displayName(it).string }
                reply(ctx.source, T.tr("cmd.status.cache", mod.cache.size(), active.joinToString(", ")))
            }))
            .then(literal("swap").then(argument("id", IntegerArgumentType.integer(0)).executes(ok { ctx ->
                // チャットの [原文] / [訳] ボタンから実行される
                if (!mod.chat.swap(IntegerArgumentType.getInteger(ctx, "id"))) reply(ctx.source, T.tr("cmd.swap.too_old"), ChatFormatting.GRAY)
            })))
            .then(literal("test").then(argument("text", StringArgumentType.greedyString()).executes(ok { ctx ->
                val text = StringArgumentType.getString(ctx, "text")
                val mc = Minecraft.getInstance()
                mod.translator.translateChat(
                    text,
                    onSuccess = { tr -> mc.execute { reply(ctx.source, Component.literal("$text → $tr")) } },
                    onFailure = { reason -> mc.execute { reply(ctx.source, T.tr("cmd.test.failed", reason), ChatFormatting.RED) } },
                )
            })))
            .then(literal("lang").then(argument("source", StringArgumentType.word()).suggests(languages)
                .then(argument("target", StringArgumentType.word()).suggests(languages).executes(ok { ctx ->
                    val source = StringArgumentType.getString(ctx, "source")
                    val target = StringArgumentType.getString(ctx, "target")
                    if (source != Languages.AUTO && Languages.get(source) == null || Languages.get(target) == null) {
                        reply(ctx.source, T.tr("cmd.lang.unknown"), ChatFormatting.RED)
                    } else {
                        TranslatorConfig.update(TranslatorConfig.sourceCode, source)
                        TranslatorConfig.update(TranslatorConfig.targetCode, target)
                        reply(ctx.source, T.tr("cmd.lang.set", Languages.displayName(source), Languages.displayName(target)))
                    }
                }))))
            .then(literal("keep")
                .then(literal("add").then(argument("term", StringArgumentType.greedyString()).executes(ok { ctx ->
                    val term = StringArgumentType.getString(ctx, "term").trim()
                    reply(ctx.source, T.tr(if (mod.dictionaries.addKeep(term)) "cmd.keep.added" else "cmd.keep.exists", term))
                })))
                .then(literal("remove").then(argument("term", StringArgumentType.greedyString()).suggests(keepTerms(mod)).executes(ok { ctx ->
                    val term = StringArgumentType.getString(ctx, "term").trim()
                    reply(ctx.source, T.tr(if (mod.dictionaries.removeKeep(term)) "cmd.removed" else "cmd.not_found", term))
                })))
                .then(literal("list").executes(ok { ctx ->
                    val list = mod.dictionaries.userPack?.keep ?: emptyList()
                    reply(ctx.source, if (list.isEmpty()) T.tr("cmd.keep.empty") else T.tr("cmd.keep.list", list.size, list.joinToString(", ")))
                })))
            .then(literal("term")
                .then(literal("add").then(argument("source", StringArgumentType.string()).then(argument("translation", StringArgumentType.greedyString()).executes(ok { ctx ->
                    val source = StringArgumentType.getString(ctx, "source")
                    val translation = StringArgumentType.getString(ctx, "translation")
                    mod.dictionaries.addTerm(source, translation)
                    reply(ctx.source, T.tr("cmd.term.added", source, translation))
                }))))
                .then(literal("remove").then(argument("source", StringArgumentType.greedyString()).executes(ok { ctx ->
                    val source = StringArgumentType.getString(ctx, "source")
                    reply(ctx.source, T.tr(if (mod.dictionaries.removeTerm(source)) "cmd.removed" else "cmd.not_found", source))
                }))))
            .then(literal("dict")
                .then(literal("list").executes(ok { ctx ->
                    val d = mod.dictionaries
                    d.packs.forEach { p ->
                        reply(ctx.source, Component.literal(if (d.isEnabled(p.id)) "■ " else "□ ").append("${p.id} — ").append(d.displayName(p))
                            .append(" (keep ${p.keep.size} / terms ${p.terms.size} / skip ${p.skip.size})"))
                    }
                }))
                .then(literal("on").then(argument("id", StringArgumentType.word()).suggests(dictionaryIds(mod)).executes(ok { ctx ->
                    val id = StringArgumentType.getString(ctx, "id")
                    mod.dictionaries.setEnabled(id, true)
                    reply(ctx.source, T.tr("cmd.dict.enabled", id))
                })))
                .then(literal("off").then(argument("id", StringArgumentType.word()).suggests(dictionaryIds(mod)).executes(ok { ctx ->
                    val id = StringArgumentType.getString(ctx, "id")
                    mod.dictionaries.setEnabled(id, false)
                    reply(ctx.source, T.tr("cmd.dict.disabled", id))
                })))
                .then(literal("reload").executes(ok { ctx ->
                    mod.dictionaries.load()
                    reply(ctx.source, T.tr("cmd.dict.reloaded", mod.dictionaries.packs.size))
                })))
            .then(literal("cache")
                .then(literal("clear").executes(ok { ctx ->
                    mod.translator.clearCache()
                    reply(ctx.source, T.tr("cmd.cache.cleared"))
                }))
                .then(literal("info").executes(ok { ctx ->
                    val c = TranslatorConfig.current
                    reply(ctx.source, T.tr("cmd.cache.info", mod.cache.size(), c.maxCacheEntries, mod.cache.fileSize() / 1024, T.onOff(c.cachePersist)))
                })))
            .then(literal("export")
                .executes(ok { ctx -> export(ctx.source, mod, null) })
                .then(argument("name", StringArgumentType.word()).executes(ok { ctx -> export(ctx.source, mod, StringArgumentType.getString(ctx, "name")) })))
            .then(literal("import").then(argument("file", StringArgumentType.greedyString()).suggests(exportFiles(mod)).executes(ok { ctx ->
                val name = StringArgumentType.getString(ctx, "file").trim()
                val path = mod.backup.resolve(name) ?: mod.backup.resolve("$name.json")
                if (path == null) {
                    reply(ctx.source, T.tr("cmd.import.not_found", name), ChatFormatting.RED)
                    return@ok
                }
                runCatching { mod.backup.import(path) }
                    .onSuccess { r -> reply(ctx.source, T.tr("settings.backup.imported", path.fileName.toString(), T.onOff(r.language), r.dictionaries, "%,d".format(r.cacheEntries))) }
                    .onFailure { reply(ctx.source, T.tr("settings.backup.failed", it.message ?: it.javaClass.simpleName), ChatFormatting.RED) }
            })))
            .then(literal("pull").executes(ok { ctx ->
                val c = TranslatorConfig.current
                when (c.provider) {
                    ProviderType.BUILTIN -> {
                        reply(ctx.source, T.tr("cmd.pull.restart"))
                        mod.llama.stop()
                        mod.llama.startAsync(c)
                    }
                    ProviderType.OPENAI_COMPATIBLE -> {
                        reply(ctx.source, T.tr("cmd.pull.ollama", c.openaiModel))
                        mod.ollama.checkAsync(c, forcePull = true)
                    }
                    else -> reply(ctx.source, T.tr("cmd.pull.unneeded"))
                }
            }))
        dispatcher.register(root)
    }
}
