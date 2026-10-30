package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncRoutineExercise
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.github.kanylbullen.tung.web.store.Tombstone
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.store.Write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The store's merge (`apply`), the phone's `SyncStore.apply` rules over memory. */
class TungStoreTest {

    private val bench = SyncExercise("ex-bench", "bench_press", "Bench press", "chest", "barbell", false, null, updatedAt = 0)
    private val workout = SyncWorkout("w1", startedAt = 1_000, endedAt = 4_000, updatedAt = 10)
    private val workoutExercise = SyncWorkoutExercise("we1", "w1", "ex-bench", 0, updatedAt = 10)
    private val set = SyncSet("s1", "we1", 0, 80.0, 8, true, updatedAt = 10)

    private fun response(changes: SyncChanges, cursor: Long = 1) = SyncResponse(cursor, changes)

    private fun fullWorkout() = SyncChanges(
        exercises = listOf(bench),
        workouts = listOf(workout),
        workoutExercises = listOf(workoutExercise),
        sets = listOf(set),
    )

    @Test
    fun insertsParentsFirstAndJournalsEveryRow() {
        val store = TungStore()
        val journal = ArrayList<Write>()
        val changed = store.apply(response(fullWorkout()), SyncChanges(), journal)
        assertEquals(4, changed)
        assertEquals(80.0, store.sets.get("s1")?.weightKg)
        assertFalse(store.sets.rows.getValue("s1").dirty, "a row from the server is not dirty")
        assertEquals(
            listOf(SyncTable.EXERCISES, SyncTable.WORKOUTS, SyncTable.WORKOUT_EXERCISES, SyncTable.SETS),
            journal.map { (it as Write.Put).table },
        )
    }

    @Test
    fun aRowWithoutItsParentIsSkipped() {
        val store = TungStore()
        val changed = store.apply(response(SyncChanges(sets = listOf(set))), SyncChanges(), ArrayList())
        assertEquals(0, changed)
        assertNull(store.sets.get("s1"))
    }

    @Test
    fun strictlyNewerWinsAndEqualOrOlderIsIgnored() {
        val store = TungStore()
        store.apply(response(fullWorkout()), SyncChanges(), ArrayList())
        val older = set.copy(weightKg = 70.0, updatedAt = 9)
        val equal = set.copy(weightKg = 75.0, updatedAt = 10)
        val newer = set.copy(weightKg = 85.0, updatedAt = 11)
        assertEquals(0, store.apply(response(SyncChanges(sets = listOf(older, equal))), SyncChanges(), ArrayList()))
        assertEquals(80.0, store.sets.get("s1")?.weightKg)
        assertEquals(1, store.apply(response(SyncChanges(sets = listOf(newer))), SyncChanges(), ArrayList()))
        assertEquals(85.0, store.sets.get("s1")?.weightKg)
    }

    @Test
    fun aNullFromAPeerKeepsTheRequiredAndTheNullKeepsFields() {
        val store = TungStore()
        store.apply(response(SyncChanges(workouts = listOf(workout.copy(startedBy = "phone", avgHeartRate = 130)))), SyncChanges(), ArrayList())
        val fromOlderPeer = SyncWorkout("w1", startedAt = null, endedAt = 5_000, updatedAt = 20)
        store.apply(response(SyncChanges(workouts = listOf(fromOlderPeer))), SyncChanges(), ArrayList())
        val stored = assertNotNull(store.workouts.get("w1"))
        assertEquals(1_000, stored.startedAt, "a required field the row left null comes from the local row")
        assertEquals(5_000, stored.endedAt)
        assertEquals("phone", stored.startedBy)
        assertEquals(130, stored.avgHeartRate)
    }

    @Test
    fun aNewerTombstoneDeletesWithItsChildren() {
        val store = TungStore()
        store.apply(response(fullWorkout()), SyncChanges(), ArrayList())
        val journal = ArrayList<Write>()
        val tombstone = SyncWorkout("w1", updatedAt = 11, deletedAt = 11)
        assertEquals(1, store.apply(response(SyncChanges(workouts = listOf(tombstone))), SyncChanges(), journal))
        assertNull(store.workouts.get("w1"))
        assertNull(store.workoutExercises.get("we1"), "Room's cascade: the workout's exercises go")
        assertNull(store.sets.get("s1"), "and their sets")
        assertEquals(
            listOf(SyncTable.SETS to "s1", SyncTable.WORKOUT_EXERCISES to "we1", SyncTable.WORKOUTS to "w1"),
            journal.map { (it as Write.Delete).table to it.id },
            "children first, and no tombstone of the browser's own: the server has it",
        )
        assertTrue(store.tombstones.isEmpty())
    }

    @Test
    fun anExerciseStillInUseIsNotDeleted() {
        val store = TungStore()
        store.apply(response(fullWorkout()), SyncChanges(), ArrayList())
        val tombstone = SyncExercise("ex-bench", updatedAt = 11, deletedAt = 11)
        assertEquals(0, store.apply(response(SyncChanges(exercises = listOf(tombstone))), SyncChanges(), ArrayList()))
        assertNotNull(store.exercises.get("ex-bench"))
    }

    @Test
    fun aNewerRowBringsBackALocallyDeletedOne() {
        val store = TungStore()
        store.apply(response(SyncChanges(exercises = listOf(bench))), SyncChanges(), ArrayList())
        store.tombstones[SyncTable.WORKOUTS to "w1"] = Tombstone(deletedAt = 5, dirty = true)
        val journal = ArrayList<Write>()
        assertEquals(1, store.apply(response(SyncChanges(workouts = listOf(workout))), SyncChanges(), journal))
        assertNotNull(store.workouts.get("w1"))
        assertTrue(store.tombstones.isEmpty())
        assertTrue(journal.any { it is Write.DeleteTombstone && it.id == "w1" })
    }

    @Test
    fun pushedRowsAreClearedOnlyWithTheUpdatedAtTheyWentWith() {
        val store = TungStore()
        store.apply(response(fullWorkout()), SyncChanges(), ArrayList())
        store.sets.put(set.copy(reps = 9, updatedAt = 12), dirty = true)
        store.routines.put(SyncRoutine("r1", "Push", null, updatedAt = 12), dirty = true)
        store.tombstones[SyncTable.PROGRAMS to "p1"] = Tombstone(deletedAt = 12, dirty = true)
        val pushed = store.localChanges()
        assertEquals(2, pushed.dirty.size())
        assertEquals(1, pushed.tombstones.size())
        // The set was edited again while the request was in flight.
        store.sets.put(set.copy(reps = 10, updatedAt = 13), dirty = true)
        val journal = ArrayList<Write>()
        store.apply(response(SyncChanges(), cursor = 2), pushed.dirty, journal)
        store.apply(response(SyncChanges(), cursor = 2), pushed.tombstones, journal)
        assertTrue(store.sets.rows.getValue("s1").dirty, "the newer edit stays dirty and goes next time")
        assertFalse(store.routines.rows.getValue("r1").dirty)
        assertFalse(store.tombstones.getValue(SyncTable.PROGRAMS to "p1").dirty)
    }

    @Test
    fun seedsTheCatalogueOnceWithThePhonesIds() {
        val store = TungStore()
        val catalogue = listOf(
            CoreExercise("bench_press", mapOf("en" to "Bench press", "sv" to "Bänkpress"), "chest", "barbell"),
            CoreExercise("back_squat", mapOf("en" to "Back squat", "sv" to "Knäböj"), "legs", "barbell"),
        )
        val journal = ArrayList<Write>()
        assertEquals(2, store.seedCatalogue(catalogue, journal))
        assertEquals(2, journal.size)
        val row = assertNotNull(store.exercises.get(catalogExerciseId("bench_press")))
        assertEquals("Bench press", row.name)
        assertEquals(0, row.updatedAt)
        assertFalse(store.exercises.rows.getValue(row.id).dirty)
        assertEquals(0, store.seedCatalogue(catalogue, ArrayList()), "a second start adds nothing")
    }

    @Test
    fun markingEverythingDirtyLeavesTheUntouchedCatalogueOut() {
        val store = TungStore()
        store.seedCatalogue(listOf(CoreExercise("bench_press", mapOf("en" to "Bench press"), "chest", "barbell")), ArrayList())
        // The user's copy of a catalogue exercise (a rest time set) and a custom one are theirs; the seeded row is not.
        val usersCopy = SyncExercise("ex-squat", "back_squat", "Back squat", "legs", "barbell", false, 120, updatedAt = 5)
        val custom = SyncExercise("ex-custom", null, "Farmer's walk", "fullbody", "other", true, null, updatedAt = 5)
        store.apply(
            response(SyncChanges(exercises = listOf(usersCopy, custom), routines = listOf(SyncRoutine("r1", "Push", null, updatedAt = 3)))),
            SyncChanges(), ArrayList(),
        )
        store.tombstones[SyncTable.WORKOUTS to "w9"] = Tombstone(deletedAt = 4, dirty = false)
        val journal = ArrayList<Write>()
        store.markEverythingDirty(journal)
        val changes = store.localChanges()
        assertEquals(listOf("ex-squat", "ex-custom"), changes.dirty.exercises.map { it.id }, "the seeded row with updatedAt 0 is not the user's")
        assertEquals(listOf("r1"), changes.dirty.routines.map { it.id })
        assertEquals(listOf("w9"), changes.tombstones.workouts.map { it.id })
        assertEquals(4, journal.size)
    }

    @Test
    fun deletingARoutineTakesTheDaysThatUseIt() {
        val store = TungStore()
        store.apply(
            response(
                SyncChanges(
                    exercises = listOf(bench),
                    routines = listOf(SyncRoutine("r1", "Push", null, updatedAt = 1)),
                    routineExercises = listOf(SyncRoutineExercise("re1", "r1", "ex-bench", 0, 3, null, updatedAt = 1)),
                ),
            ),
            SyncChanges(), ArrayList(),
        )
        store.apply(response(SyncChanges(routines = listOf(SyncRoutine("r1", updatedAt = 2, deletedAt = 2)))), SyncChanges(), ArrayList())
        assertNull(store.routines.get("r1"))
        assertNull(store.routineExercises.get("re1"))
    }
}
