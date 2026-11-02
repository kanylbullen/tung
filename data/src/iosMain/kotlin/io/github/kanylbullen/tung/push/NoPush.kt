package io.github.kanylbullen.tung.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Push on the iPhone until APNs is wired: none, so Settings says the device
 * syncs on its own schedule. The server wakes no iPhone yet.
 */
class NoPush : PushControl {
    override val status: StateFlow<PushStatus> = MutableStateFlow(PushStatus())
    override suspend fun refresh() = Unit
    override suspend fun chooseDistributor(packageName: String) = Unit
}
