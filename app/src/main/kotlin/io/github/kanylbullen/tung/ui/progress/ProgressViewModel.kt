package io.github.kanylbullen.tung.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.progress.ExerciseRecords
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.core.progress.ProgressOverview
import io.github.kanylbullen.tung.core.progress.ProgressRange
import io.github.kanylbullen.tung.core.progress.SessionPoint
import io.github.kanylbullen.tung.core.progress.exerciseRecords
import io.github.kanylbullen.tung.core.progress.progressOverview
import io.github.kanylbullen.tung.core.progress.sessionPoints
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinTimeZone

data class ProgressUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.fromEpochDays(0),
    val overview: ProgressOverview = ProgressOverview.EMPTY,
    /** The exercises by id, for their names. */
    val exercises: Map<String, Exercise> = emptyMap(),
)

/**
 * The Progress tab: one read of every logged set of finished workouts
 * ([WorkoutRepository.observeProgressSets]), summed by core's
 * [progressOverview] off the main thread. "Today" and the zone come from
 * [clock], the container's.
 */
class ProgressViewModel(workouts: WorkoutRepository, exercises: ExerciseRepository, private val clock: Clock) : ViewModel() {
    val state: StateFlow<ProgressUiState> =
        combine(workouts.observeProgressSets(), exercises.observeExercises()) { sets, all ->
            val today = clock.today()
            ProgressUiState(
                loading = false,
                today = today,
                overview = progressOverview(sets, today, clock.zone.toKotlinTimeZone()),
                exercises = all.associateBy { it.id },
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState())
}

data class ExerciseProgressUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.fromEpochDays(0),
    val exercise: Exercise? = null,
    /** Every session, oldest first. */
    val sessions: List<SessionPoint> = emptyList(),
    val records: ExerciseRecords = ExerciseRecords.EMPTY,
    val metric: ProgressMetric = ProgressMetric.E1RM,
    val range: ProgressRange = ProgressRange.THREE_MONTHS,
)

/**
 * One exercise's progress: its sessions, records and the chart's metric and
 * range. Shared by the exercise progress screen and the sheet inside a
 * workout (which keeps the defaults: estimated 1RM over 3 months).
 */
class ExerciseProgressViewModel(
    private val exerciseId: String,
    workouts: WorkoutRepository,
    exercises: ExerciseRepository,
    private val clock: Clock,
) : ViewModel() {
    private val metric = MutableStateFlow(ProgressMetric.E1RM)
    private val range = MutableStateFlow(ProgressRange.THREE_MONTHS)

    private val data = combine(
        workouts.observeProgressSets(exerciseId),
        exercises.observeExercises().map { all -> all.firstOrNull { it.id == exerciseId } },
    ) { sets, exercise ->
        val sessions = sessionPoints(sets, clock.zone.toKotlinTimeZone())
        ExerciseProgressUiState(
            loading = false,
            today = clock.today(),
            exercise = exercise,
            sessions = sessions,
            records = exerciseRecords(sessions),
        )
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<ExerciseProgressUiState> =
        combine(data, metric, range) { d, m, r -> d.copy(metric = m, range = r) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseProgressUiState())

    fun setMetric(value: ProgressMetric) {
        metric.value = value
    }

    fun setRange(value: ProgressRange) {
        range.value = value
    }
}

/** The clock's date as core's `LocalDate` (kotlinx-datetime). */
private fun Clock.today(): LocalDate = java.time.LocalDate.now(this).toKotlinLocalDate()
