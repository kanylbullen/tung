package io.github.kanylbullen.tung.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class PushSelectionTest {
    private val ntfy = "io.heckel.ntfy"
    private val nextPush = "org.unifiedpush.distributor.nextpush"

    @Test
    fun `FCM needs both Play services and a Firebase app`() {
        assertEquals(PushChoice.Fcm, selectPushTransport(playServices = true, firebaseConfigured = true, distributors = listOf(ntfy)))
        assertEquals(PushChoice.UnifiedPush(ntfy), selectPushTransport(playServices = true, firebaseConfigured = false, distributors = listOf(ntfy)))
        assertEquals(PushChoice.UnifiedPush(ntfy), selectPushTransport(playServices = false, firebaseConfigured = true, distributors = listOf(ntfy)))
    }

    @Test
    fun `without FCM or a distributor there is no push`() {
        assertEquals(PushChoice.None, selectPushTransport(playServices = true, firebaseConfigured = false, distributors = emptyList()))
        assertEquals(PushChoice.None, selectPushTransport(playServices = false, firebaseConfigured = false, distributors = emptyList()))
    }

    @Test
    fun `the chosen distributor wins while it is installed, else the first`() {
        val both = listOf(ntfy, nextPush)
        assertEquals(PushChoice.UnifiedPush(nextPush), selectPushTransport(false, false, both, chosen = nextPush))
        assertEquals(PushChoice.UnifiedPush(ntfy), selectPushTransport(false, false, both, chosen = "uninstalled.app"))
        assertEquals(PushChoice.UnifiedPush(ntfy), selectPushTransport(false, false, both))
    }

    @Test
    fun `FCM wins over a chosen distributor`() {
        assertEquals(PushChoice.Fcm, selectPushTransport(true, true, listOf(ntfy), chosen = ntfy))
    }
}
