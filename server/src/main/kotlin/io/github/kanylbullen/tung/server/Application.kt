package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.CoreCatalog
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import org.slf4j.event.Level
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

fun main() {
    val config = try {
        ServerConfig.fromEnv()
    } catch (e: ConfigException) {
        System.err.println("tung-server: ${e.message}")
        exitProcess(1)
    }
    val db = try {
        Db.connect(config)
    } catch (e: ConfigException) {
        System.err.println("tung-server: ${e.message}")
        exitProcess(1)
    }
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config, db) }.start(wait = true)
}

/**
 * The whole application. [db] is opened from [config] unless given; it is
 * closed when the application stops.
 */
fun Application.module(config: ServerConfig, db: Db = Db.connect(config)) {
    monitor.subscribe(ApplicationStopped) { db.close() }

    val accounts = Accounts(db)
    val exercises = ExerciseStore(db)
    val workouts = WorkoutStore(db, exercises)

    val catalogue = Application::class.java.getResourceAsStream("/catalog/core.json")
        ?.use { CoreCatalog.parse(it.readBytes().toString(Charsets.UTF_8)) }
        ?: error("catalog/core.json is missing from the classpath")
    exercises.seedCatalogue(db.database, catalogue)

    install(ContentNegotiation) { json(ApiJson) }
    if (config.trustProxy) {
        // The last hop is the address our proxy saw; earlier entries come from the client and can be forged.
        install(XForwardedHeaders) { useLastProxy() }
        install(ForwardedHeaders) { useLastValue() }
    }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path() != "/healthz" }
    }
    // Bearer tokens, no cookies: any origin may call the API with a token it holds.
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = config.authRequestsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
    install(Authentication) {
        bearer(BEARER) {
            realm = "tung"
            authenticate { credential -> accounts.authenticate(credential.token) }
        }
    }
    installErrorPages()

    routing { apiRoutes(config, db, accounts, exercises, workouts) }

    log.info("tung-server ready{}", config.publicUrl?.let { " at $it" } ?: "")
}
