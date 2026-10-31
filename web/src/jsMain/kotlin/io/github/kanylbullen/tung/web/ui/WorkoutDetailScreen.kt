package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.history.DetailExercise
import io.github.kanylbullen.tung.web.history.exerciseDisplayName
import io.github.kanylbullen.tung.web.history.workoutDetail
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/** `#/history/<id>`: the path of a finished workout's page. */
fun workoutDetailHash(id: String): String = "#/history/$id"

/**
 * A finished workout, the phone's workout detail: the date, duration, sets
 * and volume, Health Connect's heart rate and calories when synced, each
 * exercise's logged sets with its best set and estimated 1RM, Save as
 * routine and Delete. Every change is a command.
 */
@Composable
fun WorkoutDetailScreen(container: WebContainer, id: String) {
    val strings = container.strings
    val locale = container.locale
    val version = container.store.version.collectAsState().value
    val detail = remember(version, id) { workoutDetail(container.store, id) { exerciseDisplayName(it, container.catalogueNames, strings.tag) } }
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }

    Div({ classes("screen") }) {
        Div({ classes("topbar", "topbar-start") }) {
            A(href = Route.HISTORY.hash, attrs = { classes("text-button"); attr("aria-label", strings.history) }) { Text("←") }
            H1({ classes("detail-title") }) {
                if (detail?.title != null) RoutineTitle(detail.title) else Text(strings.workoutUntitled)
            }
        }
        if (detail == null) {
            P({ classes("empty") }) { Text(strings.notFound) }
            return@Div
        }
        P({ classes("detail-date") }) { Text(longDate(detail.startedAt, locale)) }
        Div({ classes("stats") }) {
            Stat(durationText(detail.durationMillis, strings), strings.duration)
            Stat(detail.sets.toString(), strings.setsLabel)
            Stat(wholeNumber(detail.volumeKg, locale), strings.volumeKg)
        }
        val vitals = listOfNotNull(
            detail.avgHeartRate?.let { strings.heartRate(it, detail.maxHeartRate) },
            detail.activeCalories?.let { strings.calories(it) },
        )
        if (vitals.isNotEmpty()) P({ classes("hint") }) { Text(vitals.joinToString(strings.separator)) }

        detail.exercises.forEachIndexed { index, exercise ->
            key(index) { DetailExerciseBlock(container, exercise, record = false) }
        }

        saved?.let { P({ classes("hint", "accent"); attr("role", "status") }) { Text(strings.savedAsRoutine(it)) } }
        Button({ classes("secondary", "wide"); onClick { naming = true } }) { Text(strings.saveAsRoutine) }
        Button({ classes("text-button", "danger-text"); onClick { deleting = true } }) { Text(strings.deleteWorkout) }
    }

    if (detail != null && deleting) {
        Dialog(strings.deleteWorkoutTitle, strings.deleteWorkoutBody, strings.delete, strings.cancel, onCancel = { deleting = false }) {
            deleting = false
            scope.launch {
                container.commands.execute(Command.DeleteWorkout(detail.id))
                go(Route.HISTORY)
            }
        }
    }
    if (detail != null && naming) {
        NameDialog(strings.saveAsRoutine, strings.routineName, detail.title ?: "", strings.save, strings.cancel, onCancel = { naming = false }) { name ->
            naming = false
            scope.launch {
                if (container.commands.execute(Command.SaveRoutine(detail.id, name)) is CommandResult.Done) saved = name
            }
        }
    }
}

/**
 * One exercise of a finished workout: its name, sets, volume and estimated
 * 1RM, the best set in large numbers, "New record" when [record], and every
 * logged set.
 */
@Composable
fun DetailExerciseBlock(container: WebContainer, exercise: DetailExercise, record: Boolean) {
    val strings = container.strings
    val locale = container.locale
    Div({ classes("detail-exercise") }) {
        Div({ classes("detail-head") }) {
            Div {
                H2 { Text(exercise.name) }
                P({ classes("details", "numbers") }) {
                    Text(strings.bestSetLine(exercise.sets.size, wholeNumber(exercise.volumeKg, locale), exercise.e1rmKg?.let { wholeNumber(it, locale) }))
                }
                if (record) P({ classes("details", "accent") }) { Text(strings.newRecord) }
            }
            exercise.best?.let { best ->
                Div({ classes("best", "numbers") }) { Text(weightText(best.weightKg, locale) + " × " + best.reps) }
            }
        }
        Ul({ classes("detail-sets", "numbers") }) {
            exercise.sets.forEachIndexed { n, set ->
                Li {
                    Span({ classes("muted") }) { Text("${n + 1}") }
                    Span { Text(weightText(set.weightKg, locale) + " kg × " + set.reps) }
                }
            }
        }
    }
}

/** A labelled big number, as the phone's `Stat`. */
@Composable
fun Stat(value: String, label: String) {
    Div({ classes("stat") }) {
        Div({ classes("stat-value", "numbers") }) { Text(value) }
        Div({ classes("stat-label") }) { Text(label) }
    }
}

/** A dialog with one text field, for a name: Save is enabled once it is not blank. */
@Composable
fun NameDialog(title: String, label: String, initial: String, confirm: String, cancel: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    Div({ classes("sheet-backdrop"); onClick { onCancel() } })
    Div({ classes("dialog"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", title) }) {
        H2 { Text(title) }
        Label(attrs = { classes("field", "dialog-field") }) {
            Span { Text(label) }
            Input(InputType.Text) {
                value(name)
                attr("autofocus", "")
                attr("maxlength", "200")
                onInput { name = it.value }
            }
        }
        Div({ classes("dialog-actions") }) {
            Button({ classes("secondary"); onClick { onCancel() } }) { Text(cancel) }
            Button({
                classes("accent-button")
                if (name.isBlank()) disabled()
                onClick { onConfirm(name.trim()) }
            }) { Text(confirm) }
        }
    }
}
