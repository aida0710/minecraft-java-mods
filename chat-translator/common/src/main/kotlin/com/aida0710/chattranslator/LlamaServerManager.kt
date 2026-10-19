package com.aida0710.chattranslator

import net.minecraft.network.chat.Component
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

enum class GpuBackend { VULKAN, CUDA, CUDA12, CPU }

/**
 * 内蔵バックエンド: llama.cpp の llama-server と GGUF モデルを (設定画面から) ダウンロードしてインストールし、
 * ワールドに入っている間だけローカルで動かす。
 */
class LlamaServerManager(
    private val baseDir: Path,
    private val releases: LlamaReleases = LlamaReleases(),
    private val notify: (message: Component, progress: Boolean) -> Unit,
) {
    enum class State { NOT_INSTALLED, INSTALLING, STOPPED, STARTING, READY, FAILED }

    @Volatile var state = State.STOPPED
        private set
    /** READY のときの OpenAI 互換エンドポイント */
    @Volatile var endpoint: String? = null
        private set
    /** インストール中の進捗 (設定画面に表示する) */
    @Volatile var progressText: Component = Component.empty()
        private set
    @Volatile var progressFraction: Float = 0f
        private set
    /** 直近のエラー (設定画面に表示する) */
    @Volatile var lastError: String? = null
        private set

    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    @Volatile private var restarts = 0
    private val starting = AtomicBoolean(false)
    private val installing = AtomicBoolean(false)
    private val pidFile = baseDir.resolve("server.pid")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build()

    private fun binDir(cfg: TranslatorConfig.Values) = baseDir.resolve("bin").resolve("${cfg.llamaBuild}-${cfg.gpuBackend.name.lowercase(Locale.ROOT)}")
    private fun modelFile(cfg: TranslatorConfig.Values) = baseDir.resolve("models").resolve(cfg.modelUrl.substringAfterLast('/').substringBefore('?'))

    fun isInstalled(cfg: TranslatorConfig.Values) = findServer(binDir(cfg)) != null && modelFile(cfg).exists()

    /** インストール済みファイルの合計サイズ (バイト) */
    fun installedBytes(): Long = if (!baseDir.exists()) 0L else Files.walk(baseDir).use { s ->
        s.filter { it.isRegularFile() }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
    }

    /** 状態を最新にする (設定変更でビルドやモデルが変わったとき) */
    fun refresh(cfg: TranslatorConfig.Values) {
        if (state == State.STOPPED || state == State.NOT_INSTALLED) state = if (isInstalled(cfg)) State.STOPPED else State.NOT_INSTALLED
    }

    /** llama.cpp とモデルをダウンロードする。完了後 startAfter なら起動する */
    fun installAsync(cfg: TranslatorConfig.Values, startAfter: Boolean) {
        if (!installing.compareAndSet(false, true)) return
        Thread({
            try {
                lastError = null
                state = State.INSTALLING
                ensureBinary(cfg)
                ensureModel(cfg)
                progressText = T.tr("llama.install_done")
                progressFraction = 1f
                state = State.STOPPED
                notify(T.tr("llama.install_done"), false)
                if (startAfter) startAsync(cfg)
            } catch (e: Exception) {
                state = if (isInstalled(cfg)) State.STOPPED else State.NOT_INSTALLED
                val reason = installFailureText(e)
                lastError = reason
                progressText = T.tr("llama.failed", reason)
                ChatTranslator.LOGGER.error("Failed to install built-in LLM", e)
                notify(T.tr("llama.install_failed", reason), false)
            } finally {
                installing.set(false)
            }
        }, "ChatTranslator-install").apply { isDaemon = true }.start()
    }

    /** ダウンロードしたファイルをすべて削除する */
    fun uninstall(cfg: TranslatorConfig.Values) {
        stop()
        if (baseDir.exists()) {
            Files.walk(baseDir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
        }
        progressText = Component.empty()
        progressFraction = 0f
        refresh(cfg)
    }

    fun startAsync(cfg: TranslatorConfig.Values) {
        if (state == State.READY || state == State.STARTING || state == State.INSTALLING) return
        if (!isInstalled(cfg)) {
            state = State.NOT_INSTALLED
            return
        }
        if (!starting.compareAndSet(false, true)) return
        Thread({
            try {
                lastError = null
                start(cfg)
            } catch (e: Exception) {
                state = State.FAILED
                lastError = e.message ?: e.toString()
                ChatTranslator.LOGGER.error("Failed to start built-in llama-server", e)
                notify(T.tr("llama.start_failed", e.message ?: e.toString()), false)
            } finally {
                starting.set(false)
            }
        }, "ChatTranslator-llama").apply { isDaemon = true }.start()
    }

    private fun start(cfg: TranslatorConfig.Values) {
        stopping = false
        killStale()
        val exe = findServer(binDir(cfg)) ?: throw IllegalStateException("llama-server が見つかりません")
        val model = modelFile(cfg)

        state = State.STARTING
        val port = if (cfg.builtinPort > 0) cfg.builtinPort else freePort()
        val cmd = mutableListOf(
            exe.toString(),
            "--model", model.toString(),
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--n-gpu-layers", if (cfg.gpuBackend == GpuBackend.CPU) "0" else cfg.builtinGpuLayers.toString(),
            "--ctx-size", cfg.builtinContextSize.toString(),
            "--parallel", "2",
            "--no-webui",
        )
        cmd += cfg.builtinExtraArgs.split(' ').filter { it.isNotBlank() }

        val log = baseDir.resolve("server.log")
        val pb = ProcessBuilder(cmd)
            .directory(exe.parent.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
        if (!isWindows) {
            val libVar = if (isMac) "DYLD_LIBRARY_PATH" else "LD_LIBRARY_PATH"
            pb.environment()[libVar] = listOfNotNull(exe.parent.toString(), pb.environment()[libVar]).joinToString(":")
        }
        ChatTranslator.LOGGER.info("Starting llama-server: {}", cmd.joinToString(" "))
        notify(T.tr("llama.starting"), false)
        val proc = pb.start()
        process = proc
        Files.writeString(pidFile, proc.pid().toString())
        startWatchdog(proc.pid())

        val url = "http://127.0.0.1:$port"
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) throw IllegalStateException("llama-server exited with code ${proc.exitValue()} (server.log を確認してください)")
            if (healthy(url)) {
                endpoint = "$url/v1"
                state = State.READY
                notify(T.tr("llama.ready", model.name), false)
                watch(proc, cfg)
                return
            }
            Thread.sleep(500)
        }
        proc.destroyForcibly()
        throw IllegalStateException("llama-server did not become ready in time")
    }

    /**
     * 予期せず落ちたら自動で再起動する。
     * Process.onExit() は JDK の小さなスタックの "process reaper" スレッドでコールバックを実行し、
     * Forge のクラスローダー経由のクラスロードで StackOverflowError になるため自前のスレッドで待つ。
     */
    private fun watch(proc: Process, cfg: TranslatorConfig.Values) {
        Thread({
            val code = proc.waitFor()
            if (stopping || process !== proc) return@Thread
            endpoint = null
            state = State.FAILED
            if (++restarts > MAX_RESTARTS) {
                notify(T.tr("llama.gave_up"), false)
                return@Thread
            }
            ChatTranslator.LOGGER.warn("llama-server exited unexpectedly (code {}); restarting", code)
            notify(T.tr("llama.restarting"), false)
            startAsync(cfg)
        }, "ChatTranslator-llama-watch").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopping = true
        endpoint = null
        state = State.STOPPED
        process?.let { p ->
            p.descendants().forEach { it.destroy() }
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        Files.deleteIfExists(pidFile)
    }

    /**
     * ゲームが強制終了されるとシャットダウンフックが走らず llama-server が残るため、
     * Minecraft のプロセス終了を待って llama-server を止める軽量な見張りプロセスを別に起動する。
     */
    private fun startWatchdog(serverPid: Long) {
        val selfPid = ProcessHandle.current().pid()
        val cmd = if (isWindows) listOf(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
            "Wait-Process -Id $selfPid -ErrorAction SilentlyContinue; Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue",
        ) else listOf(
            "sh", "-c", "while kill -0 $selfPid 2>/dev/null; do sleep 2; done; kill $serverPid 2>/dev/null",
        )
        runCatching {
            ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start()
        }.onFailure { ChatTranslator.LOGGER.warn("Failed to start llama-server watchdog", it) }
    }

    /** 前回クラッシュ等で残った llama-server を止める */
    private fun killStale() {
        if (!Files.exists(pidFile)) return
        runCatching {
            val pid = Files.readString(pidFile).trim().toLong()
            ProcessHandle.of(pid).ifPresent { h ->
                if (h.info().command().orElse("").contains("llama-server")) {
                    ChatTranslator.LOGGER.info("Stopping stale llama-server (pid {})", pid)
                    h.destroyForcibly()
                }
            }
        }
        Files.deleteIfExists(pidFile)
    }

    private fun healthy(url: String): Boolean = runCatching {
        val res = http.send(HttpRequest.newBuilder(URI.create("$url/health")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString())
        res.statusCode() == 200
    }.getOrDefault(false)

    internal fun ensureBinary(cfg: TranslatorConfig.Values): Path {
        val dir = binDir(cfg)
        findServer(dir)?.let { return it }

        val assets = LlamaReleases.assetNames(cfg.llamaBuild, cfg.gpuBackend)
        val sha256ByAssetName = releases.sha256ByAssetName(cfg.llamaBuild)
        for (asset in assets) {
            val archive = baseDir.resolve("downloads").resolve(asset)
            // 実行ファイルなので、SHA-256 が一致しない限り使わない
            val sha256 = sha256ByAssetName[asset] ?: throw IllegalStateException("$asset の SHA-256 を GitHub から取得できないため中止しました")
            notify(T.tr("llama.downloading", "llama.cpp ($asset)"), false)
            Downloader.download(releases.downloadUrl(cfg.llamaBuild, asset), archive, sha256) { done, total -> progress(asset, done, total) }
            Downloader.extract(archive, dir)
            Files.deleteIfExists(archive)
        }
        return findServer(dir) ?: throw IllegalStateException("llama-server not found in ${assets.first()}")
    }

    /** インストールが失敗した理由を、設定画面とチャットに出す文にする */
    private fun installFailureText(e: Exception): String = when (e) {
        is GitHubRateLimitedException -> T.s("llama.github_rate_limited", e.waitMinutes, LlamaReleases.DEFAULT_BUILD)
        else -> e.message ?: e.toString()
    }

    private fun findServer(dir: Path): Path? {
        if (!dir.exists()) return null
        val exeName = if (isWindows) "llama-server.exe" else "llama-server"
        return Files.walk(dir).use { s -> s.filter { it.name == exeName && it.isRegularFile() }.findFirst().orElse(null) }
            ?.also { if (!isWindows) it.toFile().setExecutable(true, false) }
    }

    private fun ensureModel(cfg: TranslatorConfig.Values): Path {
        val fileName = modelFile(cfg).name
        val target = baseDir.resolve("models").resolve(fileName)
        if (target.exists()) return target
        notify(T.tr("llama.downloading", fileName), false)
        Downloader.download(cfg.modelUrl, target, cfg.modelSha256.ifBlank { null }) { done, total -> progress(fileName, done, total) }
        return target
    }

    private var lastPercent = -1
    private fun progress(name: String, done: Long, total: Long) {
        if (total <= 0) {
            progressText = Component.literal("$name: ${done / 1_000_000} MB")
            notify(progressText, true)
            return
        }
        val percent = (done * 100 / total).toInt()
        progressFraction = done.toFloat() / total
        progressText = T.tr("download.progress", name, percent, done / 1_000_000, total / 1_000_000)
        if (percent == lastPercent) return
        lastPercent = percent
        notify(progressText, true)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val MAX_RESTARTS = 3
        val isWindows = HostPlatform.current.os == HostPlatform.Os.WINDOWS
        val isMac = HostPlatform.current.os == HostPlatform.Os.MAC
    }
}
