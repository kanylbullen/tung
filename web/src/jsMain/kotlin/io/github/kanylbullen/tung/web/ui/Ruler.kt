package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.github.kanylbullen.tung.core.RulerScale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLElement

/** Pixels between two ticks, the same for every step, as in the app. */
private const val TICK_PX = 12.0

/**
 * The app's ruler (`RulerPicker`) over core's [scale]: a strip of ticks that
 * slides under a fixed needle, follows the pointer pixel by pixel, carries on
 * after a flick and eases onto the nearest tick; a tap goes to
 * [RulerScale.jumpTarget] for the value under it. [onChange] gets every value
 * the needle passes, on the grid. A [value] set from outside (typed, −/+)
 * moves the strip without calling [onChange]: typing wins, the ruler never
 * writes back what was typed. Reduced motion settles at once.
 */
@Composable
fun Ruler(scale: RulerScale, value: Double, label: (Double) -> String, onChange: (Double) -> Unit) {
    val controller = remember(scale) { RulerController(scale, label) }
    controller.onChange = onChange
    LaunchedEffect(value) { controller.show(value) }
    Div({
        classes("ruler")
        attr("aria-hidden", "true")
        ref { element ->
            controller.attach(element)
            onDispose { controller.detach() }
        }
    })
    DisposableEffect(Unit) {
        val resize: (org.w3c.dom.events.Event) -> Unit = { controller.render() }
        window.addEventListener("resize", resize)
        onDispose { window.removeEventListener("resize", resize) }
    }
}

private class RulerController(private val scale: RulerScale, private val label: (Double) -> String) {
    var onChange: (Double) -> Unit = {}
    private var area: HTMLElement? = null
    private var strip: HTMLElement? = null
    private var pos = 0.0
    private var shownTick = -1
    private var frame = 0
    private var dragging = false
    private var startX = 0.0
    private var startPos = 0.0
    private var moved = 0.0
    private val samples = ArrayList<Pair<Double, Double>>()
    private val still = window.matchMedia("(prefers-reduced-motion: reduce)").matches
    private val max get() = scale.lastTick * TICK_PX

    fun attach(element: HTMLElement) {
        area = element
        val html = StringBuilder("<div class=\"strip\">")
        for (tick in 0..scale.lastTick) {
            val x = tick * TICK_PX
            val major = scale.isMajor(tick)
            html.append("<div class=\"tick").append(if (major) " major" else "").append("\" style=\"left:").append(x).append("px\"></div>")
            if (major) html.append("<div class=\"label\" style=\"left:").append(x).append("px\">").append(label(scale.valueAt(tick.toDouble()))).append("</div>")
        }
        html.append("</div><div class=\"needle\"></div>")
        element.innerHTML = html.toString()
        strip = element.firstElementChild as HTMLElement
        strip?.style?.width = "${(scale.lastTick + 1) * TICK_PX}px"
        element.addEventListener("pointerdown", { e -> down(e.asDynamic()) })
        element.addEventListener("pointermove", { e -> move(e.asDynamic()) })
        element.addEventListener("pointerup", { e -> up(e.asDynamic()) })
        element.addEventListener("pointercancel", { e -> up(e.asDynamic()) })
        render()
    }

    fun detach() {
        stop()
        area = null
        strip = null
    }

    /** A value from outside: the strip goes there, nothing is reported back. */
    fun show(value: Double) {
        if (dragging) return
        stop()
        pos = scale.positionOf(value) * TICK_PX
        shownTick = scale.snapTick(scale.positionOf(value))
        render()
    }

    fun render() {
        val area = area ?: return
        strip?.style?.transform = "translateX(${area.clientWidth / 2.0 - pos}px)"
    }

    private fun moved() {
        render()
        val tick = scale.snapTick(pos / TICK_PX)
        if (tick != shownTick) {
            shownTick = tick
            onChange(scale.valueAt(tick.toDouble()))
        }
    }

    private fun clamp(x: Double) = x.coerceIn(0.0, max)

    private fun stop() {
        if (frame != 0) window.cancelAnimationFrame(frame)
        frame = 0
    }

    private fun settle(target: Double) {
        stop()
        val goal = clamp(round(target / TICK_PX) * TICK_PX)
        if (still) {
            pos = goal
            moved()
            return
        }
        val from = pos
        val start = window.performance.now()
        fun step(now: Double) {
            val t = ((now - start) / 220.0).coerceAtMost(1.0)
            pos = from + (goal - from) * (1 - (1 - t).pow(3))
            moved()
            frame = if (t < 1) window.requestAnimationFrame(::step) else 0
        }
        frame = window.requestAnimationFrame(::step)
    }

    private fun glide(initial: Double) {
        stop()
        if (still || abs(initial) < 0.05) {
            settle(pos)
            return
        }
        var velocity = initial
        var last = window.performance.now()
        fun step(now: Double) {
            val dt = (now - last).coerceAtMost(32.0)
            last = now
            pos = clamp(pos - velocity * dt)
            velocity *= 0.995.pow(dt)
            moved()
            if (abs(velocity) < 0.05 || pos == 0.0 || pos == max) {
                frame = 0
                settle(pos)
                return
            }
            frame = window.requestAnimationFrame(::step)
        }
        frame = window.requestAnimationFrame(::step)
    }

    private fun down(e: dynamic) {
        stop()
        dragging = true
        moved = 0.0
        startX = e.clientX as Double
        startPos = pos
        samples.clear()
        samples += (e.clientX as Double) to (e.timeStamp as Double)
        (area?.asDynamic())?.setPointerCapture(e.pointerId)
        area?.classList?.add("dragging")
    }

    private fun move(e: dynamic) {
        if (!dragging) return
        val x = e.clientX as Double
        moved = maxOf(moved, abs(x - startX))
        pos = clamp(startPos - (x - startX))
        samples += x to (e.timeStamp as Double)
        if (samples.size > 6) samples.removeAt(0)
        moved()
    }

    private fun up(e: dynamic) {
        if (!dragging) return
        dragging = false
        area?.classList?.remove("dragging")
        val area = area ?: return
        if (moved < 4) {
            // A tap: to the nearest ten (five reps) under the finger, as the app's tap does.
            val rect = area.getBoundingClientRect()
            val under = pos + ((e.clientX as Double) - rect.left - rect.width / 2)
            val target = scale.jumpTarget(scale.valueAt(under / TICK_PX), scale.valueAt(pos / TICK_PX))
            settle(scale.positionOf(target) * TICK_PX)
            return
        }
        val first = samples.first()
        val last = samples.last()
        val dt = last.second - first.second
        val recent = (e.timeStamp as Double) - last.second < 80
        glide(if (dt > 0 && recent) (last.first - first.first) / dt else 0.0)
    }
}
