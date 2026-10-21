package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import io.github.kanylbullen.tung.ui.exercises.EQUIPMENT_BODYWEIGHT_ONLY_TAG
import io.github.kanylbullen.tung.ui.exercises.EQUIPMENT_CHIP_TAG
import io.github.kanylbullen.tung.ui.exercises.EQUIPMENT_EVERYTHING_TAG
import io.github.kanylbullen.tung.ui.exercises.EQUIPMENT_SHEET_TAG
import io.github.kanylbullen.tung.ui.exercises.EXERCISES_LIST_TAG
import io.github.kanylbullen.tung.ui.settings.EQUIPMENT_SETTINGS_ROW_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale

/**
 * "My equipment" (issue #65): the chip under the Exercises tab's group chips
 * and in the picker opens a sheet; the choice is saved at once, narrows the
 * lists, and Settings shows the same choice.
 */
class EquipmentFlowTest : FlowTest() {

    private fun chip(label: String) = hasTestTag(EQUIPMENT_CHIP_TAG) and hasText(label)

    private fun stored(): Set<Equipment> = runBlocking { app.container.equipmentSettings.current() }

    /** Taps the chip, then "Bodyweight only", then "Done". */
    private fun chooseBodyweightOnly() {
        compose.onNode(hasTestTag(EQUIPMENT_CHIP_TAG)).performClick()
        waitFor(hasTestTag(EQUIPMENT_SHEET_TAG))
        compose.onNode(hasTestTag(EQUIPMENT_BODYWEIGHT_ONLY_TAG)).performClick()
        // Saved at once: the chip under the sheet already says so. Read the store only after that,
        // since DataStore 1.1.1 can fail a read that races the file's very first write.
        waitFor(chip(str(R.string.my_equipment_chip_count, 1)))
        assertEquals(EquipmentFilter.BODYWEIGHT_ONLY, stored())
        screenshot("sheet-bodyweight-only")
        clickText(str(R.string.my_equipment_done))
        waitGone(hasTestTag(EQUIPMENT_SHEET_TAG))
    }

    /**
     * The tab: "Bodyweight only" leaves bodyweight exercises (Push-up stays,
     * Bench press goes, no row says Barbell or Dumbbells), the chip reads
     * "Equipment: 1", and the search combines with it.
     */
    @Test
    fun bodyweightOnlyNarrowsTheTab() {
        clickText(str(R.string.nav_exercises))
        waitFor(hasTestTag(EXERCISES_LIST_TAG))
        waitForText(str(R.string.ex_bench_press))
        assertShown(chip(str(R.string.my_equipment_chip_all)))
        screenshot("1-tab-all")

        chooseBodyweightOnly()
        assertShown(chip(str(R.string.my_equipment_chip_count, 1)))
        waitGone(hasText(str(R.string.ex_bench_press)))
        assertShown(hasText(str(R.string.ex_push_up)))
        compose.onNode(hasText(str(R.string.equipment_barbell))).assertDoesNotExist()
        compose.onNode(hasText(str(R.string.equipment_dumbbell))).assertDoesNotExist()
        screenshot("2-tab-bodyweight")

        searchTab("press")
        compose.onNode(hasText(str(R.string.ex_overhead_press))).assertDoesNotExist()
        compose.onNode(hasText(str(R.string.ex_bench_press))).assertDoesNotExist()
        searchTab("push")
        waitForText(str(R.string.ex_push_up))
        compose.onNode(hasText("Double Kettlebell Push Press")).assertDoesNotExist()
        screenshot("3-tab-search")
    }

    /**
     * The picker of a workout that already holds Bench press: after "Bodyweight
     * only" Bench press still shows (it is in the workout), Overhead press does
     * not, and "More exercises" holds only bodyweight entries.
     */
    @Test
    fun thePickerKeepsToItAndKeepsTheWorkoutsExercises() {
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))
        clickText(str(R.string.workout_add_exercise))
        waitForText(str(R.string.picker_title))
        assertShown(chip(str(R.string.my_equipment_chip_all)))

        chooseBodyweightOnly()
        assertShown(chip(str(R.string.my_equipment_chip_count, 1)))
        searchPicker("press")
        waitForText(str(R.string.ex_bench_press))
        compose.onNode(hasText(str(R.string.ex_overhead_press))).assertDoesNotExist()
        screenshot("1-picker-press")

        val (shown, hidden) = runBlocking {
            val inRoom = app.container.exerciseRepository.observeExercises().first().mapTo(HashSet()) { it.id }
            val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.SECONDARY }
            val matches = ExtendedCatalog.search(app.container.extendedCatalog.all(), inRoom, "push").sortedWith(compareBy(collator) { it.displayName })
            matches.first { it.equipment == Equipment.BODYWEIGHT.key } to matches.first { it.equipment == Equipment.KETTLEBELL.key }
        }
        searchPicker("push")
        val list = compose.onNode(hasScrollAction() and hasTestTag(EXERCISES_LIST_TAG))
        waitFor(hasText(str(R.string.picker_more)), timeoutMillis = 10_000)
        list.performScrollToNode(hasText(shown.displayName))
        assertShown(hasText(shown.displayName))
        compose.onNode(hasText(hidden.displayName)).assertDoesNotExist()
        screenshot("2-picker-more")
    }

    /** Settings shows the choice made in the tab, and "Everything" there brings the tab back to "Equipment: All". */
    @Test
    fun settingsShowsTheSameChoice() {
        clickText(str(R.string.nav_exercises))
        waitFor(hasTestTag(EXERCISES_LIST_TAG))
        chooseBodyweightOnly()

        clickText(str(R.string.nav_home))
        clickDescription(str(R.string.settings_title))
        waitFor(hasTestTag(EQUIPMENT_SETTINGS_ROW_TAG))
        assertShown(hasTestTag(EQUIPMENT_SETTINGS_ROW_TAG) and hasText(str(R.string.my_equipment_title)))
        assertShown(hasTestTag(EQUIPMENT_SETTINGS_ROW_TAG) and hasText(str(R.string.equipment_bodyweight)))
        screenshot("1-settings")

        compose.onNode(hasTestTag(EQUIPMENT_SETTINGS_ROW_TAG)).performClick()
        waitFor(hasTestTag(EQUIPMENT_SHEET_TAG))
        compose.onNode(hasTestTag(EQUIPMENT_EVERYTHING_TAG)).performClick()
        waitFor(hasTestTag(EQUIPMENT_SETTINGS_ROW_TAG) and hasText(str(R.string.my_equipment_everything)))
        clickText(str(R.string.my_equipment_done))
        waitGone(hasTestTag(EQUIPMENT_SHEET_TAG))
        assertEquals(EquipmentFilter.ALL, stored())

        clickDescription(str(R.string.action_back))
        clickText(str(R.string.nav_exercises))
        waitFor(chip(str(R.string.my_equipment_chip_all)))
        waitForText(str(R.string.ex_bench_press))
    }

    private fun searchTab(query: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(query)
    }
}
