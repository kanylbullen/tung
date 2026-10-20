package io.github.kanylbullen.tung.core.wear

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.RestTimer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the phone and the watch say to each other over the Wearable Data Layer.
 * Both sides use these classes, so the JSON cannot drift apart.
 *
 * - Phone → watch: one DataItem at [WatchProtocol.STATE_PATH] holding a
 *   [WatchWorkoutState]. The Data Layer syncs and persists it, so the watch
 *   reads the latest state after reconnecting or restarting.
 * - Watch → phone: a message at [WatchProtocol.COMMAND_PATH] holding one
 *   [WatchCommand]. Messages are not queued; a send fails when the phone is
 *   out of reach.
 */
object WatchProtocol {
    /** DataItem path of the phone's state. */
    const val STATE_PATH = "/tung/active_workout"

    /** DataMap key under which [STATE_PATH] carries the JSON. */
    const val STATE_KEY = "json"

    /** Message path of the watch's commands. */
    const val COMMAND_PATH = "/tung/cmd"

    /** Bumped when a change would make an older watch misread the state. */
    const val VERSION = 1

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encodeState(state: WatchWorkoutState): String = json.encodeToString(WatchWorkoutState.serializer(), state)

    fun decodeState(text: String): WatchWorkoutState = json.decodeFromString(WatchWorkoutState.serializer(), text)

    fun encodeCommand(command: WatchCommand): String = json.encodeToString(WatchCommand.serializer(), command)

    fun decodeCommand(text: String): WatchCommand = json.decodeFromString(WatchCommand.serializer(), text)
}

/**
 * The phone's picture of the workout in progress, as the watch shows it.
 * [workoutId] null means no workout is in progress; [routines] are what the
 * watch can start one from.
 */
@Serializable
data class WatchWorkoutState(
    val workoutId: String? = null,
    val startedAt: Long? = null,
    val exercises: List<WatchExercise> = emptyList(),
    /** The set "Log set" acts on, by the phone's rule (`core.currentSet`). */
    val currentSetId: String? = null,
    /** A running rest, or null. */
    val rest: WatchRest? = null,
    val routines: List<WatchRoutine> = emptyList(),
    /** Epoch millis on the phone when this state was built. */
    val updatedAt: Long,
    val version: Int = WatchProtocol.VERSION,
) {
    val isActive: Boolean get() = workoutId != null

    fun exerciseOf(setId: String): WatchExercise? = exercises.firstOrNull { e -> e.sets.any { it.setId == setId } }

    fun set(setId: String): WatchSet? = exercises.firstNotNullOfOrNull { e -> e.sets.firstOrNull { it.setId == setId } }
}

@Serializable
data class WatchExercise(
    val workoutExerciseId: String,
    /** Display name, already in the phone's language. */
    val name: String,
    /** In position order. */
    val sets: List<WatchSet>,
    /** The sets of the latest finished session with this exercise, in order. */
    val lastTime: List<WatchLastSet> = emptyList(),
)

@Serializable
data class WatchSet(
    val setId: String,
    val position: Int,
    val weightKg: Double,
    val reps: Int,
    val completed: Boolean,
)

@Serializable
data class WatchLastSet(val weightKg: Double, val reps: Int) {
    fun toLogged(): LoggedSet = LoggedSet(weightKg, reps)
}

fun LoggedSet.toWatch(): WatchLastSet = WatchLastSet(weightKg, reps)

@Serializable
data class WatchRest(val endsAtMillis: Long, val durationMillis: Long) {
    fun toRestTimer(): RestTimer = RestTimer(endsAtMillis, durationMillis)
}

fun RestTimer.toWatch(): WatchRest = WatchRest(endsAtMillis, durationMillis)

@Serializable
data class WatchRoutine(val id: String, val name: String, val exerciseCount: Int)

/**
 * One thing the user did on the watch. Every command carries a [commandId]
 * the watch made up (a UUID), so the phone can ignore a duplicate, and
 * [sentAt], the watch's clock when it was sent.
 */
@Serializable
sealed interface WatchCommand {
    val commandId: String
    val sentAt: Long

    /** "Log set": tick [setId] and, if it was the last of its exercise, add the next set. Starts the rest. */
    @Serializable
    @SerialName("log_set")
    data class LogSet(val setId: String, override val commandId: String, override val sentAt: Long) : WatchCommand

    @Serializable
    @SerialName("set_weight")
    data class SetWeight(
        val setId: String,
        val kg: Double,
        override val commandId: String,
        override val sentAt: Long,
    ) : WatchCommand

    @Serializable
    @SerialName("set_reps")
    data class SetReps(
        val setId: String,
        val reps: Int,
        override val commandId: String,
        override val sentAt: Long,
    ) : WatchCommand

    /** Make [setId] the current set, like tapping a set's number on the phone. */
    @Serializable
    @SerialName("select_set")
    data class SelectSet(val setId: String, override val commandId: String, override val sentAt: Long) : WatchCommand

    /** An empty workout; the exercises are added on the phone in v0. */
    @Serializable
    @SerialName("start_workout")
    data class StartWorkout(override val commandId: String, override val sentAt: Long) : WatchCommand

    @Serializable
    @SerialName("start_routine")
    data class StartRoutine(
        val routineId: String,
        override val commandId: String,
        override val sentAt: Long,
    ) : WatchCommand

    @Serializable
    @SerialName("finish_workout")
    data class FinishWorkout(override val commandId: String, override val sentAt: Long) : WatchCommand

    @Serializable
    @SerialName("skip_rest")
    data class SkipRest(override val commandId: String, override val sentAt: Long) : WatchCommand

    @Serializable
    @SerialName("add_rest_30")
    data class AddRest30(override val commandId: String, override val sentAt: Long) : WatchCommand
}
