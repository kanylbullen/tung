package io.github.kanylbullen.tung.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.sync.SYNC_MAX_ROWS
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.entries
import io.github.kanylbullen.tung.core.sync.tombstoneEntry
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.ProgramRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * [SyncClient] end to end: a real Room database (in memory), the production
 * Ktor/OkHttp client, and [FakeSyncServer] speaking the protocol on localhost.
 */
@RunWith(RobolectricTestRunner::class)
class SyncClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fake = FakeSyncServer()
    private var devices = 0
    private val databases = mutableListOf<TungDatabase>()

    /** One phone: its own database, settings, token and client, all pointing at [fake]. */
    private inner class Device {
        @Volatile var now = 1_700_000_000_000L
        val db: TungDatabase = TungDatabase.buildInMemory(context).also { databases += it }
        val workouts = WorkoutRepository(db, clock = { now })
        val routines = RoutineRepository(db, clock = { now })
        val programs = ProgramRepository(db, clock = { now })
        val exercises = ExerciseRepository(db.exerciseDao(), clock = { now })
        val settings = SyncSettings(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "device${devices++}.preferences_pb") },
        )
        val tokens = MemoryTokenStore(fake.token)
        var signedOutCalls = 0
        val client = SyncClient(
            SyncStore(db), settings, tokens, syncHttpClient(),
            clock = { now }, retryDelaysMillis = listOf(10L, 10L), onSignedOut = { signedOutCalls++ },
        )

        init {
            runBlocking { settings.signedIn(fake.baseUrl, "lifter@example.org") }
        }

        fun sync(): SyncResult = runBlocking { client.syncOnce() }

        fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

        fun dirtyRows(): Int = listOf("exercise", "workout", "workout_exercise", "set_entry", "routine", "routine_exercise", "program", "program_day")
            .sumOf { count("SELECT COUNT(*) FROM $it WHERE dirty = 1") } + count("SELECT COUNT(*) FROM tombstones WHERE dirty = 1")

        fun weight(setId: String): Double =
            db.openHelper.readableDatabase.query("SELECT weightKg FROM set_entry WHERE id = ?", arrayOf(setId)).use { c -> c.moveToFirst(); c.getDouble(0) }

        fun setIds(workoutExerciseId: String): List<String> = runBlocking { workouts.getSets(workoutExerciseId).map { it.id } }

        /** A finished workout of bench press: one logged set of 80 × 8, plus a second set never ticked (dropped on finish). */
        fun finishedWorkout(): Pair<String, String> = runBlocking {
            val workoutId = workouts.startOrResumeWorkout()
            val workoutExerciseId = workouts.addExercise(workoutId, bench)
            val first = setIds(workoutExerciseId).single()
            workouts.addSet(workoutExerciseId)
            workouts.updateWeight(first, 80.0)
            workouts.updateReps(first, 8)
            workouts.setCompleted(first, true)
            now += 60_000
            check(workouts.finishWorkout(workoutId))
            workoutId to workoutExerciseId
        }
    }

    private val bench = catalogExerciseId("bench_press")

    @Before
    fun setUp() = fake.start()

    @After
    fun tearDown() {
        fake.stop()
        databases.forEach { it.close() }
        scope.cancel()
    }

    @Test
    fun firstSyncPushesEveryLocalRowAndTombstoneAndStoresTheCursor() {
        val phone = Device()
        val (workoutId, _) = phone.finishedWorkout()
        val routineId = runBlocking { checkNotNull(phone.routines.saveFromWorkout(workoutId, "Push")) }
        runBlocking {
            val programId = phone.programs.create("Plan", ProgramMode.FIXED)
            phone.programs.addDay(programId, routineId)
            phone.exercises.setRestSeconds(bench, 120)
            phone.exercises.createExercise("Zercher squat", MuscleGroup.LEGS, Equipment.BARBELL)
        }

        assertEquals(SyncResult.Success(pushed = 10, applied = 0), phone.sync())

        val request = fake.syncRequests.single()
        assertEquals(0L, request.cursor)
        with(request.changes) {
            // The touched catalogue row (as the user's copy) and the custom one; never the other 69.
            assertEquals(setOf(bench), exercises.filter { it.nameKey != null }.map { it.id }.toSet())
            assertEquals(120, exercises.single { it.id == bench }.restSeconds)
            assertEquals(listOf("Zercher squat"), exercises.filter { it.isCustom == true }.map { it.name })
            assertEquals(listOf(workoutId), workouts.map { it.id })
            assertEquals(1, workoutExercises.size)
            assertEquals(1, routines.size)
            assertEquals(1, routineExercises.size)
            assertEquals(1, programs.size)
            assertEquals(1, programDays.size)
            // The logged set, and the tombstone of the unticked one that finishing dropped.
            assertEquals(1, sets.count { it.deletedAt == null && it.weightKg == 80.0 && it.reps == 8 })
            val tombstone = sets.single { it.deletedAt != null }
            assertEquals(tombstone.deletedAt, tombstone.updatedAt)
            assertNull(tombstone.weightKg)
            assertEquals(10, size())
        }
        assertEquals(fake.cursor, runBlocking { phone.settings.cursor() })
        assertEquals(0, phone.dirtyRows())
        assertEquals(phone.now, runBlocking { phone.settings.state.first().lastSyncAt })
        assertEquals(10, fake.storedCount())

        // Nothing dirty: the next sync is a pull from the stored cursor.
        assertEquals(SyncResult.Success(pushed = 0, applied = 0), phone.sync())
        assertEquals(fake.cursor, fake.syncRequests.last().cursor)
        assertTrue(fake.syncRequests.last().changes.isEmpty())
    }

    @Test
    fun aNewerRowUpdatesAnOlderOneIsIgnoredAndATombstoneDeletes() {
        val phone = Device()
        val (workoutId, workoutExerciseId) = runBlocking {
            val workoutId = phone.workouts.startOrResumeWorkout()
            workoutId to phone.workouts.addExercise(workoutId, bench)
        }
        runBlocking {
            phone.workouts.addSet(workoutExerciseId)
            phone.workouts.addSet(workoutExerciseId)
        }
        val (a, b, c) = phone.setIds(workoutExerciseId)
        phone.sync()
        assertEquals(0, phone.dirtyRows())

        val later = phone.now + 10_000
        fake.put(SyncChanges(sets = listOf(SyncSet(a, workoutExerciseId, 0, 100.0, 5, true, later))).entries().single())
        fake.inject(SyncChanges(sets = listOf(SyncSet(b, workoutExerciseId, 1, 1.0, 1, false, phone.now - 1))).entries().single())
        fake.put(tombstoneEntry(SyncTable.SETS, c, later))

        assertEquals(SyncResult.Success(pushed = 0, applied = 2), phone.sync())
        assertEquals(100.0, phone.weight(a), 0.0)
        assertEquals(0.0, phone.weight(b), 0.0)
        assertEquals(listOf(a, b), phone.setIds(workoutExerciseId))
        // What the server sent is not dirty, and the server's delete is not echoed back as ours.
        assertEquals(0, phone.dirtyRows())
        assertEquals(0, phone.count("SELECT COUNT(*) FROM tombstones"))
        assertEquals(workoutId, runBlocking { phone.workouts.getWorkout(workoutId)?.id })
    }

    @Test
    fun aLocalEditNewerThanTheServersIsKeptAndPushed() {
        val phone = Device()
        val (_, workoutExerciseId) = phone.finishedWorkout()
        val set = phone.setIds(workoutExerciseId).single()
        phone.sync()

        // Another device changed the set at t+1s; this phone changed it again at t+2s, offline.
        fake.put(SyncChanges(sets = listOf(SyncSet(set, workoutExerciseId, 0, 70.0, 8, true, phone.now + 1_000))).entries().single())
        phone.now += 2_000
        runBlocking { phone.workouts.updateWeight(set, 90.0) }

        phone.sync()
        assertEquals(90.0, phone.weight(set), 0.0)
        assertEquals(90.0, fake.stored(SyncTable.SETS, set)?.let { (it.row as SyncSet).weightKg } ?: 0.0, 0.0)
        assertEquals(0, phone.dirtyRows())
    }

    @Test
    fun rowsEditedDuringTheRequestStayDirty() {
        val phone = Device()
        val (_, workoutExerciseId) = phone.finishedWorkout()
        val set = phone.setIds(workoutExerciseId).single()
        var edited = false
        fake.duringSync = {
            if (!edited) {
                edited = true
                runBlocking {
                    phone.now += 5_000
                    phone.workouts.updateWeight(set, 92.5)
                }
            }
        }

        phone.sync()
        assertEquals(1, phone.count("SELECT COUNT(*) FROM set_entry WHERE dirty = 1"))
        assertEquals(1, phone.dirtyRows())
        assertEquals(92.5, phone.weight(set), 0.0)

        phone.sync()
        val second = fake.syncRequests.last().changes
        assertEquals(listOf(set), second.sets.map { it.id })
        assertEquals(92.5, second.sets.single().weightKg ?: 0.0, 0.0)
        assertEquals(1, second.size())
        assertEquals(0, phone.dirtyRows())
    }

    @Test
    fun unauthorizedSignsOutWithoutRetrying() {
        val phone = Device()
        phone.finishedWorkout()
        fake.token = "tung_revoked"

        assertEquals(SyncResult.Failed(SyncFailure.SIGNED_OUT), phone.sync())
        assertEquals(1, fake.syncRequests.size)
        assertNull(phone.tokens.read())
        assertEquals(1, phone.signedOutCalls)
        assertEquals(SyncFailure.SIGNED_OUT.key, runBlocking { phone.settings.state.first().lastError })
        assertTrue(phone.dirtyRows() > 0)

        // Signed out, a sync does nothing at all.
        assertEquals(SyncResult.NotSignedIn, phone.sync())
        assertEquals(1, fake.syncRequests.size)
    }

    @Test
    fun serverErrorsAreRetriedAndOtherClientErrorsAreNot() {
        val phone = Device()
        phone.finishedWorkout()

        fake.failNext = 1
        fake.failStatus = 503
        assertTrue(phone.sync() is SyncResult.Success)
        assertEquals(2, fake.syncRequests.size)

        runBlocking { phone.workouts.startOrResumeWorkout() }
        fake.failNext = 5
        fake.failStatus = 500
        assertEquals(SyncResult.Failed(SyncFailure.SERVER), phone.sync())
        assertEquals(2 + 3, fake.syncRequests.size)
        assertEquals(SyncFailure.SERVER.key, runBlocking { phone.settings.state.first().lastError })
        assertEquals(1, phone.dirtyRows())

        fake.failNext = 1
        fake.failStatus = 400
        assertEquals(SyncResult.Failed(SyncFailure.REJECTED), phone.sync())
        assertEquals(2 + 3 + 1, fake.syncRequests.size)
        assertEquals(phone.tokens.token, fake.token)
    }

    @Test
    fun aRequestIsChunkedAtTheProtocolsMaximum() {
        val phone = Device()
        val (workoutId, workoutExerciseId) = runBlocking {
            val workoutId = phone.workouts.startOrResumeWorkout()
            workoutId to phone.workouts.addExercise(workoutId, bench)
        }
        // Up to one row over the limit: the workout, its exercise and SYNC_MAX_ROWS - 1 + 1 sets.
        phone.db.openHelper.writableDatabase.apply {
            beginTransaction()
            try {
                val statement = compileStatement(
                    "INSERT INTO set_entry (id, workoutExerciseId, position, weightKg, reps, completed, updatedAt, dirty) VALUES (?, ?, ?, 20.0, 5, 1, 1, 1)",
                )
                for (i in 1 until SYNC_MAX_ROWS) {
                    statement.bindString(1, "bulk-$i")
                    statement.bindString(2, workoutExerciseId)
                    statement.bindLong(3, i.toLong())
                    statement.executeInsert()
                }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
        val local = SYNC_MAX_ROWS + 2 // the workout, its exercise, the pre-filled set and the bulk sets
        assertEquals(local, phone.dirtyRows())

        assertEquals(SyncResult.Success(pushed = local, applied = 0), phone.sync())
        val (first, second) = fake.syncRequests
        assertEquals(2, fake.syncRequests.size)
        assertEquals(listOf(SYNC_MAX_ROWS, 2), fake.syncRequests.map { it.changes.size() })
        assertEquals(listOf(workoutId), first.changes.workouts.map { it.id })
        assertEquals(listOf(workoutExerciseId), first.changes.workoutExercises.map { it.id })
        assertEquals(0L, first.cursor)
        assertEquals(SYNC_MAX_ROWS.toLong(), second.cursor)
        assertEquals(fake.cursor, runBlocking { phone.settings.cursor() })
        assertEquals(0, phone.dirtyRows())
    }

    @Test
    fun twoPhonesConvergeOnTheNewerEditAndADeletePropagates() {
        val first = Device()
        val (workoutId, workoutExerciseId) = first.finishedWorkout()
        val set = first.setIds(workoutExerciseId).single()
        first.sync()

        // A second phone signs in to the same account and pulls everything.
        val second = Device()
        second.sync()
        assertEquals(workoutId, runBlocking { second.workouts.getWorkout(workoutId)?.id })
        assertEquals(80.0, second.weight(set), 0.0)
        assertEquals(0, second.dirtyRows())

        // Both edit the same set offline; the second phone's edit is the later one.
        first.now += 1_000
        runBlocking { first.workouts.updateWeight(set, 82.5) }
        second.now = first.now + 1_000
        runBlocking { second.workouts.updateWeight(set, 85.0) }
        first.sync()
        second.sync()
        first.sync()
        assertEquals(85.0, first.weight(set), 0.0)
        assertEquals(85.0, second.weight(set), 0.0)

        // The first phone deletes the workout; the second loses it, sets and all.
        first.now = second.now + 1_000
        runBlocking { first.workouts.deleteWorkout(workoutId) }
        first.sync()
        second.sync()
        assertNull(runBlocking { second.workouts.getWorkout(workoutId) })
        assertEquals(0, second.count("SELECT COUNT(*) FROM set_entry"))
        assertEquals(0, second.count("SELECT COUNT(*) FROM workout_exercise"))
        assertEquals(0, first.dirtyRows())
        assertEquals(0, second.dirtyRows())
    }

    @Test
    fun authMethodsSayWhetherTheServerOffersGoogle() {
        val phone = Device()
        assertEquals(
            AuthMethodsResult.Success(AuthMethods(password = true, googleClientId = null)),
            runBlocking { phone.client.authMethods(fake.baseUrl) },
        )
        fake.googleClientId = "1234-web.apps.googleusercontent.com"
        assertEquals(
            AuthMethodsResult.Success(AuthMethods(password = true, googleClientId = "1234-web.apps.googleusercontent.com")),
            runBlocking { phone.client.authMethods(fake.baseUrl) },
        )
        // A server from before the route answers 404: the password only.
        assertEquals(
            AuthMethodsResult.Success(AuthMethods(password = true, googleClientId = null)),
            runBlocking { phone.client.authMethods(fake.baseUrl + "/old") },
        )
        assertEquals(
            AuthMethodsResult.Failed(AuthFailure.NETWORK),
            runBlocking { phone.client.authMethods("http://127.0.0.1:1") },
        )
    }

    @Test
    fun loginWithGoogleTradesTheIdTokenForASession() {
        val phone = Device()
        fake.googleClientId = "1234-web.apps.googleusercontent.com"
        val ok = runBlocking { phone.client.loginWithGoogle(fake.baseUrl, fake.googleIdToken) }
        assertEquals(AuthResult.Success(fake.token, "lifter@gmail.com", googleLinked = true), ok)
        assertEquals(listOf(fake.googleIdToken), fake.googleTokens)

        val rejected = runBlocking { phone.client.loginWithGoogle(fake.baseUrl, "forged") }
        assertEquals(AuthResult.Failed(AuthFailure.GOOGLE_REJECTED, 401), rejected)

        fake.googleClientId = null
        val notOffered = runBlocking { phone.client.loginWithGoogle(fake.baseUrl, fake.googleIdToken) }
        assertEquals(AuthResult.Failed(AuthFailure.GOOGLE_NOT_OFFERED, 404), notOffered)
    }

    @Test
    fun meSaysWhetherGoogleIsLinked() {
        val phone = Device()
        assertEquals(AccountInfo("lifter@example.org", hasPassword = true, googleLinked = false), runBlocking { phone.client.me() })
        fake.googleLinked = true
        assertEquals(true, runBlocking { phone.client.me() }?.googleLinked)
        phone.tokens.write("tung_dead")
        assertNull(runBlocking { phone.client.me() })
    }
}
