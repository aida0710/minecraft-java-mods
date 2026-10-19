package com.aida0710.chattranslator

import java.util.Locale

/** 実行環境の OS と CPU。llama.cpp の配布ファイルや実行ファイル名を選ぶのに使う */
data class HostPlatform(val os: Os, val arm: Boolean) {
    enum class Os { WINDOWS, MAC, LINUX }

    companion object {
        val current: HostPlatform = run {
            val os = System.getProperty("os.name").lowercase(Locale.ROOT)
            val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
            HostPlatform(
                os = when {
                    os.contains("win") -> Os.WINDOWS
                    os.contains("mac") -> Os.MAC
                    else -> Os.LINUX
                },
                arm = arch.contains("aarch64") || arch.contains("arm"),
            )
        }
    }
}
