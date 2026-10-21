package io.github.kanylbullen.tung.core.progress

import io.github.kanylbullen.tung.core.LoggedSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ProgressTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun at(date: String, hour: Int = 12, zone: ZoneId = utc): Long =
        LocalDateTime.parse("${date}T%02d:00".format(hour)).atZone(zone).toInstant().toEpochMilli()

    private fun set(
        workout: String,
        startedAt: Long,
        kg: Double,
        reps: Int,
        position: Int = 0,
        exercise: String = "bench",
        exercisePosition: Int = 0,
        completed: Boolean = true,
    ) = ProgressSet(exercise, workout, "$workout-$exercise-$exercisePosition", startedAt, exercisePosition, position, LoggedSet(kg, reps), completed)

    // --- estimated 1RM ---

    @Test
    fun epleyOnOneToTwelveReps() {
        assertEquals(100.0, estimatedOneRepMax(100.0, 1)!!, 1e-9)
        assertEquals(80.0 * (1 + 9 / 30.0), estimatedOneRepMax(80.0, 9)!!, 1e-9)
        assertEquals(60.0 * 1.4, estimatedOneRepMax(60.0, 12)!!, 1e-9)
        assertNull(estimatedOneRepMax(60.0, 13))
        assertNull(estimatedOneRepMax(60.0, 0))
        assertNull(estimatedOneRepMax(60.0, -1))
        assertEquals(0.0, estimatedOneRepMax(0.0, 10)!!, 1e-9)
    }

    // --- sessions ---

    @Test
    fun noSetsNoSessions() {
        assertTrue(sessionPoints(emptyList(), utc).isEmpty())
        assertTrue(exerciseProgress(emptyList(), utc).isEmpty())
    }

    @Test
    fun sessionsAreOldestFirstWithTheirNumbers() {
        val sets = listOf(
            set("w2", at("2026-02-10"), 85.0, 5, position = 0),
            set("w2", at("2026-02-10"), 80.0, 8, position = 1),
            set("w1", at("2026-02-03"), 80.0, 6),
        )
        val points = sessionPoints(sets, utc)
        assertEquals(listOf("w1", "w2"), points.map { it.workoutId })
        val second = points[1]
        assertEquals(LocalDate.parse("2026-02-10"), second.date)
        assertEquals(LoggedSet(85.0, 5), second.topSet)
        assertEquals(85.0 * 5 + 80.0 * 8, second.volumeKg, 1e-9)
        // 80 × 8 = 101.33 beats 85 × 5 = 99.17.
        assertEquals(LoggedSet(80.0, 8), second.e1rmSet)
        assertEquals(80.0 * (1 + 8 / 30.0), second.value(ProgressMetric.E1RM)!!, 1e-9)
        assertEquals(85.0, second.value(ProgressMetric.TOP_SET)!!, 1e-9)
        assertEquals(second.volumeKg, second.value(ProgressMetric.VOLUME)!!, 1e-9)
        assertEquals(listOf(LoggedSet(85.0, 5), LoggedSet(80.0, 8)), second.sets)
    }

    @Test
    fun unloggedAndZeroRepSetsDoNotCount() {
        val sets = listOf(
            set("w1", at("2026-02-03"), 200.0, 0),
            set("w1", at("2026-02-03"), 150.0, 5, position = 1, completed = false),
            set("w1", at("2026-02-03"), 100.0, 5, position = 2),
            set("w2", at("2026-02-05"), 100.0, 0),
        )
        val points = sessionPoints(sets, utc)
        assertEquals(1, points.size)
        assertEquals(LoggedSet(100.0, 5), points.single().topSet)
        assertEquals(500.0, points.single().volumeKg, 1e-9)
    }

    @Test
    fun highRepSetsHaveNoEstimate() {
        val points = sessionPoints(listOf(set("w1", at("2026-02-03"), 50.0, 15), set("w1", at("2026-02-03"), 40.0, 20, 1)), utc)
        assertNull(points.single().e1rmKg)
        assertNull(points.single().e1rmSet)
        assertEquals(50.0, points.single().value(ProgressMetric.TOP_SET)!!, 1e-9)
    }

    @Test
    fun aHighRepSetIsSkippedButTheRestOfTheSessionCounts() {
        val points = sessionPoints(listOf(set("w1", at("2026-02-03"), 100.0, 3), set("w1", at("2026-02-03"), 100.0, 15, 1)), utc)
        assertEquals(110.0, points.single().e1rmKg!!, 1e-9)
    }

    @Test
    fun anExerciseTwiceInAWorkoutIsOneSession() {
        val sets = listOf(
            set("w1", at("2026-02-03"), 60.0, 10, exercisePosition = 3),
            set("w1", at("2026-02-03"), 80.0, 5, exercisePosition = 0),
        )
        val point = sessionPoints(sets, utc).single()
        assertEquals(listOf(LoggedSet(80.0, 5), LoggedSet(60.0, 10)), point.sets)
        assertEquals(1000.0, point.volumeKg, 1e-9)
    }

    @Test
    fun topSetTieGoesToMoreReps() {
        val point = sessionPoints(listOf(set("w1", at("2026-02-03"), 80.0, 5), set("w1", at("2026-02-03"), 80.0, 7, 1)), utc).single()
        assertEquals(LoggedSet(80.0, 7), point.topSet)
    }

    @Test
    fun theDateIsTheStartInTheZone() {
        val stockholm = ZoneId.of("Europe/Stockholm")
        // 23:30 UTC on 31 January is 00:30 on 1 February in Stockholm.
        val startedAt = LocalDateTime.parse("2026-01-31T23:30").atZone(utc).toInstant().toEpochMilli()
        assertEquals(LocalDate.parse("2026-01-31"), sessionPoints(listOf(set("w1", startedAt, 50.0, 5)), utc).single().date)
        assertEquals(LocalDate.parse("2026-02-01"), sessionPoints(listOf(set("w1", startedAt, 50.0, 5)), stockholm).single().date)
    }

    // --- ranges and change ---

    @Test
    fun rangesCutFromToday() {
        val today = LocalDate.parse("2026-10-21")
        assertEquals(LocalDate.parse("2026-07-21"), ProgressRange.THREE_MONTHS.startOn(today))
        assertEquals(LocalDate.parse("2025-10-21"), ProgressRange.ONE_YEAR.startOn(today))
        assertNull(ProgressRange.ALL.startOn(today))
        val points = sessionPoints(
            listOf(
                set("old", at("2024-05-01"), 60.0, 5),
                set("year", at("2026-01-10"), 70.0, 5),
                set("edge", at("2026-07-21"), 75.0, 5),
                set("recent", at("2026-10-20"), 80.0, 5),
            ),
            utc,
        )
        assertEquals(listOf("edge", "recent"), points.within(ProgressRange.THREE_MONTHS, today).map { it.workoutId })
        assertEquals(listOf("year", "edge", "recent"), points.within(ProgressRange.ONE_YEAR, today).map { it.workoutId })
        assertEquals(4, points.within(ProgressRange.ALL, today).size)
    }

    @Test
    fun changeFromFirstToLast() {
        val points = sessionPoints(
            listOf(set("a", at("2026-08-01"), 80.0, 5), set("b", at("2026-09-01"), 90.0, 5), set("c", at("2026-10-01"), 87.5, 5)),
            utc,
        )
        val change = change(points, ProgressMetric.TOP_SET)!!
        assertEquals(80.0, change.from, 1e-9)
        assertEquals(87.5, change.to, 1e-9)
        assertEquals(7.5, change.delta, 1e-9)
        assertNull(change(points.take(1), ProgressMetric.TOP_SET))
        assertNull(change(emptyList(), ProgressMetric.VOLUME))
    }

    @Test
    fun changeSkipsSessionsWithoutAValue() {
        val points = sessionPoints(
            listOf(set("a", at("2026-08-01"), 80.0, 5), set("b", at("2026-09-01"), 40.0, 20), set("c", at("2026-10-01"), 90.0, 1)),
            utc,
        )
        val change = change(points, ProgressMetric.E1RM)!!
        assertEquals(80.0 * (1 + 5 / 30.0), change.from, 1e-9)
        assertEquals(90.0, change.to, 1e-9)
    }

    /** Sessions 2, 8 and 13 months back: 3 months holds one (no trend), 12 months two (a change), 13 months is out. */
    @Test
    fun rowTrendOverThreeAndTwelveMonths() {
        val today = LocalDate.parse("2026-10-21")
        val bench = exerciseProgress(
            listOf(
                set("old", at("2025-09-21"), 50.0, 1),
                set("eight", at("2026-02-21"), 90.0, 1),
                set("two", at("2026-08-21"), 89.0, 1),
            ),
            utc,
        ).single()
        assertEquals(listOf(ProgressRange.THREE_MONTHS, ProgressRange.ONE_YEAR), ROW_RANGES)

        val three = bench.trend(ProgressRange.THREE_MONTHS, today)
        assertEquals(listOf(89.0), three.estimates)
        assertNull(three.change)

        val twelve = bench.trend(ProgressRange.ONE_YEAR, today)
        assertEquals(listOf(90.0, 89.0), twelve.estimates)
        assertEquals(-1.0, twelve.change!!.delta, 1e-9)

        // The first day of the 12 months counts; the day before does not.
        val edge = exerciseProgress(
            listOf(set("edge", at("2025-10-21"), 80.0, 1), set("before", at("2025-10-20"), 70.0, 1), set("now", at("2026-10-20"), 85.0, 1)),
            utc,
        ).single().trend(ProgressRange.ONE_YEAR, today)
        assertEquals(listOf(80.0, 85.0), edge.estimates)
        assertEquals(5.0, edge.change!!.delta, 1e-9)
    }

    @Test
    fun exercisesMostRecentlyTrainedFirst() {
        val sets = listOf(
            set("w1", at("2026-10-01"), 80.0, 5, exercise = "bench"),
            set("w1", at("2026-10-01"), 100.0, 5, exercise = "squat"),
            set("w2", at("2026-10-08"), 100.0, 5, exercise = "squat"),
            set("w3", at("2026-10-08"), 50.0, 5, exercise = "row"),
            set("w4", at("2026-10-09"), 50.0, 0, exercise = "curl"),
        )
        // squat and row tie on the day and the hour: by id.
        assertEquals(listOf("row", "squat", "bench"), exerciseProgress(sets, utc).map { it.exerciseId })
    }

    // --- weeks and streak ---

    @Test
    fun weekStartIsMondayAcrossAYearChange() {
        // Thursday 1 January 2026 is in the ISO week that starts Monday 29 December 2025.
        assertEquals(LocalDate.parse("2025-12-29"), weekStart(LocalDate.parse("2026-01-01")))
        assertEquals(LocalDate.parse("2025-12-29"), weekStart(LocalDate.parse("2025-12-29")))
        assertEquals(LocalDate.parse("2025-12-29"), weekStart(LocalDate.parse("2026-01-04")))
        assertEquals(LocalDate.parse("2026-01-05"), weekStart(LocalDate.parse("2026-01-05")))
    }

    @Test
    fun weeklyBucketsIncludeEmptyWeeks() {
        val today = LocalDate.parse("2026-01-07") // Wednesday
        val sets = listOf(
            set("a", at("2025-12-30"), 100.0, 5),
            set("b", at("2026-01-02"), 50.0, 10),
            set("b", at("2026-01-02"), 50.0, 10, 1),
            set("c", at("2026-01-06"), 60.0, 5),
            set("x", at("2026-01-06"), 60.0, 5, completed = false),
            set("old", at("2025-01-06"), 60.0, 5),
        )
        val weeks = weeklyBuckets(sets, today, utc, weeks = 3)
        assertEquals(
            listOf(LocalDate.parse("2025-12-22"), LocalDate.parse("2025-12-29"), LocalDate.parse("2026-01-05")),
            weeks.map { it.weekStart },
        )
        assertEquals(listOf(0, 2, 1), weeks.map { it.workouts })
        assertEquals(listOf(0.0, 1500.0, 300.0), weeks.map { it.volumeKg })
        assertEquals(OVERVIEW_WEEKS, weeklyBuckets(emptyList(), today, utc).size)
    }

    @Test
    fun weeklyBucketsUseTheZone() {
        val stockholm = ZoneId.of("Europe/Stockholm")
        // Sunday 23:30 UTC is Monday 00:30 in Stockholm: the next week there.
        val late = LocalDateTime.parse("2026-01-04T23:30").atZone(utc).toInstant().toEpochMilli()
        val today = LocalDate.parse("2026-01-07")
        assertEquals(listOf(1, 0), weeklyBuckets(listOf(set("a", late, 50.0, 5)), today, utc, 2).map { it.workouts })
        assertEquals(listOf(0, 1), weeklyBuckets(listOf(set("a", late, 50.0, 5)), today, stockholm, 2).map { it.workouts })
    }

    @Test
    fun streakCountsConsecutiveWeeks() {
        val today = LocalDate.parse("2026-01-07")
        val dates = listOf("2026-01-05", "2026-01-01", "2025-12-22", "2025-12-15", "2025-12-01").map(LocalDate::parse)
        // This week, the week across the new year, 22 and 15 December; the week of 8 December is empty.
        assertEquals(4, weeklyStreak(dates, today))
    }

    @Test
    fun anEmptyCurrentWeekDoesNotBreakTheStreak() {
        val today = LocalDate.parse("2026-01-07")
        assertEquals(2, weeklyStreak(listOf("2026-01-01", "2025-12-22").map(LocalDate::parse), today))
    }

    @Test
    fun anEmptyLastWeekBreaksIt() {
        val today = LocalDate.parse("2026-01-07")
        assertEquals(0, weeklyStreak(listOf(LocalDate.parse("2025-12-22")), today))
        assertEquals(1, weeklyStreak(listOf(LocalDate.parse("2026-01-07"), LocalDate.parse("2025-12-22")), today))
        assertEquals(0, weeklyStreak(emptyList(), today))
    }

    @Test
    fun streakFromSetsIgnoresUnloggedOnes() {
        val today = LocalDate.parse("2026-01-07")
        val sets = listOf(set("a", at("2026-01-06"), 50.0, 5, completed = false), set("b", at("2025-12-30"), 50.0, 5))
        assertEquals(1, weeklyStreak(sets, today, utc))
    }

    // --- records ---

    private fun records(vararg sets: ProgressSet) = exerciseRecords(sessionPoints(sets.toList(), utc))

    @Test
    fun noHistoryNoRecords() {
        val records = exerciseRecords(emptyList())
        assertTrue(records.isEmpty)
        assertTrue(records.all.isEmpty())
        assertNull(beatsRecord(100.0, 5, records))
    }

    @Test
    fun recordsWithTheirDates() {
        val records = records(
            set("w1", at("2026-09-01"), 100.0, 3),
            set("w1", at("2026-09-01"), 60.0, 15, 1),
            set("w2", at("2026-09-08"), 90.0, 8),
            set("w2", at("2026-09-08"), 90.0, 8, 1),
        )
        val heaviest = records.heaviest!!
        assertEquals(100.0, heaviest.value, 1e-9)
        assertEquals(LocalDate.parse("2026-09-01"), heaviest.date)
        val e1rm = records.bestE1rm!!
        assertEquals(90.0 * (1 + 8 / 30.0), e1rm.value, 1e-9)
        assertEquals("w2", e1rm.workoutId)
        assertEquals(LoggedSet(90.0, 8), e1rm.set)
        val reps = records.mostReps!!
        assertEquals(15.0, reps.value, 1e-9)
        assertEquals(LoggedSet(60.0, 15), reps.set)
        val volume = records.bestVolume!!
        assertEquals(1440.0, volume.value, 1e-9)
        assertEquals("w2", volume.workoutId)
        assertEquals("w1", records.firstWorkoutId)
        assertEquals(RecordKind.entries, records.all.map { it.kind })
    }

    @Test
    fun aTieKeepsTheFirstDate() {
        val records = records(set("w1", at("2026-09-01"), 100.0, 5), set("w2", at("2026-09-08"), 100.0, 5))
        assertEquals("w1", records.heaviest!!.workoutId)
        assertEquals("w1", records.bestE1rm!!.workoutId)
        assertEquals("w1", records.mostReps!!.workoutId)
        assertEquals("w1", records.bestVolume!!.workoutId)
    }

    @Test
    fun mostRepsTieGoesToTheHeavierSet() {
        val records = records(set("w1", at("2026-09-01"), 60.0, 10), set("w2", at("2026-09-08"), 70.0, 10))
        assertEquals(LoggedSet(70.0, 10), records.mostReps!!.set)
        assertEquals("w2", records.mostReps!!.workoutId)
    }

    @Test
    fun mostRepsAtOrAboveAWeight() {
        val sets = listOf(LoggedSet(100.0, 3), LoggedSet(80.0, 8), LoggedSet(60.0, 12), LoggedSet(80.0, 0))
        assertEquals(8, mostRepsAtOrAbove(80.0, sets))
        assertEquals(3, mostRepsAtOrAbove(90.0, sets))
        assertEquals(12, mostRepsAtOrAbove(0.0, sets))
        assertNull(mostRepsAtOrAbove(105.0, sets))
    }

    @Test
    fun whatBeatsARecord() {
        // Heaviest 100 × 3 (e1RM 110), best e1RM 90 × 8 (114), 80 × 8.
        val records = records(
            set("w1", at("2026-09-01"), 100.0, 3),
            set("w2", at("2026-09-08"), 90.0, 8),
            set("w2", at("2026-09-08"), 80.0, 8, 1),
        )
        assertEquals(RecordKind.HEAVIEST_WEIGHT, beatsRecord(102.5, 1, records))
        // 100 × 4 (113.3) is no better estimate, but 4 reps at 100 kg or more beats 3.
        assertEquals(RecordKind.MOST_REPS, beatsRecord(100.0, 4, records))
        assertEquals(RecordKind.BEST_E1RM, beatsRecord(95.0, 7, records))
        assertEquals(RecordKind.MOST_REPS, beatsRecord(80.0, 9, records))
        assertNull(beatsRecord(80.0, 8, records))
        assertNull(beatsRecord(100.0, 3, records))
        assertNull(beatsRecord(100.0, 0, records))
    }

    @Test
    fun aHeavierSetWinsOverAnEstimate() {
        val records = records(set("w1", at("2026-09-01"), 100.0, 5))
        // 105 × 10 beats both; the heavier weight ranks first.
        assertEquals(RecordKind.HEAVIEST_WEIGHT, beatsRecord(105.0, 10, records))
    }

    @Test
    fun highRepsBeatOnlyMostReps() {
        val records = records(set("w1", at("2026-09-01"), 60.0, 12))
        assertEquals(RecordKind.MOST_REPS, beatsRecord(60.0, 20, records))
        assertNull(beatsRecord(50.0, 12, records))
    }

    @Test
    fun repsThatWouldBeatTheEstimate() {
        // Best estimate 90 × 8 = 114.
        val records = records(set("w1", at("2026-09-01"), 90.0, 8))
        assertEquals(9, repsToBeatE1rm(90.0, records))
        assertEquals(3, repsToBeatE1rm(105.0, records)) // 105 × 3 = 115.5; × 2 = 112
        assertEquals(1, repsToBeatE1rm(115.0, records))
        assertNull(repsToBeatE1rm(50.0, records)) // 50 × 12 = 70
        assertNull(repsToBeatE1rm(90.0, ExerciseRecords.EMPTY))
    }

    // --- recent records and overview ---

    @Test
    fun recentRecordsSkipTheFirstSessionAndKeepOnePerWorkout() {
        val sets = listOf(
            set("b1", at("2026-09-01"), 80.0, 5, exercise = "bench"),
            set("b2", at("2026-09-08"), 85.0, 5, exercise = "bench"),
            set("s1", at("2026-09-02"), 100.0, 5, exercise = "squat"),
            set("s2", at("2026-09-09"), 90.0, 12, exercise = "squat"),
            set("r1", at("2026-09-10"), 50.0, 10, exercise = "row"),
        )
        val recent = recentRecords(exerciseProgress(sets, utc))
        // Squat's 90 × 12: best e1RM (126), most reps and best volume; one row, the highest-ranked.
        // Bench's 85 × 5 holds all four; heaviest weight ranks first. Row has one session: nothing.
        assertEquals(listOf("squat" to RecordKind.BEST_E1RM, "bench" to RecordKind.HEAVIEST_WEIGHT), recent.map { it.exerciseId to it.record.kind })
        assertEquals(LocalDate.parse("2026-09-09"), recent.first().record.date)
    }

    @Test
    fun recentRecordsAreLimited() {
        val sets = (1..8).flatMap { i ->
            listOf(
                set("e$i-a", at("2026-09-01"), 50.0, 5, exercise = "ex$i"),
                set("e$i-b", at("2026-09-%02d".format(i + 1)), 60.0, 5, exercise = "ex$i"),
            )
        }
        val recent = recentRecords(exerciseProgress(sets, utc))
        assertEquals(RECENT_RECORDS, recent.size)
        assertEquals("ex8", recent.first().exerciseId)
    }

    @Test
    fun overviewFromOneRead() {
        val today = LocalDate.parse("2026-10-21")
        val sets = listOf(
            set("a", at("2026-10-13"), 80.0, 5),
            set("b", at("2026-10-20"), 85.0, 5),
        )
        val overview = progressOverview(sets, today, utc)
        assertEquals(OVERVIEW_WEEKS, overview.weeks.size)
        assertEquals(1, overview.weeks.last().workouts)
        assertEquals(2, overview.streak)
        assertEquals(listOf("bench"), overview.exercises.map { it.exerciseId })
        assertEquals(1, overview.recentRecords.size)
        assertTrue(progressOverview(emptyList(), today, utc).isEmpty)
    }
}
