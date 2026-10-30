package io.github.kanylbullen.tung.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.progress.WeekDay
import io.github.kanylbullen.tung.ui.common.localVolume
import io.github.kanylbullen.tung.ui.theme.Tung
import kotlinx.datetime.LocalDate
import java.time.format.TextStyle

/**
 * The seven days of this week, Monday first, as bars of the day's logged
 * volume: a trained day in the accent, the tallest the week's biggest day, an
 * empty day a short mark in the border colour, and [today] outlined so the
 * week has a cursor. One semantics node: a screen reader hears the trained
 * days with their volume ("Volume per day this week: Monday 3 020 kg, …").
 */
@Composable
fun WeekStrip(days: List<WeekDay>, today: LocalDate, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val max = days.maxOfOrNull { it.volumeKg } ?: 0.0
    val trained = days.filter { it.volumeKg > 0.0 }
    val listed = if (trained.isEmpty()) {
        stringResource(R.string.home_week_days_none)
    } else {
        trained.joinToString(stringResource(R.string.list_separator)) { day ->
            context.getString(R.string.home_week_day_volume, day.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale), localVolume(day.volumeKg))
        }
    }
    val description = stringResource(R.string.home_week_days_description, listed)
    val bar = RoundedCornerShape(3.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            val isToday = day.date == today
            val fraction = if (max > 0.0) (day.volumeKg / max).toFloat() else 0f
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (fraction > 0f) BAR_MIN + (BAR_MAX - BAR_MIN) * fraction else EMPTY_MARK)
                        .background(if (fraction > 0f) Tung.colors.accent else Tung.colors.border, bar)
                        .then(if (isToday) Modifier.border(2.dp, Tung.colors.text, bar) else Modifier),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) Tung.colors.text else Tung.colors.muted,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

private val STRIP_HEIGHT = 64.dp
private val BAR_MIN = 8.dp
private val BAR_MAX = 40.dp
private val EMPTY_MARK = 4.dp
