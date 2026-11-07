package io.github.kanylbullen.tung.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.data.AppCommandHandler
import io.github.kanylbullen.tung.data.AppExerciseResolver
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.data.buildInMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * A workout in progress reaches the server while it is in progress (issue
 * #199): every change through the [AppCommandHandler] asks for a sync a
 * moment later ([SyncAccount.changed]), changes close together share one,
 * and a finished workout keeps its own trigger.
 */
@RunWith(RobolectricTestRunner::class)
class ChangeSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = TungDatabase.buildInMemory(context)
    private val extended = ExtendedCatalog({ File("../catalog/extended.json").readText() })
    private val workouts = WorkoutRepository(db, clock = { 1_000L })
    private val settings = SyncSettings(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "change.preferences_pb") })

    private val enqueued = AtomicInteger()
    private val scheduler = object : SyncScheduler {
        override fun schedulePeriodic() = Unit
        override fun enqueueNow() = error("a change asks for enqueueSoon, which carries it along with a sync already waiting")
        override suspend fun enqueueSoon() { enqueued.incrementAndGet() }
        override fun cancel() = Unit
    }

    private fun account(token: String?): SyncAccount {
        val tokens = MemoryTokenStore(token)
        val store = SyncStore(db)
        val client = SyncClient(store, settings, tokens, syncHttpClient())
        return SyncAccount(client, settings, tokens, store, scheduler, scope, changeSyncDelayMillis = DELAY)
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    /** Waits until [count] syncs were asked for (at most 5 s), then a little longer for any that should not come. */
    private fun settleAt(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (enqueued.get() < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(DELAY * 2)
        assertEquals(count, enqueued.get())
    }

    @Test
    fun changesCloseTogetherShareOneSync() {
        val account = account("token")
        repeat(3) { account.changed() }
        settleAt(1)
        account.changed()
        settleAt(2)
    }

    @Test
    fun signedOutAChangeAsksForNothing() {
        account(null).changed()
        settleAt(0)
    }

    @Test
    fun everyCommandThatWentThroughCountsAsAChangeExceptSelectingAndFinishing() {
        var changes = 0
        var finished = 0
        val commands = AppCommandHandler(
            workouts = workouts,
            resolver = AppExerciseResolver(db.exerciseDao(), { mapOf("bench_press" to listOf("Bench press")) }, extended),
            onWorkoutFinished = { finished++ },
            onChanged = { changes++ },
        )
        fun run(command: Command) = runBlocking { commands.execute(command) }

        val workoutId = (run(Command.StartWorkout()) as CommandResult.Done).ids.getValue(CommandIds.WORKOUT_ID)
        val added = run(Command.AddExercise(workoutId, ExerciseRef.ByKey("bench_press"), sets = 1)) as CommandResult.Done
        val setId = runBlocking { workouts.getSets(added.ids.getValue(CommandIds.WORKOUT_EXERCISE_ID)) }.single().id
        run(Command.SetReps(setId, 8))
        run(Command.LogSet(workoutId, setId))
        assertEquals(4, changes)

        run(Command.SelectCurrent(setId = setId))
        assertTrue(run(Command.SetReps("no-such-set", 8)) is CommandResult.Rejected)
        assertEquals("selecting and a refusal write nothing", 4, changes)

        run(Command.FinishWorkout(workoutId))
        assertEquals(4, changes)
        assertEquals(1, finished)
    }

    private companion object {
        const val DELAY = 200L
    }
}
