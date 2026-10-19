package com.aida0710.chattranslator

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlamaReleasesTest {
    private val serverExeName = if (LlamaServerManager.isWindows) "llama-server.exe" else "llama-server"

    /** この環境の CPU 版の配布ファイルに見せかけた、中身の違うアーカイブ */
    private fun fakeArchive(build: String): Pair<String, ByteArray> {
        val asset = LlamaReleases.assetNames(build, GpuBackend.CPU).single()
        return asset to TestArchives.forAssetName(asset, mapOf("build/bin/$serverExeName" to "fake".toByteArray()))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 配布ファイルのダウンロード (/download/...) と GitHub API (/repos/...) の代わり。呼ばれた回数を数える */
    private class FakeGitHub(build: String, asset: String, archive: ByteArray, apiBody: String) : AutoCloseable {
        val apiCalls = AtomicInteger()
        val downloads = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/download/$build/$asset") { ex ->
                downloads.incrementAndGet()
                ex.sendResponseHeaders(200, archive.size.toLong())
                ex.responseBody.use { it.write(archive) }
            }
            createContext("/repos/") { ex ->
                apiCalls.incrementAndGet()
                val bytes = apiBody.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        private val baseUrl = "http://127.0.0.1:${server.address.port}"
        val releases = LlamaReleases(downloadBaseUrl = "$baseUrl/download", api = GitHubReleaseApi(baseUrl))

        override fun close() = server.stop(0)
    }

    private fun manager(dir: Path, releases: LlamaReleases) = LlamaServerManager(dir, releases) { _, _ -> }

    private fun config(build: String) = TranslatorConfig.current.copy(llamaBuild = build, gpuBackend = GpuBackend.CPU)

    private fun hasServer(dir: Path) = dir.exists() && Files.walk(dir).use { s -> s.anyMatch { it.name == serverExeName } }

    @Test
    fun defaultBuildHasBundledSha256ForEveryPlatformAndGpu() {
        // 既定の版で API を呼んだら接続できずに失敗する
        val releases = LlamaReleases(api = GitHubReleaseApi("http://127.0.0.1:1"))
        val sha256ByAssetName = releases.sha256ByAssetName(LlamaReleases.DEFAULT_BUILD)
        for (os in HostPlatform.Os.entries) for (arm in listOf(false, true)) for (gpu in GpuBackend.entries) {
            for (asset in LlamaReleases.assetNames(LlamaReleases.DEFAULT_BUILD, gpu, HostPlatform(os, arm))) {
                val sha256 = sha256ByAssetName[asset]
                assertTrue(sha256 != null && sha256.matches(Regex("[0-9a-f]{64}")), "$os arm=$arm $gpu: $asset → $sha256")
            }
        }
    }

    @Test
    fun defaultBuildDoesNotCallGitHubApiAndRejectsArchiveWithWrongSha256() {
        val build = LlamaReleases.DEFAULT_BUILD
        val (asset, archive) = fakeArchive(build)
        FakeGitHub(build, asset, archive, apiBody = """{"assets":[{"name":"$asset","digest":"sha256:${sha256(archive)}"}]}""").use { github ->
            val dir = Files.createTempDirectory("llama")
            val e = assertFailsWith<IllegalStateException> { manager(dir, github.releases).ensureBinary(config(build)) }
            assertTrue(e.message!!.contains("SHA-256 mismatch"), e.message)
            assertEquals(0, github.apiCalls.get())
            assertEquals(1, github.downloads.get())
            assertTrue(!hasServer(dir), "llama-server must not be extracted")
        }
    }

    @Test
    fun otherBuildUsesSha256FromGitHubApi() {
        val build = "b1"
        val (asset, archive) = fakeArchive(build)
        FakeGitHub(build, asset, archive, apiBody = """{"assets":[{"name":"$asset","digest":"sha256:${sha256(archive)}"}]}""").use { github ->
            val dir = Files.createTempDirectory("llama")
            val exe = manager(dir, github.releases).ensureBinary(config(build))
            assertEquals(serverExeName, exe.name)
            assertEquals(1, github.apiCalls.get())
        }
    }

    @Test
    fun otherBuildWithoutPublishedSha256IsNotDownloaded() {
        val build = "b1"
        val (asset, archive) = fakeArchive(build)
        FakeGitHub(build, asset, archive, apiBody = """{"assets":[{"name":"$asset","digest":null}]}""").use { github ->
            val dir = Files.createTempDirectory("llama")
            assertFailsWith<IllegalStateException> { manager(dir, github.releases).ensureBinary(config(build)) }
            assertEquals(0, github.downloads.get())
            assertTrue(!hasServer(dir))
        }
    }
}
