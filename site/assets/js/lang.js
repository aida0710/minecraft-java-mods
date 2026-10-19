// 日本語 / English の切り替え。data-lang="ja" / "en" の要素のうち、選んだ言語のものだけを CSS で表示する。
// 選んだ言語はこのブラウザに覚えておく (保存できない環境では、毎回ブラウザの言語に合わせる)。
// URL の ?lang=ja / ?lang=en は、覚えている言語より優先する (言語を決めてリンクを渡すため)
(function () {
  var STORAGE_KEY = "lang";
  var SUPPORTED = ["ja", "en"];

  function saved() {
    try {
      return localStorage.getItem(STORAGE_KEY);
    } catch (e) {
      return null;
    }
  }

  function fromUrl() {
    var match = /[?&]lang=(ja|en)\b/.exec(location.search);
    return match ? match[1] : null;
  }

  function initial() {
    var lang = fromUrl() || saved();
    if (SUPPORTED.indexOf(lang) >= 0) return lang;
    return (navigator.language || "en").toLowerCase().indexOf("ja") === 0 ? "ja" : "en";
  }

  function apply(lang) {
    document.documentElement.lang = lang;
    var buttons = document.querySelectorAll("[data-set-lang]");
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].setAttribute("aria-pressed", String(buttons[i].getAttribute("data-set-lang") === lang));
    }
  }

  apply(initial());

  document.addEventListener("DOMContentLoaded", function () {
    apply(document.documentElement.lang);
    document.addEventListener("click", function (event) {
      var button = event.target.closest("[data-set-lang]");
      if (!button) return;
      var lang = button.getAttribute("data-set-lang");
      try {
        localStorage.setItem(STORAGE_KEY, lang);
      } catch (e) {
        // 保存できなくても、この画面の切り替えはできる
      }
      apply(lang);
    });
  });
})();
