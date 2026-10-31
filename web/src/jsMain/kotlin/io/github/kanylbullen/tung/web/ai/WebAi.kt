package io.github.kanylbullen.tung.web.ai

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.ai.AI_HISTORY_DAYS
import io.github.kanylbullen.tung.core.ai.AI_HISTORY_WORKOUTS
import io.github.kanylbullen.tung.core.ai.AI_SECRET_HEADERS
import io.github.kanylbullen.tung.core.ai.AiCall
import io.github.kanylbullen.tung.core.ai.AiContext
import io.github.kanylbullen.tung.core.ai.AiHistoryExercise
import io.github.kanylbullen.tung.core.ai.AiHistorySet
import io.github.kanylbullen.tung.core.ai.AiHistoryWorkout
import io.github.kanylbullen.tung.core.ai.AiHttpRequest
import io.github.kanylbullen.tung.core.ai.AiParse
import io.github.kanylbullen.tung.core.ai.AiProgram
import io.github.kanylbullen.tung.core.ai.AiProgramDay
import io.github.kanylbullen.tung.core.ai.AiPrompt
import io.github.kanylbullen.tung.core.ai.AiProposal
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import io.github.kanylbullen.tung.core.ai.AiReply
import io.github.kanylbullen.tung.core.ai.AiRequest
import io.github.kanylbullen.tung.core.ai.AiResolution
import io.github.kanylbullen.tung.core.ai.AiRoutine
import io.github.kanylbullen.tung.core.ai.AiRoutineExercise
import io.github.kanylbullen.tung.core.ai.aiErrorMessage
import io.github.kanylbullen.tung.core.ai.aiHttpRequest
import io.github.kanylbullen.tung.core.ai.aiReply
import io.github.kanylbullen.tung.core.ai.buildAiContext
import io.github.kanylbullen.tung.core.ai.buildAiMatchPrompt
import io.github.kanylbullen.tung.core.ai.buildAiPrompt
import io.github.kanylbullen.tung.core.ai.parseAiAnswer
import io.github.kanylbullen.tung.core.ai.prettyAiBody
import io.github.kanylbullen.tung.core.ai.resolveAiAnswer
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.maskToWeekdays
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.sync.HttpReply
import io.github.kanylbullen.tung.web.sync.HttpUnreachable
import io.github.kanylbullen.tung.web.sync.postJson
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone

/** Why a request gave no proposal, the phone's `AiFailure`. */
enum class AiFailure {
    NOT_CONFIGURED, NETWORK, UNAUTHORIZED, NOT_FOUND, RATE_LIMITED, REJECTED, SERVER,
    NO_ANSWER, OUT_OF_ROOM, DECLINED, UNREADABLE, NOTHING_USABLE,
}

/** One request, built and ready: what [WebAi.send] sends, byte for byte, and what "What is sent" shows. [http] has no key. */
data class AiPrepared(
    val request: AiRequest,
    val call: AiCall,
    val prompt: AiPrompt,
    val http: AiHttpRequest,
    val catalogueKeys: Set<String>,
) {
    val previewBody: String get() = prettyAiBody(http.body)
}

sealed interface AiOutcome {
    data class Proposed(val proposal: AiProposal) : AiOutcome
    data class Failed(val failure: AiFailure, val message: String? = null) : AiOutcome
}

/** What one HTTP call to the provider gave: the body of a 2xx, or why not. */
sealed interface AiHttpResult {
    data class Ok(val body: String) : AiHttpResult
    data class Failed(val failure: AiFailure, val message: String? = null) : AiHttpResult
}

/**
 * Anthropic answers a browser only when asked to: without this header its
 * API refuses a request that comes with an `Origin`. The key is the user's
 * own, in their own browser, which is what the header is for.
 */
const val ANTHROPIC_BROWSER_HEADER = "anthropic-dangerous-direct-browser-access"

/** Sends [request] with `fetch`: one call, nothing retried, the statuses read as the phone's `AiClient` reads them. */
suspend fun sendAi(request: AiHttpRequest, post: suspend (String, String, Map<String, String>) -> HttpReply = ::postJson): AiHttpResult {
    val reply = try {
        post(request.url, request.body, request.headers)
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpUnreachable) {
        return AiHttpResult.Failed(AiFailure.NETWORK)
    }
    val status = reply.status
    return when {
        status in 200..299 -> AiHttpResult.Ok(reply.body)
        status in 300..399 -> AiHttpResult.Failed(AiFailure.NOT_FOUND)
        status == 401 || status == 403 -> AiHttpResult.Failed(AiFailure.UNAUTHORIZED, aiErrorMessage(reply.body))
        status == 404 -> AiHttpResult.Failed(AiFailure.NOT_FOUND, aiErrorMessage(reply.body))
        status == 429 -> AiHttpResult.Failed(AiFailure.RATE_LIMITED, aiErrorMessage(reply.body))
        status in 400..499 -> AiHttpResult.Failed(AiFailure.REJECTED, aiErrorMessage(reply.body))
        else -> AiHttpResult.Failed(AiFailure.SERVER, aiErrorMessage(reply.body))
    }
}

/**
 * The in-app assistant in the browser, the phone's `AiService` (CLAUDE.md,
 * "In-app AI (BYOK)"): [prepare] builds the request from the store's
 * training data (no email, name, device or server: [AiContext] has no field
 * for them); [send] makes the one call with the key added at that moment,
 * reads the answer with core and resolves its exercises; the confirms run
 * the commands only after the user confirmed.
 */
class WebAi(
    private val store: TungStore,
    val settings: WebAiSettings,
    private val resolver: ExerciseResolver,
    private val catalogueKeys: () -> Set<String>,
    private val commands: CommandHandler,
    private val now: () -> Long,
    private val zone: () -> TimeZone,
    private val equipment: () -> Set<Equipment>,
    private val http: suspend (AiHttpRequest) -> AiHttpResult = { sendAi(it) },
) {
    /** The request for [request] in [language], or null when no model is set up. */
    fun prepare(request: AiRequest, language: String): AiPrepared? {
        val config = settings.config.value
        if (!config.configured) return null
        val keys = catalogueKeys()
        val call = AiCall(config.provider, config.effectiveBaseUrl, config.model, jsonMode = config.jsonMode)
        val prompt = if (request is AiRequest.MatchExercises) buildAiMatchPrompt(request, language) else buildAiPrompt(request, context(keys), language)
        return AiPrepared(request, call, prompt, withBrowserHeader(call, aiHttpRequest(call, prompt, apiKey = null)), keys)
    }

    suspend fun send(prepared: AiPrepared): AiOutcome {
        val keyed = withBrowserHeader(prepared.call, aiHttpRequest(prepared.call, prepared.prompt, settings.key()))
        val body = when (val result = http(prepared.http.copy(headers = keyed.headers))) {
            is AiHttpResult.Failed -> return AiOutcome.Failed(result.failure, result.message)
            is AiHttpResult.Ok -> result.body
        }
        val text = when (val reply = aiReply(prepared.call.provider, body)) {
            is AiReply.Text -> reply.text
            AiReply.OutOfRoom -> return AiOutcome.Failed(AiFailure.OUT_OF_ROOM)
            AiReply.Declined -> return AiOutcome.Failed(AiFailure.DECLINED)
            AiReply.Empty -> return AiOutcome.Failed(AiFailure.NO_ANSWER)
        }
        val draft = when (val parse = parseAiAnswer(prepared.request, text, prepared.catalogueKeys)) {
            is AiParse.Malformed -> return AiOutcome.Failed(AiFailure.UNREADABLE)
            is AiParse.Parsed -> parse.draft
        }
        return when (val resolution = resolveAiAnswer(draft, resolver)) {
            is AiResolution.Empty -> AiOutcome.Failed(AiFailure.NOTHING_USABLE)
            is AiResolution.Proposed -> AiOutcome.Proposed(resolution.proposal)
        }
    }

    /** "Test" in Settings: one request for one token with no training data in it. Null when it worked. */
    suspend fun test(): AiOutcome.Failed? {
        val config = settings.config.value
        if (!config.configured) return AiOutcome.Failed(AiFailure.NOT_CONFIGURED)
        val call = AiCall(config.provider, config.effectiveBaseUrl, config.model, jsonMode = false, maxTokens = 1)
        val request = withBrowserHeader(call, aiHttpRequest(call, AiPrompt(system = "Reply with OK.", user = "OK?"), settings.key()))
        return when (val result = http(request)) {
            is AiHttpResult.Ok -> null
            is AiHttpResult.Failed -> AiOutcome.Failed(result.failure, result.message)
        }
    }

    /** Saves a program answer: each routine, then a rotating program with them in order; not activated. Its id, or null. */
    suspend fun confirm(proposal: AiProposal.Program, programName: String): String? {
        val routineIds = proposal.routines.map { routine ->
            (commands.execute(Command.SaveRoutinePlan(routine.toPlan())) as? CommandResult.Done)?.ids?.get(CommandIds.ROUTINE_ID) ?: return null
        }
        val programId = (commands.execute(Command.CreateProgram(programName, ProgramMode.ROTATING)) as? CommandResult.Done)
            ?.ids?.get(CommandIds.PROGRAM_ID) ?: return null
        routineIds.forEach { if (commands.execute(Command.AddProgramDay(programId, it)) !is CommandResult.Done) return null }
        return programId
    }

    /** Applies a next-workout answer with `apply_plan`: true when it ran. */
    suspend fun confirm(proposal: AiProposal.NextWorkout): Boolean =
        commands.execute(Command.ApplyPlan(proposal.toPlan())) is CommandResult.Done

    /** The training data: finished workouts of the last [AI_HISTORY_DAYS] days, routines and programs. */
    fun context(keys: Set<String>): AiContext {
        val nowMillis = now()
        val since = nowMillis - AI_HISTORY_DAYS * 86_400_000L
        val exercises = store.exercises.rows.mapValues { it.value.row }
        val routineNames = store.routines.rows.mapValues { it.value.row.name }
        val workoutExercises = store.workoutExercises.rows.values.map { it.row }.groupBy { it.workoutId }
        val sets = store.sets.rows.values.map { it.row }.groupBy { it.workoutExerciseId }
        val history = store.workouts.rows.values.map { it.row }
            .filter { it.endedAt != null && (it.startedAt ?: 0L) >= since }
            .sortedByDescending { it.startedAt }
            .take(AI_HISTORY_WORKOUTS)
            .map { workout ->
                AiHistoryWorkout(
                    workout.startedAt ?: 0L,
                    workout.routineId?.let { routineNames[it] },
                    workoutExercises[workout.id].orEmpty().sortedBy { it.position ?: 0 }.map { we ->
                        val exercise = we.exerciseId?.let { exercises[it] }
                        AiHistoryExercise(
                            exercise?.nameKey,
                            exercise?.name ?: "",
                            sets[we.id].orEmpty().sortedBy { it.position ?: 0 }.map { AiHistorySet(it.weightKg ?: 0.0, it.reps ?: 0, it.completed ?: false) },
                        )
                    },
                )
            }
        val routineExercises = store.routineExercises.rows.values.map { it.row }.groupBy { it.routineId }
        val routines = store.routines.rows.values.map { it.row }.mapNotNull { routine ->
            val name = routine.name ?: return@mapNotNull null
            AiRoutine(
                name,
                routineExercises[routine.id].orEmpty().sortedBy { it.position ?: 0 }.map { re ->
                    val exercise = re.exerciseId?.let { exercises[it] }
                    AiRoutineExercise(exercise?.nameKey, exercise?.name ?: "", re.targetSets ?: 3, re.targetReps)
                },
            )
        }
        val days = store.programDays.rows.values.map { it.row }.groupBy { it.programId }
        val programs = store.programs.rows.values.map { it.row }.mapNotNull { program ->
            val name = program.name ?: return@mapNotNull null
            val mode = ProgramMode.fromKey(program.mode ?: "")
            val ordered = days[program.id].orEmpty().sortedBy { it.position ?: 0 }
            val ids = ordered.map { it.id }.toSet()
            val last = store.workouts.rows.values.map { it.row }.filter { it.endedAt != null && it.programDayId in ids }.maxByOrNull { it.startedAt ?: 0L }?.programDayId
            AiProgram(
                name = name,
                mode = mode.key,
                active = program.isActive ?: false,
                days = ordered.mapIndexed { i, day ->
                    AiProgramDay(
                        i + 1,
                        day.routineId?.let { routineNames[it] } ?: "",
                        if (mode == ProgramMode.FIXED) maskToWeekdays(day.weekdays ?: 0).sorted() else emptyList(),
                    )
                },
                lastCompletedDay = ordered.indexOfFirst { it.id == last }.takeIf { it >= 0 }?.plus(1),
            )
        }
        return buildAiContext(nowMillis, zone(), history, routines, programs, keys, equipment())
    }

    private fun withBrowserHeader(call: AiCall, request: AiHttpRequest): AiHttpRequest =
        if (call.provider == AiProviderKind.ANTHROPIC) request.copy(headers = request.headers + (ANTHROPIC_BROWSER_HEADER to "true")) else request

    /** The headers "What is sent" shows: everything but the key's. */
    fun previewHeaders(prepared: AiPrepared): Map<String, String> = prepared.http.headers.filterKeys { it !in AI_SECRET_HEADERS }
}
