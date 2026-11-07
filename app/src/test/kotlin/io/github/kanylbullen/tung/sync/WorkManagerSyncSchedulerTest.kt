package io.github.kanylbullen.tung.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A change's sync (issue #199) does not pile up while offline: with a sync
 * already waiting for a network, [WorkManagerSyncScheduler.enqueueSoon] adds
 * nothing, since the waiting one has not read the dirty rows yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkManagerSyncSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scheduler = WorkManagerSyncScheduler(context)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun waiting(): Int = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(WorkManagerSyncScheduler.NOW_WORK).get()
        .count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }

    @Test
    fun aChangeWithASyncAlreadyWaitingQueuesNothingMore() = runBlocking {
        // No network in the test driver: the sync stays enqueued.
        scheduler.enqueueSoon()
        assertEquals(1, waiting())
        repeat(5) { scheduler.enqueueSoon() }
        assertEquals(1, waiting())
        // A finished workout still appends its own, as before.
        scheduler.enqueueNow()
        assertEquals(2, waiting())
    }
}
