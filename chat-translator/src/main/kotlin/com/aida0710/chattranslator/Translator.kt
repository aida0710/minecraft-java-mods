package com.aida0710.chattranslator

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 翻訳のキュー管理。チャットとツールチップでキューを分け、
 * ツールチップの大量リクエストでチャット翻訳が詰まらないようにする。
 */
class Translator(
    private val client: LlmClient,
    private val cache: TranslationCache,
    private val glossary: Glossary,
    val itemNames: ItemNames,
) {
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failed = ConcurrentHashMap<String, Long>()

    private val chatExecutor = executor("chat", 256)
    private val tooltipExecutor = executor("tooltip", 64)

    private fun executor(name: String, capacity: Int) = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingDeque(capacity),
        { r -> Thread(r, "ChatTranslator-$name").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /**
     * キャッシュキー。アイテム名保護の有無で訳文が変わるため区別する
     * (アイテム一覧の読み込み前に保護なしで訳した結果が、保護ありの訳を隠さないように)
     */
    private fun key(text: String): String {
        val cfg = TranslatorConfig.current
        val tag = if (cfg.protectItemNames && itemNames.size > 0) "|items" else ""
        return cfg.modelKey + tag + "\u0000" + text
    }

    /** 辞書またはキャッシュにあれば即座に返す。無ければ null。 */
    fun cached(text: String): String? = glossary.lookup(text) ?: cache.get(key(text))

    fun isSkipped(text: String): Boolean = glossary.shouldSkip(text)

    /** チャット用: 完了時にコールバックを (キャッシュヒット時は呼び出し元、それ以外は翻訳スレッドから) 呼ぶ */
    fun translateChat(text: String, onSuccess: (String) -> Unit, onFailure: () -> Unit) {
        cached(text)?.let { onSuccess(it); return }
        // 内蔵サーバーの起動待ち中は翻訳しない (原文のまま)
        if (!client.isReady()) { onFailure(); return }
        try {
            chatExecutor.execute {
                runCatching { translateNow(text) }
                    .onSuccess(onSuccess)
                    .onFailure {
                        ChatTranslator.LOGGER.warn("Chat translation failed: {}", it.toString())
                        onFailure()
                    }
            }
        } catch (_: RejectedExecutionException) {
            ChatTranslator.LOGGER.warn("Chat translation queue is full; dropping message")
            onFailure()
        }
    }

    /**
     * ツールチップ用: キャッシュにあれば返し、無ければ非同期に翻訳を予約して null を返す。
     * 毎フレーム呼ばれる前提なので重複予約・失敗直後の再試行はしない。
     */
    fun translateTooltipLine(text: String): String? {
        cached(text)?.let { return it }
        if (!client.isReady()) return null
        val k = key(text)
        val failedAt = failed[k]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return null
        if (pending.add(k)) {
            val task = Runnable {
                try {
                    translateNow(text)
                    failed.remove(k)
                } catch (e: Exception) {
                    failed[k] = System.currentTimeMillis()
                    ChatTranslator.LOGGER.debug("Tooltip translation failed: {}", e.toString())
                } finally {
                    pending.remove(k)
                }
            }
            try {
                tooltipExecutor.execute(task)
            } catch (_: RejectedExecutionException) {
                // キューが満杯なら捨て、次のフレームで再予約できるようにする
                pending.remove(k)
            }
        }
        return null
    }

    private fun translateNow(text: String): String {
        cached(text)?.let { return it }
        val result = translateProtected(text)
        if (result.isBlank()) throw IllegalStateException("empty translation")
        cache.put(key(text), result)
        return result
    }

    /** 既知のアイテム名を ITEMn に置き換えて訳し、元に戻す。プレースホルダーが崩れたら通常の翻訳にする */
    private fun translateProtected(text: String): String {
        if (!TranslatorConfig.current.protectItemNames) return client.translate(text)
        val p = itemNames.protect(text)
        if (p.names.isEmpty()) return client.translate(text)
        // アイテム名だけの文 (記号のみ残る) は訳さない
        if (p.text.replace(PLACEHOLDER, "").none { it.isLetter() }) return text
        itemNames.restore(client.translate(p.text), p.names)?.let { return it }
        ChatTranslator.LOGGER.debug("Placeholder lost; retrying without item protection: {}", text)
        return client.translate(text)
    }

    fun clearCache() {
        cache.clear()
        failed.clear()
    }

    fun shutdown() {
        chatExecutor.shutdownNow()
        tooltipExecutor.shutdownNow()
        cache.saveIfDirty()
    }

    private companion object {
        const val RETRY_AFTER_MS = 30_000L
        val PLACEHOLDER = Regex("ITEM\\d+")
    }
}
