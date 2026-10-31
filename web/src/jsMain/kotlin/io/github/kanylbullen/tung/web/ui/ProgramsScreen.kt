package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.ISO_WEEKDAYS
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.rotatingLabel
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.programs.ProgramDayView
import io.github.kanylbullen.tung.web.programs.programRows
import io.github.kanylbullen.tung.web.programs.programView
import io.github.kanylbullen.tung.web.routines.routineRows
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
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

/** `#/programs/<id>`: a program's page. */
fun programHash(id: String): String = "#/programs/$id"

/** Monday 6 January 2025, so ISO weekday n is this date plus n − 1 days. */
private val A_MONDAY = LocalDate(2025, 1, 6)

/** ISO weekday [day] as the locale writes it short ("Mon", "mån"). */
fun isoWeekdayName(day: Int, locale: String): String = weekdayName(A_MONDAY.plus(DatePeriod(days = day - 1)), locale)

/** What a day is called: "Day 2 of 3" in rotating mode, else its weekdays ("Mon, Thu") or "No weekdays". */
fun dayLabel(day: ProgramDayView, mode: ProgramMode, count: Int, index: Int, container: WebContainer): String = when (mode) {
    ProgramMode.ROTATING -> rotatingLabel(index, count).let { container.strings.dayOf(it.number, it.count) }
    ProgramMode.FIXED -> day.weekdays.sorted().takeIf { it.isNotEmpty() }?.joinToString(", ") { isoWeekdayName(it, container.locale) }
        ?: container.strings.noWeekdays
}

/**
 * The programs list, reached from the Routines tab: every program with its
 * mode and days, the active one first and marked; + New program asks for a
 * name and a mode, then opens it.
 */
@Composable
fun ProgramsScreen(container: WebContainer) {
    val strings = container.strings
    val version = container.store.version.collectAsState().value
    val rows = remember(version) { programRows(container.store) }
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    Div({ classes("screen") }) {
        Div({ classes("topbar", "topbar-start") }) {
            A(href = Route.ROUTINES.hash, attrs = { classes("text-button"); attr("aria-label", strings.routines) }) { Text("←") }
            H1({ classes("detail-title") }) { Text(strings.programs) }
        }
        Button({ classes("secondary", "wide"); onClick { creating = true } }) { Text("+ " + strings.newProgram) }
        if (rows.isEmpty()) {
            P({ classes("empty") }) { Text(strings.programsEmpty) }
        } else {
            Ul({ classes("rows") }) {
                rows.forEach { program ->
                    key(program.id) {
                        Li {
                            A(href = programHash(program.id), attrs = { classes("row", "row-link") }) {
                                Div({ classes("main") }) {
                                    Div({ classes("title") }) { Span({ classes("plain") }) { Text(program.name) } }
                                    Div({ classes("details") }) {
                                        Text((if (program.mode == ProgramMode.FIXED) strings.modeFixed else strings.modeRotating) + strings.separator + strings.dayCount(program.dayCount))
                                    }
                                }
                                if (program.isActive) Span({ classes("badge") }) { Text(strings.active) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        NewProgramDialog(container, onCancel = { creating = false }) { name, mode ->
            creating = false
            scope.launch {
                val result = container.run(Command.CreateProgram(name, mode))
                (result as? CommandResult.Done)?.ids?.get(CommandIds.PROGRAM_ID)?.let { window.location.hash = programHash(it) }
            }
        }
    }
}

/** The two modes as radio rows, the phone's `ProgramModeChoice`. */
@Composable
private fun ModeChoice(container: WebContainer, chosen: ProgramMode, onChoose: (ProgramMode) -> Unit) {
    val strings = container.strings
    Div({ classes("mode-choice"); attr("role", "radiogroup"); attr("aria-label", strings.mode) }) {
        listOf(
            Triple(ProgramMode.FIXED, strings.modeFixed, strings.modeFixedHint),
            Triple(ProgramMode.ROTATING, strings.modeRotating, strings.modeRotatingHint),
        ).forEach { (mode, title, hint) ->
            Label(attrs = { classes("mode-row") }) {
                Input(InputType.Radio) {
                    attr("name", "program-mode")
                    checked(mode == chosen)
                    onChange { onChoose(mode) }
                }
                Span {
                    Span({ classes("mode-title") }) { Text(title) }
                    Span({ classes("mode-hint") }) { Text(hint) }
                }
            }
        }
    }
}

@Composable
private fun NewProgramDialog(container: WebContainer, onCancel: () -> Unit, onCreate: (String, ProgramMode) -> Unit) {
    val strings = container.strings
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ProgramMode.FIXED) }
    Div({ classes("sheet-backdrop"); onClick { onCancel() } })
    Div({ classes("dialog"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", strings.newProgram) }) {
        H2 { Text(strings.newProgram) }
        Label(attrs = { classes("field", "dialog-field") }) {
            Span { Text(strings.routineName) }
            Input(InputType.Text) {
                value(name)
                attr("autofocus", "")
                attr("maxlength", "200")
                onInput { name = it.value }
            }
        }
        ModeChoice(container, mode) { mode = it }
        Div({ classes("dialog-actions") }) {
            Button({ classes("secondary"); onClick { onCancel() } }) { Text(strings.cancel) }
            Button({
                classes("accent-button")
                if (name.isBlank()) disabled()
                onClick { onCreate(name.trim(), mode) }
            }) { Text(strings.create) }
        }
    }
}

/**
 * A program's page: Activate or Deactivate, the mode, its days (each a
 * routine; in fixed mode with a chip per weekday, in rotating mode "Day 2
 * of 3"), moved up and down or removed, Add day through a list of
 * routines, Rename and Delete. Every change is a command.
 */
@Composable
fun ProgramScreen(container: WebContainer, id: String) {
    val strings = container.strings
    val version = container.store.version.collectAsState().value
    val program = remember(version, id) { programView(container.store, id) }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    fun run(command: Command) { scope.launch { container.run(command) } }

    Div({ classes("screen") }) {
        Div({ classes("topbar", "topbar-start") }) {
            A(href = Route.PROGRAMS.hash, attrs = { classes("text-button"); attr("aria-label", strings.programs) }) { Text("←") }
            H1({ classes("detail-title") }) { Text(program?.name ?: strings.programs) }
        }
        if (program == null) {
            P({ classes("empty") }) { Text(strings.programNotFound) }
            return@Div
        }
        if (program.isActive) {
            Div({ classes("action-row") }) {
                Span({ classes("badge") }) { Text(strings.active) }
                Button({ classes("secondary"); onClick { run(Command.DeactivateProgram(program.id)) } }) { Text(strings.deactivate) }
            }
        } else {
            Button({
                classes("primary")
                attr("aria-label", strings.activate + " " + program.name)
                onClick { run(Command.ActivateProgram(program.id)) }
            }) { Text(strings.activate) }
        }
        H2({ classes("section") }) { Text(strings.mode) }
        ModeChoice(container, program.mode) { if (it != program.mode) run(Command.SetProgramMode(program.id, it)) }

        if (program.days.isEmpty()) P({ classes("empty") }) { Text(strings.programDaysEmpty) }
        Ul({ classes("routine-exercises") }) {
            program.days.forEachIndexed { index, day ->
                key(day.id) {
                    Li({ classes("routine-exercise") }) {
                        Div({ classes("routine-exercise-head") }) {
                            Div {
                                H2 { Text(day.routineName) }
                                P({ classes("details") }) { Text(dayLabel(day, program.mode, program.days.size, index, container)) }
                            }
                            Div({ classes("move") }) {
                                Button({
                                    classes("icon-button")
                                    attr("aria-label", strings.moveUp)
                                    if (index == 0) disabled()
                                    onClick { run(Command.MoveProgramDay(day.id, -1)) }
                                }) { Text("↑") }
                                Button({
                                    classes("icon-button")
                                    attr("aria-label", strings.moveDown)
                                    if (index == program.days.lastIndex) disabled()
                                    onClick { run(Command.MoveProgramDay(day.id, 1)) }
                                }) { Text("↓") }
                                Button({
                                    classes("icon-button")
                                    attr("aria-label", strings.removeDay + ", " + day.routineName)
                                    onClick { run(Command.RemoveProgramDay(day.id)) }
                                }) { Text("×") }
                            }
                        }
                        if (program.mode == ProgramMode.FIXED) {
                            Div({ classes("chips", "flush", "weekdays") }) {
                                ISO_WEEKDAYS.forEach { weekday ->
                                    val on = weekday in day.weekdays
                                    Button({
                                        classes("chip")
                                        if (on) classes("on")
                                        attr("aria-pressed", on.toString())
                                        onClick { run(Command.ToggleProgramWeekday(day.id, weekday)) }
                                    }) { Text(isoWeekdayName(weekday, container.locale)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Button({ classes("secondary", "wide"); onClick { adding = true } }) { Text(strings.addDay) }
        Div({ classes("routine-actions") }) {
            Button({ classes("text-button"); onClick { renaming = true } }) { Text(strings.rename) }
            Button({ classes("text-button", "danger-text"); onClick { deleting = true } }) { Text(strings.deleteProgram) }
        }
    }

    if (program == null) return
    if (adding) {
        val routines = remember(version) { routineRows(container.store) }
        Div({ classes("sheet-backdrop"); onClick { adding = false } })
        Div({ classes("sheet"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", strings.chooseRoutineForDay) }) {
            Div({ classes("sheet-head") }) {
                H2 { Text(strings.chooseRoutineForDay) }
                Button({ classes("text-button"); onClick { adding = false } }) { Text(strings.close) }
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
                                        adding = false
                                        run(Command.AddProgramDay(program.id, routine.id))
                                    }
                                }) { Text(routine.name) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (renaming) {
        NameDialog(strings.renameProgram, strings.routineName, program.name, strings.save, strings.cancel, onCancel = { renaming = false }) { name ->
            renaming = false
            run(Command.RenameProgram(program.id, name))
        }
    }
    if (deleting) {
        Dialog(strings.deleteProgramTitle, strings.deleteProgramBody, strings.delete, strings.cancel, onCancel = { deleting = false }) {
            deleting = false
            scope.launch {
                container.run(Command.DeleteProgram(program.id))
                go(Route.PROGRAMS)
            }
        }
    }
}
