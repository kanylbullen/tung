package io.github.kanylbullen.tung.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.PlanDay
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.maskToWeekdays
import io.github.kanylbullen.tung.core.rotatingLabel
import io.github.kanylbullen.tung.core.suggestedDay
import io.github.kanylbullen.tung.data.Program
import io.github.kanylbullen.tung.data.ProgramDayRow
import io.github.kanylbullen.tung.data.ProgramRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.data.WorkoutSummaryRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import kotlinx.datetime.toKotlinLocalDate

/** One day of the active program, ready to show and to start. */
data class ProgramDayUi(
    val id: String,
    val routineId: String,
    val routineName: String,
    /** "Day [number] of [count]", for rotating programs. */
    val number: Int,
    val count: Int,
    /** ISO weekdays, for fixed programs. */
    val weekdays: Set<Int>,
)

/**
 * Home's "Today": the active program and the day it suggests, or null for a
 * rest day (fixed mode, nothing on today's weekday).
 */
data class TodayUi(
    val programName: String,
    val mode: ProgramMode,
    val suggestion: ProgramDayUi?,
    val days: List<ProgramDayUi>,
)

data class HomeUiState(
    val loading: Boolean = true,
    val activeWorkout: Workout? = null,
    /** Null when no program is active, or the active one has no days: the section is not shown. */
    val today: TodayUi? = null,
    /** Most recently used first; empty means the section is not shown at all. */
    val routines: List<RoutineSummaryRow> = emptyList(),
    val recent: List<WorkoutSummaryRow> = emptyList(),
    val hasMore: Boolean = false,
)

private data class ActiveProgram(val program: Program, val days: List<ProgramDayRow>, val lastCompletedDayId: String?)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    workouts: WorkoutRepository,
    routines: RoutineRepository,
    programs: ProgramRepository,
    private val commands: CommandHandler,
    private val clock: Clock,
) : ViewModel() {

    private val activeProgram: Flow<ActiveProgram?> = programs.observeActiveProgram().flatMapLatest { program ->
        if (program == null) {
            flowOf(null)
        } else {
            combine(
                programs.observeDays(program.id),
                programs.observeLastCompletedDayId(program.id),
            ) { days, last -> ActiveProgram(program, days, last) }
        }
    }

    /** Today's date, re-read every minute so a fixed program moves on at midnight with the app open. */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now(clock))
            delay(60_000L)
        }
    }.distinctUntilChanged()

    val state: StateFlow<HomeUiState> = combine(
        workouts.observeActiveWorkout(),
        workouts.observeFinishedWorkouts(),
        routines.observeRoutines(),
        activeProgram,
        today,
    ) { active, finished, routineRows, program, _ ->
        HomeUiState(
            loading = false,
            activeWorkout = active,
            // The clock is read here rather than taken from the ticker, so any change
            // in the data also picks up a new day.
            today = program?.let { todayUi(it, LocalDate.now(clock)) },
            routines = routineRows,
            recent = finished.take(RECENT_COUNT),
            hasMore = finished.size > RECENT_COUNT,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Starts a workout, or resumes the one in progress, and hands back its id. */
    fun startOrResume(onReady: (String) -> Unit) {
        viewModelScope.launch { start(Command.StartWorkout())?.let(onReady) }
    }

    /** Starts a workout from a routine and hands back its id. */
    fun startRoutine(routineId: String, onReady: (String) -> Unit) {
        viewModelScope.launch { start(Command.StartRoutine(routineId))?.let(onReady) }
    }

    /** Starts a workout from a program day, which the workout records, and hands back its id. */
    fun startDay(day: ProgramDayUi, onReady: (String) -> Unit) {
        viewModelScope.launch { start(Command.StartRoutine(day.routineId, programDayId = day.id))?.let(onReady) }
    }

    /** The workout a start command started or resumed; null if it did not (a routine that is gone). */
    private suspend fun start(command: Command): String? = commands.execute(command).id(CommandIds.WORKOUT_ID)

    private companion object {
        const val RECENT_COUNT = 5

        fun todayUi(active: ActiveProgram, date: LocalDate): TodayUi? {
            if (active.days.isEmpty()) return null
            val mode = ProgramMode.fromKey(active.program.mode)
            val days = active.days.mapIndexed { index, row ->
                val label = rotatingLabel(index, active.days.size)
                ProgramDayUi(
                    id = row.id,
                    routineId = row.routineId,
                    routineName = row.routineName,
                    number = label.number,
                    count = label.count,
                    weekdays = maskToWeekdays(row.weekdays),
                )
            }
            val plan = days.mapIndexed { index, day -> PlanDay(day.id, index, day.weekdays) }
            val suggested = suggestedDay(mode, plan, date.toKotlinLocalDate(), active.lastCompletedDayId)
            return TodayUi(
                programName = active.program.name,
                mode = mode,
                suggestion = suggested?.let { s -> days.first { it.id == s.id } },
                days = days,
            )
        }
    }
}
