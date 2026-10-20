package io.github.kanylbullen.tung.core.sync

import java.net.URI

/** The result of checking a server address the user typed. */
sealed interface ServerUrlCheck {
    /** Usable; [url] is normalised: scheme and host lower case, no trailing slash. */
    data class Valid(val url: String) : ServerUrlCheck

    /** Not a URL with an http(s) scheme and a host. */
    data object Malformed : ServerUrlCheck

    /** Plain http to a host that is not on this device or the local network. */
    data object NeedsHttps : ServerUrlCheck
}

/**
 * Checks a server address. `https://` always; `http://` only to `localhost`
 * or an IP address literal that is loopback or private (10/8, 172.16/12,
 * 192.168/16, 169.254/16, IPv6 `::1`, `fc00::/7`, `fe80::/10`), so a home-lab
 * server works without a certificate but a token never crosses the internet
 * in the clear. A host name (even one that resolves to a LAN address) needs
 * https. A path is kept, for a server behind a reverse proxy under a prefix.
 */
fun checkServerUrl(input: String): ServerUrlCheck {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ServerUrlCheck.Malformed
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return ServerUrlCheck.Malformed
    val scheme = uri.scheme?.lowercase() ?: return ServerUrlCheck.Malformed
    val host = uri.host?.lowercase() ?: return ServerUrlCheck.Malformed
    if (scheme != "https" && scheme != "http") return ServerUrlCheck.Malformed
    if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return ServerUrlCheck.Malformed
    if (scheme == "http" && !isLocalHost(host)) return ServerUrlCheck.NeedsHttps
    val port = if (uri.port == -1) "" else ":${uri.port}"
    val path = uri.rawPath.orEmpty().trimEnd('/')
    return ServerUrlCheck.Valid("$scheme://$host$port$path")
}

/** `localhost`, or a loopback, link-local or private IP literal (IPv6 in brackets or not). */
fun isLocalHost(host: String): Boolean {
    val h = host.lowercase().removePrefix("[").removeSuffix("]")
    if (h == "localhost" || h.endsWith(".localhost")) return true
    ipv4(h)?.let { (a, b) ->
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
    if (':' in h && h.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }) {
        if (h == "::1") return true
        val first = h.substringBefore(':').ifEmpty { return false }.toIntOrNull(16) ?: return false
        return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
    }
    return false
}

/** The first two octets of a dotted IPv4 literal, or null if [host] is not one. */
private fun ipv4(host: String): Pair<Int, Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.length <= 3 && it in 0..255 } ?: return null }
    return octets[0] to octets[1]
}
