package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.feedback.FeedbackKind
import io.github.kanylbullen.tung.web.feedback.FeedbackResult
import io.github.kanylbullen.tung.web.feedback.MAX_FEEDBACK_IMAGES
import io.github.kanylbullen.tung.web.feedback.MAX_FEEDBACK_MESSAGE
import io.github.kanylbullen.tung.web.feedback.screenshotDataUrl
import io.github.kanylbullen.tung.web.feedback.webBuildId
import io.github.kanylbullen.tung.web.sync.Account
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.maxLength
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.w3c.dom.events.Event
import org.w3c.files.Blob

private const val FILE_INPUT_ID = "feedback-file"

/**
 * Settings → Send feedback: a bug or an idea in the user's words, with up to
 * three screenshots, pasted (Ctrl+V anywhere on the page) or picked. They are
 * scaled down when large ([screenshotDataUrl]) and sent with the account's
 * token to `POST /api/v1/feedback`, where the server's admins read them.
 */
@Composable
fun FeedbackScreen(container: WebContainer, account: Account) {
    val strings = container.strings
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(FeedbackKind.BUG) }
    var message by remember { mutableStateOf("") }
    val images = remember { mutableStateListOf<String>() }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, is it an error
    // Screenshots being read: counted at once, so two quick pastes cannot pass the limit together.
    var pending by remember { mutableStateOf(0) }

    fun add(blobs: List<Blob>) {
        if (sending) return
        val taken = blobs.take((MAX_FEEDBACK_IMAGES - images.size - pending).coerceAtLeast(0))
        if (taken.isEmpty()) return
        pending += taken.size
        scope.launch {
            for (blob in taken) {
                val url = screenshotDataUrl(blob)
                pending--
                if (url == null) status = strings.feedbackUnreadable to true else images.add(url)
            }
        }
    }

    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { event ->
            val items = event.asDynamic().clipboardData?.items
            val blobs = mutableListOf<Blob>()
            if (items != null) {
                val count = (items.length as Number).toInt()
                for (i in 0 until count) {
                    val item = items[i]
                    if (item.kind == "file" && (item.type as String).startsWith("image/")) {
                        (item.getAsFile() as? Blob)?.let(blobs::add)
                    }
                }
            }
            if (blobs.isNotEmpty()) {
                event.preventDefault()
                add(blobs)
            }
        }
        window.addEventListener("paste", listener)
        onDispose { window.removeEventListener("paste", listener) }
    }

    Div({ classes("screen") }) {
        Div({ classes("topbar", "topbar-start") }) {
            A(href = Route.SETTINGS.hash, attrs = { classes("text-button"); attr("aria-label", strings.settings) }) { Text("←") }
            H1({ classes("detail-title") }) { Text(strings.sendFeedback) }
        }

        Div({ classes("chips"); attr("role", "radiogroup"); attr("aria-label", strings.sendFeedback) }) {
            listOf(FeedbackKind.BUG to strings.feedbackBug, FeedbackKind.IDEA to strings.feedbackIdea).forEach { (value, label) ->
                Button({
                    classes("chip")
                    if (kind == value) classes("on")
                    attr("role", "radio")
                    attr("aria-checked", (kind == value).toString())
                    onClick { kind = value }
                }) { Text(label) }
            }
        }

        Label(forId = "feedback-message", attrs = { classes("feedback-label") }) { Text(strings.feedbackMessageLabel) }
        TextArea(value = message) {
            id("feedback-message")
            if (sending) disabled()
            classes("feedback-text")
            maxLength(MAX_FEEDBACK_MESSAGE)
            attr("rows", "6")
            onInput { message = it.value; status = null }
        }

        P({ classes("hint") }) { Text(strings.feedbackScreenshotsHint) }
        if (images.isNotEmpty()) {
            Div({ classes("thumbs") }) {
                images.forEachIndexed { index, url ->
                    Div({ classes("thumb") }) {
                        Img(src = url, alt = strings.screenshotAlt(index + 1))
                        Button({
                            classes("thumb-remove")
                            if (sending) disabled()
                            attr("aria-label", strings.removeScreenshot(index + 1))
                            onClick { images.removeAt(index) }
                        }) { Text("×") }
                    }
                }
            }
            P({ classes("hint") }) { Text(strings.feedbackScreenshotNotice) }
        }

        Input(InputType.File) {
            id(FILE_INPUT_ID)
            classes("hidden-input")
            attr("accept", "image/png,image/jpeg,image/webp,image/*")
            attr("multiple", "")
            onChange { event ->
                val input = event.target.asDynamic()
                val files = input.files
                val blobs = mutableListOf<Blob>()
                if (files != null) for (i in 0 until (files.length as Number).toInt()) blobs.add(files[i].unsafeCast<Blob>())
                add(blobs)
                input.value = ""
            }
        }

        Div({ classes("setting-buttons", "pad") }) {
            Button({
                classes("secondary")
                if (sending || images.size + pending >= MAX_FEEDBACK_IMAGES) disabled()
                onClick { document.getElementById(FILE_INPUT_ID).asDynamic().click() }
            }) { Text(strings.addScreenshot) }
            Button({
                classes("accent-button")
                if (sending || pending > 0 || message.isBlank()) disabled()
                onClick { send(scope, container, account, kind, message, images.toList()) { sendingNow, result ->
                    sending = sendingNow
                    if (result == FeedbackResult.SENT) {
                        message = ""
                        images.clear()
                    }
                    // As a 401 during sync: the session is over, so the page goes to sign-in.
                    if (result == FeedbackResult.SIGNED_OUT) scope.launch { container.signOut() }
                    status = result?.let { feedbackStatus(it, container) }
                } }
            }) { Text(if (sending) strings.sending else strings.send) }
        }

        status?.let { (text, error) ->
            P({ classes(if (error) "error" else "hint"); attr("role", if (error) "alert" else "status") }) { Span { Text(text) } }
        }
    }
}

private fun feedbackStatus(result: FeedbackResult, container: WebContainer): Pair<String, Boolean> {
    val strings = container.strings
    return when (result) {
        FeedbackResult.SENT -> strings.feedbackSent to false
        FeedbackResult.TOO_LARGE -> strings.feedbackTooLarge to true
        FeedbackResult.TOO_MANY_TODAY -> strings.feedbackTooManyToday to true
        FeedbackResult.SIGNED_OUT -> strings.feedbackSignedOut to true
        FeedbackResult.NETWORK, FeedbackResult.FAILED -> strings.feedbackFailed to true
    }
}

private fun send(
    scope: CoroutineScope,
    container: WebContainer,
    account: Account,
    kind: FeedbackKind,
    message: String,
    images: List<String>,
    update: (sending: Boolean, result: FeedbackResult?) -> Unit,
) {
    val token = container.settings.token ?: return update(false, FeedbackResult.SIGNED_OUT)
    update(true, null)
    scope.launch {
        val result = container.feedback.send(account.serverUrl, token, kind, message, images, webBuildId())
        update(false, result)
    }
}
