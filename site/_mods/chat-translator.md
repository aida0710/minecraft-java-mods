---
title: Chat Translator
order: 1
# 一覧のカードの名前の色 (Minecraft のレアリティの色に合わせる)
color: "#FF55FF"
minecraft: "26.3"
loaders: [Forge, NeoForge, Fabric]
source: chat-translator
thumbnail: /assets/chat-translator/chat.jpg
video: /assets/chat-translator/intro.mp4
video_poster: /assets/chat-translator/poster.jpg
summary:
  ja: チャット・アイテムの説明・本を翻訳し、自分の発言も翻訳して送れるクライアント用の mod です。
  en: A client-side mod that translates chat, item tooltips and books, and can send your own messages translated.
demo:
  prefix: "[MVP+] Steve: "
  prefix_color: "#55FFFF"
  original: anyone want to do dungeons? we need a healer
  translated: ダンジョンに行く人はいますか？ ヒーラーを探しています。
features:
  - image: /assets/chat-translator/chat.jpg
    ja:
      title: チャットを、同じ行のまま翻訳
      text: 届いたチャットを、同じ行のまま訳文に置き換えます。ランクや名前の色はそのままです。行末の [原文] を押すと元の文に戻せます。
    en:
      title: Chat, translated in place
      text: Incoming chat lines are replaced with the translation on the same line, keeping ranks and name colors. Click [Original] at the end of a line to switch back.
  - image: /assets/chat-translator/tooltip.jpg
    ja:
      title: アイテムの説明も翻訳
      text: 同じ色で続く行を 1 つの段落にまとめて訳し、元の幅で折り返します。数値の色もそのままです。J を押している間は原文を表示します。
    en:
      title: Item tooltips too
      text: Lines of the same color are translated together as one paragraph and wrapped to the original width, keeping the colors of numbers. Hold J to see the original.
  - image: /assets/chat-translator/book.jpg
    ja:
      title: 本も翻訳
      text: 記入済みの本や書見台の本を、開いているページから順に訳します。クリックできる文字を含むページは、動作を壊さないよう原文のままです。
    en:
      title: Books
      text: Written books and lecterns are translated starting from the open page. Pages with clickable text stay as they are, so they keep working.
  - image: /assets/chat-translator/send.jpg
    ja:
      title: 自分の発言も翻訳して送信
      text: チャット欄で Enter を押すと、書いた文を訳して入力欄に入れます。確かめてもう一度 Enter で送信します。/pc や /msg などのコマンドにも対応しています。
    en:
      title: Send your messages translated
      text: Press Enter in the chat box and your message is translated into the box. Check it and press Enter again to send. Works with commands such as /pc and /msg.
  - image: /assets/chat-translator/settings.jpg
    ja:
      title: エンジンと言語を選べる
      text: 内蔵 LLM（無料・オフライン）、Claude、DeepL、Google、OpenAI 互換 API から選べます。翻訳できる言語は約 30。mod の画面は日本語・English・简体中文です。
    en:
      title: Your choice of engine and language
      text: Use the built-in LLM (free and offline), Claude, DeepL, Google or any OpenAI-compatible API, with about 30 languages. The mod's own screens are in Japanese, English and Simplified Chinese.
---
<div data-lang="ja" markdown="1">

## 入れ方

1. [ダウンロード](https://github.com/aida0710/minecraft-java-mods/releases)から、使うローダーの jar を取ります（Forge は `chattranslator-forge-…jar`、NeoForge は `chattranslator-neoforge-…jar`、Fabric は `chattranslator-fabric-…jar`）。Fabric では [Fabric API](https://modrinth.com/mod/fabric-api) も必要です
2. jar を `.minecraft/mods/` に入れて起動します
3. ワールドかサーバーに入り、`/chattranslator` で設定画面を開きます
4. 「翻訳エンジン」タブでエンジンを選びます。内蔵 LLM なら「インストール」を押すだけです（約 2.5GB。ゲーム中だけ動きます）。クラウドのサービスなら API キーを貼り付けます

## 翻訳エンジン

| エンジン | 料金 | 向いていること |
| --- | --- | --- |
| 内蔵 LLM（TranslateGemma 4B） | 無料 | オフラインで使える。VRAM 4GB 程度 |
| Claude API | 従量課金 | 文脈やスラングに強い |
| DeepL / Google | 従量課金（無料枠あり） | 速い |
| OpenAI 互換（Ollama・LM Studio など） | エンジンによる | 手元の LLM を使える |

## 知っておいてほしいこと

- クラウドのエンジンを選ぶと、翻訳する文はそのサービスに送られます
- 内蔵 LLM は、llama.cpp（GitHub）とモデル（Hugging Face）をダウンロードし、どちらも SHA-256 で照合してから使います
- 送信の翻訳は最初は OFF です。「送信」タブで ON にします
- Hypixel とは関係のない、個人が作った mod です。サーバーのルールに合うかはご自身で確かめてください

詳しい使い方とコマンドは [README](https://github.com/aida0710/minecraft-java-mods/tree/main/chat-translator) にあります。

</div>
<div data-lang="en" markdown="1">

## Install

1. Get the jar for your loader from [Downloads](https://github.com/aida0710/minecraft-java-mods/releases): `chattranslator-forge-…jar` for Forge, `chattranslator-neoforge-…jar` for NeoForge, or `chattranslator-fabric-…jar` for Fabric. Fabric also needs [Fabric API](https://modrinth.com/mod/fabric-api)
2. Put the jar in `.minecraft/mods/` and start the game
3. Join a world or a server and open the settings with `/chattranslator`
4. Pick an engine on the "Engine" tab. For the built-in LLM, just press "Install" (about 2.5 GB; it only runs while you play). For a cloud service, paste your API key

## Translation engines

| Engine | Cost | Good for |
| --- | --- | --- |
| Built-in LLM (TranslateGemma 4B) | Free | Works offline. Needs about 4 GB of VRAM |
| Claude API | Pay as you go | Context and slang |
| DeepL / Google | Pay as you go (free tier) | Speed |
| OpenAI-compatible (Ollama, LM Studio, …) | Depends on the engine | Using an LLM you already run |

## Good to know

- With a cloud engine, the text being translated is sent to that service
- The built-in LLM downloads llama.cpp (GitHub) and a model (Hugging Face), and checks both with SHA-256 before use
- Translating sent messages is off by default. Turn it on in the "Sending" tab
- This is a personal project and is not affiliated with Hypixel. Check that it fits the rules of the server you play on

See the [README](https://github.com/aida0710/minecraft-java-mods/tree/main/chat-translator) for full usage and commands (in Japanese).

</div>
