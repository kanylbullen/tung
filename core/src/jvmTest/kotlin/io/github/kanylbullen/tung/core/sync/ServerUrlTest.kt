package io.github.kanylbullen.tung.core.sync

import io.github.kanylbullen.tung.core.ai.checkAiBaseUrl
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

    /** Brackets hold an IPv6 address and nothing else: `[192.168.1.10]` and `[abc]` are malformed, not valid. */
    @Test
    fun aBracketedHostMustBeAnIpv6Literal() {
        assertEquals(ServerUrlCheck.Valid("http://[::1]:8080"), checkServerUrl("http://[::1]:8080"))
        assertEquals(ServerUrlCheck.Valid("http://[fe80::1]"), checkServerUrl("http://[fe80::1]"))
        assertEquals(ServerUrlCheck.Valid("https://[2001:db8::1]/tung"), checkServerUrl("https://[2001:DB8::1]/tung"))
        assertEquals(ServerUrlCheck.Valid("https://[::ffff:192.168.1.10]"), checkServerUrl("https://[::ffff:192.168.1.10]"))
        assertEquals(ServerUrlCheck.Valid("https://[1:2:3:4:5:6:7:8]"), checkServerUrl("https://[1:2:3:4:5:6:7:8]"))
        assertEquals(ServerUrlCheck.Valid("https://[1:2:3:4:5:6:10.0.0.1]"), checkServerUrl("https://[1:2:3:4:5:6:10.0.0.1]"))
        assertEquals(ServerUrlCheck.NeedsHttps, checkServerUrl("http://[2001:db8::1]"))
        listOf(
            "http://[192.168.1.10]:8080",
            "https://[abc]",
            "https://[]",
            "https://[::1",
            "https://[1:2:3:4:5:6:7]",
            "https://[1:2:3:4:5:6:7:8:9]",
            "https://[1:::2]",
            "https://[1::2::3]",
            "https://[:1:2:3:4:5:6:7]",
            "https://[12345::1]",
            "https://[::ffff:192.168.1]",
            "https://[192.168.1.10::1]",
            "https://[fe80::1%eth0]",
        ).forEach { assertEquals(it, ServerUrlCheck.Malformed, checkServerUrl(it)) }
    }

    /**
     * An octet with a leading zero is not an IP literal (platform parsers read
     * it as octal): `010.0.0.5` is a host name, so http to it needs https.
     */
    @Test
    fun anOctetWithALeadingZeroIsNotAnIpAddress() {
        assertEquals(ServerUrlCheck.NeedsHttps, checkServerUrl("http://010.0.0.5"))
        assertEquals(ServerUrlCheck.NeedsHttps, checkServerUrl("http://192.168.01.5:8080"))
        assertEquals(ServerUrlCheck.NeedsHttps, checkServerUrl("http://127.000.0.1"))
        assertEquals(ServerUrlCheck.Valid("https://010.0.0.5"), checkServerUrl("https://010.0.0.5"))
        // A single zero is an octet like any other.
        assertEquals(ServerUrlCheck.Valid("http://10.0.0.5"), checkServerUrl("http://10.0.0.5"))
        assertEquals(ServerUrlCheck.Valid("http://192.168.0.1"), checkServerUrl("http://192.168.0.1"))
        listOf("010.0.0.5", "127.000.0.1", "192.168.01.5", "00.0.0.0").forEach { assertFalse(it, isLocalHost(it)) }
    }

    /** The assistant's base URL is checked by the same parser, so it refuses and requires the same. */
    @Test
    fun theAssistantsBaseUrlSharesTheParser() {
        assertEquals(ServerUrlCheck.Malformed, checkAiBaseUrl("http://[192.168.1.10]:11434/v1"))
        assertEquals(ServerUrlCheck.Malformed, checkAiBaseUrl("https://[abc]/v1"))
        assertEquals(ServerUrlCheck.NeedsHttps, checkAiBaseUrl("http://010.0.0.5:11434/v1"))
        assertEquals(ServerUrlCheck.Valid("http://[fd00::5]:11434/v1"), checkAiBaseUrl("http://[fd00::5]:11434/v1"))
        assertEquals(ServerUrlCheck.Valid("http://10.0.0.5:11434/v1"), checkAiBaseUrl("http://10.0.0.5:11434/v1"))
    }

    @Test
    fun localHosts() {
        listOf("localhost", "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.1.1", "[::1]", "fd00::1", "fe80::1")
            .forEach { assertTrue(it, isLocalHost(it)) }
        listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "8.8.8.8", "example.org", "2001:db8::1", "256.1.1.1", "1.2.3")
            .forEach { assertFalse(it, isLocalHost(it)) }
    }
}
