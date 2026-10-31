package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import io.github.kanylbullen.tung.core.catalog.ExercisePageRef
import io.github.kanylbullen.tung.core.catalog.browse
import io.github.kanylbullen.tung.core.catalog.browseGroups
import io.github.kanylbullen.tung.core.catalog.browseNameOrder
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.exercises.browseItemsOf
import io.github.kanylbullen.tung.web.exercises.exercisePage
import io.github.kanylbullen.tung.web.history.exerciseDisplayName
import io.github.kanylbullen.tung.web.sync.Account
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Ol
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/** `#/exercises/<ref>`: an exercise's page; [ref] is core's [ExercisePageRef.route]. */
fun exerciseHash(ref: ExercisePageRef): String = "#/exercises/" + js("encodeURIComponent")(ref.route) as String

/**
 * The Exercises tab, the phone's: the whole catalogue (the store's
 * exercises and every extended entry not picked yet) by muscle group,
 * narrowed by a group chip, "My equipment" and the search, all core's
 * `browse`. A row opens the exercise's page.
 */
@Composable
fun ExercisesScreen(container: WebContainer, account: Account) {
    val strings = container.strings
    val version = container.store.version.collectAsState().value
    val catalogue = container.catalogue.collectAsState().value
    val available = container.preferences.equipment.collectAsState().value
    val order = remember { browseNameOrder(container.locale) }
    val items = remember(version, catalogue) { browseItemsOf(container.store, catalogue) { exerciseDisplayName(it, container.catalogueNames, strings.tag) } }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<MuscleGroup?>(null) }
    val groups = remember(items, available) { browseGroups(items, available) }
    val sections = remember(items, group, query, available) { browse(items, group, query, order, available) }

    Div({ classes("screen") }) {
        TopBar(container, account) { H1 { Text(strings.exercises) } }
        Div({ classes("pad") }) {
            Input(InputType.Search) {
                classes("search", "wide-search")
                value(query)
                attr("placeholder", strings.searchExercises)
                attr("aria-label", strings.searchExercises)
                onInput { query = it.value }
            }
        }
        Div({ classes("chips"); attr("role", "radiogroup") }) {
            (listOf<MuscleGroup?>(null) + groups).forEach { g ->
                Button({
                    classes("chip")
                    if (g == group) classes("on")
                    attr("role", "radio")
                    attr("aria-checked", (g == group).toString())
                    // Tapping the chip that is on goes back to All, as on the phone.
                    onClick { group = if (g == group) null else g }
                }) { Text(g?.let(strings::groupLabel) ?: strings.all) }
            }
        }
        Div({ classes("chips") }) {
            A(href = Route.SETTINGS.hash, attrs = { classes("chip") }) {
                Text(strings.equipmentFilter(available.size.takeIf { available != EquipmentFilter.ALL }))
            }
        }
        if (sections.isEmpty()) {
            P({ classes("empty") }) { Text(strings.noMatches) }
            return@Div
        }
        sections.forEach { section ->
            key(section.group) {
                H2({ classes("section") }) { Text(strings.groupLabel(section.group)) }
                Ul({ classes("rows") }) {
                    section.items.forEach { item ->
                        key("b-" + item.ref.route) {
                            Li {
                                A(href = exerciseHash(item.ref), attrs = { classes("row", "row-link") }) {
                                    Div({ classes("main") }) {
                                        Div({ classes("title") }) { Span({ classes("plain") }) { Text(item.name) } }
                                        Div({ classes("details") }) {
                                            Text(strings.equipmentLabel(item.equipment) + if (item.isCustom) strings.separator + strings.custom else "")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * An exercise's page, the phone's: name, group and equipment, the muscle
 * map (primary in the accent, secondary at 40 %), Add to routine and, while
 * a workout is in progress, Add to workout, the history with its Progress
 * link, and the catalogue's instructions. An extended entry is named by
 * [ExercisePageRef.Extended] until a command makes its row.
 */
@Composable
fun ExercisePageScreen(container: WebContainer, route: String) {
    val strings = container.strings
    val locale = container.locale
    val version = container.store.version.collectAsState().value
    val catalogue = container.catalogue.collectAsState().value
    val ref = remember(route) { ExercisePageRef.parse(js("decodeURIComponent")(route) as String) }
    val displayName = { e: SyncExercise -> exerciseDisplayName(e, container.catalogueNames, strings.tag) }
    val page = remember(version, catalogue, ref) { ref?.let { exercisePage(container.store, catalogue, it, strings.tag, displayName) } }
    val active = remember(version) {
        container.store.workouts.rows.values.map { it.row }.filter { it.endedAt == null && it.startedAt != null }.maxByOrNull { it.startedAt ?: 0L }
    }
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Div({ classes("screen") }) {
        Div({ classes("topbar", "topbar-start") }) {
            A(href = Route.EXERCISES.hash, attrs = { classes("text-button"); attr("aria-label", strings.exercises) }) { Text("←") }
            H1({ classes("detail-title") }) { Text(page?.name ?: strings.exercises) }
        }
        if (page == null) {
            P({ classes("empty") }) { Text(strings.exerciseNotFound) }
            return@Div
        }
        P({ classes("detail-date") }) { Text(strings.groupLabel(page.group) + strings.separator + strings.equipmentLabel(page.equipment)) }
        page.muscles?.let { muscles ->
            fun names(list: List<Muscle>) = list.joinToString(", ") { strings.muscleName(it) }
            MuscleMap(strings.mapDescription(names(muscles.primary), muscles.secondary.takeIf { it.isNotEmpty() }?.let(::names))) { muscle ->
                when (muscle) {
                    in muscles.primary -> 1.0
                    in muscles.secondary -> 0.4
                    else -> null
                }
            }
            P({ classes("legend") }) {
                Span({ classes("swatch") }); Text(strings.primary)
                Span({ classes("swatch", "swatch-secondary") }); Text(strings.secondary)
            }
        }
        Div({ classes("action-row") }) {
            Button({ classes("secondary"); onClick { choosing = true } }) { Text(strings.addToRoutine) }
            active?.let { workout ->
                Button({
                    classes("accent-button")
                    onClick {
                        scope.launch {
                            if (container.commands.execute(Command.AddExercise(workout.id, page.ref.toExerciseRef())) is CommandResult.Done) go(Route.WORKOUT)
                        }
                    }
                }) { Text(strings.addToWorkout) }
            }
        }
        message?.let { P({ classes("hint", "accent"); attr("role", "status") }) { Text(it) } }

        if (page.exerciseId != null) {
            H2({ classes("section") }) { Text(strings.history) }
            val history = page.history
            if (history.isEmpty) {
                P({ classes("hint") }) { Text(strings.notLogged) }
            } else {
                Div({ classes("stats") }) {
                    history.best?.let { Stat(weightText(it.weightKg, locale) + " × " + it.reps, strings.bestSetKg) }
                }
                P({ classes("hint") }) { Text(strings.inWorkouts(history.sessions)) }
                history.lastSessionAt?.let { at ->
                    P({ classes("hint", "numbers") }) {
                        val date = Instant.fromEpochMilliseconds(at).toLocalDateTime(TimeZone.currentSystemDefault()).date
                        Text(strings.lastTime(shortMonthDay(date, locale)) + ": " + setsText(history.lastSession, locale))
                    }
                }
                A(href = progressHash(page.exerciseId), attrs = { classes("text-button", "pad-link") }) { Text(strings.progress) }
            }
        }
        if (page.instructions.isNotEmpty()) {
            H2({ classes("section") }) { Text(strings.howTo) }
            Ol({ classes("instructions") }) { page.instructions.forEach { Li { Text(it) } } }
        }
    }

    if (page != null && choosing) {
        val routines = remember(version) {
            container.store.routines.rows.values.map { it.row }.filter { it.name != null }
                .sortedWith(compareByDescending<io.github.kanylbullen.tung.core.sync.SyncRoutine> { it.lastUsedAt ?: 0L }.thenBy { it.name })
        }
        Div({ classes("sheet-backdrop"); onClick { choosing = false } })
        Div({ classes("sheet"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", strings.chooseRoutine) }) {
            Div({ classes("sheet-head") }) {
                H2 { Text(strings.chooseRoutine) }
                Button({ classes("text-button"); onClick { choosing = false } }) { Text(strings.close) }
            }
            if (routines.isEmpty()) {
                P({ classes("hint") }) { Text(strings.noRoutinesYet) }
            } else {
                Ul({ classes("picker") }) {
                    routines.forEach { routine ->
                        key(routine.id) {
                            Li {
                                Button({
                                    classes("picker-row")
                                    onClick {
                                        choosing = false
                                        scope.launch {
                                            val result = container.commands.execute(Command.AddRoutineExercise(routine.id, page.ref.toExerciseRef()))
                                            if (result is CommandResult.Done) message = strings.addedToRoutine(routine.name ?: "")
                                        }
                                    }
                                }) { Text(routine.name ?: "") }
                            }
                        }
                    }
                }
            }
        }
    }
}

