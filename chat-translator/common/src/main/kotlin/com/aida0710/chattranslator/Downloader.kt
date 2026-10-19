package com.aida0710.chattranslator

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** HTTP ダウンロード (途中再開・進捗通知・SHA-256 検証) とアーカイブ展開 */
object Downloader {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    /**
     * url を target にダウンロードする。target.part が残っていれば続きから再開する。
     * @param progress (受信済みバイト, 全体バイト or -1)
     */
    fun download(url: String, target: Path, sha256: String?, progress: (Long, Long) -> Unit) {
        Files.createDirectories(target.parent)
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        val existing = if (Files.exists(part)) Files.size(part) else 0L

        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "ChatTranslator-Minecraft-Mod")
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .GET().build()
        val res = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val resumed = res.statusCode() == 206
        if (res.statusCode() != 200 && !resumed) {
            res.body().close()
            throw IllegalStateException("HTTP ${res.statusCode()} for $url")
        }
        val length = res.headers().firstValueAsLong("Content-Length").orElse(-1L)
        val total = if (length < 0) -1L else if (resumed) existing + length else length
        var done = if (resumed) existing else 0L

        val options = if (resumed) arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        res.body().use { input ->
            Files.newOutputStream(part, *options).use { out ->
                val buf = ByteArray(1 shl 16)
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 500) {
                        lastReport = now
                        progress(done, total)
                    }
                }
            }
        }
        progress(done, total)

        if (!sha256.isNullOrBlank()) {
            val actual = sha256(part)
            if (!actual.equals(sha256.trim(), ignoreCase = true)) {
                Files.deleteIfExists(part)
                throw IllegalStateException("SHA-256 mismatch for ${target.fileName}: expected $sha256, got $actual")
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** .zip / .tar.gz を dest に展開する。パストラバーサルは拒否する */
    fun extract(archive: Path, dest: Path) {
        Files.createDirectories(dest)
        val name = archive.fileName.toString()
        when {
            name.endsWith(".zip") -> ZipInputStream(Files.newInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = safeResolve(dest, entry.name)
                    if (entry.isDirectory) Files.createDirectories(out) else write(out, zip)
                }
            }
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                GZIPInputStream(Files.newInputStream(archive)).use { untar(it, dest) }
            else -> throw IllegalArgumentException("Unsupported archive: $name")
        }
    }

    private fun write(out: Path, input: InputStream) {
        Files.createDirectories(out.parent)
        Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun safeResolve(dest: Path, entry: String): Path {
        val out = dest.resolve(entry).normalize()
        require(out.startsWith(dest.normalize())) { "Illegal archive entry: $entry" }
        return out
    }

    /** 最小限の ustar リーダー (通常ファイル・ディレクトリ・シンボリックリンク) */
    private fun untar(input: InputStream, dest: Path) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (input.readNBytes(header, 0, 512) < 512) return
            if (header.all { it.toInt() == 0 }) return
            fun str(off: Int, len: Int) = String(header, off, len, Charsets.UTF_8).substringBefore('\u0000')
            val size = str(124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val prefix = str(345, 155)
            val name = longName ?: (if (prefix.isNotEmpty()) "$prefix/" else "") + str(0, 100)
            longName = null
            val mode = str(100, 8).trim().ifEmpty { "0" }.toInt(8)
            val padded = (size + 511) / 512 * 512

            when (type) {
                'L' -> { // GNU long name
                    longName = String(input.readNBytes(size.toInt()), Charsets.UTF_8).substringBefore('\u0000')
                    input.skipNBytes(padded - size)
                    continue
                }
                '5' -> Files.createDirectories(safeResolve(dest, name))
                '2' -> {
                    val out = safeResolve(dest, name)
                    Files.createDirectories(out.parent)
                    Files.deleteIfExists(out)
                    runCatching { Files.createSymbolicLink(out, Path.of(str(157, 100))) }
                }
                '0', '\u0000', '7' -> {
                    val out = safeResolve(dest, name)
                    Files.createDirectories(out.parent)
                    Files.newOutputStream(out).use { o ->
                        var remaining = size
                        val buf = ByteArray(1 shl 16)
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) throw java.io.EOFException("Truncated tar")
                            o.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    if (mode and 0b001_001_001 != 0) out.toFile().setExecutable(true, false)
                    input.skipNBytes(padded - size)
                    continue
                }
            }
            // ディレクトリ・リンク・pax ヘッダなどはデータ部を読み飛ばす
            input.skipNBytes(padded)
        }
    }
}
