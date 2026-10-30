package io.github.kanylbullen.tung.core.importing

import io.github.kanylbullen.tung.core.ROUTINE_NAME_SEPARATOR
import io.github.kanylbullen.tung.core.MAX_WEIGHT_KG
import io.github.kanylbullen.tung.core.clampTargetSets
import io.github.kanylbullen.tung.core.mostCommonReps
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.EnumMap
import kotlin.math.roundToLong

/**
 * Jefit's account export (My Jefit → Settings → Data Controls → Export Data):
 * one UTF-8 CSV of sections (`### NAME ####`), each one or more tables with
 * their own header row. Written against a real export; the layout is not
 * documented anywhere. What is read:
 *
 * - **SETTING** `mass`: the account's unit (`" kg"` or lbs), which the
 *   exercise logs' `logs` text is in.
 * - **WORKOUT SESSIONS**: one workout each, `starttime`/`endtime` in epoch
 *   seconds. A session with `starttime` 0 (Jefit has them, which would be
 *   1970) takes its exercise logs' times instead.
 * - **EXERCISE LOGS**: one exercise of a session (`belongsession`). Logs with
 *   `belongsession` 0 (old exports) are grouped by `mydate` into one workout
 *   per day, which adopts a session of that day that has no logs of its own.
 * - **EXERCISE SET LOGS**: the sets of a log, weights always in lbs
 *   (`weight_lbs`, converted and rounded to 0.5 kg). A log with set rows uses
 *   them; an older log without any parses `logs`, `"15.0x8,25.0x8"` (weight ×
 *   reps in the account's unit).
 * - **ROUTINES**: routines (packages), their days and the days' exercises,
 *   three kinds of sub-table. Each day with exercises becomes a Tung routine
 *   named "<package> · <day>" (just the day when its package has one day),
 *   exercises in `mysort` order, `setcount` as target sets and the most common
 *   of `targetrep` as target reps. Programs are not imported.
 * - **CUSTOM EXERCISES**: their names, so the review can offer to create them.
 * - Cardio (**CARDIO LOGS**, and exercise logs that **CARDIO EXERCISE LOGS**
 *   points at) is skipped and counted. PROFILE, EXERCISE RECORDS, FAVORITE
 *   EXERCISES and NOTES are not read.
 *
 * Ids are [importId]s over the Jefit user id and the rows' own ids, so a
 * second import of the same (or a newer) export adds nothing.
 */
object JefitImport : ImportAdapter {
    const val SOURCE = "jefit"

    override val source: String = SOURCE

    private const val LBS_TO_KG = 0.45359237

    /** Earlier than 2000-01-01 is not a real workout time (Jefit writes 0 for "unknown"). */
    private const val EARLIEST_SECONDS = 946_684_800L

    private val markers = listOf("WORKOUT SESSIONS", "EXERCISE LOGS").map { Regex("^###\\s+$it\\s+#", RegexOption.MULTILINE) }
    private val setText = Regex("^\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*[xX]\\s*([0-9]+(?:\\.0+)?)\\s*$")

    override fun recognises(text: String): Boolean = markers.all { it.containsMatchIn(text) }

    override fun parse(text: String, zone: ZoneId): ImportBundle = Parser(splitSections(text), zone).bundle()

    private class Parser(private val sections: Map<String, ExportSection>, private val zone: ZoneId) {
        private val skipped = EnumMap<SkipReason, Int>(SkipReason::class.java)
        private val setTypes = LinkedHashMap<String, Int>()
        private var setRowsUsed = 0
        private var setRowsSkipped = 0

        private fun skip(reason: SkipReason, count: Int = 1) {
            if (count > 0) skipped[reason] = (skipped[reason] ?: 0) + count
        }

        private val setting = sections["SETTING"]?.records("mass")?.firstOrNull()
        private val accountInLbs = setting?.get("mass")?.contains("lb", ignoreCase = true) == true

        /** Ids are per Jefit account, so two accounts imported on one phone never collide. */
        private val userId: String = setting?.get("USERID")?.trim()?.takeIf { it.isNotEmpty() }
            ?: sections["EXERCISE LOGS"]?.records("USERID")?.firstOrNull()?.get("USERID")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "0"

        private fun id(kind: String, vararg parts: String) = importId(SOURCE, kind, userId, *parts)

        fun bundle(): ImportBundle {
            skip(SkipReason.STRAY_HEADER, sections.values.sumOf { it.strayHeaders })
            val setRows = sections["EXERCISE SET LOGS"]?.records("exercise_log_id", "set_index").orEmpty()
            val workouts = workouts(setRows)
            val routines = routines()
            skip(SkipReason.CARDIO_LOG, sections["CARDIO LOGS"]?.tables?.sumOf { it.rows.size } ?: 0)
            val custom = sections["CUSTOM EXERCISES"]?.records("name").orEmpty()
                .map { it["name"].trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
            return ImportBundle(
                source = SOURCE,
                workouts = workouts,
                routines = routines,
                customExercises = custom,
                skipped = skipped.toMap(),
                setTypes = setTypes.toMap(),
                setRows = SetRowAccount(read = setRows.size, used = setRowsUsed, skipped = setRowsSkipped),
            )
        }

        /** An exercise log read into an exercise, with when it was logged. */
        private inner class Log(val exercise: ImportedExercise, val seconds: Long?, val date: LocalDate?, val order: Int)

        private fun workouts(setRows: List<CsvRecord>): List<ImportedWorkout> {
            val sessions = sections["WORKOUT SESSIONS"]?.records("_id", "starttime").orEmpty()
            val logs = sections["EXERCISE LOGS"]?.records("_id", "ename", "logs").orEmpty()
            val cardio = sections["CARDIO EXERCISE LOGS"]?.records("el_id").orEmpty().map { it["el_id"].trim() }.toSet()
            val rowsByLog = setRows.groupBy { it["exercise_log_id"].trim() }
            val logIds = logs.map { it["_id"].trim() }.toSet()
            rowsByLog.filterKeys { it !in logIds }.values.sumOf { it.size }.let {
                skip(SkipReason.SET_WITHOUT_LOG, it)
                setRowsSkipped += it
            }

            val sessionIds = sessions.map { it["_id"].trim() }.toSet()
            val bySession = LinkedHashMap<String, MutableList<Log>>()
            val orphans = mutableListOf<Log>()
            logs.forEachIndexed { order, record ->
                val logId = record["_id"].trim()
                val rows = rowsByLog[logId].orEmpty()
                val name = record["ename"].trim()
                when {
                    name.equals("ename", ignoreCase = true) -> {
                        skip(SkipReason.STRAY_HEADER)
                        return@forEachIndexed
                    }
                    logId in cardio -> {
                        skip(SkipReason.CARDIO)
                        setRowsSkipped += rows.size
                        return@forEachIndexed
                    }
                }
                val sets = if (rows.isNotEmpty()) setsFromRows(logId, rows) else setsFromText(logId, record["logs"])
                if (sets.isEmpty() || name.isEmpty()) {
                    skip(SkipReason.LOG_WITHOUT_SETS)
                    return@forEachIndexed
                }
                val log = Log(
                    exercise = ImportedExercise(id("workout_exercise", logId), name, sets),
                    seconds = plausibleSeconds(record["logTime"]),
                    date = date(record["mydate"]),
                    order = order,
                )
                val session = record["belongsession"].trim()
                if (session != "0" && session in sessionIds) bySession.getOrPut(session) { mutableListOf() } += log else orphans += log
            }

            val result = mutableListOf<ImportedWorkout>()
            val logless = mutableListOf<CsvRecord>()
            for (session in sessions) {
                val sessionId = session["_id"].trim()
                val own = bySession[sessionId]
                if (own == null) {
                    logless += session
                    continue
                }
                val start = plausibleSeconds(session["starttime"])
                val workout = workout(id("workout", "session", sessionId), start, plausibleSeconds(session["endtime"]), own)
                if (workout == null) skip(SkipReason.SESSION_WITHOUT_TIME) else result += workout
            }

            // Logs that name no session (old exports): one workout per day, in a session of that day if one has no logs.
            val adopted = HashSet<String>()
            orphans.groupBy { it.date }.forEach { (date, dayLogs) ->
                val session = date?.let { day ->
                    logless.firstOrNull { s ->
                        s["_id"].trim() !in adopted && plausibleSeconds(s["starttime"])?.let { localDate(it) } == day
                    }
                }
                val workout = if (session != null) {
                    adopted += session["_id"].trim()
                    workout(
                        id("workout", "session", session["_id"].trim()),
                        plausibleSeconds(session["starttime"]),
                        plausibleSeconds(session["endtime"]),
                        dayLogs,
                    )
                } else {
                    workout(id("workout", "date", date?.toString() ?: "none"), null, null, dayLogs, preferDate = true)
                }
                if (workout == null) skip(SkipReason.SESSION_WITHOUT_TIME) else result += workout
            }
            skip(SkipReason.SESSION_WITHOUT_EXERCISES, logless.count { it["_id"].trim() !in adopted })
            return result.sortedWith(compareBy<ImportedWorkout> { it.startedAt }.thenBy { it.id })
        }

        /**
         * A workout of [logs] in the order they were logged: from [startSeconds]
         * to [endSeconds] when the session has them, else from the logs' own
         * times, else noon on their date. A day of loose logs ([preferDate])
         * takes noon on its date first: their `logTime` is only the date's
         * midnight where the export was made, which is the day before further
         * west. Null when there is no time at all.
         */
        private fun workout(id: String, startSeconds: Long?, endSeconds: Long?, logs: List<Log>, preferDate: Boolean = false): ImportedWorkout? {
            val ordered = logs.sortedWith(compareBy<Log> { it.seconds ?: Long.MAX_VALUE }.thenBy { it.order })
            val logTimes = logs.mapNotNull { it.seconds }
            val noon = logs.firstNotNullOfOrNull { it.date }?.atTime(LocalTime.NOON)?.atZone(zone)?.toInstant()?.toEpochMilli()
            val startMillis = startSeconds?.let { it * 1000 }
                ?: noon?.takeIf { preferDate }
                ?: logTimes.minOrNull()?.let { it * 1000 }
                ?: noon
                ?: return null
            val endMillis = when {
                endSeconds != null -> endSeconds * 1000
                preferDate && noon != null -> startMillis
                else -> logTimes.maxOrNull()?.let { it * 1000 } ?: startMillis
            }.coerceAtLeast(startMillis)
            return ImportedWorkout(id, startMillis, endMillis, ordered.map { it.exercise })
        }

        private fun setsFromRows(logId: String, rows: List<CsvRecord>): List<ImportedSet> {
            val sets = mutableListOf<ImportedSet>()
            rows.sortedBy { it["set_index"].trim().toIntOrNull() ?: Int.MAX_VALUE }.forEach { row ->
                val index = row["set_index"].trim()
                val reps = row["reps"].trim().toDoubleOrNull()?.toInt() ?: 0
                val lbs = row["weight_lbs"].trim().ifEmpty { "0" }.toDoubleOrNull()
                val kg = lbs?.let { roundToHalf(it * LBS_TO_KG) }
                when {
                    reps <= 0 -> {
                        skip(SkipReason.SET_WITHOUT_REPS)
                        setRowsSkipped++
                    }
                    kg == null || kg < 0.0 || kg > MAX_WEIGHT_KG -> {
                        skip(SkipReason.SET_UNREADABLE)
                        setRowsSkipped++
                    }
                    else -> {
                        sets += ImportedSet(id("set", logId, index), kg, reps)
                        setRowsUsed++
                        val type = row["set_type"].trim()
                        if (type.isNotEmpty() && type != "default") setTypes[type] = (setTypes[type] ?: 0) + 1
                    }
                }
            }
            return sets
        }

        private fun setsFromText(logId: String, text: String): List<ImportedSet> {
            val sets = mutableListOf<ImportedSet>()
            text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, entry ->
                val match = setText.find(entry)
                val weight = match?.groupValues?.get(1)?.toDoubleOrNull()
                val reps = match?.groupValues?.get(2)?.toDoubleOrNull()?.toInt()
                val kg = weight?.let { if (accountInLbs) roundToHalf(it * LBS_TO_KG) else roundToHundredth(it) }
                when {
                    kg == null || reps == null || kg < 0.0 || kg > MAX_WEIGHT_KG -> skip(SkipReason.SET_UNREADABLE)
                    reps <= 0 -> skip(SkipReason.SET_WITHOUT_REPS)
                    else -> sets += ImportedSet(id("set", logId, index.toString()), kg, reps)
                }
            }
            return sets
        }

        private fun routines(): List<ImportedRoutine> {
            val section = sections["ROUTINES"] ?: return emptyList()
            val packages = section.records("_id", "name", "dayaweek").associate { it["_id"].trim() to it["name"].trim() }
            val days = section.records("package", "_id", "name", "dayIndex")
            val exercises = section.records("belongplan", "exercisename", "mysort")
            val daysPerPackage = days.groupingBy { it["package"].trim() }.eachCount()
            val seen = HashSet<List<Any?>>()
            val result = mutableListOf<ImportedRoutine>()
            days.withIndex()
                .sortedWith(
                    compareBy<IndexedValue<CsvRecord>> { packages.keys.indexOf(it.value["package"].trim()).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                        .thenBy { it.value["sort_order"].trim().toIntOrNull() ?: Int.MAX_VALUE }
                        .thenBy { it.index },
                )
                .forEach { (_, day) ->
                    val dayId = day["_id"].trim()
                    val items = exercises.withIndex()
                        .filter { it.value["belongplan"].trim() == dayId && it.value["exercisename"].isNotBlank() }
                        .sortedWith(compareBy<IndexedValue<CsvRecord>> { it.value["mysort"].trim().toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.index })
                        .map { (_, e) ->
                            ImportedRoutineExercise(
                                id = id("routine_exercise", dayId, e["_id"].trim()),
                                name = e["exercisename"].trim(),
                                targetSets = clampTargetSets(e["setcount"].trim().toIntOrNull() ?: 3),
                                targetReps = mostCommonReps(e["targetrep"].split(',').mapNotNull { it.trim().toIntOrNull() }),
                            )
                        }
                    if (items.isEmpty()) {
                        skip(SkipReason.ROUTINE_WITHOUT_EXERCISES)
                        return@forEach
                    }
                    val packageId = day["package"].trim()
                    val name = routineName(packages[packageId].orEmpty(), day["name"].trim(), (daysPerPackage[packageId] ?: 0) > 1)
                    if (!seen.add(listOf(name) + items.map { Triple(it.name, it.targetSets, it.targetReps) })) {
                        skip(SkipReason.DUPLICATE_ROUTINE)
                        return@forEach
                    }
                    result += ImportedRoutine(id("routine", dayId), name, items)
                }
            return result
        }

        private fun routineName(packageName: String, dayName: String, severalDays: Boolean): String = when {
            packageName.isEmpty() && dayName.isEmpty() -> "Jefit"
            packageName.isEmpty() -> dayName
            dayName.isEmpty() || !severalDays -> dayName.ifEmpty { packageName }
            else -> packageName + ROUTINE_NAME_SEPARATOR + dayName
        }

        private fun plausibleSeconds(cell: String): Long? = cell.trim().toLongOrNull()?.takeIf { it >= EARLIEST_SECONDS }

        private fun date(cell: String): LocalDate? = runCatching { LocalDate.parse(cell.trim()) }.getOrNull()

        private fun localDate(seconds: Long): LocalDate = Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate()
    }

    private fun roundToHalf(kg: Double): Double = (kg * 2).roundToLong() / 2.0

    private fun roundToHundredth(kg: Double): Double = (kg * 100).roundToLong() / 100.0
}
