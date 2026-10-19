package com.aida0710.chattranslator

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubReleaseApiTest {
    /** /repos/o/r/releases/tags/t に status・headers・body を返すだけの GitHub API の代わり */
    private fun server(status: Int, headers: Map<String, String> = emptyMap(), body: String = "{}"): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/repos/o/r/releases/tags/t") { ex ->
                headers.forEach { (name, value) -> ex.responseHeaders.add(name, value) }
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun <T> withApi(server: HttpServer, block: (GitHubReleaseApi) -> T): T =
        try { block(GitHubReleaseApi("http://127.0.0.1:${server.address.port}")) } finally { server.stop(0) }

    @Test
    fun readsOnlyPublishedSha256Digests() {
        val body = """{"assets":[
            {"name":"a.zip","digest":"sha256:${"a".repeat(64)}"},
            {"name":"b.zip","digest":null},
            {"name":"c.zip","digest":"sha512:${"c".repeat(128)}"}
        ]}"""
        val sha256s = withApi(server(200, body = body)) { it.assetSha256s("o/r", "t") }
        assertEquals(mapOf("a.zip" to "a".repeat(64)), sha256s)
    }

    @Test
    fun rateLimitedRequestTellsMinutesUntilReset() {
        val reset = Instant.now().epochSecond + 1500
        val headers = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to reset.toString())
        val e = assertFailsWith<GitHubRateLimitedException> {
            withApi(server(403, headers)) { it.assetSha256s("o/r", "t") }
        }
        assertTrue(e.waitSeconds in 1490..1500, "waitSeconds: ${e.waitSeconds}")
        assertEquals(25, e.waitMinutes)
    }

    @Test
    fun tooManyRequestsWaitsForRetryAfter() {
        val e = assertFailsWith<GitHubRateLimitedException> {
            withApi(server(429, mapOf("Retry-After" to "90"))) { it.assetSha256s("o/r", "t") }
        }
        assertEquals(90, e.waitSeconds)
        assertEquals(2, e.waitMinutes)
    }

    @Test
    fun forbiddenWithoutRateLimitHeadersIsNotReportedAsRateLimit() {
        val e = assertFailsWith<IllegalStateException> {
            withApi(server(403)) { it.assetSha256s("o/r", "t") }
        }
        assertFalse(e is GitHubRateLimitedException)
        assertEquals("GitHub API HTTP 403", e.message)
    }
}
