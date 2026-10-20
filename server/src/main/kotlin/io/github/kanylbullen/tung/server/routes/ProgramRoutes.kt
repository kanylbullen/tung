package io.github.kanylbullen.tung.server.routes

import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.server.ProgramList
import io.github.kanylbullen.tung.server.RoutineStore
import io.github.kanylbullen.tung.server.command.CommandService
import io.github.kanylbullen.tung.server.notFound
import io.github.kanylbullen.tung.server.pathId
import io.github.kanylbullen.tung.server.push.ChangeNotifier
import io.github.kanylbullen.tung.server.push.sourceDevice
import io.github.kanylbullen.tung.server.user
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * `/api/v1/programs`, mirroring the workouts API: reads return the stored
 * rows with their days; `POST` and `PUT` write by the row rule
 * ([RoutineStore]) and then keep at most one program active (newest
 * `updated_at` wins); `DELETE` runs core's `delete_program` through the
 * command handler, which tombstones the program and its days. Mounted inside
 * the bearer block that refuses writes with a read token.
 */
fun Route.programRoutes(routines: RoutineStore, commands: CommandService, push: ChangeNotifier) {
    route("/programs") {
        get { call.respond(ProgramList(routines.programs(call.user().userId))) }
        post {
            val created = routines.createProgram(call.user().userId, call.receive())
            push.changed(call.user().userId, call.sourceDevice())
            call.respond(HttpStatusCode.Created, created)
        }
        get("/{id}") { call.respond(routines.program(call.user().userId, call.pathId())) }
        put("/{id}") {
            val stored = routines.putProgram(call.user().userId, call.pathId(), call.receive())
            push.changed(call.user().userId, call.sourceDevice())
            call.respond(stored)
        }
        delete("/{id}") {
            // The handler notifies the user's other devices itself.
            val result = commands.handlerFor(call.user().userId, call.sourceDevice()).execute(Command.DeleteProgram(call.pathId()))
            if (result !is CommandResult.Done) throw notFound("Program")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
