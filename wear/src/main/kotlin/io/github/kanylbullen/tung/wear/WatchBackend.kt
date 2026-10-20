package io.github.kanylbullen.tung.wear

import android.content.Context
import io.github.kanylbullen.tung.core.wear.WatchCommand
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.data.TungContainer
import kotlinx.coroutines.flow.Flow

/**
 * Where the watch's screens get their state and send their taps: the phone
 * ([PhoneLink], companion mode) or the watch's own database
 * ([LocalWatchBackend], standalone mode). The screens see the same
 * [WatchWorkoutState] and send the same [WatchCommand]s either way.
 */
interface WatchBackend {
    /** The state to show; null while there is none to show (the phone never published). */
    fun states(): Flow<WatchWorkoutState?>

    /** Carries out [command]. False when it could not be delivered. */
    suspend fun send(command: WatchCommand): Boolean
}

/**
 * Standalone mode: the state of the watch's own Room database
 * ([WatchStateSource], the same builder the phone publishes with), and the
 * taps applied on the watch by [LocalWatchCommands] through the shared
 * CommandHandler.
 */
class LocalWatchBackend(container: TungContainer, context: Context) : WatchBackend {
    private val source = WatchStateSource(
        context = context,
        workouts = container.workoutRepository,
        routines = container.routineRepository,
        restTimer = container.restTimer,
        restChanges = container.settings.data,
    )
    private val commands = LocalWatchCommands(container.commands, container.workoutRepository, container.restTimer, source.selection)

    override fun states(): Flow<WatchWorkoutState?> = source.states()

    /** Always delivered: the watch is its own server here. Whether it changed anything shows in the next state. */
    override suspend fun send(command: WatchCommand): Boolean {
        commands.apply(command)
        return true
    }
}
