package io.github.kanylbullen.tung.web.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLScriptElement
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.js.Promise

/** Sign in with Apple JS in [locale] (`en_US`, `sv_SE`): the script Apple's popup comes from. */
private fun appleJsSrc(locale: String) = "https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/$locale/appleid.auth.js"

/** What Apple's popup gave: an ID token, the person closing it (nothing to say), or a failure. */
sealed interface AppleOutcome {
    data class Token(val idToken: String) : AppleOutcome
    data object Closed : AppleOutcome
    data object Failed : AppleOutcome
}

/**
 * Loads Sign in with Apple JS once and calls [ready] when `AppleID.auth` is
 * there, or [failed] when the script cannot load (blocked, offline). [tag] is
 * the app's language (`en`, `sv`).
 */
fun loadAppleSignIn(tag: String, ready: () -> Unit, failed: () -> Unit) {
    if (window.asDynamic().AppleID?.auth != null) {
        ready()
        return
    }
    val src = appleJsSrc(if (tag == "sv") "sv_SE" else "en_US")
    val existing = document.querySelector("script[src='$src']") as? HTMLScriptElement
    val script = existing ?: (document.createElement("script") as HTMLScriptElement).also {
        it.src = src
        it.async = true
        document.head?.appendChild(it)
    }
    script.addEventListener("load", { ready() })
    script.addEventListener("error", {
        // Gone again, so the next sign-in screen tries anew instead of waiting on a script that never loads.
        script.remove()
        failed()
    })
}

/** 128 random bits from the browser's CSPRNG, as hex. */
private fun randomState(): String {
    val bytes = Uint8Array(16)
    window.asDynamic().crypto.getRandomValues(bytes)
    return (0 until 16).joinToString("") { bytes[it].toInt().and(0xff).toString(16).padStart(2, '0') }
}

/**
 * Opens Apple's sign-in popup for [clientId] (the server's Services ID) and
 * hands [done] its outcome. Called straight from a click, so the popup is not
 * blocked. The popup answers by message, never a redirect, so the page and its
 * state stay; `redirectURI` must still be one of the Services ID's Return URLs,
 * and is the app's own address on the server (`/app/`). Only the email is
 * asked for: Tung keeps no names. The `state` is checked here, so an answer
 * meant for another page is dropped.
 */
fun signInWithApple(clientId: String, done: (AppleOutcome) -> Unit) {
    val state = randomState()
    val config: dynamic = js("({})")
    config.clientId = clientId
    config.scope = "email"
    config.redirectURI = window.location.origin + "/app/"
    config.state = state
    config.usePopup = true
    val auth = window.asDynamic().AppleID.auth
    try {
        auth.init(config)
        (auth.signIn() as Promise<dynamic>).then(
            { response: dynamic ->
                val token = response?.authorization?.id_token as? String
                val sameState = response?.authorization?.state as? String == state
                done(if (token != null && sameState) AppleOutcome.Token(token) else AppleOutcome.Failed)
            },
            { error: dynamic ->
                // Apple rejects with {error: "popup_closed_by_user"} or "user_cancelled_authorize": not a failure.
                val code = error?.error as? String
                done(if (code == "popup_closed_by_user" || code == "user_cancelled_authorize") AppleOutcome.Closed else AppleOutcome.Failed)
            },
        )
    } catch (e: Throwable) {
        done(AppleOutcome.Failed)
    }
}
