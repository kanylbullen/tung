package io.github.kanylbullen.tung.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class DataOwnerTest {
    private val a = DataOwner("https://tung.example.org", "user-a")
    private val b = DataOwner("https://tung.example.org", "user-b")

    @Test
    fun theSameAccountUploads() {
        assertEquals(LocalDataOnSignIn.UPLOAD, localDataOnSignIn(a, a.copy(serverUrl = "https://tung.example.org/"), hasLocalData = true))
    }

    @Test
    fun anotherAccountOrServerAsks() {
        assertEquals(LocalDataOnSignIn.ASK, localDataOnSignIn(a, b, hasLocalData = true))
        assertEquals(LocalDataOnSignIn.ASK, localDataOnSignIn(a, a.copy(serverUrl = "https://other.example.org"), hasLocalData = true))
        // An answer without a user id is not the same account.
        assertEquals(LocalDataOnSignIn.ASK, localDataOnSignIn(a, null, hasLocalData = true))
    }

    @Test
    fun theEmailNeverDecidesOnceAnOwnerIsRecorded() {
        assertEquals(
            LocalDataOnSignIn.ASK,
            localDataOnSignIn(a, b, hasLocalData = true, previousServerUrl = a.serverUrl, previousEmail = "x@example.org", incomingEmail = "x@example.org"),
        )
        assertEquals(
            LocalDataOnSignIn.UPLOAD,
            localDataOnSignIn(a, a, hasLocalData = true, previousServerUrl = a.serverUrl, previousEmail = "old@example.org", incomingEmail = "new@example.org"),
        )
    }

    @Test
    fun nothingToUploadNeverAsks() {
        assertEquals(LocalDataOnSignIn.UPLOAD, localDataOnSignIn(a, b, hasLocalData = false))
    }

    @Test
    fun aFirstSignInUploads() {
        assertEquals(LocalDataOnSignIn.UPLOAD, localDataOnSignIn(null, b, hasLocalData = true))
    }

    @Test
    fun aDeviceFromBeforeOwnersFallsBackOnTheLastSignIn() {
        fun rule(server: String, email: String) =
            localDataOnSignIn(null, b.copy(serverUrl = server), hasLocalData = true, previousServerUrl = a.serverUrl, previousEmail = "A@example.org", incomingEmail = email)
        assertEquals(LocalDataOnSignIn.UPLOAD, rule(a.serverUrl, "a@example.org "))
        assertEquals(LocalDataOnSignIn.ASK, rule(a.serverUrl, "b@example.org"))
        assertEquals(LocalDataOnSignIn.ASK, rule("https://other.example.org", "a@example.org"))
    }
}
