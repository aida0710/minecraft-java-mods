package com.aida0710.chattranslator

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/** Python で動く推論サーバーの共通部分: Python 環境とモデルのフォルダをそろえる */
abstract class PythonServerRuntime(
    protected val baseDir: Path,
    private val hub: HuggingFaceHub,
) : InferenceServerRuntime {
    protected abstract val environment: PythonEnvironment

    protected fun modelDirectory(cfg: TranslatorConfig.Values): Path =
        baseDir.resolve("models").resolve((cfg.builtinDistribution as SnapshotDistribution).directoryName)

    override fun isInstalled(cfg: TranslatorConfig.Values) = environment.isInstalled() && hub.isSnapshotComplete(modelDirectory(cfg))

    override fun install(cfg: TranslatorConfig.Values, progress: InstallProgress) {
        checkPrerequisites()
        environment.install(progress)
        val directory = modelDirectory(cfg)
        if (!hub.isSnapshotComplete(directory)) {
            val distribution = cfg.builtinDistribution as SnapshotDistribution
            hub.downloadSnapshot(distribution, directory) { done, total -> progress.downloading(distribution.directoryName, done, total) }
        }
        prepareModelDirectory(cfg, directory)
    }

    /** ダウンロードしたフォルダに、推論サーバーが必要とするものを足す */
    protected open fun prepareModelDirectory(cfg: TranslatorConfig.Values, directory: Path) = Unit

    /** 何 GB もダウンロードしてから動かないと分かるのを避けるため、先に確かめる */
    protected open fun checkPrerequisites() = Unit
}

/** Apple silicon の mlx-lm。Metal で動く */
class MlxRuntime(baseDir: Path, hub: HuggingFaceHub = HuggingFaceHub()) : PythonServerRuntime(baseDir, hub) {
    override val host: CommandHost = LocalCommandHost()

    override val environment = PythonEnvironment(host, baseDir, "mlx", UvRelease.APPLE_SILICON, "/assets/chattranslator/python/mlx-requirements.txt")

    /** サーバーは起動してからモデルを読むので、読み込みの時間も試し翻訳の待ちに入る */
    override val startupTimeout: Duration = Duration.ofSeconds(300)

    override fun serverCommand(cfg: TranslatorConfig.Values, port: Int): CommandLine {
        val directory = host.pathOf(modelDirectory(cfg))
        val network = listOf("--host", "127.0.0.1", "--port", port.toString())
        val python = environment.executable("python")
        return when (cfg.builtinModel.family) {
            // PLaMo はモデルのコードを読むので、リポジトリ同梱のサーバーをそのフォルダで動かす
            ModelFamily.PLAMO_TRANSLATE -> CommandLine(listOf(python, "serve.py", "--model", ".", "--trust-remote-code") + network, workingDirectory = directory)
            ModelFamily.TRANSLATE_GEMMA -> CommandLine(listOf(python, "-m", "mlx_lm", "server", "--model", directory) + network)
        }
    }
}

/**
 * NVIDIA GPU の vLLM。FP8 と NVFP4 はこのサーバーでしか動かない。
 * Windows では vLLM が直接動かないので WSL2 の中で動かす。
 */
class VllmRuntime(
    baseDir: Path,
    override val host: CommandHost = defaultHost(),
    hub: HuggingFaceHub = HuggingFaceHub(),
) : PythonServerRuntime(baseDir, hub) {
    override val environment = PythonEnvironment(host, baseDir, "vllm", UvRelease.LINUX_X64, "/assets/chattranslator/python/vllm-requirements.txt")

    /** PyTorch の読み込みと GPU 用のコードの生成に数分かかる。初回は WSL2 の起動も入る */
    override val startupTimeout: Duration = Duration.ofSeconds(600)

    override fun checkPrerequisites() {
        readTotalVramBytes()
    }

    override fun prepareModelDirectory(cfg: TranslatorConfig.Values, directory: Path) {
        if (cfg.builtinModel.family == ModelFamily.TRANSLATE_GEMMA) addMissingGemmaProcessorConfigs(directory)
    }

    override fun serverCommand(cfg: TranslatorConfig.Values, port: Int): CommandLine {
        val distribution = cfg.builtinDistribution
        val utilization = gpuMemoryUtilization(distribution.bytes, readTotalVramBytes())
        val arguments = buildList {
            addAll(listOf(environment.executable("vllm"), "serve", host.pathOf(modelDirectory(cfg))))
            addAll(listOf("--host", "127.0.0.1", "--port", port.toString()))
            addAll(listOf("--served-model-name", InferenceServerRuntime.SERVED_MODEL_NAME))
            addAll(listOf("--max-model-len", cfg.builtinContextSize.toString(), "--max-num-seqs", "2"))
            addAll(listOf("--gpu-memory-utilization", "%.3f".format(Locale.ROOT, utilization)))
            // PLaMo のトークナイザーはリポジトリ同梱のコード。コミットを固定して取得している
            if (cfg.builtinModel.family == ModelFamily.PLAMO_TRANSLATE) add("--trust-remote-code")
        }
        return CommandLine(arguments)
    }

    private fun readTotalVramBytes(): Long {
        val query = CommandLine(listOf("nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits"))
        val output = runCatching {
            val process = host.processBuilder(query).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor(NVIDIA_SMI_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) { text }
            text
        }.getOrElse { throw IllegalStateException(T.s("builtin.vllm_needs_nvidia", it.message ?: it.toString())) }
        val mebibytes = output.lineSequence().firstOrNull()?.trim()?.toLongOrNull()
            ?: throw IllegalStateException(T.s("builtin.vllm_needs_nvidia", output.trim()))
        return mebibytes * BYTES_PER_MEBIBYTE
    }

    companion object {
        private const val NVIDIA_SMI_TIMEOUT_SECONDS = 30L
        private const val BYTES_PER_MEBIBYTE = 1024L * 1024L

        /**
         * vLLM の既定 (GPU の 92%) はゲームと同居できず、起動時に空きが足りないと止まるので、モデルが動くだけの割合を渡す。
         * 重みに足す 2GiB は、文脈 4096 トークン × 2 本の KV キャッシュと PyTorch の作業領域の分。RTX 3080 Ti と PLaMo 0.8B で確かめた値。
         */
        private const val VLLM_WORKING_MEMORY_BYTES = 2L * 1024 * BYTES_PER_MEBIBYTE

        /** 全部を割り当てると vLLM が起動できない。vLLM の既定に近い値を上限にする */
        private const val MAX_GPU_MEMORY_UTILIZATION = 0.9

        /**
         * TranslateGemma の FP8・NVFP4 の変換版には画像の前処理の設定が入っていないが、vLLM は起動時に読もうとして止まる。
         * 文章しか訳さないので中身は使われない。google/translategemma-4b-it の設定を jar に入れて補う。
         */
        internal fun addMissingGemmaProcessorConfigs(directory: Path) {
            for (name in listOf("preprocessor_config.json", "processor_config.json")) {
                val target = directory.resolve(name)
                if (target.exists()) continue
                val stream = VllmRuntime::class.java.getResourceAsStream("/assets/chattranslator/python/gemma3/$name") ?: error("missing $name")
                stream.use { Files.copy(it, target) }
            }
        }

        internal fun gpuMemoryUtilization(modelBytes: Long, totalVramBytes: Long): Double =
            ((modelBytes + VLLM_WORKING_MEMORY_BYTES).toDouble() / totalVramBytes).coerceAtMost(MAX_GPU_MEMORY_UTILIZATION)

        private fun defaultHost(): CommandHost =
            if (HostPlatform.current.os == HostPlatform.Os.WINDOWS) WslCommandHost() else LocalCommandHost()
    }
}
