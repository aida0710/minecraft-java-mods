// mod のページの冒頭のチャット欄。届いた発言が、少し遅れて同じ行のまま訳文に変わる様子を流し続ける。
// ゲーム内と同じく、訳文にカーソルを合わせると原文が見え、行末の [原文] / [訳] で切り替えられる。
// 発言と表記は _mods/<mod>.md の demo をページに埋め込んだ JSON から読み、ページの言語 (html の lang) で選ぶ。
// 読んでいる途中で行が動かないよう、カーソルが欄の上にある間・ボタンを選んでいる間は次の発言を止める。
(function () {
  // 次の発言が届くまで。1 行を読み終える前に流れていかない長さ
  var ARRIVAL_INTERVAL_MS = 2600;
  // 原文が出てから訳文に変わるまで。同じ行が置き換わる様子を目で追える長さ
  var TRANSLATION_DELAY_MS = 1100;
  // 開いたときに空の欄を見せないよう、訳し終えた状態で並べておく発言の数
  var PREFILLED_LINES = 4;
  // 欄に残す行の数。見える行数は CSS の高さで決まり、これは見えなくなった古い行を捨てる上限
  var KEPT_LINES = 12;
  // 新しい行が下から押し上げる動きの長さ
  var SLIDE_MS = 220;
  // スマホで触ったあと、次の発言を止めておく長さ (触るとカーソルが外れたことになり、すぐ流れ出すため)
  var TOUCH_HOLD_MS = 5000;
  // ゲーム内の説明の枠と同じく、カーソルの右上に出す
  var TOOLTIP_OFFSET_PX = 12;
  var VIEWPORT_MARGIN_PX = 8;

  // Minecraft の文字色。ランクとチャンネルは Hypixel の表示に合わせる
  var WHITE = "#FFFFFF";
  var GRAY = "#AAAAAA";
  var RANK_COLORS = {
    "VIP": { name: "#55FF55", plus: "#55FF55" },
    "VIP+": { name: "#55FF55", plus: "#FFAA00" },
    "MVP": { name: "#55FFFF", plus: "#55FFFF" },
    "MVP+": { name: "#55FFFF", plus: "#FF5555" },
    "MVP++": { name: "#FFAA00", plus: "#FF5555" }
  };
  var CHANNELS = {
    party: { label: "Party > ", color: "#5555FF" },
    guild: { label: "Guild > ", color: "#00AA00" }
  };

  var reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /** "Party > [MVP+] Steve: " の部分を、色ごとの [文字, 色] に分ける */
  function prefixSegments(message) {
    var segments = [];
    var channel = CHANNELS[message.channel];
    if (channel) segments.push([channel.label, channel.color]);
    var rankColor = RANK_COLORS[message.rank];
    if (rankColor) {
      var pluses = message.rank.replace(/[^+]/g, "");
      segments.push(["[" + message.rank.replace(/\+/g, ""), rankColor.name]);
      if (pluses) segments.push([pluses, rankColor.plus]);
      segments.push(["] " + message.name, rankColor.name]);
    } else {
      segments.push([message.name, WHITE]);
    }
    segments.push([": ", WHITE]);
    return segments;
  }

  function appendSegments(parent, segments) {
    segments.forEach(function (segment) {
      var span = document.createElement("span");
      span.textContent = segment[0];
      span.style.color = segment[1];
      parent.appendChild(span);
    });
  }

  /** 画面に 1 つだけ置く、ゲーム内の説明と同じ形の枠 */
  function createTooltip() {
    var element = document.createElement("div");
    element.className = "chat-tooltip tooltip-box";
    element.hidden = true;
    element.setAttribute("aria-hidden", "true");
    document.body.appendChild(element);
    var shownFor = null;

    function show(target, segments, position) {
      element.textContent = "";
      appendSegments(element, segments);
      element.hidden = false;
      shownFor = target;
      // 前の位置のままだと、画面の右端で幅が縮んだ大きさを測ってしまう
      element.style.left = "0px";
      element.style.top = "0px";
      var width = element.offsetWidth;
      var height = element.offsetHeight;
      var left = position.x + TOOLTIP_OFFSET_PX;
      // 右にはみ出すなら、ゲーム内と同じくカーソルの左に出す
      if (left + width > window.innerWidth - VIEWPORT_MARGIN_PX) left = position.x - TOOLTIP_OFFSET_PX - width;
      // above: 指で押した行を隠さないよう、行の上に出す
      var top = position.above ? position.y - VIEWPORT_MARGIN_PX - height : position.y - TOOLTIP_OFFSET_PX;
      top = Math.min(top, window.innerHeight - VIEWPORT_MARGIN_PX - height);
      element.style.left = Math.max(VIEWPORT_MARGIN_PX, left) + "px";
      element.style.top = Math.max(VIEWPORT_MARGIN_PX, top) + "px";
    }

    function hide() {
      element.hidden = true;
      shownFor = null;
    }

    return {
      show: show,
      hide: hide,
      isShownFor: function (target) { return shownFor === target; },
      isShownInside: function (container) { return shownFor !== null && container.contains(shownFor); }
    };
  }

  function startChatDemo(figure) {
    var data = JSON.parse(figure.querySelector(".chat-demo-data").textContent);
    var box = figure.querySelector(".chat-box");
    var list = figure.querySelector(".chat-lines");
    var tooltip = createTooltip();
    var lineByElement = new WeakMap();
    var language = null;
    var ui = null;
    var messages = [];
    var nextIndex = 0;
    var arrivalTimer = null;
    var hovering = false;
    var focused = false;
    var holdUntil = 0;
    var lastPointer = { x: 0, y: 0 };

    function isPaused() {
      return hovering || focused || Date.now() < holdUntil;
    }

    /** 行の状態 (arriving: 訳を待っている / translated: 訳文 / original: 原文に戻した) を画面に出す */
    function render(line) {
      line.body.textContent = line.state === "translated" ? line.message.translated : line.message.original;
      line.button.hidden = line.state === "arriving";
      line.button.textContent = line.state === "translated" ? ui.show_original : ui.show_translation;
    }

    /** カーソルを合わせた場所に出す説明。訳文の上なら原文、ボタンの上なら押すと何が起きるか */
    function tooltipSegments(target) {
      var line = lineByElement.get(target.closest(".chat-line"));
      if (!line) return null;
      if (target === line.button) {
        return [[line.state === "translated" ? ui.show_original_tooltip : ui.show_translation_tooltip, WHITE]];
      }
      if (target === line.body && line.state === "translated") {
        return [[ui.original_prefix, GRAY]].concat(prefixSegments(line.message), [[line.message.original, WHITE]]);
      }
      return null;
    }

    function showTooltipAt(target, position) {
      var segments = target && tooltipSegments(target);
      if (segments) tooltip.show(target, segments, position);
      else tooltip.hide();
    }

    function createLine(message, state) {
      var element = document.createElement("li");
      element.className = "chat-line";
      var prefix = document.createElement("span");
      appendSegments(prefix, prefixSegments(message));
      var body = document.createElement("span");
      body.className = "chat-body";
      var button = document.createElement("button");
      button.type = "button";
      button.className = "chat-button";
      element.append(prefix, body, " ", button);
      var line = { message: message, element: element, body: body, button: button, state: state };
      lineByElement.set(element, line);
      render(line);
      return line;
    }

    /** 増えた行の高さの分だけ欄を下げてから戻し、下から押し上げたように見せる */
    function slideUp(addedHeight) {
      list.style.transition = "none";
      list.style.transform = "translateY(" + addedHeight + "px)";
      list.getBoundingClientRect(); // 動き出す前の位置を確定させる
      list.style.transition = "transform " + SLIDE_MS + "ms ease-out";
      list.style.transform = "";
    }

    function dropOldLines() {
      while (list.children.length > KEPT_LINES) {
        var oldest = list.firstElementChild;
        if (tooltip.isShownInside(oldest)) tooltip.hide();
        list.removeChild(oldest);
      }
    }

    function addMessage(message, options) {
      var line = createLine(message, options.translated ? "translated" : "arriving");
      list.appendChild(line.element);
      if (options.animate) slideUp(line.element.offsetHeight);
      dropOldLines();
      if (options.translated) return;
      setTimeout(function () {
        if (!line.element.isConnected) return;
        line.state = "translated";
        render(line);
        line.body.classList.add("just-translated");
      }, TRANSLATION_DELAY_MS);
    }

    function nextMessage() {
      var message = messages[nextIndex];
      nextIndex = (nextIndex + 1) % messages.length;
      return message;
    }

    function scheduleArrival() {
      arrivalTimer = setTimeout(function () {
        // 止めている間は届けず、次の機会に回す
        if (!isPaused()) addMessage(nextMessage(), { translated: false, animate: true });
        scheduleArrival();
      }, ARRIVAL_INTERVAL_MS);
    }

    /** 言語を変えたら、その言語の発言で最初から流し直す */
    function restart(nextLanguage) {
      if (nextLanguage === language) return;
      language = nextLanguage;
      ui = data.ui[language] || data.ui.en;
      messages = data.lines[language] || data.lines.en;
      nextIndex = 0;
      clearTimeout(arrivalTimer);
      tooltip.hide();
      list.textContent = "";
      // 動きを減らす設定なら、訳し終えた行を並べるだけにする (カーソルとボタンの動きは残す)
      var prefilled = reducedMotion ? Math.min(messages.length, KEPT_LINES) : PREFILLED_LINES;
      for (var i = 0; i < prefilled; i++) addMessage(nextMessage(), { translated: true, animate: false });
      if (!reducedMotion) scheduleArrival();
    }

    box.addEventListener("pointerenter", function (event) {
      if (event.pointerType !== "touch") hovering = true;
    });
    box.addEventListener("pointerleave", function (event) {
      if (event.pointerType === "touch") return;
      hovering = false;
      tooltip.hide();
    });
    box.addEventListener("pointermove", function (event) {
      if (event.pointerType === "touch") return;
      lastPointer = { x: event.clientX, y: event.clientY };
      showTooltipAt(event.target.closest(".chat-body, .chat-button"), lastPointer);
    });
    // スマホではカーソルを合わせられないので、訳文を押したら原文を出す
    box.addEventListener("pointerdown", function (event) {
      if (event.pointerType !== "touch") return;
      holdUntil = Date.now() + TOUCH_HOLD_MS;
      var target = event.target.closest(".chat-body");
      if (!target || tooltip.isShownFor(target)) {
        tooltip.hide();
        return;
      }
      showTooltipAt(target, { x: event.clientX, y: target.getBoundingClientRect().top, above: true });
    });
    document.addEventListener("pointerdown", function (event) {
      if (!box.contains(event.target)) tooltip.hide();
    });
    box.addEventListener("click", function (event) {
      var button = event.target.closest(".chat-button");
      if (!button) return;
      var line = lineByElement.get(button.closest(".chat-line"));
      line.state = line.state === "translated" ? "original" : "translated";
      render(line);
      if (tooltip.isShownFor(button) || tooltip.isShownFor(line.body)) {
        var rect = button.getBoundingClientRect();
        var position = event.detail === 0 ? { x: rect.right, y: rect.top } : lastPointer;
        showTooltipAt(button, position);
      }
    });
    // キーボードでボタンを選んだときだけ止める (マウスで押したあとも選んだままになり、流れが止まり続けないように)
    box.addEventListener("focusin", function (event) {
      focused = event.target.matches(":focus-visible");
      if (!focused) return;
      var rect = event.target.getBoundingClientRect();
      showTooltipAt(event.target.closest(".chat-button"), { x: rect.right, y: rect.top });
    });
    box.addEventListener("focusout", function (event) {
      focused = false;
      if (!box.contains(event.relatedTarget) && !hovering) tooltip.hide();
    });
    window.addEventListener("scroll", tooltip.hide, { passive: true });
    window.addEventListener("resize", tooltip.hide);

    new MutationObserver(function () {
      restart(document.documentElement.lang);
    }).observe(document.documentElement, { attributes: true, attributeFilter: ["lang"] });

    figure.hidden = false;
    restart(document.documentElement.lang);
  }

  document.querySelectorAll("[data-chat-demo]").forEach(startChatDemo);
})();
