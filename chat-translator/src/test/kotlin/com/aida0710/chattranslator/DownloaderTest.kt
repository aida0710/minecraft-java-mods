package com.aida0710.chattranslator

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DownloaderTest {
    private val payload = ByteArray(300_000) { (it % 251).toByte() }

    private fun server(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/file.bin") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val body = payload.copyOfRange(start, payload.size)
            if (start > 0) ex.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
            ex.sendResponseHeaders(if (start > 0) 206 else 200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }

    @Test
    fun resumesPartialDownloadAndVerifiesHash() {
        val s = server()
        val dir = Files.createTempDirectory("dl")
        try {
            val target = dir.resolve("file.bin")
            Files.write(dir.resolve("file.bin.part"), payload.copyOfRange(0, 100_000))
            var lastTotal = 0L
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
            Downloader.download("http://127.0.0.1:${s.address.port}/file.bin", target, expected) { _, total -> lastTotal = total }
            assertTrue(Files.readAllBytes(target).contentEquals(payload))
            assertEquals(payload.size.toLong(), lastTotal)
        } finally {
            s.stop(0)
        }
    }

    @Test
    fun rejectsHashMismatch() {
        val s = server()
        val dir = Files.createTempDirectory("dl")
        try {
            assertFailsWith<IllegalStateException> {
                Downloader.download("http://127.0.0.1:${s.address.port}/file.bin", dir.resolve("f"), "00".repeat(32)) { _, _ -> }
            }
            assertTrue(!Files.exists(dir.resolve("f")) && !Files.exists(dir.resolve("f.part")))
        } finally {
            s.stop(0)
        }
    }

    @Test
    fun extractsZipAndTarGz() {
        val dir = Files.createTempDirectory("ex")
        val zip = dir.resolve("a.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("bin/llama-server.exe")); z.write("zip".toByteArray()); z.closeEntry()
        }
        Downloader.extract(zip, dir.resolve("z"))
        assertEquals("zip", dir.resolve("z/bin/llama-server.exe").readText())

        // ustar ヘッダを手書きして tar.gz を作る
        fun header(name: String, size: Int): ByteArray {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "0000755\u0000".toByteArray().copyInto(h, 100)
            "%011o\u0000".format(size).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            "ustar\u0000".toByteArray().copyInto(h, 257)
            return h
        }
        val content = "tar".toByteArray()
        val tar = ByteArrayOutputStream().apply {
            write(header("build/bin/llama-server", content.size)); write(content); write(ByteArray(512 - content.size)); write(ByteArray(1024))
        }.toByteArray()
        val tgz = dir.resolve("a.tar.gz")
        GZIPOutputStream(Files.newOutputStream(tgz)).use { it.write(tar) }
        Downloader.extract(tgz, dir.resolve("t"))
        assertEquals("tar", dir.resolve("t/build/bin/llama-server").readText())
    }

    @Test
    fun rejectsPathTraversal() {
        val dir = Files.createTempDirectory("ex")
        val zip = dir.resolve("evil.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z -> z.putNextEntry(ZipEntry("../evil.txt")); z.write(1); z.closeEntry() }
        assertFailsWith<IllegalArgumentException> { Downloader.extract(zip, dir.resolve("out")) }
    }
}
