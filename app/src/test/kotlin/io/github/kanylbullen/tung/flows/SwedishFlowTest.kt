package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasContentDescription
import io.github.kanylbullen.tung.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The same app on a Swedish phone. The other flow tests force English; this
 * one checks that what TalkBack hears uses the localised exercise names.
 */
@Config(qualifiers = "sv-w411dp-h891dp-xhdpi")
class SwedishFlowTest : FlowTest() {

    /** "+ Set" is described with the name shown on screen, "Bänkpress", not the English fallback. */
    @Test
    fun addSetDescriptionUsesTheLocalisedName() {
        val benchPress = str(R.string.ex_bench_press)
        assertEquals("Bänkpress", benchPress)

        startWorkoutFromHome()
        addExercise("bänk", benchPress)

        val description = str(R.string.workout_add_set_description, benchPress)
        assertShown(hasContentDescription(description))
        compose.onNode(hasContentDescription("Bench press", substring = true)).assertDoesNotExist()
        screenshot("1-workout-sv")
    }
}
