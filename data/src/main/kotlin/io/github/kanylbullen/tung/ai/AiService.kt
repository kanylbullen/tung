package io.github.kanylbullen.tung.ai

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.ai.AI_HISTORY_DAYS
import io.github.kanylbullen.tung.core.ai.AI_HISTORY_WORKOUTS
import io.github.kanylbullen.tung.core.ai.AI_SECRET_HEADERS
import io.github.kanylbullen.tung.core.ai.AiCall
import io.github.kanylbullen.tung.core.ai.AiCatalogueEntry
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
import io.github.kanylbullen.tung.core.ai.AiRequest
import io.github.kanylbullen.tung.core.ai.AiResolution
import io.github.kanylbullen.tung.core.ai.AiRoutine
import io.github.kanylbullen.tung.core.ai.AiRoutineExercise
import io.github.kanylbullen.tung.core.ai.aiHttpRequest
import io.github.kanylbullen.tung.core.ai.AiReply
import io.github.kanylbullen.tung.core.ai.aiReply
import io.github.kanylbullen.tung.core.ai.buildAiContext
import io.github.kanylbullen.tung.core.ai.buildAiMatchPrompt
import io.github.kanylbullen.tung.core.ai.buildAiPrompt
import io.github.kanylbullen.tung.core.ai.parseAiAnswer
import io.github.kanylbullen.tung.core.ai.prettyAiBody
import io.github.kanylbullen.tung.core.ai.resolveAiAnswer
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.maskToWeekdays
import io.github.kanylbullen.tung.data.ProgramRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.WorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * One request, built and ready: what [AiService.send] sends, byte for byte,
 * and what "Show what is sent" shows. [http] has no key in it; the key is
 * added to the headers at the moment of sending.
 */
data class AiPrepared(
    val request: AiRequest,
    val call: AiCall,
    val prompt: AiPrompt,
    val http: AiHttpRequest,
    val catalogueKeys: Set<String>,
) {
    /** The body, indented: the same JSON that is sent. */
    val previewBody: String get() = prettyAiBody(http.body)

    /** The headers that go with it, except the one with the key. */
    val previewHeaders: Map<String, String> get() = http.headers.filterKeys { it !in AI_SECRET_HEADERS }
}

/** The answer to one request: a proposal to show, or why there is none. */
sealed interface AiOutcome {
    data class Proposed(val proposal: AiProposal) : AiOutcome

    /** [message] is the provider's own error text when it sent one. */
    data class Failed(val failure: AiFailure, val message: String? = null) : AiOutcome
}

/** What confirming a proposal did. */
sealed interface AiConfirmation {
    /** A program answer: its routines, and the program that holds them in order. */
    data class Saved(val routineIds: List<String>, val programId: String) : AiConfirmation

    /** A next-workout answer: the workout in progress it was applied to. */
    data class Started(val workoutId: String) : AiConfirmation

    /** A command did not run; nothing after it ran either. */
    data class Failed(val reasons: List<String>) : AiConfirmation
}

/**
 * The in-app assistant with the user's own key (CLAUDE.md, "In-app AI
 * (BYOK)"). Everything runs on a tap and nothing in the background: [prepare]
 * builds the context from the repositories and the request; [send] makes one
 * call, parses the answer in core and resolves its exercises over [resolver]
 * (an exercise that resolves to nothing is dropped); [confirm] runs the
 * commands only after the user has confirmed the proposal.
 *
 * What is sent is training data only ([AiContext]): no email, name, device id,
 * server URL or note is read here.
 */
class AiService(
    private val settings: AiSettings,
    private val client: AiClient,
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
    private val programs: ProgramRepository,
    private val resolver: ExerciseResolver,
    private val catalogueKeys: () -> Collection<String>,
    private val commands: CommandHandler,
    private val clock: Clock,
    /** "My equipment"; the context carries it only when it is narrowed. */
    private val equipment: suspend () -> Set<Equipment> = { EquipmentFilter.ALL },
    /** The core catalogue as matching sends it (key, English name, equipment), for [prepareMatch]. */
    private val catalogue: () -> List<AiCatalogueEntry> = { emptyList() },
) {

    /** The request for [request] with the current settings and data; null when no model is set up. */
    suspend fun prepare(request: AiRequest, language: String): AiPrepared? {
        val config = settings.config()
        if (!config.configured) return null
        val keys = withContext(Dispatchers.IO) { catalogueKeys().toSet() }
        val call = AiCall(config.provider, config.effectiveBaseUrl, config.model, jsonMode = config.jsonMode)
        // Matching carries everything it sends; the training data is not even read for it.
        val prompt = if (request is AiRequest.MatchExercises) buildAiMatchPrompt(request, language) else buildAiPrompt(request, context(keys), language)
        return AiPrepared(request, call, prompt, aiHttpRequest(call, prompt, apiKey = null), keys)
    }

    /**
     * "Suggest with the assistant" in the import review: [names] with their
     * offline [candidates] and the core catalogue, nothing else. The same
     * [prepare] and [send] as the other questions, so "What is sent" and
     * "Show what is sent" apply; the answer is an [AiProposal.Matches].
     */
    suspend fun prepareMatch(names: List<String>, candidates: Map<String, List<ExerciseCandidate>>, language: String): AiPrepared? {
        val entries = withContext(Dispatchers.IO) { catalogue() }
        return prepare(AiRequest.MatchExercises(names, candidates, entries), language)
    }

    /** Sends [prepared] exactly as built, with the key in its header, and turns the answer into a proposal. */
    suspend fun send(prepared: AiPrepared): AiOutcome {
        val keyed = aiHttpRequest(prepared.call, prepared.prompt, settings.key())
        val body = when (val result = client.send(prepared.http.copy(headers = keyed.headers))) {
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

    /**
     * "Test" in Settings: one request asking for one token, with no training
     * data in it. Null when it worked, else why not.
     */
    suspend fun test(): AiOutcome.Failed? {
        val config = settings.config()
        if (!config.configured) return AiOutcome.Failed(AiFailure.NOT_CONFIGURED)
        val call = AiCall(config.provider, config.effectiveBaseUrl, config.model, jsonMode = false, maxTokens = 1)
        val request = aiHttpRequest(call, AiPrompt(system = "Reply with OK.", user = "OK?"), settings.key())
        return when (val result = client.send(request)) {
            is AiHttpResult.Ok -> null
            is AiHttpResult.Failed -> AiOutcome.Failed(result.failure, result.message)
        }
    }

    /**
     * Saves a program answer: each routine with `save_routine_plan`, then a
     * rotating program named [programName] with `create_program` and its days
     * in order with `add_program_day`. The program is not activated; the user
     * does that on its screen.
     */
    suspend fun confirm(proposal: AiProposal.Program, programName: String): AiConfirmation {
        val routineIds = mutableListOf<String>()
        for (routine in proposal.routines) {
            val result = commands.execute(Command.SaveRoutinePlan(routine.toPlan()))
            routineIds += result.id(CommandIds.ROUTINE_ID) ?: return failed(result)
        }
        val created = commands.execute(Command.CreateProgram(programName, ProgramMode.ROTATING))
        val programId = created.id(CommandIds.PROGRAM_ID) ?: return failed(created)
        for (routineId in routineIds) {
            val day = commands.execute(Command.AddProgramDay(programId, routineId))
            if (day !is CommandResult.Done) return failed(day)
        }
        return AiConfirmation.Saved(routineIds, programId)
    }

    /** Applies a next-workout answer with `apply_plan`: to the workout in progress, or a new one. */
    suspend fun confirm(proposal: AiProposal.NextWorkout): AiConfirmation {
        val result = commands.execute(Command.ApplyPlan(proposal.toPlan()))
        return result.id(CommandIds.WORKOUT_ID)?.let { AiConfirmation.Started(it) } ?: failed(result)
    }

    private fun failed(result: CommandResult): AiConfirmation.Failed = AiConfirmation.Failed(
        when (result) {
            is CommandResult.Rejected -> result.reasons
            is CommandResult.NeedsAnswers -> result.questions.map { "unknown exercise: ${it.text}" }
            is CommandResult.Done -> listOf("no id in the result")
        },
    )

    /** The training data: finished workouts of the last [AI_HISTORY_DAYS] days, routines, programs. */
    private suspend fun context(keys: Set<String>): AiContext = withContext(Dispatchers.IO) {
        val now = clock.instant()
        val since = now.toEpochMilli() - AI_HISTORY_DAYS * 86_400_000L
        val routineRows = routines.observeRoutines().first()
        val routineNames = routineRows.associate { it.id to it.name }

        val history = workouts.observeFinishedWorkouts().first()
            .filter { it.startedAt >= since }
            .take(AI_HISTORY_WORKOUTS)
            .map { row ->
                val routineName = workouts.getWorkout(row.id)?.routineId?.let { routineNames[it] }
                val exercises = workouts.getExercisesWithSets(row.id).map { e ->
                    AiHistoryExercise(
                        key = e.exercise.nameKey,
                        name = e.exercise.name,
                        sets = e.sortedSets.map { AiHistorySet(it.weightKg, it.reps, it.completed) },
                    )
                }
                AiHistoryWorkout(row.startedAt, routineName, exercises)
            }

        val routineList = routineRows.map { row ->
            AiRoutine(
                name = row.name,
                exercises = routines.observeRoutineExercises(row.id).first().map { e ->
                    AiRoutineExercise(e.exercise.nameKey, e.exercise.name, e.routineExercise.targetSets, e.routineExercise.targetReps)
                },
            )
        }

        val programList = programs.observePrograms().first().map { row ->
            val mode = ProgramMode.fromKey(row.mode)
            val days = programs.observeDays(row.id).first()
            val last = programs.observeLastCompletedDayId(row.id).first()
            AiProgram(
                name = row.name,
                mode = mode.key,
                active = row.isActive,
                days = days.mapIndexed { i, day ->
                    AiProgramDay(i + 1, day.routineName, if (mode == ProgramMode.FIXED) maskToWeekdays(day.weekdays).sorted() else emptyList())
                },
                lastCompletedDay = days.indexOfFirst { it.id == last }.takeIf { it >= 0 }?.plus(1),
            )
        }

        buildAiContext(now, clock.zone, history, routineList, programList, keys, equipment())
    }
}
