// Loaded in <head>, before the page paints: the theme chosen with the
// header's toggle (remembered), else none, so the system's theme applies.
(function () {
  try {
    var theme = localStorage.getItem("tung-theme");
    if (theme === "light" || theme === "dark") document.documentElement.setAttribute("data-theme", theme);
  } catch (e) { /* private mode: the system's theme */ }
})();
