package com.aida0710.chattranslator

import java.nio.file.Path

/**
 * macOS の隔離の印 (com.apple.quarantine) を外す。
 * 隔離の対象になっているランチャーから動かすと、ダウンロードしたファイルや子プロセスが作ったファイルに印が付くことがあり、
 * llama-server や uv は公証されていないので、印があると Gatekeeper に起動を止められるおそれがある。
 * ダウンロードしたものは mod か uv が SHA-256 で照合しているので、印を外してよい。
 */
object MacQuarantine {
    private const val XATTR = "/usr/bin/xattr"
    private const val ATTRIBUTE = "com.apple.quarantine"

    /** 印を外せなくてもインストールは止めない。印が付いていなければ起動できるので、失敗はログに残すだけにする */
    fun removeFrom(directory: Path, host: HostPlatform = HostPlatform.current): Boolean {
        if (host.os != HostPlatform.Os.MAC) return true
        return runCatching {
            val xattr = ProcessBuilder(XATTR, "-dr", ATTRIBUTE, directory.toString()).redirectErrorStream(true).start()
            val output = xattr.inputStream.bufferedReader().use { it.readText().trim() }
            val exitCode = xattr.waitFor()
            if (exitCode != 0) ChatTranslator.LOGGER.warn("xattr exited with code {} for {}: {}", exitCode, directory, output)
            exitCode == 0
        }.getOrElse {
            ChatTranslator.LOGGER.warn("Failed to run xattr for {}", directory, it)
            false
        }
    }
}
