package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.sync.SYNC_MAX_ROWS
import io.github.kanylbullen.tung.core.sync.SYNC_PATH
import io.github.kanylbullen.tung.core.sync.SyncRequest
import io.github.kanylbullen.tung.server.command.CommandService
import io.github.kanylbullen.tung.server.push.ChangeNotifier
import io.github.kanylbullen.tung.server.push.PushStore
import io.github.kanylbullen.tung.server.push.sourceDevice
import io.github.kanylbullen.tung.server.routes.Testers
import io.github.kanylbullen.tung.server.routes.linkRoutes
import io.github.kanylbullen.tung.server.routes.programRoutes
import io.github.kanylbullen.tung.server.routes.pushRoutes
import io.github.kanylbullen.tung.server.routes.routineRoutes
import io.github.kanylbullen.tung.server.routes.testerAdminRoutes
import io.github.kanylbullen.tung.server.routes.testerSignupRoute
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

const val BEARER = "bearer"
val AUTH_RATE_LIMIT = RateLimitName("auth")

/** Read tokens may only read: anything but GET/HEAD/OPTIONS with one is refused. */
private val RequireWriteScope = createRouteScopedPlugin("RequireWriteScope") {
    on(AuthenticationChecked) { call ->
        val principal = call.principal<TokenPrincipal>() ?: return@on
        val reading = call.request.httpMethod in setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)
        if (!reading && principal.scope != Tokens.SCOPE_WRITE) {
            throw forbidden("insufficient_scope", "This token is read-only. Use a token with scope 'write' to change data.")
        }
    }
}

internal fun ApplicationCall.user(): TokenPrincipal =
    principal<TokenPrincipal>() ?: throw unauthorized("unauthorized", "Send a valid API token as 'Authorization: Bearer tung_…'.")

internal fun ApplicationCall.pathId(name: String = "id"): String =
    parameters[name] ?: throw badRequest("Missing path parameter $name.")

/**
 * Every route the server has. Each one must also be in
 * resources/openapi/openapi.yaml; OpenApiCoverageTest fails otherwise.
 */
fun Route.apiRoutes(
    config: ServerConfig,
    db: Db,
    accounts: Accounts,
    exercises: ExerciseStore,
    workouts: WorkoutStore,
    routines: RoutineStore,
    sync: SyncStore,
    pushStore: PushStore,
    push: ChangeNotifier,
    commands: CommandService,
) {
    /** After a REST write to training data: wake the user's other devices. */
    fun ApplicationCall.changed() = push.changed(user().userId, sourceDevice())

    get("/healthz") {
        val ok = runCatching { db.tx { exec("SELECT 1") { it.next() } } }.getOrNull() == true
        call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, Health(if (ok) "ok" else "database_unavailable"))
    }

    val spec = openApiSpec(config.publicUrl)
    get("/openapi.yaml") { call.respondText(spec, ContentType.parse("application/yaml")) }
    swaggerUI(path = "docs", swaggerFile = "openapi/openapi.yaml")

    val testers = Testers(db, config.adminEmails, config.clock)
    route("/api/v1") {
        linkRoutes(db, accounts)
        rateLimit(AUTH_RATE_LIMIT) {
            testerSignupRoute(testers)
            post("/auth/register") { call.respond(HttpStatusCode.Created, accounts.register(call.receive())) }
            post("/auth/login") { call.respond(accounts.login(call.receive())) }
            post("/auth/google") { call.respond(accounts.signInWithGoogle(call.receive<GoogleSignIn>().idToken)) }
        }
        get("/auth/methods") {
            call.respond(AuthMethods(password = true, google = accounts.google?.let { GoogleMethod(it.clientIds.first()) }))
        }

        authenticate(BEARER) {
            install(RequireWriteScope)

            post("/auth/logout") {
                val principal = call.user()
                accounts.revokeToken(principal.userId, principal.tokenId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/me") { call.respond(accounts.me(call.user().userId)) }

            testerAdminRoutes(testers)

            route("/tokens") {
                get { call.respond(accounts.listTokens(call.user())) }
                post { call.respond(HttpStatusCode.Created, accounts.createToken(call.user().userId, call.receive())) }
                delete("/{id}") {
                    accounts.revokeToken(call.user().userId, call.pathId())
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/exercises") {
                get { call.respond(ExerciseList(exercises.list(call.user().userId))) }
                post {
                    val created = exercises.create(call.user().userId, call.receive())
                    call.changed()
                    call.respond(HttpStatusCode.Created, created)
                }
                patch("/{id}") {
                    val patched = exercises.patch(call.user().userId, call.pathId(), call.receive<JsonObject>())
                    call.changed()
                    call.respond(patched)
                }
                get("/{id}/last") {
                    val exclude = call.request.queryParameters["exclude_workout_id"]
                    call.respond(workouts.lastSession(call.user().userId, call.pathId(), exclude))
                }
            }

            route("/workouts") {
                get {
                    val limit = call.request.queryParameters["limit"]?.let {
                        it.toIntOrNull()?.takeIf { n -> n in 1..MAX_PAGE } ?: throw badRequest("limit must be 1 to $MAX_PAGE.")
                    } ?: DEFAULT_PAGE
                    val before = call.request.queryParameters["before"]?.let {
                        it.toLongOrNull() ?: throw badRequest("before must be epoch milliseconds.")
                    }
                    call.respond(workouts.page(call.user().userId, limit, before))
                }
                get("/{id}") { call.respond(workouts.get(call.user().userId, call.pathId())) }
                put("/{id}") {
                    val stored = workouts.put(call.user().userId, call.pathId(), call.receive())
                    call.changed()
                    call.respond(stored)
                }
                delete("/{id}") {
                    workouts.delete(call.user().userId, call.pathId())
                    call.changed()
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/finish") {
                    val input = if (call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > 0 } == true ||
                        call.request.headers[HttpHeaders.TransferEncoding] != null
                    ) {
                        call.receive<FinishInput>()
                    } else {
                        FinishInput()
                    }
                    val result = workouts.finish(call.user().userId, call.pathId(), input)
                    call.changed()
                    call.respond(result)
                }
            }

            routineRoutes(routines, commands, push)
            programRoutes(routines, commands, push)

            pushRoutes(pushStore)

            get("/export") {
                val userId = call.user().userId
                val now = System.currentTimeMillis()
                val document = ExportDocument(
                    exportedAt = now,
                    user = accounts.me(userId),
                    exercises = exercises.own(userId),
                    workouts = workouts.all(userId),
                    routines = routines.routines(userId),
                    programs = routines.programs(userId),
                    tokens = accounts.exportTokens(userId),
                )
                val date = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate()
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "tung-export-$date.json").toString(),
                )
                call.respond(document)
            }
        }

        // Outside RequireWriteScope: a read token may pull (empty changes); the handler refuses its pushes.
        authenticate(BEARER) {
            post(SYNC_PATH.removePrefix("/api/v1")) {
                val principal = call.user()
                val request = call.receiveSyncRequest()
                if (!request.changes.isEmpty() && principal.scope != Tokens.SCOPE_WRITE) {
                    throw forbidden("insufficient_scope", "This token is read-only: it may pull with empty changes, not push. Use a token with scope 'write'.")
                }
                val response = sync.sync(principal.userId, request)
                // Only a request that pushed rows is a write: a pull-only sync (every device's periodic one) wakes nobody.
                if (!request.changes.isEmpty()) push.changed(principal.userId, call.sourceDevice())
                call.respond(response)
            }
        }
    }
}

/** The largest sync request body: 20 000 rows of the widest table fit with room to spare. */
const val SYNC_MAX_BODY_BYTES = 8L * 1024 * 1024

/**
 * Reads the body itself instead of `receive()`, to stop at
 * [SYNC_MAX_BODY_BYTES] (413) before parsing, and to answer more than
 * [SYNC_MAX_ROWS] rows with 413 too.
 */
private suspend fun ApplicationCall.receiveSyncRequest(): SyncRequest {
    val tooLarge = { payloadTooLarge("payload_too_large", "A sync request can be at most ${SYNC_MAX_BODY_BYTES / (1024 * 1024)} MiB. Push in smaller batches.") }
    request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { if (it > SYNC_MAX_BODY_BYTES) throw tooLarge() }
    val bytes = receiveChannel().readRemaining(SYNC_MAX_BODY_BYTES + 1).readByteArray()
    if (bytes.size > SYNC_MAX_BODY_BYTES) throw tooLarge()
    val request = try {
        ApiJson.decodeFromString(SyncRequest.serializer(), bytes.decodeToString())
    } catch (e: SerializationException) {
        throw badRequest("The request body could not be read: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw badRequest("The request body could not be read: ${e.message}")
    }
    if (request.changes.size() > SYNC_MAX_ROWS) {
        throw payloadTooLarge("too_many_rows", "A sync request can push at most $SYNC_MAX_ROWS rows, got ${request.changes.size()}. Push in batches.")
    }
    return request
}

private const val DEFAULT_PAGE = 20
private const val MAX_PAGE = 100

/** openapi.yaml from the classpath, naming [publicUrl] as its server when one is configured. */
fun openApiSpec(publicUrl: String?): String {
    val text = object {}.javaClass.getResource("/openapi/openapi.yaml")?.readText()
        ?: error("openapi/openapi.yaml is missing from the classpath")
    return if (publicUrl == null) text else text.replace("\n  - url: /\n", "\n  - url: $publicUrl\n")
}
