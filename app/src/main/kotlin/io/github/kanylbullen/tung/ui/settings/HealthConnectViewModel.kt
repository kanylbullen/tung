package io.github.kanylbullen.tung.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.health.HealthAccess
import io.github.kanylbullen.tung.health.HealthAvailability
import io.github.kanylbullen.tung.health.HealthConnectGateway
import io.github.kanylbullen.tung.health.HealthConnectSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HealthConnectUiState(
    val availability: HealthAvailability,
    /** The switches: opted in **and** permitted, so a permission revoked in Health Connect shows as off. */
    val write: Boolean = false,
    val read: Boolean = false,
    /** The opt-in whose permission sheet came back without the permissions, for the hint under the switches. */
    val denied: HealthAccess? = null,
)

/**
 * The Health Connect section of Settings: two opt-ins in [settings], each
 * turned on only once its permissions are granted (the screen shows the
 * sheet when [onToggle] says so). Turning one off keeps the permissions;
 * they are revoked in Health Connect itself.
 */
class HealthConnectViewModel(
    private val gateway: HealthConnectGateway?,
    private val settings: HealthConnectSettings,
) : ViewModel() {

    private val availability = MutableStateFlow(gateway?.availability() ?: HealthAvailability.UNAVAILABLE)
    private val granted = MutableStateFlow<Set<HealthAccess>>(emptySet())
    private val denied = MutableStateFlow<HealthAccess?>(null)

    val state: StateFlow<HealthConnectUiState> = combine(
        availability,
        granted,
        settings.enabled(HealthAccess.WRITE),
        settings.enabled(HealthAccess.READ),
        denied,
    ) { available, permitted, write, read, deniedAccess ->
        HealthConnectUiState(
            availability = available,
            write = write && HealthAccess.WRITE in permitted,
            read = read && HealthAccess.READ in permitted,
            denied = deniedAccess,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthConnectUiState(availability.value))

    init {
        refresh()
    }

    /** Reads availability and permissions again: on start and whenever the screen comes back (from Health Connect's own settings, say). */
    fun refresh() {
        val gateway = gateway ?: return
        viewModelScope.launch {
            val available = gateway.availability()
            availability.value = available
            granted.value = if (available != HealthAvailability.AVAILABLE) {
                emptySet()
            } else {
                HealthAccess.entries.filter { gateway.hasPermissions(it) }.toSet()
            }
        }
    }

    fun permissions(access: HealthAccess): Set<String> = gateway?.permissions(access).orEmpty()

    /**
     * The switch for [access] was tapped. Off, or on with the permissions
     * already granted, is stored at once; on without them returns true, and
     * the screen shows the permission sheet and calls [onPermissionResult].
     */
    fun onToggle(access: HealthAccess, on: Boolean): Boolean {
        denied.value = null
        if (on && access !in granted.value) return true
        viewModelScope.launch { settings.setEnabled(access, on) }
        return false
    }

    fun onPermissionResult(access: HealthAccess, grantedPermissions: Set<String>) {
        val ok = grantedPermissions.containsAll(permissions(access))
        if (ok) granted.value += access else denied.value = access
        viewModelScope.launch {
            if (ok) settings.setEnabled(access, true)
        }
    }
}
