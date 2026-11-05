package com.aida0710.chattranslator

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 本物の WSL で [WslCommandHost] を動かす。Windows の自動テストで、環境変数 CHATTRANSLATOR_WSL_TEST を付けたときだけ動く。
 * GPU はいらない。空白を含むパス・環境変数・サーバーの起動と停止が Windows と Linux の境目を越えて届くかを見る。
 */
class WslCommandHostIntegrationTest {
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
        assertEquals("a b", output(CommandLine(listOf("sh", "-c", "printf %s \"\$UV_TEST\""), environment = mapOf("UV_TEST" to "a b"))))
    }

    @Test
    fun environmentRootIsInLinuxHome() {
        assumeWsl()
        val root = host.environmentRoot(Files.createTempDirectory("base"))
        assertTrue(root.endsWith("/.local/share/chattranslator") && root.startsWith("/"), root)
        assertFalse(root.startsWith("/mnt/"), root)
    }

    @Test
    fun serverStartedInLinuxAnswersOnWindowsLocalhostAndStops() {
        assumeWsl()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val log = Files.createTempFile("server", ".log")
        val server = host.startServer(CommandLine(listOf("python3", "-m", "http.server", port.toString(), "--bind", "127.0.0.1")), log)
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/")).timeout(Duration.ofSeconds(2)).GET().build()
        var status = -1
        val deadline = System.currentTimeMillis() + 60_000
        while (status != 200 && System.currentTimeMillis() < deadline) {
            status = runCatching { http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() }.getOrDefault(-1)
            if (status != 200) Thread.sleep(500)
        }
        assertEquals(200, status, Files.readString(log))

        host.stopServer(server)
        assertFalse(server.isAlive)
        val stillRunning = output(CommandLine(listOf("sh", "-c", "pgrep -f 'http.server $port' | grep -v \"^\$\$\$\" | wc -l")))
        assertEquals("0", stillRunning, "the server must not keep running in Linux")
    }
}
