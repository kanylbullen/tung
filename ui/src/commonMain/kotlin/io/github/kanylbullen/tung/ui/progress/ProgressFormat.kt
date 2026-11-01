package io.github.kanylbullen.tung.ui.progress

import io.github.kanylbullen.tung.ui.common.skeletonDate
import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.runtime.Composable
import io.github.kanylbullen.tung.core.progress.Change
import io.github.kanylbullen.tung.core.progress.ExerciseRecords
import io.github.kanylbullen.tung.core.progress.PersonalRecord
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.core.progress.ProgressRange
import io.github.kanylbullen.tung.core.progress.RecordKind
import io.github.kanylbullen.tung.core.progress.SessionPoint
import io.github.kanylbullen.tung.core.progress.beatsRecord
import io.github.kanylbullen.tung.core.progress.repsToBeatE1rm
import io.github.kanylbullen.tung.core.progress.trendPoints
import io.github.kanylbullen.tung.ui.common.charts.ChartPoint
import io.github.kanylbullen.tung.ui.common.localVolume
import io.github.kanylbullen.tung.ui.common.localWeight
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How progress numbers are written: an estimated 1RM and a volume in whole
 * kilos (an estimate has no meaningful decimals), a top set as lifted.
 */
fun metricText(metric: ProgressMetric, kg: Double): String = when (metric) {
    ProgressMetric.E1RM -> localWeight(kg.roundToLong().toDouble())
    ProgressMetric.TOP_SET -> localWeight(kg)
    ProgressMetric.VOLUME, ProgressMetric.WEEKLY_VOLUME -> localVolume(kg)
}

/** "+7.5 kg", "−2 kg", or null when it rounds to no change. */
@Composable
fun signedChange(metric: ProgressMetric, change: Change): String? {
    val text = metricText(metric, abs(change.delta))
    if (metricText(metric, 0.0) == text) return null
    return stringResource(if (change.delta > 0) Res.string.progress_change_up else Res.string.progress_change_down, text)
}

/** A short month and day as the locale writes it: "Oct 21", "21 okt.". */
fun shortDate(date: LocalDate): String = skeletonDate(date, "MMMd")

/** Day, month and year as the locale writes them: "Oct 21, 2026". */
fun mediumDate(date: LocalDate): String = skeletonDate(date, "yMMMd")

@Composable
fun metricLabel(metric: ProgressMetric): String = stringResource(
    when (metric) {
        ProgressMetric.E1RM -> Res.string.progress_metric_e1rm
        ProgressMetric.TOP_SET -> Res.string.progress_metric_top_set
        ProgressMetric.VOLUME -> Res.string.progress_metric_volume
        ProgressMetric.WEEKLY_VOLUME -> Res.string.progress_metric_weekly_volume
    },
)

@Composable
fun metricLongLabel(metric: ProgressMetric): String = stringResource(
    when (metric) {
        ProgressMetric.E1RM -> Res.string.progress_metric_e1rm_long
        ProgressMetric.TOP_SET -> Res.string.progress_metric_top_set_long
        ProgressMetric.VOLUME -> Res.string.progress_metric_volume_long
        ProgressMetric.WEEKLY_VOLUME -> Res.string.progress_metric_weekly_volume_long
    },
)

@Composable
fun rangeLabel(range: ProgressRange): String = stringResource(
    when (range) {
        ProgressRange.THREE_MONTHS -> Res.string.progress_range_3m
        ProgressRange.ONE_YEAR -> Res.string.progress_range_1y
        ProgressRange.ALL -> Res.string.progress_range_all
    },
)

@Composable
fun rangeLongLabel(range: ProgressRange): String = stringResource(
    when (range) {
        ProgressRange.THREE_MONTHS -> Res.string.progress_range_3m_long
        ProgressRange.ONE_YEAR -> Res.string.progress_range_1y_long
        ProgressRange.ALL -> Res.string.progress_range_all_long
    },
)

/** The compact trends' span chips (the Progress tab's rows, the sheet in a workout): "3M", "12M". */
@Composable
fun trendRangeLabel(range: ProgressRange): String =
    if (range == ProgressRange.ONE_YEAR) stringResource(Res.string.progress_range_12m) else rangeLabel(range)

/** [trendRangeLabel] inside a sentence: "3 months", "12 months" (the exercise screen says "1 year"). */
@Composable
fun trendRangeLongLabel(range: ProgressRange): String =
    if (range == ProgressRange.ONE_YEAR) stringResource(Res.string.progress_range_12m_long) else rangeLongLabel(range)

/**
 * The points a line chart plots for [metric] over [range]: one per session
 * that has a value, or per week for weekly volume (core's [trendPoints]), x
 * the epoch day.
 */
fun chartPoints(sessions: List<SessionPoint>, metric: ProgressMetric, range: ProgressRange, today: LocalDate): List<ChartPoint> =
    trendPoints(sessions, metric, range, today).map { ChartPoint(it.date.toEpochDays().toLong(), it.value) }

/**
 * A chart's or a sparkline's summary over [span] ("3 months"): "Estimated
 * 1RM, 3 months: from 92 to 101 kg", or that there are too few sessions.
 */
@Composable
fun chartSummary(metric: ProgressMetric, span: String, values: List<Double>): String {
    val name = metricLongLabel(metric)
    // One value is a dot on a row's sparkline: say the value, not "log two workouts".
    if (values.size == 1) return stringResource(Res.string.progress_chart_summary_one, name, span, metricText(metric, values.first()))
    if (values.isEmpty()) return stringResource(Res.string.progress_chart_summary_empty, name, span)
    return stringResource(
        Res.string.progress_chart_summary,
        name,
        span,
        metricText(metric, values.first()),
        metricText(metric, values.last()),
    )
}

@Composable
fun recordLabel(kind: RecordKind): String = stringResource(
    when (kind) {
        RecordKind.HEAVIEST_WEIGHT -> Res.string.progress_record_heaviest
        RecordKind.BEST_E1RM -> Res.string.progress_record_e1rm
        RecordKind.MOST_REPS -> Res.string.progress_record_reps
        RecordKind.BEST_SESSION_VOLUME -> Res.string.progress_record_volume
    },
)

/** A record's value: "100 kg × 3", "114 kg (90 × 8)", "60 kg × 15", "1 440 kg". */
@Composable
fun recordValue(record: PersonalRecord): String {
    val set = record.set
    return when (record.kind) {
        RecordKind.BEST_E1RM -> if (set != null) {
            stringResource(Res.string.progress_e1rm_from, metricText(ProgressMetric.E1RM, record.value), localWeight(set.weightKg), set.reps)
        } else {
            stringResource(Res.string.progress_kg, metricText(ProgressMetric.E1RM, record.value))
        }
        RecordKind.BEST_SESSION_VOLUME -> stringResource(Res.string.progress_kg, localVolume(record.value))
        RecordKind.HEAVIEST_WEIGHT, RecordKind.MOST_REPS -> if (set != null) {
            stringResource(Res.string.detail_set_line, localWeight(set.weightKg), set.reps)
        } else {
            stringResource(Res.string.progress_kg, localWeight(record.value))
        }
    }
}

/**
 * "What beats your record" for a set of [weightKg] × [reps] about to be
 * logged: the record it would beat, else the reps at that weight that would
 * beat the best estimate, else that it is no record. Null without history.
 */
@Composable
fun recordLine(weightKg: Double, reps: Int, records: ExerciseRecords): String? {
    if (records.isEmpty) return null
    val weight = localWeight(weightKg)
    return when (beatsRecord(weightKg, reps, records)) {
        RecordKind.HEAVIEST_WEIGHT -> stringResource(Res.string.progress_beats_heaviest, weight, reps)
        RecordKind.BEST_E1RM -> stringResource(Res.string.progress_beats_e1rm, weight, reps)
        RecordKind.MOST_REPS -> stringResource(Res.string.progress_beats_reps, weight, reps)
        RecordKind.BEST_SESSION_VOLUME, null -> {
            val needed = repsToBeatE1rm(weightKg, records)
            when {
                needed != null -> stringResource(Res.string.progress_beats_e1rm, weight, needed)
                reps > 0 -> stringResource(Res.string.progress_no_record, weight, reps)
                else -> null
            }
        }
    }
}
