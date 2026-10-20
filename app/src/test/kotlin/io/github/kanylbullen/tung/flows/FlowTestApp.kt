package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.TungApp
import io.github.kanylbullen.tung.data.TungContainer
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.push.FakePushEnvironment
import io.github.kanylbullen.tung.sync.MemoryTokenStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The application Robolectric starts for the flow tests (see
 * `robolectric.properties`). Identical to [TungApp] except that the database
 * lives in memory, seeded with the same catalogue, that Home's "today"
 * comes from [today], and that the sync token is kept in [tokens] instead of
 * EncryptedSharedPreferences (the assistant's API key likewise, in [aiKeys]), and that its push transports are [push]. Robolectric creates a new application per test, so every
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

    val tokens = MemoryTokenStore()

    /** The in-app assistant's API key, in memory for the same reason as [tokens]. */
    val aiKeys = MemoryTokenStore()

    /** The push transports this "device" has: none unless a test sets its fields (it is read when push refreshes). */
    val push = FakePushEnvironment()

    override fun createContainer(): TungContainer = TungContainer(
        this,
        clock = SettableClock(),
        createDatabase = { database },
        createTokenStore = { tokens },
        createPushEnvironment = { push },
        createAiKeyStore = { aiKeys },
    )

    /** Every read goes to [today], so a test can change it after the container exists. */
    private inner class SettableClock : Clock() {
        override fun getZone(): ZoneId = today.zone
        override fun withZone(zone: ZoneId): Clock = today.withZone(zone)
        override fun instant(): Instant = today.instant()
    }
}
