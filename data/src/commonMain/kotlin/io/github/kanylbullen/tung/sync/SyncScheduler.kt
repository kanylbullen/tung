package io.github.kanylbullen.tung.sync

/** When background syncs run: WorkManager on Android (`WorkManagerSyncScheduler`); the iOS one (BGTaskScheduler) comes with the iOS app. */
interface SyncScheduler {
    /** Every 15 minutes while signed in, with a network. Kept if already scheduled. */
    fun schedulePeriodic()

    /** One sync as soon as there is a network, after any that is running. */
    fun enqueueNow()

    /** Both, on sign out. */
    fun cancel()
}
