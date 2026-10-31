package io.github.kanylbullen.tung.web.ui

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

        H2({ classes("section") }) { Text(strings.about) }
        Div({ classes("setting", "column") }) {
            P({ classes("hint", "flush") }) { Text(strings.licenceText) }
            P({ classes("hint", "flush") }) { Text(strings.catalogueText) }
            A(href = "https://github.com/kanylbullen/tung", attrs = { classes("text-button", "flush-link") }) { Text(strings.sourceCode) }
            A(href = "/privacy", attrs = { classes("text-button", "flush-link") }) { Text(strings.privacy) }
        }
    }
}
