package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.ProgramMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The outcome of [CommandWire.decode]. */
sealed interface WireDecoding {
    data class Decoded(val command: Command) : WireDecoding

    /** The JSON does not describe the command: unknown or missing fields, wrong types. Every problem, not only the first. */
    data class Invalid(val reasons: List<String>) : WireDecoding
}

/**
 * Commands as JSON: what a REST client (`POST /api/v1/commands/{name}`) or an
 * MCP tool call sends for each [CommandNames] name, the JSON Schema of that
 * body, and the JSON a [CommandResult] is answered with. One table here
 * drives all three, so the schema an assistant reads and the decoder that
 * runs can never disagree.
 *
 * Fields are snake_case like the rest of the API (`workout_id`, `weight_kg`,
 * `apply_to_open_sets`); the keys of a result's `ids` are [CommandIds] as
 * they are. Plans are flat: `apply_plan` takes `{"exercises": [...]}` and
 * `save_routine_plan` takes `{"name", "exercises"}`. An exercise is
 * `{"key": "bench_press"}`, `{"id": "…"}`, `{"name": "Bänkpress"}` or
 * `{"extended": "<free-exercise-db id>"}`; a bare string is read as a name.
 *
 * Decoding checks shape only (fields, types, enum keys). Ranges and blank
 * values are [validate]'s, which every [CommandHandler] runs anyway; numbers
 * sent as strings are accepted, because assistants send them.
 */
object CommandWire {

    /** Every wire name, in [CommandNames] order. */
    val names: List<String> get() = CommandNames.all.values.toList()

    /** The JSON Schema (an object schema) of [name]'s arguments, or null for an unknown name. */
    fun schema(name: String): JsonObject? = specs[name]?.let(::schemaOf)

    /** Reads [arguments] as the command [name]. An unknown name is [WireDecoding.Invalid]. */
    fun decode(name: String, arguments: JsonObject): WireDecoding {
        val spec = specs[name] ?: return WireDecoding.Invalid(listOf("there is no command called '$name'"))
        val reasons = mutableListOf<String>()
        val known = spec.fields.map { it.name }.toSet()
        (arguments.keys - known).forEach { reasons += "unknown field '$it' (expected: ${known.joinToString().ifEmpty { "none" }})" }
        for (field in spec.fields) {
            val value = arguments[field.name]
            if (value == null || (value is JsonNull && !field.kind.nullable)) {
                if (field.required) reasons += "${field.name} is required"
                continue
            }
            check(field.kind, value, field.name, reasons)
        }
        if (reasons.isNotEmpty()) return WireDecoding.Invalid(reasons)
        return WireDecoding.Decoded(Reader(arguments).let { spec.build(it) })
    }

    /** A [CommandResult] as JSON: `{"status": "done" | "needs_answers" | "rejected", …}`. */
    fun encode(result: CommandResult): JsonObject = when (result) {
        is CommandResult.Done -> buildJsonObject {
            put("status", "done")
            putJsonObject("ids") { result.ids.forEach { (key, value) -> put(key, value) } }
        }
        is CommandResult.NeedsAnswers -> buildJsonObject {
            put("status", "needs_answers")
            putJsonArray("questions") {
                result.questions.forEach { question ->
                    addJsonObject {
                        put("text", question.text)
                        putJsonArray("suggestions") { question.suggestions.forEach { add(encodeCandidate(it)) } }
                    }
                }
            }
        }
        is CommandResult.Rejected -> buildJsonObject {
            put("status", "rejected")
            putJsonArray("reasons") { result.reasons.forEach { add(it) } }
        }
    }

    /** A suggestion: its id, names, and the `exercise` value that picks it in a command (`ref`). */
    fun encodeCandidate(candidate: ExerciseCandidate): JsonObject = buildJsonObject {
        put("id", candidate.id)
        put("name", candidate.displayName)
        putJsonArray("names") { candidate.names.forEach { add(it) } }
        put("key", candidate.nameKey)
        put("extended", candidate.extendedId)
        putJsonObject("ref") {
            when {
                candidate.nameKey != null -> put("key", candidate.nameKey)
                candidate.extendedId != null -> put("extended", candidate.extendedId)
                else -> put("id", candidate.id)
            }
        }
    }

    // --- the table ---

    private enum class Kind(val nullable: Boolean = false) {
        ID, TEXT, INT, NUMBER, BOOL, NULLABLE_INT(nullable = true), MUSCLE_GROUP, EQUIPMENT, PROGRAM_MODE,
        EXERCISE, PLAN_EXERCISES, ROUTINE_EXERCISES,
    }

    private class Field(val name: String, val kind: Kind, val required: Boolean, val description: String)

    private class Spec(val fields: List<Field>, val build: (Reader) -> Command)

    private fun req(name: String, kind: Kind, description: String) = Field(name, kind, true, description)
    private fun opt(name: String, kind: Kind, description: String) = Field(name, kind, false, description)

    private val workoutId = req("workout_id", Kind.ID, "The workout's id.")
    private val workoutExerciseId = req("workout_exercise_id", Kind.ID, "The id of an exercise inside a workout (not the exercise's own id).")
    private val setId = req("set_id", Kind.ID, "The set's id.")
    private val routineId = req("routine_id", Kind.ID, "The routine's id.")
    private val routineExerciseId = req("routine_exercise_id", Kind.ID, "The id of an exercise inside a routine.")
    private val programId = req("program_id", Kind.ID, "The program's id.")
    private val dayId = req("day_id", Kind.ID, "The program day's id.")
    private val programDayId = opt("program_day_id", Kind.ID, "The program day this workout is started from, if any.")
    private val delta = req("delta", Kind.INT, "Places to move: -1 is up, 1 is down.")
    private val name = req("name", Kind.TEXT, "The name, 1 to 100 characters.")
    private val applyToOpen = opt("apply_to_open_sets", Kind.BOOL, "Also write the value to every un-ticked set of the same exercise. Default false.")

    private val specs: Map<String, Spec> = mapOf(
        "start_workout" to Spec(
            listOf(
                opt("routine_id", Kind.ID, "Start from this routine instead of empty (the same as start_routine)."),
                opt("program_day_id", Kind.ID, "With routine_id: the program day it is started from."),
            ),
        ) { Command.StartWorkout(it.idOrNull("routine_id"), it.idOrNull("program_day_id")) },
        "start_routine" to Spec(listOf(routineId, programDayId)) {
            Command.StartRoutine(it.id("routine_id"), it.idOrNull("program_day_id"))
        },
        "add_exercise" to Spec(listOf(workoutId, req("exercise", Kind.EXERCISE, EXERCISE_DESCRIPTION))) {
            Command.AddExercise(it.id("workout_id"), it.exercise("exercise"))
        },
        "remove_exercise" to Spec(listOf(workoutExerciseId)) { Command.RemoveExercise(it.id("workout_exercise_id")) },
        "add_set" to Spec(listOf(workoutExerciseId)) { Command.AddSet(it.id("workout_exercise_id")) },
        "remove_set" to Spec(listOf(setId)) { Command.RemoveSet(it.id("set_id")) },
        "set_weight" to Spec(listOf(setId, req("weight_kg", Kind.NUMBER, "Weight in kilograms, 0 to 999."), applyToOpen)) {
            Command.SetWeight(it.id("set_id"), it.double("weight_kg"), it.bool("apply_to_open_sets"))
        },
        "set_reps" to Spec(listOf(setId, req("reps", Kind.INT, "Repetitions, 0 to 999."), applyToOpen)) {
            Command.SetReps(it.id("set_id"), it.int("reps"), it.bool("apply_to_open_sets"))
        },
        "tick_set" to Spec(listOf(setId, req("done", Kind.BOOL, "true ticks the set, false un-ticks it."))) {
            Command.TickSet(it.id("set_id"), it.bool("done"))
        },
        "log_set" to Spec(listOf(workoutId, opt("set_id", Kind.ID, "The set to log. Default: the first un-ticked set of the first exercise that has one."))) {
            Command.LogSet(it.id("workout_id"), it.idOrNull("set_id"))
        },
        "select_current" to Spec(
            listOf(
                opt("set_id", Kind.ID, "The set to make current."),
                opt("workout_exercise_id", Kind.ID, "Or: an exercise in the workout, whose first un-ticked set becomes current."),
            ),
        ) { Command.SelectCurrent(it.idOrNull("set_id"), it.idOrNull("workout_exercise_id")) },
        "finish_workout" to Spec(listOf(workoutId)) { Command.FinishWorkout(it.id("workout_id")) },
        "delete_workout" to Spec(listOf(workoutId)) { Command.DeleteWorkout(it.id("workout_id")) },
        "apply_plan" to Spec(listOf(req("exercises", Kind.PLAN_EXERCISES, "The exercises in order, each with its planned sets."))) {
            Command.ApplyPlan(WorkoutPlan(it.planExercises("exercises")))
        },
        "set_rest" to Spec(
            listOf(
                req("exercise_id", Kind.ID, "The exercise's id (catalogue or custom)."),
                opt("seconds", Kind.NULLABLE_INT, "Rest between sets in seconds, 0 to 3600; 0 turns the timer off, null or absent follows the default."),
            ),
        ) { Command.SetRest(it.id("exercise_id"), it.intOrNull("seconds")) },
        "create_exercise" to Spec(
            listOf(
                name,
                req("muscle_group", Kind.MUSCLE_GROUP, "The main muscle group."),
                req("equipment", Kind.EQUIPMENT, "The equipment it needs."),
            ),
        ) { Command.CreateExercise(it.text("name"), MuscleGroup.fromKey(it.text("muscle_group")), Equipment.fromKey(it.text("equipment"))) },
        "save_routine" to Spec(listOf(workoutId, name)) { Command.SaveRoutine(it.id("workout_id"), it.text("name")) },
        "save_routine_plan" to Spec(
            listOf(name, req("exercises", Kind.ROUTINE_EXERCISES, "The routine's exercises in order.")),
        ) { Command.SaveRoutinePlan(RoutinePlan(it.text("name"), it.routineExercises("exercises"))) },
        "create_routine" to Spec(listOf(name)) { Command.CreateRoutine(it.text("name")) },
        "rename_routine" to Spec(listOf(routineId, name)) { Command.RenameRoutine(it.id("routine_id"), it.text("name")) },
        "delete_routine" to Spec(listOf(routineId)) { Command.DeleteRoutine(it.id("routine_id")) },
        "move_routine_exercise" to Spec(listOf(routineExerciseId, delta)) {
            Command.MoveRoutineExercise(it.id("routine_exercise_id"), it.int("delta"))
        },
        "adjust_target_sets" to Spec(listOf(routineExerciseId, req("delta", Kind.INT, "Sets to add (negative removes); the result stays within 1 to 20."))) {
            Command.AdjustTargetSets(it.id("routine_exercise_id"), it.int("delta"))
        },
        "add_routine_exercise" to Spec(
            listOf(
                routineId,
                req("exercise", Kind.EXERCISE, EXERCISE_DESCRIPTION),
                opt("target_sets", Kind.INT, "Sets per session, 1 to 20. Default $DEFAULT_ROUTINE_TARGET_SETS."),
                opt("target_reps", Kind.NULLABLE_INT, "Repetitions aimed at per set, 1 to 999; leave out or null for none."),
            ),
        ) {
            Command.AddRoutineExercise(
                it.id("routine_id"),
                it.exercise("exercise"),
                it.intOrNull("target_sets") ?: DEFAULT_ROUTINE_TARGET_SETS,
                it.intOrNull("target_reps"),
            )
        },
        "remove_routine_exercise" to Spec(listOf(routineExerciseId)) { Command.RemoveRoutineExercise(it.id("routine_exercise_id")) },
        "set_target_reps" to Spec(
            listOf(routineExerciseId, req("target_reps", Kind.NULLABLE_INT, "Repetitions aimed at per set, 1 to 999; null clears the target.")),
        ) { Command.SetTargetReps(it.id("routine_exercise_id"), it.intOrNull("target_reps")) },
        "create_program" to Spec(listOf(name, req("mode", Kind.PROGRAM_MODE, PROGRAM_MODE_DESCRIPTION))) {
            Command.CreateProgram(it.text("name"), ProgramMode.fromKey(it.text("mode")))
        },
        "rename_program" to Spec(listOf(programId, name)) { Command.RenameProgram(it.id("program_id"), it.text("name")) },
        "set_program_mode" to Spec(listOf(programId, req("mode", Kind.PROGRAM_MODE, PROGRAM_MODE_DESCRIPTION))) {
            Command.SetProgramMode(it.id("program_id"), ProgramMode.fromKey(it.text("mode")))
        },
        "activate_program" to Spec(listOf(programId)) { Command.ActivateProgram(it.id("program_id")) },
        "deactivate_program" to Spec(listOf(programId)) { Command.DeactivateProgram(it.id("program_id")) },
        "delete_program" to Spec(listOf(programId)) { Command.DeleteProgram(it.id("program_id")) },
        "add_program_day" to Spec(listOf(programId, req("routine_id", Kind.ID, "The routine this day starts."))) {
            Command.AddProgramDay(it.id("program_id"), it.id("routine_id"))
        },
        "remove_program_day" to Spec(listOf(dayId)) { Command.RemoveProgramDay(it.id("day_id")) },
        "move_program_day" to Spec(listOf(dayId, delta)) { Command.MoveProgramDay(it.id("day_id"), it.int("delta")) },
        "toggle_program_weekday" to Spec(listOf(dayId, req("weekday", Kind.INT, "ISO weekday: 1 Monday … 7 Sunday."))) {
            Command.ToggleProgramWeekday(it.id("day_id"), it.int("weekday"))
        },
    )

    // --- schema ---

    private fun schemaOf(spec: Spec): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { spec.fields.forEach { put(it.name, kindSchema(it.kind, it.description)) } }
        putJsonArray("required") { spec.fields.filter { it.required }.forEach { add(it.name) } }
        put("additionalProperties", false)
    }

    private fun kindSchema(kind: Kind, description: String): JsonObject = buildJsonObject {
        when (kind) {
            Kind.ID, Kind.TEXT -> put("type", "string")
            Kind.INT -> put("type", "integer")
            Kind.NUMBER -> put("type", "number")
            Kind.BOOL -> put("type", "boolean")
            Kind.NULLABLE_INT -> putJsonArray("type") { add("integer"); add("null") }
            Kind.MUSCLE_GROUP -> enumOf(MuscleGroup.entries.map { it.key })
            Kind.EQUIPMENT -> enumOf(Equipment.entries.map { it.key })
            Kind.PROGRAM_MODE -> enumOf(ProgramMode.entries.map { it.key })
            Kind.EXERCISE -> exerciseSchema(this)
            Kind.PLAN_EXERCISES -> {
                put("type", "array")
                put("minItems", 1)
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("exercise") { exerciseSchema(this); put("description", EXERCISE_DESCRIPTION) }
                        putJsonObject("sets") {
                            put("type", "array")
                            put("description", "One entry per set, in order; a number left out comes from last session (or the set before it). Give either sets or set_count.")
                            put("minItems", MIN_PLANNED_SETS)
                            put("maxItems", MAX_PLANNED_SETS)
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("weight_kg") { put("type", "number"); put("description", "Kilograms, 0 to 999.") }
                                    putJsonObject("reps") { put("type", "integer"); put("description", "Repetitions, 0 to 999.") }
                                }
                                put("additionalProperties", false)
                            }
                        }
                        putJsonObject("set_count") {
                            put("type", "integer")
                            put("description", "Instead of sets: this many identical sets of reps × weight_kg (\"five sets of ten at 60 kg\").")
                        }
                        putJsonObject("reps") { put("type", "integer"); put("description", "With set_count: repetitions per set.") }
                        putJsonObject("weight_kg") { put("type", "number"); put("description", "With set_count: kilograms per set.") }
                    }
                    putJsonArray("required") { add("exercise") }
                    put("additionalProperties", false)
                }
            }
            Kind.ROUTINE_EXERCISES -> {
                put("type", "array")
                put("minItems", 1)
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("exercise") { exerciseSchema(this); put("description", EXERCISE_DESCRIPTION) }
                        putJsonObject("target_sets") { put("type", "integer"); put("description", "Sets per session, 1 to 20.") }
                        putJsonObject("target_reps") { put("type", "integer"); put("description", "Repetitions aimed at per set; leave out for none.") }
                    }
                    putJsonArray("required") { add("exercise"); add("target_sets") }
                    put("additionalProperties", false)
                }
            }
        }
        put("description", description)
    }

    private fun JsonObjectBuilder.enumOf(values: List<String>) {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(it) } }
    }

    private fun exerciseSchema(builder: JsonObjectBuilder) = with(builder) {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("key") { put("type", "string"); put("description", "A core catalogue key, e.g. bench_press.") }
            putJsonObject("id") { put("type", "string"); put("description", "An exercise id.") }
            putJsonObject("name") { put("type", "string"); put("description", "A name in any language the catalogue has, e.g. \"Bench press\" or \"Bänkpress\".") }
            putJsonObject("extended") { put("type", "string"); put("description", "An extended-catalogue id, from the exercises tool.") }
        }
        put("minProperties", 1)
        put("maxProperties", 1)
        put("additionalProperties", false)
    }

    // --- decoding ---

    private fun check(kind: Kind, value: JsonElement, at: String, reasons: MutableList<String>) {
        when (kind) {
            Kind.ID, Kind.TEXT -> if (value.string() == null) reasons += "$at must be a string"
            Kind.INT -> if (value.int() == null) reasons += "$at must be a whole number"
            Kind.NUMBER -> if (value.double() == null) reasons += "$at must be a number"
            Kind.BOOL -> if (value.bool() == null) reasons += "$at must be true or false"
            Kind.NULLABLE_INT -> if (value !is JsonNull && value.int() == null) reasons += "$at must be a whole number or null"
            Kind.MUSCLE_GROUP -> enumCheck(value, MuscleGroup.entries.map { it.key }, at, reasons)
            Kind.EQUIPMENT -> enumCheck(value, Equipment.entries.map { it.key }, at, reasons)
            Kind.PROGRAM_MODE -> enumCheck(value, ProgramMode.entries.map { it.key }, at, reasons)
            Kind.EXERCISE -> if (exerciseRef(value) == null) {
                reasons += "$at must be an object with exactly one of key, id, name or extended (a string), or a name as a string"
            }
            Kind.PLAN_EXERCISES -> {
                val items = value as? JsonArray ?: return run { reasons += "$at must be an array" }
                items.forEachIndexed { i, item -> checkPlanExercise(item, "$at[$i]", reasons) }
            }
            Kind.ROUTINE_EXERCISES -> {
                val items = value as? JsonArray ?: return run { reasons += "$at must be an array" }
                items.forEachIndexed { i, item -> checkRoutineExercise(item, "$at[$i]", reasons) }
            }
        }
    }

    private fun enumCheck(value: JsonElement, keys: List<String>, at: String, reasons: MutableList<String>) {
        if (value.string() !in keys) reasons += "$at must be one of ${keys.joinToString()}"
    }

    private fun checkPlanExercise(item: JsonElement, at: String, reasons: MutableList<String>) {
        val obj = item as? JsonObject ?: return run { reasons += "$at must be an object" }
        val known = setOf("exercise", "sets", "set_count", "reps", "weight_kg")
        (obj.keys - known).forEach { reasons += "$at: unknown field '$it' (expected: ${known.joinToString()})" }
        obj["exercise"]?.let { check(Kind.EXERCISE, it, "$at.exercise", reasons) } ?: run { reasons += "$at.exercise is required" }
        val sets = obj["sets"]?.takeUnless { it is JsonNull }
        val count = obj["set_count"]?.takeUnless { it is JsonNull }
        when {
            sets != null && count != null -> reasons += "$at: give either sets or set_count, not both"
            sets != null -> {
                if (obj["reps"] != null || obj["weight_kg"] != null) reasons += "$at: reps and weight_kg go inside each set when sets is given"
                val list = sets as? JsonArray ?: return run { reasons += "$at.sets must be an array" }
                list.forEachIndexed { s, set ->
                    val setAt = "$at.sets[$s]"
                    val setObj = set as? JsonObject ?: return@forEachIndexed run { reasons += "$setAt must be an object" }
                    (setObj.keys - setOf("weight_kg", "reps")).forEach { reasons += "$setAt: unknown field '$it' (expected: weight_kg, reps)" }
                    setObj["weight_kg"]?.takeUnless { it is JsonNull }?.let { if (it.double() == null) reasons += "$setAt.weight_kg must be a number" }
                    setObj["reps"]?.takeUnless { it is JsonNull }?.let { if (it.int() == null) reasons += "$setAt.reps must be a whole number" }
                }
            }
            count != null -> {
                if (count.int() == null) reasons += "$at.set_count must be a whole number"
                obj["weight_kg"]?.takeUnless { it is JsonNull }?.let { if (it.double() == null) reasons += "$at.weight_kg must be a number" }
                obj["reps"]?.takeUnless { it is JsonNull }?.let { if (it.int() == null) reasons += "$at.reps must be a whole number" }
            }
            else -> reasons += "$at needs sets or set_count"
        }
    }

    private fun checkRoutineExercise(item: JsonElement, at: String, reasons: MutableList<String>) {
        val obj = item as? JsonObject ?: return run { reasons += "$at must be an object" }
        val known = setOf("exercise", "target_sets", "target_reps")
        (obj.keys - known).forEach { reasons += "$at: unknown field '$it' (expected: ${known.joinToString()})" }
        obj["exercise"]?.let { check(Kind.EXERCISE, it, "$at.exercise", reasons) } ?: run { reasons += "$at.exercise is required" }
        val target = obj["target_sets"]?.takeUnless { it is JsonNull }
        if (target == null) reasons += "$at.target_sets is required" else if (target.int() == null) reasons += "$at.target_sets must be a whole number"
        obj["target_reps"]?.takeUnless { it is JsonNull }?.let { if (it.int() == null) reasons += "$at.target_reps must be a whole number" }
    }

    /** Reads fields [decode] has already checked. */
    private class Reader(private val args: JsonObject) {
        fun id(name: String): String = text(name)
        fun idOrNull(name: String): String? = args[name]?.string()
        fun text(name: String): String = requireNotNull(args[name]?.string())
        fun int(name: String): Int = requireNotNull(args[name]?.int())
        fun intOrNull(name: String): Int? = args[name]?.int()
        fun double(name: String): Double = requireNotNull(args[name]?.double())
        fun bool(name: String): Boolean = args[name]?.takeUnless { it is JsonNull }?.bool() ?: false
        fun exercise(name: String): ExerciseRef = requireNotNull(args[name]?.let(::exerciseRef))

        fun planExercises(name: String): List<PlannedExercise> = (args[name] as JsonArray).map { item ->
            val obj = item as JsonObject
            val ref = requireNotNull(exerciseRef(obj.getValue("exercise")))
            val sets = obj["sets"]?.takeUnless { it is JsonNull } as? JsonArray
            if (sets != null) {
                PlannedExercise(
                    ref,
                    sets.map { set ->
                        val setObj = set as JsonObject
                        PlannedSet(weightKg = setObj["weight_kg"]?.double(), reps = setObj["reps"]?.int())
                    },
                )
            } else {
                PlannedExercise.uniform(ref, requireNotNull(obj["set_count"]?.int()), reps = obj["reps"]?.int(), weightKg = obj["weight_kg"]?.double())
            }
        }

        fun routineExercises(name: String): List<RoutinePlanExercise> = (args[name] as JsonArray).map { item ->
            val obj = item as JsonObject
            RoutinePlanExercise(
                exercise = requireNotNull(exerciseRef(obj.getValue("exercise"))),
                targetSets = requireNotNull(obj["target_sets"]?.int()),
                targetReps = obj["target_reps"]?.int(),
            )
        }
    }

    private fun exerciseRef(value: JsonElement): ExerciseRef? {
        value.string()?.let { return ExerciseRef.ByName(it) }
        val obj = value as? JsonObject ?: return null
        if (obj.size != 1) return null
        val (key, raw) = obj.entries.single()
        val text = raw.string() ?: return null
        return when (key) {
            "key" -> ExerciseRef.ByKey(text)
            "id" -> ExerciseRef.ById(text)
            "name" -> ExerciseRef.ByName(text)
            "extended" -> ExerciseRef.Extended(text)
            else -> null
        }
    }

    private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.double(): Double? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun JsonElement.int(): Int? {
        val number = double() ?: return null
        if (number != Math.floor(number) || number > Int.MAX_VALUE || number < Int.MIN_VALUE) return null
        return number.toInt()
    }

    private fun JsonElement.bool(): Boolean? {
        val primitive = this as? JsonPrimitive ?: return null
        return when (primitive.content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}

private const val EXERCISE_DESCRIPTION =
    "The exercise: {\"key\": \"bench_press\"} (a core catalogue key), {\"id\": \"…\"}, {\"name\": \"Bench press\"} " +
        "(any catalogue language; only an exact name resolves, anything else comes back as a question with suggestions) " +
        "or {\"extended\": \"…\"} (an extended-catalogue id)."

private const val PROGRAM_MODE_DESCRIPTION = "fixed: each day on chosen weekdays; rotating: day 1, 2, 3 in order whatever the date."
