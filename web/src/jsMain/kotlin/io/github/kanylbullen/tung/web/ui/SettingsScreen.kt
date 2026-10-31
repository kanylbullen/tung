package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import kotlin.js.Date
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.web.importing.undoImport
import io.github.kanylbullen.tung.web.importing.ImportMemory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MAX_REST_SECONDS
import io.github.kanylbullen.tung.core.REST_STEP_SECONDS
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.sync.Account
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.attributes.AutoComplete
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.autoComplete
import io.github.kanylbullen.tung.web.sync.DeleteAccountResult
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/**
 * Settings, the phone's groups on one page: Training (the default rest,
 * −/+ 30 s from off to 5:00, and "My equipment", which narrows the exercise
 * picker), Account (who, the server, Sync now, Sign out), Server admin
 * (only for the server's admins) and About. The
 * training settings are this browser's own, not synced, as on the phone.
 */
@Composable
fun SettingsScreen(container: WebContainer, account: Account) {
    val strings = container.strings
    val rest = container.preferences.restSeconds.collectAsState().value
    val equipment = container.preferences.equipment.collectAsState().value
    val running = container.sync.running.collectAsState().value
    val scope = rememberCoroutineScope()

    Div({ classes("screen") }) {
        Div({ classes("topbar", "topbar-start") }) {
            A(href = Route.HOME.hash, attrs = { classes("text-button"); attr("aria-label", strings.home) }) { Text("←") }
            H1({ classes("detail-title") }) { Text(strings.settings) }
        }

        H2({ classes("section") }) { Text(strings.training) }
        Div({ classes("setting") }) {
            Span({ classes("setting-label") }) { Text(strings.defaultRest) }
            Div({ classes("setting-control") }) {
                Button({
                    classes("stepper-small")
                    attr("aria-label", strings.less)
                    if (rest <= 0) disabled()
                    onClick { container.preferences.setRestSeconds(rest - REST_STEP_SECONDS) }
                }) { Text("−") }
                Span({ classes("setting-value", "numbers") }) { Text(if (rest == 0) strings.restOff else clockText(rest * 1000L)) }
                Button({
                    classes("stepper-small")
                    attr("aria-label", strings.more)
                    if (rest >= MAX_REST_SECONDS) disabled()
                    onClick { container.preferences.setRestSeconds(rest + REST_STEP_SECONDS) }
                }) { Text("+") }
            }
        }

        Div({ classes("setting", "column") }) {
            Span({ classes("setting-label") }) { Text(strings.myEquipment) }
            P({ classes("hint", "flush") }) { Text(strings.equipmentHint) }
            Div({ classes("chips", "flush", "wrap") }) {
                Button({
                    classes("chip")
                    if (equipment == EquipmentFilter.ALL) classes("on")
                    onClick { container.preferences.setEquipment(EquipmentFilter.ALL) }
                }) { Text(strings.everything) }
                Button({
                    classes("chip")
                    if (equipment == setOf(Equipment.BODYWEIGHT)) classes("on")
                    onClick { container.preferences.setEquipment(setOf(Equipment.BODYWEIGHT)) }
                }) { Text(strings.bodyweightOnly) }
            }
            Div({ classes("checks") }) {
                Equipment.entries.forEach { kind ->
                    Label(attrs = { classes("check") }) {
                        CheckboxInput(kind in equipment) {
                            // The last one ticked stays ticked.
                            if (kind in equipment && equipment.size == 1) disabled()
                            onChange { container.preferences.toggleEquipment(kind) }
                        }
                        Span { Text(strings.equipmentLabel(kind)) }
                    }
                }
            }
        }

        AssistantSettings(container)

        DataSection(container)

        H2({ classes("section") }) { Text(strings.account) }
        Div({ classes("setting", "column") }) {
            Span { Text(strings.signedInAs(account.email)) }
            P({ classes("hint", "flush") }) { Text(account.serverUrl) }
            Div({ classes("setting-buttons") }) {
                Button({
                    classes("secondary")
                    if (running) disabled()
                    onClick { container.syncInBackground() }
                }) { Text(if (running) strings.syncing else strings.syncNow) }
                Button({ classes("secondary"); onClick { scope.launch { container.signOut() } } }) { Text(strings.signOut) }
            }
            DeleteAccount(container, account)
        }

        // Only for the server's admins (TUNG_ADMIN_EMAILS); asked once each time Settings opens.
        val isAdmin by produceState(false, account) {
            val token = container.settings.token
            value = token != null && container.admin.isAdmin(account.serverUrl, token)
        }
        if (isAdmin) {
            H2({ classes("section") }) { Text(strings.serverAdmin) }
            Ul({ classes("rows") }) {
                Li {
                    A(href = Route.ADMIN.hash, attrs = { classes("row", "row-link") }) {
                        Div({ classes("main") }) {
                            Div({ classes("title") }) { Span({ classes("plain") }) { Text(strings.serverAdmin) } }
                            Div({ classes("details") }) { Text(strings.serverAdminHint) }
                        }
                    }
                }
            }
        }

        Ul({ classes("rows") }) {
            Li {
                A(href = Route.FEEDBACK.hash, attrs = { classes("row", "row-link") }) {
                    Div({ classes("main") }) {
                        Div({ classes("title") }) { Span({ classes("plain") }) { Text(strings.sendFeedback) } }
                        Div({ classes("details") }) { Text(strings.sendFeedbackHint) }
                    }
                }
            }
        }

        H2({ classes("section") }) { Text(strings.about) }
        Div({ classes("setting", "column") }) {
            P({ classes("hint", "flush") }) { Text(strings.licenceText) }
            P({ classes("hint", "flush") }) { Text(strings.catalogueText) }
            A(href = "https://github.com/kanylbullen/tung", attrs = { classes("text-button", "flush-link") }) { Text(strings.sourceCode) }
            A(href = "/privacy", attrs = { classes("text-button", "flush-link") }) { Text(strings.privacy) }
        }
    }
}

/**
 * Settings → Data, the phone's: Import from Jefit, and while this browser
 * remembers the last import, Undo last import and "Delete routines from
 * the last import (N)" (N counting those that still exist).
 */
@Composable
private fun DataSection(container: WebContainer) {
    val strings = container.strings
    val version = container.store.version.collectAsState().value
    val scope = rememberCoroutineScope()
    var last by remember { mutableStateOf(ImportMemory.load()) }
    var confirming by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val batchWorkouts = remember(version, last) {
        last?.let { l -> container.store.workouts.rows.values.count { it.row.importBatchId == l.batchId } } ?: 0
    }
    val routines = remember(version, last) { last?.routineIds?.filter { container.store.routines.rows.containsKey(it) }.orEmpty() }

    H2({ classes("section") }) { Text(strings.data) }
    Ul({ classes("rows") }) {
        Li {
            A(href = Route.IMPORT.hash, attrs = { classes("row", "row-link") }) {
                Div({ classes("main") }) {
                    Div({ classes("title") }) { Span({ classes("plain") }) { Text(strings.importJefit) } }
                    last?.let { Div({ classes("details") }) { Text(strings.lastImport(it.workouts, shortDate(it.at, container.locale, Date.now().toLong()))) } }
                }
            }
        }
    }
    if (last != null && (batchWorkouts > 0 || routines.isNotEmpty())) {
        Div({ classes("setting-buttons", "pad") }) {
            if (batchWorkouts > 0) Button({ classes("secondary"); onClick { confirming = "undo" } }) { Text(strings.undoLastImport) }
            if (routines.isNotEmpty()) Button({ classes("secondary"); onClick { confirming = "routines" } }) { Text(strings.deleteImportRoutines(routines.size)) }
        }
    }
    message?.let { P({ classes("hint", "accent"); attr("role", "status") }) { Text(it) } }

    val current = last
    when (confirming) {
        "undo" -> if (current != null) {
            Dialog(strings.undoImportTitle(batchWorkouts), strings.undoImportBody, strings.remove, strings.cancel, onCancel = { confirming = null }) {
                confirming = null
                scope.launch {
                    message = strings.importUndone(undoImport(container.store, container.commands, current.batchId, current.routineIds))
                    ImportMemory.forget()
                    last = null
                }
            }
        }
        "routines" -> {
            val days = container.store.programDays.rows.values.count { it.row.routineId in routines }
            val body = listOf(strings.deleteImportRoutinesBody) + (if (days > 0) listOf(strings.deleteManyDays(days)) else emptyList())
            Dialog(strings.deleteManyTitle(routines.size), body.joinToString(" "), strings.delete, strings.cancel, onCancel = { confirming = null }) {
                confirming = null
                scope.launch { container.run(Command.DeleteRoutines(routines)) }
            }
        }
    }
}

/**
 * "Delete account…" under the account, and its dialog: what goes
 * (everything on the server) and what stays (the log in this browser), the
 * account's email typed to confirm, and the server's answer.
 */
@Composable
private fun DeleteAccount(container: WebContainer, account: Account) {
    val strings = container.strings
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Button({ classes("text-button", "danger-text"); onClick { open = true } }) { Text(strings.deleteAccount) }
    if (!open) return
    fun close() {
        if (busy) return
        open = false
        email = ""
        error = null
    }
    Div({ classes("sheet-backdrop"); onClick { close() } })
    Div({ classes("dialog"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", strings.deleteAccountTitle) }) {
        H2 { Text(strings.deleteAccountTitle) }
        P { Text(strings.deleteAccountBody(account.serverUrl)) }
        Label(attrs = { classes("field", "dialog-field") }) {
            Span { Text(strings.deleteAccountEmail) }
            Input(InputType.Email) {
                value(email)
                autoComplete(AutoComplete.off)
                onInput { email = it.value; error = null }
                if (busy) disabled()
            }
        }
        error?.let { message -> P({ classes("error"); attr("role", "alert") }) { Text(message) } }
        Div({ classes("dialog-actions") }) {
            Button({ classes("secondary"); if (busy) disabled(); onClick { close() } }) { Text(strings.cancel) }
            Button({
                classes("accent-button")
                if (busy || email.isBlank()) disabled()
                onClick {
                    busy = true
                    scope.launch {
                        val result = container.deleteAccount(email)
                        busy = false
                        // Deleted or signed out: the page turns to the sign-in screen by itself.
                        if (result != DeleteAccountResult.DELETED && result != DeleteAccountResult.SIGNED_OUT) {
                            error = strings.deleteAccountFailure(result)
                        }
                    }
                }
            }) { Text(strings.deleteAccountConfirm) }
        }
    }
}
