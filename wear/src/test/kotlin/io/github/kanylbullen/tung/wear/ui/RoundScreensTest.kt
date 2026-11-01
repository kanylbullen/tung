package io.github.kanylbullen.tung.wear.ui

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.sync.SyncState
import io.github.kanylbullen.tung.core.wear.WatchExercise
import io.github.kanylbullen.tung.core.wear.WatchLastSet
import io.github.kanylbullen.tung.core.wear.WatchRest
import io.github.kanylbullen.tung.core.wear.WatchRoutine
import io.github.kanylbullen.tung.core.wear.WatchSet
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.sync.AccountState
import io.github.kanylbullen.tung.wear.AccountUi
import io.github.kanylbullen.tung.wear.AdjustField
import io.github.kanylbullen.tung.wear.LinkCodeInput
import io.github.kanylbullen.tung.wear.LinkUi
import io.github.kanylbullen.tung.wear.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A small round watch: 192 dp across (384 px at xhdpi). */
@Config(qualifiers = "w192dp-h192dp-round-watch-xhdpi")
class SmallRoundScreensTest : RoundScreensTest()

/** A large round watch: 227 dp across, as the Galaxy Watch7's 432 px. */
@Config(qualifiers = "w227dp-h227dp-round-watch-xhdpi")
class LargeRoundScreensTest : RoundScreensTest()

/**
 * Every watch screen drawn on a round screen (issue #84): the controls a
 * person needs must lie on the visible circle, not past its edge. A control
 * is a rounded rectangle (a chip's ends are half its height) and must fit
 * inside the circle inscribed in the screen ([RoundGeometry.fitsInCircle]).
 * Lists are checked where they open and, for the last item, scrolled to the
 * end. Each step is also saved as a PNG in `wear/build/reports/round-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class RoundScreensTest {

    val compose = createComposeRule()

    /**
     * Registers the empty activity [compose] hosts the screens in. The
     * ui-test-manifest library would declare it in the debug APK only, and the
     * release variant runs these tests too.
     */
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addOrUpdateActivity(
                ActivityInfo().apply {
                    name = ComponentActivity::class.java.name
                    packageName = app.packageName
                },
            )
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(compose)
    @get:Rule val testName = TestName()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    /** On the watch's black, as the window is on a watch (the test's activity is white). */
    private fun show(content: @Composable () -> Unit) = compose.setContent {
        TungWearTheme { Box(Modifier.fillMaxSize().background(Color.Black)) { content() } }
    }

    @Test
    fun startScreenInCompanionMode() {
        show {
            StartScreen(
                routines = listOf(
                    WatchRoutine("r1", "Skokloster Gym · JT2 dag 1", 6),
                    WatchRoutine("r2", "Push day", 5),
                    WatchRoutine("r3", "Legs", 4),
                ),
                standalone = false,
                neverHeardFromPhone = true,
                phoneUnreachable = false,
                onStartWorkout = {},
                onStartRoutine = {},
                onSettings = {},
            )
        }
        screenshot("top")
        assertOnCircle(hasText(str(R.string.open_phone_hint)))
        assertOnCircle(chip(str(R.string.start_workout)), chip = true)
        scrollToEnd()
        screenshot("end")
        assertOnCircle(chip(str(R.string.settings_title)), chip = true)
    }

    @Test
    fun settingsNotLinked() {
        show {
            SettingsScreen(
                ui = AccountUi(
                    account = AccountState(serverUrl = null, email = null, signedIn = false, sync = SyncState(), syncing = false),
                    phoneServer = "https://tung.xuper.fun",
                ),
                onSyncNow = {},
                onSignOut = {},
                onLinkTo = {},
                onChooseServer = {},
            )
        }
        screenshot("top")
        assertOnCircle(hasText(str(R.string.settings_title)))
        scrollToEnd()
        screenshot("end")
        assertOnCircle(chip(str(R.string.link_choose_server)), chip = true)
    }

    @Test
    fun settingsSignedIn() {
        show {
            SettingsScreen(
                ui = AccountUi(
                    account = AccountState(
                        serverUrl = "https://tung.xuper.fun",
                        email = "someone.with.a.long.address@example.com",
                        signedIn = true,
                        sync = SyncState(lastSyncAt = 1_793_000_000_000L),
                        syncing = false,
                    ),
                ),
                onSyncNow = {},
                onSignOut = {},
                onLinkTo = {},
                onChooseServer = {},
            )
        }
        screenshot("top")
        scrollToEnd()
        screenshot("end")
        assertOnCircle(chip(str(R.string.sign_out)), chip = true)
    }

    @Test
    fun exercisePage() {
        show { Workout(workout()) }
        screenshot("set")
        assertOnCircle(hasContentDescription(str(R.string.edit_weight, "102.5")))
        assertOnCircle(hasContentDescription(str(R.string.edit_reps, "12")))
        assertOnCircle(chip(str(R.string.log_set)), chip = true)
    }

    @Test
    fun finishPage() {
        show { Workout(workout()) }
        repeat(2) { compose.onRoot().performTouchInput { swipeLeft() } }
        compose.waitForIdle()
        screenshot("finish")
        assertOnCircle(chip(str(R.string.finish_workout)), chip = true)
    }

    @Test
    fun restCountdown() {
        val rest = WatchRest(endsAtMillis = System.currentTimeMillis() + 10 * 60_000L, durationMillis = 15 * 60_000L)
        compose.mainClock.autoAdvance = false
        show { Workout(workout().copy(rest = rest)) }
        compose.mainClock.advanceTimeByFrame()
        screenshot("rest")
        assertOnCircle(chip(str(R.string.rest_skip)), chip = true)
        assertOnCircle(hasContentDescription(str(R.string.rest_add_description)), chip = true)
    }

    @Test
    fun adjustWeight() {
        show { AdjustScreen(AdjustField.Weight, initial = 102.5, onDone = {}) }
        screenshot("weight")
        assertOnCircle(hasText("102.5"))
        assertOnCircle(hasText(str(R.string.input_minus)) and hasClickAction(), chip = true)
        assertOnCircle(hasText(str(R.string.input_plus)) and hasClickAction(), chip = true)
        assertOnCircle(chip(str(R.string.input_done)), chip = true)
    }

    @Test
    fun adjustReps() {
        show { AdjustScreen(AdjustField.Reps, initial = 12.0, onDone = {}) }
        screenshot("reps")
        assertOnCircle(hasContentDescription(str(R.string.input_minus_reps)), chip = true)
        assertOnCircle(hasContentDescription(str(R.string.input_plus_reps)), chip = true)
        assertOnCircle(chip(str(R.string.input_done)), chip = true)
    }

    @Test
    fun linkCode() {
        show {
            LinkCodeScreen(
                link = LinkUi(server = "https://tung.xuper.fun", input = LinkCodeInput("123")),
                onDigit = {},
                onDelete = {},
                onSubmit = {},
            )
        }
        screenshot("pad")
        assertOnCircle(hasText(str(R.string.link_title_server, "tung.xuper.fun")))
        for (digit in "1234567890") assertOnCircle(hasText(digit.toString()) and hasClickAction(), chip = true)
        assertOnCircle(hasContentDescription(str(R.string.link_delete)), chip = true)
        assertOnCircle(hasContentDescription(str(R.string.link_submit)), chip = true)
    }

    // --- the screens' inputs ---

    private fun workout() = WatchWorkoutState(
        workoutId = "w",
        startedAt = 0L,
        exercises = listOf(
            WatchExercise(
                "bench", "Incline dumbbell bench press",
                listOf(WatchSet("b1", 0, 102.5, 12, false), WatchSet("b2", 1, 102.5, 12, false), WatchSet("b3", 2, 102.5, 12, false)),
                lastTime = listOf(WatchLastSet(100.0, 12)),
            ),
        ),
        currentSetId = "b1",
        updatedAt = 1L,
    )

    @Composable
    private fun Workout(state: WatchWorkoutState) = WorkoutScreen(
        state = state,
        phoneUnreachable = false,
        onLogSet = {},
        onAddSet = {},
        onSelectSet = {},
        onAdjust = { _, _ -> },
        onFinish = {},
        onSkipRest = {},
        onAddRest = {},
        claimRestBuzz = { false },
    )

    // --- checks ---

    private fun chip(label: String) = hasText(label) and hasClickAction()

    /** Swipes the list up until its end is on screen. */
    private fun scrollToEnd() {
        repeat(4) { compose.onRoot().performTouchInput { swipeUp() } }
        compose.waitForIdle()
    }

    /**
     * The one node matching [matcher] lies on the circle. A [chip] has rounded
     * ends (half its height); anything else is taken as a plain box.
     */
    private fun assertOnCircle(matcher: SemanticsMatcher, chip: Boolean = false) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds: Rect = compose.onNode(matcher, useUnmergedTree = !chip).fetchSemanticsNode().boundsInRoot
        val diameter = minOf(root.width, root.height)
        val fits = RoundGeometry.fitsInCircle(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            diameter = diameter,
            cornerRadius = if (chip) bounds.height / 2f else 0f,
            // A pixel for rounding.
            margin = -1f,
        )
        assertTrue("${matcher.description} at $bounds leaves the ${diameter}px circle", fits)
    }

    /**
     * Saves the screen as `<test>-<step>.png` in the directory Gradle names
     * (wear/build.gradle.kts, debug only). Drawn from the view, as app/'s flow
     * tests do: captureToImage() times out under Robolectric on API 34.
     */
    private fun screenshot(step: String) {
        val dir = System.getProperty("tung.roundScreenshots")?.let(::File) ?: return
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        val view = (roots.last().root as ViewRootForTest).view.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        dir.mkdirs()
        File(dir, "${javaClass.simpleName}-${testName.methodName}-$step.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
