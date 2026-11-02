package io.github.kanylbullen.tung.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iPhone's database at run time, which compiling the klibs on Linux
 * cannot show: Room over the bundled SQLite opens a file, the seed writes
 * the core catalogue, and a workout started through the command rules reads
 * back. Runs on a simulator (`:data:iosSimulatorArm64Test`, CI's `ios` job).
 */
@OptIn(ExperimentalForeignApi::class)
class IosDatabaseTest {

    @Test
    fun theDatabaseOpensSeedsTheCatalogueAndKeepsAWorkout() = runBlocking {
        val path = NSTemporaryDirectory() + "tung-${Random.nextLong().toULong()}.db"
        val db = TungDatabase.build(path)
        try {
            val exercises = ExerciseRepository(db).observeExercises().first()
            assertEquals(70, exercises.size)

            val workouts = WorkoutRepository(db)
            val id = workouts.startOrResumeWorkout()
            workouts.addExercise(id, exercises.first().id)
            assertEquals(id, workouts.observeActiveWorkout().first()?.id)
            assertEquals(1, workouts.observeExercises(id).first().size)
        } finally {
            db.close()
            listOf("", "-wal", "-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(path + it, null) }
        }
    }
}
