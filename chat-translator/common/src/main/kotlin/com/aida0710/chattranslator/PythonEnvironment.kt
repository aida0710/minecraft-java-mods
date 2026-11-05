package com.aida0710.chattranslator

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.name

/** uv の配布ファイル。Python と依存パッケージはすべて uv に用意させる */
data class UvRelease(val assetName: String, val sha256: String) {
    fun downloadUrl() = "https://github.com/astral-sh/uv/releases/download/$VERSION/$assetName"

    /** アーカイブの中で uv の実行ファイルがあるフォルダ */
    val directoryName get() = assetName.removeSuffix(".tar.gz")

    companion object {
        /** 値は `gh api repos/astral-sh/uv/releases/tags/<版>` の digest を写したもの */
        const val VERSION = "0.13.7"

        val APPLE_SILICON = UvRelease("uv-aarch64-apple-darwin.tar.gz", "81b811b57e4624df7ed3aac148e0359100d9962f3d9dce00aec533dc2e086731")
        val LINUX_X64 = UvRelease("uv-x86_64-unknown-linux-gnu.tar.gz", "c5221ff41b7892bfc223543883600125f6a47fe845093e0f5bdff4f359196d83")
    }
}

/**
 * 推論サーバー用の Python 環境を作る。mod の利用者に Python の導入を求めないよう、単体で動く uv を取得して作らせる。
 * パッケージは jar に同梱した一覧 (版と SHA-256 が固定) から入れる:
 * モデルのコードと合わせて動作を確かめた組み合わせだけを使うためと、実行するコードの改ざんを防ぐため。
 * 一覧の作り方は chat-translator/README.md の「Python の依存パッケージ」を参照。
 */
class PythonEnvironment(
    private val host: CommandHost,
    private val baseDir: Path,
    private val name: String,
    private val uvRelease: UvRelease,
    private val requirementsResource: String,
    private val uvDownloadUrl: String = uvRelease.downloadUrl(),
) {
    /** ゲームの側に置く印。実行先が WSL2 でも、設定画面を描くたびに wsl.exe を呼ばずに済むように */
    private val installedMarker = baseDir.resolve("python").resolve("$name.installed")
    private val installLog = baseDir.resolve("python").resolve("$name-install.log")

    fun isInstalled(): Boolean = installedMarker.exists()

    /** venv の中の実行ファイルの、実行先から見たパス */
    fun executable(executableName: String): String = "${environmentDirectory()}/venv/bin/$executableName"

    private fun environmentDirectory() = "${host.environmentRoot(baseDir)}/$name"

    fun install(progress: InstallProgress) {
        if (isInstalled()) return
        val uv = host.pathOf(ensureUv(progress))
        val directory = environmentDirectory()
        val uvEnvironment = mapOf(
            // Python と一時ファイルも環境の置き場所に入れ、「削除」でまとめて消せるようにする
            "UV_PYTHON_INSTALL_DIR" to "$directory/interpreters",
            "UV_CACHE_DIR" to "$directory/uv-cache",
        )
        progress.step(T.tr("builtin.python_preparing"))
        runUv(CommandLine(listOf(uv, "venv", "--python", PYTHON_VERSION, "--python-preference", "only-managed", "--clear", "$directory/venv"), environment = uvEnvironment), progress)
        val requirements = copyRequirementsToDisk()
        runUv(
            CommandLine(listOf(uv, "pip", "install", "--python", "$directory/venv/bin/python", "--require-hashes", "-r", host.pathOf(requirements)), environment = uvEnvironment),
            progress,
        )
        // パッケージの本体は venv に入ったので、ダウンロードの控えは要らない
        runUv(CommandLine(listOf(uv, "cache", "clean"), environment = uvEnvironment), progress)
        // uv が作った Python と共有ライブラリにも印が付くことがある
        MacQuarantine.removeFrom(baseDir.resolve("python"))
        Files.writeString(installedMarker, requirementsResource)
    }

    private fun ensureUv(progress: InstallProgress): Path {
        val directory = baseDir.resolve("python").resolve("uv-${UvRelease.VERSION}")
        val executable = directory.resolve(uvRelease.directoryName).resolve("uv")
        if (executable.exists()) return executable
        val archive = baseDir.resolve("downloads").resolve(uvRelease.assetName)
        Downloader.download(uvDownloadUrl, archive, uvRelease.sha256) { done, total -> progress.downloading(uvRelease.assetName, done, total) }
        Downloader.extract(archive, directory)
        Files.deleteIfExists(archive)
        MacQuarantine.removeFrom(directory)
        check(executable.exists()) { "uv not found in ${uvRelease.assetName}" }
        return executable
    }

    private fun copyRequirementsToDisk(): Path {
        val target = baseDir.resolve("python").resolve(requirementsResource.substringAfterLast('/'))
        val stream = javaClass.getResourceAsStream(requirementsResource) ?: error("missing $requirementsResource")
        stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        return target
    }

    /** uv を実行し、出力の最後の行を進み具合として見せる。全部の出力はログに残す */
    private fun runUv(command: CommandLine, progress: InstallProgress) {
        Files.createDirectories(installLog.parent)
        val uv = host.processBuilder(command).redirectErrorStream(true).start()
        val lines = ArrayDeque<String>()
        Files.newBufferedWriter(installLog, Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { log ->
            uv.inputStream.bufferedReader().forEachLine { line ->
                log.appendLine(line)
                log.flush()
                if (line.isNotBlank()) {
                    progress.step(T.tr("builtin.python_step", line.trim()))
                    lines.addLast(line)
                    if (lines.size > ERROR_CONTEXT_LINES) lines.removeFirst()
                }
            }
        }
        val exitCode = uv.waitFor()
        check(exitCode == 0) { "uv ${command.arguments.getOrNull(1)} exited with code $exitCode (${installLog.name}): ${lines.joinToString(" / ")}" }
    }

    private companion object {
        /** mlx-lm と vLLM の両方が対応する版。uv が単体で動く Python を取得する */
        const val PYTHON_VERSION = "3.12"

        /** 失敗の理由として画面に出す行数。uv は最後の数行に原因を書く */
        const val ERROR_CONTEXT_LINES = 3
    }
}
