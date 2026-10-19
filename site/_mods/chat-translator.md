---
title: Chat Translator
order: 1
# 公開している最新の版。Release のタグ (chat-translator-v<版>) とダウンロードの行き先に使う
version: "0.2.0"
icon: /assets/chat-translator/icon.svg
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
# ページの冒頭のチャット欄に流す発言 (assets/js/chat-demo.js が使う)
demo:
  # ボタンと説明の表記。ゲーム内と同じ (chat-translator の lang ファイルの chattranslator.chat.*)
  ui:
    ja:
      show_original: "[原文]"
      show_original_tooltip: "クリックで原文を表示"
      show_translation: "[訳]"
      show_translation_tooltip: "クリックで訳文を表示"
      original_prefix: "原文: "
    en:
      show_original: "[Original]"
      show_original_tooltip: "Click to show the original text"
      show_translation: "[Translated]"
      show_translation_tooltip: "Click to show the translation"
      original_prefix: "Original: "
  # 届く順に流す (訳は例)。ja は日本語で見ている人向けに英語を日本語へ、en は英語で見ている人向けにいろいろな言語を英語へ。
  # channel は party / guild (書かなければ全体のチャット)、rank は VIP / VIP+ / MVP / MVP+ / MVP++
  lines:
    ja:
      - { rank: "MVP+", name: "Steve", original: "anyone want to do dungeons? we need a healer", translated: "ダンジョンに行く人はいますか？ ヒーラーを探しています。" }
      - { channel: party, rank: "VIP", name: "Alex", original: "ready whenever you are", translated: "いつでも行けます" }
      - { rank: "MVP++", name: "Sofia", original: "does anyone know where the Dwarven Mines entrance is?", translated: "Dwarven Mines の入り口がどこか知っている人はいますか？" }
      - { channel: guild, rank: "MVP+", name: "Minji", original: "gg everyone, thanks for the carry", translated: "みんなお疲れさま、キャリーありがとう" }
      - { rank: "VIP+", name: "Lucas", original: "selling Aspect of the End, 200k each", translated: "Aspect of the End を 1 本 20 万で売っています" }
      - { rank: "MVP+", name: "Wei", original: "finally got my first dragon pet!!", translated: "ついに初めてのドラゴンのペットを手に入れた！！" }
      - { channel: party, rank: "MVP++", name: "Hika", original: "let's start, everyone get in the portal", translated: "始めよう、みんなポータルに入って" }
      - { rank: "VIP", name: "Chloe", original: "how do you unlock the Crystal Hollows?", translated: "Crystal Hollows はどうやって解放するの？" }
      - { channel: guild, rank: "MVP+", name: "Yuki", original: "welcome back! long time no see", translated: "おかえり！ 久しぶり" }
      - { rank: "MVP", name: "Mateo", original: "is the bazaar down or is it just me?", translated: "バザーが落ちてるのかな、それとも自分だけ？" }
    en:
      - { rank: "MVP+", name: "Hika", original: "ダンジョン行く人いる？ヒーラー募集中", translated: "Anyone going to dungeons? Looking for a healer" }
      - { channel: party, rank: "VIP", name: "Lukas", original: "Bin bereit, wann ihr wollt", translated: "I'm ready whenever you are" }
      - { rank: "MVP++", name: "Sofía", original: "¿alguien sabe dónde está la entrada a Dwarven Mines?", translated: "Does anyone know where the Dwarven Mines entrance is?" }
      - { channel: guild, rank: "MVP+", name: "Minji", original: "다들 수고했어요, 캐리 고마워요", translated: "Good work everyone, thanks for the carry" }
      - { rank: "VIP+", name: "Lucas", original: "vendo Aspect of the End, 200k cada", translated: "Selling Aspect of the End, 200k each" }
      - { rank: "MVP+", name: "Wei", original: "终于拿到第一只龙宠物了！！", translated: "Finally got my first dragon pet!!" }
      - { channel: party, rank: "MVP++", name: "Hika", original: "始めよう、みんなポータルに入って", translated: "Let's start, everyone get in the portal" }
      - { rank: "VIP", name: "Chloé", original: "comment on débloque les Crystal Hollows ?", translated: "How do you unlock the Crystal Hollows?" }
      - { channel: guild, rank: "MVP+", name: "Yuki", original: "おかえり！久しぶり", translated: "Welcome back! Long time no see" }
      - { rank: "MVP", name: "Mateo", original: "¿el bazar está caído o solo soy yo?", translated: "Is the bazaar down or is it just me?" }
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
