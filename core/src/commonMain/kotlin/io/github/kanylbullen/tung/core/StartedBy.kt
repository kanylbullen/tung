package io.github.kanylbullen.tung.core

/**
 * Who started a workout, as stored in `Workout.startedBy` and synced as
 * `started_by`. Each client's command path stamps its own value when it
 * creates a workout; the ongoing notification says "Started by your
 * assistant" for [AGENT]. A stored value is never changed afterwards.
 */
object StartedBy {
    /** The phone app's screens. */
    const val PHONE = "phone"

    /** The watch: standalone, or a companion watch's command run on the phone. */
    const val WATCH = "watch"

    /** Reserved for a workout the server makes on its own; nothing sets it yet. */
    const val SERVER = "server"

    /** The server's command handler: REST commands and MCP tools, i.e. an assistant or a script. */
    const val AGENT = "agent"

    /**
     * The web app (`web/`). A server older than this value refuses a synced
     * workout that carries it (`SyncValidation`), so the server is deployed
     * before the web starts workouts.
     */
    const val WEB = "web"

    val ALL: Set<String> = setOf(PHONE, WATCH, SERVER, AGENT, WEB)
}
