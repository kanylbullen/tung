package io.github.kanylbullen.tung.wear

import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull

/**
 * Keeps the watch's default rest the same as the phone's (#228). The default
 * rest is a setting on each device, not synced, and the watch has no screen
 * for it, so a standalone watch rested 90 s whatever the phone said. The phone
 * publishes its value in [WatchWorkoutState.defaultRestSeconds]; every new
 * value read from [states] (the stored DataItem first, then each change) is
 * handed to [store]. A state without it (an older phone, or none at all)
 * changes nothing. In companion mode the phone times the rest, so the stored
 * copy is unused there but harmless.
 */
suspend fun followPhoneDefaultRest(states: Flow<WatchWorkoutState?>, store: suspend (Int) -> Unit) {
    states.mapNotNull { it?.defaultRestSeconds }.distinctUntilChanged().collect { store(it) }
}
