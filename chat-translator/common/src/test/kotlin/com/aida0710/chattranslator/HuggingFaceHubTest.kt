package com.aida0710.chattranslator

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HuggingFaceHubTest {
    private val commit = "0123456789012345678901234567890123456789"
    private val distribution = SnapshotDistribution("org/model-MLX", commit, 100)
    private val weights = "weights".toByteArray()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 配布ファイルと一覧の API の代わり。ファイルごとの取得回数を数える */
    private class FakeHub(private val tree: String, private val files: Map<String, ByteArray>) : AutoCloseable {
        val downloads = ConcurrentHashMap<String, Int>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path
                val body = when {
                    path.startsWith("/api/models/") -> tree.toByteArray()
                    else -> {
                        val file = path.substringAfter("/resolve/").substringAfter('/')
                        downloads.merge(file, 1, Int::plus)
                        files[file]
                    }
                }
                if (body == null) {
                    ex.sendResponseHeaders(404, -1)
                } else {
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                ex.close()
            }
            start()
        }
        val hub = HuggingFaceHub("http://127.0.0.1:${server.address.port}")

        override fun close() = server.stop(0)
    }

    private fun tree(weightsSha256: String) = """[
        {"type":"file","path":"model.safetensors","size":${weights.size},"lfs":{"oid":"$weightsSha256"}},
        {"type":"file","path":"config.json","size":2},
        {"type":"file","path":"README.md","size":5},
        {"type":"file","path":"figures/plot.png","size":5},
        {"type":"directory","path":"figures","size":0}
    ]"""

    private val files = mapOf("model.safetensors" to weights, "config.json" to "{}".toByteArray(), "README.md" to "skip!".toByteArray())

    @Test
    fun snapshotDownloadsModelFilesAndSkipsDocumentation() = FakeHub(tree(sha256(weights)), files).use { fake ->
        val dir = Files.createTempDirectory("snapshot").resolve("model")
        fake.hub.downloadSnapshot(distribution, dir) { _, _ -> }
        assertEquals("{}", dir.resolve("config.json").readText())
        assertTrue(dir.resolve("model.safetensors").exists())
        assertFalse(dir.resolve("README.md").exists())
        assertTrue(fake.hub.isSnapshotComplete(dir))
    }

    @Test
    fun snapshotWithWrongSha256IsNotMarkedComplete() = FakeHub(tree("0".repeat(64)), files).use { fake ->
        val dir = Files.createTempDirectory("snapshot").resolve("model")
        val e = assertFailsWith<IllegalStateException> { fake.hub.downloadSnapshot(distribution, dir) { _, _ -> } }
        assertTrue(e.message!!.contains("SHA-256 mismatch"), e.message)
        assertFalse(dir.resolve("model.safetensors").exists())
        assertFalse(fake.hub.isSnapshotComplete(dir))
    }

    @Test
    fun interruptedSnapshotDoesNotDownloadFinishedFilesAgain() = FakeHub(tree(sha256(weights)), files).use { fake ->
        val dir = Files.createTempDirectory("snapshot").resolve("model")
        Files.createDirectories(dir)
        Files.write(dir.resolve("model.safetensors"), weights)
        fake.hub.downloadSnapshot(distribution, dir) { _, _ -> }
        assertEquals(null, fake.downloads["model.safetensors"])
        assertEquals(1, fake.downloads["config.json"])
    }

    @Test
    fun snapshotRejectsPathsOutsideItsDirectory() {
        val tree = """[{"type":"file","path":"../evil.py","size":1}]"""
        FakeHub(tree, mapOf("../evil.py" to "x".toByteArray())).use { fake ->
            val dir = Files.createTempDirectory("snapshot").resolve("model")
            assertFailsWith<IllegalStateException> { fake.hub.downloadSnapshot(distribution, dir) { _, _ -> } }
            assertFalse(dir.resolveSibling("evil.py").exists())
        }
    }

    @Test
    fun singleFileIsVerifiedWithPinnedSha256() = FakeHub("[]", mapOf("m.gguf" to weights)).use { fake ->
        val dir = Files.createTempDirectory("single")
        val good = SingleFileDistribution("org/model-GGUF", commit, "m.gguf", sha256(weights), weights.size.toLong())
        fake.hub.downloadFile(good, dir.resolve("m.gguf")) { _, _ -> }
        assertTrue(dir.resolve("m.gguf").exists())

        val bad = good.copy(sha256 = "0".repeat(64))
        assertFailsWith<IllegalStateException> { fake.hub.downloadFile(bad, dir.resolve("other.gguf")) { _, _ -> } }
        assertFalse(dir.resolve("other.gguf").exists())
    }
}
