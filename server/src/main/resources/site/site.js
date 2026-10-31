// Shared by the landing page and the privacy page: which language to show.
// The footer's switch (?lang=sv or ?lang=en) wins and is remembered; else the
// browser's first language decides, Swedish for sv*, English otherwise, as in
// the app. Elements carry their Swedish in data-sv (text) and data-sv-aria
// (aria-label); a long text has a block per language with data-lang.
(function () {
  var param = new URLSearchParams(location.search).get("lang");
  var stored = null;
  try {
    if (param === "sv" || param === "en") localStorage.setItem("tung-lang", param);
    stored = localStorage.getItem("tung-lang");
  } catch (e) { /* private mode: no memory, the link still works */ }
  var browser = (navigator.languages && navigator.languages[0] || navigator.language || "").toLowerCase();
  var lang = param === "sv" || param === "en" ? param
    : stored === "sv" || stored === "en" ? stored
    : (browser === "sv" || browser.indexOf("sv-") === 0 ? "sv" : "en");
  window.tungLang = lang;
  document.documentElement.lang = lang;
  if (lang === "sv") {
    var title = document.querySelector("meta[name=title-sv]");
    if (title) document.title = title.content;
    document.querySelectorAll("[data-sv]").forEach(function (el) { el.textContent = el.getAttribute("data-sv"); });
    document.querySelectorAll("[data-sv-aria]").forEach(function (el) { el.setAttribute("aria-label", el.getAttribute("data-sv-aria")); });
  }
  document.querySelectorAll("[data-lang]").forEach(function (el) { el.hidden = el.getAttribute("data-lang") !== lang; });
  // The switch names the other language, in that language.
  document.querySelectorAll("a.lang").forEach(function (a) {
    var other = lang === "sv" ? "en" : "sv";
    a.textContent = other === "sv" ? "Svenska" : "English";
    a.setAttribute("lang", other);
    a.href = "?lang=" + other;
  });
})();

// Swedish alt texts for the screens.
(function () {
  if (window.tungLang !== "sv") return;
  document.querySelectorAll("[data-sv-alt]").forEach(function (el) { el.setAttribute("alt", el.getAttribute("data-sv-alt")); });
})();

// The theme toggle: the page follows the system until the toggle picks the
// other theme; picking the system's own theme again forgets the choice.
(function () {
  var button = document.querySelector(".theme-toggle");
  if (!button) return;
  var root = document.documentElement;
  var media = window.matchMedia("(prefers-color-scheme: dark)");
  var sv = window.tungLang === "sv";
  function system() { return media.matches ? "dark" : "light"; }
  function current() { return root.getAttribute("data-theme") || system(); }
  function show() {
    var dark = current() === "dark";
    button.setAttribute("aria-pressed", String(dark));
    button.setAttribute("aria-label", dark
      ? (sv ? "Byt till ljust tema" : "Switch to the light theme")
      : (sv ? "Byt till mörkt tema" : "Switch to the dark theme"));
    button.title = button.getAttribute("aria-label");
    document.querySelectorAll("meta[name=theme-color]").forEach(function (meta) {
      meta.setAttribute("content", dark ? "#1A1D21" : "#F6F6F4");
    });
  }
  button.addEventListener("click", function () {
    var next = current() === "dark" ? "light" : "dark";
    try {
      if (next === system()) {
        root.removeAttribute("data-theme");
        localStorage.removeItem("tung-theme");
      } else {
        root.setAttribute("data-theme", next);
        localStorage.setItem("tung-theme", next);
      }
    } catch (e) {
      root.setAttribute("data-theme", next);
    }
    show();
  });
  media.addEventListener("change", show);
  show();
})();
