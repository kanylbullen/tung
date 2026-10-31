package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.kanylbullen.tung.core.ROUTINE_NAME_SEPARATOR
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.history.HistoryRow
import io.github.kanylbullen.tung.web.history.exerciseDisplayName
import io.github.kanylbullen.tung.web.history.historyRows
import io.github.kanylbullen.tung.web.sync.Account
import kotlin.js.Date
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/**
 * Every finished workout, newest first, as the phone's History lists them,
 * with the account and the sync's status above. The rows come from the
 * store in memory and are recomputed when it ticks (a sync applied
 * something), so the list is right after a reload without a network too.
 */
@Composable
fun HistoryScreen(container: WebContainer, account: Account) {
    val strings = container.strings
    val version = container.store.version.collectAsState().value
    val rows = remember(version) {
        historyRows(container.store) { exerciseDisplayName(it, container.catalogueNames, strings.tag) }
    }
    val now = Date.now().toLong()

    Div({ classes("screen") }) {
        TopBar(container, account) { H1 { Text(strings.history) } }
        if (rows.isEmpty()) {
            Div({ classes("empty") }) { Text(strings.historyEmpty) }
        } else {
            Ul({ classes("rows") }) {
                rows.forEach { row ->
                    key(row.id) {
                        Li { A(href = workoutDetailHash(row.id), attrs = { classes("row", "row-link") }) { WorkoutRow(row, container, now) } }
                    }
                }
            }
        }
    }
}

/**
 * Every screen's top: its [title], Sync now and Settings (where Sign out is), and under them
 * the account and how sync stands ("Last sync just now", or why it failed).
 */
@Composable
fun TopBar(container: WebContainer, account: Account, title: @Composable () -> Unit) {
    val strings = container.strings
    val running = container.sync.running.collectAsState().value
    val syncState = container.settings.syncState.collectAsState().value
    val scope = rememberCoroutineScope()
    val now = Date.now().toLong()
    val status = when {
        running -> strings.syncing
        syncState.lastError != null -> SyncFailure.fromKey(syncState.lastError)?.let(strings::syncFailure)
            ?: lastSyncText(syncState.lastSyncAt, now, container.locale, strings)
        else -> lastSyncText(syncState.lastSyncAt, now, container.locale, strings)
    }
    Div({ classes("topbar") }) {
        title()
        Div({ classes("actions") }) {
            Button({
                classes("icon-button", "top-icon")
                if (running) classes("spinning")
                attr("aria-label", if (running) strings.syncing else strings.syncNow)
                attr("title", strings.syncNow)
                if (running) disabled()
                onClick { container.syncInBackground() }
            }) { Icon(Icons.SYNC) }
            A(href = Route.SETTINGS.hash, attrs = {
                classes("icon-button", "top-icon")
                attr("aria-label", strings.settings)
                attr("title", strings.settings)
            }) { Icon(Icons.SETTINGS) }
        }
    }
    Div({ classes("status"); attr("role", "status") }) {
        Text(strings.signedInAs(account.email) + strings.separator + status)
    }
}

/** A workout's row: its title, "Thu 22 Oct · 47 min · 5 exercises" muted under it, and the volume on the right. */
@Composable
fun WorkoutRow(row: HistoryRow, container: WebContainer, now: Long) {
    val strings = container.strings
    Div({ classes("main") }) {
        Div({ classes("title") }) { WorkoutTitle(row, container) }
        Div({ classes("details", "numbers") }) {
            Text(
                listOf(
                    shortDate(row.startedAt, container.locale, now),
                    durationText(row.durationMillis, strings),
                    strings.exerciseCount(row.exerciseCount),
                ).joinToString(strings.separator),
            )
        }
    }
    Div({ classes("volume", "numbers") }) {
        Text(wholeNumber(row.volumeKg, container.locale))
        Span({ classes("unit") }) { Text(strings.kg) }
    }
}

/**
 * The title: the routine's name with everything up to the last
 * [ROUTINE_NAME_SEPARATOR] muted (the phone's `RoutineName`: an imported
 * "Skokloster Gym · JT2 dag 1" reads as its day, and the group is what gets
 * cut), else the first two exercises, else "Workout".
 */
@Composable
private fun WorkoutTitle(row: HistoryRow, container: WebContainer) {
    val routineName = row.routineName
    if (routineName != null) {
        RoutineTitle(routineName)
        return
    }
    Span({ classes("plain") }) {
        Text(if (row.exerciseNames.isEmpty()) container.strings.workoutUntitled else row.exerciseNames.joinToString(", "))
    }
}

/** A routine's name with everything up to the last [ROUTINE_NAME_SEPARATOR] muted, the group cut before the day. */
@Composable
fun RoutineTitle(name: String) {
    val cut = name.lastIndexOf(ROUTINE_NAME_SEPARATOR)
    if (cut > 0) {
        Span({ classes("group") }) { Text(name.substring(0, cut + ROUTINE_NAME_SEPARATOR.length)) }
        Span({ classes("day") }) { Text(name.substring(cut + ROUTINE_NAME_SEPARATOR.length)) }
    } else {
        Span({ classes("plain") }) { Text(name) }
    }
}
