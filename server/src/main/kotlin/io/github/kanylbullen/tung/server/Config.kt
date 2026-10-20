package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.server.push.FCM_BASE_URL
import io.github.kanylbullen.tung.server.push.FcmServiceAccount
import java.io.File

/** A configuration problem the operator has to fix; main prints the message and exits. */
class ConfigException(message: String) : RuntimeException(message)

/**
 * Everything the server reads from its environment. See server/README.md for
 * the variables.
 */
data class ServerConfig(
    /** JDBC URL, `jdbc:postgresql://host:5432/db`. */
    val databaseUrl: String,
    val databaseUser: String? = null,
    val databasePassword: String? = null,
    val port: Int = DEFAULT_PORT,
    /** The public base URL (`https://tung.example.org`); the served OpenAPI spec names it as its server. */
    val publicUrl: String? = null,
    /** Behind a reverse proxy: take the client address from X-Forwarded-For / Forwarded, for the rate limit and the log. */
    val trustProxy: Boolean = false,
    /** Requests per minute and client address to register and login. */
    val authRequestsPerMinute: Int = DEFAULT_AUTH_REQUESTS_PER_MINUTE,
    /** The Firebase service account for FCM push (`TUNG_FCM_SERVICE_ACCOUNT_JSON`); null turns FCM off. */
    val fcmServiceAccount: FcmServiceAccount? = null,
    /** FCM's API base. Not an environment variable: only tests point it elsewhere. */
    val fcmBaseUrl: String = FCM_BASE_URL,
    /** How long changes are collected before one push goes out per user. Not an environment variable: tests shorten it. */
    val pushDebounceMillis: Long = DEFAULT_PUSH_DEBOUNCE_MILLIS,
) {
    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_AUTH_REQUESTS_PER_MINUTE = 10
        const val DEFAULT_PUSH_DEBOUNCE_MILLIS = 2_000L

        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            fun value(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

            val url = value("DATABASE_URL") ?: throw ConfigException(
                "DATABASE_URL is not set. tung-server needs Postgres: set DATABASE_URL to a JDBC URL such as " +
                    "jdbc:postgresql://localhost:5432/tung, plus DATABASE_USER and DATABASE_PASSWORD.",
            )
            if (!url.startsWith("jdbc:postgresql:")) {
                throw ConfigException("DATABASE_URL must be a Postgres JDBC URL starting with jdbc:postgresql:, got '$url'.")
            }
            val port = value("PORT")?.let {
                it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: throw ConfigException("PORT must be a port number, got '$it'.")
            } ?: DEFAULT_PORT
            val publicUrl = value("TUNG_PUBLIC_URL")?.trimEnd('/')
            val trustProxy = when (val raw = value("TUNG_TRUST_PROXY")?.lowercase()) {
                null, "false", "0", "no" -> false
                "true", "1", "yes" -> true
                else -> throw ConfigException("TUNG_TRUST_PROXY must be true or false, got '$raw'.")
            }
            return ServerConfig(
                databaseUrl = url,
                databaseUser = value("DATABASE_USER"),
                databasePassword = env["DATABASE_PASSWORD"],
                port = port,
                publicUrl = publicUrl,
                trustProxy = trustProxy,
                fcmServiceAccount = value(FCM_ENV)?.let(::fcmServiceAccount),
            )
        }

        private const val FCM_ENV = "TUNG_FCM_SERVICE_ACCOUNT_JSON"

        /** [raw] is the JSON itself when it starts with `{`, else a path to the file. */
        private fun fcmServiceAccount(raw: String): FcmServiceAccount {
            if (raw.startsWith("{")) return FcmServiceAccount.parse(raw, FCM_ENV)
            val file = File(raw)
            val text = try {
                file.readText()
            } catch (e: Exception) {
                throw ConfigException(
                    "$FCM_ENV is '$raw', which cannot be read (${e.javaClass.simpleName}). " +
                        "Set it to the path of the Firebase service-account JSON inside the container, or to the JSON itself.",
                )
            }
            return FcmServiceAccount.parse(text, "$FCM_ENV ($raw)")
        }
    }
}
