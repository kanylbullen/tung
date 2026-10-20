package io.github.kanylbullen.tung.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    @Test
    fun httpsIsAcceptedAndNormalised() {
        assertEquals(ServerUrlCheck.Valid("https://tung.example.org"), checkServerUrl("  HTTPS://Tung.Example.org/ "))
        assertEquals(ServerUrlCheck.Valid("https://example.org:8443/tung"), checkServerUrl("https://example.org:8443/tung/"))
    }

    @Test
    fun httpOnlyToThisDeviceOrTheLocalNetwork() {
        assertEquals(ServerUrlCheck.Valid("http://localhost:8080"), checkServerUrl("http://localhost:8080"))
        assertEquals(ServerUrlCheck.Valid("http://192.168.12.5:8080"), checkServerUrl("http://192.168.12.5:8080"))
        assertEquals(ServerUrlCheck.Valid("http://10.0.2.2:8080"), checkServerUrl("http://10.0.2.2:8080"))
        assertEquals(ServerUrlCheck.NeedsHttps, checkServerUrl("http://tung.example.org"))
        assertEquals(ServerUrlCheck.NeedsHttps, checkServerUrl("http://8.8.8.8"))
        assertEquals(ServerUrlCheck.NeedsHttps, checkServerUrl("http://nas.lan"))
    }

    @Test
    fun anythingElseIsMalformed() {
        listOf("", "tung.example.org", "ftp://example.org", "https://", "https://example.org/?a=1", "https://u:p@example.org")
            .forEach { assertEquals(it, ServerUrlCheck.Malformed, checkServerUrl(it)) }
    }

    @Test
    fun localHosts() {
        listOf("localhost", "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.1.1", "[::1]", "fd00::1", "fe80::1")
            .forEach { assertTrue(it, isLocalHost(it)) }
        listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "8.8.8.8", "example.org", "2001:db8::1", "256.1.1.1", "1.2.3")
            .forEach { assertFalse(it, isLocalHost(it)) }
    }
}
