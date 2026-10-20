package io.github.kanylbullen.tung.data

import androidx.room.withTransaction
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.toggleWeekday
import kotlinx.coroutines.flow.Flow

/**
 * All program reads and writes. Starting a program day goes through
 * [RoutineRepository.start] with the day's id, so the workout records it.
 */
class ProgramRepository(
    private val db: TungDatabase,
    private val dao: ProgramDao = db.programDao(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observePrograms(): Flow<List<ProgramSummaryRow>> = dao.observeProgramSummaries()

    fun observeProgram(id: String): Flow<Program?> = dao.observeProgram(id)

    fun observeActiveProgram(): Flow<Program?> = dao.observeActiveProgram()

    fun observeDays(programId: String): Flow<List<ProgramDayRow>> = dao.observeDays(programId)

    /** The day of the program's latest finished workout, which a rotating program follows. */
    fun observeLastCompletedDayId(programId: String): Flow<String?> = dao.observeLastCompletedDayId(programId)

    /** Creates an inactive, empty program and returns its id. */
    suspend fun create(name: String, mode: ProgramMode): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "a program needs a name" }
        val program = Program(id = newId(), name = trimmed, mode = mode.key, isActive = false, updatedAt = clock())
        dao.insertProgram(program)
        return program.id
    }

    suspend fun rename(programId: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "a program needs a name" }
        dao.rename(programId, trimmed, clock())
    }

    /** Makes [programId] the one active program; every other program is deactivated in the same transaction. */
    suspend fun activate(programId: String) = db.withTransaction {
        dao.getProgram(programId) ?: return@withTransaction
        val now = clock()
        dao.deactivateOthers(programId, now)
        dao.setActive(programId, true, now)
    }

    /** Leaves no program active, if [programId] was the active one. */
    suspend fun deactivate(programId: String) = dao.setActive(programId, false, clock())

    /** Deletes the program and its days, with a tombstone for each. Workouts started from it are kept. */
    suspend fun delete(programId: String) = db.withTransaction {
        dao.getProgram(programId) ?: return@withTransaction
        val now = clock()
        db.recordDeletes(SyncTable.PROGRAM_DAYS, dao.dayIds(programId), now)
        db.recordDeletes(SyncTable.PROGRAMS, listOf(programId), now)
        dao.deleteProgram(programId)
    }

    /** Adds [routineId] as the program's last day, with no weekdays. Returns the day's id. */
    suspend fun addDay(programId: String, routineId: String): String = db.withTransaction {
        val day = ProgramDay(
            id = newId(),
            programId = programId,
            routineId = routineId,
            position = dao.maxPosition(programId) + 1,
            weekdays = 0,
            updatedAt = clock(),
        )
        dao.insertDay(day)
        day.id
    }

    /** Removes one day and closes the gap, so the rest keep counting 1, 2, 3. */
    suspend fun removeDay(dayId: String) = db.withTransaction {
        val day = dao.getDay(dayId) ?: return@withTransaction
        db.recordDeletes(SyncTable.PROGRAM_DAYS, listOf(dayId), clock())
        dao.deleteDay(dayId)
        renumber(day.programId)
    }

    /**
     * Moves one day [delta] places (-1 up, +1 down), reading the order inside
     * the transaction. A move past either end does nothing.
     */
    suspend fun moveDay(dayId: String, delta: Int) = db.withTransaction {
        val day = dao.getDay(dayId) ?: return@withTransaction
        val days = dao.getDays(day.programId).toMutableList()
        val from = days.indexOfFirst { it.id == dayId }
        val to = from + delta
        if (from < 0 || to !in days.indices) return@withTransaction
        days.add(to, days.removeAt(from))
        writePositions(days)
    }

    /** Switches [weekday] (ISO, 1 Monday … 7 Sunday) on or off for one day; reads the current value itself. */
    suspend fun toggleWeekday(dayId: String, weekday: Int) = db.withTransaction {
        val day = dao.getDay(dayId) ?: return@withTransaction
        dao.setWeekdays(dayId, toggleWeekday(day.weekdays, weekday), clock())
    }

    private suspend fun renumber(programId: String) = writePositions(dao.getDays(programId))

    /** Gives [days] the positions 0, 1, 2 … in list order; only rows whose position changes are written. */
    private suspend fun writePositions(days: List<ProgramDay>) {
        val now = clock()
        days.forEachIndexed { position, day ->
            if (day.position != position) dao.setPosition(day.id, position, now)
        }
    }
}
