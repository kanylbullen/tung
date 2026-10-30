package io.github.kanylbullen.tung.web.progress

import io.github.kanylbullen.tung.core.progress.ExerciseRecords
import io.github.kanylbullen.tung.core.progress.ProgressOverview
import io.github.kanylbullen.tung.core.progress.SessionPoint
import io.github.kanylbullen.tung.core.progress.exerciseRecords
import io.github.kanylbullen.tung.core.progress.progressOverview
import io.github.kanylbullen.tung.core.progress.sessionPoints
import io.github.kanylbullen.tung.web.home.progressSets
import io.github.kanylbullen.tung.web.store.TungStore
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** The Progress tab's numbers, all core's: twelve weeks, the streak, every exercise trained, the recent records. */
fun overview(store: TungStore, today: LocalDate, zone: TimeZone): ProgressOverview =
    progressOverview(progressSets(store), today, zone)

/** One exercise's sessions (oldest first) and records, for its page. */
data class ExerciseHistoryView(val sessions: List<SessionPoint>, val records: ExerciseRecords)

fun exerciseHistory(store: TungStore, exerciseId: String, zone: TimeZone): ExerciseHistoryView {
    val sessions = sessionPoints(progressSets(store).filter { it.exerciseId == exerciseId }, zone)
    return ExerciseHistoryView(sessions, exerciseRecords(sessions))
}
