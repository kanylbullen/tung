package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.catalog.browseNameOrder
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.history.exerciseDisplayName
import io.github.kanylbullen.tung.web.workout.ExerciseView
import io.github.kanylbullen.tung.web.workout.SetView
import io.github.kanylbullen.tung.web.workout.WorkoutView
import io.github.kanylbullen.tung.web.workout.activeWorkout
import io.github.kanylbullen.tung.web.workout.pickerRows
import kotlin.js.Date
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/** Goes to [route] by the location's hash, as the bottom bar does. */
fun go(route: Route) {
    window.location.hash = route.hash
}

/** Runs [command] through the container's one command surface, as every write on the web does. */
suspend fun WebContainer.run(command: Command): CommandResult = commands.execute(command)

/**
 * The workout in progress, the phone's active workout without its input
 * sheet yet: the clock, each exercise with "Last" and its sets (tick one to
 * log it), + Set, Add exercise through a searchable picker, Finish (which
 * says what it drops) and Discard. Every change is a command.
 */
@Composable
fun WorkoutScreen(container: WebContainer) {
    val strings = container.strings
    val version = container.store.version.collectAsState().value
    val workout = remember(version) {
        activeWorkout(container.store) { exerciseDisplayName(it, container.catalogueNames, strings.tag) }
    }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Confirm?>(null) }
    var now by remember { mutableStateOf(Date.now().toLong()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Date.now().toLong()
        }
    }

    if (workout == null) {
        Div({ classes("screen") }) {
            Div({ classes("topbar") }) { H1 { Text(strings.workoutUntitled) } }
            P({ classes("empty") }) { Text(strings.noWorkout) }
            Button({
                classes("primary")
                onClick { scope.launch { container.run(Command.StartWorkout()) } }
            }) { Text(strings.startWorkout) }
        }
        return
    }

    Div({ classes("screen", "workout") }) {
        Div({ classes("topbar") }) {
            A(href = Route.HOME.hash, attrs = { classes("text-button"); attr("aria-label", strings.home) }) { Text("←") }
            Div({ classes("clock", "numbers") }) { Text(clockText(now - workout.startedAt)) }
            Button({
                classes("text-button")
                onClick { confirming = Confirm.FINISH }
            }) { Text(strings.finish) }
        }
        workout.routineName?.let { name -> Div({ classes("routine-name") }) { RoutineTitle(name) } }

        if (workout.exercises.isEmpty()) {
            P({ classes("empty") }) { Text(strings.emptyWorkout) }
        }
        workout.exercises.forEach { exercise ->
            key(exercise.workoutExerciseId) { ExerciseBlock(exercise, container) { command -> scope.launch { container.run(command) } } }
        }

        Button({
            classes("secondary", "wide")
            onClick { picking = true }
        }) { Text(strings.addExercise) }
        Button({
            classes("text-button", "danger-text")
            onClick { confirming = Confirm.DISCARD }
        }) { Text(strings.discard) }
    }

    if (picking) {
        Picker(container, onClose = { picking = false }) { exerciseId ->
            picking = false
            scope.launch { container.run(Command.AddExercise(workout.id, ExerciseRef.ById(exerciseId))) }
        }
    }

    when (confirming) {
        Confirm.FINISH -> Dialog(
            title = strings.finishTitle,
            body = finishBody(workout, container),
            confirm = strings.finish,
            cancel = strings.keepTraining,
            onCancel = { confirming = null },
        ) {
            confirming = null
            scope.launch {
                container.run(Command.FinishWorkout(workout.id))
                go(Route.HISTORY)
            }
        }
        Confirm.DISCARD -> Dialog(
            title = strings.discardTitle,
            body = strings.discardBody,
            confirm = strings.discard,
            cancel = strings.keepTraining,
            onCancel = { confirming = null },
        ) {
            confirming = null
            scope.launch {
                container.run(Command.DeleteWorkout(workout.id))
                go(Route.HOME)
            }
        }
        null -> Unit
    }
}

private enum class Confirm { FINISH, DISCARD }

private fun finishBody(workout: WorkoutView, container: WebContainer): String = when {
    workout.loggedSets == 0 -> container.strings.finishNothingLogged
    workout.openSets == 0 -> container.strings.finishAllLogged
    else -> container.strings.finishDropsOpen(workout.openSets)
}

/** One exercise: its name, "Last: …", its sets, and + Set. */
@Composable
private fun ExerciseBlock(exercise: ExerciseView, container: WebContainer, run: (Command) -> Unit) {
    val strings = container.strings
    val locale = container.locale
    Div({ classes("exercise") }) {
        Div({ classes("exercise-head") }) {
            H2 { Text(exercise.name) }
            Button({
                classes("icon-button")
                attr("aria-label", strings.removeExercise(exercise.name))
                onClick { run(Command.RemoveExercise(exercise.workoutExerciseId)) }
            }) { Text("×") }
        }
        if (exercise.last.isNotEmpty()) {
            P({ classes("last-line", "numbers") }) { Text(strings.last(setsText(exercise.last, locale))) }
        }
        Ul({ classes("sets") }) {
            exercise.sets.forEach { set -> key(set.id) { SetRow(set, container, run) } }
        }
        Button({
            classes("text-button")
            onClick { run(Command.AddSet(exercise.workoutExerciseId)) }
        }) { Text(strings.addSet) }
    }
}

/** A set: its number, the weight and reps in large numbers, and the tick. */
@Composable
private fun SetRow(set: SetView, container: WebContainer, run: (Command) -> Unit) {
    val strings = container.strings
    Li({ classes("set-row"); if (set.completed) classes("done") }) {
        Span({ classes("set-number", "numbers") }) { Text(set.number.toString()) }
        Span({ classes("set-values", "numbers") }) {
            Span({ classes("big") }) { Text(weightText(set.weightKg, container.locale)) }
            Span({ classes("unit") }) { Text("kg") }
            Span({ classes("times") }) { Text("×") }
            Span({ classes("big") }) { Text(set.reps.toString()) }
            Span({ classes("unit") }) { Text("reps") }
        }
        Button({
            classes("tick")
            if (set.completed) classes("on")
            attr("aria-pressed", set.completed.toString())
            attr("aria-label", strings.setDone(set.number))
            onClick { run(Command.TickSet(set.id, !set.completed)) }
        }) { Text("✓") }
    }
}

/** The exercise picker: a search field over every exercise in the store, by the name on screen. */
@Composable
private fun Picker(container: WebContainer, onClose: () -> Unit, onPick: (String) -> Unit) {
    val strings = container.strings
    var query by remember { mutableStateOf("") }
    val order = remember { browseNameOrder(container.locale) }
    val rows = remember(query) {
        pickerRows(container.store, query, { exerciseDisplayName(it, container.catalogueNames, strings.tag) }, order)
    }
    Div({ classes("sheet-backdrop"); onClick { onClose() } })
    Div({ classes("sheet"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", strings.addExercise) }) {
        Div({ classes("sheet-head") }) {
            Input(InputType.Search) {
                classes("search")
                value(query)
                attr("placeholder", strings.searchExercises)
                attr("aria-label", strings.searchExercises)
                attr("autofocus", "")
                onInput { query = it.value }
            }
            Button({ classes("text-button"); onClick { onClose() } }) { Text(strings.close) }
        }
        if (rows.isEmpty()) {
            P({ classes("empty") }) { Text(strings.noMatches) }
        } else {
            Ul({ classes("picker") }) {
                rows.forEach { row ->
                    key(row.id) {
                        Li {
                            Button({ classes("picker-row"); onClick { onPick(row.id) } }) { Text(row.name) }
                        }
                    }
                }
            }
        }
    }
}

/** A confirmation: a title, what happens, and two buttons side by side. */
@Composable
fun Dialog(title: String, body: String, confirm: String, cancel: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Div({ classes("sheet-backdrop"); onClick { onCancel() } })
    Div({ classes("dialog"); attr("role", "alertdialog"); attr("aria-modal", "true"); attr("aria-label", title) }) {
        H2 { Text(title) }
        P { Text(body) }
        Div({ classes("dialog-actions") }) {
            Button({ classes("secondary"); onClick { onCancel() } }) { Text(cancel) }
            Button({ classes("accent-button"); onClick { onConfirm() } }) { Text(confirm) }
        }
    }
}
