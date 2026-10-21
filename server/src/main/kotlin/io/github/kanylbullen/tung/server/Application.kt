package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.CoreCatalog
import io.github.kanylbullen.tung.server.auth.GoogleIdTokens
import io.github.kanylbullen.tung.server.command.CommandService
import io.github.kanylbullen.tung.server.command.ServerCatalogue
import io.github.kanylbullen.tung.server.mcp.McpReads
import io.github.kanylbullen.tung.server.mcp.TungMcp
import io.github.kanylbullen.tung.core.sync.PushTransport
import io.github.kanylbullen.tung.server.oauth.OAuthStore
import io.github.kanylbullen.tung.server.push.DisabledPushSender
import io.github.kanylbullen.tung.server.push.EndpointGuard
import io.github.kanylbullen.tung.server.push.FcmSender
import io.github.kanylbullen.tung.server.push.PushService
import io.github.kanylbullen.tung.server.push.PushStore
import io.github.kanylbullen.tung.server.push.UnifiedPushSender
import io.github.kanylbullen.tung.server.push.pushHttpClient
import io.github.kanylbullen.tung.server.routes.ExchangeTransport
import io.github.kanylbullen.tung.server.routes.McpSessions
import io.github.kanylbullen.tung.server.routes.commandRoutes
import io.github.kanylbullen.tung.server.routes.mcpRoutes
import io.github.kanylbullen.tung.server.routes.oauthRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
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
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

/** The application's [PushService], for tests that wait for pushes to settle. */
val PushServiceKey = AttributeKey<PushService>("PushService")

/**
 * The whole application. [db] is opened from [config] unless given; it is
 * closed when the application stops.
 */
fun Application.module(config: ServerConfig, db: Db = Db.connect(config)) {
    // Push (issue #34): an empty "sync now" to the user's other devices after every change.
    // Never to an internal address unless the instance allows it (TUNG_PUSH_ALLOW_PRIVATE): at registration and at every send.
    val pushGuard = EndpointGuard(config.pushAllowPrivate, config.pushResolver)
    val pushStore = PushStore(db, pushGuard)
    val pushHttp = pushHttpClient()
    val push = PushService(
        pushStore,
        listOf(
            UnifiedPushSender(pushHttp, pushGuard),
            config.fcmServiceAccount?.let { FcmSender(it, pushHttp, config.fcmBaseUrl) } ?: DisabledPushSender(PushTransport.FCM),
        ),
        config.pushDebounceMillis,
    )
    attributes.put(PushServiceKey, push)
    monitor.subscribe(ApplicationStopped) {
        push.close()
        db.close()
    }

    // Sign in with Google (issue #51), only with a client id.
    val google = config.googleClientIds.takeIf { it.isNotEmpty() }?.let { GoogleIdTokens(it, config.googleJwksUrl, config.clock) }
    val accounts = Accounts(db, google)
    val exercises = ExerciseStore(db)
    val workouts = WorkoutStore(db, exercises)

    val catalogue = Application::class.java.getResourceAsStream("/catalog/core.json")
        ?.use { CoreCatalog.parse(it.readBytes().toString(Charsets.UTF_8)) }
        ?: error("catalog/core.json is missing from the classpath")
    exercises.seedCatalogue(db.database, catalogue)
    val routines = RoutineStore(db, exercises)
    val sync = SyncStore(db, catalogue)

    // Commands, OAuth and MCP (issue #29): every change an assistant or a script makes goes through the one command handler.
    val serverCatalogue = ServerCatalogue.fromClasspath()
    val commands = CommandService(db, serverCatalogue, exercises, workouts, notifier = push)
    val oauth = OAuthStore(db)
    // Registration is open, so unused clients are deleted: once at start, then daily (OAuthStore.sweepUnusedClients).
    val oauthSweep = launch(Dispatchers.IO) {
        while (isActive) {
            try {
                val deleted = oauth.sweepUnusedClients()
                if (deleted > 0) log.info("Deleted {} OAuth client(s) unused for 90 days", deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("The sweep of unused OAuth clients failed; it runs again in 24 h", e)
            }
            delay(OAuthStore.CLIENT_SWEEP_INTERVAL_MS)
        }
    }
    monitor.subscribe(ApplicationStopping) { oauthSweep.cancel() }
    val mcp = TungMcp(commands, McpReads(db, serverCatalogue, workouts, routines))
    val mcpTransport = ExchangeTransport().also { runBlocking { mcp.server.connect(it) } }
    val mcpSessions = McpSessions()

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
        // Streamable HTTP, for MCP clients that run in a browser.
        allowHeader("Mcp-Session-Id")
        allowHeader("MCP-Protocol-Version")
        exposeHeader("Mcp-Session-Id")
        exposeHeader(HttpHeaders.WWWAuthenticate)
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

    routing { apiRoutes(config, db, accounts, exercises, workouts, routines, sync, pushStore, push, commands) }
    routing {
        commandRoutes(commands)
        oauthRoutes(config, accounts, oauth)
        mcpRoutes(config, accounts, mcp, mcpTransport, mcpSessions)
    }

    log.info("tung-server ready{}", config.publicUrl?.let { " at $it" } ?: "")
}
