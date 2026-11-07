package io.github.kanylbullen.tung.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.sync.DataOwner
import io.github.kanylbullen.tung.core.sync.OtherAccountChoice
import io.github.kanylbullen.tung.core.sync.PendingSignIn
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.data.buildInMemory
import io.github.kanylbullen.tung.data.SeedExercises
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Whose data is on the device (issue #152): a sign-in as the account the
 * data belongs to, or the first sign-in, uploads it; another account is
 * asked first, and the watch's link code wipes instead of asking.
 */
@RunWith(RobolectricTestRunner::class)
class AccountOwnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fake = FakeSyncServer()
    private val db = TungDatabase.buildInMemory(context)
    private val workouts = WorkoutRepository(db, clock = { 1_700_000_000_000L })
    private val routines = RoutineRepository(db, clock = { 1_700_000_000_000L })
    private val exercises = ExerciseRepository(db, clock = { 1_700_000_000_000L })
    private val settings = SyncSettings(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "owner.preferences_pb") })
    private val tokens = MemoryTokenStore(null)
    private val store = SyncStore(db)
    private val client = SyncClient(store, settings, tokens, syncHttpClient(), retryDelaysMillis = listOf(10L))
    private var wiped = 0
    private val account = SyncAccount(client, settings, tokens, store, NoScheduler, scope, afterWipe = { wiped++ })
    private val bench = catalogExerciseId("bench_press")

    private object NoScheduler : SyncScheduler {
        override fun schedulePeriodic() = Unit
        override fun enqueueNow() = Unit
        override fun cancel() = Unit
    }

    @Before
    fun start() = fake.start()

    @After
    fun stop() {
        fake.stop()
        db.close()
        scope.cancel()
    }

    private fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    private fun dirty(): Int = listOf("exercise", "workout", "workout_exercise", "set_entry", "routine", "routine_exercise", "program", "program_day")
        .sumOf { count("SELECT COUNT(*) FROM $it WHERE dirty = 1") }

    /** A's training: a finished workout of bench press, a routine, a custom exercise, a rest time on a catalogue row, a deleted workout's tombstone. */
    private fun train() = runBlocking {
        val workoutId = workouts.startOrResumeWorkout()
        val we = workouts.addExercise(workoutId, bench, sets = 1)
        val set = workouts.getSets(we).single().id
        workouts.updateWeight(set, 80.0)
        workouts.updateReps(set, 8)
        workouts.setCompleted(set, true)
        check(workouts.finishWorkout(workoutId))
        routines.saveFromWorkout(workoutId, "Push")
        exercises.createExercise("Zercher hold", MuscleGroup.CORE, Equipment.BARBELL)
        exercises.setRestSeconds(bench, 120)
        val discarded = workouts.startOrResumeWorkout()
        workouts.addExercise(discarded, bench, sets = 1)
        workouts.deleteWorkout(discarded)
    }

    private fun signIn() = runBlocking { account.signIn(fake.baseUrl, fake.userEmail, "secret123", create = false) }

    private fun signOut() = runBlocking { account.signOut() }

    /** Signed in as A: everything goes up, so nothing on the device is unsynced. */
    private fun syncAndSignOut() {
        check(runBlocking { client.syncOnce() } is SyncResult.Success)
        check(runBlocking { store.unsyncedCount() } == 0)
        signOut()
    }

    private fun logouts(): Int = fake.authPaths.count { it == "/api/v1/auth/logout" }

    private fun pending(): PendingSignIn? = runBlocking { account.state.first().otherAccountData }

    @Test
    fun theFirstSignInUploadsEverythingAndRecordsTheOwner() {
        train()
        assertTrue(signIn() is AuthResult.Success)
        assertNull(pending())
        assertTrue(runBlocking { account.isSignedIn() })
        assertTrue(dirty() > 0)
        assertEquals(DataOwner(fake.baseUrl, "u"), runBlocking { settings.owner() })
    }

    @Test
    fun theSameAccountAgainUploadsWithoutAsking() {
        train()
        signIn()
        db.openHelper.writableDatabase.execSQL("UPDATE workout SET dirty = 0")
        signOut()
        // The owner is kept across the sign-out.
        assertEquals(DataOwner(fake.baseUrl, "u"), runBlocking { settings.owner() })
        // Even with another email: the user id decides, never the email.
        fake.userEmail = "renamed@example.org"
        signIn()
        assertNull(pending())
        assertTrue(runBlocking { account.isSignedIn() })
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE dirty = 1"))
    }

    @Test
    fun anotherAccountIsAskedBeforeAnythingIsStoredOrMarked() {
        train()
        signIn()
        syncAndSignOut()
        fake.switchAccount("b", "b@example.org")

        assertTrue(signIn() is AuthResult.Success)
        assertEquals(PendingSignIn(fake.baseUrl, "b@example.org", unsynced = 0), pending())
        assertFalse(runBlocking { account.isSignedIn() })
        assertNull(tokens.read())
        assertEquals(0, count("SELECT COUNT(*) FROM workout WHERE dirty = 1"))
        assertEquals(DataOwner(fake.baseUrl, "u"), runBlocking { settings.owner() })
    }

    @Test
    fun dismissingSignsOutAgainAndChangesNothing() {
        train()
        signIn()
        signOut()
        fake.switchAccount("b", "b@example.org")
        signIn()
        val logouts = fake.authPaths.count { it == "/api/v1/auth/logout" }
        runBlocking { account.resolveOtherAccountData(null) }
        assertNull(pending())
        assertFalse(runBlocking { account.isSignedIn() })
        assertNull(tokens.read())
        assertEquals(logouts + 1, fake.authPaths.count { it == "/api/v1/auth/logout" })
        assertEquals(1, count("SELECT COUNT(*) FROM workout"))
        assertEquals(DataOwner(fake.baseUrl, "u"), runBlocking { settings.owner() })
    }

    @Test
    fun addingThemUploadsEverythingToTheNewAccount() {
        train()
        signIn()
        signOut()
        db.openHelper.writableDatabase.execSQL("UPDATE workout SET dirty = 0")
        fake.switchAccount("b", "b@example.org")
        signIn()
        runBlocking { account.resolveOtherAccountData(OtherAccountChoice.ADD) }
        assertTrue(runBlocking { account.isSignedIn() })
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE dirty = 1"))
        assertEquals(DataOwner(fake.baseUrl, "b"), runBlocking { settings.owner() })
        assertEquals("b@example.org", runBlocking { account.state.first().email })
    }

    @Test
    fun deletingThemWipesTheTrainingInOneGoWithoutTombstonesAndKeepsTheCatalogue() {
        train()
        signIn()
        signOut()
        fake.switchAccount("b", "b@example.org")
        val catalogue = count("SELECT COUNT(*) FROM exercise WHERE nameKey IS NOT NULL")
        signIn()
        runBlocking { account.resolveOtherAccountData(OtherAccountChoice.DELETE) }

        assertTrue(runBlocking { account.isSignedIn() })
        listOf("workout", "workout_exercise", "set_entry", "routine", "routine_exercise", "program", "program_day", "tombstones").forEach {
            assertEquals(it, 0, count("SELECT COUNT(*) FROM $it"))
        }
        assertEquals(0, count("SELECT COUNT(*) FROM exercise WHERE nameKey IS NULL"))
        assertEquals(SeedExercises.all.size, catalogue)
        assertEquals(catalogue, count("SELECT COUNT(*) FROM exercise"))
        // Back to the seed: no rest time of A's, untouched and not dirty, so nothing of A's goes up.
        assertEquals(0, count("SELECT COUNT(*) FROM exercise WHERE restSeconds IS NOT NULL OR updatedAt != 0 OR dirty = 1"))
        assertFalse(runBlocking { store.hasLocalData() })
        assertEquals(1, wiped)
        assertEquals(DataOwner(fake.baseUrl, "b"), runBlocking { settings.owner() })
        // Nothing to push: the first sync only pulls.
        assertTrue(runBlocking { client.syncOnce() } is io.github.kanylbullen.tung.core.sync.SyncResult.Success)
        assertTrue(fake.syncRequests.last().changes.let { it.exercises.isEmpty() && it.workouts.isEmpty() && it.routines.isEmpty() })
    }

    @Test
    fun anotherAccountOnADeviceWithNothingOfItsOwnIsNotAsked() {
        signIn()
        signOut()
        fake.switchAccount("b", "b@example.org")
        signIn()
        assertNull(pending())
        assertTrue(runBlocking { account.isSignedIn() })
    }

    @Test
    fun aDeviceFromBeforeOwnersAsksWhenTheLastEmailWasAnother() = runBlocking {
        train()
        // Signed in and out before this version: a server and an email, no owner.
        settings.signedIn(fake.baseUrl, "a@example.org")
        assertNull(settings.owner())
        fake.switchAccount("b", "b@example.org")
        signIn()
        assertEquals("b@example.org", pending()?.email)
        // Never signed in before: nothing of it reached an account.
        assertEquals(runBlocking { store.unsyncedCount() }, pending()?.unsynced)
        assertTrue(pending()!!.unsynced > 0)
    }

    @Test
    fun theQuestionCountsTheChangesThatNeverSynced() {
        train()
        signIn()
        syncAndSignOut()
        // Edited after the last sync, signed out: two rows no account has.
        db.openHelper.writableDatabase.execSQL("UPDATE routine SET dirty = 1")
        db.openHelper.writableDatabase.execSQL("UPDATE workout SET dirty = 1")
        fake.switchAccount("b", "b@example.org")
        signIn()
        assertEquals(PendingSignIn(fake.baseUrl, "b@example.org", unsynced = 2), pending())
    }

    @Test
    fun leavingTheQuestionRevokesTheHeldToken() {
        train()
        signIn()
        syncAndSignOut()
        fake.switchAccount("b", "b@example.org")
        signIn()
        val before = logouts()
        account.abandonPendingSignIn()
        assertNull(pending())
        val deadline = System.currentTimeMillis() + 5_000
        while (logouts() == before && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(before + 1, logouts())
        assertFalse(runBlocking { account.isSignedIn() })
        assertEquals(1, count("SELECT COUNT(*) FROM workout"))
    }

    @Test
    fun anAnswerWithoutAUserIdComparesTheServerAndEmail() {
        train()
        signIn()
        syncAndSignOut()
        // A server that leaves the id out: the same server and email are the same account.
        fake.userId = null
        signIn()
        assertNull(pending())
        assertTrue(runBlocking { account.isSignedIn() })
        syncAndSignOut()
        // Another email without an id is another account.
        fake.switchAccount(null, "b@example.org")
        signIn()
        assertEquals("b@example.org", pending()?.email)
    }

    @Test
    fun aSignedInDeviceFromBeforeOwnersLearnsItsOwnerFromMe() = runBlocking {
        settings.signedIn(fake.baseUrl, "lifter@example.org")
        tokens.write(fake.token)
        account.refreshAccount()
        assertEquals(DataOwner(fake.baseUrl, "u"), settings.owner())
    }

    @Test
    fun theWatchsLinkCodeWipesAnotherAccountsSyncedDataWithoutAsking() = runBlocking {
        train()
        signIn()
        syncAndSignOut()
        fake.switchAccount("b", "b@example.org")
        val result = account.signInWithCode(fake.baseUrl, fake.linkCode)
        assertTrue(result is LinkClaimResult.Success)
        assertNull(pending())
        assertTrue(account.isSignedIn())
        assertEquals(0, count("SELECT COUNT(*) FROM workout"))
        assertEquals(DataOwner(fake.baseUrl, "b"), settings.owner())
    }

    @Test
    fun theWatchsLinkCodeRefusesRatherThanWipeUnsyncedData() = runBlocking {
        train()
        signIn()
        syncAndSignOut()
        db.openHelper.writableDatabase.execSQL("UPDATE workout SET dirty = 1")
        fake.switchAccount("b", "b@example.org")
        val before = logouts()
        assertEquals(LinkClaimResult.Failed(LinkFailure.UNSYNCED_OTHER_ACCOUNT), account.signInWithCode(fake.baseUrl, fake.linkCode))
        assertEquals(before + 1, logouts())
        assertFalse(account.isSignedIn())
        assertNull(tokens.read())
        assertNull(pending())
        assertEquals(1, count("SELECT COUNT(*) FROM workout"))
        assertEquals(DataOwner(fake.baseUrl, "u"), settings.owner())
    }
}
