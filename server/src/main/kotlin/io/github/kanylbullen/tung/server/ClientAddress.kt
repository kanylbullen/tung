package io.github.kanylbullen.tung.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The key every per-address limit counts a request under: the client's
 * address as the forwarded-header plugin left it (see `TUNG_TRUST_PROXY` and
 * `TUNG_CLIENT_IP_HEADER`), with an IPv6 address cut to its /64. One IPv6
 * subscriber usually holds a whole /64, so counting single addresses would
 * hand each client 2⁶⁴ budgets.
 */
fun clientKey(call: ApplicationCall): String = addressKey(call.request.origin.remoteAddress)

/**
 * [address] as a limit key: an IPv4 address (also written IPv4-mapped,
 * `::ffff:a.b.c.d`) as it is, an IPv6 address as its /64 prefix
 * (`2001:db8:1:2::/64`), and anything that is no address literal as it is.
 * No DNS lookup is ever made.
 */
internal fun addressKey(address: String): String {
    val bare = address.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
    if (!bare.contains(':') || !bare.all { it in IPV6_CHARS }) return address
    // In brackets getByName takes it as an IPv6 literal and throws on a malformed one instead of asking DNS.
    return when (val parsed = runCatching { InetAddress.getByName("[$bare]") }.getOrNull()) {
        is Inet6Address -> {
            val bytes = parsed.address
            (0 until 4).joinToString(":", postfix = "::/64") { group ->
                val value = ((bytes[group * 2].toInt() and 0xff) shl 8) or (bytes[group * 2 + 1].toInt() and 0xff)
                value.toString(16)
            }
        }
        null -> address
        else -> parsed.hostAddress
    }
}

private val IPV6_CHARS = ('0'..'9').toSet() + ('a'..'f') + ('A'..'F') + ':' + '.'
