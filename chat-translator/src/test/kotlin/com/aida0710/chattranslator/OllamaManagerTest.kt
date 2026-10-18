package com.aida0710.chattranslator

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OllamaManagerTest {
    private fun server(models: String, onPull: () -> Unit = {}): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            fun respond(path: String, body: () -> String) = createContext(path) { ex ->
                val bytes = body().toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            respond("/api/version") { """{"version":"0.20.0"}""" }
            respond("/api/tags") { """{"models":[$models]}""" }
            respond("/api/pull") {
                onPull()
                listOf(
                    """{"status":"pulling manifest"}""",
                    """{"status":"downloading","total":2000000,"completed":1000000}""",
                    """{"status":"downloading","total":2000000,"completed":2000000}""",
                    """{"status":"success"}""",
                ).joinToString("\n")
            }
            start()
        }

    private fun awaitIdle(m: OllamaManager) {
        val deadline = System.currentTimeMillis() + 5000
        while (m.state in setOf(OllamaManager.State.UNKNOWN, OllamaManager.State.MISSING, OllamaManager.State.PULLING) &&
            System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun pullsMissingModel() {
        var pulled = false
        val s = server("""{"name":"llama3:latest"}""") { pulled = true }
        try {
            val notices = CopyOnWriteArrayList<String>()
            val m = OllamaManager { msg, _ -> notices.add(msg) }
            m.checkAsync(TranslatorConfig.current.copy(endpoint = "http://127.0.0.1:${s.address.port}/v1"))
            awaitIdle(m)
            assertEquals(OllamaManager.State.READY, m.state)
            assertTrue(pulled)
            assertTrue(notices.any { it.contains("50%") }, notices.toString())
        } finally {
            s.stop(0)
        }
    }

    @Test
    fun skipsPullWhenModelExists() {
        var pulled = false
        val s = server("""{"name":"translategemma:4b"}""") { pulled = true }
        try {
            val m = OllamaManager { _, _ -> }
            m.checkAsync(TranslatorConfig.current.copy(endpoint = "http://127.0.0.1:${s.address.port}/v1"))
            awaitIdle(m)
            assertEquals(OllamaManager.State.READY, m.state)
            assertTrue(!pulled)
        } finally {
            s.stop(0)
        }
    }

    @Test
    fun reportsServerNotRunning() {
        val m = OllamaManager { _, _ -> }
        m.checkAsync(TranslatorConfig.current.copy(endpoint = "http://127.0.0.1:1/v1"))
        val deadline = System.currentTimeMillis() + 5000
        while (m.state == OllamaManager.State.UNKNOWN && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(OllamaManager.State.NOT_RUNNING, m.state)
    }
}
