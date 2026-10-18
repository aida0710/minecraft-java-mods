package com.aida0710.chattranslator

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
 * 内蔵バックエンド: llama.cpp の llama-server と GGUF モデルを自動でダウンロードし、
 * Minecraft の起動中だけローカルで動かす。
 */
class LlamaServerManager(
    private val baseDir: Path,
    private val notify: (message: String, progress: Boolean) -> Unit,
) {
    enum class State { STOPPED, DOWNLOADING, STARTING, READY, FAILED }

    @Volatile var state = State.STOPPED
        private set
    /** READY のときの OpenAI 互換エンドポイント */
    @Volatile var endpoint: String? = null
        private set

    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    @Volatile private var restarts = 0
    private val starting = AtomicBoolean(false)
    private val pidFile = baseDir.resolve("server.pid")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build()

    fun startAsync(cfg: TranslatorConfig.Values) {
        if (!starting.compareAndSet(false, true)) return
        Thread({
            try {
                start(cfg)
            } catch (e: Exception) {
                state = State.FAILED
                ChatTranslator.LOGGER.error("Failed to start built-in llama-server", e)
                notify("内蔵 LLM サーバーの起動に失敗しました: ${e.message} (詳細は logs/latest.log と config/chattranslator/llama/server.log)", false)
            } finally {
                starting.set(false)
            }
        }, "ChatTranslator-llama").apply { isDaemon = true }.start()
    }

    private fun start(cfg: TranslatorConfig.Values) {
        stopping = false
        killStale()
        state = State.DOWNLOADING
        val exe = ensureBinary(cfg)
        val model = ensureModel(cfg)

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
        notify("内蔵 LLM サーバーを起動しています…", false)
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
                notify("内蔵 LLM サーバーの準備ができました (${model.name})", false)
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
                notify("内蔵 LLM サーバーが繰り返し停止したため再起動を中止しました (server.log を確認してください)", false)
                return@Thread
            }
            ChatTranslator.LOGGER.warn("llama-server exited unexpectedly (code {}); restarting", code)
            notify("内蔵 LLM サーバーが停止したため再起動します", false)
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

    private fun ensureBinary(cfg: TranslatorConfig.Values): Path {
        val dir = baseDir.resolve("bin").resolve("${cfg.llamaBuild}-${cfg.gpuBackend.name.lowercase(Locale.ROOT)}")
        findServer(dir)?.let { return it }

        val assets = assetNames(cfg.llamaBuild, cfg.gpuBackend)
        val digests = releaseDigests(cfg.llamaBuild)
        for (asset in assets) {
            val archive = baseDir.resolve("downloads").resolve(asset)
            val url = "https://github.com/ggml-org/llama.cpp/releases/download/${cfg.llamaBuild}/$asset"
            // 実行ファイルなので、GitHub が公開している SHA-256 と一致しない限り使わない
            val sha256 = digests[asset] ?: throw IllegalStateException("$asset の SHA-256 を GitHub から取得できないため中止しました")
            notify("llama.cpp ($asset) をダウンロードしています…", false)
            Downloader.download(url, archive, sha256) { done, total -> progress(asset, done, total) }
            Downloader.extract(archive, dir)
            Files.deleteIfExists(archive)
        }
        return findServer(dir) ?: throw IllegalStateException("llama-server not found in ${assets.first()}")
    }

    /** GitHub Releases API からアセット名 → SHA-256 を取得する */
    private fun releaseDigests(build: String): Map<String, String> {
        val res = http.send(
            HttpRequest.newBuilder(URI.create("https://api.github.com/repos/ggml-org/llama.cpp/releases/tags/$build"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ChatTranslator-Minecraft-Mod")
                .timeout(Duration.ofSeconds(20)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) { "GitHub API HTTP ${res.statusCode()}" }
        return com.google.gson.JsonParser.parseString(res.body()).asJsonObject.getAsJsonArray("assets").mapNotNull { a ->
            val o = a.asJsonObject
            val digest = o["digest"]?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            if (!digest.startsWith("sha256:")) return@mapNotNull null
            o["name"].asString to digest.removePrefix("sha256:")
        }.toMap()
    }

    private fun findServer(dir: Path): Path? {
        if (!dir.exists()) return null
        val exeName = if (isWindows) "llama-server.exe" else "llama-server"
        return Files.walk(dir).use { s -> s.filter { it.name == exeName && it.isRegularFile() }.findFirst().orElse(null) }
            ?.also { if (!isWindows) it.toFile().setExecutable(true, false) }
    }

    private fun ensureModel(cfg: TranslatorConfig.Values): Path {
        val fileName = cfg.modelUrl.substringAfterLast('/').substringBefore('?')
        val target = baseDir.resolve("models").resolve(fileName)
        if (target.exists()) return target
        notify("翻訳モデル $fileName をダウンロードしています… (初回のみ。数 GB あります)", false)
        Downloader.download(cfg.modelUrl, target, cfg.modelSha256.ifBlank { null }) { done, total -> progress(fileName, done, total) }
        return target
    }

    private var lastPercent = -1
    private fun progress(name: String, done: Long, total: Long) {
        if (total <= 0) {
            notify("$name: ${done / 1_000_000} MB", true)
            return
        }
        val percent = (done * 100 / total).toInt()
        if (percent == lastPercent) return
        lastPercent = percent
        notify("$name をダウンロード中: $percent% (${done / 1_000_000} / ${total / 1_000_000} MB)", true)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val MAX_RESTARTS = 3
        private val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        private val arm = System.getProperty("os.arch").lowercase(Locale.ROOT).let { it.contains("aarch64") || it.contains("arm") }
        val isWindows = os.contains("win")
        val isMac = os.contains("mac")

        /** 実行環境に合う llama.cpp リリースのアセット名 (CUDA は cudart も必要) */
        fun assetNames(build: String, gpu: GpuBackend): List<String> = when {
            isWindows -> {
                val a = if (arm) "arm64" else "x64"
                when (gpu) {
                    GpuBackend.VULKAN -> listOf("llama-$build-bin-win-vulkan-$a.zip")
                    GpuBackend.CPU -> listOf("llama-$build-bin-win-cpu-$a.zip")
                    GpuBackend.CUDA -> listOf("llama-$build-bin-win-cuda-13.4-$a.zip", "cudart-llama-bin-win-cuda-13.4-$a.zip")
                    GpuBackend.CUDA12 -> listOf("llama-$build-bin-win-cuda-12.4-x64.zip", "cudart-llama-bin-win-cuda-12.4-x64.zip")
                }
            }
            isMac -> listOf("llama-$build-bin-macos-${if (arm) "arm64" else "x64"}.tar.gz") // Metal 対応済み
            else -> {
                val a = if (arm) "arm64" else "x64"
                when (gpu) {
                    GpuBackend.VULKAN -> listOf("llama-$build-bin-ubuntu-vulkan-$a.tar.gz")
                    GpuBackend.CPU -> listOf("llama-$build-bin-ubuntu-$a.tar.gz")
                    GpuBackend.CUDA -> listOf("llama-$build-bin-ubuntu-cuda-13.4-$a.tar.gz", "cudart-llama-$build-bin-ubuntu-cuda-13.4-$a.tar.gz")
                    GpuBackend.CUDA12 -> listOf("llama-$build-bin-ubuntu-cuda-12.8-x64.tar.gz", "cudart-llama-$build-bin-ubuntu-cuda-12.8-x64.tar.gz")
                }
            }
        }
    }
}
