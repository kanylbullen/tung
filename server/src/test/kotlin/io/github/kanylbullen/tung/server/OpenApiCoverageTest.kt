package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.sync.PushTransport
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.testApplication
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spec cannot rot: every route registered in the application must be in
 * openapi.yaml with the same method, and every operation in openapi.yaml must
 * exist as a route. Only Swagger UI's own pages under /docs are exempt.
 */
class OpenApiCoverageTest {

    private val spec: Map<String, Any> = Yaml().load(javaClass.getResource("/openapi/openapi.yaml")!!.readText())

    private val httpMethods = setOf("get", "put", "post", "delete", "patch", "head", "options")

    @Suppress("UNCHECKED_CAST")
    private fun specOperations(): Set<String> =
        (spec["paths"] as Map<String, Map<String, Any>>).flatMap { (path, item) ->
            item.keys.filter { it in httpMethods }.map { "${it.uppercase()} $path" }
        }.toSet()

    private fun describe(node: RoutingNode): String? {
        var method: String? = null
        val segments = ArrayDeque<String>()
        var current: RoutingNode? = node
        while (current != null) {
            when (val selector = current.selector) {
                is HttpMethodRouteSelector -> method = selector.method.value
                is PathSegmentConstantRouteSelector -> segments.addFirst(selector.value)
                is PathSegmentParameterRouteSelector -> segments.addFirst("{${selector.name}}")
                else -> Unit // root, authenticate, rate limit, trailing slash: not part of the path
            }
            current = current.parent
        }
        return method?.let { "$it /${segments.joinToString("/")}" }
    }

    @Test
    fun everyRouteIsInTheSpecAndEveryOperationIsARoute() = testApplication {
        environment { config = MapApplicationConfig() }
        lateinit var app: Application
        application {
            module(TestDatabase.config())
            app = this
        }
        startApplication()

        val routes = app.plugin(RoutingRoot).getAllRoutes()
            .mapNotNull(::describe)
            .filterNot { it.substringAfter(' ').startsWith("/docs") }
            // The landing page and its assets, and the web app: pages, not API (SiteRoutes.kt).
            .filterNot { it.substringAfter(' ').let { path -> path == "/" || path.startsWith("/site") || path.startsWith("/app") } }
            .toSet()
        val operations = specOperations()

        assertTrue(routes.size >= 15, "found the routes: $routes")
        assertEquals(emptySet(), routes - operations, "routes missing from openapi.yaml")
        assertEquals(emptySet(), operations - routes, "operations in openapi.yaml that the server does not have")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun enumsMatchCore() {
        val schemas = (spec["components"] as Map<String, Any>)["schemas"] as Map<String, Map<String, Any>>
        assertEquals(MuscleGroup.entries.map { it.key }, schemas["MuscleGroup"]!!["enum"])
        assertEquals(Equipment.entries.map { it.key }, schemas["Equipment"]!!["enum"])
        assertEquals(ProgramMode.entries.map { it.key }, schemas["ProgramMode"]!!["enum"])
        assertEquals(StartedBy.ALL, (schemas["StartedBy"]!!["enum"] as List<String>).toSet())
        assertEquals(PushTransport.ALL, (schemas["PushTransport"]!!["enum"] as List<String>).toSet())
        assertEquals("3.1.0", spec["openapi"])
    }
}
