# Chat Translator (Forge 26.3 / Kotlin)

ローカル LLM を使って、受信チャットと**アイテムのツールチップ (名前・説明文)** を英語→日本語に翻訳するクライアント専用 mod です。
Hypixel SkyBlock での利用を想定しています。

- 対応: Minecraft **26.3** / Forge **66.0.x** / Java 25
- 実装言語: Kotlin (標準ライブラリは jar に同梱済み。Kotlin for Forge は不要)
- LLM: OpenAI 互換 API (`/v1/chat/completions`) を持つものなら何でも可 — Ollama / LM Studio / llama.cpp server / vLLM

## 1. ローカル LLM の準備 (Ollama 推奨)

翻訳特化モデル **TranslateGemma** (Google, 55言語対応) をデフォルトにしています。

```powershell
winget install Ollama.Ollama
ollama pull translategemma:4b     # 3.3GB。VRAM 6GB 程度〜。チャット用途ならこれで十分
# 余裕があれば品質重視で
ollama pull translategemma:12b    # 8.1GB
```

Ollama を起動しておけば `http://localhost:11434/v1` で待ち受けます (設定変更不要)。

### モデルの選択肢

| モデル | サイズ | 特徴 | 設定 |
| --- | --- | --- | --- |
| `translategemma:4b` (デフォルト) | 3.3GB | 翻訳特化。速度と品質のバランス | そのまま |
| `translategemma:12b` | 8.1GB | 翻訳特化・高品質 | `model = "translategemma:12b"` |
| LFM2-350M-ENJP-MT (Liquid AI) | ~0.3GB | 英日特化の超小型。CPU でもリアルタイム級 | GGUF を LM Studio / llama.cpp で読み込み、`promptTemplate = "{text}"` + `systemPrompt = "Translate to Japanese."` |
| 汎用モデル (Qwen など) | — | 指示に従わせやすい | `systemPrompt` に「翻訳文のみ出力」等を記述 |

## 2. 導入

1. [Actions](../../actions) の最新ビルドの Artifact、または Release から `chattranslator-x.y.z.jar` を取得
2. `.minecraft/mods/` (Prism Launcher ならインスタンスの `mods/`) に配置
3. 起動すると `config/chattranslator-client.toml` と `config/chattranslator/glossary.json` が生成されます

## 3. 使い方

- チャット: 英文を受信すると、その下に ` ⤷ 訳文` が灰色で追加表示されます
  - `chat.showOriginal = false` で原文を隠して訳文のみ表示 (翻訳失敗時は原文を表示)
  - `[MVP+] Name: message` のような形式は名前部分を除いて本文だけ翻訳します
- ツールチップ: アイテムにカーソルを合わせると、未翻訳の行は裏で翻訳が始まり、完了次第訳文に切り替わります
  - 一度訳した文は `config/chattranslator/cache.json` に保存され、次回から即時表示
- キー操作 (設定 > 操作 で変更可)
  - `J`: ツールチップ翻訳 ON/OFF (原文を確認したいときに)
  - 未割当: チャット翻訳 ON/OFF
- クライアントコマンド
  - `/chattranslator status` — 状態表示
  - `/chattranslator test <英文>` — LLM との接続テスト
  - `/chattranslator reload` — glossary.json の再読み込み
  - `/chattranslator clearcache` — 翻訳キャッシュを削除

## 4. 辞書 (glossary.json)

```json
{
  "terms": {
    "Hyperion": "Hyperion",
    "Click to view!": "クリックして表示！"
  },
  "skip": [
    "^\\s*(Damage|Strength|Defense): .*$"
  ]
}
```

- `terms`: 行全体が完全一致したら LLM を使わずこの訳を使う。**値を原文と同じにすると翻訳しない**ので、固有名詞のアイテム名を英語のまま残すのに便利です
- `skip`: 正規表現にマッチする行は翻訳しない (ステータス行やレアリティ行など)

## 5. 既知の制限

- **アイテム説明文の翻訳は可能ですが**、以下の制約があります
  - 初めて見る行は最初の数秒は英語のまま (LLM の応答待ち)。キャッシュに入れば以降は即時
  - 1 行の中で色が混在している場合、訳文は行頭の色 1 色になります
  - 行単位の翻訳なので、複数行にまたがる文は不自然になることがあります
  - ステータス値など数値が頻繁に変わる行はキャッシュが効きにくいため、`skip` で除外するのがおすすめ
- 訳文のみ表示モードでは、翻訳待ちの間にメッセージの順序が前後することがあります
- この mod はクライアント側の表示を変えるだけで、サーバーへ何かを送信することはありません。Hypixel のルール (Allowed Modifications) に照らしての利用可否はご自身で判断してください

## 開発

```powershell
cd chat-translator
./gradlew build       # build/libs/chattranslator-<version>.jar が配布用 (Kotlin 同梱)
./gradlew runClient   # 開発用クライアント起動
```

JDK 25 は Gradle (foojay) が自動で取得します。Gradle の起動自体には Java 17 以上が必要です。
