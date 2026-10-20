package io.github.kanylbullen.tung.wear

import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import io.github.kanylbullen.tung.core.sync.checkServerUrl

/**
 * Where the watch's workout lives, chosen automatically.
 *
 * - [STANDALONE]: the watch has a server token (it was linked with a code).
 *   Its own Room database is the log, commands run on the watch through the
 *   shared CommandHandler, the rest timer runs on the watch, and it syncs
 *   with the server over its own network like the phone does.
 * - [COMPANION]: no token. The watch mirrors the phone over the Data Layer
 *   and sends it commands, as in v0. Without a paired phone it shows the
 *   hint to open the phone, and Settings offers linking to a server.
 *
 * With both a token and a paired phone, standalone wins and the phone's
 * Data Layer state is ignored: the watch and the phone then meet through
 * the server, never through each other.
 */
enum class WatchMode { STANDALONE, COMPANION }

/** The mode for a watch that does or does not hold a server token. */
fun watchMode(hasToken: Boolean): WatchMode = if (hasToken) WatchMode.STANDALONE else WatchMode.COMPANION

/** Digits in a link code (`POST /api/v1/link/start` on the server). */
const val LINK_CODE_LENGTH = 6

/** What has been typed on the link screen's number pad. Only digits, at most [LINK_CODE_LENGTH]. */
data class LinkCodeInput(val digits: String = "") {
    init {
        require(digits.length <= LINK_CODE_LENGTH && digits.all { it in '0'..'9' }) { "not a partial link code: $digits" }
    }

    val isComplete: Boolean get() = digits.length == LINK_CODE_LENGTH

    /** [digit] appended, or this when it is not a digit or the code is complete. */
    fun type(digit: Char): LinkCodeInput =
        if (digit in '0'..'9' && !isComplete) LinkCodeInput(digits + digit) else this

    fun delete(): LinkCodeInput = LinkCodeInput(digits.dropLast(1))

    companion object {
        /** A pasted or spoken code: digits with any spaces or dashes, or null if that is not six digits. */
        fun parse(text: String): String? =
            text.filterNot { it == ' ' || it == '-' }.takeIf { it.length == LINK_CODE_LENGTH && it.all { c -> c in '0'..'9' } }
    }
}

/**
 * A server typed on the watch. Typing is slow there, so a bare host means
 * https: `tung.example.org` is `https://tung.example.org`. Then the phone's
 * rule, [checkServerUrl] (http only to local addresses).
 */
fun checkWatchServerInput(text: String): ServerUrlCheck {
    val trimmed = text.trim()
    return checkServerUrl(if (trimmed.isNotEmpty() && "://" !in trimmed) "https://$trimmed" else trimmed)
}

/**
 * The servers to offer when linking, in order: the one the phone is signed in
 * to (from the Data Layer, the normal way), then the ones this watch used
 * before, without repeats.
 */
fun serverChoices(fromPhone: String?, recent: List<String>): List<String> =
    (listOfNotNull(fromPhone) + recent).distinct()
