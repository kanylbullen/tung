package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.admin.AdminResult
import io.github.kanylbullen.tung.web.admin.FeedbackItem
import io.github.kanylbullen.tung.web.admin.FeedbackList
import io.github.kanylbullen.tung.web.sync.Account
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.url.URL

/**
 * Server admin's feedback: newest first, each with who sent it, from where,
 * its words and its screenshots (fetched with the token, so they are object
 * URLs, opened full size in a new tab on a click), and Mark resolved /
 * Reopen and Delete. A resolved one is muted.
 */
@Composable
fun FeedbackSection(container: WebContainer, account: Account, now: Long) {
    val strings = container.strings
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<FeedbackList?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var deleting by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(account, reload) {
        val token = container.settings.token
        if (token == null) {
            failed = true
            return@LaunchedEffect
        }
        when (val result = container.admin.feedback(account.serverUrl, token)) {
            is AdminResult.Ok -> { list = result.value; failed = false }
            else -> failed = true
        }
    }

    fun act(block: suspend (String) -> Boolean) {
        val token = container.settings.token ?: return
        scope.launch {
            if (block(token)) reload++ else failed = true
        }
    }

    val current = list
    H2({ classes("section") }) { Text(strings.feedbackHeading(current?.openCount ?: 0)) }
    if (failed) P({ classes("error"); attr("role", "alert") }) { Text(strings.adminFailed) }
    when {
        current == null -> if (!failed) P({ classes("hint") }) { Text(strings.loading) }
        current.feedback.isEmpty() -> P({ classes("hint") }) { Text(strings.feedbackEmpty) }
        else -> Ul({ classes("rows") }) {
            current.feedback.forEach { item ->
                key(item.id) {
                    Li({ classes("feedback-item"); if (item.resolvedAt != null) classes("resolved") }) {
                        FeedbackRow(item, container, account, now)
                        Div({ classes("setting-buttons") }) {
                            Button({
                                classes("secondary")
                                onClick { act { token -> container.admin.setResolved(account.serverUrl, token, item.id, item.resolvedAt == null) } }
                            }) { Text(if (item.resolvedAt == null) strings.markResolved else strings.reopen) }
                            Button({ classes("text-button"); onClick { deleting = item.id } }) { Text(strings.delete) }
                        }
                    }
                }
            }
        }
    }

    val id = deleting
    if (id != null) {
        Dialog(
            title = strings.deleteFeedbackTitle,
            body = strings.deleteFeedbackBody,
            confirm = strings.delete,
            cancel = strings.cancel,
            onCancel = { deleting = null },
            onConfirm = {
                deleting = null
                act { token -> container.admin.deleteFeedback(account.serverUrl, token, id) }
            },
        )
    }
}

@Composable
private fun FeedbackRow(item: FeedbackItem, container: WebContainer, account: Account, now: Long) {
    val strings = container.strings
    Div({ classes("feedback-head") }) {
        Span({ classes("feedback-kind") }) { Text(if (item.kind == "bug") strings.feedbackBug else strings.feedbackIdea) }
        Span({ classes("details", "numbers") }) {
            Text(
                listOfNotNull(
                    shortDate(item.createdAt, container.locale, now),
                    strings.platformName(item.platform),
                    item.appVersion,
                    strings.resolved.takeIf { item.resolvedAt != null },
                ).joinToString(strings.separator),
            )
        }
    }
    P({ classes("feedback-email") }) { Text(item.email) }
    P({ classes("feedback-message") }) { Text(item.message) }
    if (item.images.isNotEmpty()) {
        Div({ classes("thumbs") }) {
            item.images.forEachIndexed { index, image ->
                key(image.id) { Screenshot(container, account, item.id, image.id, index + 1) }
            }
        }
    }
}

/** One screenshot, fetched with the token into an object URL that is revoked when the row goes. */
@Composable
private fun Screenshot(container: WebContainer, account: Account, feedbackId: String, imageId: String, number: Int) {
    val strings = container.strings
    var url by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(imageId) {
        val token = container.settings.token ?: return@LaunchedEffect
        url = container.admin.imageUrl(account.serverUrl, token, feedbackId, imageId)
    }
    DisposableEffect(imageId) { onDispose { url?.let(URL::revokeObjectURL) } }
    val src = url
    Div({ classes("thumb") }) {
        if (src == null) {
            Span({ classes("hint") }) { Text("…") }
        } else {
            A(href = src, attrs = { attr("target", "_blank"); attr("rel", "noopener"); attr("aria-label", strings.openScreenshot(number)) }) {
                Img(src = src, alt = strings.screenshotAlt(number))
            }
        }
    }
}
