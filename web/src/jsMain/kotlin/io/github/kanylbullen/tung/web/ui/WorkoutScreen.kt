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
import io.github.kanylbullen.tung.core.REPS_RULER
import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.RulerScale
import io.github.kanylbullen.tung.core.StickyAction
import io.github.kanylbullen.tung.core.WEIGHT_RULER
import io.github.kanylbullen.tung.core.catalog.browseNameOrder
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.currentSet
import io.github.kanylbullen.tung.core.parseReps
import io.github.kanylbullen.tung.core.parseWeight
import io.github.kanylbullen.tung.core.stepReps
import io.github.kanylbullen.tung.core.stepWeight
import io.github.kanylbullen.tung.core.stickyAction
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
import org.jetbrains.compose.web.dom.CheckboxInput
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
    // View state, as the phone's ViewModel keeps it: a set chosen by hand, the
    // exercise last touched, the sheet that is open, and the rest timer.
    var selectedSetId by remember { mutableStateOf<String?>(null) }
    var touchedExerciseId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var rest by remember { mutableStateOf(RestStore.load()) }
    var restOpen by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Date.now().toLong()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            now = Date.now().toLong()
        }
    }
    // The rest is over: one buzz on a phone, then the countdown goes.
    LaunchedEffect(rest, now) {
        val timer = rest ?: return@LaunchedEffect
        if (timer.isFinishedAt(now)) {
            window.navigator.asDynamic().vibrate?.call(window.navigator, arrayOf(200, 100, 200))
            rest = null
            RestStore.save(null)
        }
    }

    fun startRest(exercise: ExerciseView) {
        val seconds = exercise.restSeconds ?: container.preferences.restSeconds.value
        rest = if (seconds > 0) RestTimer(Date.now().toLong() + seconds * 1000L, seconds * 1000L) else null
        RestStore.save(rest)
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
            rest?.let { timer ->
                Button({
                    classes("rest", "numbers")
                    attr("aria-label", strings.rest + " " + clockText(timer.remainingAt(now)))
                    onClick { restOpen = !restOpen }
                }) { Text(clockText(timer.remainingAt(now) + 999)) }
            }
            Button({
                classes("text-button")
                onClick { confirming = Confirm.FINISH }
            }) { Text(strings.finish) }
        }
        if (restOpen && rest != null) {
            Div({ classes("rest-actions") }) {
                Button({ classes("secondary"); onClick { rest = null; RestStore.save(null); restOpen = false } }) { Text(strings.skip) }
                Button({
                    classes("secondary")
                    onClick { rest = rest?.plus(Date.now().toLong()); RestStore.save(rest) }
                }) { Text(strings.plusThirty) }
            }
        }
        workout.routineName?.let { name -> Div({ classes("routine-name") }) { RoutineTitle(name) } }

        if (workout.exercises.isEmpty()) {
            P({ classes("empty") }) { Text(strings.emptyWorkout) }
        }
        val touchedIndex = workout.exercises.indexOfFirst { it.workoutExerciseId == touchedExerciseId }.takeIf { it >= 0 }
        val current = currentSet(workout.completed, selectedSetId?.let(workout::indexOf), touchedIndex)
        val currentId = current?.let { workout.exercises[it.exercise].sets[it.set].id }
        workout.exercises.forEach { exercise ->
            key(exercise.workoutExerciseId) {
                ExerciseBlock(
                    exercise = exercise,
                    container = container,
                    currentSetId = currentId,
                    run = { command ->
                        touchedExerciseId = exercise.workoutExerciseId
                        scope.launch { container.run(command) }
                    },
                    onEdit = { target -> editing = target },
                    onSelect = { setId -> selectedSetId = setId },
                    onTick = { set ->
                        touchedExerciseId = exercise.workoutExerciseId
                        selectedSetId = null
                        when {
                            !set.completed && set.reps == 0 -> editing = EditTarget(set.id, Field.REPS)
                            else -> scope.launch {
                                container.run(Command.TickSet(set.id, !set.completed))
                                if (!set.completed) startRest(exercise)
                            }
                        }
                    },
                )
            }
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

    // The sticky button, core's rule: Log set on the current set, else Add set.
    val touchedIndex = workout.exercises.indexOfFirst { it.workoutExerciseId == touchedExerciseId }.takeIf { it >= 0 }
    when (val action = stickyAction(workout.completed, selectedSetId?.let(workout::indexOf), touchedIndex)) {
        is StickyAction.LogSet -> {
            val exercise = workout.exercises[action.at.exercise]
            val set = exercise.sets[action.at.set]
            val values = weightText(set.weightKg, container.locale) + " × " + set.reps
            Button({
                classes("sticky")
                onClick {
                    touchedExerciseId = exercise.workoutExerciseId
                    selectedSetId = null
                    if (set.reps == 0) {
                        editing = EditTarget(set.id, Field.REPS)
                    } else {
                        scope.launch {
                            if (container.run(Command.LogSet(workout.id, set.id)) is CommandResult.Done) startRest(exercise)
                        }
                    }
                }
            }) { Text(strings.logSet(exercise.name, values)) }
        }
        is StickyAction.AddSet -> {
            val exercise = workout.exercises[action.exercise]
            val last = action.copyOf?.let { exercise.sets[it] }
            val values = last?.let { weightText(it.weightKg, container.locale) + " × " + it.reps } ?: ""
            Button({
                classes("sticky")
                onClick {
                    touchedExerciseId = exercise.workoutExerciseId
                    selectedSetId = null
                    scope.launch { container.run(Command.AddSet(exercise.workoutExerciseId)) }
                }
            }) { Text(strings.addSetSticky(exercise.name, values)) }
        }
        null -> Unit
    }

    editing?.let { target ->
        val exercise = workout.exercises.firstOrNull { e -> e.sets.any { it.id == target.setId } }
        val set = exercise?.sets?.firstOrNull { it.id == target.setId }
        if (exercise == null || set == null) {
            editing = null
        } else {
            InputSheet(
                container = container,
                field = target.field,
                initial = if (target.field == Field.WEIGHT) set.weightKg else set.reps.toDouble(),
                othersOpen = exercise.sets.any { it.id != set.id && !it.completed },
                onClose = { editing = null },
            ) { value, toAll ->
                editing = null
                touchedExerciseId = exercise.workoutExerciseId
                scope.launch {
                    if (target.field == Field.WEIGHT) {
                        container.run(Command.SetWeight(set.id, value, toAll))
                    } else {
                        container.run(Command.SetReps(set.id, value.toInt(), toAll))
                    }
                }
            }
        }
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
            // A workout with nothing logged is deleted by Finish: back to Home.
            val anyLogged = workout.loggedSets > 0
            scope.launch {
                container.run(Command.FinishWorkout(workout.id))
                if (anyLogged) window.location.hash = summaryHash(workout.id) else go(Route.HOME)
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

/** One exercise: its name (a tap makes its first open set current), "Last: …", its sets, and + Set. */
@Composable
private fun ExerciseBlock(
    exercise: ExerciseView,
    container: WebContainer,
    currentSetId: String?,
    run: (Command) -> Unit,
    onEdit: (EditTarget) -> Unit,
    onSelect: (String) -> Unit,
    onTick: (SetView) -> Unit,
) {
    val strings = container.strings
    val locale = container.locale
    var askingToRemove by remember { mutableStateOf(false) }
    val logged = exercise.sets.count { it.completed && it.reps > 0 }
    Div({ classes("exercise") }) {
        Div({ classes("exercise-head") }) {
            Button({
                classes("exercise-name")
                onClick { exercise.sets.firstOrNull { !it.completed }?.let { onSelect(it.id) } }
            }) { Text(exercise.name) }
            Button({
                classes("icon-button")
                attr("aria-label", strings.removeExercise(exercise.name))
                // As on the phone: at once without a logged set, else asked first.
                onClick { if (logged == 0) run(Command.RemoveExercise(exercise.workoutExerciseId)) else askingToRemove = true }
            }) { Text("×") }
        }
        if (exercise.last.isNotEmpty()) {
            P({ classes("last-line", "numbers") }) { Text(strings.last(setsText(exercise.last, locale))) }
        }
        Ul({ classes("sets") }) {
            exercise.sets.forEach { set ->
                key(set.id) { SetRow(set, set.id == currentSetId, container, onEdit, onSelect, onTick) }
            }
        }
        Button({
            classes("text-button")
            onClick { run(Command.AddSet(exercise.workoutExerciseId)) }
        }) { Text(strings.addSet) }
    }
    if (askingToRemove) {
        Dialog(
            title = strings.removeExerciseTitle(exercise.name, logged),
            body = "",
            confirm = strings.remove,
            cancel = strings.keepTraining,
            onCancel = { askingToRemove = false },
        ) {
            askingToRemove = false
            run(Command.RemoveExercise(exercise.workoutExerciseId))
        }
    }
}

/** A set: its number (a tap makes it current), the weight and reps (a tap opens the sheet), and the tick. */
@Composable
private fun SetRow(
    set: SetView,
    current: Boolean,
    container: WebContainer,
    onEdit: (EditTarget) -> Unit,
    onSelect: (String) -> Unit,
    onTick: (SetView) -> Unit,
) {
    val strings = container.strings
    Li({
        classes("set-row")
        if (set.completed) classes("done")
        if (current) classes("current")
    }) {
        Button({
            classes("set-number", "numbers")
            attr("aria-label", if (current) strings.currentSet else strings.makeCurrent(set.number))
            onClick { if (!set.completed) onSelect(set.id) }
        }) { Text(set.number.toString()) }
        Span({ classes("set-values", "numbers") }) {
            Button({
                classes("value")
                attr("aria-label", strings.weight + " " + weightText(set.weightKg, container.locale) + " kg")
                onClick { onEdit(EditTarget(set.id, Field.WEIGHT)) }
            }) {
                Span({ classes("big") }) { Text(weightText(set.weightKg, container.locale)) }
                Span({ classes("unit") }) { Text("kg") }
            }
            Span({ classes("times") }) { Text("×") }
            Button({
                classes("value")
                attr("aria-label", strings.reps + " " + set.reps)
                onClick { onEdit(EditTarget(set.id, Field.REPS)) }
            }) {
                Span({ classes("big") }) { Text(set.reps.toString()) }
                Span({ classes("unit") }) { Text("reps") }
            }
        }
        Button({
            classes("tick")
            if (set.completed) classes("on")
            attr("aria-pressed", set.completed.toString())
            attr("aria-label", strings.setDone(set.number))
            onClick { onTick(set) }
        }) { Text("✓") }
    }
}

/** Which number of which set the sheet edits. */
data class EditTarget(val setId: String, val field: Field)

enum class Field { WEIGHT, REPS }

/**
 * The input sheet, the app's one bold move: the number huge (type it, or),
 * the ruler under it, −/+ by the local step (core's [RulerScale.stepUp]),
 * "Apply to all sets not ticked" when there are others, and Done.
 */
@Composable
private fun InputSheet(
    container: WebContainer,
    field: Field,
    initial: Double,
    othersOpen: Boolean,
    onClose: () -> Unit,
    onDone: (Double, Boolean) -> Unit,
) {
    val strings = container.strings
    val scale = if (field == Field.WEIGHT) WEIGHT_RULER else REPS_RULER
    var value by remember { mutableStateOf(initial) }
    var typed by remember { mutableStateOf(format(field, initial, container.locale)) }
    var toAll by remember { mutableStateOf(false) }
    fun set(v: Double) {
        value = v
        typed = format(field, v, container.locale)
    }
    Div({ classes("sheet-backdrop"); onClick { onClose() } })
    Div({ classes("sheet", "input-sheet"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", if (field == Field.WEIGHT) strings.weight else strings.reps) }) {
        Div({ classes("sheet-label") }) { Text(if (field == Field.WEIGHT) strings.weight else strings.reps) }
        Input(InputType.Text) {
            classes("huge", "numbers")
            value(typed)
            attr("inputmode", if (field == Field.WEIGHT) "decimal" else "numeric")
            attr("aria-label", if (field == Field.WEIGHT) strings.weight else strings.reps)
            onInput { event ->
                typed = event.value
                val parsed = if (field == Field.WEIGHT) parseWeight(event.value) else parseReps(event.value)?.toDouble()
                if (parsed != null) value = parsed
            }
            onFocus { it.target.asDynamic().select() }
        }
        Div({ classes("sheet-unit") }) { Text(if (field == Field.WEIGHT) "kg" else "reps") }
        Ruler(scale, value, label = { format(field, it, container.locale) }) { v -> set(v) }
        Div({ classes("steppers") }) {
            Button({
                classes("stepper")
                attr("aria-label", strings.less)
                onClick { set(if (field == Field.WEIGHT) stepWeight(value, -1) else stepReps(value.toInt(), -1).toDouble()) }
            }) { Text("−") }
            Button({
                classes("stepper")
                attr("aria-label", strings.more)
                onClick { set(if (field == Field.WEIGHT) stepWeight(value, 1) else stepReps(value.toInt(), 1).toDouble()) }
            }) { Text("+") }
        }
        if (othersOpen) {
            Label(attrs = { classes("to-all") }) {
                CheckboxInput(toAll) { onChange { toAll = it.value } }
                Span { Text(strings.applyToOpenSets) }
            }
        }
        Button({ classes("primary"); onClick { onDone(value, toAll) } }) { Text(strings.done) }
    }
}

private fun format(field: Field, value: Double, locale: String): String =
    if (field == Field.WEIGHT) weightText(value, locale) else value.toInt().toString()

/** The rest timer kept across reloads of the page, in the browser's local storage. */
private object RestStore {
    private const val KEY = "tung-rest"

    fun load(): RestTimer? = try {
        window.localStorage.getItem(KEY)?.split(":")?.let { (ends, duration) -> RestTimer(ends.toLong(), duration.toLong()) }
            ?.takeIf { it.isRunningAt(Date.now().toLong()) }
    } catch (e: Throwable) {
        null
    }

    fun save(timer: RestTimer?) {
        try {
            if (timer == null) window.localStorage.removeItem(KEY) else window.localStorage.setItem(KEY, "${timer.endsAtMillis}:${timer.durationMillis}")
        } catch (e: Throwable) {
            // Private mode: the timer only lasts while the page does.
        }
    }
}

/** The exercise picker: a search field over every exercise in the store, by the name on screen. */
@Composable
fun Picker(container: WebContainer, onClose: () -> Unit, onPick: (String) -> Unit) {
    val strings = container.strings
    var query by remember { mutableStateOf("") }
    val order = remember { browseNameOrder(container.locale) }
    val available = container.preferences.equipment.collectAsState().value
    val rows = remember(query, available) {
        pickerRows(container.store, query, { exerciseDisplayName(it, container.catalogueNames, strings.tag) }, order, available)
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
        if (body.isNotEmpty()) P { Text(body) }
        Div({ classes("dialog-actions") }) {
            Button({ classes("secondary"); onClick { onCancel() } }) { Text(cancel) }
            Button({ classes("accent-button"); onClick { onConfirm() } }) { Text(confirm) }
        }
    }
}
