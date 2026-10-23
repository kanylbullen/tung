package io.github.kanylbullen.tung.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.RoutinePlan
import io.github.kanylbullen.tung.core.command.RoutinePlanExercise
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.sync.SyncTable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * `delete_routines` (issue #79) through the app's [AppCommandHandler]: one
 * transaction, the same cascade and tombstones as `delete_routine` for every
 * routine, history untouched, and ids that are gone ignored.
 */
@RunWith(RobolectricTestRunner::class)
class DeleteRoutinesCommandTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)
    private val now = 1_000L

    /** Every time the routine repository reads its clock: whether it was inside a transaction. */
    private val routineClockReads = mutableListOf<Boolean>()
    private val extended = ExtendedCatalog({ File("../catalog/extended.json").inputStream() })
    private val commands = AppCommandHandler(
        db = db,
        workouts = WorkoutRepository(db, clock = { now }),
        exercises = ExerciseRepository(db.exerciseDao(), clock = { now }),
        routines = RoutineRepository(db, clock = { routineClockReads += db.inTransaction(); now }),
        programs = ProgramRepository(db, clock = { now }),
        resolver = AppExerciseResolver(db.exerciseDao(), { emptyMap() }, extended),
        extendedCatalog = extended,
    )

    @After
    fun closeDatabase() = db.close()

    private fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    private fun done(command: Command): CommandResult.Done {
        val result = runBlocking { commands.execute(command) }
        assertTrue("$command: $result", result is CommandResult.Done)
        return result as CommandResult.Done
    }

    private fun routine(name: String): String = checkNotNull(
        done(
            Command.SaveRoutinePlan(
                RoutinePlan(
                    name,
                    listOf(
                        RoutinePlanExercise(ExerciseRef.ByKey("bench_press"), 2, 8),
                        RoutinePlanExercise(ExerciseRef.ByKey("back_squat"), 3),
                    ),
                ),
            ),
        ).id(CommandIds.ROUTINE_ID),
    )

    private fun tombstones(table: SyncTable): Set<String> = runBlocking {
        db.tombstoneDao().dirty().filter { it.tableName == table.key }.map { it.id }.toSet()
    }

    private fun column(sql: String): List<String> =
        db.openHelper.readableDatabase.query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test
    fun severalRoutinesGoInOneTransactionWithTheirExercisesAndProgramDays() {
        val a = routine("A")
        val b = routine("B")
        val kept = routine("Kept")
        val programId = checkNotNull(done(Command.CreateProgram("PPL", ProgramMode.ROTATING)).id(CommandIds.PROGRAM_ID))
        val dayA = checkNotNull(done(Command.AddProgramDay(programId, a)).id(CommandIds.PROGRAM_DAY_ID))
        val dayKept = checkNotNull(done(Command.AddProgramDay(programId, kept)).id(CommandIds.PROGRAM_DAY_ID))

        // A workout done from A, finished: history that must survive.
        val workoutId = checkNotNull(done(Command.StartRoutine(a)).id(CommandIds.WORKOUT_ID))
        done(Command.LogSet(workoutId))
        done(Command.FinishWorkout(workoutId))

        val exercisesOfAB = column("SELECT id FROM routine_exercise WHERE routineId IN ('$a', '$b')").toSet()
        routineClockReads.clear()

        val result = done(Command.DeleteRoutines(listOf(a, b, a)))

        assertEquals("a duplicate counts once", "2", result.id(CommandIds.DELETED_COUNT))
        assertEquals("one stamp, taken inside the transaction", listOf(true), routineClockReads)
        assertEquals(listOf(kept), column("SELECT id FROM routine"))
        assertEquals(setOf(a, b), tombstones(SyncTable.ROUTINES))
        assertEquals(4, exercisesOfAB.size)
        assertEquals(exercisesOfAB, tombstones(SyncTable.ROUTINE_EXERCISES))
        assertEquals("the program day of A goes, the other stays", setOf(dayA), tombstones(SyncTable.PROGRAM_DAYS))
        assertEquals(listOf(dayKept), column("SELECT id FROM program_day"))
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE id = '$workoutId' AND routineId = '$a' AND endedAt IS NOT NULL"))
        assertEquals("history keeps its sets", 1, count("SELECT COUNT(*) FROM set_entry"))
    }

    @Test
    fun idsThatAreGoneAreIgnored() {
        val a = routine("A")
        assertEquals("1", done(Command.DeleteRoutines(listOf("not-a-routine", a))).id(CommandIds.DELETED_COUNT))
        assertEquals("nothing left to delete is still done, like delete_routine", "0", done(Command.DeleteRoutines(listOf(a))).id(CommandIds.DELETED_COUNT))
        assertEquals(0, count("SELECT COUNT(*) FROM routine"))
    }

    @Test
    fun anEmptyOrBlankListIsRejected() {
        assertTrue(runBlocking { commands.execute(Command.DeleteRoutines(emptyList())) } is CommandResult.Rejected)
        assertTrue(runBlocking { commands.execute(Command.DeleteRoutines(listOf(" "))) } is CommandResult.Rejected)
    }
}
