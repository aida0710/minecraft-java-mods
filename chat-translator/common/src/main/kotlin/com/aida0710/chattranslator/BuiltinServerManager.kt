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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * 内蔵バックエンド: 推論サーバーとモデルを (設定画面から) ダウンロードしてインストールし、
 * ワールドに入っている間だけローカルで動かす。どのサーバーを使うかはモデルの形式で決まる ([ModelFormat])。
 */
class BuiltinServerManager(
    private val baseDir: Path,
    private val runtimes: Map<InferenceServer, InferenceServerRuntime>,
    private val notify: (message: Component, progress: Boolean) -> Unit,
) : InstallProgress {
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
    /** インストール済みファイルの合計サイズ (バイト)。設定画面が毎 tick 読むので、計算済みの値を持つ */
    @Volatile var installedBytes: Long = 0L
        private set

    @Volatile private var process: Process? = null
    @Volatile private var processHost: CommandHost? = null
    @Volatile private var stopping = false
    @Volatile private var restarts = 0
    private val starting = AtomicBoolean(false)
    private val installing = AtomicBoolean(false)
    private val pidFile = baseDir.resolve("server.pid")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build()

    private fun runtime(cfg: TranslatorConfig.Values) = runtimes.getValue(cfg.builtinFormat.server)

    fun isInstalled(cfg: TranslatorConfig.Values) = runtime(cfg).isInstalled(cfg)

    /** 状態を最新にする (設定変更でモデルや形式が変わったとき) */
    fun refresh(cfg: TranslatorConfig.Values) {
        if (state == State.STOPPED || state == State.NOT_INSTALLED) state = if (isInstalled(cfg)) State.STOPPED else State.NOT_INSTALLED
        measureInstalledBytesAsync()
    }

    /** Python 環境は数万ファイルあり、数えるのに時間がかかる */
    private fun measureInstalledBytesAsync() {
        Thread({
            installedBytes = if (!baseDir.exists()) 0L else Files.walk(baseDir).use { s ->
                s.filter { it.isRegularFile() }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
            }
        }, "ChatTranslator-measure").apply { isDaemon = true }.start()
    }

    /** 推論サーバーとモデルをダウンロードする。完了後 startAfter なら起動する */
    fun installAsync(cfg: TranslatorConfig.Values, startAfter: Boolean) {
        if (!installing.compareAndSet(false, true)) return
        Thread({
            try {
                lastError = null
                state = State.INSTALLING
                runtime(cfg).install(cfg, this)
                progressText = T.tr("builtin.install_done")
                progressFraction = 1f
                state = State.STOPPED
                notify(T.tr("builtin.install_done"), false)
                if (startAfter) startAsync(cfg)
            } catch (e: Exception) {
                state = if (isInstalled(cfg)) State.STOPPED else State.NOT_INSTALLED
                val reason = installFailureText(e)
                lastError = reason
                progressText = T.tr("builtin.failed", reason)
                ChatTranslator.LOGGER.error("Failed to install built-in LLM", e)
                notify(T.tr("builtin.install_failed", reason), false)
            } finally {
                installing.set(false)
                measureInstalledBytesAsync()
            }
        }, "ChatTranslator-install").apply { isDaemon = true }.start()
    }

    /** ダウンロードしたファイルをすべて削除する */
    fun uninstall(cfg: TranslatorConfig.Values) {
        stop()
        runtimes.values.map { it.host }.distinct().forEach { it.removeEnvironment(baseDir) }
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
                ChatTranslator.LOGGER.error("Failed to start built-in inference server", e)
                notify(T.tr("builtin.start_failed", e.message ?: e.toString()), false)
            } finally {
                starting.set(false)
            }
        }, "ChatTranslator-builtin").apply { isDaemon = true }.start()
    }

    private fun start(cfg: TranslatorConfig.Values) {
        stopping = false
        val runtime = runtime(cfg)
        runtime.host.stopLeftoverServer(baseDir, pidFile)
        Files.deleteIfExists(pidFile)

        state = State.STARTING
        val port = if (cfg.builtinPort > 0) cfg.builtinPort else freePort()
        val serverCommand = runtime.serverCommand(cfg, port)
        val command = serverCommand.copy(arguments = serverCommand.arguments + cfg.builtinExtraArgs.split(' ').filter { it.isNotBlank() })

        Files.createDirectories(baseDir)
        val log = baseDir.resolve("server.log")
        ChatTranslator.LOGGER.info("Starting inference server: {}", command.arguments.joinToString(" "))
        notify(T.tr("builtin.starting"), false)
        val proc = runtime.host.startServer(command, log)
        process = proc
        processHost = runtime.host
        Files.writeString(pidFile, proc.pid().toString())
        startWatchdog(runtime.host, proc.pid())

        val url = "http://127.0.0.1:$port"
        val deadline = System.currentTimeMillis() + runtime.startupTimeout.toMillis()
        val serverName = cfg.builtinFormat.server.displayName
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) throw IllegalStateException("$serverName exited with code ${proc.exitValue()}: ${firstErrorLine(log) ?: "server.log"}")
            if (healthy(url)) {
                warmUp(url, cfg, deadline)
                endpoint = "$url/v1"
                state = State.READY
                notify(T.tr("builtin.ready", cfg.builtinDistribution.cacheName), false)
                watch(proc, cfg)
                return
            }
            Thread.sleep(HEALTH_POLL_MILLIS)
        }
        runtime.host.stopServer(proc)
        throw IllegalStateException("$serverName did not become ready in time")
    }

    /**
     * 実際に 1 文訳して、最初の翻訳が待ち時間切れにならないようにする。
     * mlx-lm は /health に応答してからモデルを読み始めるので、ここで読み込みの完了も待つ。
     */
    private fun warmUp(url: String, cfg: TranslatorConfig.Values, deadlineMillis: Long) {
        val remainingSeconds = maxOf(1L, (deadlineMillis - System.currentTimeMillis()) / 1000).toInt()
        val warmUpConfig = cfg.copy(sourceCode = "en", targetCode = "ja", timeoutSeconds = remainingSeconds)
        BuiltinProvider({ "$url/v1" }, { true }).translate("Hello.", warmUpConfig)
    }

    /**
     * サーバーのログの最初のエラー。vLLM の失敗の理由 (GPU が対応していない、メモリが足りないなど) はここにしか出ない。
     * 最後の行は「上を見てください」としか書いていないので使わない。"(EngineCore pid=1) ERROR ... [core.py:1] " は取り除く
     */
    private fun firstErrorLine(log: Path): String? = runCatching {
        Files.readAllLines(log).firstOrNull { line -> ERROR_LINE_MARKERS.any { line.contains(it) } }
            ?.substringAfter("] ")?.trim()?.take(MAX_ERROR_TEXT_LENGTH)
    }.getOrNull()

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
                notify(T.tr("builtin.gave_up"), false)
                return@Thread
            }
            ChatTranslator.LOGGER.warn("Inference server exited unexpectedly (code {}); restarting", code)
            notify(T.tr("builtin.restarting"), false)
            startAsync(cfg)
        }, "ChatTranslator-builtin-watch").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopping = true
        endpoint = null
        state = State.STOPPED
        process?.let { p -> processHost?.stopServer(p) }
        process = null
        Files.deleteIfExists(pidFile)
    }

    /**
     * ゲームが強制終了されるとシャットダウンフックが走らず推論サーバーが残るため、
     * Minecraft のプロセス終了を待って推論サーバーを止める軽量な見張りプロセスを別に起動する。
     */
    private fun startWatchdog(host: CommandHost, serverPid: Long) {
        val cmd = host.watchdogCommand(ProcessHandle.current().pid(), serverPid)
        runCatching {
            ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start()
        }.onFailure { ChatTranslator.LOGGER.warn("Failed to start inference server watchdog", it) }
    }

    private fun healthy(url: String): Boolean = runCatching {
        val res = http.send(HttpRequest.newBuilder(URI.create("$url/health")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString())
        res.statusCode() == 200
    }.getOrDefault(false)

    /** インストールが失敗した理由を、設定画面とチャットに出す文にする */
    private fun installFailureText(e: Exception): String = when (e) {
        is GitHubRateLimitedException -> T.s("builtin.github_rate_limited", e.waitMinutes, LlamaReleases.DEFAULT_BUILD)
        else -> e.message ?: e.toString()
    }

    private var lastPercent = -1
    override fun downloading(name: String, done: Long, total: Long) {
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

    override fun step(message: Component) {
        progressText = message
        notify(message, true)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val MAX_RESTARTS = 3
        const val HEALTH_POLL_MILLIS = 500L
        val ERROR_LINE_MARKERS = listOf("Error:", "error:")
        const val MAX_ERROR_TEXT_LENGTH = 300
    }
}
