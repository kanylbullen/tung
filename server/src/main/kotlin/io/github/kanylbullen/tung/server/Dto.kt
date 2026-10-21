package io.github.kanylbullen.tung.server

import kotlinx.serialization.Serializable

// Request and response bodies. Property names are camelCase here and
// snake_case on the wire (ApiJson's naming strategy); openapi.yaml describes
// the wire format and must change with these classes.

@Serializable
data class Credentials(val email: String, val password: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val createdAt: Long,
    /** False for an account made with Google that never set a password: password login is refused. */
    val hasPassword: Boolean,
    /** A Google account is linked: signing in with Google reaches this user. */
    val googleLinked: Boolean,
)

/** `POST /api/v1/auth/google`: a Google ID token (a JWT) from Credential Manager or Google Identity Services. */
@Serializable
data class GoogleSignIn(val idToken: String)

/** `GET /api/v1/auth/methods`: how this instance lets users sign in. */
@Serializable
data class AuthMethods(val password: Boolean, val google: GoogleMethod?)

/** The OAuth client id clients pass to Google (Credential Manager's `serverClientId`). */
@Serializable
data class GoogleMethod(val clientId: String)

@Serializable
data class SessionDto(val token: String, val user: UserDto)

@Serializable
data class TokenDto(
    val id: String,
    val name: String,
    val scope: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
    /** When it stops working; null for tokens that never expire. Tokens issued through OAuth (MCP) have one, moved on by each refresh. */
    val expiresAt: Long? = null,
    /** True for the token this request was made with. */
    val current: Boolean,
)

@Serializable
data class TokenList(val tokens: List<TokenDto>)

@Serializable
data class NewToken(val name: String, val scope: String)

/** The plaintext [token] is in this response and nowhere else, ever. */
@Serializable
data class CreatedToken(val id: String, val name: String, val scope: String, val createdAt: Long, val token: String)

@Serializable
data class ExerciseDto(
    val id: String,
    val nameKey: String?,
    val name: String,
    val muscleGroup: String,
    val equipment: String,
    val isCustom: Boolean,
    val restSeconds: Int?,
    val updatedAt: Long,
)

@Serializable
data class ExerciseList(val exercises: List<ExerciseDto>)

@Serializable
data class NewExercise(
    val id: String? = null,
    val name: String,
    val muscleGroup: String,
    val equipment: String,
    val restSeconds: Int? = null,
)

@Serializable
data class SetDto(
    val id: String,
    val position: Int,
    val weightKg: Double,
    val reps: Int,
    val completed: Boolean,
    val updatedAt: Long,
)

@Serializable
data class WorkoutExerciseDto(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val updatedAt: Long,
    val sets: List<SetDto>,
)

@Serializable
data class WorkoutDto(
    val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val note: String?,
    val routineId: String?,
    val programDayId: String?,
    val updatedAt: Long,
    val exercises: List<WorkoutExerciseDto>,
    /** Who started it, one of core's `StartedBy` values; null when unknown. */
    val startedBy: String? = null,
    /** From Health Connect on the phone that finished it (V9); null when not read. Read only here: they arrive by sync. */
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val activeCalories: Int? = null,
    /** The Health Connect session written for it; null when none was. */
    val healthConnectId: String? = null,
    /** The app it was imported from (V11, `jefit`) and the import run; null for a workout logged in Tung. Arrive by sync only. */
    val importSource: String? = null,
    val importBatchId: String? = null,
)

@Serializable
data class WorkoutPage(
    val workouts: List<WorkoutDto>,
    /** Pass as `before` for the next page; null on the last page. */
    val nextBefore: Long?,
)

@Serializable
data class SetInput(
    val id: String,
    val position: Int,
    val weightKg: Double,
    val reps: Int,
    val completed: Boolean = false,
    val updatedAt: Long? = null,
)

@Serializable
data class WorkoutExerciseInput(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val updatedAt: Long? = null,
    val sets: List<SetInput> = emptyList(),
)

@Serializable
data class WorkoutInput(
    val id: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val note: String? = null,
    val routineId: String? = null,
    val programDayId: String? = null,
    val updatedAt: Long? = null,
    val exercises: List<WorkoutExerciseInput> = emptyList(),
    /** One of core's `StartedBy` values. Null keeps the stored value: it is set once, when the workout is made. */
    val startedBy: String? = null,
)

@Serializable
data class FinishInput(val endedAt: Long? = null)

@Serializable
data class FinishResult(
    /** The finished workout; null when it was discarded. */
    val workout: WorkoutDto?,
    /** True when the workout had no logged set and was deleted instead of saved, as in the app. */
    val discarded: Boolean,
)

@Serializable
data class LastSetDto(val id: String, val position: Int, val weightKg: Double, val reps: Int)

@Serializable
data class LastSession(
    val exerciseId: String,
    /** The workout the sets come from; null when the exercise has no finished history. */
    val workoutId: String?,
    val startedAt: Long?,
    val sets: List<LastSetDto>,
)

@Serializable
data class RoutineExerciseDto(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val targetSets: Int,
    val targetReps: Int?,
    val updatedAt: Long,
)

@Serializable
data class RoutineDto(
    val id: String,
    val name: String,
    val lastUsedAt: Long?,
    val updatedAt: Long,
    val exercises: List<RoutineExerciseDto>,
)

@Serializable
data class ProgramDayDto(
    val id: String,
    val routineId: String,
    val position: Int,
    /** ISO weekdays as bits, Monday in bit 0. */
    val weekdays: Int,
    val updatedAt: Long,
)

@Serializable
data class ProgramDto(
    val id: String,
    val name: String,
    val mode: String,
    val isActive: Boolean,
    val updatedAt: Long,
    val days: List<ProgramDayDto>,
)

@Serializable
data class RoutineList(val routines: List<RoutineDto>)

@Serializable
data class ProgramList(val programs: List<ProgramDto>)

@Serializable
data class RoutineExerciseInput(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val targetSets: Int,
    val targetReps: Int? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class RoutineInput(
    /** Must equal the path id on PUT; on POST the new routine's id, made by the server when absent. */
    val id: String? = null,
    val name: String,
    /** Null keeps the stored value: it is set when a workout is started from the routine. */
    val lastUsedAt: Long? = null,
    val updatedAt: Long? = null,
    val exercises: List<RoutineExerciseInput> = emptyList(),
)

@Serializable
data class ProgramDayInput(
    val id: String,
    val routineId: String,
    val position: Int,
    /** ISO weekdays as bits, Monday in bit 0. */
    val weekdays: Int = 0,
    val updatedAt: Long? = null,
)

@Serializable
data class ProgramInput(
    /** Must equal the path id on PUT; on POST the new program's id, made by the server when absent. */
    val id: String? = null,
    val name: String,
    /** core's `ProgramMode` key: `fixed` or `rotating`. */
    val mode: String,
    val isActive: Boolean = false,
    val updatedAt: Long? = null,
    val days: List<ProgramDayInput> = emptyList(),
)

@Serializable
data class ExportTokenDto(val name: String, val scope: String, val createdAt: Long)

@Serializable
data class ExportDocument(
    val format: String = "tung-export",
    val version: Int = 1,
    val exportedAt: Long,
    val user: UserDto,
    /** The user's own exercises: custom ones and their copies of catalogue exercises. */
    val exercises: List<ExerciseDto>,
    val workouts: List<WorkoutDto>,
    val routines: List<RoutineDto>,
    val programs: List<ProgramDto>,
    /** Names and scopes only; never a token or its hash. */
    val tokens: List<ExportTokenDto>,
)

@Serializable
data class Health(val status: String)
