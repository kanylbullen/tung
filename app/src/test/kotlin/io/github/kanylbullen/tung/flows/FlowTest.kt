package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getPluralString
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.platform.ViewRootForTest
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.MainActivity
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * Base for the flow tests: the real [MainActivity] and the real screens, on the
 * JVM under Robolectric, against an in-memory Room database ([FlowTestApp]).
 *
 * Assertions are on semantics (text, content descriptions) taken from the
 * English string resources. Each test also saves PNGs of the screens it passes
 * through to `app/build/reports/flow-screenshots/` for a human to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
abstract class FlowTest {

    @get:Rule(order = 0)
    val testName = TestName()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    protected val app: FlowTestApp get() = ApplicationProvider.getApplicationContext()
    protected val db: TungDatabase get() = app.database

    @Before
    fun englishNumbers() {
        // The qualifiers pick values/strings.xml; this makes number formatting match it.
        Locale.setDefault(Locale.ENGLISH)
    }

    protected fun str(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    protected fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): String =
        app.resources.getQuantityString(id, quantity, *args)

    /** A string of a screen in `ui/` (Compose Resources), as that screen writes it. */
    protected fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }

    protected fun plural(res: PluralStringResource, quantity: Int, vararg args: Any): String =
        runBlocking { getPluralString(res, quantity, *args) }

    // --- waiting: Room works on its own threads, so the UI catches up asynchronously ---

    /**
     * Waits until [condition] holds, letting the main looper run between checks.
     *
     * `compose.waitUntil` alone does not: between checks it only advances
     * Compose's own clock and sleeps. Under Robolectric the test thread *is*
     * the main thread, and the main looper runs only when the test idles it,
     * which Compose does only from its own calls (a click, `onAllNodes`, a
     * screenshot). Work that goes to a background thread and comes back to
     * Main (a DataStore edit runs its transform on the caller's dispatcher,
     * Room hands a transaction thread back to the caller) and returns after
     * the last such call sits in the queue for the whole wait, however long,
     * when [condition] reads only the database or an alarm. So this idles
     * the looper before every check.
     *
     * On timeout every thread's stack and the looper's state go to stderr,
     * so a CI failure shows whether anything was stuck. Use this, not
     * `compose.waitUntil`.
     */
    protected fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis) {
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: ComposeTimeoutException) {
            System.err.println(threadDump("${testName.methodName}: ${e.message}"))
            // What the screen actually showed, so a timeout on CI can be read without a device.
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 12) }
                .getOrElse { "semantics tree unavailable: $it" }
            System.err.println("semantics at timeout (${testName.methodName}):\n" + tree.take(12_000))
            throw e
        }
    }

    protected fun waitFor(matcher: SemanticsMatcher, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun waitGone(matcher: SemanticsMatcher, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Waits until a node matching [matcher] is on screen and displayed. Right
     * after a navigation a node can exist for a frame before it is placed.
     */
    protected fun assertShown(matcher: SemanticsMatcher, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            runCatching { compose.onNode(matcher).assertIsDisplayed() }.isSuccess
        }
    }

    /**
     * Waits until [read], a read of what the app keeps in its `settings`
     * DataStore, gives [expected]. A screen shows a choice before DataStore has
     * written it, so a stored value is waited for, never read once right after
     * a tap. A read that throws counts as not yet: it runs on the test thread
     * while the app writes, and DataStore before 1.1.3 threw
     * FileNotFoundException when the file's very first write landed mid-read.
     * On timeout the error names the last read, value or exception.
     */
    protected fun <T> waitUntilStored(expected: T, read: suspend () -> T) {
        var last: Result<T>? = null
        try {
            waitUntil {
                val result = runCatching { runBlocking { read() } }
                last = result
                result.isSuccess && result.getOrNull() == expected
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("${testName.methodName}: stored value never became $expected, last read $last", e)
        }
    }

    protected fun waitForText(text: String) = waitFor(hasText(text))

    protected fun waitForDescription(description: String) = waitFor(hasContentDescription(description))

    protected fun clickText(text: String): SemanticsNodeInteraction {
        waitForText(text)
        return compose.onNode(hasText(text)).performClick()
    }

    protected fun clickDescription(description: String): SemanticsNodeInteraction {
        waitForDescription(description)
        return compose.onNode(hasContentDescription(description)).performClick()
    }

    /** A button inside the dialog that is open, where the same text may also be on the screen behind it. */
    protected fun clickInDialog(text: String): SemanticsNodeInteraction {
        val matcher = hasText(text) and hasAnyAncestor(isDialog())
        waitFor(matcher)
        return compose.onNode(matcher).performClick()
    }

    // --- steps several flows share, written as a person would do them ---

    /** Settings from Home's gear, then the group titled [group] (a `settings_group_*` string) (issue #67). */
    protected fun openSettingsGroup(@StringRes group: Int) {
        clickDescription(str(R.string.settings_title))
        clickText(str(group))
    }

    /** From a Settings group back to Home: the group's back, then the list's. */
    protected fun leaveSettings() {
        clickDescription(str(Res.string.action_back))
        waitForText(str(R.string.settings_group_about))
        clickDescription(str(Res.string.action_back))
    }

    /** On Account, signed out: "Use another server" and [url] in the field it reveals. */
    protected fun useServer(url: String) {
        clickText(str(R.string.sync_use_another_server))
        val field = hasSetTextAction() and hasText(str(R.string.sync_server_url))
        waitFor(field)
        compose.onNode(field).performScrollTo().performTextReplacement(url)
    }

    protected fun startWorkoutFromHome() {
        // The button is disabled until Home has read the database; a tap before that does nothing.
        val start = hasText(str(R.string.home_start_workout)) and isEnabled()
        waitFor(start)
        compose.onNode(start).performClick()
        waitForText(str(R.string.workout_empty))
    }

    protected fun searchPicker(query: String) {
        waitForText(str(R.string.picker_title))
        compose.onNode(hasSetTextAction()).performTextReplacement(query)
    }

    /**
     * From the active workout: opens the picker, searches and taps the exercise
     * called [name]. An exercise with no history opens its weight sheet at once
     * (issue #55); this dismisses it, leaving the one empty set the flows that
     * call this expect. [addExerciseFirstTime] keeps it open.
     */
    protected fun addExercise(query: String, name: String) {
        addExerciseFirstTime(query, name)
        if (lastAddedIsEmpty()) {
            waitForDescription(str(R.string.input_field_weight))
            dismissSheet(str(R.string.input_field_weight))
        }
    }

    /** [addExercise] without dismissing the first-time weight sheet. */
    protected fun addExerciseFirstTime(query: String, name: String) {
        clickText(str(R.string.workout_add_exercise))
        searchPicker(query)
        clickText(name)
        waitForText(str(R.string.workout_finish))
        waitForText(name)
    }

    /** Whether the exercise added last to the workout in progress has one set of 0 × 0, i.e. no history. */
    protected fun lastAddedIsEmpty(): Boolean = db.openHelper.readableDatabase.query(
        """
        SELECT COUNT(*), MAX(s.weightKg), MAX(s.reps) FROM set_entry s WHERE s.workoutExerciseId = (
            SELECT we.id FROM workout_exercise we JOIN workout w ON we.workoutId = w.id
            WHERE w.endedAt IS NULL ORDER BY we.position DESC LIMIT 1
        )
        """.trimIndent(),
    ).use { c -> c.moveToFirst() && c.getInt(0) == 1 && c.getDouble(1) == 0.0 && c.getInt(2) == 0 }

    /**
     * What Material 3's sheet scrim says to a screen reader ("Close sheet", in
     * the app's language); its click dismisses the sheet. Compose UI's own
     * string, merged into the app's resources.
     */
    private fun sheetScrimDescription(): String =
        app.getString(app.resources.getIdentifier("close_sheet", "string", app.packageName))

    /** Closes the open bottom sheet the way a tap outside it does, and waits until [fieldDescription] is gone. */
    protected fun dismissSheet(fieldDescription: String) {
        val scrim = hasContentDescription(sheetScrimDescription())
        waitFor(scrim)
        // The action, not a tap at its centre: there the sheet itself covers the scrim.
        compose.onAllNodes(scrim).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitGone(hasContentDescription(fieldDescription))
    }

    /** Opens the number sheet from the cell described as [cellDescription], types [text] and taps Done. */
    /**
     * Opens the cell's input sheet, types [text], confirms, and waits until the
     * cell reads [cellAfter]. The sheet closes before the command behind Done
     * has written and the row has re-read it (issue #37): a tick clicked in
     * that gap sees reps 0 and opens the reps sheet again, so waiting only for
     * the sheet to be gone is not enough.
     */
    protected fun enterInSheet(cellDescription: String, fieldDescription: String, text: String, cellAfter: String) {
        clickDescription(cellDescription)
        waitForDescription(fieldDescription)
        compose.onNode(hasContentDescription(fieldDescription)).performTextReplacement(text)
        compose.onNode(hasText(str(Res.string.input_done))).performClick()
        waitGone(hasContentDescription(fieldDescription))
        waitForDescription(cellAfter)
    }

    /** Taps "Finish workout" in the top bar and confirms it in the dialog. */
    protected fun finishWorkout(screenshotStep: String? = null) {
        clickText(str(R.string.workout_finish))
        waitFor(isDialog())
        screenshotStep?.let { screenshot(it) }
        clickInDialog(str(R.string.workout_finish_confirm))
    }

    // --- database ---

    protected fun count(sql: String): Int =
        db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    protected fun bench(): String = catalogExerciseId("bench_press")

    /**
     * Writes a finished workout through the real repository, as if it had been
     * logged in the app at [startedAt]: one exercise, one ticked set.
     */
    protected fun seedFinishedWorkout(startedAt: Long, exerciseId: String, weightKg: Double, reps: Int): String =
        runBlocking {
            var now = startedAt
            val repo = WorkoutRepository(db, clock = { now })
            val workoutId = repo.startOrResumeWorkout()
            val workoutExerciseId = repo.addExercise(workoutId, exerciseId, sets = 1)
            val setId = db.openHelper.readableDatabase
                .query("SELECT id FROM set_entry WHERE workoutExerciseId = ?", arrayOf(workoutExerciseId))
                .use { c -> c.moveToFirst(); c.getString(0) }
            repo.updateWeight(setId, weightKg)
            repo.updateReps(setId, reps)
            repo.setCompleted(setId, true)
            now = startedAt + 45 * 60_000L
            check(repo.finishWorkout(workoutId)) { "seeded workout was discarded" }
            workoutId
        }

    // --- screenshots ---

    /**
     * Saves the topmost window (a dialog or sheet if one is open, else the
     * activity) as `<test>-<step>.png` in `app/build/reports/flow-screenshots/`.
     * An artefact, not an assertion; skipped when Gradle did not name a directory.
     *
     * Draws the window's view into a bitmap rather than using
     * `captureToImage()`: on API 34 that waits for a frame-commit callback,
     * which Robolectric never fires, and times out after two seconds.
     */
    protected fun screenshot(step: String) {
        // Set by Gradle for testDebugUnitTest only (see app/build.gradle.kts).
        val dir = System.getProperty("tung.flowScreenshots")?.let(::File) ?: return
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        val view = (roots.last().root as ViewRootForTest).view.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        dir.mkdirs()
        File(dir, "${testName.methodName}-$step.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

/** Every live thread with its state and stack, for a test that timed out. */
internal fun threadDump(title: String): String = buildString {
    appendLine("=== thread dump: $title")
    appendLine("main looper idle: ${shadowOf(Looper.getMainLooper()).isIdle}")
    Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread, stack) ->
        appendLine("\"${thread.name}\" daemon=${thread.isDaemon} state=${thread.state}")
        stack.forEach { appendLine("    at $it") }
        appendLine()
    }
    appendLine("=== end of thread dump")
}
