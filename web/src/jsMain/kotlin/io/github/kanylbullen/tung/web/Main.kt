package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.web.i18n.stringsFor
import io.github.kanylbullen.tung.web.ui.App
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.renderComposable

fun main() {
    val language = window.navigator.language
    val strings = stringsFor(language)
    document.documentElement?.setAttribute("lang", strings.tag)
    val scope = MainScope()
    val container = WebContainer(strings, language, scope)
    registerServiceWorker()
    renderComposable(rootElementId = "root") { App(container) }
    scope.launch { container.start() }
}

/** The app shell's cache (sw.js). A browser without service workers runs the app online only. */
private fun registerServiceWorker() {
    if (js("'serviceWorker' in navigator") as Boolean) {
        window.navigator.serviceWorker.register("sw.js").catch { console.warn("Service worker not registered", it) }
    }
}
