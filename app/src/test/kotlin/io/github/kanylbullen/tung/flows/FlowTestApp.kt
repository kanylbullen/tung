package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.AppContainer
import io.github.kanylbullen.tung.TungApp
import io.github.kanylbullen.tung.data.TungDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The application Robolectric starts for the flow tests (see
 * `robolectric.properties`). Identical to [TungApp] except that the database
 * lives in memory, seeded with the same catalogue, and that Home's "today"
 * comes from [today]. Robolectric creates a new application per test, so every
 * test starts with an empty history.
 */
class FlowTestApp : TungApp() {
    val database: TungDatabase by lazy { TungDatabase.buildInMemory(this) }

    /**
     * The clock Home reads "today" from. The system clock unless a test sets
     * another; Home reads it whenever its data changes, so set it before
     * seeding what Home should show.
     */
    @Volatile
    var today: Clock = Clock.systemDefaultZone()

    override fun createContainer(): AppContainer = AppContainer(this, clock = SettableClock()) { database }

    /** Every read goes to [today], so a test can change it after the container exists. */
    private inner class SettableClock : Clock() {
        override fun getZone(): ZoneId = today.zone
        override fun withZone(zone: ZoneId): Clock = today.withZone(zone)
        override fun instant(): Instant = today.instant()
    }
}
