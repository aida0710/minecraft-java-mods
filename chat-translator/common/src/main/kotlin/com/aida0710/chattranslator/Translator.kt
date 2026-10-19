package com.aida0710.chattranslator

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 翻訳のキュー管理。チャットと、表示中に裏で訳すもの (ツールチップ・本) でキューを分け、
 * ツールチップの大量リクエストでチャット翻訳が詰まらないようにする。
 */
class Translator(
    private val providers: Map<ProviderType, TranslationProvider>,
    val cache: TranslationCache,
    val dictionaries: Dictionaries,
    val itemNames: ItemNames,
) {
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failed = ConcurrentHashMap<String, Long>()

    private val chatExecutor = executor("chat", 256)
    private val backgroundExecutor = executor("background", 64)

    private fun executor(name: String, capacity: Int) = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingDeque(capacity),
        { r -> Thread(r, "ChatTranslator-$name").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun provider(cfg: TranslatorConfig.Values = TranslatorConfig.current) = providers.getValue(cfg.provider)

    fun isReady(cfg: TranslatorConfig.Values = TranslatorConfig.current) = provider(cfg).isReady(cfg)

    fun notReadyReason(cfg: TranslatorConfig.Values = TranslatorConfig.current) = provider(cfg).notReadyReason(cfg)

    /**
     * キャッシュキー。語の保護 (アイテム一覧の読み込み状況・keep 語) で訳文が変わるため区別する
     * (アイテム一覧の読み込み前に保護なしで訳した結果が、保護ありの訳を隠さないように)
     */
    private fun key(text: String, cfg: TranslatorConfig.Values = TranslatorConfig.current): String {
        val tag = "|" + itemNames.protectionTag
        return cfg.modelKey + tag + "\u0000" + text
    }

    /** 辞書またはキャッシュにあれば即座に返す。無ければ null。 */
    fun cached(text: String, cfg: TranslatorConfig.Values = TranslatorConfig.current): String? = dictionaries.lookup(text) ?: cache.get(key(text, cfg))

    fun isSkipped(text: String): Boolean = dictionaries.shouldSkip(text)

    /**
     * チャット用: 完了時にコールバックを (キャッシュヒット時は呼び出し元、それ以外は翻訳スレッドから) 呼ぶ。
     * 設定 (言語・エンジン) は依頼した時点の [cfg] を使う (処理待ちの間に設定が変わっても混ざらないように)。
     * 送信するチャットは翻訳元・翻訳先を入れ替えた cfg (TranslatorConfig.Values.forSending) を渡す
     */
    fun translateChat(
        text: String,
        onSuccess: (String) -> Unit,
        onFailure: (net.minecraft.network.chat.Component) -> Unit,
        cfg: TranslatorConfig.Values = TranslatorConfig.current,
    ) {
        cached(text, cfg)?.let { onSuccess(it); return }
        // エンジンの準備ができていなければ翻訳しない (原文のまま)
        if (!isReady(cfg)) { onFailure(notReadyReason(cfg)); return }
        try {
            chatExecutor.execute {
                runCatching { translateNow(text, cfg) }
                    .onSuccess(onSuccess)
                    .onFailure {
                        ChatTranslator.LOGGER.warn("Chat translation failed: {}", it.toString())
                        onFailure(net.minecraft.network.chat.Component.literal(it.message ?: it.toString()))
                    }
            }
        } catch (_: RejectedExecutionException) {
            ChatTranslator.LOGGER.warn("Chat translation queue is full; dropping message")
            onFailure(T.tr("error.queue_full"))
        }
    }

    /**
     * ツールチップ・本のページ用: キャッシュにあれば返し、無ければ非同期に翻訳を予約して null を返す。
     * 毎フレーム呼ばれる前提なので重複予約・失敗直後の再試行はしない。
     */
    fun translateInBackground(text: String): String? {
        cached(text)?.let { return it }
        if (!isReady()) return null
        val cfg = TranslatorConfig.current
        val k = key(text, cfg)
        val failedAt = failed[k]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return null
        if (pending.add(k)) {
            val task = Runnable {
                try {
                    translateNow(text, cfg)
                    failed.remove(k)
                } catch (e: Exception) {
                    failed[k] = System.currentTimeMillis()
                    ChatTranslator.LOGGER.debug("Background translation failed: {}", e.toString())
                } finally {
                    pending.remove(k)
                }
            }
            try {
                backgroundExecutor.execute(task)
            } catch (_: RejectedExecutionException) {
                // キューが満杯なら捨て、次のフレームで再予約できるようにする
                pending.remove(k)
            }
        }
        return null
    }

    private fun translateNow(text: String, cfg: TranslatorConfig.Values): String {
        (dictionaries.lookup(text) ?: cache.get(key(text, cfg)))?.let { return it }
        val result = translateProtected(text, cfg)
        if (result.isBlank()) throw IllegalStateException("empty translation")
        cache.put(key(text, cfg), result)
        return result
    }

    /** 保護語 (アイテム名・辞書の keep) を ITEMn に置き換えて訳し、元に戻す。プレースホルダーが崩れたら通常の翻訳にする */
    private fun translateProtected(text: String, cfg: TranslatorConfig.Values): String {
        val provider = provider(cfg)
        val p = itemNames.protect(text)
        if (p.names.isEmpty()) return provider.translate(text, cfg)
        // 保護語だけの文 (記号のみ残る) は訳さない
        if (p.text.replace(PLACEHOLDER, "").none { it.isLetter() }) return text
        itemNames.restore(provider.translate(p.text, cfg), p.names)?.let { return it }
        ChatTranslator.LOGGER.info("Protected term lost in translation; retrying without protection: {}", text)
        return provider.translate(text, cfg)
    }

    fun clearCache() {
        cache.clear()
        cache.saveIfDirty()
        failed.clear()
    }

    fun shutdown() {
        chatExecutor.shutdownNow()
        backgroundExecutor.shutdownNow()
        cache.saveIfDirty()
    }

    private companion object {
        const val RETRY_AFTER_MS = 30_000L
        val PLACEHOLDER = Regex("ITEM\\d+")
    }
}
