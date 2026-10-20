package io.github.kanylbullen.tung.server.mcp

import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.CommandWire
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.WireDecoding
import io.github.kanylbullen.tung.server.TokenPrincipal
import io.github.kanylbullen.tung.server.Tokens
import io.github.kanylbullen.tung.server.command.CommandService
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.InitializeRequest
import io.modelcontextprotocol.kotlin.sdk.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.Method
import io.modelcontextprotocol.kotlin.sdk.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Properties
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The protocol versions `/mcp` speaks, newest first; a client asking for another gets the newest. */
val MCP_PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

/** The server's version, written into the classpath by the build (`tung-server.properties`). */
object BuildInfo {
    val version: String by lazy {
        BuildInfo::class.java.getResourceAsStream("/tung-server.properties")
            ?.use { Properties().apply { load(it) }.getProperty("version") }
            ?.takeUnless { it.isBlank() || it.startsWith("\${") }
            ?: "dev"
    }
}

/**
 * Who one HTTP request to `/mcp` is from, and what the server answers it.
 * McpRoutes puts it in the coroutine context around the SDK's message
 * handling, which runs inline, so a tool handler knows the user and the
 * token's scope, and the transport knows where to put each response.
 */
class McpExchange(val principal: TokenPrincipal) : AbstractCoroutineContextElement(Key) {
    val responses: MutableList<io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage> = mutableListOf()

    companion object Key : CoroutineContext.Key<McpExchange>
}

/**
 * Tung's MCP server: one tool per command in core's `CommandNames` (same
 * name, the input schema from `CommandWire`, executed by the server-side
 * command handler, so an assistant's change is exactly a button's), the read
 * tools, three prompts and the core catalogue as a resource. One SDK
 * [Server] serves every user; the user comes from the request ([McpExchange]).
 */
class TungMcp(private val commands: CommandService, private val reads: McpReads) {

    private val capabilities = ServerCapabilities(
        experimental = null,
        sampling = null,
        logging = null,
        prompts = ServerCapabilities.Prompts(listChanged = false),
        resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
        tools = ServerCapabilities.Tools(listChanged = false),
    )
    private val info = Implementation(name = "tung", version = BuildInfo.version)

    val server: Server = Server(info, ServerOptions(capabilities)).also { server ->
        // The SDK only knows 2024-11-05; the transport and the auth are the newer protocol's, so negotiate here.
        server.setRequestHandler<InitializeRequest>(Method.Defined.Initialize) { request, _ ->
            val version = request.protocolVersion.takeIf { it in MCP_PROTOCOL_VERSIONS } ?: MCP_PROTOCOL_VERSIONS.first()
            InitializeResult(protocolVersion = version, capabilities = capabilities, serverInfo = info)
        }
        registerCommandTools(server)
        registerReadTools(server)
        registerPrompts(server)
        server.addResource(
            uri = CATALOG_URI,
            name = "Core exercise catalogue",
            description = "The ~70 core exercises: catalogue key (what {\"key\": …} takes), name in each language, muscle group and equipment.",
            mimeType = "application/json",
        ) { ReadResourceResult(listOf(TextResourceContents(reads.coreCatalogue().toString(), CATALOG_URI, "application/json"))) }
    }

    /** The names of every tool, for tests and the README. */
    val toolNames: List<String> get() = CommandWire.names + READ_TOOLS

    private fun registerCommandTools(server: Server) {
        for (name in CommandWire.names) {
            val schema = checkNotNull(CommandWire.schema(name))
            server.addTool(name, McpDescriptions.command(name), toolInput(schema)) { request -> runCommand(name, request) }
        }
    }

    private suspend fun runCommand(name: String, request: CallToolRequest): CallToolResult {
        val principal = principal()
        if (principal.scope != Tokens.SCOPE_WRITE) {
            return toolError(
                "This connection has read access only, so $name cannot change anything. " +
                    "Reconnect Tung with write access (scope write) to log workouts.",
            )
        }
        val command = when (val decoded = CommandWire.decode(name, request.arguments)) {
            is WireDecoding.Invalid -> return toolError("The arguments for $name are not right: ${decoded.reasons.joinToString("; ")}.")
            is WireDecoding.Decoded -> decoded.command
        }
        val result = commands.handlerFor(principal.userId).execute(command)
        val body = CommandWire.encode(result)
        return when (result) {
            is CommandResult.Done -> toolResult(body)
            // Not an error: the questions are the answer. Nothing was written.
            is CommandResult.NeedsAnswers -> toolResult(
                JsonObject(body + ("hint" to JsonPrimitive(NEEDS_ANSWERS_HINT))),
            )
            is CommandResult.Rejected -> CallToolResult(content = listOf(TextContent(body.toString())), isError = true)
        }
    }

    private fun registerReadTools(server: Server) {
        val exerciseSchema = checkNotNull(CommandWire.schema("add_exercise"))["properties"]!!.jsonObject["exercise"]!!
        server.addTool(
            "history",
            McpDescriptions.HISTORY,
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "How many workouts, 1 to $MAX_HISTORY. Default $DEFAULT_HISTORY.")
                    }
                    putJsonObject("before") {
                        put("type", "integer")
                        put("description", "Only workouts started before this time (epoch milliseconds): the next_before of the previous page.")
                    }
                },
            ),
        ) { request ->
            val limit = request.arguments.number("limit")?.toInt() ?: DEFAULT_HISTORY
            if (limit !in 1..MAX_HISTORY) return@addTool toolError("limit must be 1 to $MAX_HISTORY.")
            toolResult(reads.history(principal().userId, limit, request.arguments.number("before")?.toLong()))
        }
        server.addTool(
            "last_time",
            McpDescriptions.LAST_TIME,
            Tool.Input(properties = buildJsonObject { put("exercise", exerciseSchema) }, required = listOf("exercise")),
        ) { request ->
            val ref = request.arguments["exercise"]?.let(::exerciseRef)
                ?: return@addTool toolError("exercise must be {\"key\": …}, {\"id\": …}, {\"name\": …} or {\"extended\": …}, or a name.")
            toolResult(reads.lastTime(principal().userId, ref))
        }
        server.addTool("current_workout", McpDescriptions.CURRENT_WORKOUT, Tool.Input()) { toolResult(reads.currentWorkout(principal().userId)) }
        server.addTool("routines", McpDescriptions.ROUTINES, Tool.Input()) { toolResult(reads.routines(principal().userId)) }
        server.addTool("programs", McpDescriptions.PROGRAMS, Tool.Input()) { toolResult(reads.programs(principal().userId)) }
        server.addTool(
            "exercises",
            McpDescriptions.EXERCISES,
            Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Part of a name in any catalogue language, e.g. \"bench\" or \"bänk\". Leave out to list everything the user has.")
                    }
                },
            ),
        ) { request ->
            val query = (request.arguments["query"] as? JsonPrimitive)?.contentOrNull
            toolResult(reads.exercises(principal().userId, query))
        }
    }

    private fun registerPrompts(server: Server) {
        server.addPrompt(
            name = "suggest_program",
            description = "Design a training program from the user's goal, schedule, equipment and history, and save it once they agree.",
            arguments = listOf(
                PromptArgument("goal", "What the program is for: strength, muscle, a sport, getting back into training …", required = false),
                PromptArgument("days_per_week", "How many days a week the user can train.", required = false),
                PromptArgument("equipment", "What the user has: a full gym, dumbbells at home, bodyweight only …", required = false),
            ),
        ) { request ->
            val args = request.arguments.orEmpty()
            prompt("Design a training program", McpDescriptions.suggestProgram(args["goal"], args["days_per_week"], args["equipment"]))
        }
        server.addPrompt(
            name = "next_workout",
            description = "Work out what the user should train today, with weights and reps from their last sessions.",
            arguments = emptyList(),
        ) { prompt("Today's workout", McpDescriptions.NEXT_WORKOUT) }
        server.addPrompt(
            name = "plateaus",
            description = "Find exercises where the user has stopped progressing, and suggest what to change.",
            arguments = emptyList(),
        ) { prompt("Plateaus", McpDescriptions.PLATEAUS) }
    }

    private suspend fun principal(): TokenPrincipal =
        checkNotNull(currentCoroutineContext()[McpExchange]) { "an MCP request outside McpRoutes" }.principal

    private fun prompt(description: String, text: String) =
        GetPromptResult(description = description, messages = listOf(PromptMessage(Role.user, TextContent(text))))

    private fun toolResult(json: JsonObject) = CallToolResult(content = listOf(TextContent(json.toString())))

    private fun toolError(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)

    /** The SDK's input type: the properties and the required list of a `CommandWire` object schema. */
    private fun toolInput(schema: JsonObject) = Tool.Input(
        properties = schema["properties"]!!.jsonObject,
        required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
    )

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.toDoubleOrNull()

    /** The same exercise forms as the commands take; decoded through `CommandWire` so they cannot differ. */
    private fun exerciseRef(value: JsonElement): ExerciseRef? {
        val decoded = CommandWire.decode("add_exercise", buildJsonObject {
            put("workout_id", "-")
            put("exercise", value)
        })
        return ((decoded as? WireDecoding.Decoded)?.command as? io.github.kanylbullen.tung.core.command.Command.AddExercise)?.exercise
    }

    companion object {
        const val CATALOG_URI = "tung://catalog/core"
        val READ_TOOLS = listOf("history", "last_time", "current_workout", "routines", "programs", "exercises")
        const val DEFAULT_HISTORY = 10
        const val MAX_HISTORY = 50
        private const val NEEDS_ANSWERS_HINT =
            "Nothing was written. Ask the user which exercise they meant for each question (offer the suggestions by name, " +
                "in their language), then call the tool again with the chosen suggestion's ref as the exercise."
    }
}
