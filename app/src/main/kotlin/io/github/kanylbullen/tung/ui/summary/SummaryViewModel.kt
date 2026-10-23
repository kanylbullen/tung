package io.github.kanylbullen.tung.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.MuscleIndex
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.muscles.ExerciseWork
import io.github.kanylbullen.tung.core.muscles.MuscleBreakdown
import io.github.kanylbullen.tung.core.muscles.muscleBreakdown
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.core.progress.ProgressRange
import io.github.kanylbullen.tung.core.progress.SessionPoint
import io.github.kanylbullen.tung.core.progress.sessionPoints
import io.github.kanylbullen.tung.data.CatalogueMuscles
import io.github.kanylbullen.tung.data.ProgressSettings
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.ui.common.WorkoutReport
import io.github.kanylbullen.tung.ui.common.buildWorkoutReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

/** One exercise of the workout with every session of it, this workout's included, for its progress chart. */
data class ExerciseSessions(
    val exerciseId: String,
    /** Fallback name; shown through `exerciseDisplayName(nameKey, name)`. */
    val name: String,
    val nameKey: String?,
    val sessions: List<SessionPoint>,
)

data class SummaryUiState(
    val loading: Boolean = true,
    val report: WorkoutReport? = null,
    /** The workout's sets per muscle (core's [muscleBreakdown]). */
    val muscles: MuscleBreakdown = MuscleBreakdown.EMPTY,
    val today: LocalDate = LocalDate.MIN,
    /** Each exercise of the workout once, in workout order. */
    val progress: List<ExerciseSessions> = emptyList(),
    /** The charts' metric and span: the Progress tab's remembered choice ([ProgressSettings]). */
    val trendMetric: ProgressMetric = ProgressSettings.DEFAULT_METRIC,
    val trendRange: ProgressRange = ProgressSettings.DEFAULT_RANGE,
)

/**
 * The summary after Finish: the report (sets, volume, best set, records per
 * exercise), the muscles it worked by the catalogue ([catalogueMuscles];
 * custom exercises have none), and each exercise's progress over every
 * finished workout, this one included, charted with the compact trends'
 * metric and span, which a choice here changes for the Progress tab and the
 * workout sheet too. Computed off the main thread; "today" and the zone are
 * [clock]'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryViewModel(
    private val workoutId: String,
    repo: WorkoutRepository,
    private val commands: CommandHandler,
    catalogueMuscles: CatalogueMuscles,
    private val settings: ProgressSettings,
    private val clock: Clock,
) : ViewModel() {
    private val report =
        combine(repo.observeWorkout(workoutId), repo.observeExercises(workoutId)) { w, e -> w to e }
            .mapLatest { (workout, exercises) -> workout?.let { buildWorkoutReport(it, exercises, repo, withRecords = true) } }

    private val data =
        combine(report, flow<MuscleIndex> { emit(catalogueMuscles.index()) }, repo.observeProgressSets()) { report, index, sets ->
            val exercises = report?.exercises.orEmpty()
            val byExercise = sets.groupBy { it.exerciseId }
            SummaryUiState(
                loading = false,
                report = report,
                muscles = muscleBreakdown(exercises.map { ExerciseWork(index.musclesOf(it.exerciseId), it.sets.size) }),
                today = LocalDate.now(clock),
                progress = exercises.distinctBy { it.exerciseId }.map { e ->
                    ExerciseSessions(e.exerciseId, e.name, e.nameKey, sessionPoints(byExercise[e.exerciseId].orEmpty(), clock.zone))
                },
            )
        }.flowOn(Dispatchers.Default)

    val state: StateFlow<SummaryUiState> =
        combine(data, settings.trendMetric, settings.trendRange) { d, metric, range -> d.copy(trendMetric = metric, trendRange = range) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUiState())

    fun setTrendMetric(value: ProgressMetric) {
        viewModelScope.launch { settings.setTrendMetric(value) }
    }

    fun setTrendRange(value: ProgressRange) {
        viewModelScope.launch { settings.setTrendRange(value) }
    }

    /** Saves this workout as a new routine; [onSaved] gets false if there was nothing to save. */
    fun saveAsRoutine(name: String, onSaved: (Boolean) -> Unit) {
        viewModelScope.launch { onSaved(commands.execute(Command.SaveRoutine(workoutId, name)).id(CommandIds.ROUTINE_ID) != null) }
    }
}
