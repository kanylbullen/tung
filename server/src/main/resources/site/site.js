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
