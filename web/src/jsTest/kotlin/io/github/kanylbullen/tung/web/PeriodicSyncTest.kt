package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.web.sync.PeriodicSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PeriodicSyncTest {

    @Test
    fun syncsEveryIntervalOnlyWhileShownAndSignedIn() = runTest {
        var visible = true
        var signedIn = true
        var syncs = 0
        val job = PeriodicSync(intervalMillis = 1_000, isVisible = { visible }, isSignedIn = { signedIn }, sync = { syncs++ }).start(this)

        advanceTimeBy(999)
        assertEquals(0, syncs, "nothing before the first interval")
        advanceTimeBy(2)
        assertEquals(1, syncs)
        advanceTimeBy(2_000)
        assertEquals(3, syncs)

        visible = false
        advanceTimeBy(3_000)
        assertEquals(3, syncs, "a hidden page skips its turns")

        visible = true
        signedIn = false
        advanceTimeBy(3_000)
        assertEquals(3, syncs, "and so does a signed-out one")

        signedIn = true
        advanceTimeBy(1_000)
        assertEquals(4, syncs)
        job.cancel()
    }
}
