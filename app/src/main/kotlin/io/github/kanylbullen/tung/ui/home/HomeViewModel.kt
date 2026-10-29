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
import io.github.kanylbullen.tung.core.muscles.ExerciseWork
import io.github.kanylbullen.tung.core.muscles.MuscleBreakdown
import io.github.kanylbullen.tung.core.muscles.muscleBreakdown
import io.github.kanylbullen.tung.core.progress.ThisWeek
import io.github.kanylbullen.tung.core.progress.dateOf
import io.github.kanylbullen.tung.core.progress.thisWeek
import io.github.kanylbullen.tung.core.rotatingLabel
import io.github.kanylbullen.tung.core.suggestedDay
import io.github.kanylbullen.tung.data.CatalogueMuscles
import io.github.kanylbullen.tung.data.Program
import io.github.kanylbullen.tung.data.ProgramDayRow
import io.github.kanylbullen.tung.data.ProgramRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.data.WorkoutSummaryRow
import io.github.kanylbullen.tung.timer.RestTimerController
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

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

/**
 * Home's "This week": the week's days and totals (core's [thisWeek]) and the
 * muscles its logged sets worked (core's [muscleBreakdown] over the catalogue).
 */
data class WeekUi(val week: ThisWeek, val muscles: MuscleBreakdown)

data class HomeUiState(
    val loading: Boolean = true,
    val activeWorkout: Workout? = null,
    /** Null until the first read; shown once there is a finished workout at all. */
    val week: WeekUi? = null,
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
    private val restTimer: RestTimerController,
    catalogueMuscles: CatalogueMuscles,
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

    /** This week's days, totals and muscles, computed off the main thread from every counted set. */
    private val week: Flow<WeekUi> = combine(
        workouts.observeProgressSets(),
        flow { emit(catalogueMuscles.index()) },
        today,
    ) { sets, index, date ->
        val zone = clock.zone
        val week = thisWeek(sets, date, zone)
        val end = week.start.plusDays(6)
        val inWeek = sets.filter { set ->
            set.isLogged && dateOf(set.startedAt, zone).let { !it.isBefore(week.start) && !it.isAfter(end) }
        }
        val work = inWeek.groupBy { it.exerciseId }.map { (exerciseId, rows) -> ExerciseWork(index.musclesOf(exerciseId), rows.size) }
        WeekUi(week, muscleBreakdown(work))
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<HomeUiState> = combine(
        combine(
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
                // The few used most recently; the Routines tab, behind "All", has every one.
                routines = routineRows.take(HOME_ROUTINES),
                recent = finished.take(RECENT_COUNT),
                hasMore = finished.size > RECENT_COUNT,
            )
        },
        week,
    ) { base, week -> base.copy(week = week) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

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

    /**
     * Discards the workout in progress (issue #81): deleted with tombstones,
     * nothing saved. The rest timer and its alarm go first, as the workout
     * screen's discard and finish do, or "Rest over" would buzz for a workout
     * that no longer exists.
     */
    fun discard(workoutId: String) {
        viewModelScope.launch {
            restTimer.set(workoutId, null)
            commands.execute(Command.DeleteWorkout(workoutId))
        }
    }

    /** The workout a start command started or resumed; null if it did not (a routine that is gone). */
    private suspend fun start(command: Command): String? = commands.execute(command).id(CommandIds.WORKOUT_ID)

    private companion object {
        const val RECENT_COUNT = 5

        /** How many routines Home shows, most recently used first. */
        const val HOME_ROUTINES = 3

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
            val suggested = suggestedDay(mode, plan, date, active.lastCompletedDayId)
            return TodayUi(
                programName = active.program.name,
                mode = mode,
                suggestion = suggested?.let { s -> days.first { it.id == s.id } },
                days = days,
            )
        }
    }
}
