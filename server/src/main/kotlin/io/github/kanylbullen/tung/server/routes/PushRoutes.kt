package io.github.kanylbullen.tung.server.routes

import io.github.kanylbullen.tung.core.sync.PUSH_ENDPOINTS_PATH
import io.github.kanylbullen.tung.core.sync.PushEndpointList
import io.github.kanylbullen.tung.core.sync.PushEndpointRequest
import io.github.kanylbullen.tung.server.TokenPrincipal
import io.github.kanylbullen.tung.server.badRequest
import io.github.kanylbullen.tung.server.push.PushStore
import io.github.kanylbullen.tung.server.unauthorized
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Push endpoints (issue #34), mounted by `apiRoutes` under `/api/v1` inside
 * its bearer block, so a `read` token gets 403 on POST and DELETE there:
 *
 * - `POST /api/v1/push/endpoints` [PushEndpointRequest]: registers the
 *   endpoint of `device_id`, or replaces that device's transport and
 *   endpoint (same id, revocation cleared). 201 when new, 200 when replaced.
 * - `GET /api/v1/push/endpoints`: the caller's active endpoints.
 * - `DELETE /api/v1/push/endpoints/{id}`: revokes one; another user's or an
 *   unknown id is 404. 204.
 */
fun Route.pushRoutes(store: PushStore) {
    route(PUSH_ENDPOINTS_PATH.removePrefix("/api/v1")) {
        post {
            val registered = store.register(call.userId(), call.receive<PushEndpointRequest>())
            call.respond(if (registered.created) HttpStatusCode.Created else HttpStatusCode.OK, registered.endpoint)
        }
        get { call.respond(PushEndpointList(store.list(call.userId()))) }
        delete("/{id}") {
            val id = call.parameters["id"] ?: throw badRequest("Missing path parameter id.")
            store.revoke(call.userId(), id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.userId(): String =
    principal<TokenPrincipal>()?.userId ?: throw unauthorized("unauthorized", "Send a valid API token as 'Authorization: Bearer tung_…'.")
