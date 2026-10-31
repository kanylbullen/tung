package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import io.github.kanylbullen.tung.core.command.Command
import kotlinx.coroutines.runBlocking
import io.github.kanylbullen.tung.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale
import org.robolectric.annotation.Config

/**
 * The same app on a Swedish phone. The other flow tests force English; this
 * one checks that what TalkBack hears uses the localised exercise names, and
 * that the screens in `ui/` (Compose Resources) speak the same language as
 * the rest of the app (Android resources). The Swedish texts are written out:
 * `str(Res.string.…)` reads Compose Resources outside any screen, which is no
 * proof of what a screen shows.
 */
@Config(qualifiers = "sv-w411dp-h891dp-xhdpi")
class SwedishFlowTest : FlowTest() {

    /**
     * A Swedish phone's default locale is Swedish. FlowTest sets English for
     * every test (number formats), but Compose Resources pick the language
     * from the default locale, not from the qualifiers as Android's resources
     * do; on a phone Android sets both from the app's language and recreates
     * the activity when it changes. Runs after FlowTest's @Before.
     */
    @Before
    fun swedishDefaultLocale() {
        Locale.setDefault(Locale.forLanguageTag("sv"))
    }

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

    /**
     * The Routines tab in Swedish: the bottom bar (app's strings), the title,
     * "+ Ny rutin", the Programs row and a row's "0 övningar · Aldrig" (ui's
     * plural and separator) all in one language.
     */
    @Test
    fun theRoutinesTabSpeaksSwedishLikeTheRestOfTheApp() {
        runBlocking { app.container.commands.execute(Command.CreateRoutine("Ben")) }
        compose.onNode(hasText(str(R.string.nav_routines)) and isSelectable()).performClick()

        waitForText("Ben")
        // The title; the tab of the same text is the selectable one.
        assertShown(hasText("Rutiner") and !isSelectable())
        assertShown(hasText("+ Ny rutin"))
        assertShown(hasText("Rutiner på fasta veckodagar eller rullande"))
        assertShown(hasText("0 övningar · Aldrig"))
        screenshot("2-routines-sv")
    }
}
