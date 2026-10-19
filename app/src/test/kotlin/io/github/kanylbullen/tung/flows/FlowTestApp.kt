package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.AppContainer
import io.github.kanylbullen.tung.TungApp
import io.github.kanylbullen.tung.data.TungDatabase

/**
 * The application Robolectric starts for the flow tests (see
 * `robolectric.properties`). Identical to [TungApp] except that the database
 * lives in memory, seeded with the same catalogue. Robolectric creates a new
 * application per test, so every test starts with an empty history.
 */
class FlowTestApp : TungApp() {
    val database: TungDatabase by lazy { TungDatabase.buildInMemory(this) }

    override fun createContainer(): AppContainer = AppContainer(this) { database }
}
