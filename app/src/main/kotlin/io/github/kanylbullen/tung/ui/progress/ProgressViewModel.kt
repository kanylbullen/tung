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
import io.github.kanylbullen.tung.data.ProgressSettings
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

data class ProgressUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.MIN,
    val overview: ProgressOverview = ProgressOverview.EMPTY,
    /** The exercises by id, for their names. */
    val exercises: Map<String, Exercise> = emptyMap(),
    /** The exercise rows' metric: estimated 1RM, or volume per session or per week ([ProgressSettings]). */
    val trendMetric: ProgressMetric = ProgressSettings.DEFAULT_METRIC,
    /** The span of the exercise rows' sparkline and change: 3 or 12 months ([ProgressSettings]). */
    val trendRange: ProgressRange = ProgressSettings.DEFAULT_RANGE,
)

/**
 * The Progress tab: one read of every logged set of finished workouts
 * ([WorkoutRepository.observeProgressSets]), summed by core's
 * [progressOverview] off the main thread. "Today" and the zone come from
 * [clock], the container's. The rows' metric and span are [ProgressSettings]'s,
 * so they outlive the screen and are the sheet's in a workout too.
 */
class ProgressViewModel(
    workouts: WorkoutRepository,
    exercises: ExerciseRepository,
    private val settings: ProgressSettings,
    private val clock: Clock,
) : ViewModel() {
    private val data =
        combine(workouts.observeProgressSets(), exercises.observeExercises()) { sets, all ->
            val today = LocalDate.now(clock)
            ProgressUiState(
                loading = false,
                today = today,
                overview = progressOverview(sets, today, clock.zone),
                exercises = all.associateBy { it.id },
            )
        }.flowOn(Dispatchers.Default)

    val state: StateFlow<ProgressUiState> =
        combine(data, settings.trendMetric, settings.trendRange) { d, metric, range -> d.copy(trendMetric = metric, trendRange = range) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState())

    fun setTrendMetric(value: ProgressMetric) {
        viewModelScope.launch { settings.setTrendMetric(value) }
    }

    fun setTrendRange(value: ProgressRange) {
        viewModelScope.launch { settings.setTrendRange(value) }
    }
}

data class ExerciseProgressUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.MIN,
    val exercise: Exercise? = null,
    /** Every session, oldest first. */
    val sessions: List<SessionPoint> = emptyList(),
    val records: ExerciseRecords = ExerciseRecords.EMPTY,
    val metric: ProgressMetric = ProgressMetric.E1RM,
    val range: ProgressRange = ProgressRange.THREE_MONTHS,
)

/**
 * One exercise's progress: its sessions, records and the chart's metric and
 * range. Shared by the exercise progress screen, where the metric and range
 * start at estimated 1RM over 3 months and live as long as the screen, and
 * the sheet inside a workout, which passes [settings]: there they are the
 * Progress tab's rows', remembered, and a choice in the sheet is the rows'
 * too.
 */
class ExerciseProgressViewModel(
    private val exerciseId: String,
    workouts: WorkoutRepository,
    exercises: ExerciseRepository,
    private val clock: Clock,
    private val settings: ProgressSettings? = null,
) : ViewModel() {
    private val localMetric = MutableStateFlow(ProgressMetric.E1RM)
    private val localRange = MutableStateFlow(ProgressRange.THREE_MONTHS)
    private val metric: Flow<ProgressMetric> = settings?.trendMetric ?: localMetric
    private val range: Flow<ProgressRange> = settings?.trendRange ?: localRange

    private val data = combine(
        workouts.observeProgressSets(exerciseId),
        exercises.observeExercises().map { all -> all.firstOrNull { it.id == exerciseId } },
    ) { sets, exercise ->
        val sessions = sessionPoints(sets, clock.zone)
        ExerciseProgressUiState(
            loading = false,
            today = LocalDate.now(clock),
            exercise = exercise,
            sessions = sessions,
            records = exerciseRecords(sessions),
        )
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<ExerciseProgressUiState> =
        combine(data, metric, range) { d, m, r -> d.copy(metric = m, range = r) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseProgressUiState())

    fun setMetric(value: ProgressMetric) {
        if (settings == null) localMetric.value = value else viewModelScope.launch { settings.setTrendMetric(value) }
    }

    fun setRange(value: ProgressRange) {
        if (settings == null) localRange.value = value else viewModelScope.launch { settings.setTrendRange(value) }
    }
}
