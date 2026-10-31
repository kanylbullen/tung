package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.data.ProgramRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.ui.routines.weekdayFull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class ProgramFlowTest : FlowTest() {

    private val benchPress get() = str(R.string.ex_bench_press)
    private val barbellRow get() = str(R.string.ex_barbell_row)

    private fun dayOf(number: Int, count: Int) = str(R.string.program_day_of, number, count)
    private fun rotatingLine(number: Int, count: Int, routine: String) =
        str(R.string.today_rotating, dayOf(number, count), routine)
    private fun startToday(routine: String) = hasContentDescription(str(R.string.today_start_description, routine))

    /**
     * Someone with a "Push day" and a "Pull day" routine opens Routines, then
     * Programs, creates a rotating program "Push pull" with both days and
     * activates it. Home now says "Day 1 of 2: Push day"; they start it, log
     * one set and finish, and Home moves on to "Day 2 of 2: Pull day".
     */
    @Test
    fun aRotatingProgramMovesOnAfterAFinishedDay() {
        val push = seedRoutine("Push day", bench())
        val pull = seedRoutine("Pull day", row())

        waitForText("Push day")
        clickText(str(R.string.routines_all))
        waitGone(hasText(str(R.string.home_recent)))
        clickText(str(R.string.programs_title))
        clickText(str(R.string.program_new))
        val field = hasSetTextAction() and hasAnyAncestor(isDialog())
        waitFor(field)
        compose.onNode(field).performTextReplacement("Push pull")
        screenshot("1-new-program")
        clickInDialog(str(R.string.program_create))
        waitGone(isDialog())

        waitForText(str(R.string.program_days_empty))
        clickText(str(R.string.program_add_day))
        waitForText(str(R.string.program_pick_routine))
        screenshot("2-pick-routine")
        clickText("Push day")
        waitGone(hasText(str(R.string.program_pick_routine)))
        clickText(str(R.string.program_add_day))
        waitForText(str(R.string.program_pick_routine))
        clickText("Pull day")
        waitGone(hasText(str(R.string.program_pick_routine)))
        waitForText(dayOf(2, 2))
        clickDescription(str(R.string.program_activate_description, "Push pull"))
        waitForText(str(R.string.program_active))
        screenshot("3-program")

        assertEquals(1, count("SELECT COUNT(*) FROM program WHERE name = 'Push pull' AND mode = 'rotating' AND isActive = 1"))
        assertEquals(1, count("SELECT COUNT(*) FROM program_day WHERE routineId = '$push' AND position = 0"))
        assertEquals(1, count("SELECT COUNT(*) FROM program_day WHERE routineId = '$pull' AND position = 1"))

        clickDescription(str(R.string.action_back))
        waitForText(str(R.string.program_new))
        clickDescription(str(R.string.action_back))
        waitForText(str(Res.string.programs_entry_active, "Push pull"))
        // Routines is a tab now, with no back arrow: Home is the tab next to it.
        clickText(str(R.string.nav_home))
        waitForText(rotatingLine(1, 2, "Push day"))
        assertShown(hasText("Push pull"))
        screenshot("4-home-day-1")

        val start = startToday("Push day") and isEnabled()
        waitFor(start)
        compose.onNode(start).performClick()
        waitForText(str(R.string.workout_finish))
        waitForText(benchPress)
        clickDescription(str(R.string.workout_mark_done, 1))
        waitForDescription(str(R.string.workout_mark_undone, 1))
        finishWorkout()
        waitForText(str(R.string.summary_title))
        clickText(str(R.string.summary_done))

        waitForText(rotatingLine(2, 2, "Pull day"))
        assertShown(startToday("Pull day"))
        screenshot("5-home-day-2")

        val pushDay = string("SELECT id FROM program_day WHERE routineId = '$push'")
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM workout WHERE endedAt IS NOT NULL " +
                    "AND routineId = '$push' AND programDayId = '$pushDay'",
            ),
        )
    }

    /**
     * With today a Wednesday by the test clock, a fixed program whose only day
     * has no weekdays makes Home say "Rest day" with no Start. Ticking
     * Wednesday on that day in the program turns Home's Today into
     * "Today: Push day" with a Start.
     */
    @Test
    fun aFixedProgramShowsTodaysRoutineOrARestDay() {
        val zone = ZoneId.systemDefault()
        val wednesday = LocalDate.of(2026, 10, 21)
        app.today = Clock.fixed(wednesday.atTime(10, 0).atZone(zone).toInstant(), zone)
        val weekday = LocalDate.now(app.today).dayOfWeek.value
        assertEquals(3, weekday)

        val push = seedRoutine("Push day", bench())
        runBlocking {
            val programs = ProgramRepository(db)
            val program = programs.create("Weekly", ProgramMode.FIXED)
            programs.addDay(program, push)
            programs.activate(program)
        }

        waitForText(str(R.string.today_rest))
        assertShown(hasText("Weekly"))
        compose.onNode(startToday("Push day")).assertDoesNotExist()
        screenshot("1-home-rest-day")

        clickText(str(R.string.routines_all))
        waitGone(hasText(str(R.string.home_recent)))
        clickText(str(R.string.programs_title))
        clickText("Weekly")
        waitForText(str(R.string.program_active))
        val toggle = str(R.string.program_weekday_description, "Push day", weekdayFull(weekday))
        clickDescription(toggle)
        waitUntil(5_000) { count("SELECT weekdays FROM program_day") == 1 shl (weekday - 1) }
        assertShown(hasContentDescription(toggle) and isOn())
        screenshot("2-program-wednesday")

        clickDescription(str(R.string.action_back))
        waitForText(str(R.string.program_new))
        clickDescription(str(R.string.action_back))
        waitForText(str(Res.string.programs_entry_active, "Weekly"))
        // Routines is a tab now, with no back arrow: Home is the tab next to it.
        clickText(str(R.string.nav_home))
        waitForText(str(R.string.today_fixed, "Push day"))
        assertShown(startToday("Push day") and isEnabled())
        compose.onNode(hasText(str(R.string.today_rest))).assertDoesNotExist()
        screenshot("3-home-today")
    }

    /**
     * Home suggests "Day 1 of 2: Push day", but someone taps "Choose another"
     * and picks Pull day in the sheet. The workout that opens is Pull day's,
     * and it records that program day.
     */
    @Test
    fun chooseAnotherStartsTheChosenDay() {
        val push = seedRoutine("Push day", bench())
        val pull = seedRoutine("Pull day", row())
        val pullDay = runBlocking {
            val programs = ProgramRepository(db)
            val program = programs.create("Push pull", ProgramMode.ROTATING)
            programs.addDay(program, push)
            val day = programs.addDay(program, pull)
            programs.activate(program)
            day
        }

        waitForText(rotatingLine(1, 2, "Push day"))
        clickText(str(R.string.today_choose_another))
        waitForText(str(R.string.today_choose_title))
        screenshot("1-choose-sheet")
        // "Pull day" is on Home too, in Routines; the sheet's row is the one with its place in the program.
        clickText(dayOf(2, 2))

        waitForText(str(R.string.workout_finish))
        waitForText(barbellRow)
        compose.onNode(hasText(benchPress)).assertDoesNotExist()
        screenshot("2-active-workout")

        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM workout WHERE endedAt IS NULL " +
                    "AND routineId = '$pull' AND programDayId = '$pullDay'",
            ),
        )
    }

    /**
     * Home suggests "Day 1 of 2: Push day", but another device removed that day
     * and the sync landed between the card's drawing and the tap (here the row
     * is deleted behind Room's back, so the card still shows it). Start then
     * opens nothing: Home says the day is gone, and the card, which follows the
     * database, has moved on to the program as it is now, Pull day alone.
     */
    @Test
    fun startingADayThatIsGoneSaysSoAndMovesOn() {
        val push = seedRoutine("Push day", bench())
        val pull = seedRoutine("Pull day", row())
        val pushDay = runBlocking {
            val programs = ProgramRepository(db)
            val program = programs.create("Push pull", ProgramMode.ROTATING)
            val day = programs.addDay(program, push)
            programs.addDay(program, pull)
            programs.activate(program)
            day
        }

        waitForText(rotatingLine(1, 2, "Push day"))
        // Room's invalidation tracker looks only after a Room transaction, so the card keeps showing Push day.
        db.openHelper.writableDatabase.execSQL("DELETE FROM program_day WHERE id = ?", arrayOf(pushDay))
        compose.onNode(startToday("Push day")).performClick()

        waitForText(str(R.string.today_day_gone))
        screenshot("1-day-gone")
        // The refusing command's own transaction brought the removal to the card.
        waitForText(rotatingLine(1, 1, "Pull day"))
        compose.onNode(hasText(str(R.string.workout_finish))).assertDoesNotExist()
        assertEquals(0, count("SELECT COUNT(*) FROM workout WHERE endedAt IS NULL"))
        assertEquals(1, count("SELECT COUNT(*) FROM program_day WHERE routineId = '$pull'"))
    }

    /**
     * A rotating program "Split" with one day changes its mind: on its own
     * screen the owner picks "Fixed days", the day gets its weekday toggles
     * (and loses "Day 1 of 1"); ticking Monday and going back to "Rotating"
     * keeps the day, and the Monday it had.
     */
    @Test
    fun switchAProgramsMode() {
        val push = seedRoutine("Push day", bench())
        val programId = runBlocking {
            val programs = ProgramRepository(db)
            programs.create("Split", ProgramMode.ROTATING).also { programs.addDay(it, push) }
        }

        waitForText("Push day")
        clickText(str(R.string.routines_all))
        waitGone(hasText(str(R.string.home_recent)))
        clickText(str(R.string.programs_title))
        clickText("Split")
        waitForText(str(R.string.program_mode_title))
        waitForText(dayOf(1, 1))
        screenshot("1-rotating")

        clickText(str(R.string.program_mode_fixed))
        val monday = hasContentDescription(str(R.string.program_weekday_description, "Push day", weekdayFull(1)))
        waitFor(monday)
        waitGone(hasText(dayOf(1, 1)))
        assertEquals(1, count("SELECT COUNT(*) FROM program WHERE id = '$programId' AND mode = 'fixed' AND dirty = 1"))
        compose.onNode(monday).performClick()
        waitUntil { count("SELECT COUNT(*) FROM program_day WHERE weekdays = 1") == 1 }
        screenshot("2-fixed")

        clickText(str(R.string.program_mode_rotating))
        waitForText(dayOf(1, 1))
        waitGone(monday)
        waitUntil { count("SELECT COUNT(*) FROM program WHERE id = '$programId' AND mode = 'rotating'") == 1 }
        assertEquals("the day and its Monday are kept", 1, count("SELECT COUNT(*) FROM program_day WHERE routineId = '$push' AND weekdays = 1"))
        screenshot("3-rotating-again")
    }

    // --- seeding through the real repositories ---

    private fun row(): String = catalogExerciseId("barbell_row")

    /** A routine called [name] with one exercise and one set of 8, saved from a workout logged three days ago. */
    private fun seedRoutine(name: String, exerciseId: String): String {
        val source = seedFinishedWorkout(System.currentTimeMillis() - 3 * DAY, exerciseId, 60.0, 8)
        return checkNotNull(runBlocking { RoutineRepository(db).saveFromWorkout(source, name) })
    }

    private fun string(sql: String): String =
        db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getString(0) }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
