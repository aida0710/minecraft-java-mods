package com.aida0710.chattranslator

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** GitHub の API の回数制限で断られた。[waitSeconds] 秒後にまた使える */
class GitHubRateLimitedException(val waitSeconds: Long) :
    IllegalStateException("GitHub API rate limit exceeded; retry in $waitSeconds s") {
    /** 案内に出す待ち時間 (分、切り上げ。0 分とは言わない) */
    val waitMinutes: Long get() = maxOf(1L, (waitSeconds + 59) / 60)
}

/**
 * GitHub Releases API からリリースの配布ファイルの SHA-256 を取る。
 * 認証しないので、同じ IP からの呼び出しは 1 時間 60 回までに制限される。
 */
class GitHubReleaseApi(private val baseUrl: String = "https://api.github.com") {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** 配布ファイル名 → SHA-256 (GitHub が digest を公開しているものだけ) */
    fun assetSha256s(repository: String, tag: String): Map<String, String> {
        val res = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/repos/$repository/releases/tags/$tag"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ChatTranslator-Minecraft-Mod")
                .timeout(Duration.ofSeconds(20)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        rateLimitWaitSeconds(res.statusCode(), res.headers(), Instant.now())?.let { throw GitHubRateLimitedException(it) }
        check(res.statusCode() == 200) { "GitHub API HTTP ${res.statusCode()}" }
        return JsonParser.parseString(res.body()).asJsonObject.getAsJsonArray("assets").mapNotNull { a ->
            val o = a.asJsonObject
            val digest = o["digest"]?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            if (!digest.startsWith("sha256:")) return@mapNotNull null
            o["name"].asString to digest.removePrefix("sha256:")
        }.toMap()
    }

    private companion object {
        /** 待ち時間の手がかりが無い 429 は、GitHub の案内どおり最低 1 分待たせる */
        const val MINIMUM_WAIT_SECONDS = 60L

        /**
         * 回数制限で断られたなら、また使えるまでの秒数。回数制限でなければ null。
         * GitHub は回数制限を 403 か 429 で返し、retry-after (秒) か x-ratelimit-reset (UTC の epoch 秒) で待ち時間を示す。
         * 手がかりの無い 403 は権限などほかの理由もありうるので、回数制限とは見なさない。
         */
        fun rateLimitWaitSeconds(status: Int, headers: HttpHeaders, now: Instant): Long? {
            if (status != 403 && status != 429) return null
            fun header(name: String) = headers.firstValue(name).orElse(null)?.trim()?.toLongOrNull()
            header("retry-after")?.let { return maxOf(0L, it) }
            if (header("x-ratelimit-remaining") == 0L) {
                return header("x-ratelimit-reset")?.let { maxOf(0L, it - now.epochSecond) } ?: MINIMUM_WAIT_SECONDS
            }
            return if (status == 429) MINIMUM_WAIT_SECONDS else null
        }
    }
}
