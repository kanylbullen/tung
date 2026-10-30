package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.newId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

/**
 * Exercises: the shared catalogue (user_id NULL, seeded from core.json) plus
 * each user's own rows. A user's row with a catalogue id is their copy of that
 * catalogue exercise and replaces it in what they see.
 */
class ExerciseStore(private val db: Db) {

    /**
     * Upserts the catalogue from core.json with the same deterministic ids as
     * the app (catalogExerciseId(key)) and updated_at 0. Run at every start;
     * running it twice changes nothing.
     */
    fun seedCatalogue(database: Database, entries: List<CoreExercise>) = transaction(database) {
        for (entry in entries) {
            Exercises.upsert(
                Exercises.userId, Exercises.id,
                onUpdateExclude = listOf(Exercises.userId, Exercises.id, Exercises.isCustom, Exercises.restSeconds, Exercises.updatedAt, Exercises.deletedAt),
            ) {
                it[userId] = null
                it[id] = catalogExerciseId(entry.key)
                it[nameKey] = entry.key
                it[name] = entry.name["en"] ?: entry.key
                it[muscleGroup] = entry.muscleGroup
                it[equipment] = entry.equipment
                it[isCustom] = false
                it[restSeconds] = null
                it[updatedAt] = 0L
                it[deletedAt] = null
            }
        }
    }

    /** The catalogue plus the user's own exercises, by name. */
    suspend fun list(userId: String): List<ExerciseDto> = db.tx {
        val own = ownRows(userId).associateBy { it[Exercises.id] }
        val catalogue = Exercises.selectAll().where { Exercises.userId.isNull() }.filter { it[Exercises.id] !in own }
        (catalogue + own.values).map { it.toDto() }.sortedBy { it.name.lowercase() }
    }

    /** The user's own rows only (custom exercises and their catalogue copies), for the export. */
    suspend fun own(userId: String): List<ExerciseDto> = db.tx { ownRows(userId).map { it.toDto() }.sortedBy { it.name.lowercase() } }

    suspend fun create(userId: String, request: NewExercise): ExerciseDto {
        val id = request.id?.let { requireUuid(it, "id") } ?: newId()
        val name = validName(request.name)
        val muscleGroup = MuscleGroup.entries.firstOrNull { it.key == request.muscleGroup }?.key
            ?: throw badRequest("muscle_group must be one of ${MuscleGroup.entries.joinToString { it.key }}.")
        val equipment = Equipment.entries.firstOrNull { it.key == request.equipment }?.key
            ?: throw badRequest("equipment must be one of ${Equipment.entries.joinToString { it.key }}.")
        val restSeconds = request.restSeconds
        requireValid(Command.CreateExercise(name, MuscleGroup.fromKey(muscleGroup), Equipment.fromKey(equipment)))
        requireValid(Command.SetRest(id, restSeconds))
        val now = System.currentTimeMillis()
        return db.tx {
            lockUser(userId)
            val taken = Exercises.selectAll()
                .where { (Exercises.id eq id) and (Exercises.userId.isNull() or (Exercises.userId eq userId)) }
                .any()
            if (taken) throw conflict("exercise_exists", "An exercise with id $id already exists.")
            insertOwn(userId, id, name, muscleGroup, equipment, isCustom = true, restSeconds = restSeconds, now = now)
        }
    }

    /**
     * Inserts one of the user's own exercises in the current transaction (the
     * caller holds the user's lock and has validated the values): a custom
     * one, or the row of an extended-catalogue entry (`isCustom = false`, as
     * the app's `getOrCreateExtended` makes it).
     */
    fun Transaction.insertOwn(
        userId: String,
        id: String,
        name: String,
        muscleGroup: String,
        equipment: String,
        isCustom: Boolean,
        restSeconds: Int? = null,
        now: Long,
    ): ExerciseDto {
        Exercises.insert {
            it[Exercises.userId] = userId
            it[Exercises.id] = id
            it[nameKey] = null
            it[Exercises.name] = name
            it[Exercises.muscleGroup] = muscleGroup
            it[Exercises.equipment] = equipment
            it[Exercises.isCustom] = isCustom
            it[Exercises.restSeconds] = restSeconds
            it[updatedAt] = now
        }
        return ExerciseDto(id, null, name, muscleGroup, equipment, isCustom, restSeconds, now)
    }

    /**
     * Changes `name` and/or `rest_seconds` (null = follow the default). An own
     * exercise takes both; a catalogue exercise only `rest_seconds`, which makes
     * the user's copy of it on first change.
     */
    suspend fun patch(userId: String, id: String, body: JsonObject): ExerciseDto {
        val unknown = body.keys - setOf("name", "rest_seconds")
        if (unknown.isNotEmpty()) throw badRequest("Only name and rest_seconds can be changed, not ${unknown.joinToString()}.")
        val name = body["name"]?.let {
            val text = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw badRequest("name must be a string.")
            validName(text)
        }
        val restGiven = "rest_seconds" in body
        val rest = body["rest_seconds"]?.let {
            if (it is JsonNull) null else (it as? JsonPrimitive)?.intOrNull ?: throw badRequest("rest_seconds must be a whole number or null.")
        }
        if (restGiven) requireValid(Command.SetRest(id, rest))
        val now = System.currentTimeMillis()
        return db.tx {
            lockUser(userId)
            patchIn(userId, id, name, restGiven, rest, now)
        }
    }

    /**
     * [patch] in the current transaction, with values already checked; the
     * caller holds the user's lock. Shared with the command handler's
     * `set_rest`. Throws 404 for an exercise the user cannot see.
     */
    fun Transaction.patchIn(userId: String, id: String, name: String?, restGiven: Boolean, rest: Int?, now: Long): ExerciseDto {
        val own = ownRows(userId).firstOrNull { it[Exercises.id] == id }
        if (own != null) {
            if (name != null && !own[Exercises.isCustom]) {
                throw badRequest("A catalogue exercise keeps its name; only rest_seconds can change.", "catalogue_name_fixed")
            }
            val updated = maxOf(now, own[Exercises.updatedAt] + 1)
            Exercises.update({ (Exercises.userId eq userId) and (Exercises.id eq id) }) {
                if (name != null) it[Exercises.name] = name
                if (restGiven) it[restSeconds] = rest
                it[updatedAt] = updated
            }
        } else {
            val catalogue = Exercises.selectAll().where { Exercises.userId.isNull() and (Exercises.id eq id) }.singleOrNull()
                ?: throw notFound("Exercise")
            if (name != null) throw badRequest("A catalogue exercise keeps its name; only rest_seconds can change.", "catalogue_name_fixed")
            copyCatalogueExercise(userId, catalogue, if (restGiven) rest else catalogue[Exercises.restSeconds], now)
        }
        return ownRows(userId).first { it[Exercises.id] == id }.toDto()
    }

    /** True when [id] is a catalogue exercise or one of the user's own. */
    fun Transaction.isVisible(userId: String, id: String): Boolean = visibleIds(userId, setOf(id)).isNotEmpty()

    /** Which of [ids] the user may use in a workout: catalogue exercises and their own. */
    fun Transaction.visibleIds(userId: String, ids: Set<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return Exercises.selectAll()
            .where {
                (Exercises.id inList ids) and
                    (Exercises.userId.isNull() or ((Exercises.userId eq userId) and Exercises.deletedAt.isNull()))
            }
            .map { it[Exercises.id] }
            .toSet()
    }

    private fun Transaction.ownRows(userId: String): List<ResultRow> =
        Exercises.selectAll().where { (Exercises.userId eq userId) and Exercises.deletedAt.isNull() }.toList()

    private fun ResultRow.toDto() = ExerciseDto(
        id = this[Exercises.id],
        nameKey = this[Exercises.nameKey],
        name = this[Exercises.name],
        muscleGroup = this[Exercises.muscleGroup],
        equipment = this[Exercises.equipment],
        isCustom = this[Exercises.isCustom],
        restSeconds = this[Exercises.restSeconds],
        updatedAt = this[Exercises.updatedAt],
    )
}

/**
 * Makes the user's copy of the [catalogue] exercise row with [restSeconds]:
 * the row that shadows the catalogue for them from now on. A deleted copy
 * (sync can tombstone one) is revived, as the key (user_id, id) is taken.
 * Shared by `PATCH /exercises/{id}` and the command handler's `set_rest`.
 */
internal fun Transaction.copyCatalogueExercise(userId: String, catalogue: ResultRow, restSeconds: Int?, now: Long) {
    val id = catalogue[Exercises.id]
    val deletedCopyUpdatedAt = Exercises.selectAll()
        .where { (Exercises.userId eq userId) and (Exercises.id eq id) }
        .singleOrNull()?.get(Exercises.updatedAt)
    Exercises.upsert(Exercises.userId, Exercises.id) {
        it[Exercises.userId] = userId
        it[Exercises.id] = id
        it[Exercises.nameKey] = catalogue[Exercises.nameKey]
        it[Exercises.name] = catalogue[Exercises.name]
        it[Exercises.muscleGroup] = catalogue[Exercises.muscleGroup]
        it[Exercises.equipment] = catalogue[Exercises.equipment]
        it[Exercises.isCustom] = false
        it[Exercises.restSeconds] = restSeconds
        it[Exercises.updatedAt] = deletedCopyUpdatedAt?.let { stored -> maxOf(now, stored + 1) } ?: now
        it[Exercises.deletedAt] = null
    }
}
