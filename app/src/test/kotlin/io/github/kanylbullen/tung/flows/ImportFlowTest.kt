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
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.importing.JefitImport
import io.github.kanylbullen.tung.core.importing.nameUsage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * The synthetic export with two more names nothing matches exactly, both
     * logged: the bent-over row is a machine seated row and the curl a
     * dumbbell hammer curl to press. With Heel Walking, three to decide.
     */
    private val threeNames: String by lazy {
        fixture.readText().replace("Barbell Bent Over Row", "Machine Seated Row").replace("Barbell Curl", "Dumbbell Hammer Curl to Press")
    }

    private fun answerFilePicker(text: String? = null) {
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        val copy = File(app.cacheDir, "jefit-export.csv").apply { if (text != null) writeText(text) else writeBytes(fixture.readBytes()) }
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

    private fun openImport(text: String) {
        openSettingsGroup(R.string.settings_group_data)
        scrollToInSettings(str(R.string.import_jefit))
        compose.onNode(hasText(str(R.string.import_jefit))).performClick()
        answerFilePicker(text)
        waitForText(str(R.string.import_review_title))
    }

    private fun importNow() {
        scrollToInList(str(R.string.import_action))
        waitGone(hasText(str(R.string.import_decide_first)))
        compose.onNode(hasText(str(R.string.import_action)) and hasClickAction()).assertIsEnabled().performClick()
        waitForText(str(R.string.import_done_title))
    }

    /**
     * The offline suggestions and the bulk decisions (issue #71): each row
     * says how much of the export uses the name, a suggestion chip chooses
     * that exercise, and "Skip all remaining" decides only the rows still
     * undecided, so Import is enabled.
     */
    @Test
    fun aSuggestionChipChoosesAndSkipAllRemainingDecidesTheRest() {
        openImport(threeNames)
        val usage = JefitImport.parse(threeNames, ZoneId.systemDefault()).nameUsage()
        val hammer = usage.getValue("Dumbbell Hammer Curl to Press").workouts
        scrollToInList("Dumbbell Hammer Curl to Press")
        waitFor(hasText(plural(R.plurals.import_used_in_workouts, hammer, hammer)))
        // No assistant set up: no button for it.
        assertTrue(compose.onAllNodes(hasText(str(R.string.import_assist_action))).fetchSemanticsNodes().isEmpty())

        val chip = str(R.string.import_suggestion, str(R.string.ex_hammer_curl))
        scrollToInList(chip)
        clickText(chip)
        assertShown(hasText(str(R.string.import_chosen_use, str(R.string.ex_hammer_curl))))
        assertEquals(0, count("SELECT COUNT(*) FROM workout"))
        screenshot("1-chip-chosen")

        scrollToInList(str(R.string.import_skip_remaining))
        clickText(str(R.string.import_skip_remaining))
        waitUntil { compose.onAllNodes(hasText(str(R.string.import_chosen_skip))).fetchSemanticsNodes().size == 2 }
        // The chosen row keeps its choice.
        assertShown(hasText(str(R.string.import_chosen_use, str(R.string.ex_hammer_curl))))
        screenshot("2-skipped-the-rest")
        importNow()
        assertTrue(count("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = '${catalogExerciseId("hammer_curl")}'") >= 1)
        assertEquals(0, count("SELECT COUNT(*) FROM exercise WHERE isCustom = 1"))
    }

    /**
     * "Suggest with the assistant" with a provider set up (a MockWebServer as
     * an OpenAI-compatible API): the first request is shown before it goes,
     * the rows show the proposals, "Accept all high-confidence" decides those
     * rows and leaves the low one undecided, and nothing is written until
     * Import.
     */
    @Test
    fun theAssistantProposesAndAcceptAllHighConfidenceDecidesThoseRows() {
        val server = MockWebServer().apply { start() }
        try {
            runBlocking {
                app.container.aiSettings.save(AiProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString().trimEnd('/'), "test-model", jsonMode = true)
                app.container.aiSettings.setKey("sk-flow-test")
            }
            val text = """{"matches": [
                {"name": "Machine Seated Row", "ref": {"key": "seated_cable_row"}, "confidence": "high", "note": ""},
                {"name": "Dumbbell Hammer Curl to Press", "ref": {"key": "hammer_curl"}, "confidence": "high", "note": ""},
                {"name": "Heel Walking", "ref": {"key": "plank"}, "confidence": "low", "note": "Not the same exercise."}]}"""
            val body = buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("role", "assistant"); put("content", text) }) }) })
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Json.encodeToString(JsonObject.serializer(), body)))

            openImport(threeNames)
            scrollToInList(str(R.string.import_assist_action))
            clickText(str(R.string.import_assist_action))
            waitForText(str(R.string.ai_payload_title))
            screenshot("1-what-is-sent")
            assertEquals(0, server.requestCount)
            clickText(str(R.string.ai_payload_send))

            // The answer comes back on another thread: wait for the button it brings.
            waitUntil { runCatching { scrollToInList(str(R.string.import_assist_accept_all)) }.isSuccess }
            val seated = str(R.string.import_assist_suggests, str(R.string.ex_seated_cable_row), str(R.string.import_confidence_high))
            scrollToInList(seated)
            assertShown(hasText(seated))
            val plank = str(R.string.import_assist_suggests, str(R.string.ex_plank), str(R.string.import_confidence_low))
            scrollToInList(plank)
            assertShown(hasText(plank))
            screenshot("2-proposals")
            val sent = server.takeRequest().body.readUtf8()
            assertFalse("an @ was sent", sent.contains('@'))
            assertTrue(sent.contains("Machine Seated Row"))
            assertFalse(sent.contains("weight_kg"))
            // A proposal applies nothing by itself: no row says "As …", Import still waits.
            assertTrue(compose.onAllNodes(hasText(str(R.string.import_chosen_use, str(R.string.ex_seated_cable_row)))).fetchSemanticsNodes().isEmpty())
            scrollToInList(str(R.string.import_decide_first))
            compose.onNode(hasText(str(R.string.import_action)) and hasClickAction()).assertIsNotEnabled()

            scrollToInList(str(R.string.import_assist_accept_all))
            clickText(str(R.string.import_assist_accept_all))
            scrollToInList(str(R.string.ex_seated_cable_row).let { str(R.string.import_chosen_use, it) })
            scrollToInList(str(R.string.import_chosen_use, str(R.string.ex_hammer_curl)))
            scrollToInList("Heel Walking")
            assertShown(hasText(str(R.string.import_chosen_none)))
            scrollToInList(str(R.string.import_decide_first))
            compose.onNode(hasText(str(R.string.import_action)) and hasClickAction()).assertIsNotEnabled()
            screenshot("3-accepted")

            scrollToInList(str(R.string.import_skip_remaining))
            clickText(str(R.string.import_skip_remaining))
            importNow()
            assertTrue(count("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = '${catalogExerciseId("seated_cable_row")}'") >= 1)
            assertTrue(count("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = '${catalogExerciseId("hammer_curl")}'") >= 1)
            assertEquals(0, count("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = '${catalogExerciseId("plank")}'"))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
