package io.github.kanylbullen.tung.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.RoutinePlan
import io.github.kanylbullen.tung.core.command.RoutinePlanExercise
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.extendedExerciseId
import io.github.kanylbullen.tung.core.sync.SyncTable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The four routine-editing commands (issue #41) through the app's
 * [AppCommandHandler], over a real Room database: what they write, the
 * positions they leave, and the tombstone and dirty flags sync relies on.
 */
@RunWith(RobolectricTestRunner::class)
class RoutineEditingCommandsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)
    private var now = 1_000L
    private val extended = ExtendedCatalog({ File("../catalog/extended.json").inputStream() })
    private val commands = AppCommandHandler(
        db = db,
        workouts = WorkoutRepository(db, clock = { now }),
        exercises = ExerciseRepository(db.exerciseDao(), clock = { now }),
        routines = RoutineRepository(db, clock = { now }),
        programs = ProgramRepository(db, clock = { now }),
        resolver = AppExerciseResolver(db.exerciseDao(), { mapOf("bench_press" to listOf("Bench press", "Bänkpress")) }, extended),
        extendedCatalog = extended,
    )
    private val bench = catalogExerciseId("bench_press")

    @After
    fun closeDatabase() = db.close()

    private fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    private fun column(sql: String): List<String?> =
        db.openHelper.readableDatabase.query(sql).use { c -> buildList { while (c.moveToNext()) add(if (c.isNull(0)) null else c.getString(0)) } }

    private fun run(command: Command): CommandResult = runBlocking { commands.execute(command) }

    private fun done(command: Command): CommandResult.Done {
        val result = run(command)
        assertTrue("$command: $result", result is CommandResult.Done)
        return result as CommandResult.Done
    }

    private fun clean() = db.openHelper.writableDatabase.apply {
        listOf("routine", "routine_exercise", "program", "program_day", "tombstones").forEach { execSQL("UPDATE $it SET dirty = 0") }
    }

    /** A routine of bench press (3 × 8) and back squat (3), as `save_routine_plan` makes it. */
    private fun routine(): String = checkNotNull(
        done(
            Command.SaveRoutinePlan(
                RoutinePlan(
                    "Push",
                    listOf(
                        RoutinePlanExercise(ExerciseRef.ByKey("bench_press"), 3, 8),
                        RoutinePlanExercise(ExerciseRef.ByKey("back_squat"), 3),
                    ),
                ),
            ),
        ).ids[CommandIds.ROUTINE_ID],
    )

    private fun order(routineId: String): List<String?> =
        column("SELECT exerciseId FROM routine_exercise WHERE routineId = '$routineId' ORDER BY position")

    private fun positions(routineId: String): List<String?> =
        column("SELECT position FROM routine_exercise WHERE routineId = '$routineId' ORDER BY position")

    @Test
    fun anAddedExerciseGoesLastWithTheDefaultTargets() {
        val routineId = routine()
        clean()
        now = 2_000L
        val result = done(Command.AddRoutineExercise(routineId, ExerciseRef.ByName("Bänkpress")))
        val added = checkNotNull(result.id(CommandIds.ROUTINE_EXERCISE_ID))
        assertEquals(bench, result.id(CommandIds.EXERCISE_ID))
        assertEquals(listOf(bench, catalogExerciseId("back_squat"), bench), order(routineId))
        assertEquals(listOf("0", "1", "2"), positions(routineId))
        assertEquals(
            "target sets 3, no target reps, stamped and dirty",
            1,
            count("SELECT COUNT(*) FROM routine_exercise WHERE id = '$added' AND targetSets = 3 AND targetReps IS NULL AND updatedAt = 2000 AND dirty = 1"),
        )

        val withTargets = done(Command.AddRoutineExercise(routineId, ExerciseRef.ByKey("deadlift"), targetSets = 5, targetReps = 5))
            .id(CommandIds.ROUTINE_EXERCISE_ID)
        assertEquals(1, count("SELECT COUNT(*) FROM routine_exercise WHERE id = '$withTargets' AND position = 3 AND targetSets = 5 AND targetReps = 5"))
    }

    @Test
    fun anExtendedExerciseGetsItsRowTheFirstTime() {
        val routineId = routine()
        val result = done(Command.AddRoutineExercise(routineId, ExerciseRef.Extended("Zercher_Squats")))
        val rowId = extendedExerciseId("Zercher_Squats")
        assertEquals(1, count("SELECT COUNT(*) FROM exercise WHERE id = '$rowId'"))
        assertEquals(rowId, order(routineId).last())
        assertEquals(rowId, result.id(CommandIds.EXERCISE_ID))
    }

    @Test
    fun anUnknownNameOrAMissingRoutineWritesNothing() {
        val routineId = routine()
        val exercises = count("SELECT COUNT(*) FROM exercise")
        val question = run(Command.AddRoutineExercise(routineId, ExerciseRef.ByName("Flying squirrel press")))
        assertTrue("$question", question is CommandResult.NeedsAnswers)
        assertEquals(2, count("SELECT COUNT(*) FROM routine_exercise"))

        val missing = run(Command.AddRoutineExercise("no-such-routine", ExerciseRef.Extended("Zercher_Squats")))
        assertTrue("$missing", missing is CommandResult.Rejected)
        assertEquals(2, count("SELECT COUNT(*) FROM routine_exercise"))
        assertEquals("not even the extended row", exercises, count("SELECT COUNT(*) FROM exercise"))

        val invalid = run(Command.AddRoutineExercise(routineId, ExerciseRef.ByKey("bench_press"), targetSets = 21))
        assertTrue("$invalid", invalid is CommandResult.Rejected)
    }

    @Test
    fun removingAnExerciseClosesTheGapAndLeavesATombstone() {
        val routineId = routine()
        done(Command.AddRoutineExercise(routineId, ExerciseRef.ByKey("deadlift")))
        val first = checkNotNull(column("SELECT id FROM routine_exercise WHERE routineId = '$routineId' AND position = 0").single())
        clean()
        now = 3_000L

        done(Command.RemoveRoutineExercise(first))
        assertEquals(listOf(catalogExerciseId("back_squat"), catalogExerciseId("deadlift")), order(routineId))
        assertEquals("positions closed", listOf("0", "1"), positions(routineId))
        assertEquals(
            "the moved rows are stamped and dirty",
            2,
            count("SELECT COUNT(*) FROM routine_exercise WHERE updatedAt = 3000 AND dirty = 1"),
        )
        assertEquals(
            1,
            count("SELECT COUNT(*) FROM tombstones WHERE tableName = '${SyncTable.ROUTINE_EXERCISES.key}' AND id = '$first' AND deletedAt = 3000 AND dirty = 1"),
        )

        // Gone already: nothing happens, nothing more is recorded.
        done(Command.RemoveRoutineExercise(first))
        assertEquals(1, count("SELECT COUNT(*) FROM tombstones"))
    }

    @Test
    fun targetRepsAreSetAndCleared() {
        val routineId = routine()
        val squat = checkNotNull(column("SELECT id FROM routine_exercise WHERE routineId = '$routineId' AND position = 1").single())
        clean()
        now = 4_000L

        done(Command.SetTargetReps(squat, 12))
        assertEquals(1, count("SELECT COUNT(*) FROM routine_exercise WHERE id = '$squat' AND targetReps = 12 AND updatedAt = 4000 AND dirty = 1"))

        now = 5_000L
        done(Command.SetTargetReps(squat, null))
        assertEquals(1, count("SELECT COUNT(*) FROM routine_exercise WHERE id = '$squat' AND targetReps IS NULL AND updatedAt = 5000"))

        clean()
        now = 6_000L
        done(Command.SetTargetReps(squat, null))
        assertEquals("the same value again writes nothing", 0, count("SELECT COUNT(*) FROM routine_exercise WHERE dirty = 1"))

        assertTrue(run(Command.SetTargetReps(squat, 0)) is CommandResult.Rejected)
        assertNull(column("SELECT targetReps FROM routine_exercise WHERE id = '$squat'").single())
    }

    @Test
    fun aProgramsModeSwitchesAndKeepsItsDays() {
        val routineId = routine()
        val programId = checkNotNull(done(Command.CreateProgram("PPL", ProgramMode.ROTATING)).id(CommandIds.PROGRAM_ID))
        val dayId = checkNotNull(done(Command.AddProgramDay(programId, routineId)).id(CommandIds.PROGRAM_DAY_ID))
        done(Command.ToggleProgramWeekday(dayId, 2))
        clean()
        now = 7_000L

        done(Command.SetProgramMode(programId, ProgramMode.FIXED))
        assertEquals(1, count("SELECT COUNT(*) FROM program WHERE id = '$programId' AND mode = 'fixed' AND updatedAt = 7000 AND dirty = 1"))
        assertEquals("the day and its weekday stay", 1, count("SELECT COUNT(*) FROM program_day WHERE id = '$dayId' AND weekdays = 2 AND dirty = 0"))

        clean()
        done(Command.SetProgramMode(programId, ProgramMode.FIXED))
        assertEquals("the same mode again writes nothing", 0, count("SELECT COUNT(*) FROM program WHERE dirty = 1"))

        done(Command.SetProgramMode(programId, ProgramMode.ROTATING))
        assertEquals(1, count("SELECT COUNT(*) FROM program WHERE id = '$programId' AND mode = 'rotating'"))
    }
}
