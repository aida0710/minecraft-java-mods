package com.aida0710.chattranslator

import java.util.Locale

/**
 * 実行環境の OS と CPU。llama.cpp の配布ファイルや実行ファイル名を選ぶのに使う
 * @param macOsMajorVersion macOS のメジャーバージョン (14 など)。macOS 以外と、読み取れなかったときは 0
 */
data class HostPlatform(val os: Os, val arm: Boolean, val macOsMajorVersion: Int = 0) {
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
                macOsMajorVersion = if (os == Os.MAC) majorVersionOf(System.getProperty("os.version")) else 0,
            )
        }

        internal fun majorVersionOf(osVersion: String): Int = osVersion.substringBefore('.').toIntOrNull() ?: 0

        /**
         * x64 版の Java を Rosetta で動かしていても os.arch は x86_64 になる。
         * その場合も arm64 の llama-server は単体で動かせるので、Metal が使えない x64 版を選ばないよう CPU そのものを調べる。
         */
        private fun isAppleSilicon(): Boolean = runCatching {
            val process = ProcessBuilder("/usr/sbin/sysctl", "-n", "hw.optional.arm64").redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText().trim() == "1" }
        }.getOrDefault(false)
    }
}
