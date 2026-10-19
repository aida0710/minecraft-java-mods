package com.aida0710.chattranslator.fabric

import com.aida0710.chattranslator.SettingsScreen
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/** Mod Menu の mod 一覧の「設定」ボタンから設定画面を開く。Mod Menu が無ければ読み込まれない */
class ModMenuEntry : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<SettingsScreen> = ConfigScreenFactory { parent -> SettingsScreen(parent) }
}
