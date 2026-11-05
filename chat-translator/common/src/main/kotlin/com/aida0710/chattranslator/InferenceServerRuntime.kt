package com.aida0710.chattranslator

import net.minecraft.network.chat.Component
import java.time.Duration

/** インストールの進み具合を受け取る */
interface InstallProgress {
    fun downloading(name: String, done: Long, total: Long)

    /** ダウンロード以外の段階。割合が分からないので文で知らせる */
    fun step(message: Component)
}

/** 推論サーバー 1 種類ぶんの、インストールと起動の手順 */
interface InferenceServerRuntime {
    val host: CommandHost

    /** インストールの必要が無いときも含めて、[serverCommand] を呼べる状態か */
    fun isInstalled(cfg: TranslatorConfig.Values): Boolean

    fun install(cfg: TranslatorConfig.Values, progress: InstallProgress)

    /** port で待ち受けるサーバーのコマンド。Completions API の model には [SERVED_MODEL_NAME] を渡す */
    fun serverCommand(cfg: TranslatorConfig.Values, port: Int): CommandLine

    /** /health に応答して試し翻訳が通るまでの待ち時間の上限 */
    val startupTimeout: Duration

    companion object {
        /** mlx-lm のサーバーが --model のモデルに付ける名前。vLLM にも同じ名前を付け、llama.cpp は値を見ない */
        const val SERVED_MODEL_NAME = "default_model"
    }
}
