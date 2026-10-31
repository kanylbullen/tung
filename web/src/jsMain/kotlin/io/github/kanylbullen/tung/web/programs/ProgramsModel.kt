package io.github.kanylbullen.tung.web.programs

import io.github.kanylbullen.tung.core.PlanDay
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.maskToWeekdays
import io.github.kanylbullen.tung.core.suggestedDay
import io.github.kanylbullen.tung.web.store.TungStore
import kotlinx.datetime.LocalDate

/** One row of the programs list. */
data class ProgramRow(val id: String, val name: String, val mode: ProgramMode, val isActive: Boolean, val dayCount: Int)

/** One day of a program: its routine, its place and, in fixed mode, its ISO weekdays. */
data class ProgramDayView(val id: String, val routineId: String, val routineName: String, val position: Int, val weekdays: Set<Int>)

/** A program's page. */
data class ProgramView(val id: String, val name: String, val mode: ProgramMode, val isActive: Boolean, val days: List<ProgramDayView>)

/**
 * Home's "Today" for the active program: [day] is core's [suggestedDay]
 * (null on a fixed program's rest day), [days] every day for "Choose
 * another".
 */
data class TodayView(val programName: String, val mode: ProgramMode, val day: ProgramDayView?, val days: List<ProgramDayView>)

/** Every program, the active one first, then by name. */
fun programRows(store: TungStore): List<ProgramRow> {
    val dayCounts = store.programDays.rows.values.map { it.row }.groupingBy { it.programId }.eachCount()
    return store.programs.rows.values.map { it.row }
        .mapNotNull { p -> p.name?.let { ProgramRow(p.id, it, ProgramMode.fromKey(p.mode ?: ""), p.isActive ?: false, dayCounts[p.id] ?: 0) } }
        .sortedWith(compareByDescending<ProgramRow> { it.isActive }.thenBy { it.name.lowercase() })
}

/** The days of [programId] by position, each with its routine's name; a day whose routine is gone is left out. */
fun programDays(store: TungStore, programId: String): List<ProgramDayView> =
    store.programDays.rows.values.map { it.row }
        .filter { it.programId == programId }
        .mapNotNull { day ->
            val routineId = day.routineId ?: return@mapNotNull null
            val routine = store.routines.get(routineId) ?: return@mapNotNull null
            ProgramDayView(day.id, routineId, routine.name ?: "", day.position ?: 0, maskToWeekdays(day.weekdays ?: 0))
        }
        .sortedBy { it.position }

fun programView(store: TungStore, id: String): ProgramView? {
    val program = store.programs.get(id) ?: return null
    return ProgramView(id, program.name ?: "", ProgramMode.fromKey(program.mode ?: ""), program.isActive ?: false, programDays(store, id))
}

/**
 * Today's day of the active program, null when no program is active or it
 * has no days. Rotating mode goes on from the last completed day: the day
 * of the latest finished workout started from this program.
 */
fun todayView(store: TungStore, today: LocalDate): TodayView? {
    val program = store.programs.rows.values.map { it.row }.firstOrNull { it.isActive == true } ?: return null
    val days = programDays(store, program.id).takeIf { it.isNotEmpty() } ?: return null
    val ids = days.mapTo(HashSet()) { it.id }
    val lastCompleted = store.workouts.rows.values.map { it.row }
        .filter { it.endedAt != null && it.programDayId in ids }
        .maxByOrNull { it.startedAt ?: 0L }?.programDayId
    val mode = ProgramMode.fromKey(program.mode ?: "")
    val suggested = suggestedDay(mode, days.map { PlanDay(it.id, it.position, it.weekdays) }, today, lastCompleted)
    return TodayView(program.name ?: "", mode, days.firstOrNull { it.id == suggested?.id }, days)
}
