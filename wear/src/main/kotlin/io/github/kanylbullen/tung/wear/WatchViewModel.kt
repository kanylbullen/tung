package io.github.kanylbullen.tung.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.wear.WatchCommand
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.data.TungHost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** What the watch shows. */
data class WatchUi(
    /** False until the mode is known and its state has been read once. */
    val loaded: Boolean = false,
    /** Standalone (own log) or companion (the phone's); null until the token store has been read. */
    val mode: WatchMode? = null,
    /**
     * The state to show: in companion mode the phone's latest with the
     * watch's unconfirmed taps applied, null if the phone never published;
     * in standalone mode the watch's own database.
     */
    val state: WatchWorkoutState? = null,
    /** Companion mode: the last command could not be delivered. */
    val phoneUnreachable: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class WatchViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as TungHost).container
    private val phone = PhoneLink(application)
    private val local by lazy { (application as? TungWearApp)?.localBackend ?: LocalWatchBackend(container, application) }
    private val clock: () -> Long = System::currentTimeMillis

    private val mode = MutableStateFlow<WatchMode?>(null)
    private val loaded = MutableStateFlow(false)
    private val remote = MutableStateFlow<WatchWorkoutState?>(null)

    /** Companion mode: commands sent since the phone's last state, applied on top of it. */
    private val pending = MutableStateFlow<List<WatchCommand>>(emptyList())
    private val unreachable = MutableStateFlow(false)

    /** The `endsAtMillis` of the last rest the watch buzzed for, so each rest buzzes once. */
    private var lastBuzzedRestEnd: Long? = null

    val ui: StateFlow<WatchUi> = combine(mode, loaded, remote, pending, unreachable) { m, isLoaded, state, commands, noPhone ->
        val now = clock()
        WatchUi(
            loaded = isLoaded,
            mode = m,
            state = state?.let { commands.fold(it) { s, c -> s.applying(c, now) } },
            phoneUnreachable = noPhone,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WatchUi())

    init {
        viewModelScope.launch {
            container.syncAccount.state.map { it.signedIn }.filterNotNull().map(::watchMode).distinctUntilChanged()
                .onEach { m ->
                    // A new mode starts from nothing: the other side's state and guesses do not carry over.
                    mode.value = m
                    loaded.value = false
                    remote.value = null
                    pending.value = emptyList()
                    unreachable.value = false
                }
                .flatMapLatest { m -> backend(m).states() }
                .collect { state ->
                    if (state != null) {
                        remote.value = state
                        // The phone (or the watch's own database) has answered: its state replaces the guesses.
                        pending.value = emptyList()
                        unreachable.value = false
                    }
                    loaded.value = true
                }
        }
    }

    private fun backend(m: WatchMode): WatchBackend = when (m) {
        WatchMode.STANDALONE -> local
        WatchMode.COMPANION -> phone
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

    /**
     * Whether the screen should buzz for the rest ending at [endsAt]: once per
     * rest. Standalone, the watch's own rest timer alerts instead
     * (`RestAlerts`, which also fires from the alarm and alerts once per rest),
     * so the screen never buzzes a second time.
     */
    fun claimRestBuzz(endsAt: Long): Boolean {
        val workoutId = ui.value.state?.workoutId
        if (ui.value.mode == WatchMode.STANDALONE) {
            if (workoutId != null) container.restTimer.restOver(workoutId, endsAt)
            return false
        }
        return (lastBuzzedRestEnd != endsAt).also { lastBuzzedRestEnd = endsAt }
    }

    private fun send(make: (commandId: String, sentAt: Long) -> WatchCommand) {
        val command = make(UUID.randomUUID().toString(), clock())
        val target = mode.value ?: return
        // Standalone runs the command locally in milliseconds; a guess would only linger if it changed nothing.
        if (target == WatchMode.COMPANION) pending.update { it + command }
        viewModelScope.launch {
            val delivered = backend(target).send(command)
            if (target == WatchMode.COMPANION) unreachable.value = !delivered
        }
    }
}
