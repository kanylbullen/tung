package io.github.kanylbullen.tung.web.settings

import io.github.kanylbullen.tung.core.DEFAULT_REST_SECONDS
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MAX_REST_SECONDS
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The browser's own settings, the phone's `settings` DataStore values that
 * are not synced: the default rest and "My equipment". Kept in local
 * storage per browser, as the phone keeps them per phone; a browser that
 * stores nothing (private mode) keeps the defaults.
 */
class WebPreferences {
    private val _restSeconds = MutableStateFlow(read(REST)?.toIntOrNull()?.coerceIn(0, MAX_REST_SECONDS) ?: DEFAULT_REST_SECONDS)
    private val _equipment = MutableStateFlow(
        read(EQUIPMENT)?.split(',')?.filter { it.isNotBlank() }?.let { EquipmentFilter.fromKeys(it) }?.takeIf { it.isNotEmpty() }
            ?: EquipmentFilter.ALL,
    )

    /** The rest after a set when the exercise has none of its own, 0 to 300 s; 90 s until changed. */
    val restSeconds: StateFlow<Int> = _restSeconds.asStateFlow()

    /** What the picker offers; everything until narrowed. Never empty. */
    val equipment: StateFlow<Set<Equipment>> = _equipment.asStateFlow()

    fun setRestSeconds(seconds: Int) {
        val value = seconds.coerceIn(0, MAX_REST_SECONDS)
        _restSeconds.value = value
        write(REST, value.toString())
    }

    /** Ticks or unticks one kind; the last one ticked stays, as on the phone. */
    fun toggleEquipment(kind: Equipment) {
        val now = _equipment.value
        val next = if (kind in now) now - kind else now + kind
        if (next.isEmpty()) return
        setEquipment(next)
    }

    fun setEquipment(kinds: Set<Equipment>) {
        if (kinds.isEmpty()) return
        _equipment.value = kinds
        write(EQUIPMENT, EquipmentFilter.toKeys(kinds).joinToString(","))
    }

    private companion object {
        const val REST = "tung-rest-default"
        const val EQUIPMENT = "tung-my-equipment"

        fun read(key: String): String? = try {
            window.localStorage.getItem(key)
        } catch (e: Throwable) {
            null
        }

        fun write(key: String, value: String) {
            try {
                window.localStorage.setItem(key, value)
            } catch (e: Throwable) {
                // Private mode: the choice lasts while the page does.
            }
        }
    }
}
