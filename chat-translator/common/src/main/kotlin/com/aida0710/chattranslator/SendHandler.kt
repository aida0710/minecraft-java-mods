package com.aida0710.chattranslator

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 自分が送信するチャットの翻訳。チャット入力欄で Enter が押されたときに、各ローダーの画面のキー入力イベントから呼ばれる。
 * Forge の送信イベントはコマンド (/pc など) では出ないため、送る前の入力欄の文字を扱う。
 *  - 確認あり (send.confirm): 訳文を入力欄に入れる。もう一度 Enter を押すとそのまま送る
 *  - 確認なし: 訳文を入力欄に入れて、画面の通常の Enter 処理 (送信・履歴・画面を閉じる) をそのまま動かす
 *
 * @param notify 案内を出す (true なら画面下の一時表示、false ならチャット欄)
 */
class SendHandler(private val translator: Translator, private val notify: (Component, Boolean) -> Unit) {
    /** 入力欄に入れた訳文 (または翻訳に失敗した原文)。これと同じ文で Enter が押されたら翻訳せずに送る */
    private var passThrough: String? = null
    /** 翻訳中の原文。Enter を連打しても二重に依頼しない */
    private var translating: String? = null
    /** 最近翻訳して入力欄に入れた文 (コマンド部分を除く) と時刻。サーバーから戻ってきた自分の発言を訳し戻さないために使う */
    private val recentlySent = ConcurrentHashMap<String, Long>()

    /** [body] が最近この mod が翻訳して送った (送ろうとした) 文か */
    fun isRecentlySent(body: String): Boolean {
        val now = System.currentTimeMillis()
        recentlySent.entries.removeIf { now - it.value > RECENT_MS }
        return recentlySent.containsKey(body.trim())
    }

    /** @return true ならこの Enter はここで処理した (画面の既定の送信をさせない) */
    fun onEnter(screen: ChatScreen): Boolean {
        val cfg = TranslatorConfig.current
        if (!cfg.sendEnabled) return false
        val box = screen.focused as? EditBox ?: return false
        val text = box.value.trim()
        if (text.isEmpty()) return false
        if (text == passThrough) {
            passThrough = null
            return false
        }
        if (text == translating) return true
        val parts = SendMessage.split(text, cfg.sendCommands) ?: return false
        if (!TextUtil.looksTranslatable(parts.body, minLength = 1, source = cfg.sendSourceCode, target = cfg.sendTargetCode)) return false

        translating = text
        notify(T.tr("send.translating"), true)
        // キャッシュにあればこの場でコールバックが呼ばれる。キー入力の処理中に画面を書き換えないよう、次のフレームで反映する
        val mc = Minecraft.getInstance()
        translator.translateChat(
            parts.body,
            onSuccess = { translated -> mc.schedule { deliver(screen, box, text, parts, SendMessage.compose(parts, translated, cfg.sendFormat), cfg.sendConfirm) } },
            onFailure = { reason -> mc.schedule { fail(text, reason) } },
            cfg = cfg.forSending(),
        )
        return true
    }

    private fun deliver(screen: ChatScreen, box: EditBox, original: String, parts: SendMessage.Parts, message: String, confirm: Boolean) {
        translating = null
        val mc = Minecraft.getInstance()
        // 翻訳を待つ間に画面を閉じた・書き換えたなら、送らない
        if (mc.gui.screen() !== screen || box.value.trim() != original) {
            notify(T.tr("send.cancelled"), true)
            return
        }
        // ↑ キーの履歴で原文も呼び出せるようにする (訳文は送信時に画面が履歴へ入れる)
        mc.gui.hud.chat.addRecentChat(original)
        recentlySent[message.removePrefix(parts.prefix).trim()] = System.currentTimeMillis()
        box.value = message
        if (confirm) {
            passThrough = message
            notify(T.tr("send.review"), true)
            return
        }
        // 画面の keyPressed を直接呼ぶとローダーのキー入力イベントは出ないので、ここへは戻ってこない
        screen.keyPressed(KeyEvent(InputConstants.KEY_RETURN, 0, 0))
    }

    private fun fail(original: String, reason: Component) {
        translating = null
        passThrough = original
        notify(T.tr("send.failed", reason), false)
    }

    private companion object {
        /** 送ってからサーバーの返す自分の発言が届くまで、または確認中に入力欄で待つ時間の目安 */
        const val RECENT_MS = 120_000L
    }
}
