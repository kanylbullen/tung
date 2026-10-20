package io.github.kanylbullen.tung.server.push

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/** Turns a host name into its addresses. The system's DNS in production; tests hand in a table. */
fun interface HostResolver {
    /** Every address [host] resolves to; throws [UnknownHostException] when it resolves to none. */
    fun resolve(host: String): List<InetAddress>

    companion object {
        val SYSTEM = HostResolver { host -> InetAddress.getAllByName(host).toList() }
    }
}

/**
 * Keeps the server from being made to POST to places it should not reach
 * (SSRF): a UnifiedPush endpoint is a URL a user hands in, and the server
 * calls it. Checked when an endpoint is registered **and** before every
 * send, because a name can resolve elsewhere later.
 *
 * By default an endpoint must be `https://`, and its host (an IP literal, or
 * every address its name resolves to) must not be loopback, unspecified,
 * link-local (including the cloud metadata address 169.254.169.254),
 * private (10/8, 172.16/12, 192.168/16, fc00::/7, the old fec0::/10),
 * carrier-grade NAT (100.64/10), 0/8, broadcast or multicast.
 * [allowPrivate] (`TUNG_PUSH_ALLOW_PRIVATE=true`, a self-hoster whose
 * distributor is on the LAN) lifts both rules.
 *
 * What it cannot stop: a name that resolves to a public address here and to
 * a private one a moment later, when the HTTP client resolves it again
 * (DNS rebinding between the check and the connect). The send-time check
 * narrows that window to milliseconds; redirects are never followed.
 */
class EndpointGuard(
    private val allowPrivate: Boolean = false,
    private val resolver: HostResolver = HostResolver.SYSTEM,
) {
    /** Null when the server may POST to [endpoint], else why not, without the endpoint itself (it is a capability). */
    fun refusal(endpoint: String): String? {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return "the endpoint is not a URL"
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return "the endpoint has no host"
        if (allowPrivate) return if (scheme == "https" || scheme == "http") null else "the endpoint must be an http(s) URL"
        if (scheme != "https") return "the endpoint must use https:// (plain http:// needs TUNG_PUSH_ALLOW_PRIVATE=true on this server)"
        val addresses = ipLiteral(host)?.let(::listOf) ?: try {
            resolver.resolve(host)
        } catch (_: UnknownHostException) {
            return "the endpoint's host does not resolve"
        }
        if (addresses.isEmpty()) return "the endpoint's host does not resolve"
        if (addresses.any(::isInternal)) {
            return "the endpoint's host is a private, loopback or link-local address " +
                "(a distributor on the LAN needs TUNG_PUSH_ALLOW_PRIVATE=true on this server)"
        }
        return null
    }
}

/** [host] as an address when it is an IPv4 or IPv6 literal, without any DNS lookup; else null. */
internal fun ipLiteral(host: String): InetAddress? {
    val looksLiteral = host.contains(':') || host.matches(Regex("""[0-9.]+"""))
    if (!looksLiteral) return null
    // getByName does no lookup for a literal; a malformed one throws, and is refused as unresolvable.
    return runCatching { InetAddress.getByName(host) }.getOrNull()
}

/** An address the server must not be made to call: anything that is not plainly on the public internet. */
fun isInternal(address: InetAddress): Boolean {
    val v4 = when (address) {
        is Inet4Address -> address
        // ::a.b.c.d (IPv4-compatible); ::ffff:a.b.c.d already comes back from getByName as an Inet4Address.
        is Inet6Address -> if (address.isIPv4CompatibleAddress) {
            InetAddress.getByAddress(address.address.copyOfRange(12, 16)) as Inet4Address
        } else {
            null
        }
        else -> null
    }
    if (v4 != null) return isInternalV4(v4.address)
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) {
        return true
    }
    val first = address.address[0].toInt() and 0xff
    return first and 0xfe == 0xfc // fc00::/7, unique local
}

private fun isInternalV4(bytes: ByteArray): Boolean {
    val a = bytes[0].toInt() and 0xff
    val b = bytes[1].toInt() and 0xff
    return a == 0 || // 0.0.0.0/8, "this network"
        a == 10 || // 10/8
        a == 127 || // loopback
        (a == 100 && b in 64..127) || // 100.64/10, carrier-grade NAT
        (a == 169 && b == 254) || // link-local, with the cloud metadata address 169.254.169.254
        (a == 172 && b in 16..31) || // 172.16/12
        (a == 192 && b == 168) || // 192.168/16
        a >= 224 // multicast 224/4, reserved 240/4 and broadcast
}
