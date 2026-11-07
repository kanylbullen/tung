package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.checkServerUrl
import io.github.kanylbullen.tung.web.defaultServerUrl
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.sync.AuthFailure
import io.github.kanylbullen.tung.web.sync.AuthResult
import io.github.kanylbullen.tung.web.sync.ForgotResult
import io.github.kanylbullen.tung.core.sync.OtherAccountChoice
import io.github.kanylbullen.tung.core.sync.PendingSignIn
import io.github.kanylbullen.tung.web.sync.SignInMethods
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.AutoComplete
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.autoComplete
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Email and password against a tung-server, the phone's Settings → Account
 * when signed out: the default server is shown read-only under the fields,
 * "Use another server" turns it into a field, and a stored server other
 * than the default is a field from the start. "Create account" registers
 * with the same fields, as the phone's does. The first sync starts as soon
 * as the sign-in succeeds.
 */
@Composable
fun SignInScreen(container: WebContainer) {
    val strings = container.strings
    val storedServer = container.settings.lastServerUrl
    var email by remember { mutableStateOf(container.settings.lastEmail ?: "") }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(storedServer ?: defaultServerUrl()) }
    var editingServer by remember { mutableStateOf(storedServer != null && storedServer != defaultServerUrl()) }
    var busy by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember {
        mutableStateOf(if (container.signedOutByServer) strings.syncFailure(SyncFailure.SIGNED_OUT) else null)
    }
    val scope = rememberCoroutineScope()
    // Said once, right after "Delete account" here.
    val deleted = remember { container.accountDeleted.also { container.accountDeleted = false } }

    // Sign in with Google and Apple, when the page is served by the server it
    // signs in to: Google only runs its button on origins the server's client
    // id has authorised, and Apple only answers the Services ID's own domain
    // and Return URL, both the server's. The ids come from the server
    // (`/auth/methods`), never from the app.
    val sameOrigin = (checkServerUrl(server) as? ServerUrlCheck.Valid)?.url?.takeIf { it == window.location.origin }
    var methods by remember { mutableStateOf(SignInMethods(null, null)) }
    LaunchedEffect(sameOrigin) {
        methods = sameOrigin?.let { container.auth.methods(it) } ?: SignInMethods(null, null)
    }
    var appleReady by remember { mutableStateOf(false) }
    // Apple's window is open: one at a time.
    var applePopup by remember { mutableStateOf(false) }

    fun signInWithGoogle(url: String, idToken: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = container.signInWithGoogle(url, idToken)
            busy = false
            if (result is AuthResult.Failed) error = strings.authFailure(result.failure)
        }
    }

    fun signInWithApple(url: String, clientId: String) {
        if (busy || applePopup) return
        error = null
        applePopup = true
        signInWithApple(clientId) { outcome ->
            applePopup = false
            when (outcome) {
                is AppleOutcome.Token -> {
                    busy = true
                    scope.launch {
                        val result = container.signInWithApple(url, outcome.idToken)
                        busy = false
                        if (result is AuthResult.Failed) error = strings.authFailure(result.failure)
                    }
                }
                AppleOutcome.Closed -> Unit
                AppleOutcome.Failed -> error = strings.authFailure(AuthFailure.APPLE_FAILED)
            }
        }
    }

    /** "Forgot password?": a reset link to the address typed above. */
    fun forgot() {
        if (busy) return
        notice = null
        if (email.isBlank()) {
            error = strings.forgotNeedsEmail
            return
        }
        val url = when (val check = checkServerUrl(server)) {
            is ServerUrlCheck.Valid -> check.url
            ServerUrlCheck.Malformed -> {
                error = strings.errorUrl
                return
            }
            ServerUrlCheck.NeedsHttps -> {
                error = strings.errorUrlHttps
                return
            }
        }
        busy = true
        error = null
        scope.launch {
            val typed = email.trim()
            try {
                val result = container.forgotPassword(url, typed)
                if (result == ForgotResult.SENT) notice = strings.forgotResult(result, typed) else error = strings.forgotResult(result, typed)
            } finally {
                busy = false
            }
        }
    }

    fun submit(create: Boolean = false) {
        if (busy) return
        if (email.isBlank() || password.isEmpty()) {
            error = strings.errorFields
            return
        }
        val url = when (val check = checkServerUrl(server)) {
            is ServerUrlCheck.Valid -> check.url
            ServerUrlCheck.Malformed -> {
                error = strings.errorUrl
                return
            }
            ServerUrlCheck.NeedsHttps -> {
                error = strings.errorUrlHttps
                return
            }
        }
        busy = true
        creating = create
        error = null
        scope.launch {
            val result =
                if (create) container.register(url, email.trim(), password)
                else container.signIn(url, email.trim(), password)
            busy = false
            if (result is AuthResult.Failed) error = strings.authFailure(result.failure)
        }
    }

    // Another account's log is in this browser: nothing is stored or synced until this is answered (issue #152).
    val held by container.signIns.pending.collectAsState()
    held?.let { pending ->
        OtherAccountDialog(container, pending) { choice -> scope.launch { container.resolveOtherAccountData(choice) } }
    }

    Div({ classes("screen") }) {
        Div({ classes("wordmark") }) { Text(strings.appName) }
        P({ classes("intro") }) { Text(strings.signInIntro) }
        if (deleted) P({ classes("notice"); attr("role", "status") }) { Text(strings.accountDeleted) }
        val clientId = methods.google
        val appleId = methods.apple
        val providerUrl = sameOrigin
        if (clientId != null && providerUrl != null) {
            Div({
                classes("google")
                ref { element ->
                    loadGoogleIdentity(
                        ready = {
                            renderGoogleButton(element, clientId, strings.tag) { token -> signInWithGoogle(providerUrl, token) }
                        },
                        failed = { error = strings.authFailure(AuthFailure.GOOGLE_FAILED) },
                    )
                    onDispose { }
                }
            })
        }
        if (appleId != null && providerUrl != null) {
            Button({
                classes("apple")
                attr("type", "button")
                if (busy || applePopup || !appleReady) disabled()
                onClick { signInWithApple(providerUrl, appleId) }
                ref {
                    loadAppleSignIn(
                        strings.tag,
                        ready = { appleReady = true },
                        failed = { error = strings.authFailure(AuthFailure.APPLE_FAILED) },
                    )
                    onDispose { }
                }
            }) { Text(strings.continueWithApple) }
        }
        if (providerUrl != null && (clientId != null || appleId != null)) {
            P({ classes("google-hint") }) { Text(if (appleId != null) strings.signInWindowHint else strings.googleHint) }
            P({ classes("or") }) { Text(strings.orWithEmail) }
        }
        Form(attrs = {
            addEventListener("submit") {
                it.preventDefault()
                submit()
            }
        }) {
            Div({ classes("field") }) {
                Label(forId = "email") { Text(strings.email) }
                Input(InputType.Email) {
                    id("email")
                    value(email)
                    autoComplete(AutoComplete.username)
                    onInput { email = it.value }
                    if (busy) disabled()
                }
            }
            Div({ classes("field") }) {
                Label(forId = "password") { Text(strings.password) }
                Input(InputType.Password) {
                    id("password")
                    value(password)
                    autoComplete(AutoComplete.currentPassword)
                    onInput { password = it.value }
                    if (busy) disabled()
                }
            }
            if (editingServer) {
                Div({ classes("field") }) {
                    Label(forId = "server") { Text(strings.server) }
                    Input(InputType.Url) {
                        id("server")
                        value(server)
                        attr("placeholder", "https://tung.example.org")
                        attr("inputmode", "url")
                        attr("spellcheck", "false")
                        onInput { server = it.value }
                        if (busy) disabled()
                    }
                }
            } else {
                Div({ classes("field") }) {
                    Label { Text(strings.server) }
                    Div({ classes("readonly") }) { Text(server) }
                    Button({
                        classes("text-button")
                        attr("type", "button")
                        onClick { editingServer = true }
                    }) { Text(strings.useAnotherServer) }
                }
            }
            error?.let { message ->
                P({ classes("error"); attr("role", "alert") }) { Text(message) }
            }
            Button({
                classes("primary")
                if (busy) disabled()
            }) { Text(if (busy && !creating) strings.signingIn else strings.signIn) }
            Button({
                classes("secondary", "wide")
                attr("type", "button")
                if (busy) disabled()
                onClick { submit(create = true) }
            }) { Text(if (busy && creating) strings.creatingAccount else strings.createAccount) }
            Button({
                classes("text-button", "forgot")
                attr("type", "button")
                if (busy) disabled()
                onClick { forgot() }
            }) { Text(strings.forgotPassword) }
            notice?.let { P({ classes("hint"); attr("role", "status") }) { Text(it) } }
        }
    }
}

/**
 * "This device has workouts from another account" (issue #152), the phone's
 * dialog: delete the log in this browser, add it to the account that just
 * signed in, or (the backdrop, Escape) neither, which signs out again.
 * Neither button is the accent and Delete never has the focus: deleting is
 * not the default. Changes that never synced are counted, since deleting loses them.
 */
@Composable
private fun OtherAccountDialog(container: WebContainer, held: PendingSignIn, onChoose: (OtherAccountChoice?) -> Unit) {
    val strings = container.strings
    Div({ classes("sheet-backdrop"); onClick { onChoose(null) } })
    Div({
        classes("dialog")
        attr("role", "alertdialog")
        attr("aria-modal", "true")
        attr("aria-label", strings.otherAccountTitle)
        onKeyDown { if (it.key == "Escape") onChoose(null) }
    }) {
        H2 { Text(strings.otherAccountTitle) }
        P({ classes("hint") }) { Text(strings.otherAccountBody) }
        if (held.unsynced > 0) P { Text(strings.otherAccountUnsynced(held.unsynced)) }
        Div({ classes("dialog-actions") }) {
            Button({ classes("secondary"); onClick { onChoose(OtherAccountChoice.DELETE) } }) { Text(strings.otherAccountDelete) }
            Button({ classes("secondary"); attr("autofocus", ""); onClick { onChoose(OtherAccountChoice.ADD) } }) { Text(strings.otherAccountAdd(held.email)) }
        }
    }
}
