package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.command.CommandNames
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.server.mcp.McpDescriptions
import io.github.kanylbullen.tung.server.mcp.TungMcp
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private suspend fun HttpClient.mcp(token: String?, body: String, session: String? = null, accept: String = "application/json, text/event-stream"): HttpResponse =
    post("/mcp") {
        token?.let { bearerAuth(it) }
        header(HttpHeaders.Accept, accept)
        session?.let { header("Mcp-Session-Id", it) }
        jsonBody(body)
    }

private suspend fun HttpClient.rpc(token: String, method: String, params: String = "{}", session: String? = null): JsonObject {
    val response = mcp(token, """{"jsonrpc": "2.0", "id": 7, "method": "$method", "params": $params}""", session)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    val body = response.json()
    assertEquals(7, body["id"]!!.jsonPrimitive.content.toInt())
    return assertNotNull(body["result"], "$method failed: $body").jsonObject
}

/** Calls a tool; returns the result's JSON text parsed, and whether it is an error. */
private suspend fun HttpClient.tool(token: String, name: String, arguments: String = "{}"): Pair<JsonObject?, Boolean> {
    val result = rpc(token, "tools/call", """{"name": "$name", "arguments": $arguments}""")
    val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.str
    val isError = result["isError"]?.jsonPrimitive?.boolean ?: false
    return runCatching { json(text) }.getOrNull() to isError
}

private suspend fun HttpClient.toolOk(token: String, name: String, arguments: String = "{}"): JsonObject {
    val (json, isError) = tool(token, name, arguments)
    assertFalse(isError, "$name returned an error: $json")
    return assertNotNull(json)
}

private const val INITIALIZE =
    """{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}}}"""

class McpTest {

    @Test
    fun everyCommandHasADescription() {
        assertEquals(CommandNames.all.values.toSet(), McpDescriptions.describedCommands)
    }

    @Test
    fun withoutATokenThe401LeadsToOAuth() = tungTest {
        val response = client.mcp(null, INITIALIZE)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(
            "Bearer resource_metadata=\"http://localhost/.well-known/oauth-protected-resource\"",
            response.headers[HttpHeaders.WWWAuthenticate],
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/mcp").status)
        val bad = client.mcp("tung_nonsense", INITIALIZE)
        assertTrue(bad.headers[HttpHeaders.WWWAuthenticate]!!.contains("error=\"invalid_token\""))
    }

    @Test
    fun initializeListsEveryCommandAndTheReadTools() = tungTest {
        val token = client.register()
        val init = client.mcp(token, INITIALIZE)
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val session = assertNotNull(init.headers["Mcp-Session-Id"])
        val result = init.json()["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.str)
        assertEquals("tung", result["serverInfo"]!!.jsonObject["name"]!!.str)
        assertEquals("0.1.0", result["serverInfo"]!!.jsonObject["version"]!!.str, "the version from the build")
        val capabilities = result["capabilities"]!!.jsonObject
        assertTrue("tools" in capabilities && "prompts" in capabilities && "resources" in capabilities)
        assertFalse("logging" in capabilities, "nothing advertised that the server does not do")

        val notified = client.mcp(token, """{"jsonrpc": "2.0", "method": "notifications/initialized"}""", session)
        assertEquals(HttpStatusCode.Accepted, notified.status)

        val tools = client.rpc(token, "tools/list", session = session)["tools"]!!.jsonArray.map { it.jsonObject }
        val names = tools.map { it["name"]!!.str }
        assertEquals((CommandNames.all.values + TungMcp.READ_TOOLS).toSet(), names.toSet())
        val applyPlan = tools.single { it["name"]!!.str == "apply_plan" }
        assertTrue(applyPlan["description"]!!.str.contains("set_count"), "apply_plan carries the plan schema")
        assertEquals("array", applyPlan["inputSchema"]!!.jsonObject["properties"]!!.jsonObject["exercises"]!!.jsonObject["type"]!!.str)
        val logSet = tools.single { it["name"]!!.str == "log_set" }
        assertEquals(listOf("workout_id"), logSet["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.str })

        val old = client.mcp(token, INITIALIZE.replace("2025-06-18", "2024-11-05")).json()["result"]!!.jsonObject
        assertEquals("2024-11-05", old["protocolVersion"]!!.str, "an older client gets its version")
        val future = client.mcp(token, INITIALIZE.replace("2025-06-18", "2099-01-01")).json()["result"]!!.jsonObject
        assertEquals("2025-06-18", future["protocolVersion"]!!.str)
    }

    @Test
    fun aSetLoggedThroughMcpIsVisibleOverRest() = tungTest {
        val token = client.register()
        val workoutId = client.toolOk(token, "start_workout")["ids"]!!.jsonObject["workoutId"]!!.str
        client.toolOk(token, "apply_plan", """{"exercises": [{"exercise": {"key": "bench_press"}, "set_count": 2, "reps": 8, "weight_kg": 60}]}""")

        val current = client.toolOk(token, "current_workout")["workout"]!!.jsonObject
        assertEquals(workoutId, current["id"]!!.str)
        val exercise = current["exercises"]!!.jsonArray.single().jsonObject
        assertEquals("Bench press", exercise["name"]!!.str)
        val firstSet = exercise["sets"]!!.jsonArray[0].jsonObject["set_id"]!!.str
        assertEquals(firstSet, current["current_set_id"]!!.str)

        val logged = client.toolOk(token, "log_set", """{"workout_id": "$workoutId"}""")
        assertEquals("done", logged["status"]!!.str)
        assertEquals(firstSet, logged["ids"]!!.jsonObject["setId"]!!.str)

        val viaRest = client.authedGet("/api/v1/workouts/$workoutId", token).workout()
        assertEquals(listOf(true, false), viaRest.exercises.single().sets.map { it.completed })

        client.toolOk(token, "finish_workout", """{"workout_id": "$workoutId"}""")
        val history = client.toolOk(token, "history")["workouts"]!!.jsonArray
        assertEquals(workoutId, history.single().jsonObject["id"]!!.str)
        val last = client.toolOk(token, "last_time", """{"exercise": "Bänkpress"}""")
        assertEquals(listOf(60.0 to 8), last["sets"]!!.jsonArray.map { it.jsonObject }.map { it["weight_kg"]!!.str.toDouble() to it["reps"]!!.str.toInt() })
    }

    /** Heart rate and calories from the phone's Health Connect (V9) are in the history an assistant reads. */
    @Test
    fun historyShowsHeartRateAndCaloriesWhenThePhoneReadThem() = tungTest {
        val token = client.register()
        val withVitals = id()
        val without = id()
        client.synced(
            token,
            changes = SyncChanges(
                workouts = listOf(
                    SyncWorkout(withVitals, 1_700_000_000_000, 1_700_003_600_000, null, null, null, 1_000, avgHeartRate = 128, maxHeartRate = 171, activeCalories = 342),
                    SyncWorkout(without, 1_600_000_000_000, 1_600_003_600_000, null, null, null, 1_000),
                ),
            ),
        )
        val history = client.toolOk(token, "history")["workouts"]!!.jsonArray.map { it.jsonObject }.associateBy { it["id"]!!.str }
        val read = history.getValue(withVitals)
        assertEquals("128", read["avg_heart_rate"]!!.str)
        assertEquals("171", read["max_heart_rate"]!!.str)
        assertEquals("342", read["active_calories"]!!.str)
        assertFalse("avg_heart_rate" in history.getValue(without), "left out when not read")
    }

    @Test
    fun anUnknownNameComesBackAsQuestionsNotAnError() = tungTest {
        val token = client.register()
        val (result, isError) = client.tool(
            token,
            "apply_plan",
            """{"exercises": [{"exercise": {"name": "Bench pres"}, "set_count": 3, "reps": 5}]}""",
        )
        assertFalse(isError, "questions are an answer")
        assertNotNull(result)
        assertEquals("needs_answers", result["status"]!!.str)
        val suggestions = result["questions"]!!.jsonArray.single().jsonObject["suggestions"]!!.jsonArray.map { it.jsonObject }
        assertTrue(suggestions.any { it["key"]?.jsonPrimitive?.content == "bench_press" }, suggestions.toString())
        assertTrue(result["hint"]!!.str.contains("Ask the user"))
        assertEquals("needs_answers", client.toolOk(token, "last_time", """{"exercise": {"name": "Bench pres"}}""")["status"]!!.str)

        val (rejected, rejectedIsError) = client.tool(token, "log_set", """{"workout_id": "${id()}"}""")
        assertTrue(rejectedIsError, "a refusal is a tool error")
        assertEquals("rejected", rejected!!["status"]!!.str)
        val (_, badArgs) = client.tool(token, "set_reps", """{"reps": "many"}""")
        assertTrue(badArgs)
    }

    @Test
    fun aReadTokenReadsButCannotChange() = tungTest {
        val session = client.register()
        val readToken = client.post("/api/v1/tokens") {
            bearerAuth(session)
            jsonBody("""{"name": "assistant", "scope": "read"}""")
        }.json()["token"]!!.str
        val response = client.mcp(readToken, """{"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "start_workout", "arguments": {}}}""")
        assertEquals(HttpStatusCode.OK, response.status, "a scope problem is a tool error, not a transport error")
        val result = response.json()["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertTrue(result["content"]!!.jsonArray.single().jsonObject["text"]!!.str.contains("read access only"))
        assertTrue(client.synced(session).changes.workouts.isEmpty(), "nothing was started")

        val (routines, isError) = client.tool(readToken, "routines")
        assertFalse(isError)
        assertEquals(0, routines!!["routines"]!!.jsonArray.size)
        val exercises = client.toolOk(readToken, "exercises", """{"query": "bänk"}""")["exercises"]!!.jsonArray.map { it.jsonObject }
        assertTrue(exercises.any { it["key"]?.jsonPrimitive?.content == "bench_press" }, "searches every catalogue language")
        val extended = client.toolOk(readToken, "exercises", """{"query": "zercher"}""")["extended"]!!.jsonArray
        assertTrue(extended.isNotEmpty(), "and the extended catalogue")
    }

    @Test
    fun promptsAndTheCatalogue() = tungTest {
        val token = client.register()
        val prompts = client.rpc(token, "prompts/list")["prompts"]!!.jsonArray.map { it.jsonObject["name"]!!.str }
        assertEquals(setOf("suggest_program", "next_workout", "plateaus"), prompts.toSet())
        for (name in prompts) {
            val args = if (name == "suggest_program") """{"goal": "strength", "days_per_week": "3", "equipment": "full gym"}""" else "{}"
            val text = client.rpc(token, "prompts/get", """{"name": "$name", "arguments": $args}""")["messages"]!!.jsonArray
                .single().jsonObject["content"]!!.jsonObject["text"]!!.str
            assertTrue(text.contains("history"), "$name reads the history")
            assertTrue(text.contains("my language"), "$name answers in the user's language")
            assertTrue(text.contains("save_routine_plan only after I"), "$name saves only after confirmation")
        }
        val resources = client.rpc(token, "resources/list")["resources"]!!.jsonArray.map { it.jsonObject["uri"]!!.str }
        assertEquals(listOf("tung://catalog/core"), resources)
        val catalogue = client.rpc(token, "resources/read", """{"uri": "tung://catalog/core"}""")["contents"]!!.jsonArray.single().jsonObject["text"]!!.str
        assertTrue(catalogue.contains("\"bench_press\""))
    }

    @Test
    fun sessionsBatchesAndTheTransportsEdges() = tungTest {
        val token = client.register()
        val session = client.mcp(token, INITIALIZE).headers["Mcp-Session-Id"]!!
        assertEquals(HttpStatusCode.OK, client.mcp(token, """{"jsonrpc": "2.0", "id": 2, "method": "ping"}""", session).status)
        assertEquals(HttpStatusCode.NotFound, client.mcp(token, """{"jsonrpc": "2.0", "id": 2, "method": "ping"}""", "no-such-session").status)
        val stranger = client.register()
        assertEquals(HttpStatusCode.NotFound, client.mcp(stranger, """{"jsonrpc": "2.0", "id": 2, "method": "ping"}""", session).status, "a session is its user's")

        assertEquals(HttpStatusCode.NoContent, client.delete("/mcp") { bearerAuth(token); header("Mcp-Session-Id", session) }.status)
        assertEquals(HttpStatusCode.NotFound, client.mcp(token, """{"jsonrpc": "2.0", "id": 2, "method": "ping"}""", session).status)

        val batch = client.mcp(token, """[{"jsonrpc": "2.0", "id": 1, "method": "ping"}, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}]""")
        assertEquals(HttpStatusCode.OK, batch.status)
        assertEquals(2, ApiJson.parseToJsonElement(batch.bodyAsText()).jsonArray.size)

        val sse = client.mcp(token, """{"jsonrpc": "2.0", "id": 4, "method": "ping"}""", accept = "text/event-stream")
        assertTrue(sse.bodyAsText().startsWith("event: message\ndata: {"), sse.bodyAsText())

        val notJson = client.mcp(token, "{nope")
        assertEquals(HttpStatusCode.BadRequest, notJson.status)
        assertEquals(-32700, notJson.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())

        val unknownMethod = client.mcp(token, """{"jsonrpc": "2.0", "id": 9, "method": "no/such"}""").json()
        assertEquals(-32601, unknownMethod["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())

        val get = client.get("/mcp") { bearerAuth(token) }
        assertEquals(HttpStatusCode.MethodNotAllowed, get.status)
    }
}
