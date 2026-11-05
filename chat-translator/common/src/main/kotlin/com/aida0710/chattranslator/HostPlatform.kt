package com.aida0710.chattranslator

import java.util.Locale

/** 実行環境の OS と CPU。llama.cpp の配布ファイルや実行ファイル名を選ぶのに使う */
data class HostPlatform(val os: Os, val arm: Boolean) {
    enum class Os { WINDOWS, MAC, LINUX }

    companion object {
        val current: HostPlatform = run {
            val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
            val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
            val os = when {
                osName.contains("win") -> Os.WINDOWS
                osName.contains("mac") -> Os.MAC
                else -> Os.LINUX
            }
            HostPlatform(
                os = os,
                arm = arch.contains("aarch64") || arch.contains("arm") || (os == Os.MAC && isAppleSilicon()),
            )
        }

        /**
         * x64 版の Java を Rosetta で動かしていても os.arch は x86_64 になる。
         * その場合も arm64 の llama-server は単体で動かせるので、Metal が使えない x64 版を選ばないよう CPU そのものを調べる。
         */
        private fun isAppleSilicon(): Boolean = runCatching {
            val process = ProcessBuilder("sysctl", "-n", "hw.optional.arm64").redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText().trim() == "1" }
        }.getOrDefault(false)
    }
}
