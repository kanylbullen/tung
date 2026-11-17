// The landing page: Swedish alt texts, the MCP line, copy buttons and the weight ruler.
(function () {
  var swedish = window.tungLang === "sv";
  if (swedish) {
    document.querySelectorAll("[data-sv-alt]").forEach(function (el) { el.alt = el.getAttribute("data-sv-alt"); });
  }

  // A server of one's own shows its own address in the MCP line.
  if (location.protocol === "https:" && location.host !== "tung.xuper.fun") {
    document.getElementById("mcp-line").textContent = "claude mcp add --transport http tung " + location.origin + "/mcp";
  }

  // Copy buttons: the text of the code block they name.
  document.querySelectorAll("button.copy").forEach(function (copy) {
    var label = copy.textContent;
    copy.addEventListener("click", function () {
      var code = document.getElementById(copy.getAttribute("data-copy")).textContent;
      navigator.clipboard.writeText(code).then(function () {
        copy.textContent = swedish ? "Kopierad" : "Copied";
        setTimeout(function () { copy.textContent = label; }, 2000);
      });
    });
  });

  // The app's weight scale (core/Ruler.kt): 1 kg ticks from 0 to 30, then 2.5 kg, here up to 300.
  var values = [];
  for (var kg = 0; kg <= 30; kg++) values.push(kg);
  for (var w = 32.5; w <= 300; w += 2.5) values.push(w);
  var TICK = 12;                          // px between ticks, the same for every step, as in the app
  var area = document.getElementById("ruler-area");
  var strip = document.getElementById("strip");
  var input = document.getElementById("ruler");
  var weight = document.getElementById("weight");
  var still = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  input.max = String(values.length - 1);

  function text(v) {
    var s = Number.isInteger(v) ? String(v) : v.toFixed(1);
    return swedish ? s.replace(".", ",") : s;
  }
  // Ticks once: a longer one with its number every 10 kg (every 5 below 30).
  var html = "";
  values.forEach(function (v, i) {
    var major = v < 30 ? v % 5 === 0 : v % 10 === 0;
    html += '<div class="tick' + (major ? " major" : "") + '" style="left:' + (i * TICK) + 'px"></div>';
    if (major) html += '<div class="label" style="left:' + (i * TICK) + 'px">' + text(v) + "</div>";
  });
  strip.innerHTML = html;
  strip.style.width = (values.length * TICK) + "px";

  // The position is in pixels along the strip; the value is the tick nearest to it.
  var max = (values.length - 1) * TICK;
  var pos = values.indexOf(80) * TICK;
  var shown = -1;
  function clamp(x) { return Math.max(0, Math.min(max, x)); }
  function render() {
    strip.style.transform = "translateX(" + (area.clientWidth / 2 - pos) + "px)";
    var index = Math.round(pos / TICK);
    if (index !== shown) {
      shown = index;
      weight.textContent = text(values[index]);
      input.value = String(index);
      input.setAttribute("aria-valuetext", text(values[index]) + " kg");
    }
  }

  var frame = 0;
  function stop() { if (frame) cancelAnimationFrame(frame); frame = 0; }
  // Eases to a tick: what a release, a key or a tap ends with.
  function settle(target) {
    stop();
    target = clamp(Math.round(target / TICK) * TICK);
    if (still) { pos = target; render(); return; }
    var from = pos, start = performance.now(), duration = 220;
    (function step(now) {
      var t = Math.min(1, (now - start) / duration);
      pos = from + (target - from) * (1 - Math.pow(1 - t, 3));
      render();
      frame = t < 1 ? requestAnimationFrame(step) : 0;
    })(start);
  }
  // A flick carries on and slows down, then settles where it would stop.
  function glide(velocity) {
    stop();
    if (still || Math.abs(velocity) < 0.05) { settle(pos); return; }
    var last = performance.now();
    (function step(now) {
      var dt = Math.min(32, now - last); last = now;
      pos = clamp(pos - velocity * dt);
      velocity *= Math.pow(0.995, dt);
      render();
      if (Math.abs(velocity) < 0.05 || pos === 0 || pos === max) { frame = 0; settle(pos); return; }
      frame = requestAnimationFrame(step);
    })(last);
  }

  var dragging = false, startX = 0, startPos = 0, moved = 0, samples = [];
  area.addEventListener("pointerdown", function (e) {
    stop();
    dragging = true; moved = 0;
    startX = e.clientX; startPos = pos;
    samples = [{ x: e.clientX, t: e.timeStamp }];
    area.setPointerCapture(e.pointerId);
    area.classList.add("dragging");
  });
  area.addEventListener("pointermove", function (e) {
    if (!dragging) return;
    moved = Math.max(moved, Math.abs(e.clientX - startX));
    pos = clamp(startPos - (e.clientX - startX));
    samples.push({ x: e.clientX, t: e.timeStamp });
    if (samples.length > 6) samples.shift();
    render();
  });
  function release(e) {
    if (!dragging) return;
    dragging = false;
    area.classList.remove("dragging");
    if (moved < 4) {
      // A tap: to the tick under the finger, as the app's tap does.
      var rect = area.getBoundingClientRect();
      settle(pos + (e.clientX - rect.left - rect.width / 2));
      return;
    }
    var first = samples[0], lastSample = samples[samples.length - 1];
    var dt = lastSample.t - first.t;
    glide(dt > 0 && e.timeStamp - lastSample.t < 80 ? (lastSample.x - first.x) / dt : 0);
  }
  area.addEventListener("pointerup", release);
  area.addEventListener("pointercancel", release);
  // Keys and screen readers move the hidden range input; the strip follows.
  input.addEventListener("input", function () { settle(Number(input.value) * TICK); });
  area.addEventListener("click", function () { input.focus({ preventScroll: true }); });
  window.addEventListener("resize", render);
  render();
})();
