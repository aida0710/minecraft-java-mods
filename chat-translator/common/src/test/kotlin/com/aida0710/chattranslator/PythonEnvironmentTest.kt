package com.aida0710.chattranslator

import com.sun.net.httpserver.HttpServer
import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PythonEnvironmentTest {
    init {
        T.setUiLanguage("en_us")
    }

    private val steps = mutableListOf<String>()
    private val progress = object : InstallProgress {
        override fun downloading(name: String, done: Long, total: Long) = Unit
        override fun step(message: Component) { steps += message.string }
    }

    private val releaseName = "uv-fake.tar.gz"

    /** 受け取った引数と環境変数を uv-calls.txt に書くだけの uv */
    private fun fakeUvArchive(callLog: Path, exitCode: Int = 0): ByteArray = TestArchives.tarGz(
        mapOf(
            "uv-fake/uv" to """
                #!/bin/sh
                echo "${'$'}* | ${'$'}UV_CACHE_DIR" >> "$callLog"
                echo "Installed 3 packages"
                exit $exitCode
            """.trimIndent().toByteArray(),
        ),
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <R> withUvServer(archive: ByteArray, block: (url: String) -> R): R {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/$releaseName") { ex ->
                ex.sendResponseHeaders(200, archive.size.toLong())
                ex.responseBody.use { it.write(archive) }
            }
            start()
        }
        return try { block("http://127.0.0.1:${server.address.port}/$releaseName") } finally { server.stop(0) }
    }

    private fun environment(baseDir: Path, archive: ByteArray, url: String) = PythonEnvironment(
        LocalCommandHost(), baseDir, "mlx", UvRelease(releaseName, sha256(archive)),
        "/assets/chattranslator/python/mlx-requirements.txt", uvDownloadUrl = url,
    )

    @Test
    fun installCreatesVenvFromPinnedRequirementsAndMarksInstalled() {
        assumeTrue(HostPlatform.current.os != HostPlatform.Os.WINDOWS)
        val baseDir = Files.createTempDirectory("python")
        val callLog = baseDir.resolve("uv-calls.txt")
        val archive = fakeUvArchive(callLog)
        withUvServer(archive) { url ->
            val environment = environment(baseDir, archive, url)
            assertFalse(environment.isInstalled())
            environment.install(progress)
            assertTrue(environment.isInstalled())
        }
        val calls = callLog.readLines()
        assertEquals(3, calls.size, calls.toString())
        assertTrue(calls[0].startsWith("venv --python 3.12"), calls[0])
        assertTrue(calls[1].contains("pip install") && calls[1].contains("--require-hashes"), calls[1])
        assertTrue(calls[2].startsWith("cache clean"), calls[2])
        // ダウンロードの控えは Python 環境の置き場所に入れ、「削除」でまとめて消せるようにする
        assertTrue(calls.all { it.endsWith(baseDir.resolve("python/mlx/uv-cache").toString()) }, calls.toString())
        assertTrue(steps.any { it.contains("Installed 3 packages") }, steps.toString())
        assertTrue(baseDir.resolve("python/mlx-requirements.txt").exists())
    }

    @Test
    fun failedInstallShowsUvOutputAndIsNotMarkedInstalled() {
        assumeTrue(HostPlatform.current.os != HostPlatform.Os.WINDOWS)
        val baseDir = Files.createTempDirectory("python")
        val archive = fakeUvArchive(baseDir.resolve("uv-calls.txt"), exitCode = 3)
        withUvServer(archive) { url ->
            val environment = environment(baseDir, archive, url)
            val e = assertFailsWith<IllegalStateException> { environment.install(progress) }
            assertTrue(e.message!!.contains("Installed 3 packages"), e.message)
            assertFalse(environment.isInstalled())
        }
    }

    @Test
    fun requirementsFilesPinEveryPackageWithHashes() {
        for (name in listOf("mlx-requirements.txt", "vllm-requirements.txt")) {
            val text = javaClass.getResourceAsStream("/assets/chattranslator/python/$name")!!.reader().use { it.readText() }
            val packages = text.lines().filter { it.isNotBlank() && !it.startsWith(" ") && !it.startsWith("#") }
            assertTrue(packages.isNotEmpty(), name)
            for (line in packages) assertTrue(line.contains("==") && line.endsWith("\\"), "$name: $line")
        }
    }
}
