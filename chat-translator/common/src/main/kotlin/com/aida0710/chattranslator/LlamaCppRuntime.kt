package com.aida0710.chattranslator

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** llama.cpp の llama-server で GGUF モデルを動かす */
class LlamaCppRuntime(
    private val baseDir: Path,
    private val releases: LlamaReleases = LlamaReleases(),
    private val hub: HuggingFaceHub = HuggingFaceHub(),
    private val platform: HostPlatform = HostPlatform.current,
) : InferenceServerRuntime {
    override val host: CommandHost = LocalCommandHost(platform)

    /** モデルの読み込みだけなので、遅いディスクでも 3 分あれば終わる */
    override val startupTimeout: Duration = Duration.ofSeconds(180)

    private val isWindows = platform.os == HostPlatform.Os.WINDOWS

    private fun binDir(cfg: TranslatorConfig.Values) =
        baseDir.resolve("bin").resolve("${cfg.llamaBuild}-${cfg.gpuBackend.name.lowercase(Locale.ROOT)}")

    private fun modelFile(cfg: TranslatorConfig.Values) =
        baseDir.resolve("models").resolve((cfg.builtinDistribution as SingleFileDistribution).fileName)

    override fun isInstalled(cfg: TranslatorConfig.Values) = findServer(binDir(cfg)) != null && modelFile(cfg).exists()

    override fun install(cfg: TranslatorConfig.Values, progress: InstallProgress) {
        ensureBinary(cfg, progress)
        val distribution = cfg.builtinDistribution as SingleFileDistribution
        val model = modelFile(cfg)
        if (model.exists()) return
        hub.downloadFile(distribution, model) { done, total -> progress.downloading(model.name, done, total) }
    }

    override fun serverCommand(cfg: TranslatorConfig.Values, port: Int): CommandLine {
        val exe = findServer(binDir(cfg)) ?: throw IllegalStateException("llama-server が見つかりません")
        val libraryPathVariable = when (platform.os) {
            HostPlatform.Os.WINDOWS -> null // Windows は実行ファイルのフォルダから DLL を読む
            HostPlatform.Os.MAC -> "DYLD_LIBRARY_PATH"
            HostPlatform.Os.LINUX -> "LD_LIBRARY_PATH"
        }
        return CommandLine(
            arguments = listOf(
                exe.toString(),
                "--model", modelFile(cfg).toString(),
                "--host", "127.0.0.1",
                "--port", port.toString(),
                "--n-gpu-layers", if (cfg.gpuBackend == GpuBackend.CPU) "0" else cfg.builtinGpuLayers.toString(),
                "--ctx-size", cfg.builtinContextSize.toString(),
                "--parallel", "2",
                "--no-webui",
            ),
            workingDirectory = exe.parent.toString(),
            environment = libraryPathVariable?.let { name ->
                mapOf(name to listOfNotNull(exe.parent.toString(), System.getenv(name)).joinToString(":"))
            }.orEmpty(),
        )
    }

    internal fun ensureBinary(cfg: TranslatorConfig.Values, progress: InstallProgress): Path {
        val dir = binDir(cfg)
        findServer(dir)?.let { return it }

        val assets = LlamaReleases.assetNames(cfg.llamaBuild, cfg.gpuBackend, platform)
        val sha256ByAssetName = releases.sha256ByAssetName(cfg.llamaBuild)
        for (asset in assets) {
            val archive = baseDir.resolve("downloads").resolve(asset)
            // 実行ファイルなので、SHA-256 が一致しない限り使わない
            val sha256 = sha256ByAssetName[asset] ?: throw IllegalStateException("$asset の SHA-256 を GitHub から取得できないため中止しました")
            Downloader.download(releases.downloadUrl(cfg.llamaBuild, asset), archive, sha256) { done, total -> progress.downloading(asset, done, total) }
            Downloader.extract(archive, dir)
            Files.deleteIfExists(archive)
        }
        MacQuarantine.removeFrom(dir, platform)
        return findServer(dir) ?: throw IllegalStateException("llama-server not found in ${assets.first()}")
    }

    private fun findServer(dir: Path): Path? {
        if (!dir.exists()) return null
        val exeName = if (isWindows) "llama-server.exe" else "llama-server"
        return Files.walk(dir).use { s -> s.filter { it.name == exeName && it.isRegularFile() }.findFirst().orElse(null) }
            ?.also { if (!isWindows) it.toFile().setExecutable(true, false) }
    }
}
