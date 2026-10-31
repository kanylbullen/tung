package io.github.kanylbullen.tung.ui.home

import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.ui.routines.ProgramDayChoiceRow
import io.github.kanylbullen.tung.ui.routines.programDayPlace
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/**
 * "Today" on Home: the active program's name, the suggested day with one
 * Start, or "Rest day" without one, and "Choose another" for the other days.
 */
@Composable
fun TodaySection(
    today: TodayUi,
    startEnabled: Boolean,
    onStart: (ProgramDayUi) -> Unit,
    onChooseAnother: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            today.programName,
            style = MaterialTheme.typography.titleMedium,
            color = Tung.colors.muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        val day = today.suggestion
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (day == null) stringResource(R.string.today_rest) else todayLine(today.mode, day),
                style = MaterialTheme.typography.titleLarge,
                color = Tung.colors.text,
                modifier = Modifier.weight(1f),
            )
            if (day != null) {
                Spacer(Modifier.width(12.dp))
                val description = stringResource(R.string.today_start_description, day.routineName)
                Button(
                    onClick = { onStart(day) },
                    enabled = startEnabled,
                    shape = TungCorner,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Tung.colors.raised,
                        contentColor = Tung.colors.text,
                        disabledContainerColor = Tung.colors.background,
                        disabledContentColor = Tung.colors.muted,
                    ),
                    elevation = null,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
                ) {
                    Text(stringResource(Res.string.routine_start), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        TextButton(
            onClick = onChooseAnother,
            enabled = startEnabled,
            modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp),
        ) {
            Text(
                stringResource(R.string.today_choose_another),
                color = if (startEnabled) Tung.colors.text else Tung.colors.muted,
            )
        }
    }
}

/** "Today: Push day" for a fixed program, "Day 2 of 3: Pull day" for a rotating one. */
@Composable
private fun todayLine(mode: ProgramMode, day: ProgramDayUi): String = when (mode) {
    ProgramMode.FIXED -> stringResource(R.string.today_fixed, day.routineName)
    ProgramMode.ROTATING ->
        stringResource(R.string.today_rotating, stringResource(R.string.program_day_of, day.number, day.count), day.routineName)
}

/** Every day of the active program; tapping one starts it instead of the suggestion. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseDaySheet(today: TodayUi, onDismiss: () -> Unit, onChoose: (ProgramDayUi) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = TungCorner,
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.today_choose_title),
                style = MaterialTheme.typography.titleMedium,
                color = Tung.colors.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            today.days.forEach { day ->
                ProgramDayChoiceRow(
                    routineName = day.routineName,
                    place = programDayPlace(today.mode, day.number, day.count, day.weekdays),
                    onClick = { onChoose(day) },
                )
            }
        }
    }
}
