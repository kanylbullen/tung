package io.github.kanylbullen.tung.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** The cap on every request body that no route raises or lowers: JSON edits of a workout, a routine, an exercise. */
const val DEFAULT_MAX_BODY_BYTES = 1L * 1024 * 1024

/** The cap on the routes anyone may call without a token: sign-in, registration, link claims, tester sign-ups, OAuth. */
const val PUBLIC_MAX_BODY_BYTES = 64L * 1024

class BodyLimitConfig {
    var maxBytes: Long = DEFAULT_MAX_BODY_BYTES

    /** The error a body over [maxBytes] answers; a route whose clients read the code may name its own. */
    var tooLarge: (maxBytes: Long) -> ApiException = ::defaultTooLarge
}

private fun defaultTooLarge(maxBytes: Long) = payloadTooLarge(
    "payload_too_large",
    if (maxBytes >= 1024 * 1024) {
        "The request body can be at most ${maxBytes / (1024 * 1024)} MiB."
    } else {
        "The request body can be at most ${maxBytes / 1024} KiB."
    },
)

/**
 * The request body as it enters the receive pipeline, before any converter
 * sees it: [ReceiveRequestBytes][io.ktor.server.application.hooks.ReceiveRequestBytes]
 * with a suspending handler, so the body can be read here.
 */
private object ReceiveBody : Hook<suspend (ApplicationCall, ByteReadChannel) -> ByteReadChannel> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (ApplicationCall, ByteReadChannel) -> ByteReadChannel) {
        pipeline.receivePipeline.intercept(ApplicationReceivePipeline.Before) { body ->
            if (body !is ByteReadChannel) return@intercept
            proceedWith(handler(context, body))
        }
    }
}

/**
 * Caps the request body of the routes it is installed on (issue #149). A
 * Content-Length over the cap is 413 before the handler runs, so nothing is
 * read or parsed; without one (chunked), the body is read only one byte past
 * the cap and answers 413 too. Every reader buffers the whole body anyway
 * (the JSON parser, a form, `readRemaining`), so reading it here once costs
 * nothing more, and the error comes out of `receive()` as the [ApiException]
 * itself, before a converter could wrap it.
 *
 * Installed at the routing root with [DEFAULT_MAX_BODY_BYTES]; [bodyLimit]
 * sets another cap for a subtree. The nearest installation wins: Ktor runs a
 * route-scoped plugin's interceptors only for the instance closest to the
 * call's route.
 */
val RequestBodyLimit = createRouteScopedPlugin("RequestBodyLimit", ::BodyLimitConfig) {
    val maxBytes = pluginConfig.maxBytes
    val tooLarge = pluginConfig.tooLarge
    onCall { call ->
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length > maxBytes) throw tooLarge(maxBytes)
    }
    on(ReceiveBody) { _, body ->
        val bytes = body.readRemaining(maxBytes + 1).readByteArray()
        if (bytes.size > maxBytes) throw tooLarge(maxBytes)
        ByteReadChannel(bytes)
    }
}

/** A transparent child route, compared by identity so that two caps side by side stay two routes. */
private class BodyLimitSelector(private val maxBytes: Long) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
    override fun toString() = "(body limit $maxBytes)"
}

/** The routes in [build] take a body of at most [maxBytes]; [tooLarge] is the 413 they answer past it. */
fun Route.bodyLimit(
    maxBytes: Long,
    tooLarge: (maxBytes: Long) -> ApiException = ::defaultTooLarge,
    build: Route.() -> Unit,
): Route {
    val route = createChild(BodyLimitSelector(maxBytes))
    route.install(RequestBodyLimit) {
        this.maxBytes = maxBytes
        this.tooLarge = tooLarge
    }
    route.build()
    return route
}
