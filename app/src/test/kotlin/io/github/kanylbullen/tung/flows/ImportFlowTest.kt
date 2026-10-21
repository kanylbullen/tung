package io.github.kanylbullen.tung.flows

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.importing.JefitImport
import io.github.kanylbullen.tung.ui.common.localDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.ZoneId

/**
 * Import from Jefit (issue #5) from Settings to History and back: the system
 * file picker is answered with the synthetic export
 * (`core/src/test/resources/import/jefit/`) through Robolectric's recorded
 * activity result, so no test seam is needed in the app.
 */
class ImportFlowTest : FlowTest() {

    private val fixture = File("../core/src/test/resources/import/jefit/export-synthetic.csv")

    /** The workouts' dates as History shows them, read from the same file in the app's zone. */
    private val dates: List<String> by lazy {
        JefitImport.parse(fixture.readText(), ZoneId.systemDefault()).workouts.map { localDate(it.startedAt) }
    }

    private fun counts(workouts: Int, sets: Int, routines: Int) = listOf(
        plural(R.plurals.import_workouts, workouts, workouts),
        plural(R.plurals.import_sets, sets, sets),
        plural(R.plurals.import_routines, routines, routines),
    ).joinToString(str(R.string.row_separator))

    /** Settings' Data group is a scrolling column. */
    private fun scrollToInSettings(text: String) {
        waitForText(text)
        compose.onNode(hasText(text)).performScrollTo()
    }

    /** The import screen is a lazy list. */
    private fun scrollToInList(text: String) {
        waitFor(hasScrollToNodeAction())
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    private fun answerFilePicker() {
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        val copy = File(app.cacheDir, "jefit-export.csv").apply { writeBytes(fixture.readBytes()) }
        shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(copy)))
    }

    /**
     * Settings → Import from Jefit → the file: the preview shows four
     * workouts, twelve sets and three routines, the warm-up note, and one name
     * to decide on (Heel Walking). Import waits for the decision; with "Skip"
     * it imports four workouts with eleven sets, History lists them on
     * Jefit's dates, and "Undo last import" in Settings removes them again.
     */
    @Test
    fun importReviewHistoryAndUndo() {
        openSettingsGroup(R.string.settings_group_data)
        scrollToInSettings(str(R.string.import_jefit))
        screenshot("1-settings")
        compose.onNode(hasText(str(R.string.import_jefit))).performClick()
        answerFilePicker()

        waitForText(str(R.string.import_review_title))
        assertShown(hasText("Heel Walking"))
        assertShown(hasText(counts(4, 12, 3)))
        assertShown(hasText(str(R.string.import_chosen_none)))
        scrollToInList(str(R.string.import_decide_first))
        assertShown(hasText(plural(R.plurals.import_note_warm_up, 1, 1)))
        compose.onNode(hasText(str(R.string.import_action)) and hasClickAction()).assertIsNotEnabled()
        screenshot("2-preview")

        scrollToInList("Heel Walking")
        clickText(str(R.string.import_choice_skip))
        assertShown(hasText(str(R.string.import_chosen_skip)))
        scrollToInList(str(R.string.import_action))
        waitGone(hasText(str(R.string.import_decide_first)))
        compose.onNode(hasText(str(R.string.import_action)) and hasClickAction()).assertIsEnabled().performClick()

        waitForText(str(R.string.import_done_title))
        assertShown(hasText(str(R.string.import_done_added, counts(4, 11, 3))))
        screenshot("3-summary")
        assertEquals(4, count("SELECT COUNT(*) FROM workout WHERE importSource = 'jefit' AND endedAt IS NOT NULL"))
        assertEquals(0, count("SELECT COUNT(*) FROM workout_exercise we JOIN exercise e ON we.exerciseId = e.id WHERE e.name = 'Heel Walking'"))

        clickText(str(R.string.import_done))
        scrollToInSettings(str(R.string.import_undo_last))
        leaveSettings()
        clickText(str(R.string.nav_history))
        waitGone(hasText(str(R.string.home_recent)))
        dates.forEach { waitForText(it) }
        screenshot("4-history")

        clickText(str(R.string.nav_home))
        openSettingsGroup(R.string.settings_group_data)
        scrollToInSettings(str(R.string.import_undo_last))
        compose.onNode(hasText(str(R.string.import_undo_last))).performClick()
        waitUntil { count("SELECT COUNT(*) FROM workout") == 0 }
        waitGone(hasText(str(R.string.import_undo_last)))
        assertEquals(0, count("SELECT COUNT(*) FROM routine"))
        leaveSettings()
        clickText(str(R.string.nav_history))
        dates.forEach { waitGone(hasText(it)) }
        screenshot("5-history-after-undo")
    }

    /**
     * "Choose" opens the picker in choose mode; a tap there hands the exercise
     * back to the review without adding it anywhere, and the import logs Heel
     * Walking as that exercise.
     */
    @Test
    fun chooseAnExerciseForAnUnmatchedName() {
        openSettingsGroup(R.string.settings_group_data)
        scrollToInSettings(str(R.string.import_jefit))
        compose.onNode(hasText(str(R.string.import_jefit))).performClick()
        answerFilePicker()

        waitForText(str(R.string.import_review_title))
        clickText(str(R.string.import_choice_pick))
        waitForText(str(R.string.import_pick_title))
        compose.onNode(hasSetTextAction()).performTextReplacement("plank")
        clickText(str(R.string.ex_plank))
        assertShown(hasText(str(R.string.import_chosen_use, str(R.string.ex_plank))))
        screenshot("1-chosen")
        assertEquals(0, count("SELECT COUNT(*) FROM workout"))

        scrollToInList(str(R.string.import_action))
        compose.onNode(hasText(str(R.string.import_action)) and hasClickAction()).assertIsEnabled().performClick()
        waitForText(str(R.string.import_done_title))
        assertEquals(1, count("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = '${catalogExerciseId("plank")}'"))
        // Monday's routine has it too.
        assertEquals(1, count("SELECT COUNT(*) FROM routine_exercise WHERE exerciseId = '${catalogExerciseId("plank")}'"))
    }
}
