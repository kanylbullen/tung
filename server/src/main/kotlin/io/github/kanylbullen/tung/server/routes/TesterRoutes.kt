package io.github.kanylbullen.tung.server.routes

import io.github.kanylbullen.tung.server.Admins
import io.github.kanylbullen.tung.server.ApiException
import io.github.kanylbullen.tung.server.Db
import io.github.kanylbullen.tung.server.TokenPrincipal
import io.github.kanylbullen.tung.server.badRequest
import io.github.kanylbullen.tung.server.user
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll

/** The most sign-ups kept: a full list answers 503 `list_full` instead of growing for a bot. */
const val MAX_TESTER_SIGNUPS = 5_000

object TesterSignups : Table("tester_signups") {
    val email = text("email")
    val createdAt = long("created_at")
    val language = text("language").nullable()
    override val primaryKey = PrimaryKey(email)
}

@Serializable
data class TesterSignup(
    val email: String,
    /** The box "use my email only to invite me to the test" was ticked. */
    val consent: Boolean = false,
    /** The page's language, `en` or `sv`, for the invitation. */
    val language: String? = null,
    /** A field people never see: a bot that fills it in is answered as anyone else and stored as no one. */
    val website: String? = null,
)

@Serializable
data class TesterSignupAccepted(val status: String = "signed_up")

@Serializable
data class TesterDto(
    val email: String,
    @SerialName("created_at") val createdAt: Long,
    val language: String?,
)

@Serializable
data class TesterList(val testers: List<TesterDto>)

/**
 * Sign-ups to test the Android app on Google Play, from the landing page.
 * Anyone may sign up ([signUp]); an admin (`TUNG_ADMIN_EMAILS`) lists them
 * to copy into the Play Console and deletes them once invited. Only while
 * [enabled] (`TUNG_TESTER_SIGNUPS`): a self-hosted server runs no Play test,
 * so there every call is 404 `not_configured`.
 */
class Testers(private val db: Db, private val admins: Admins, private val enabled: Boolean, private val clock: () -> Long) {

    /**
     * Stores [request]'s address once. The answer is the same whether the
     * address was new, already there, or the honeypot was filled in, so the
     * form tells nobody who else signed up.
     */
    suspend fun signUp(request: TesterSignup) {
        requireEnabled()
        val email = normalize(request.email)
        if (!request.consent) throw badRequest("Tick the box to say we may email you the invitation.", "consent_required")
        if (!request.website.isNullOrEmpty()) return
        val language = request.language?.lowercase()?.takeIf { it in LANGUAGES }
        db.tx {
            if (TesterSignups.selectAll().count() >= MAX_TESTER_SIGNUPS) {
                throw ApiException(HttpStatusCode.ServiceUnavailable, "list_full", "The test is full for now. Try again later.")
            }
            TesterSignups.insertIgnore {
                it[TesterSignups.email] = email
                it[createdAt] = clock()
                it[TesterSignups.language] = language
            }
        }
    }

    suspend fun list(principal: TokenPrincipal): TesterList {
        requireEnabled()
        admins.require(principal)
        return db.tx {
            TesterList(
                TesterSignups.selectAll().orderBy(TesterSignups.createdAt to SortOrder.ASC).map {
                    TesterDto(it[TesterSignups.email], it[TesterSignups.createdAt], it[TesterSignups.language])
                },
            )
        }
    }

    suspend fun delete(principal: TokenPrincipal, email: String) {
        requireEnabled()
        admins.require(principal)
        val address = email.trim().lowercase()
        db.tx { TesterSignups.deleteWhere { TesterSignups.email eq address } }
    }

    private fun requireEnabled() {
        if (!enabled) throw ApiException(HttpStatusCode.NotFound, "not_configured", "This server takes no test sign-ups (TUNG_TESTER_SIGNUPS).")
    }

    private fun normalize(raw: String): String {
        val email = raw.trim().lowercase()
        if (email.length > 254 || !EMAIL.matches(email)) throw badRequest("That is not an email address.", "invalid_email")
        return email
    }

    private companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val LANGUAGES = setOf("en", "sv")
    }
}

/** `POST /testers`, public; mounted under the auth rate limit. */
fun Route.testerSignupRoute(testers: Testers) {
    post("/testers") {
        testers.signUp(call.receive())
        call.respond(HttpStatusCode.Accepted, TesterSignupAccepted())
    }
}

/** `GET /testers` and `DELETE /testers/{email}`, behind the bearer token; mounted with the authenticated routes. */
fun Route.testerAdminRoutes(testers: Testers) {
    route("/testers") {
        get { call.respond(testers.list(call.user())) }
        delete("/{email}") {
            testers.delete(call.user(), call.parameters["email"] ?: throw badRequest("Missing path parameter email."))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
