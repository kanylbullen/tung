package io.github.kanylbullen.tung.core.ai

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.normalizeExerciseName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * "Suggest with the assistant" in the import review (issue #71): the names
 * nothing matched exactly, sent with the catalogue and the offline
 * candidates, answered with one proposed exercise (or none) per name. What is
 * sent is only that: no history, routines, account, device or server.
 */

/** One core catalogue entry as matching sends it: its key, English name and equipment key. */
@Serializable
data class AiCatalogueEntry(val key: String, val name: String, val equipment: String)

/** The core catalogue as [AiCatalogueEntry]s, sorted by key. */
fun aiCatalogue(core: List<CoreExercise>): List<AiCatalogueEntry> =
    core.map { AiCatalogueEntry(it.key, it.name["en"] ?: it.key, it.equipment) }.sortedBy { it.key }

/** One offline candidate: a core [key] or an extended-catalogue [extendedId], its name and equipment. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AiMatchCandidate(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val key: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("extended_id") val extendedId: String? = null,
    val name: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val equipment: String? = null,
)

/** One name to match, with its offline candidates, best first. */
@Serializable
data class AiMatchName(val name: String, val candidates: List<AiMatchCandidate>)

/**
 * Everything a matching request sends: the [names] with their candidates
 * and the core [catalogue]. The shape has no field for history, an account,
 * a device or a server, so none can be sent.
 */
@Serializable
data class AiMatchContext(val names: List<AiMatchName>, val catalogue: List<AiCatalogueEntry>)

/** How many offline candidates go with each name. */
const val AI_MATCH_CANDIDATES = 5

/**
 * The context of [request]: each name with at most [AI_MATCH_CANDIDATES]
 * candidates that the catalogue can name (a core key or an extended id; a
 * custom exercise is the user's own and is left out), and the catalogue.
 */
fun buildAiMatchContext(request: AiRequest.MatchExercises): AiMatchContext = AiMatchContext(
    names = request.names.map { name ->
        AiMatchName(name, request.candidates[name].orEmpty().mapNotNull(::matchCandidate).take(AI_MATCH_CANDIDATES))
    },
    catalogue = request.catalogue,
)

private fun matchCandidate(c: ExerciseCandidate): AiMatchCandidate? = when {
    c.nameKey != null -> AiMatchCandidate(key = c.nameKey, name = c.displayName, equipment = c.equipment?.key)
    c.extendedId != null -> AiMatchCandidate(extendedId = c.extendedId, name = c.displayName, equipment = c.equipment?.key)
    else -> null
}

/** The prompt of a matching request: English, the answer's JSON shape and the load rule in the system message. */
fun buildAiMatchPrompt(request: AiRequest.MatchExercises, language: String = "en"): AiPrompt {
    val system = MATCH_SYSTEM + "\n\n" + MATCH_SCHEMA + "\n\n" + matchRules(language)
    val user = "Match each of these exercise names from my old training app to Tung's catalogue. Names and catalogue (JSON):\n" +
        AiContextJson.encodeToString(AiMatchContext.serializer(), buildAiMatchContext(request))
    return AiPrompt(system = system, user = user)
}

private const val MATCH_SYSTEM =
    "You are the training assistant inside Tung, a strength training log. The user is importing their log from " +
        "another app, and some of its exercise names matched nothing in Tung exactly. You get those names as JSON, each " +
        "with up to five candidates found offline (a core catalogue key or an extended_id, a name and the equipment), " +
        "and the core catalogue (key, English name, equipment). For each name, say which exercise it is, or that " +
        "none fits.\n\n" +
        "Answer with exactly one JSON object and nothing else: no Markdown, no code fence, no text before or after it. " +
        "The object has this shape:"

private val MATCH_SCHEMA =
    """
    {
      "matches": [
        { "name": "Machine Lat Pulldown", "ref": { "key": "lat_pulldown" }, "confidence": "medium", "note": "The catalogue's is on a cable." },
        { "name": "Dumbbell Deadlift", "ref": { "extended_id": "Stiff-Legged_Dumbbell_Deadlift" }, "confidence": "low", "note": "Closest dumbbell deadlift." },
        { "name": "Heel Walking", "ref": null, "confidence": "high", "note": "No such exercise; create it." }
      ]
    }
    One entry per name, with the name exactly as given. ref is {"key": …} from the catalogue, {"extended_id": …} from that
    name's candidates, or null when nothing fits. confidence is high, medium or low: high only when it is the same
    exercise with the same equipment.
    """.trimIndent()

private fun matchRules(language: String): String =
    "Rules:\n" +
        "- Never map across load types: a dumbbell deadlift is not the barbell deadlift, a machine pulldown is not the " +
        "cable one, a bodyweight exercise is not a weighted one. The weights logged would not compare. Prefer a " +
        "candidate with the same equipment as the name; if only a different load fits, give it with low confidence and " +
        "say so in the note; if nothing fits, ref is null and the user creates their own exercise.\n" +
        "- Only use keys from the catalogue and extended_ids from the candidates; anything else is dropped.\n" +
        "- Write the note in ${aiLanguageName(language)}. Keep it short and plain."

/** How sure the model is of a match. Anything else it writes reads as [LOW]. */
enum class AiConfidence(val key: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    companion object {
        fun fromKey(key: String?): AiConfidence = entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: LOW
    }
}

/** One match as the model wrote it: [ref] is null for "no good match". */
data class AiMatchDraft(val name: String, val ref: ExerciseRef?, val confidence: AiConfidence, val note: String)

/**
 * The `matches` list: an entry for a name that was not asked about, a second
 * entry for a name, or a ref that names no catalogue key or no id is dropped
 * (counted). A `key` outside [catalogueKeys] is dropped too; an `extended_id`
 * is checked when it is resolved.
 */
internal fun parseMatches(root: JsonObject, request: AiRequest.MatchExercises, catalogueKeys: Set<String>): AiParse {
    val matches = root.array("matches") ?: return AiParse.Malformed("no matches list")
    val asked = request.names.associateBy { normalizeExerciseName(it) }
    val seen = mutableSetOf<String>()
    var dropped = 0
    val drafts = matches.mapNotNull { element ->
        val match = element as? JsonObject ?: return@mapNotNull null.also { dropped++ }
        val name = match.string("name")?.let { asked[normalizeExerciseName(it)] }
        if (name == null || !seen.add(name)) return@mapNotNull null.also { dropped++ }
        val refElement = match["ref"]
        val ref = when (refElement) {
            null, is kotlinx.serialization.json.JsonNull -> null
            is JsonObject -> {
                val key = refElement.string("key")?.trim()?.ifEmpty { null }
                val extended = refElement.string("extended_id")?.trim()?.ifEmpty { null }
                when {
                    key != null && key in catalogueKeys -> ExerciseRef.ByKey(key)
                    key == null && extended != null -> ExerciseRef.Extended(extended)
                    else -> return@mapNotNull null.also { dropped++ }
                }
            }
            else -> return@mapNotNull null.also { dropped++ }
        }
        AiMatchDraft(name, ref, AiConfidence.fromKey(match.string("confidence")), match.string("note").orEmpty().trim())
    }
    return AiParse.Parsed(AiDraft.Matches(drafts, dropped))
}

/** One proposal for a review row: [exercise] is null when the model found no good match (the row keeps Create and Skip). */
data class ProposedMatch(val name: String, val exercise: ExerciseCandidate?, val confidence: AiConfidence, val note: String)
