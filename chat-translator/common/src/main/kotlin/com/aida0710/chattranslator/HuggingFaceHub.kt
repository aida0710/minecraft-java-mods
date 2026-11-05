package com.aida0710.chattranslator

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

/** Hugging Face からモデルを取得する。テストでは取得元をローカルのサーバーに向ける */
class HuggingFaceHub(private val baseUrl: String = "https://huggingface.co") {
    private data class RepositoryFile(val path: String, val bytes: Long, val sha256: String?)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun downloadFile(distribution: SingleFileDistribution, target: Path, progress: (Long, Long) -> Unit) {
        Downloader.download(fileUrl(distribution.repository, distribution.commit, distribution.fileName), target, distribution.sha256, progress)
    }

    fun isSnapshotComplete(directory: Path): Boolean = directory.resolve(COMPLETE_MARKER).exists()

    /**
     * リポジトリの全ファイルを directory に取得する。途中で止まっても、取得済みのファイルは次回そのまま使う。
     * 全部そろったら印のファイルを書くので、[isSnapshotComplete] が true なら起動してよい。
     */
    fun downloadSnapshot(distribution: SnapshotDistribution, directory: Path, progress: (Long, Long) -> Unit) {
        val files = listFiles(distribution).filterNot { isDocumentation(it.path) }
        val totalBytes = files.sumOf { it.bytes }
        var finishedBytes = 0L
        for (file in files) {
            val target = directory.resolve(file.path).normalize()
            check(target.startsWith(directory.normalize())) { "Illegal path in ${distribution.repository}: ${file.path}" }
            if (!(target.exists() && Files.size(target) == file.bytes)) {
                Downloader.download(fileUrl(distribution.repository, distribution.commit, file.path), target, file.sha256) { done, _ ->
                    progress(finishedBytes + done, totalBytes)
                }
            }
            finishedBytes += file.bytes
        }
        Files.writeString(directory.resolve(COMPLETE_MARKER), distribution.commit)
    }

    private fun fileUrl(repository: String, commit: String, path: String) = "$baseUrl/$repository/resolve/$commit/$path"

    private fun listFiles(distribution: SnapshotDistribution): List<RepositoryFile> {
        val url = "$baseUrl/api/models/${distribution.repository}/tree/${distribution.commit}?recursive=true"
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(API_TIMEOUT).header("User-Agent", Downloader.USER_AGENT).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
        return JsonParser.parseString(response.body()).asJsonArray.map { it.asJsonObject }
            .filter { it["type"].asString == "file" }
            .map { entry ->
                RepositoryFile(
                    path = entry["path"].asString,
                    bytes = entry["size"].asLong,
                    // Git LFS のファイル (重みなど) だけ SHA-256 が公開されている。小さなファイルはコミットを固定した URL で取得する
                    sha256 = entry["lfs"]?.asJsonObject?.get("oid")?.asString,
                )
            }
    }

    /** 動かすのに要らないファイル。PLaMo の README と図だけで数 MB ある */
    private fun isDocumentation(path: String) = path.startsWith("figures/") || path.startsWith("README") || path == ".gitattributes"

    private companion object {
        const val COMPLETE_MARKER = ".chattranslator-complete"
        val API_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
