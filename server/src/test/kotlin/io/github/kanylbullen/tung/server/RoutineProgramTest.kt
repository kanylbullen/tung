package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncProgram
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

fun routineExercise(exerciseId: String, position: Int, targetSets: Int = 3, targetReps: Int? = 8, updatedAt: Long? = 1_000L, id: String = id()) =
    RoutineExerciseInput(id, exerciseId, position, targetSets, targetReps, updatedAt)

fun routineInput(name: String, vararg exercises: RoutineExerciseInput, updatedAt: Long? = 1_000L, id: String? = null) =
    RoutineInput(id = id, name = name, updatedAt = updatedAt, exercises = exercises.toList())

fun programInput(name: String, vararg days: ProgramDayInput, active: Boolean = false, updatedAt: Long? = 1_000L, mode: String = "fixed", id: String? = null) =
    ProgramInput(id = id, name = name, mode = mode, isActive = active, updatedAt = updatedAt, days = days.toList())

fun day(routineId: String, position: Int, weekdays: Int = 0b1, updatedAt: Long? = 1_000L, id: String = id()) =
    ProgramDayInput(id, routineId, position, weekdays, updatedAt)

suspend fun HttpClient.postRoutine(token: String, input: RoutineInput): HttpResponse =
    post("/api/v1/routines") { bearerAuth(token); jsonBody(ApiJson.encodeToString(RoutineInput.serializer(), input)) }

suspend fun HttpClient.putRoutine(token: String, id: String, input: RoutineInput): HttpResponse =
    put("/api/v1/routines/$id") { bearerAuth(token); jsonBody(ApiJson.encodeToString(RoutineInput.serializer(), input)) }

suspend fun HttpClient.postProgram(token: String, input: ProgramInput): HttpResponse =
    post("/api/v1/programs") { bearerAuth(token); jsonBody(ApiJson.encodeToString(ProgramInput.serializer(), input)) }

suspend fun HttpClient.putProgram(token: String, id: String, input: ProgramInput): HttpResponse =
    put("/api/v1/programs/$id") { bearerAuth(token); jsonBody(ApiJson.encodeToString(ProgramInput.serializer(), input)) }

suspend fun HttpResponse.routine(): RoutineDto = ApiJson.decodeFromString(RoutineDto.serializer(), bodyAsText())

suspend fun HttpResponse.program(): ProgramDto = ApiJson.decodeFromString(ProgramDto.serializer(), bodyAsText())

suspend fun HttpClient.readToken(session: String): String =
    post("/api/v1/tokens") { bearerAuth(session); jsonBody("""{"name": "reader", "scope": "read"}""") }.json()["token"]!!.str

/** A routine made over REST, for tests that need one; returns its id. */
suspend fun HttpClient.someRoutine(token: String, name: String = "Push"): String {
    val response = postRoutine(token, routineInput(name, routineExercise(BENCH, 0)))
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.routine().id
}

class RoutineProgramTest {

    @Test
    fun aRoutineIsCreatedListedReadAndItsIdCannotBePostedTwice() = tungTest {
        val token = client.register()
        val created = client.postRoutine(token, routineInput("Push day", routineExercise(BENCH, 0, 5, 5), routineExercise(SQUAT, 1, 3, null)))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val routine = created.routine()
        assertTrue(isUuid(routine.id), "the server makes the id when the body has none")
        assertEquals("Push day", routine.name)
        assertEquals(listOf(BENCH to 5, SQUAT to 3), routine.exercises.map { it.exerciseId to it.targetSets })
        assertEquals(listOf(5, null), routine.exercises.map { it.targetReps })

        assertEquals(routine, client.authedGet("/api/v1/routines/${routine.id}", token).routine(), "POST answers with what GET returns")
        val list = ApiJson.decodeFromString(RoutineList.serializer(), client.authedGet("/api/v1/routines", token).bodyAsText())
        assertEquals(listOf(routine), list.routines)

        // A client's own id is kept; a second POST with it is a conflict, not an overwrite.
        val own = id()
        assertEquals(HttpStatusCode.Created, client.postRoutine(token, routineInput("Legs", id = own)).status)
        val again = client.postRoutine(token, routineInput("Other", id = own, updatedAt = 9_000))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("already_exists", again.errorCode())
        assertEquals("Legs", client.authedGet("/api/v1/routines/$own", token).routine().name)

        // Validation is the command's: target sets 1..20, known exercises, a name.
        assertEquals(HttpStatusCode.BadRequest, client.postRoutine(token, routineInput("Bad", routineExercise(BENCH, 0, targetSets = 21))).status)
        val unknown = client.postRoutine(token, routineInput("Bad", routineExercise(id(), 0)))
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals("unknown_exercise", unknown.errorCode())
        assertEquals(HttpStatusCode.BadRequest, client.postRoutine(token, routineInput("  ")).status)
    }

    @Test
    fun putAppliesTheRowRuleToTheRoutineAndEachExercise() = tungTest {
        val token = client.register()
        val routineId = id()
        val bench = routineExercise(BENCH, 0, updatedAt = 2_000)
        val squat = routineExercise(SQUAT, 1, updatedAt = 2_000)
        val first = client.putRoutine(token, routineId, routineInput("Push", bench, squat, updatedAt = 2_000))
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())

        // Older and equal writes are ignored, row by row.
        val older = client.putRoutine(token, routineId, routineInput("Renamed", bench.copy(targetSets = 9, updatedAt = 1_500), squat, updatedAt = 1_000)).routine()
        assertEquals("Push", older.name)
        assertEquals(3, older.exercises.first { it.id == bench.id }.targetSets)
        val equal = client.putRoutine(token, routineId, routineInput("Renamed", bench.copy(targetSets = 9), squat, updatedAt = 2_000)).routine()
        assertEquals("Push", equal.name)
        assertEquals(3, equal.exercises.first { it.id == bench.id }.targetSets)

        // A newer exercise row wins even when the routine row does not.
        val newerChild = client.putRoutine(token, routineId, routineInput("Renamed", bench.copy(targetSets = 4, updatedAt = 3_000), squat, updatedAt = 1_000)).routine()
        assertEquals("Push", newerChild.name)
        assertEquals(4, newerChild.exercises.first { it.id == bench.id }.targetSets)

        // A child missing from the body goes only when it is older than the body's routine.
        val dropped = client.putRoutine(token, routineId, routineInput("Push", bench.copy(updatedAt = 3_000), updatedAt = 2_500)).routine()
        assertEquals(listOf(bench.id), dropped.exercises.map { it.id }, "squat (2000) is older than the body (2500)")
        val press = routineExercise(SQUAT, 1, updatedAt = 5_000)
        client.putRoutine(token, routineId, routineInput("Push", bench.copy(updatedAt = 3_000), press, updatedAt = 2_600))
        val kept = client.putRoutine(token, routineId, routineInput("Push", bench.copy(updatedAt = 3_000), updatedAt = 4_000)).routine()
        assertEquals(listOf(bench.id, press.id), kept.exercises.map { it.id }, "a child written after the client's copy stays")

        val mismatch = client.putRoutine(token, routineId, routineInput("Push", id = id(), updatedAt = 9_000))
        assertEquals(HttpStatusCode.BadRequest, mismatch.status)
    }

    @Test
    fun anotherUsersRoutineOrProgramIsNotFoundAndAReadTokenCannotWrite() = tungTest {
        val alice = client.register()
        val bob = client.register()
        val routineId = client.someRoutine(alice)
        val programId = client.postProgram(alice, programInput("PPL", day(routineId, 0))).program().id

        assertEquals(HttpStatusCode.NotFound, client.authedGet("/api/v1/routines/$routineId", bob).status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/routines/$routineId") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.NotFound, client.authedGet("/api/v1/programs/$programId", bob).status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/programs/$programId") { bearerAuth(bob) }.status)
        // Bob's PUT under the same id is Bob's own row; Alice's is untouched, and Bob cannot use her routine in a program.
        assertEquals(HttpStatusCode.OK, client.putRoutine(bob, routineId, routineInput("Bob's", updatedAt = 9_000)).status)
        assertEquals("Push", client.authedGet("/api/v1/routines/$routineId", alice).routine().name)
        val foreign = client.postProgram(bob, programInput("Bob's", day(id(), 0)))
        assertEquals("unknown_routine", foreign.errorCode())
        assertTrue(ApiJson.decodeFromString(ProgramList.serializer(), client.authedGet("/api/v1/programs", bob).bodyAsText()).programs.isEmpty())

        val reader = client.readToken(alice)
        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/routines", reader).status)
        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/programs/$programId", reader).status)
        assertEquals(HttpStatusCode.Forbidden, client.postRoutine(reader, routineInput("Nope")).status)
        assertEquals(HttpStatusCode.Forbidden, client.putRoutine(reader, routineId, routineInput("Nope", updatedAt = 9_000)).status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/routines/$routineId") { bearerAuth(reader) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.postProgram(reader, programInput("Nope")).status)
        assertEquals(HttpStatusCode.Forbidden, client.putProgram(reader, programId, programInput("Nope", updatedAt = 9_000)).status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/programs/$programId") { bearerAuth(reader) }.status)
        assertEquals("Push", client.authedGet("/api/v1/routines/$routineId", alice).routine().name)
        assertEquals("PPL", client.authedGet("/api/v1/programs/$programId", alice).program().name)
    }

    @Test
    fun deletingARoutineTombstonesItsExercisesAndTheProgramDaysThatUseIt() = tungTest {
        val token = client.register()
        val push = client.someRoutine(token, "Push")
        val pull = client.someRoutine(token, "Pull")
        val program = client.postProgram(token, programInput("PPL", day(push, 0), day(pull, 1))).program()
        val cursor = client.synced(token, 0).cursor

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/routines/$push") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.authedGet("/api/v1/routines/$push", token).status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/routines/$push") { bearerAuth(token) }.status, "deleting twice")
        assertEquals(listOf(pull), client.authedGet("/api/v1/programs/${program.id}", token).program().days.map { it.routineId })

        // Tombstones, not deletes: the next pull carries them to the phone.
        val changes = client.synced(token, cursor).changes
        assertNotNull(changes.routines.single { it.id == push }.deletedAt)
        assertTrue(changes.routineExercises.isNotEmpty() && changes.routineExercises.all { it.deletedAt != null })
        assertEquals(listOf(program.days[0].id), changes.programDays.map { it.id })
        assertNotNull(changes.programDays.single().deletedAt)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/programs/${program.id}") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.authedGet("/api/v1/programs/${program.id}", token).status)
        val after = client.synced(token, cursor).changes
        assertTrue(after.programDays.all { it.deletedAt != null }, "both days are tombstoned")
        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/routines/$pull", token).status, "deleting a program keeps its routines")
    }

    @Test
    fun aProgramRoundtripsWithItsDaysUnderTheRowRule() = tungTest {
        val token = client.register()
        val push = client.someRoutine(token, "Push")
        val legs = client.someRoutine(token, "Legs")
        val first = day(push, 0, weekdays = 0b101, updatedAt = 2_000)
        val second = day(legs, 1, weekdays = 0b10, updatedAt = 2_000)
        val created = client.postProgram(token, programInput("Split", first, second, mode = "rotating", updatedAt = 2_000))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val program = created.program()
        assertEquals("rotating", program.mode)
        assertEquals(listOf(push to 0b101, legs to 0b10), program.days.map { it.routineId to it.weekdays })
        assertEquals(program, client.authedGet("/api/v1/programs/${program.id}", token).program())

        val older = client.putProgram(token, program.id, programInput("Older", first.copy(weekdays = 0b1111111, updatedAt = 1_000), second, updatedAt = 1_000)).program()
        assertEquals(program, older, "older rows are ignored")
        val newer = client.putProgram(token, program.id, programInput("Newer", first.copy(weekdays = 0b1, updatedAt = 3_000), updatedAt = 3_000)).program()
        assertEquals("Newer", newer.name)
        assertEquals(listOf(first.id to 0b1), newer.days.map { it.id to it.weekdays }, "the missing, older day is tombstoned")

        assertEquals(HttpStatusCode.BadRequest, client.postProgram(token, programInput("Bad", mode = "weekly")).status)
        assertEquals(HttpStatusCode.BadRequest, client.postProgram(token, programInput("Bad", day(push, 0, weekdays = 128))).status)
    }

    @Test
    fun putKeepsAtMostOneActiveProgramTheNewest() = tungTest {
        val token = client.register()
        val a = client.postProgram(token, programInput("A", active = true, updatedAt = 1_000)).program()
        val b = client.putProgram(token, id(), programInput("B", active = true, updatedAt = 2_000)).program()
        assertTrue(b.isActive)
        val aNow = client.authedGet("/api/v1/programs/${a.id}", token).program()
        assertFalse(aNow.isActive, "the older active program is switched off")
        assertTrue(aNow.updatedAt > 1_000, "with a server stamp, so other devices get it")

        // An activation older than the active one loses, and the answer says so.
        val c = client.putProgram(token, id(), programInput("C", active = true, updatedAt = 1_500)).program()
        assertFalse(c.isActive)
        assertTrue(client.authedGet("/api/v1/programs/${b.id}", token).program().isActive)
        val all = ApiJson.decodeFromString(ProgramList.serializer(), client.authedGet("/api/v1/programs", token).bodyAsText()).programs
        assertEquals(listOf(b.id), all.filter { it.isActive }.map { it.id })
    }

    @Test
    fun twoDevicesActivatingDifferentProgramsOfflineConvergeOnTheNewest() = tungTest {
        val email = uniqueEmail()
        val phone = client.register(email)
        val watch = client.login(email)
        // Someone else's active program is never touched.
        val other = client.register()
        val theirs = SyncProgram(id(), "Theirs", "fixed", true, 500)
        client.synced(other, 0, SyncChanges(programs = listOf(theirs)))

        val p1 = SyncProgram(id(), "Upper/lower", "fixed", true, 1_000)
        val phoneCursor = client.synced(phone, 0, SyncChanges(programs = listOf(p1))).cursor
        val p2 = SyncProgram(id(), "Full body", "rotating", true, 2_000)
        val watchPush = client.synced(watch, 0, SyncChanges(programs = listOf(p2)))
        assertEquals(true, watchPush.changes.programs.single { it.id == p2.id }.isActive)
        assertEquals(false, watchPush.changes.programs.single { it.id == p1.id }.isActive)

        // The phone, which lost, receives the correction on its next pull.
        val pulled = client.synced(phone, phoneCursor).changes.programs
        val corrected = pulled.single { it.id == p1.id }
        assertEquals(false, corrected.isActive)
        assertTrue(corrected.updatedAt > p1.updatedAt, "a newer updated_at, so the phone applies it")
        assertEquals(listOf(p2.id), client.synced(phone, 0).changes.programs.filter { it.isActive == true }.map { it.id })

        // An older activation pushed later loses too, and its device hears it in the same answer.
        val p3 = SyncProgram(id(), "Old plan", "fixed", true, 1_500)
        val late = client.synced(phone, 0, SyncChanges(programs = listOf(p3))).changes.programs
        assertEquals(false, late.single { it.id == p3.id }.isActive)
        assertEquals(true, late.single { it.id == p2.id }.isActive)

        assertEquals(listOf(theirs), client.synced(other, 0).changes.programs, "another user's rows are untouched")
    }

    @Test
    fun aTieOnUpdatedAtGoesToTheProgramJustPushed() = tungTest {
        val token = client.register()
        val first = SyncProgram(id(), "First", "fixed", true, 1_000)
        client.synced(token, 0, SyncChanges(programs = listOf(first)))
        val second = SyncProgram(id(), "Second", "fixed", true, 1_000)
        val answer = client.synced(token, 0, SyncChanges(programs = listOf(second))).changes.programs
        assertEquals(listOf(second.id), answer.filter { it.isActive == true }.map { it.id })
    }
}
