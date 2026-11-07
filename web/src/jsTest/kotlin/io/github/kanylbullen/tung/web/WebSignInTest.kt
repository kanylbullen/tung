package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.sync.DataOwner
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.web.store.IndexedDbStore
import io.github.kanylbullen.tung.web.store.Tombstone
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.sync.AuthResult
import io.github.kanylbullen.tung.web.sync.OtherAccountChoice
import io.github.kanylbullen.tung.web.sync.PendingSignIn
import io.github.kanylbullen.tung.web.sync.WebSettings
import io.github.kanylbullen.tung.web.sync.WebSignIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Whose log is in the browser (issue #152), over a real IndexedDB: the same
 * account or a first sign-in uploads, another account is asked first, and
 * each answer does what it says, also after the page is loaded again.
 */
class WebSignInTest {

    private val server = "https://tung.example.org"
    private val bench = catalogExerciseId("bench_press")
    private val a = AuthResult.Success("tung_a", "a@example.org", userId = "a")
    private val b = AuthResult.Success("tung_b", "b@example.org", userId = "b")

    private class Browser(val store: TungStore, val db: IndexedDbStore, val settings: WebSettings, val signIns: WebSignIn, val revoked: MutableList<String>)

    private suspend fun browser(name: String): Browser {
        IndexedDbStore.delete(name)
        return open(name)
    }

    /** The page loaded (again) from IndexedDB [name]. */
    private suspend fun open(name: String): Browser {
        val db = IndexedDbStore.open(name)
        val settings = WebSettings(db).also { it.load() }
        val store = TungStore()
        db.loadInto(store)
        val revoked = ArrayList<String>()
        return Browser(store, db, settings, WebSignIn(store, db, settings, revoke = { _, token -> revoked += token }), revoked)
    }

    /** A's log: a workout, a routine, a custom exercise, a rest time on a catalogue row, a tombstone. */
    private suspend fun Browser.train() {
        store.exercises.put(SyncExercise(bench, nameKey = "bench_press", name = "Bench press", muscleGroup = "chest", equipment = "barbell", isCustom = false, restSeconds = 120, updatedAt = 7), dirty = false)
        store.exercises.put(SyncExercise("custom", name = "Zercher hold", muscleGroup = "core", equipment = "barbell", isCustom = true, updatedAt = 7), dirty = false)
        store.workouts.put(SyncWorkout("w1", startedAt = 1, endedAt = 2, updatedAt = 7), dirty = false)
        store.routines.put(SyncRoutine("r1", "Push", null, updatedAt = 7), dirty = false)
        store.tombstones[SyncTable.WORKOUTS to "w-old"] = Tombstone(5, dirty = false)
        // Written as a command would, so a reload sees it.
        val journal = ArrayList<io.github.kanylbullen.tung.web.store.Write>()
        store.markEverythingDirty(journal)
        db.write(journal)
    }

    private fun Browser.dirtyRows(): Int = store.tables.values.sumOf { t -> t.rows.values.count { it.dirty } }

    @Test
    fun aFirstSignInAndTheSameAccountAgainUploadWithoutAsking() = runTest {
        val browser = browser("signin-same")
        browser.train()
        assertTrue(browser.signIns.accept(server, a))
        assertEquals(DataOwner(server, "a"), browser.settings.owner)
        browser.settings.signedOut(failure = null)

        // A reload: the owner was kept in IndexedDB. Another email, the same user id: still A.
        val again = open("signin-same")
        assertEquals(DataOwner(server, "a"), again.settings.owner)
        assertTrue(again.signIns.accept(server, a.copy(email = "renamed@example.org")))
        assertNull(again.signIns.pending.value)
        assertNotNull(again.settings.account.value)
    }

    @Test
    fun anotherAccountIsAskedAndNothingIsStoredUntilItAnswers() = runTest {
        val browser = browser("signin-ask")
        browser.train()
        browser.signIns.accept(server, a)
        browser.settings.signedOut(failure = null)

        assertFalse(browser.signIns.accept(server, b))
        assertEquals(PendingSignIn(server, "b@example.org"), browser.signIns.pending.value)
        assertNull(browser.settings.account.value)
        assertNull(browser.settings.token)
        assertEquals(DataOwner(server, "a"), browser.settings.owner)
    }

    @Test
    fun dismissingRevokesTheTokenAndChangesNothing() = runTest {
        val browser = browser("signin-dismiss")
        browser.train()
        browser.signIns.accept(server, a)
        browser.settings.signedOut(failure = null)
        browser.signIns.accept(server, b)

        assertFalse(browser.signIns.resolve(null))
        assertEquals(listOf("tung_b"), browser.revoked)
        assertNull(browser.signIns.pending.value)
        assertNull(browser.settings.account.value)
        assertEquals(1, browser.store.workouts.rows.size)
        assertEquals(DataOwner(server, "a"), browser.settings.owner)
    }

    @Test
    fun addingThemUploadsEverythingToTheNewAccount() = runTest {
        val browser = browser("signin-add")
        browser.train()
        browser.signIns.accept(server, a)
        browser.settings.signedOut(failure = null)
        browser.signIns.accept(server, b)

        assertTrue(browser.signIns.resolve(OtherAccountChoice.ADD))
        assertEquals("b@example.org", browser.settings.account.value?.email)
        assertEquals(DataOwner(server, "b"), browser.settings.owner)
        assertTrue(browser.store.workouts.rows.getValue("w1").dirty)
    }

    @Test
    fun deletingThemWipesTheLogKeepsTheCatalogueAndWritesNoTombstone() = runTest {
        val browser = browser("signin-delete")
        browser.train()
        browser.signIns.accept(server, a)
        browser.settings.signedOut(failure = null)
        browser.signIns.accept(server, b)

        assertTrue(browser.signIns.resolve(OtherAccountChoice.DELETE))
        assertEquals(DataOwner(server, "b"), browser.settings.owner)
        assertFalse(browser.store.hasUserData())
        assertEquals(0, browser.dirtyRows())
        assertTrue(browser.store.tombstones.isEmpty())
        assertEquals(listOf(bench), browser.store.exercises.rows.keys.toList())
        assertEquals(null, browser.store.exercises.get(bench)?.restSeconds)
        assertEquals(0L, browser.store.exercises.get(bench)?.updatedAt)

        // IndexedDB was written in the same go: a reload finds the same.
        val reloaded = open("signin-delete")
        assertFalse(reloaded.store.hasUserData())
        assertTrue(reloaded.store.tombstones.isEmpty())
        assertEquals(1, reloaded.store.exercises.rows.size)
        assertEquals("b@example.org", reloaded.settings.account.value?.email)
    }

    @Test
    fun aBrowserFromBeforeOwnersAsksWhenTheLastEmailWasAnother() = runTest {
        val browser = browser("signin-legacy")
        browser.train()
        browser.settings.signedIn(server, "a@example.org", "tung_old")
        browser.settings.signedOut(failure = null)
        assertNull(browser.settings.owner)
        assertFalse(browser.signIns.accept(server, b))
        assertEquals(PendingSignIn(server, "b@example.org"), browser.signIns.pending.value)
    }

    @Test
    fun aBrowserWithNothingOfItsOwnIsNotAsked() = runTest {
        val browser = browser("signin-empty")
        browser.signIns.accept(server, a)
        browser.settings.signedOut(failure = null)
        assertTrue(browser.signIns.accept(server, b))
        assertNull(browser.signIns.pending.value)
    }
}
