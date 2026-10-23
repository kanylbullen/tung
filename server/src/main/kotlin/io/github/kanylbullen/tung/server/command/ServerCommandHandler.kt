package io.github.kanylbullen.tung.server.command

import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandExecutor
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.CommandResult.Done
import io.github.kanylbullen.tung.core.command.CommandResult.Rejected
import io.github.kanylbullen.tung.core.command.validate
import io.github.kanylbullen.tung.server.Db
import io.github.kanylbullen.tung.server.WorkoutStore
import io.github.kanylbullen.tung.server.lockUser
import io.github.kanylbullen.tung.server.push.ChangeNotifier
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.longParam
import org.jetbrains.exposed.sql.update

/** Longest name a command may give an exercise, routine or program; the same cap as the REST API's. */
const val MAX_COMMAND_NAME = 100

/**
 * Builds the [ServerCommandHandler] for one user. Every REST command route
 * and every MCP tool that changes data gets its handler here, so there is one
 * path from a command to the tables.
 */
class CommandService(
    private val db: Db,
    private val catalogue: ServerCatalogue,
    private val workouts: WorkoutStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notifier: ChangeNotifier = ChangeNotifier.NONE,
) {
    /** [sourceDeviceId] is the calling client's device id (push skips it), null for a client without one, as every MCP client. */
    fun handlerFor(userId: String, sourceDeviceId: String? = null): ServerCommandHandler =
        ServerCommandHandler(db, userId, catalogue, workouts, clock, notifier, sourceDeviceId)

    fun resolverFor(userId: String): ServerExerciseResolver = ServerExerciseResolver(userId, catalogue)
}

/**
 * The server's [CommandHandler] for one user: core's [CommandExecutor] over
 * [ExposedCommandStore] (one transaction per command, started with the
 * user's lock, [lockUser]; names resolved by [ServerExerciseResolver], the
 * app's rules over the tables, issue #87), plus what is the server's alone:
 * the REST API's cap on names ([MAX_COMMAND_NAME], checked before anything
 * else, as [validate] is) and, after a commit, the push that wakes the user's
 * other devices ([ChangeNotifier]). Rows are written with `updated_at = now`
 * (or `stored + 1` when that is later, [bumped]), deletes are tombstones,
 * and the `seq` trigger stamps every written row, so a change made here
 * reaches the phone on its next sync like any other write. Every command on
 * the server comes from an assistant or a script: its workouts are started
 * by [StartedBy.AGENT].
 */
class ServerCommandHandler(
    db: Db,
    private val userId: String,
    catalogue: ServerCatalogue,
    workouts: WorkoutStore,
    clock: () -> Long = System::currentTimeMillis,
    private val notifier: ChangeNotifier = ChangeNotifier.NONE,
    private val sourceDeviceId: String? = null,
) : CommandHandler {

    private val executor = CommandExecutor(ExposedCommandStore(db, userId, workouts, clock), ServerExerciseResolver(userId, catalogue), StartedBy.AGENT)

    override suspend fun execute(command: Command): CommandResult {
        nameTooLong(command)?.let { return it }
        val result = executor.execute(command)
        // After the commit: push the change to the user's other devices. select_current only answers ids and writes nothing.
        if (result is Done && command !is Command.SelectCurrent) notifier.changed(userId, sourceDeviceId)
        return result
    }

    /** The REST API's cap on a name, which the sync protocol does not have ([MAX_COMMAND_NAME]); null when the command names nothing or fits. */
    private fun nameTooLong(command: Command): Rejected? {
        val name = when (command) {
            is Command.CreateExercise -> command.name
            is Command.SaveRoutine -> command.name
            is Command.SaveRoutinePlan -> command.plan.name
            is Command.CreateRoutine -> command.name
            is Command.RenameRoutine -> command.name
            is Command.CreateProgram -> command.name
            is Command.RenameProgram -> command.name
            else -> return null
        }
        return if (name.trim().length > MAX_COMMAND_NAME) Rejected(listOf("the name can be at most $MAX_COMMAND_NAME characters")) else null
    }
}

/** `GREATEST(now, updated_at + 1)`: the server's stamp for its own changes, so a write always moves a row forward. */
internal fun bumped(updatedAt: Column<Long>, now: Long): Expression<Long> =
    CustomFunction("GREATEST", LongColumnType(), longParam(now), with(SqlExpressionBuilder) { updatedAt + 1L })

/** Tombstones the live rows [where] matches: `deleted_at` and `updated_at` both get [bumped]. */
internal fun <T : Table> tombstone(table: T, updatedAt: Column<Long>, deletedAt: Column<Long?>, now: Long, where: SqlExpressionBuilder.() -> Op<Boolean>) {
    table.update({ where() and deletedAt.isNull() }) {
        it[deletedAt] = bumped(updatedAt, now)
        it[updatedAt] = bumped(updatedAt, now)
    }
}
