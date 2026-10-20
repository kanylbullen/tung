package io.github.kanylbullen.tung.server

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
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset

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

private fun ApplicationCall.user(): TokenPrincipal =
    principal<TokenPrincipal>() ?: throw unauthorized("unauthorized", "Send a valid API token as 'Authorization: Bearer tung_…'.")

private fun ApplicationCall.pathId(name: String = "id"): String =
    parameters[name] ?: throw badRequest("Missing path parameter $name.")

/**
 * Every route the server has. Each one must also be in
 * resources/openapi/openapi.yaml; OpenApiCoverageTest fails otherwise.
 */
fun Route.apiRoutes(config: ServerConfig, db: Db, accounts: Accounts, exercises: ExerciseStore, workouts: WorkoutStore) {
    get("/healthz") {
        val ok = runCatching { db.tx { exec("SELECT 1") { it.next() } } }.getOrNull() == true
        call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, Health(if (ok) "ok" else "database_unavailable"))
    }

    val spec = openApiSpec(config.publicUrl)
    get("/openapi.yaml") { call.respondText(spec, ContentType.parse("application/yaml")) }
    swaggerUI(path = "docs", swaggerFile = "openapi/openapi.yaml")

    route("/api/v1") {
        rateLimit(AUTH_RATE_LIMIT) {
            post("/auth/register") { call.respond(HttpStatusCode.Created, accounts.register(call.receive())) }
            post("/auth/login") { call.respond(accounts.login(call.receive())) }
        }

        authenticate(BEARER) {
            install(RequireWriteScope)

            post("/auth/logout") {
                val principal = call.user()
                accounts.revokeToken(principal.userId, principal.tokenId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/me") { call.respond(accounts.me(call.user().userId)) }

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
                post { call.respond(HttpStatusCode.Created, exercises.create(call.user().userId, call.receive())) }
                patch("/{id}") { call.respond(exercises.patch(call.user().userId, call.pathId(), call.receive<JsonObject>())) }
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
                put("/{id}") { call.respond(workouts.put(call.user().userId, call.pathId(), call.receive())) }
                delete("/{id}") {
                    workouts.delete(call.user().userId, call.pathId())
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
                    call.respond(workouts.finish(call.user().userId, call.pathId(), input))
                }
            }

            get("/export") {
                val userId = call.user().userId
                val now = System.currentTimeMillis()
                val document = ExportDocument(
                    exportedAt = now,
                    user = accounts.me(userId),
                    exercises = exercises.own(userId),
                    workouts = workouts.all(userId),
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
    }
}

private const val DEFAULT_PAGE = 20
private const val MAX_PAGE = 100

/** openapi.yaml from the classpath, naming [publicUrl] as its server when one is configured. */
fun openApiSpec(publicUrl: String?): String {
    val text = object {}.javaClass.getResource("/openapi/openapi.yaml")?.readText()
        ?: error("openapi/openapi.yaml is missing from the classpath")
    return if (publicUrl == null) text else text.replace("\n  - url: /\n", "\n  - url: $publicUrl\n")
}
