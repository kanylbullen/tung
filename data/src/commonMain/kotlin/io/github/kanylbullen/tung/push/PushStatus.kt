package io.github.kanylbullen.tung.push

import io.github.kanylbullen.tung.core.sync.PushChoice
import kotlinx.coroutines.flow.StateFlow

/** An installed UnifiedPush distributor, for Settings' "Choose UnifiedPush distributor". */
data class PushDistributor(val packageName: String, val label: String)

/**
 * What Settings shows about push. [choice] is null until the first
 * [PushControl.refresh]; [registered] is true once the server has the
 * endpoint; [distributors] are the installed ones, to choose among when
 * there are several.
 */
data class PushStatus(
    val choice: PushChoice? = null,
    val distributorLabel: String? = null,
    val registered: Boolean = false,
    val distributors: List<PushDistributor> = emptyList(),
    /** The endpoint the server has: an FCM token or a UnifiedPush URL (show only its host). */
    val endpoint: String? = null,
)

/**
 * Push as Settings sees it: what wakes this device, and the choice among
 * distributors. The phone's is `PushRegistrar` (FCM or UnifiedPush); an
 * iPhone's will be APNs.
 */
interface PushControl {
    val status: StateFlow<PushStatus>

    /** Picks the transport again and registers its endpoint with the server. */
    suspend fun refresh()

    /** Use [packageName] among several UnifiedPush distributors. */
    suspend fun chooseDistributor(packageName: String)
}
