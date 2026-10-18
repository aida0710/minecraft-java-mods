package com.aida0710.chattranslator

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ollama の場合にモデルの有無を確認し、無ければ /api/pull で自動ダウンロードする。
 * Ollama 以外 (LM Studio など) のサーバーでは何もしない。
 */
class OllamaManager(private val notify: (message: String, progress: Boolean) -> Unit) {
    enum class State { UNKNOWN, NOT_RUNNING, NOT_OLLAMA, MISSING, PULLING, READY, FAILED }

    @Volatile var state = State.UNKNOWN
        private set
    private val busy = AtomicBoolean(false)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    /** バックグラウンドで確認 (と必要なら pull) を行う。実行中なら何もしない */
    fun checkAsync(cfg: TranslatorConfig.Values, forcePull: Boolean = false) {
        if (!busy.compareAndSet(false, true)) {
            notify("モデルの確認/ダウンロードは既に実行中です", false)
            return
        }
        Thread({
            try {
                check(cfg, forcePull)
            } catch (e: Exception) {
                state = State.FAILED
                ChatTranslator.LOGGER.warn("Ollama check failed", e)
                notify("モデルの確認に失敗しました: ${e.message}", false)
            } finally {
                busy.set(false)
            }
        }, "ChatTranslator-ollama").apply { isDaemon = true }.start()
    }

    private fun check(cfg: TranslatorConfig.Values, forcePull: Boolean) {
        val base = baseUrl(cfg.endpoint)
        val version = try {
            get("$base/api/version")
        } catch (e: java.io.IOException) {
            state = State.NOT_RUNNING
            notify("LLM サーバー (${cfg.endpoint}) に接続できません。Ollama などを起動してください", false)
            return
        }
        if (version.statusCode() != 200 || !version.body().contains("\"version\"")) {
            state = State.NOT_OLLAMA // LM Studio など。モデル管理はユーザーに任せる
            return
        }

        if (!forcePull && hasModel(base, cfg.model)) {
            state = State.READY
            ChatTranslator.LOGGER.info("Ollama model {} is available", cfg.model)
            return
        }
        state = State.MISSING
        if (!cfg.autoPull && !forcePull) {
            notify("モデル ${cfg.model} が Ollama にありません。/chattranslator pull でダウンロードできます", false)
            return
        }
        pull(base, cfg.model)
    }

    private fun hasModel(base: String, model: String): Boolean {
        val res = get("$base/api/tags")
        if (res.statusCode() != 200) return false
        val wanted = if (model.contains(':')) model else "$model:latest"
        return JsonParser.parseString(res.body()).asJsonObject.getAsJsonArray("models")
            ?.any { it.asJsonObject["name"]?.asString == wanted } ?: false
    }

    private fun pull(base: String, model: String) {
        state = State.PULLING
        notify("翻訳モデル $model をダウンロードしています… (初回のみ。完了まで翻訳は行われません)", false)
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", true)
        }
        val request = HttpRequest.newBuilder(URI.create("$base/api/pull"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val res = http.send(request, HttpResponse.BodyHandlers.ofLines())
        if (res.statusCode() != 200) throw IllegalStateException("HTTP ${res.statusCode()}")

        var lastPercent = -1
        var success = false
        res.body().use { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val json = JsonParser.parseString(line).asJsonObject
                json["error"]?.let { throw IllegalStateException(it.asString) }
                val status = json["status"]?.asString ?: continue
                val total = json["total"]?.asLong ?: 0L
                val completed = json["completed"]?.asLong ?: 0L
                if (total > 0 && completed > 0) {
                    val percent = (completed * 100 / total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        notify("$model をダウンロード中: $percent% (${completed.mb()} / ${total.mb()} MB)", true)
                    }
                }
                if (status == "success") success = true
            }
        }
        if (!success) throw IllegalStateException("pull did not finish")
        state = State.READY
        notify("翻訳モデル $model の準備ができました", false)
    }

    private fun Long.mb() = this / 1_000_000

    private fun get(url: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())

    companion object {
        /** OpenAI 互換エンドポイント (…/v1) から Ollama のベース URL を得る */
        fun baseUrl(endpoint: String): String = endpoint.trimEnd('/').removeSuffix("/v1")
    }
}
