package com.aida0710.chattranslator

import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 本物の WSL で [WslCommandHost] を動かす。Windows の自動テストで、環境変数 CHATTRANSLATOR_WSL_TEST を付けたときだけ動く。
 * GPU はいらない。空白を含むパス・環境変数・サーバーの起動と停止が Windows と Linux の境目を越えて届くかを見る。
 */
class WslCommandHostIntegrationTest {
    init {
        T.setUiLanguage("en_us")
    }

    private val host = WslCommandHost()

    private fun assumeWsl() = assumeTrue(System.getenv("CHATTRANSLATOR_WSL_TEST") != null, "CHATTRANSLATOR_WSL_TEST is not set")

    private fun output(command: CommandLine): String {
        val process = host.processBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText().trim() }
        assertEquals(0, process.waitFor(), text)
        return text
    }

    @Test
    fun windowsPathWithSpacesIsReachableFromLinux() {
        assumeWsl()
        val directory = Files.createTempDirectory("chat translator test")
        Files.writeString(directory.resolve("model file.txt"), "weights")
        val linuxPath = host.pathOf(directory.resolve("model file.txt"))
        assertTrue(linuxPath.startsWith("/mnt/"), linuxPath)
        assertEquals("weights", output(CommandLine(listOf("cat", linuxPath))))
        assertEquals(host.pathOf(directory), output(CommandLine(listOf("pwd"), workingDirectory = host.pathOf(directory))))
    }

    @Test
    fun environmentReachesLinuxCommand() {
        assumeWsl()
        assertEquals("a b", output(CommandLine(listOf("printenv", "UV_TEST"), environment = mapOf("UV_TEST" to "a b"))))
    }

    @Test
    fun environmentRootIsInLinuxHome() {
        assumeWsl()
        val root = host.environmentRoot(Files.createTempDirectory("base"))
        assertTrue(root.endsWith("/.local/share/chattranslator") && root.startsWith("/"), root)
        assertFalse(root.startsWith("/mnt/"), root)
    }

    @Test
    fun pythonEnvironmentIsBuiltInLinuxHomeAndRemoved() {
        assumeWsl()
        val baseDir = Files.createTempDirectory("chat translator env")
        val environment = PythonEnvironment(host, baseDir, "wsl-test", UvRelease.LINUX_X64, "/python/test-requirements.txt")
        val ignoredProgress = object : InstallProgress {
            override fun downloading(name: String, done: Long, total: Long) = Unit
            override fun step(message: Component) = Unit
        }
        environment.install(ignoredProgress)
        assertTrue(environment.isInstalled())

        val python = environment.executable("python")
        assertTrue(python.startsWith(host.environmentRoot(baseDir)), python)
        assertEquals("1.17.0", output(CommandLine(listOf(python, "-c", "import six; print(six.__version__)"))))

        host.removeEnvironment(baseDir)
        assertEquals("gone", output(CommandLine(listOf("sh", "-c", "test -e $python && echo there || echo gone"))))
    }

    @Test
    fun vllmExplainsWhatIsNeededWhenThereIsNoNvidiaGpu() {
        assumeWsl()
        val runtime = VllmRuntime(Files.createTempDirectory("chat translator vllm"), host)
        val cfg = TranslatorConfig.current.copy(builtinModel = BuiltinModel.PLAMO_0_8B, builtinFormat = ModelFormat.NVFP4)
        val failure = assertFailsWith<IllegalStateException> { runtime.install(cfg, object : InstallProgress {
            override fun downloading(name: String, done: Long, total: Long) = Unit
            override fun step(message: Component) = Unit
        }) }
        assertTrue(failure.message!!.contains("wsl --install"), failure.message)
    }

    @Test
    fun watchdogStopsServerInLinuxAfterGameExits() {
        assumeWsl()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val server = host.startServer(CommandLine(listOf("python3", "-m", "http.server", port.toString(), "--bind", "127.0.0.1")), Files.createTempFile("server", ".log"))
        val isServing = {
            output(CommandLine(listOf("sh", "-c", "python3 -c 'import socket; print(socket.socket().connect_ex((chr(49)+chr(50)+chr(55)+chr(46)+chr(48)+chr(46)+chr(48)+chr(46)+chr(49), $port)))'"))) == "0"
        }
        waitUntil(isServing)
        assertTrue(isServing(), "the server must be running")

        // ゲームの代わりに、すぐ終わる Windows のプロセスの終了を待たせる
        val game = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 3").start()
        ProcessBuilder(host.watchdogCommand(game.pid(), server.pid())).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        waitUntil { !server.isAlive }
        assertFalse(server.isAlive, "the watchdog must stop the server")
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(500)
    }

    @Test
    fun serverStartedInLinuxWithSpacesInPathAnswersOnWindowsLocalhostAndStops() {
        assumeWsl()
        val directory = Files.createTempDirectory("chat translator's model")
        Files.writeString(directory.resolve("config.json"), "{}")
        val port = java.net.ServerSocket(0).use { it.localPort }
        val log = Files.createTempFile("server", ".log")
        val server = host.startServer(
            CommandLine(listOf("python3", "-m", "http.server", port.toString(), "--bind", "127.0.0.1", "--directory", host.pathOf(directory))),
            log,
        )
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build()
        // フォルダの中のファイルが返るなら、空白と単一引用符を含むパスがそのまま届いている
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/config.json")).timeout(Duration.ofSeconds(2)).GET().build()
        var status = -1
        val deadline = System.currentTimeMillis() + 60_000
        while (status != 200 && System.currentTimeMillis() < deadline) {
            status = runCatching { http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() }.getOrDefault(-1)
            if (status != 200) Thread.sleep(500)
        }
        assertEquals(200, status, Files.readString(log))

        host.stopServer(server)
        assertFalse(server.isAlive)
        assertTrue(runCatching { http.send(request, HttpResponse.BodyHandlers.discarding()) }.isFailure, "the server must not keep running in Linux")
    }
}
