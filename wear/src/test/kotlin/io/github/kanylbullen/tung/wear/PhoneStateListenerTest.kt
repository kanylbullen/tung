package io.github.kanylbullen.tung.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.core.wear.WatchExercise
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import io.github.kanylbullen.tung.core.wear.WatchSet
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.data.TungContainer
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.buildInMemory
import io.github.kanylbullen.tung.ongoing.OngoingWorkout
import io.github.kanylbullen.tung.sync.MemoryTokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The watch following the phone's state DataItem with the app closed (#247):
 * companion mode shows and cancels from the phone's state, standalone mode
 * only asks for a sync and never shows the phone's workout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class PhoneStateListenerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)

    @After
    fun close() = db.close()

    private fun container(token: String?) =
        TungContainer(context, createDatabase = { db }, createTokenStore = { MemoryTokenStore(token) })

    private val shown = mutableListOf<OngoingWorkout?>()
    private val syncs = mutableListOf<String?>()

    private fun handler(container: TungContainer) = PhoneStateHandler(
        hasToken = { container.syncAccount.isSignedIn() },
        localWorkoutId = { container.workoutRepository.observeActiveWorkout().first()?.id },
        show = { shown += it },
        syncSoon = { syncs += it },
    )

    private fun phoneState(workoutId: String?) = WatchWorkoutState(
        workoutId = workoutId,
        startedAt = workoutId?.let { 1_000L },
        exercises = if (workoutId == null) emptyList() else listOf(
            WatchExercise("we1", "Bench press", listOf(WatchSet("s1", 0, 80.0, 8, false)), targetSets = 3),
        ),
        updatedAt = 2_000,
    )

    @Test
    fun `companion mode shows the phone's workout and cancels when it ends`() = runBlocking {
        val handler = handler(container(token = null))

        handler.handle(phoneState("w1"))
        assertEquals("w1", shown.single()?.workoutId)

        handler.handle(phoneState(null))
        assertEquals(2, shown.size)
        assertNull(shown.last())

        // The DataItem gone (the phone app removed): nothing to show either.
        handler.handle(null)
        assertNull(shown.last())
        assertTrue(syncs.isEmpty())
    }

    @Test
    fun `standalone mode asks for a sync and never shows the phone's workout`() = runBlocking {
        val handler = handler(container(token = "tung_watch"))

        handler.handle(phoneState("w1"))
        assertEquals(listOf<String?>("w1"), syncs)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `standalone mode asks nothing while the watch already has the phone's workout`() = runBlocking {
        val container = container(token = "tung_watch")
        val workoutId = checkNotNull(container.commands.execute(Command.StartWorkout()).id(CommandIds.WORKOUT_ID))
        val handler = handler(container)

        // A set logged on the phone: the same workout, the chip is already there.
        handler.handle(phoneState(workoutId))
        assertTrue(syncs.isEmpty())

        // Finished on the phone: the watch still has it, so it syncs to see it end.
        handler.handle(phoneState(null))
        assertEquals(listOf<String?>(null), syncs)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `standalone mode with no workout anywhere asks nothing`() = runBlocking {
        val handler = handler(container(token = "tung_watch"))
        handler.handle(phoneState(null))
        handler.handle(null)
        assertTrue(syncs.isEmpty())
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `the system starts the listener for the phone's state DataItem`() {
        val intent = Intent("com.google.android.gms.wearable.DATA_CHANGED")
            .setData(Uri.parse("wear://phone-node${WatchProtocol.STATE_PATH}"))
            .setPackage(context.packageName)
        val services = context.packageManager.queryIntentServices(intent, 0).map { it.serviceInfo.name }
        assertEquals(listOf(PhoneStateListenerService::class.java.name), services)

        val other = Intent(intent).setData(Uri.parse("wear://phone-node/tung/other"))
        assertTrue(context.packageManager.queryIntentServices(other, 0).isEmpty())
    }

    @Test
    fun `a sync retries while the phone's workout has not arrived, at most four times`() {
        val ok = SyncResult.Success(0, 0)
        assertEquals(PhoneSyncNext.RETRY, phoneSyncNext(ok, "w1", null, attempt = 0))
        assertEquals(PhoneSyncNext.DONE, phoneSyncNext(ok, "w1", "w1", attempt = 0))
        assertEquals(PhoneSyncNext.RETRY, phoneSyncNext(ok, null, "w1", attempt = 2))
        assertEquals(PhoneSyncNext.DONE, phoneSyncNext(ok, "w1", null, attempt = PHONE_SYNC_ATTEMPTS - 1))
        assertEquals(PhoneSyncNext.RETRY, phoneSyncNext(SyncResult.Failed(SyncFailure.NETWORK), "w1", null, attempt = 0))
        assertEquals(PhoneSyncNext.DONE, phoneSyncNext(SyncResult.Failed(SyncFailure.SIGNED_OUT), "w1", null, attempt = 0))
        assertEquals(PhoneSyncNext.DONE, phoneSyncNext(SyncResult.NotSignedIn, "w1", null, attempt = 0))
    }
}
