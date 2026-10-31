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
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.history.exerciseDisplayName
import io.github.kanylbullen.tung.web.programs.programRows
import io.github.kanylbullen.tung.web.routines.RoutineExerciseView
import io.github.kanylbullen.tung.web.routines.routineMuscles
import io.github.kanylbullen.tung.web.routines.routineRows
import io.github.kanylbullen.tung.web.routines.routineView
import io.github.kanylbullen.tung.web.routines.workoutInProgress
import io.github.kanylbullen.tung.web.sync.Account
import kotlinx.browser.window
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

/** `#/routines/<id>`: the path of a routine's page. */
fun routineHash(id: String): String = "#/routines/$id"

/**
 * The Routines tab: every routine by last use, each with Start (off while a
 * workout is in progress or for a routine without exercises) and its page
 * behind a tap; + New routine asks for a name and opens it.
 */
@Composable
fun RoutinesScreen(container: WebContainer, account: Account) {
    val strings = container.strings
    val version = container.store.version.collectAsState().value
    val rows = remember(version) { routineRows(container.store) }
    val busy = remember(version) { workoutInProgress(container.store) }
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    val catalogue = container.catalogue.collectAsState().value
    val muscles = remember(version, catalogue) { routineMuscles(container.store, catalogue.muscles) }

    // Selection mode (the phone's long press): null when off, else the
    // chosen ids, of which only routines that still exist count.
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    val selected = selection?.let { chosen -> rows.map { it.id }.filter { it in chosen }.toSet() }
    var deleting by remember { mutableStateOf(false) }

    Div({ classes("screen") }) {
        if (selected == null) {
            TopBar(container, account) { H1 { Text(strings.routines) } }
            val programs = remember(version) { programRows(container.store) }
            A(href = Route.PROGRAMS.hash, attrs = { classes("card", "card-link") }) {
                Div({ classes("card-title") }) { Text(strings.programs) }
                P({ classes("hint") }) { Text(programs.firstOrNull { it.isActive }?.let { strings.programsActive(it.name) } ?: strings.programsHint) }
            }
            Div({ classes("action-row") }) {
                Button({ classes("secondary"); onClick { naming = true } }) { Text(strings.newRoutine) }
                if (rows.isNotEmpty()) Button({ classes("secondary"); onClick { selection = emptySet() } }) { Text(strings.select) }
            }
        } else {
            Div({ classes("topbar", "topbar-start", "selection-bar") }) {
                Button({ classes("text-button"); attr("aria-label", strings.leaveSelection); onClick { selection = null } }) { Text("✕") }
                H1({ classes("detail-title") }) { Text(strings.selectedCount(selected.size)) }
                if (selected.size < rows.size) {
                    Button({ classes("text-button"); onClick { selection = rows.map { it.id }.toSet() } }) { Text(strings.selectAll) }
                }
                Button({
                    classes("text-button", "danger-text")
                    attr("aria-label", strings.deleteSelected)
                    if (selected.isEmpty()) disabled()
                    onClick { deleting = true }
                }) { Text(strings.delete) }
            }
        }
        if (rows.isEmpty()) {
            P({ classes("empty") }) { Text(strings.routinesAllEmpty) }
        } else {
            Ul({ classes("rows") }) {
                rows.forEach { routine ->
                    key(routine.id) {
                        if (selected != null) {
                            val on = routine.id in selected
                            Li {
                                Label(attrs = { classes("row", "select-row") }) {
                                    RoutineMuscles(muscles[routine.id], strings)
                                    Div({ classes("main") }) {
                                        Div({ classes("title") }) { RoutineTitle(routine.name) }
                                        Div({ classes("details", "numbers") }) { Text(strings.exerciseCount(routine.exerciseCount)) }
                                    }
                                    Input(InputType.Checkbox) {
                                        checked(on)
                                        // Read the state at the click, not the composition's copy:
                                        // two quick clicks would otherwise keep only the second.
                                        onChange {
                                            val now = selection ?: emptySet()
                                            selection = if (routine.id in now) now - routine.id else now + routine.id
                                        }
                                    }
                                }
                            }
                        } else {
                            Li({ classes("row") }) {
                                RoutineMuscles(muscles[routine.id], strings)
                                A(href = routineHash(routine.id), attrs = { classes("main", "row-link") }) {
                                    Div({ classes("title") }) { RoutineTitle(routine.name) }
                                    Div({ classes("details", "numbers") }) { Text(strings.exerciseCount(routine.exerciseCount)) }
                                }
                                Button({
                                    classes("secondary", "start")
                                    if (busy || routine.exerciseCount == 0) disabled()
                                    onClick {
                                        scope.launch {
                                            if (container.run(Command.StartRoutine(routine.id)) is CommandResult.Done) go(Route.WORKOUT)
                                        }
                                    }
                                }) { Text(strings.start) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (deleting && selected != null) {
        val days = remember(version, selected) { container.store.programDays.rows.values.count { it.row.routineId in selected } }
        val body = listOf(strings.deleteManyBody) + (if (days > 0) listOf(strings.deleteManyDays(days)) else emptyList())
        Dialog(strings.deleteManyTitle(selected.size), body.joinToString(" "), strings.delete, strings.cancel, onCancel = { deleting = false }) {
            deleting = false
            val ids = selected.toList()
            scope.launch {
                if (container.run(Command.DeleteRoutines(ids)) is CommandResult.Done) selection = null
            }
        }
    }
    if (naming) {
        NameDialog(strings.newRoutineTitle, strings.routineName, "", strings.save, strings.cancel, onCancel = { naming = false }) { name ->
            naming = false
            scope.launch {
                val result = container.run(Command.CreateRoutine(name))
                (result as? CommandResult.Done)?.ids?.get(CommandIds.ROUTINE_ID)?.let { window.location.hash = routineHash(it) }
            }
        }
    }
}

/**
 * A routine's page, the phone's routine detail: Start, the exercises with
 * their target sets (−/+) and reps (a dialog), moved up and down or
 * removed, + Exercise through the picker, Rename and Delete. Every change
 * is a command.
 */
@Composable
fun RoutineScreen(container: WebContainer, id: String) {
    val strings = container.strings
    val version = container.store.version.collectAsState().value
    val routine = remember(version, id) { routineView(container.store, id) { exerciseDisplayName(it, container.catalogueNames, strings.tag) } }
    val busy = remember(version) { workoutInProgress(container.store) }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var editingReps by remember { mutableStateOf<RoutineExerciseView?>(null) }
    fun run(command: Command) { scope.launch { container.run(command) } }

    Div({ classes("screen") }) {
        Div({ classes("topbar", "topbar-start") }) {
            A(href = Route.ROUTINES.hash, attrs = { classes("text-button"); attr("aria-label", strings.routines) }) { Text("←") }
            H1({ classes("detail-title") }) { if (routine != null) RoutineTitle(routine.name) }
        }
        if (routine == null) {
            P({ classes("empty") }) { Text(strings.notFound) }
            return@Div
        }
        Button({
            classes("primary")
            if (busy || routine.exercises.isEmpty()) disabled()
            onClick { scope.launch { if (container.run(Command.StartRoutine(routine.id)) is CommandResult.Done) go(Route.WORKOUT) } }
        }) { Text(strings.start) }
        if (routine.exercises.isEmpty()) P({ classes("empty") }) { Text(strings.routineEmptyHint) }
        // The muscles the routine works, weighted by target sets: what kind of day it is, before the list.
        val catalogue = container.catalogue.collectAsState().value
        val breakdown = remember(version, catalogue, id) { routineMuscles(container.store, catalogue.muscles)[id] }
        if (breakdown != null && !breakdown.isEmpty) {
            val items = breakdown.muscles.map { strings.muscleName(it.muscle) + " " + (if (it.percent == 0) "<1%" else "${it.percent}%") }
            MuscleMap(strings.routineMuscles(items.joinToString(", ")), "muscle-routine", heatShade(breakdown))
            P({ classes("hint", "center") }) { Text(items.take(3).joinToString(strings.separator)) }
        }
        Ul({ classes("routine-exercises") }) {
            routine.exercises.forEachIndexed { index, exercise ->
                key(exercise.id) {
                    Li({ classes("routine-exercise") }) {
                        Div({ classes("routine-exercise-head") }) {
                            H2 { Text(exercise.name) }
                            Div({ classes("move") }) {
                                Button({
                                    classes("icon-button")
                                    attr("aria-label", strings.moveUp)
                                    if (index == 0) disabled()
                                    onClick { run(Command.MoveRoutineExercise(exercise.id, -1)) }
                                }) { Text("↑") }
                                Button({
                                    classes("icon-button")
                                    attr("aria-label", strings.moveDown)
                                    if (index == routine.exercises.lastIndex) disabled()
                                    onClick { run(Command.MoveRoutineExercise(exercise.id, 1)) }
                                }) { Text("↓") }
                                Button({
                                    classes("icon-button")
                                    attr("aria-label", strings.removeExercise(exercise.name))
                                    onClick { run(Command.RemoveRoutineExercise(exercise.id)) }
                                }) { Text("×") }
                            }
                        }
                        Div({ classes("targets") }) {
                            Button({
                                classes("stepper-small")
                                attr("aria-label", strings.fewerSets(exercise.name))
                                if (exercise.targetSets <= 1) disabled()
                                onClick { run(Command.AdjustTargetSets(exercise.id, -1)) }
                            }) { Text("−") }
                            Span({ classes("target-sets", "numbers") }) { Text("${exercise.targetSets} ${strings.setsShort}") }
                            Button({
                                classes("stepper-small")
                                attr("aria-label", strings.moreSets(exercise.name))
                                if (exercise.targetSets >= 20) disabled()
                                onClick { run(Command.AdjustTargetSets(exercise.id, 1)) }
                            }) { Text("+") }
                            Button({ classes("text-button"); onClick { editingReps = exercise } }) { Text(strings.targetReps(exercise.targetReps)) }
                        }
                    }
                }
            }
        }
        Button({ classes("secondary", "wide"); onClick { picking = true } }) { Text(strings.addRoutineExercise) }
        Div({ classes("routine-actions") }) {
            Button({ classes("text-button"); onClick { renaming = true } }) { Text(strings.rename) }
            Button({ classes("text-button", "danger-text"); onClick { deleting = true } }) { Text(strings.deleteRoutine) }
        }
    }

    if (routine == null) return
    if (picking) {
        Picker(container, onClose = { picking = false }) { exerciseId ->
            picking = false
            run(Command.AddRoutineExercise(routine.id, ExerciseRef.ById(exerciseId)))
        }
    }
    if (renaming) {
        NameDialog(strings.renameRoutine, strings.routineName, routine.name, strings.save, strings.cancel, onCancel = { renaming = false }) { name ->
            renaming = false
            run(Command.RenameRoutine(routine.id, name))
        }
    }
    if (deleting) {
        Dialog(strings.deleteRoutineTitle, strings.deleteRoutineBody, strings.delete, strings.cancel, onCancel = { deleting = false }) {
            deleting = false
            scope.launch {
                container.run(Command.DeleteRoutine(routine.id))
                go(Route.ROUTINES)
            }
        }
    }
    editingReps?.let { exercise ->
        RepsDialog(container, exercise, onClose = { editingReps = null }) { reps ->
            editingReps = null
            run(Command.SetTargetReps(exercise.id, reps))
        }
    }
}

/** Target reps: a number from 1 to 999, or none. */
@Composable
private fun RepsDialog(container: WebContainer, exercise: RoutineExerciseView, onClose: () -> Unit, onDone: (Int?) -> Unit) {
    val strings = container.strings
    var text by remember { mutableStateOf(exercise.targetReps?.toString() ?: "") }
    val reps = text.trim().toIntOrNull()?.takeIf { it in 1..999 }
    Div({ classes("sheet-backdrop"); onClick { onClose() } })
    Div({ classes("dialog"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", strings.targetRepsTitle(exercise.name)) }) {
        H2 { Text(strings.targetRepsTitle(exercise.name)) }
        Label(attrs = { classes("field", "dialog-field") }) {
            Span { Text(strings.reps) }
            Input(InputType.Number) {
                value(text)
                attr("inputmode", "numeric")
                attr("min", "1")
                attr("max", "999")
                attr("autofocus", "")
                onInput { text = it.value?.toString() ?: "" }
            }
        }
        Div({ classes("dialog-actions") }) {
            Button({ classes("secondary"); onClick { onDone(null) } }) { Text(strings.clearTargetReps) }
            Button({ classes("accent-button"); if (reps == null) disabled(); onClick { onDone(reps) } }) { Text(strings.save) }
        }
    }
}
