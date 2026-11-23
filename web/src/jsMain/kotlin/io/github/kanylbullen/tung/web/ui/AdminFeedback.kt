package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.admin.BulkOutcome
import io.github.kanylbullen.tung.web.admin.FeedbackItem
import io.github.kanylbullen.tung.web.admin.FeedbackKind
import io.github.kanylbullen.tung.web.admin.FeedbackList
import io.github.kanylbullen.tung.web.admin.FeedbackStatus
import io.github.kanylbullen.tung.web.admin.chosenOnShow
import io.github.kanylbullen.tung.web.admin.filterFeedback
import io.github.kanylbullen.tung.web.sync.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.url.URL

/**
 * Server admin's Feedback tab: open feedback first by default, filtered by
 * state and kind and searched in its words and its sender's email; newest
 * first, each with who sent it, from where, its words and its screenshots
 * (fetched with the token on "Show screenshots", so they are object URLs,
 * opened full size in a new tab on a click), and Mark resolved / Reopen and
 * Delete. Rows on show can be ticked and resolved, reopened or deleted
 * together, one request each ([io.github.kanylbullen.tung.web.admin.runBulk]).
 * A resolved one is muted. [list] is read by [AdminScreen]; [onChanged]
 * reads it again. Actions run on [scope], the screen's, so switching tabs
 * neither stops a bulk action nor skips the read after it.
 */
@Composable
fun FeedbackTab(container: WebContainer, account: Account, scope: CoroutineScope, list: FeedbackList?, failed: Boolean, now: Long, onChanged: () -> Unit) {
    val strings = container.strings
    var status by remember { mutableStateOf(FeedbackStatus.OPEN) }
    var kind by remember { mutableStateOf(FeedbackKind.ALL) }
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf<List<String>?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var outcome by remember { mutableStateOf<BulkOutcome?>(null) }
    var actFailed by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf(false) }

    /** Runs a bulk action over [ids], then reads the list again; the selection stays when something failed. */
    fun bulk(ids: List<String>, action: suspend (String, (Int) -> Unit) -> BulkOutcome) {
        val token = container.settings.token ?: return
        if (ids.isEmpty() || progress != null) return
        outcome = null
        progress = 0 to ids.size
        scope.launch {
            val result = action(token) { done -> progress = done to ids.size }
            progress = null
            outcome = result.takeIf { it.failed > 0 }
            if (result.failed == 0) selection = emptySet()
            onChanged()
        }
    }

    fun one(block: suspend (String) -> Boolean) {
        val token = container.settings.token ?: return
        if (acting || progress != null) return
        acting = true
        scope.launch {
            try {
                if (block(token)) { actFailed = false; onChanged() } else actFailed = true
            } finally {
                acting = false
            }
        }
    }

    if (failed || actFailed) P({ classes("error"); attr("role", "alert") }) { Text(strings.adminFailed) }
    if (list == null) {
        if (!failed) {
            P({ classes("hint") }) { Text(strings.loading) }
        } else {
            Div({ classes("setting-buttons", "pad") }) {
                Button({ classes("secondary"); onClick { onChanged() } }) { Text(strings.tryAgain) }
            }
        }
        return
    }
    if (list.feedback.isEmpty()) {
        P({ classes("hint") }) { Text(strings.feedbackEmpty) }
        return
    }

    AdminChips(strings.feedbackHeading(list.openCount), FeedbackStatus.entries, status, {
        when (it) {
            FeedbackStatus.OPEN -> strings.filterOpen
            FeedbackStatus.RESOLVED -> strings.filterResolved
            FeedbackStatus.ALL -> strings.filterAll
        }
    }) { status = it }
    AdminChips(strings.filterAllKinds, FeedbackKind.entries, kind, {
        when (it) {
            FeedbackKind.ALL -> strings.filterAllKinds
            FeedbackKind.BUG -> strings.filterBugs
            FeedbackKind.IDEA -> strings.filterIdeas
        }
    }) { kind = it }
    AdminSearch(strings.searchFeedback, query) { query = it }

    val shown = filterFeedback(list.feedback, status, kind, query)
    val chosen = chosenOnShow(shown, selection) { it.id }
    val running = progress
    SelectionBar(container, shown.size, chosen.size, running, onSelectAll = { selection = shown.map { it.id }.toSet() }, onClear = { selection = emptySet() }) {
        val open = chosen.filter { it.resolvedAt == null }.map { it.id }
        val done = chosen.filter { it.resolvedAt != null }.map { it.id }
        if (open.isNotEmpty()) {
            Button({
                classes("secondary")
                if (running != null) disabled()
                onClick { bulk(open) { token, onProgress -> container.admin.setResolved(account.serverUrl, token, open, true, onProgress) } }
            }) { Text(strings.markResolved) }
        }
        if (done.isNotEmpty()) {
            Button({
                classes("secondary")
                if (running != null) disabled()
                onClick { bulk(done) { token, onProgress -> container.admin.setResolved(account.serverUrl, token, done, false, onProgress) } }
            }) { Text(strings.reopen) }
        }
        Button({
            classes("text-button", "danger-text")
            if (running != null) disabled()
            onClick { deleting = chosen.map { it.id } }
        }) { Text(strings.deleteSelectedEllipsis) }
    }
    outcome?.let { P({ classes("error"); attr("role", "alert") }) { Text(strings.bulkFailed(it.done, it.failed)) } }

    if (shown.isEmpty()) {
        P({ classes("hint") }) { Text(strings.nothingMatches) }
    } else {
        Ul({ classes("rows") }) {
            shown.forEach { item ->
                key(item.id) {
                    Li({ classes("feedback-item"); if (item.resolvedAt != null) classes("resolved") }) {
                        FeedbackRow(item, container, account, now, selected = item.id in selection) {
                            val current = selection
                            selection = if (item.id in current) current - item.id else current + item.id
                        }
                        Div({ classes("setting-buttons") }) {
                            Button({
                                classes("secondary")
                                if (running != null || acting) disabled()
                                onClick { one { token -> container.admin.setResolved(account.serverUrl, token, item.id, item.resolvedAt == null) } }
                            }) { Text(if (item.resolvedAt == null) strings.markResolved else strings.reopen) }
                            Button({ classes("text-button"); if (running != null) disabled(); onClick { deleting = listOf(item.id) } }) { Text(strings.delete) }
                        }
                    }
                }
            }
        }
    }

    val ids = deleting
    if (ids != null) {
        Dialog(
            title = if (ids.size == 1) strings.deleteFeedbackTitle else strings.deleteFeedbackManyTitle(ids.size),
            body = strings.deleteFeedbackBody,
            confirm = strings.delete,
            cancel = strings.cancel,
            onCancel = { deleting = null },
            onConfirm = {
                deleting = null
                bulk(ids) { token, onProgress -> container.admin.deleteFeedback(account.serverUrl, token, ids, onProgress) }
            },
        )
    }
}

@Composable
private fun FeedbackRow(item: FeedbackItem, container: WebContainer, account: Account, now: Long, selected: Boolean, onToggle: () -> Unit) {
    val strings = container.strings
    Div({ classes("feedback-head") }) {
        Input(InputType.Checkbox) {
            classes("row-check")
            checked(selected)
            attr("aria-label", strings.selectRow(item.email))
            onChange { onToggle() }
        }
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
        // Fetched only when asked for: up to 500 rows of full-size screenshots would be a lot of memory.
        var shown by remember(item.id) { mutableStateOf(false) }
        if (!shown) {
            Div({ classes("setting-buttons") }) {
                Button({ classes("text-button"); onClick { shown = true } }) { Text(strings.showScreenshots(item.images.size)) }
            }
        } else {
            Div({ classes("thumbs") }) {
                item.images.forEachIndexed { index, image ->
                    key(image.id) { Screenshot(container, account, item.id, image.id, index + 1) }
                }
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

/** One labelled line of choices, the chosen one in the accent. */
@Composable
internal fun <T> AdminChips(groupLabel: String, options: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
    Div({ classes("chips"); attr("role", "radiogroup"); attr("aria-label", groupLabel) }) {
        options.forEach { option ->
            Button({
                classes("chip")
                if (option == chosen) classes("on")
                attr("role", "radio")
                attr("aria-checked", (option == chosen).toString())
                onClick { onChoose(option) }
            }) { Text(label(option)) }
        }
    }
}

/** A search field over a list. */
@Composable
internal fun AdminSearch(label: String, query: String, onQuery: (String) -> Unit) {
    Div({ classes("pad") }) {
        Input(InputType.Search) {
            classes("search")
            value(query)
            attr("placeholder", label)
            attr("aria-label", label)
            onInput { onQuery(it.value) }
        }
    }
}

/**
 * The line above a list that can be ticked: "Select all (12)" while not all
 * on show are, and once any is, "3 selected", the [actions] and Clear; while
 * an action runs, its progress instead.
 */
@Composable
internal fun SelectionBar(
    container: WebContainer,
    shownCount: Int,
    chosenCount: Int,
    progress: Pair<Int, Int>?,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val strings = container.strings
    if (shownCount == 0 && progress == null) return
    Div({ classes("setting-buttons", "pad", "bulk-bar") }) {
        if (progress != null) {
            Span({ classes("hint"); attr("role", "status") }) { Text(strings.bulkProgress(progress.first, progress.second)) }
            return@Div
        }
        if (chosenCount < shownCount) {
            Button({ classes("text-button"); onClick { onSelectAll() } }) { Text(strings.selectAllShown(shownCount)) }
        }
        if (chosenCount > 0) {
            Span({ classes("bulk-count"); attr("aria-live", "polite") }) { Text(strings.selectedCount(chosenCount)) }
            actions()
            Button({ classes("text-button"); onClick { onClear() } }) { Text(strings.clearSelection) }
        }
    }
}
