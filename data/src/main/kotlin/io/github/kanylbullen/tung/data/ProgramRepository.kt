package io.github.kanylbullen.tung.data

import androidx.room.withTransaction
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.CommandRules
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_DAY_ID
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_ID
import io.github.kanylbullen.tung.core.command.id
import kotlinx.coroutines.flow.Flow

/**
 * All program reads, and the Room-side entry to the program writes for
 * callers that are not commands (tests): each runs core's rule
 * ([CommandRules], issue #87) over this database, as `AppCommandHandler`
 * does for the commands. Starting a program day goes through
 * [RoutineRepository.start] with the day's id, so the workout records it.
 */
class ProgramRepository(
    private val db: TungDatabase,
    private val dao: ProgramDao = db.programDao(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val executor = CommandRules()

    private fun tx() = RoomCommandTx(db, clock)

    fun observePrograms(): Flow<List<ProgramSummaryRow>> = dao.observeProgramSummaries()

    fun observeProgram(id: String): Flow<Program?> = dao.observeProgram(id)

    fun observeActiveProgram(): Flow<Program?> = dao.observeActiveProgram()

    fun observeDays(programId: String): Flow<List<ProgramDayRow>> = dao.observeDays(programId)

    /** The day of the program's latest finished workout, which a rotating program follows. */
    fun observeLastCompletedDayId(programId: String): Flow<String?> = dao.observeLastCompletedDayId(programId)

    /** Creates an inactive, empty program and returns its id. */
    suspend fun create(name: String, mode: ProgramMode): String = db.withTransaction {
        checkNotNull(executor.createProgram(tx(), name, mode).id(PROGRAM_ID))
    }

    /** Makes [programId] the one active program; every other program is deactivated in the same transaction. */
    suspend fun activate(programId: String) = db.withTransaction {
        executor.activateProgram(tx(), programId)
        Unit
    }

    /** Deletes the program and its days, with a tombstone for each. Workouts started from it are kept. */
    suspend fun delete(programId: String) = db.withTransaction {
        executor.deleteProgram(tx(), programId)
        Unit
    }

    /** Adds [routineId] as the program's last day, with no weekdays. Returns the day's id. */
    suspend fun addDay(programId: String, routineId: String): String = db.withTransaction {
        checkNotNull(executor.addProgramDay(tx(), programId, routineId).id(PROGRAM_DAY_ID))
    }

    /** Removes one day and closes the gap, so the rest keep counting 1, 2, 3. */
    suspend fun removeDay(dayId: String) = db.withTransaction {
        executor.removeProgramDay(tx(), dayId)
        Unit
    }

    /** Switches [weekday] (ISO, 1 Monday … 7 Sunday) on or off for one day. */
    suspend fun toggleWeekday(dayId: String, weekday: Int) = db.withTransaction {
        executor.toggleProgramWeekday(tx(), dayId, weekday)
        Unit
    }
}
