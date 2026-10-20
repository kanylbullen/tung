package io.github.kanylbullen.tung.server.mcp

import io.github.kanylbullen.tung.core.command.CommandWire
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.RefResolution
import io.github.kanylbullen.tung.core.command.resolveRef
import io.github.kanylbullen.tung.core.currentSet
import io.github.kanylbullen.tung.core.extendedExerciseId
import io.github.kanylbullen.tung.core.maskToWeekdays
import io.github.kanylbullen.tung.core.matchesSearch
import io.github.kanylbullen.tung.server.Db
import io.github.kanylbullen.tung.server.Exercises
import io.github.kanylbullen.tung.server.RoutineStore
import io.github.kanylbullen.tung.server.WorkoutDto
import io.github.kanylbullen.tung.server.WorkoutStore
import io.github.kanylbullen.tung.server.Workouts
import io.github.kanylbullen.tung.server.command.ServerCatalogue
import io.github.kanylbullen.tung.server.command.ServerExerciseResolver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll

/**
 * The MCP read tools' answers: the user's data shaped for an assistant, with
 * exercise names next to every id, weights in kg, and only what helps
 * (history has no un-ticked sets, because a finished workout has none).
 * Reads only; every change goes through the command handler.
 */
class McpReads(
    private val db: Db,
    private val catalogue: ServerCatalogue,
    private val workouts: WorkoutStore,
    private val routines: RoutineStore,
) {

    /** Finished workouts, newest first, [limit] at a time; `next_before` pages back. */
    suspend fun history(userId: String, limit: Int, before: Long?): JsonObject {
        val page = workouts.page(userId, limit, before, finishedOnly = true)
        val names = exerciseNames(userId, page.workouts.flatMap { w -> w.exercises.map { it.exerciseId } })
        return buildJsonObject {
            putJsonArray("workouts") {
                page.workouts.forEach { workout ->
                    addJsonObject {
                        put("id", workout.id)
                        put("started_at", workout.startedAt)
                        put("ended_at", workout.endedAt)
                        put("routine_id", workout.routineId)
                        put("program_day_id", workout.programDayId)
                        put("started_by", workout.startedBy)
                        workout.avgHeartRate?.let { put("avg_heart_rate", it) }
                        workout.maxHeartRate?.let { put("max_heart_rate", it) }
                        workout.activeCalories?.let { put("active_calories", it) }
                        workout.note?.let { put("note", it) }
                        putJsonArray("exercises") {
                            workout.exercises.forEach { exercise ->
                                addJsonObject {
                                    exerciseFields(exercise.exerciseId, names)
                                    putJsonArray("sets") {
                                        exercise.sets.forEach { set ->
                                            addJsonObject {
                                                put("weight_kg", set.weightKg)
                                                put("reps", set.reps)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            put("next_before", page.nextBefore)
        }
    }

    /** The previous finished session of one exercise, or the question when the name does not resolve. */
    suspend fun lastTime(userId: String, ref: ExerciseRef): JsonObject = db.suspendingTx {
        val resolver = ServerExerciseResolver(userId, catalogue)
        when (val resolution = resolveRef(ref, resolver)) {
            is RefResolution.Unresolved -> buildJsonObject {
                put("status", "needs_answers")
                putJsonArray("questions") {
                    addJsonObject {
                        put("text", resolution.question.text)
                        putJsonArray("suggestions") { resolution.question.suggestions.forEach { add(CommandWire.encodeCandidate(it)) } }
                    }
                }
            }
            is RefResolution.Resolved -> {
                val exercise = resolution.exercise
                val last = with(workouts) { lastSessionIn(userId, exercise.id, null) }
                buildJsonObject {
                    putJsonObject("exercise") {
                        put("id", exercise.id)
                        put("name", exercise.displayName)
                        put("key", exercise.nameKey)
                    }
                    put("workout_id", last.workoutId)
                    put("started_at", last.startedAt)
                    putJsonArray("sets") {
                        last.sets.forEach { set ->
                            addJsonObject {
                                put("weight_kg", set.weightKg)
                                put("reps", set.reps)
                            }
                        }
                    }
                }
            }
        }
    }

    /** The workout in progress with every set's id and state, and which set "log_set" would log; `workout: null` when none. */
    suspend fun currentWorkout(userId: String): JsonObject {
        val workout: WorkoutDto? = db.tx {
            val id = Workouts.selectAll()
                .where { (Workouts.userId eq userId) and Workouts.endedAt.isNull() and Workouts.deletedAt.isNull() }
                .orderBy(Workouts.startedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(Workouts.id)
            id?.let { with(workouts) { liveWorkout(userId, it) } }
        }
        if (workout == null) return buildJsonObject { put("workout", null as String?) }
        val names = exerciseNames(userId, workout.exercises.map { it.exerciseId })
        val current = currentSet(workout.exercises.map { e -> e.sets.map { it.completed } })
            ?.let { workout.exercises[it.exercise].sets[it.set].id }
        return buildJsonObject {
            putJsonObject("workout") {
                put("id", workout.id)
                put("started_at", workout.startedAt)
                put("routine_id", workout.routineId)
                put("program_day_id", workout.programDayId)
                put("started_by", workout.startedBy)
                put("current_set_id", current)
                putJsonArray("exercises") {
                    workout.exercises.forEach { exercise ->
                        addJsonObject {
                            put("workout_exercise_id", exercise.id)
                            exerciseFields(exercise.exerciseId, names)
                            putJsonArray("sets") {
                                exercise.sets.forEach { set ->
                                    addJsonObject {
                                        put("set_id", set.id)
                                        put("weight_kg", set.weightKg)
                                        put("reps", set.reps)
                                        put("done", set.completed)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun routines(userId: String): JsonObject {
        val list = routines.routines(userId)
        val names = exerciseNames(userId, list.flatMap { r -> r.exercises.map { it.exerciseId } })
        return buildJsonObject {
            putJsonArray("routines") {
                list.forEach { routine ->
                    addJsonObject {
                        put("id", routine.id)
                        put("name", routine.name)
                        put("last_used_at", routine.lastUsedAt)
                        putJsonArray("exercises") {
                            routine.exercises.forEach { exercise ->
                                addJsonObject {
                                    put("routine_exercise_id", exercise.id)
                                    exerciseFields(exercise.exerciseId, names)
                                    put("target_sets", exercise.targetSets)
                                    put("target_reps", exercise.targetReps)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun programs(userId: String): JsonObject {
        val list = routines.programs(userId)
        val routineNames = routines.routines(userId).associate { it.id to it.name }
        val lastDays = db.tx { list.associate { it.id to lastCompletedDayId(userId, it.days.map { d -> d.id }) } }
        return buildJsonObject {
            putJsonArray("programs") {
                list.forEach { program ->
                    addJsonObject {
                        put("id", program.id)
                        put("name", program.name)
                        put("mode", program.mode)
                        put("active", program.isActive)
                        put("last_completed_day_id", lastDays[program.id])
                        putJsonArray("days") {
                            program.days.forEach { day ->
                                addJsonObject {
                                    put("day_id", day.id)
                                    put("position", day.position)
                                    put("routine_id", day.routineId)
                                    put("routine_name", routineNames[day.routineId])
                                    putJsonArray("weekdays") { maskToWeekdays(day.weekdays).sorted().forEach { add(it) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** The exercises the user has (catalogue and own), and from two characters of [query] the extended catalogue's matches too. */
    suspend fun exercises(userId: String, query: String?): JsonObject {
        val q = query?.trim().orEmpty()
        val resolver = ServerExerciseResolver(userId, catalogue)
        val rows = db.tx { resolver.visibleRows().map { it to resolver.names(it) } }
            .filter { (_, names) -> q.isEmpty() || names.any { matchesSearch(it, q) } }
            .sortedBy { (row, _) -> row[Exercises.name].lowercase() }
        val rowIds = rows.mapTo(HashSet()) { (row, _) -> row[Exercises.id] }
        val extended = if (q.length < 2) {
            emptyList()
        } else {
            catalogue.extended.filter { extendedExerciseId(it.id) !in rowIds && matchesSearch(it.displayName, q) }.take(MAX_EXTENDED)
        }
        return buildJsonObject {
            putJsonArray("exercises") {
                rows.take(MAX_EXERCISES).forEach { (row, names) ->
                    addJsonObject {
                        put("id", row[Exercises.id])
                        put("key", row[Exercises.nameKey])
                        put("name", row[Exercises.name])
                        putJsonArray("names") { names.forEach { add(it) } }
                        put("muscle_group", row[Exercises.muscleGroup])
                        put("equipment", row[Exercises.equipment])
                        put("custom", row[Exercises.isCustom])
                        put("rest_seconds", row[Exercises.restSeconds])
                    }
                }
            }
            putJsonArray("extended") {
                extended.forEach { entry ->
                    addJsonObject {
                        put("extended", entry.id)
                        put("name", entry.displayName)
                        put("muscle_group", entry.muscleGroup)
                        put("equipment", entry.equipment)
                    }
                }
            }
            if (rows.size > MAX_EXERCISES) put("truncated", true)
        }
    }

    /** The core catalogue for the `tung://catalog/core` resource: key, names per language, muscle group, equipment. */
    fun coreCatalogue(): JsonObject = buildJsonObject {
        putJsonArray("exercises") {
            catalogue.core.forEach { entry ->
                addJsonObject {
                    put("key", entry.key)
                    putJsonObject("name") { entry.name.forEach { (lang, name) -> put(lang, name) } }
                    put("muscle_group", entry.muscleGroup)
                    put("equipment", entry.equipment)
                }
            }
        }
    }

    private class ExerciseName(val name: String, val key: String?)

    /** Name and key of every id, deleted own exercises included (history can name them). */
    private suspend fun exerciseNames(userId: String, ids: Collection<String>): Map<String, ExerciseName> {
        if (ids.isEmpty()) return emptyMap()
        return db.tx {
            Exercises.selectAll()
                .where { (Exercises.id inList ids.toSet()) and (Exercises.userId.isNull() or (Exercises.userId eq userId)) }
                .sortedBy { it[Exercises.userId] != null } // own rows last, so they win below
                .associate { it[Exercises.id] to ExerciseName(it[Exercises.name], it[Exercises.nameKey]) }
        }
    }

    private fun JsonObjectBuilder.exerciseFields(exerciseId: String, names: Map<String, ExerciseName>) {
        put("exercise_id", exerciseId)
        put("name", names[exerciseId]?.name)
        names[exerciseId]?.key?.let { put("key", it) }
    }

    /** The day of the program's latest finished workout, which a rotating program follows. */
    private fun Transaction.lastCompletedDayId(userId: String, dayIds: List<String>): String? {
        if (dayIds.isEmpty()) return null
        return Workouts.selectAll()
            .where {
                (Workouts.userId eq userId) and Workouts.deletedAt.isNull() and Workouts.endedAt.isNotNull() and
                    (Workouts.programDayId inList dayIds)
            }
            .orderBy(Workouts.startedAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(Workouts.programDayId)
    }

    private companion object {
        const val MAX_EXERCISES = 200
        const val MAX_EXTENDED = 25
    }
}
