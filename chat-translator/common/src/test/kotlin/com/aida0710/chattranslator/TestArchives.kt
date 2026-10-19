package com.aida0710.chattranslator

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** テスト用に、llama.cpp の配布ファイルと同じ形式 (.zip / .tar.gz) のアーカイブを作る */
object TestArchives {
    /** 配布ファイル名の拡張子に合わせて、files (パス → 中身) を詰める */
    fun forAssetName(assetName: String, files: Map<String, ByteArray>): ByteArray = when {
        assetName.endsWith(".zip") -> zip(files)
        assetName.endsWith(".tar.gz") -> tarGz(files)
        else -> error("unsupported archive: $assetName")
    }

    fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z ->
            for ((path, content) in files) {
                z.putNextEntry(ZipEntry(path)); z.write(content); z.closeEntry()
            }
        }
    }.toByteArray()

    /** ustar ヘッダを手書きして tar.gz を作る (実行権限つきの通常ファイルだけ) */
    fun tarGz(files: Map<String, ByteArray>): ByteArray {
        fun header(path: String, size: Int): ByteArray {
            val h = ByteArray(512)
            path.toByteArray().copyInto(h, 0)
            "0000755\u0000".toByteArray().copyInto(h, 100)
            "%011o\u0000".format(size).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            "ustar\u0000".toByteArray().copyInto(h, 257)
            return h
        }
        val tar = ByteArrayOutputStream().apply {
            for ((path, content) in files) {
                write(header(path, content.size)); write(content); write(ByteArray((512 - content.size % 512) % 512))
            }
            write(ByteArray(1024))
        }.toByteArray()
        return ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(tar) } }.toByteArray()
    }
}
