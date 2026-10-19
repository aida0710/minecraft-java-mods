package com.aida0710.chattranslator

import com.electronwill.nightconfig.core.file.CommentedFileConfig
import com.electronwill.nightconfig.toml.TomlFormat
import java.nio.file.Files
import java.nio.file.Path

/**
 * [Setting] の値を TOML ファイルに読み書きする。
 * Forge の ForgeConfigSpec が書く設定ファイルと同じ形なので、Forge 版で保存した設定もそのまま読める。
 */
class SettingsFile(path: Path, private val settings: List<Setting<*>>) {
    // 拡張子からの形式判定は TomlFormat が読み込まれていないと失敗するため、形式を明示する
    private val config: CommentedFileConfig = CommentedFileConfig.builder(path, TomlFormat.instance())
        .preserveInsertionOrder()
        .sync()
        .build()

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
    }

    /** ファイルを読み、欠けている項目・不正な値は既定値で、コメントは最新の説明で書き直す */
    fun load() {
        config.load()
        var changed = false
        for (setting in settings) {
            changed = repair(setting) || changed
        }
        if (changed) config.save()
    }

    private fun <T : Any> repair(setting: Setting<T>): Boolean {
        var changed = false
        if (setting.parse(config.get<Any?>(setting.path)) == null) {
            config.set<Any>(setting.path, setting.serialize(setting.default))
            changed = true
        }
        if (config.getComment(setting.path) != setting.comment) {
            config.setComment(setting.path, setting.comment)
            changed = true
        }
        return changed
    }

    operator fun <T : Any> get(setting: Setting<T>): T = setting.parse(config.get<Any?>(setting.path)) ?: setting.default

    /** 値を変えて即保存する */
    operator fun <T : Any> set(setting: Setting<T>, value: T) {
        config.set<Any>(setting.path, setting.serialize(value))
        config.save()
    }
}
