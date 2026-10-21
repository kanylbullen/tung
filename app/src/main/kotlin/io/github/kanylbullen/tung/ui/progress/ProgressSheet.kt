package io.github.kanylbullen.tung.ui.progress

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.core.progress.ProgressRange
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/** Sessions the sheet inside a workout lists. */
private const val SHEET_SESSIONS = 3

/**
 * An exercise's progress without leaving the workout: the estimated 1RM over
 * 3 months (compact), the last three sessions, and what [proposed] (the
 * exercise's current set, if it has one) would beat. Only finished workouts
 * count, so the workout in progress is not part of it. It reads and changes
 * nothing of the workout; dismissing it leaves the list where it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressSheet(exerciseId: String, exerciseName: String, proposed: LoggedSet?, onDismiss: () -> Unit) {
    val vm = exerciseProgressViewModel(exerciseId)
    val state by vm.state.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = TungCorner,
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.progress_sheet_title, exerciseName),
                style = MaterialTheme.typography.titleMedium,
                color = Tung.colors.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (state.loading) return@Column
            if (state.sessions.isEmpty()) {
                ProgressMuted(stringResource(R.string.progress_no_history), Modifier.padding(vertical = 12.dp))
                return@Column
            }
            proposed?.let { set ->
                recordLine(set.weightKg, set.reps, state.records)?.let { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.titleMedium,
                        color = Tung.colors.accentText,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            ProgressChart(state.sessions, ProgressMetric.E1RM, ProgressRange.THREE_MONTHS, state.today, height = 140.dp)
            ProgressHeading(stringResource(R.string.progress_last_sessions), Modifier.padding(top = 0.dp))
            state.sessions.takeLast(SHEET_SESSIONS).reversed().forEach { session ->
                SessionLine(mediumDate(session.date), session.sets)
            }
        }
    }
}
