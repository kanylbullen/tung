@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.kanylbullen.tung.core.importing

import io.github.kanylbullen.tung.core.MAX_REPS
import io.github.kanylbullen.tung.core.MAX_WEIGHT_KG
import io.github.kanylbullen.tung.core.MAX_WORKOUT_DURATION_MILLIS
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.roundToLong

/**
 * Strong (strong.app) CSV export: one UTF-8 CSV, one row per set, comma- or
 * semicolon-separated. Two header variants exist in the wild:
 *
 * - Older: `Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE`
 *   (`Duration` like `1h 5m` or `45m`; `Weight` in the account's unit, which
 *   the file does not state).
 * - Newer: `Workout #,Date,Workout Name,Duration (sec),Exercise Name,Set Order,Weight (kg),Reps,Distance (meters),Seconds,Notes,Workout Notes,RPE`
 *   (the unit is in the column name: `Weight (kg)` or `Weight (lbs)`).
 *
 * Recognised by the header row (never by line position). Rows sharing
 * `Date` + `Workout Name` form one workout, with exercises in order of first
 * appearance and sets in file order (Strong writes them in the order they
 * were done; `W`, `D` and `F` are not numbers, so `Set Order` cannot sort
 * them). `Workout #` is not used: it is a running number that changes when
 * an earlier workout is deleted, and the ids must survive a later export.
 * `Set Order` values `W`, `D`, `F` are imported as ordinary sets and counted
 * under `warm-up`, `drop`, `failure`; non-set markers such as `Rest Timer`
 * are skipped and counted. Rows with `Distance` or `Seconds` and no reps are
 * skipped as [SkipReason.CARDIO]. Missing or unparseable workout durations
 * fall back to 1 hour.
 *
 * Strong exports carry neither routines nor a custom-exercise list, so
 * [ImportBundle.routines] and [ImportBundle.customExercises] are empty.
 *
 * TODO: For the older format with no unit in the header, the import review
 * screen must ask the user whether weights are in kg or lbs before applying.
 */
object StrongImport : ImportAdapter {
    const val SOURCE = "strong"

    override val source: String = SOURCE

    private const val LBS_TO_KG = 0.45359237
    private const val DEFAULT_DURATION_MILLIS = 3_600_000L

    private val requiredCommon = listOf("Date", "Workout Name", "Exercise Name", "Set Order", "Reps")
    private val durationPart = Regex("([0-9]+)\\s*([hms])", RegexOption.IGNORE_CASE)

    override fun recognises(text: String): Boolean {
        val cleaned = text.removePrefix("\uFEFF")
        val firstLine = cleaned.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        val delimiter = detectDelimiter(firstLine)
        val header = csvRecords(firstLine, delimiter).firstOrNull()?.map { it.trim() } ?: return false
        return isStrongHeader(header)
    }

    override fun parse(text: String, zone: TimeZone): ImportBundle =
        parse(text, zone, defaultWeightInLbs = false)

    /**
     * Parses [text] in [zone]. For the older Strong CSV format whose `Weight`
     * column does not state a unit, [defaultWeightInLbs] controls whether
     * weights are read as lbs (default `false`, assuming kg).
     *
     * TODO: The import review screen must ask the user for the weight unit
     * when importing an older Strong CSV export without a unit in the header.
     */
    fun parse(text: String, zone: TimeZone, defaultWeightInLbs: Boolean): ImportBundle {
        val cleaned = text.removePrefix("\uFEFF")
        val firstLine = cleaned.lineSequence().firstOrNull { it.isNotBlank() }
            ?: return emptyBundle()
        val delimiter = detectDelimiter(firstLine)
        val records = csvRecords(cleaned, delimiter)
        val headerIndex = records.indexOfFirst { row -> row.any { it.isNotBlank() } }
        if (headerIndex < 0) return emptyBundle()
        val header = records[headerIndex].map { it.trim() }
        if (!isStrongHeader(header)) return emptyBundle()

        var strayHeaders = 0
        val dataRows = mutableListOf<List<String>>()
        for (i in (headerIndex + 1) until records.size) {
            val row = records[i]
            if (row.all { it.isBlank() }) continue
            if (row.map { it.trim() } == header) {
                strayHeaders++
                continue
            }
            dataRows += row
        }
        val table = CsvTable(header, dataRows)
        return Parser(table, zone, defaultWeightInLbs, strayHeaders).bundle()
    }

    private fun emptyBundle() = ImportBundle(
        source = SOURCE,
        workouts = emptyList(),
        routines = emptyList(),
        customExercises = emptySet(),
        skipped = emptyMap(),
        setTypes = emptyMap(),
        setRows = SetRowAccount(),
    )

    private fun isStrongHeader(header: List<String>): Boolean {
        if (!requiredCommon.all { it in header }) return false
        val isOlder = "Duration" in header && "Weight" in header
        val isNewer = "Duration (sec)" in header && ("Weight (kg)" in header || "Weight (lbs)" in header)
        return isOlder || isNewer
    }

    private fun detectDelimiter(line: String): Char {
        var commas = 0
        var semicolons = 0
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') i++ else quoted = false
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    ',' -> commas++
                    ';' -> semicolons++
                }
            }
            i++
        }
        return if (semicolons > commas) ';' else ','
    }

    private data class WorkoutKey(val date: String, val name: String) {
        fun idParts(): Array<String> = arrayOf(date, name)
    }

    private data class ParsedSet(
        val weightKg: Double,
        val reps: Int,
        val setType: String?,
    )

    private class Parser(
        private val table: CsvTable,
        private val zone: TimeZone,
        defaultWeightInLbs: Boolean,
        strayHeaders: Int,
    ) {
        private val skipped = LinkedHashMap<SkipReason, Int>()
        private val setTypes = LinkedHashMap<String, Int>()
        private var setRowsUsed = 0
        private var setRowsSkipped = 0

        private val durationColumn = if (table.has("Duration (sec)")) "Duration (sec)" else "Duration"
        private val durationInSeconds = durationColumn == "Duration (sec)"
        private val distanceColumn = if (table.has("Distance (meters)")) "Distance (meters)" else "Distance"
        private val weightColumn: String
        private val weightInLbs: Boolean

        init {
            if (strayHeaders > 0) skip(SkipReason.STRAY_HEADER, strayHeaders)
            when {
                table.has("Weight (kg)") -> {
                    weightColumn = "Weight (kg)"
                    weightInLbs = false
                }
                table.has("Weight (lbs)") -> {
                    weightColumn = "Weight (lbs)"
                    weightInLbs = true
                }
                else -> {
                    weightColumn = "Weight"
                    weightInLbs = defaultWeightInLbs
                }
            }
        }

        private fun skip(reason: SkipReason, count: Int = 1) {
            if (count > 0) skipped[reason] = (skipped[reason] ?: 0) + count
        }

        fun bundle(): ImportBundle {
            val allRecords = table.records()
            val byWorkout = LinkedHashMap<WorkoutKey, MutableList<IndexedValue<CsvRecord>>>()
            allRecords.forEachIndexed { index, record ->
                val key = WorkoutKey(
                    date = record["Date"].trim(),
                    name = record["Workout Name"].trim(),
                )
                byWorkout.getOrPut(key) { mutableListOf() } += IndexedValue(index, record)
            }

            val workouts = mutableListOf<ImportedWorkout>()
            for ((key, rows) in byWorkout) {
                val startMillis = parseDateMillis(key.date)
                if (startMillis == null) {
                    skip(SkipReason.SESSION_WITHOUT_TIME)
                    setRowsSkipped += rows.size
                    continue
                }
                // Fall back to 1 h when duration is missing or unparseable.
                val durationMillis = rows.firstNotNullOfOrNull { parseDurationMillis(it.value[durationColumn]) }
                    ?: DEFAULT_DURATION_MILLIS
                val endMillis = startMillis + durationMillis

                val byExercise = LinkedHashMap<String, MutableList<IndexedValue<CsvRecord>>>()
                for (row in rows) {
                    val exerciseName = row.value["Exercise Name"].trim()
                    byExercise.getOrPut(exerciseName) { mutableListOf() } += row
                }

                val keyParts = key.idParts()
                val exercises = mutableListOf<ImportedExercise>()
                for ((exerciseName, exerciseRows) in byExercise) {
                    if (exerciseName.isEmpty()) {
                        skip(SkipReason.SET_UNREADABLE, exerciseRows.size)
                        setRowsSkipped += exerciseRows.size
                        continue
                    }
                    val parsedSets = parseExerciseSets(exerciseRows)
                    if (parsedSets.isEmpty()) continue

                    // The name is unique within a workout (rows are grouped by it), so it
                    // identifies the exercise without a position that shifts when an earlier
                    // exercise has no usable sets.
                    val sets = parsedSets.mapIndexed { setIndex, set ->
                        set.setType?.let { type -> setTypes[type] = (setTypes[type] ?: 0) + 1 }
                        ImportedSet(
                            id = importId(SOURCE, "set", *keyParts, exerciseName, setIndex.toString()),
                            weightKg = set.weightKg,
                            reps = set.reps,
                        )
                    }
                    setRowsUsed += sets.size
                    exercises += ImportedExercise(
                        id = importId(SOURCE, "workout_exercise", *keyParts, exerciseName),
                        name = exerciseName,
                        sets = sets,
                    )
                }

                if (exercises.isEmpty()) {
                    skip(SkipReason.SESSION_WITHOUT_EXERCISES)
                } else {
                    workouts += ImportedWorkout(
                        id = importId(SOURCE, "workout", *keyParts),
                        startedAt = startMillis,
                        endedAt = endMillis,
                        exercises = exercises,
                    )
                }
            }

            return ImportBundle(
                source = SOURCE,
                workouts = workouts.sortedWith(compareBy<ImportedWorkout> { it.startedAt }.thenBy { it.id }),
                routines = emptyList(),
                customExercises = emptySet(),
                skipped = SkipReason.entries.mapNotNull { reason -> skipped[reason]?.let { reason to it } }.toMap(),
                setTypes = setTypes.toMap(),
                setRows = SetRowAccount(read = allRecords.size, used = setRowsUsed, skipped = setRowsSkipped),
            )
        }

        private fun parseExerciseSets(rows: List<IndexedValue<CsvRecord>>): List<ParsedSet> {
            val sets = mutableListOf<ParsedSet>()
            for ((_, row) in rows) {
                val setOrder = row["Set Order"].trim()
                val setType = when {
                    setOrder.equals("W", ignoreCase = true) -> "warm-up"
                    setOrder.equals("D", ignoreCase = true) -> "drop"
                    setOrder.equals("F", ignoreCase = true) -> "failure"
                    setOrder.toIntOrNull() != null -> null
                    else -> {
                        // Non-set marker such as "Rest Timer".
                        skip(SkipReason.SET_UNREADABLE)
                        setRowsSkipped++
                        continue
                    }
                }

                val repsRaw = row["Reps"].trim()
                // NaN, infinities and counts past MAX_REPS are unreadable, never clamped.
                val reps = repsRaw.replace(',', '.').toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_REPS }
                    ?.toInt()
                val distance = row[distanceColumn].trim().replace(',', '.').toDoubleOrNull() ?: 0.0
                val seconds = row["Seconds"].trim().replace(',', '.').toDoubleOrNull() ?: 0.0
                if ((reps == null || reps <= 0) && (distance > 0.0 || seconds > 0.0)) {
                    skip(SkipReason.CARDIO)
                    setRowsSkipped++
                    continue
                }

                if (reps == null) {
                    skip(if (repsRaw.isEmpty()) SkipReason.SET_WITHOUT_REPS else SkipReason.SET_UNREADABLE)
                    setRowsSkipped++
                    continue
                }
                if (reps <= 0) {
                    skip(SkipReason.SET_WITHOUT_REPS)
                    setRowsSkipped++
                    continue
                }

                val weightRaw = row[weightColumn].trim().replace(',', '.')
                // Checked before rounding: NaN cannot be rounded, and -0.1 would round to 0.
                val weight = if (weightRaw.isEmpty()) 0.0 else weightRaw.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                val kg = weight?.let { if (weightInLbs) roundToHalf(it * LBS_TO_KG) else roundToHundredth(it) }
                if (kg == null || kg < 0.0 || kg > MAX_WEIGHT_KG) {
                    skip(SkipReason.SET_UNREADABLE)
                    setRowsSkipped++
                    continue
                }

                sets += ParsedSet(weightKg = kg, reps = reps, setType = setType)
            }
            return sets
        }

        private fun parseDateMillis(raw: String): Long? {
            if (raw.isEmpty()) return null
            val iso = raw.replace(' ', 'T')
            return runCatching { LocalDateTime.parse(iso).toInstant(zone).toEpochMilliseconds() }.getOrNull()
        }

        /** The duration, or null when missing, not positive, unreadable or over a day (the caller falls back). */
        private fun parseDurationMillis(raw: String): Long? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('-')) return null
            val maxSeconds = MAX_WORKOUT_DURATION_MILLIS / 1000L
            val totalSeconds: Long = if (durationInSeconds) {
                val secs = trimmed.replace(',', '.').toDoubleOrNull() ?: return null
                if (!secs.isFinite() || secs > maxSeconds) return null
                secs.toLong()
            } else {
                val matches = durationPart.findAll(trimmed).toList()
                if (matches.isEmpty()) return null
                var sum = 0L
                for (match in matches) {
                    val value = match.groupValues[1].toLongOrNull()?.takeIf { it <= maxSeconds } ?: return null
                    sum += when (match.groupValues[2].lowercase()) {
                        "h" -> value * 3600L
                        "m" -> value * 60L
                        else -> value
                    }
                    if (sum > maxSeconds) return null
                }
                sum
            }
            return if (totalSeconds > 0L) totalSeconds * 1000L else null
        }
    }

    private fun roundToHalf(kg: Double): Double = (kg * 2).roundToLong() / 2.0

    private fun roundToHundredth(kg: Double): Double = (kg * 100).roundToLong() / 100.0
}
