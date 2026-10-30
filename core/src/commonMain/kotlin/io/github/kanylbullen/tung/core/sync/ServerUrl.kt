package io.github.kanylbullen.tung.core.sync

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
 *
 * Parsed here rather than with a platform URI class: `scheme://host[:port][/path]`,
 * the host a name of ASCII letters, digits, `-` and `.`, an IPv4 literal or a
 * bracketed IPv6 literal ([isIpv6Literal]: brackets around anything else,
 * `[192.168.1.10]` or `[abc]`, are malformed); a query, a fragment, user
 * info or whitespace is malformed. A dotted name whose octet has a leading
 * zero (`010.0.0.5`) is not an IP literal but a host name, so over http it
 * needs https like any other name: platform parsers read such octets as
 * octal, and a private-looking address must not pass as one by a reading
 * this check does not share.
 */
fun checkServerUrl(input: String): ServerUrlCheck {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.code < 0x20 }) return ServerUrlCheck.Malformed
    val schemeMatch = SCHEME.find(trimmed) ?: return ServerUrlCheck.Malformed
    val scheme = schemeMatch.groupValues[1].lowercase()
    if (scheme != "https" && scheme != "http") return ServerUrlCheck.Malformed
    val rest = trimmed.substring(schemeMatch.range.last + 1)
    if (rest.any { it == '?' || it == '#' || it == '@' }) return ServerUrlCheck.Malformed
    val slash = rest.indexOf('/')
    val authority = if (slash < 0) rest else rest.substring(0, slash)
    val path = if (slash < 0) "" else rest.substring(slash).trimEnd('/')
    val (host, port) = splitHostPort(authority) ?: return ServerUrlCheck.Malformed
    if (!isValidHost(host)) return ServerUrlCheck.Malformed
    if (scheme == "http" && !isLocalHost(host)) return ServerUrlCheck.NeedsHttps
    val portText = if (port == null) "" else ":$port"
    return ServerUrlCheck.Valid("$scheme://${host.lowercase()}$portText$path")
}

private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

/** `host`, `host:port`, `[v6]` or `[v6]:port`; null when the port is not 0–65535 or the brackets do not close. */
private fun splitHostPort(authority: String): Pair<String, Int?>? {
    val hostEnd = if (authority.startsWith("[")) {
        authority.indexOf(']').takeIf { it > 0 }?.plus(1) ?: return null
    } else {
        authority.indexOf(':').takeIf { it >= 0 } ?: authority.length
    }
    val host = authority.substring(0, hostEnd)
    val after = authority.substring(hostEnd)
    val port = when {
        after.isEmpty() -> null
        after.startsWith(":") -> after.drop(1)
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c in '0'..'9' } }
            ?.toInt()?.takeIf { it <= 65535 }
            ?: return null
        else -> return null
    }
    return host to port
}

private fun isValidHost(host: String): Boolean {
    if (host.isEmpty()) return false
    if (host.startsWith("[")) return host.endsWith("]") && isIpv6Literal(host.substring(1, host.length - 1))
    return host.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '.' } &&
        !host.startsWith(".") && !host.endsWith(".") && !host.startsWith("-")
}

/**
 * A well-formed IPv6 address (RFC 4291, section 2.2): hex groups of one to
 * four digits separated by `:`, eight of them, or fewer with one `::` standing
 * for the missing zeros; the last group may be a dotted IPv4 address
 * (`::ffff:192.168.1.10`), which counts as two groups. No zone id (`%eth0`).
 */
private fun isIpv6Literal(text: String): Boolean {
    if (text.isEmpty() || text.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return false
    val gap = text.indexOf("::")
    if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) return false
    val head = if (gap < 0) text else text.substring(0, gap)
    val tail = if (gap < 0) "" else text.substring(gap + 2)
    val groups = listOf(head, tail).filter { it.isNotEmpty() }.flatMap { it.split(':') }
    if (groups.any { it.isEmpty() }) return false
    var count = 0
    groups.forEachIndexed { index, group ->
        if ('.' in group) {
            if (index != groups.lastIndex || ipv4(group) == null) return false
            count += 2
        } else {
            if (group.length > 4) return false
            count += 1
        }
    }
    return if (gap < 0) count == 8 else count <= 7
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

/**
 * The first two octets of a dotted IPv4 literal, or null if [host] is not one:
 * four decimal octets of 0–255, written without a leading zero (`010.0.0.5`
 * is not an address here; see [checkServerUrl]).
 */
private fun ipv4(host: String): Pair<Int, Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { p ->
        p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.length <= 3 && (p.length == 1 || p[0] != '0') && it in 0..255 } ?: return null
    }
    return octets[0] to octets[1]
}
