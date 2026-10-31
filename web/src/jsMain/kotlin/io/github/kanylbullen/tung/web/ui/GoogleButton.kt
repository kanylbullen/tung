package io.github.kanylbullen.tung.web.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLScriptElement

/** Google Identity Services, the script Google's own button comes from. */
private const val GIS_SRC = "https://accounts.google.com/gsi/client"

/**
 * Loads Google Identity Services once and calls [ready] when `google.accounts.id`
 * is there, or [failed] when the script cannot load (blocked, offline).
 */
fun loadGoogleIdentity(ready: () -> Unit, failed: () -> Unit) {
    if (window.asDynamic().google?.accounts?.id != null) {
        ready()
        return
    }
    val existing = document.querySelector("script[src='$GIS_SRC']") as? HTMLScriptElement
    val script = existing ?: (document.createElement("script") as HTMLScriptElement).also {
        it.src = GIS_SRC
        it.async = true
        it.defer = true
        document.head?.appendChild(it)
    }
    script.addEventListener("load", { ready() })
    script.addEventListener("error", { failed() })
}

/**
 * Renders Google's "Sign in with Google" button into [container] for [clientId]:
 * a popup, never a redirect, so the app's page and its state stay. [onCredential]
 * gets the ID token for `POST /api/v1/auth/google`. [locale] is the button's
 * language. The button is Google's white one on both themes: on the dark page
 * the black one read as a strip of account details, not as a button (Google
 * personalises it, "Continue as Johan", when the browser has a Google session).
 */
fun renderGoogleButton(container: HTMLElement, clientId: String, locale: String, onCredential: (String) -> Unit) {
    val id = window.asDynamic().google.accounts.id
    val config: dynamic = js("({})")
    config.client_id = clientId
    config.ux_mode = "popup"
    config.auto_select = false
    config.callback = { response: dynamic -> (response.credential as? String)?.let(onCredential) }
    id.initialize(config)
    val options: dynamic = js("({})")
    options.type = "standard"
    options.theme = "outline"
    options.size = "large"
    options.text = "continue_with"
    options.logo_alignment = "left"
    options.shape = "rectangular"
    options.locale = locale
    options.width = minOf(400, container.offsetWidth.takeIf { it > 0 } ?: 320)
    container.innerHTML = ""
    id.renderButton(container, options)
}
