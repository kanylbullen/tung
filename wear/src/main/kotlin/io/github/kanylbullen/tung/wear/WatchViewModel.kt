package io.github.kanylbullen.tung.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.wear.WatchCommand
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** What the watch shows. */
data class WatchUi(
    /** False until the Data Layer has been read once. */
    val loaded: Boolean = false,
    /** The phone's latest state with the watch's unconfirmed taps applied; null if the phone never published. */
    val state: WatchWorkoutState? = null,
    /** The last command could not be delivered. */
    val phoneUnreachable: Boolean = false,
)

class WatchViewModel(application: Application) : AndroidViewModel(application) {
    private val link = PhoneLink(application)
    private val clock: () -> Long = System::currentTimeMillis

    private val loaded = MutableStateFlow(false)
    private val remote = MutableStateFlow<WatchWorkoutState?>(null)

    /** Commands sent since the last state from the phone, applied on top of it. */
    private val pending = MutableStateFlow<List<WatchCommand>>(emptyList())
    private val unreachable = MutableStateFlow(false)

    /** The `endsAtMillis` of the last rest the watch buzzed for, so each rest buzzes once. */
    var lastBuzzedRestEnd: Long? = null

    val ui: StateFlow<WatchUi> = combine(loaded, remote, pending, unreachable) { isLoaded, state, commands, noPhone ->
        val now = clock()
        WatchUi(
            loaded = isLoaded,
            state = state?.let { commands.fold(it) { s, c -> s.applying(c, now) } },
            phoneUnreachable = noPhone,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WatchUi())

    init {
        viewModelScope.launch {
            link.states().collect { state ->
                if (state != null) {
                    remote.value = state
                    // The phone has answered: its state replaces the guesses, and it is evidently reachable.
                    pending.value = emptyList()
                    unreachable.value = false
                }
                loaded.value = true
            }
        }
    }

    fun logSet(setId: String) = send { id, at -> WatchCommand.LogSet(setId, id, at) }
    fun setWeight(setId: String, kg: Double) = send { id, at -> WatchCommand.SetWeight(setId, kg, id, at) }
    fun setReps(setId: String, reps: Int) = send { id, at -> WatchCommand.SetReps(setId, reps, id, at) }
    fun selectSet(setId: String) = send { id, at -> WatchCommand.SelectSet(setId, id, at) }
    fun startWorkout() = send { id, at -> WatchCommand.StartWorkout(id, at) }
    fun startRoutine(routineId: String) = send { id, at -> WatchCommand.StartRoutine(routineId, id, at) }
    fun finishWorkout() = send { id, at -> WatchCommand.FinishWorkout(id, at) }
    fun skipRest() = send { id, at -> WatchCommand.SkipRest(id, at) }
    fun addRest() = send { id, at -> WatchCommand.AddRest30(id, at) }

    private fun send(make: (commandId: String, sentAt: Long) -> WatchCommand) {
        val command = make(UUID.randomUUID().toString(), clock())
        pending.update { it + command }
        viewModelScope.launch {
            unreachable.value = !link.send(command)
        }
    }
}
