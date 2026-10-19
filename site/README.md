# 紹介サイト (GitHub Pages)

https://aida0710.github.io/minecraft-java-mods/ のソース。`main` の `site/` が変わると `.github/workflows/pages.yml` が Jekyll で組み立てて公開する。

## mod を足すとき

1. `_mods/<mod の id>.md` を作る。トップの一覧に自動で並び、`/<mod の id>/` にページができる
2. 画像と動画は `assets/<mod の id>/` に置く
3. 書き方は `_mods/chat-translator.md` を写す。先頭の項目 (front matter) の意味:

| 項目 | 内容 |
| --- | --- |
| `title` | mod の名前 |
| `order` | 一覧の並び順 (小さいほど先) |
| `color` | 一覧のカードの名前の色 (Minecraft のレアリティの色: `#FFFFFF` `#55FF55` `#5555FF` `#AA00AA` `#FFAA00` `#FF55FF` など) |
| `minecraft` / `loaders` | 対応する Minecraft の版とローダー |
| `source` | リポジトリの中の mod のフォルダ (ソースコードへのリンクに使う) |
| `thumbnail` | 一覧のカードの画像 (16:9) |
| `summary.ja` / `summary.en` | 1 文の説明 |
| `video` / `video_poster` | 紹介動画と、再生前に出す画像 (無ければ省く) |
| `demo` | ページの冒頭のチャット欄の動き (無ければ省く) |
| `features` | できることの一覧。画像と、日本語・英語の見出しと説明 |

本文 (front matter の後) は `<div data-lang="ja" markdown="1">` と `<div data-lang="en" markdown="1">` に分けて書く。画面の右上の「日本語 / English」で、選んだ方だけが表示される。

## 手元で確かめる

```bash
docker run --rm -v "$PWD/site:/srv" -w /srv -p 4000:4000 ruby:3.3 \
  sh -c "gem install --silent jekyll kramdown-parser-gfm webrick && jekyll serve --host 0.0.0.0"
```

http://localhost:4000/minecraft-java-mods/ で見られる。
