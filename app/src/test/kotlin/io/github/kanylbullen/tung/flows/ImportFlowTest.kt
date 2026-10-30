package io.github.kanylbullen.tung.flows

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import org.robolectric.annotation.Config
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.importing.JefitImport
import io.github.kanylbullen.tung.core.importing.nameUsage
import io.github.kanylbullen.tung.data.RoutineRepository
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
import io.github.kanylbullen.tung.ui.common.shortLocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlinx.datetime.TimeZone

/**
 * Import from Jefit (issue #5) from Settings to History and back: the system
 * file picker is answered with the synthetic export
 * (`core/src/jvmTest/resources/import/jefit/`) through Robolectric's recorded
 * activity result, so no test seam is needed in the app.
 */
class ImportFlowTest : FlowTest() {

    private val fixture = File("../core/src/jvmTest/resources/import/jefit/export-synthetic.csv")

    /** The workouts' dates as History shows them, read from the same file in the app's zone. */
    private val dates: List<String> by lazy {
        JefitImport.parse(fixture.readText(), TimeZone.currentSystemDefault()).workouts.map { shortLocalDate(it.startedAt) }
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
    private fun scrollToInList(text: String) = scrollToInList(hasText(text))

    private fun scrollToInList(matcher: SemanticsMatcher) {
        waitFor(hasScrollToNodeAction())
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    }

    /** For a node a tap just brought: the state reaches the list on the next frames. */
    private fun scrollToWhenThere(matcher: SemanticsMatcher) = waitUntil { runCatching { scrollToInList(matcher) }.isSuccess }

    /** A collapsed row's decision line, one node for a screen reader (issue #77). */
    private fun decidedAs(name: String, exercise: Int) = hasContentDescription(str(R.string.import_decided_use_description, name, str(exercise)))
    private fun decidedSkip(name: String) = hasContentDescription(str(R.string.import_decided_skip_description, name))
    private fun decidedCreate(name: String) = hasContentDescription(str(R.string.import_decided_create_description, name))
    private fun counter(decided: Int, of: Int) = str(R.string.import_decided_count, decided, of)

    /** The rows still showing Choose/Create/Skip: the undecided and the reopened ones. */
    private fun openRows(): Int = compose.onAllNodes(hasText(str(R.string.import_choice_pick)) and hasClickAction()).fetchSemanticsNodes().size

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
        assertShown(hasText(counter(0, 1)))
        scrollToInList(str(R.string.import_decide_first))
        assertShown(hasText(plural(R.plurals.import_note_warm_up, 1, 1)))
        compose.onNode(hasText(str(R.string.import_action)) and hasClickAction()).assertIsNotEnabled()
        screenshot("2-preview")

        scrollToInList("Heel Walking")
        clickText(str(R.string.import_choice_skip))
        // Intended change (issue #77): a decided row collapses to its decision; Choose/Create/Skip are gone from it.
        assertShown(decidedSkip("Heel Walking"))
        assertShown(hasText(counter(1, 1)))
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
        clickInDialog(str(R.string.import_undo_confirm))
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
        // Intended change (issue #77): the picked exercise is the collapsed row's decision line.
        assertShown(decidedAs("Heel Walking", R.string.ex_plank))
        screenshot("1-chosen")
        assertEquals(0, count("SELECT COUNT(*) FROM workout"))

        scrollToInList(str(R.string.import_action))
        compose.onNode(hasText(str(R.string.import_action)) and hasClickAction()).assertIsEnabled().performClick()
        waitForText(str(R.string.import_done_title))
        assertEquals(1, count("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = '${catalogExerciseId("plank")}'"))
        // Monday's routine has it too.
        assertEquals(1, count("SELECT COUNT(*) FROM routine_exercise WHERE exerciseId = '${catalogExerciseId("plank")}'"))
    }

    /**
     * "Delete routines from the last import (N)" (issue #79): after an import
     * of three routines it offers 3, and 2 once one of them is gone. It deletes
     * only those, never a routine of the person's own nor the imported
     * workouts, and goes away; "Undo last import" afterwards still removes the
     * import's workouts.
     */
    @Test
    fun deleteTheLastImportsRoutinesThenUndo() {
        val ownWorkout = seedFinishedWorkout(System.currentTimeMillis() - 86_400_000L, bench(), 60.0, 8)
        runBlocking { RoutineRepository(db).saveFromWorkout(ownWorkout, "Mine") }
        openImport(fixture.readText())
        scrollToInList("Heel Walking")
        clickText(str(R.string.import_choice_skip))
        importNow()
        assertEquals(4, count("SELECT COUNT(*) FROM routine"))
        clickText(str(R.string.import_done))

        scrollToInSettings(str(R.string.import_delete_routines, 3))
        screenshot("1-shortcut")
        val oneImported = db.openHelper.readableDatabase.query("SELECT id FROM routine WHERE name != 'Mine' LIMIT 1").use { it.moveToFirst(); it.getString(0) }
        runBlocking { RoutineRepository(db).delete(oneImported) }
        scrollToInSettings(str(R.string.import_delete_routines, 2))

        compose.onNode(hasText(str(R.string.import_delete_routines, 2))).performClick()
        waitFor(isDialog())
        assertShown(hasText(plural(R.plurals.routines_delete_many_title, 2, 2)))
        assertShown(hasText(str(R.string.import_delete_routines_body)))
        screenshot("2-confirm")
        clickInDialog(str(R.string.routine_delete_confirm))
        waitUntil { count("SELECT COUNT(*) FROM routine") == 1 }
        assertEquals(1, count("SELECT COUNT(*) FROM routine WHERE name = 'Mine'"))
        waitGone(hasText(str(R.string.import_delete_routines, 2)))
        assertEquals("the import's workouts stay", 4, count("SELECT COUNT(*) FROM workout WHERE importSource = 'jefit'"))

        scrollToInSettings(str(R.string.import_undo_last))
        compose.onNode(hasText(str(R.string.import_undo_last))).performClick()
        clickInDialog(str(R.string.import_undo_confirm))
        waitUntil { count("SELECT COUNT(*) FROM workout WHERE importSource = 'jefit'") == 0 }
        waitGone(hasText(str(R.string.import_undo_last)))
        assertEquals("the person's own workout and routine stay", 1, count("SELECT COUNT(*) FROM workout"))
        assertEquals(1, count("SELECT COUNT(*) FROM routine"))
        screenshot("3-after-undo")
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
        val usage = JefitImport.parse(threeNames, TimeZone.currentSystemDefault()).nameUsage()
        val hammer = usage.getValue("Dumbbell Hammer Curl to Press").workouts
        scrollToInList("Dumbbell Hammer Curl to Press")
        waitFor(hasText(plural(R.plurals.import_used_in_workouts, hammer, hammer)))
        // No assistant set up: no button for it.
        assertTrue(compose.onAllNodes(hasText(str(R.string.import_assist_action))).fetchSemanticsNodes().isEmpty())

        val chip = str(R.string.import_suggestion, str(R.string.ex_hammer_curl))
        scrollToInList(chip)
        clickText(chip)
        // Intended change (issue #77): the chip collapses the row, so its chips are gone and the decision is one line.
        assertShown(decidedAs("Dumbbell Hammer Curl to Press", R.string.ex_hammer_curl))
        waitGone(hasText(chip))
        assertEquals(0, count("SELECT COUNT(*) FROM workout"))
        screenshot("1-chip-chosen")

        scrollToInList(str(R.string.import_skip_remaining))
        clickText(str(R.string.import_skip_remaining))
        // Intended change (issue #77): the bulk decision collapses the rest like a tap on each.
        scrollToInList(decidedSkip("Machine Seated Row"))
        scrollToInList(decidedSkip("Heel Walking"))
        waitUntil { openRows() == 0 }
        // The chosen row keeps its choice.
        scrollToInList(decidedAs("Dumbbell Hammer Curl to Press", R.string.ex_hammer_curl))
        screenshot("2-skipped-the-rest")
        importNow()
        assertTrue(count("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = '${catalogExerciseId("hammer_curl")}'") >= 1)
        assertEquals(0, count("SELECT COUNT(*) FROM exercise WHERE isCustom = 1"))
    }

    /**
     * A decided row looks decided (issue #77): Accept collapses the row to
     * "✓ Seated cable row" and Change, with no proposal, chips or
     * Choose/Create/Skip left on it, and the counter counts it. Change opens
     * the row as before with the decision still standing, so the counter
     * stays; "Create all remaining" collapses the other two, and Import is
     * enabled once all three are decided.
     */
    @Test
    fun acceptCollapsesTheRowAndChangeOpensItAgain() {
        val server = MockWebServer().apply { start() }
        try {
            runBlocking {
                app.container.aiSettings.save(AiProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString().trimEnd('/'), "test-model", jsonMode = true)
                app.container.aiSettings.setKey("sk-flow-test")
                app.container.aiSettings.markPayloadShown()
            }
            val text = """{"matches": [
                {"name": "Machine Seated Row", "ref": {"key": "seated_cable_row"}, "confidence": "high", "note": ""},
                {"name": "Dumbbell Hammer Curl to Press", "ref": null, "confidence": "high", "note": ""},
                {"name": "Heel Walking", "ref": null, "confidence": "high", "note": ""}]}"""
            val body = buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("role", "assistant"); put("content", text) }) }) })
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Json.encodeToString(JsonObject.serializer(), body)))

            openImport(threeNames)
            scrollToInList(counter(0, 3))
            assertShown(hasText(counter(0, 3)))
            scrollToInList(str(R.string.import_assist_action))
            clickText(str(R.string.import_assist_action))
            val seated = str(R.string.import_assist_suggests, str(R.string.ex_seated_cable_row))
            waitUntil { runCatching { scrollToInList(seated) }.isSuccess }
            val accept = hasText(str(R.string.import_assist_accept)) and hasClickAction()
            // The one proposal with an exercise is the only Accept.
            scrollToInList(accept)
            compose.onNode(accept).performClick()

            val decision = decidedAs("Machine Seated Row", R.string.ex_seated_cable_row)
            scrollToInList(decision)
            assertShown(decision)
            val change = hasContentDescription(str(R.string.import_change_description, "Machine Seated Row"))
            assertShown(change)
            // Nothing on the collapsed row asks for a tap: the proposal and its Accept are gone, and the open line with it.
            waitGone(hasText(seated))
            waitGone(accept)
            assertTrue(compose.onAllNodes(hasText(str(R.string.import_chosen_use, str(R.string.ex_seated_cable_row)))).fetchSemanticsNodes().isEmpty())
            scrollToInList(counter(1, 3))
            assertShown(hasText(counter(1, 3)))
            screenshot("1-accepted-collapsed")

            scrollToInList(change)
            compose.onNode(change).performClick()
            scrollToInList(seated)
            assertShown(hasText(seated))
            // Open again exactly as before: the decision stands, so Accept rests and the counter keeps it.
            assertShown(hasText(str(R.string.import_chosen_use, str(R.string.ex_seated_cable_row))))
            compose.onNode(accept).assertIsNotEnabled()
            waitGone(decision)
            scrollToInList(counter(1, 3))
            assertShown(hasText(counter(1, 3)))
            screenshot("2-changed-open")

            scrollToInList(str(R.string.import_create_remaining))
            clickText(str(R.string.import_create_remaining))
            scrollToInList(counter(3, 3))
            assertShown(hasText(counter(3, 3)))
            scrollToInList(decidedCreate("Dumbbell Hammer Curl to Press"))
            scrollToInList(decidedCreate("Heel Walking"))
            // The reopened row was decided, so the bulk action leaves it as it is.
            scrollToInList(str(R.string.import_chosen_use, str(R.string.ex_seated_cable_row)))
            screenshot("3-all-decided")
            importNow()
            assertTrue(count("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = '${catalogExerciseId("seated_cable_row")}'") >= 1)
            assertEquals(2, count("SELECT COUNT(*) FROM exercise WHERE isCustom = 1"))
        } finally {
            server.shutdown()
        }
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
            // Intended change (issue #75): the confidence is a tag of its own beside the proposal, not part of its sentence.
            val seated = str(R.string.import_assist_suggests, str(R.string.ex_seated_cable_row))
            scrollToInList(seated)
            assertShown(hasText(seated))
            val plank = str(R.string.import_assist_suggests, str(R.string.ex_plank))
            scrollToInList(plank)
            assertShown(hasText(plank))
            assertShown(hasContentDescription(str(R.string.confidence_tag_description, str(R.string.import_confidence_low))))
            screenshot("2-proposals")
            val sent = server.takeRequest().body.readUtf8()
            // The app shows English, so the note is asked for in English, by name.
            assertTrue(sent.contains("in English."))
            assertFalse("an @ was sent", sent.contains('@'))
            assertTrue(sent.contains("Machine Seated Row"))
            assertFalse(sent.contains("weight_kg"))
            // A proposal applies nothing by itself: no row says "As …", Import still waits.
            assertTrue(compose.onAllNodes(hasText(str(R.string.import_chosen_use, str(R.string.ex_seated_cable_row)))).fetchSemanticsNodes().isEmpty())
            scrollToInList(str(R.string.import_decide_first))
            compose.onNode(hasText(str(R.string.import_action)) and hasClickAction()).assertIsNotEnabled()

            scrollToInList(str(R.string.import_assist_accept_all))
            clickText(str(R.string.import_assist_accept_all))
            // Intended change (issue #77): the accepted rows collapse to their decision.
            scrollToInList(decidedAs("Machine Seated Row", R.string.ex_seated_cable_row))
            scrollToInList(decidedAs("Dumbbell Hammer Curl to Press", R.string.ex_hammer_curl))
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

    /**
     * How a proposal looks by confidence (issue #75), in the light theme: high
     * (accent-filled tag, primary Accept), medium (accent outline, secondary
     * Accept), no good match (muted text, no tag, no Accept); low is in
     * [theAssistantProposesAndAcceptAllHighConfidenceDecidesThoseRows]. The screenshots
     * are the check that the tags read in this theme; the dark test is the
     * same flow.
     */
    @Test
    fun proposalsAreTaggedByConfidence() = proposalsTaggedByConfidence()

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-night-xhdpi")
    fun proposalsAreTaggedByConfidenceInTheDarkTheme() = proposalsTaggedByConfidence()

    private fun proposalsTaggedByConfidence() {
        val server = MockWebServer().apply { start() }
        try {
            runBlocking {
                app.container.aiSettings.save(AiProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString().trimEnd('/'), "test-model", jsonMode = true)
                app.container.aiSettings.setKey("sk-flow-test")
                app.container.aiSettings.markPayloadShown()
            }
            val text = """{"matches": [
                {"name": "Machine Seated Row", "ref": {"key": "seated_cable_row"}, "confidence": "high", "note": ""},
                {"name": "Dumbbell Hammer Curl to Press", "ref": {"key": "hammer_curl"}, "confidence": "medium", "note": "No press in the catalogue's."},
                {"name": "Heel Walking", "ref": null, "confidence": "high", "note": "No such exercise; create it."}]}"""
            val body = buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("role", "assistant"); put("content", text) }) }) })
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Json.encodeToString(JsonObject.serializer(), body)))

            openImport(threeNames)
            scrollToInList(str(R.string.import_assist_action))
            clickText(str(R.string.import_assist_action))
            waitUntil { runCatching { scrollToInList(str(R.string.import_assist_accept_all)) }.isSuccess }

            fun tag(level: Int) = hasContentDescription(str(R.string.confidence_tag_description, str(level)))
            scrollToInList(str(R.string.import_assist_suggests, str(R.string.ex_seated_cable_row)))
            assertShown(tag(R.string.import_confidence_high))
            screenshot("1-high")
            scrollToInList(str(R.string.import_assist_suggests, str(R.string.ex_hammer_curl)))
            assertShown(tag(R.string.import_confidence_medium))
            scrollToInList(str(R.string.import_assist_no_match))
            screenshot("2-medium-and-no-match")
            // "No good match" has no tag at all, and nothing here is low.
            assertTrue(compose.onAllNodes(tag(R.string.import_confidence_low)).fetchSemanticsNodes().isEmpty())

            // "Accept all high-confidence" takes the row and collapses it (issue #77), the medium one's Accept is still there to tap.
            scrollToInList(str(R.string.import_assist_accept_all))
            clickText(str(R.string.import_assist_accept_all))
            scrollToInList(decidedAs("Machine Seated Row", R.string.ex_seated_cable_row))
            val enabledAccept = hasText(str(R.string.import_assist_accept)) and hasClickAction() and isEnabled()
            scrollToInList(str(R.string.import_assist_suggests, str(R.string.ex_hammer_curl)))
            waitFor(enabledAccept)
            assertEquals(1, compose.onAllNodes(enabledAccept).fetchSemanticsNodes().size)
            // The collapsed row above is shorter, so the button can sit below the fold: bring it into view first.
            scrollToInList(enabledAccept)
            compose.onNode(enabledAccept).performClick()
            scrollToWhenThere(decidedAs("Dumbbell Hammer Curl to Press", R.string.ex_hammer_curl))
            waitGone(enabledAccept)
            screenshot("3-medium-accepted")
        } finally {
            server.shutdown()
        }
    }
}
