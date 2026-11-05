package com.aida0710.chattranslator

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 本物の推論サーバーとモデルをダウンロードして、インストール → 起動 → 翻訳 → 停止を通す。
 * 重いので、環境変数 CHATTRANSLATOR_SMOKE_MODEL を付けたときだけ動く (.github/workflows/builtin-llm-smoke.yml)。
 *   CHATTRANSLATOR_SMOKE_MODEL  [BuiltinModel] の名前 (PLAMO_0_8B など)
 *   CHATTRANSLATOR_SMOKE_FORMAT [ModelFormat] の名前 (既定は GGUF)
 *   CHATTRANSLATOR_SMOKE_GPU    [GpuBackend] の名前 (既定はこの OS の既定)
 *   CHATTRANSLATOR_SMOKE_DIR    インストール先 (既定は一時フォルダ)
 */
class BuiltinRuntimeSmokeTest {
    @Test
    fun installsStartsTranslatesAndStops() {
        val modelName = System.getenv("CHATTRANSLATOR_SMOKE_MODEL")
        assumeTrue(modelName != null, "CHATTRANSLATOR_SMOKE_MODEL is not set")
        val baseDir = System.getenv("CHATTRANSLATOR_SMOKE_DIR")?.let { Path.of(it) } ?: Files.createTempDirectory("builtin smoke")
        val cfg = TranslatorConfig.current.copy(
            builtinModel = BuiltinModel.valueOf(modelName),
            builtinFormat = System.getenv("CHATTRANSLATOR_SMOKE_FORMAT")?.let { ModelFormat.valueOf(it) } ?: ModelFormat.GGUF,
            gpuBackend = System.getenv("CHATTRANSLATOR_SMOKE_GPU")?.let { GpuBackend.valueOf(it) } ?: GpuBackend.defaultFor(HostPlatform.current),
            builtinExtraArgs = System.getenv("CHATTRANSLATOR_SMOKE_EXTRA_ARGS").orEmpty(),
            sourceCode = "en",
            targetCode = "ja",
            timeoutSeconds = 600,
        )
        val manager = BuiltinServerManager(
            baseDir,
            mapOf(
                InferenceServer.LLAMA_CPP to LlamaCppRuntime(baseDir),
                InferenceServer.MLX_LM to MlxRuntime(baseDir),
                InferenceServer.VLLM to VllmRuntime(baseDir),
            ),
        ) { message, progress -> if (!progress) println("[notice] ${message.string}") }

        val installStarted = System.currentTimeMillis()
        manager.installAsync(cfg, startAfter = false)
        waitWhile(minutes = 40) { manager.state == BuiltinServerManager.State.INSTALLING || !manager.isInstalled(cfg) && manager.lastError == null }
        assertEquals(null, manager.lastError, "install failed")
        assertTrue(manager.isInstalled(cfg))
        println("[smoke] installed in ${(System.currentTimeMillis() - installStarted) / 1000} s")

        val startStarted = System.currentTimeMillis()
        manager.startAsync(cfg)
        waitWhile(minutes = 15) { manager.state != BuiltinServerManager.State.READY && manager.state != BuiltinServerManager.State.FAILED }
        val log = baseDir.resolve("server.log")
        if (manager.state != BuiltinServerManager.State.READY && Files.exists(log)) println(Files.readString(log).takeLast(4000))
        assertEquals(BuiltinServerManager.State.READY, manager.state, manager.lastError)
        println("[smoke] ready in ${(System.currentTimeMillis() - startStarted) / 1000} s")

        try {
            val provider = BuiltinProvider({ manager.endpoint }, { true })
            val translationStarted = System.currentTimeMillis()
            val japanese = provider.translate("The meeting has been rescheduled to next Monday.", cfg)
            println("[smoke] en>ja ${System.currentTimeMillis() - translationStarted} ms: $japanese")
            assertTrue(japanese.any { Languages.inScript(it, Script.JAPANESE) || Languages.inScript(it, Script.HAN) }, japanese)

            val english = provider.translate("ヒーラーで参加します！", cfg.copy(sourceCode = "ja", targetCode = "en"))
            println("[smoke] ja>en: $english")
            assertTrue(english.contains("healer", ignoreCase = true), english)
        } finally {
            manager.stop()
        }
        assertNotNull(manager)
        assertEquals(null, manager.endpoint)
    }

    private fun waitWhile(minutes: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + minutes * 60_000
        while (condition() && System.currentTimeMillis() < deadline) Thread.sleep(500)
    }
}
