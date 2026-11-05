package com.aida0710.chattranslator

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandHostTest {
    private val isWindows = HostPlatform.current.os == HostPlatform.Os.WINDOWS

    @Test
    fun wslRunsCommandInLinuxWithWorkingDirectoryAndEnvironment() {
        val command = CommandLine(listOf("uv", "venv"), workingDirectory = "/mnt/c/a b", environment = mapOf("UV_CACHE_DIR" to "/home/me/cache"))
        assertEquals(
            listOf("wsl.exe", "--cd", "/mnt/c/a b", "--exec", "env", "UV_CACHE_DIR=/home/me/cache", "uv", "venv"),
            WslCommandHost().processBuilder(command).command(),
        )
    }

    @Test
    fun wslWatchdogStopsServerInLinuxAfterGameExits() {
        val script = WslCommandHost().watchdogCommand(gamePid = 12, serverPid = 34).last()
        assertTrue(script.contains("Wait-Process -Id 12"), script)
        assertTrue(script.contains("wsl.exe --exec sh -c"), script)
        assertTrue(script.contains("kill"), script)
    }

    @Test
    fun localHostStartsAndStopsServerWithItsChildren() {
        assumeTrue(!isWindows)
        val host = LocalCommandHost()
        val log = Files.createTempFile("server", ".log")
        val server = host.startServer(CommandLine(listOf("sh", "-c", "echo started; sleep 60 & wait")), log)
        val deadline = System.currentTimeMillis() + 5_000
        while (Files.size(log) == 0L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val child = server.descendants().findFirst()
        host.stopServer(server)
        assertFalse(server.isAlive)
        // 子プロセスへの停止の合図は非同期なので、終わるのを待つ
        child.ifPresent { it.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertEquals("started", Files.readString(log).trim())
    }

    @Test
    fun leftoverServerIsStoppedOnlyWhenItRunsFromBaseDir() {
        assumeTrue(!isWindows)
        val baseDir = Files.createTempDirectory("base")
        val pidFile = baseDir.resolve("server.pid")
        // baseDir の外の実行ファイルで動くプロセスは、pid が使い回されたものとして止めない
        val unrelated = ProcessBuilder("sleep", "60").start()
        try {
            Files.writeString(pidFile, unrelated.pid().toString())
            LocalCommandHost().stopLeftoverServer(baseDir, pidFile)
            assertTrue(unrelated.isAlive)
        } finally {
            unrelated.destroyForcibly()
        }

        val copiedSleep = baseDir.resolve("sleep")
        Files.copy(java.nio.file.Path.of("/bin/sleep"), copiedSleep)
        copiedSleep.toFile().setExecutable(true)
        val leftover = ProcessBuilder(copiedSleep.toString(), "60").start()
        Files.writeString(pidFile, leftover.pid().toString())
        LocalCommandHost().stopLeftoverServer(baseDir, pidFile)
        leftover.waitFor()
        assertFalse(leftover.isAlive)
    }
}
