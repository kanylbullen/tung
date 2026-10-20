package io.github.kanylbullen.tung.flows

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.platform.ViewRootForTest
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
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
import androidx.compose.ui.test.performClick
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

    /** From the active workout: opens the picker, searches and taps the exercise called [name]. */
    protected fun addExercise(query: String, name: String) {
        clickText(str(R.string.workout_add_exercise))
        searchPicker(query)
        clickText(name)
        waitForText(str(R.string.workout_finish))
        waitForText(name)
    }

    /** Opens the number sheet from the cell described as [cellDescription], types [text] and taps Done. */
    protected fun enterInSheet(cellDescription: String, fieldDescription: String, text: String) {
        clickDescription(cellDescription)
        waitForDescription(fieldDescription)
        compose.onNode(hasContentDescription(fieldDescription)).performTextReplacement(text)
        compose.onNode(hasText(str(R.string.input_done))).performClick()
        waitGone(hasContentDescription(fieldDescription))
    }

    /** Taps "Finish workout" in the top bar and confirms it in the dialog. */
    protected fun finishWorkout(screenshotStep: String? = null) {
        clickText(str(R.string.workout_finish))
        waitFor(isDialog())
        screenshotStep?.let { screenshot(it) }
        clickInDialog(str(R.string.workout_finish))
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
            val workoutExerciseId = repo.addExercise(workoutId, exerciseId)
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
