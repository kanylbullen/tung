package io.github.kanylbullen.tung.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.catalogExerciseId
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
 * The repositories' half of sync: every write marks its row dirty, every
 * delete leaves a tombstone for the row and each row it cascades to, and an
 * untouched catalogue exercise is never dirty.
 */
@RunWith(RobolectricTestRunner::class)
class DirtyTrackingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)
    private var now = 1_000L
    private val workouts = WorkoutRepository(db, clock = { now })
    private val routines = RoutineRepository(db, clock = { now })
    private val programs = ProgramRepository(db, clock = { now })
    private val exercises = ExerciseRepository(db, clock = { now })
    private val bench = catalogExerciseId("bench_press")

    @After
    fun closeDatabase() = db.close()

    private fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    private fun tombstones(table: SyncTable): Int =
        count("SELECT COUNT(*) FROM tombstones WHERE dirty = 1 AND tableName = '${table.key}' AND deletedAt = $now")

    private fun clean() = db.openHelper.writableDatabase.apply {
        listOf("exercise", "workout", "workout_exercise", "set_entry", "routine", "routine_exercise", "program", "program_day", "tombstones")
            .forEach { execSQL("UPDATE $it SET dirty = 0") }
    }

    @Test
    fun theSeededCatalogueIsNotDirtyUntilItsRestTimeChanges() = runBlocking {
        assertTrue(count("SELECT COUNT(*) FROM exercise") > 0)
        assertEquals(0, count("SELECT COUNT(*) FROM exercise WHERE dirty = 1"))
        assertEquals(0, db.syncDao().dirtyExercises().size)

        exercises.setRestSeconds(bench, 60)
        assertEquals(listOf(bench), db.syncDao().dirtyExercises().map { it.id })
    }

    @Test
    fun everyWriteMarksItsRowDirty() = runBlocking {
        val workoutId = workouts.startOrResumeWorkout()
        val workoutExerciseId = workouts.addExercise(workoutId, bench, sets = 1)
        val setId = workouts.getSets(workoutExerciseId).single().id
        assertEquals(1, count("SELECT COUNT(*) FROM set_entry WHERE dirty = 1"))

        clean()
        now = 2_000
        workouts.updateWeight(setId, 60.0)
        workouts.updateReps(setId, 5)
        workouts.logSet(setId, addNext = true) // the tick and the added set
        assertEquals(2, count("SELECT COUNT(*) FROM set_entry WHERE dirty = 1"))
        assertEquals(1, count("SELECT COUNT(*) FROM set_entry WHERE dirty = 1 AND updatedAt = 2000 AND id = '$setId'"))

        clean()
        workouts.finishWorkout(workoutId)
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE dirty = 1 AND endedAt IS NOT NULL"))

        clean()
        val routineId = checkNotNull(routines.saveFromWorkout(workoutId, "A"))
        routines.rename(routineId, "B")
        val programId = programs.create("P", ProgramMode.FIXED)
        val dayId = programs.addDay(programId, routineId)
        programs.toggleWeekday(dayId, 1)
        programs.activate(programId)
        assertEquals(1, count("SELECT COUNT(*) FROM routine WHERE dirty = 1"))
        assertEquals(1, count("SELECT COUNT(*) FROM routine_exercise WHERE dirty = 1"))
        assertEquals(1, count("SELECT COUNT(*) FROM program WHERE dirty = 1 AND isActive = 1"))
        assertEquals(1, count("SELECT COUNT(*) FROM program_day WHERE dirty = 1 AND weekdays = 1"))
    }

    @Test
    fun removingAnExerciseTombstonesItAndItsSets() = runBlocking {
        val workoutId = workouts.startOrResumeWorkout()
        val workoutExerciseId = workouts.addExercise(workoutId, bench, sets = 1)
        workouts.addSet(workoutExerciseId)
        val oneSet = workouts.getSets(workoutExerciseId).first().id

        now = 5_000
        workouts.deleteSet(oneSet)
        assertEquals(1, tombstones(SyncTable.SETS))

        now = 6_000
        workouts.removeExercise(workoutExerciseId)
        assertEquals(1, tombstones(SyncTable.SETS))
        assertEquals(1, tombstones(SyncTable.WORKOUT_EXERCISES))
    }

    @Test
    fun finishingTombstonesWhatItDropsAndDiscardingTombstonesTheWorkout() = runBlocking {
        val kept = workouts.startOrResumeWorkout()
        val keptExercise = workouts.addExercise(kept, bench, sets = 1)
        val logged = workouts.getSets(keptExercise).single().id
        workouts.updateReps(logged, 5)
        workouts.logSet(logged, addNext = true) // adds an unticked set, dropped on finish
        workouts.addExercise(kept, catalogExerciseId("back_squat"), sets = 1)
        now = 7_000
        workouts.finishWorkout(kept)
        assertEquals(2, tombstones(SyncTable.SETS)) // the unticked bench set and the squat's set
        assertEquals(1, tombstones(SyncTable.WORKOUT_EXERCISES)) // the squat, left empty
        assertEquals(0, tombstones(SyncTable.WORKOUTS))

        val discarded = workouts.startOrResumeWorkout()
        workouts.addExercise(discarded, bench, sets = 1)
        now = 8_000
        workouts.finishWorkout(discarded)
        assertEquals(1, tombstones(SyncTable.SETS))
        assertEquals(1, tombstones(SyncTable.WORKOUT_EXERCISES))
        assertEquals(1, tombstones(SyncTable.WORKOUTS))
    }

    @Test
    fun deletingAWorkoutTombstonesEverythingInIt() = runBlocking {
        val workoutId = workouts.startOrResumeWorkout()
        workouts.addSet(workouts.addExercise(workoutId, bench, sets = 1))
        workouts.addExercise(workoutId, catalogExerciseId("back_squat"), sets = 1)
        now = 9_000
        workouts.deleteWorkout(workoutId)
        assertEquals(3, tombstones(SyncTable.SETS))
        assertEquals(2, tombstones(SyncTable.WORKOUT_EXERCISES))
        assertEquals(1, tombstones(SyncTable.WORKOUTS))
        assertEquals(0, count("SELECT COUNT(*) FROM set_entry"))
    }

    @Test
    fun deletingARoutineTombstonesItsExercisesAndTheProgramDaysThatUsedIt() = runBlocking {
        val routineId = routines.createFromPlan("Legs", listOf(Triple(bench, 3, 5), Triple(catalogExerciseId("back_squat"), 3, 5)))
        val programId = programs.create("P", ProgramMode.ROTATING)
        programs.addDay(programId, routineId)
        val otherRoutine = routines.createFromPlan("Push", listOf(Triple(bench, 3, 5)))
        val otherDay = programs.addDay(programId, otherRoutine)

        now = 10_000
        routines.delete(routineId)
        assertEquals(1, tombstones(SyncTable.ROUTINES))
        assertEquals(2, tombstones(SyncTable.ROUTINE_EXERCISES))
        assertEquals(1, tombstones(SyncTable.PROGRAM_DAYS))

        now = 11_000
        programs.removeDay(otherDay)
        assertEquals(1, tombstones(SyncTable.PROGRAM_DAYS))

        now = 12_000
        programs.addDay(programId, otherRoutine)
        programs.delete(programId)
        assertEquals(1, tombstones(SyncTable.PROGRAMS))
        assertEquals(1, tombstones(SyncTable.PROGRAM_DAYS))
    }

    /** The rule is "a DAO statement that stamps updatedAt also sets dirty": checked on the source, so a new one cannot forget it. */
    @Test
    fun everyDaoUpdateThatStampsUpdatedAtAlsoSetsDirty() {
        val dir = listOf(File("src/commonMain/kotlin/io/github/kanylbullen/tung/data"), File("data/src/commonMain/kotlin/io/github/kanylbullen/tung/data"))
            .first { it.isDirectory }
        val setClause = Regex("""UPDATE\s+(\w+)\s+SET\s+(.*?)\s+WHERE""")
        val offenders = dir.listFiles { f -> f.name.endsWith("Dao.kt") }.orEmpty().flatMap { file ->
            setClause.findAll(file.readText()).map { it.groupValues[2] }
                .filter { "updatedAt = :updatedAt" in it && "dirty = 1" !in it }
                .map { "${file.name}: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
