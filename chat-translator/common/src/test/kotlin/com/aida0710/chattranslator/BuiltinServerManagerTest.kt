package com.aida0710.chattranslator

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuiltinServerManagerTest {
    init {
        T.setUiLanguage("en_us")
    }

    private val notices = mutableListOf<String>()

    /** どの推論サーバーの代わりにもなる。command がプロセスになり、/health と Completions はテストのサーバーが答える */
    private class FakeRuntime(private val command: List<String>, private val timeout: Duration = Duration.ofSeconds(20)) : InferenceServerRuntime {
        override val host = LocalCommandHost()
        override fun isInstalled(cfg: TranslatorConfig.Values) = true
        override fun install(cfg: TranslatorConfig.Values, progress: InstallProgress) = Unit
        override fun serverCommand(cfg: TranslatorConfig.Values, port: Int) = CommandLine(command)
        override val startupTimeout = timeout
    }

    private fun manager(baseDir: Path, runtime: InferenceServerRuntime) =
        BuiltinServerManager(baseDir, InferenceServer.entries.associateWith { runtime }) { message, _ -> notices += message.string }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun serverBecomesReadyOnlyAfterWarmUpTranslationSucceeds() {
        assumeTrue(HostPlatform.current.os != HostPlatform.Os.WINDOWS)
        val completions = AtomicInteger()
        val port = freePort()
        val fakeServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
            createContext("/health") { ex -> ex.sendResponseHeaders(200, -1); ex.close() }
            createContext("/v1/completions") { ex ->
                completions.incrementAndGet()
                val body = """{"choices":[{"text":"こんにちは"}]}""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val manager = manager(Files.createTempDirectory("builtin"), FakeRuntime(listOf("sleep", "60")))
            manager.startAsync(TranslatorConfig.current.copy(builtinPort = port))
            waitFor { manager.state == BuiltinServerManager.State.READY }
            assertEquals(BuiltinServerManager.State.READY, manager.state)
            assertEquals(1, completions.get())
            assertEquals("http://127.0.0.1:$port/v1", manager.endpoint)
            manager.stop()
            assertEquals(null, manager.endpoint)
        } finally {
            fakeServer.stop(0)
        }
    }

    @Test
    fun serverThatExitsShowsLastErrorFromItsLog() {
        assumeTrue(HostPlatform.current.os != HostPlatform.Os.WINDOWS)
        val script = "echo 'INFO loading'; echo 'ValueError: type fp8e4nv not supported in this architecture'; exit 1"
        val manager = manager(Files.createTempDirectory("builtin"), FakeRuntime(listOf("sh", "-c", script)))
        manager.startAsync(TranslatorConfig.current.copy(builtinPort = freePort()))
        waitFor { manager.state == BuiltinServerManager.State.FAILED }
        assertEquals(BuiltinServerManager.State.FAILED, manager.state)
        assertTrue(manager.lastError!!.contains("fp8e4nv"), manager.lastError)
        assertTrue(notices.any { it.contains("fp8e4nv") }, notices.toString())
        assertFalse(manager.endpoint != null)
    }

    @Test
    fun uninstallRemovesEverythingUnderBaseDir() {
        val baseDir = Files.createTempDirectory("builtin")
        val model = baseDir.resolve("models/m/model.safetensors")
        Files.createDirectories(model.parent)
        Files.write(model, ByteArray(10))
        val manager = manager(baseDir, FakeRuntime(listOf("true")))
        manager.uninstall(TranslatorConfig.current)
        assertFalse(Files.exists(baseDir))
    }
}
