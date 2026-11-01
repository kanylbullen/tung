package io.github.kanylbullen.tung.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One plain-text email to one address. */
data class MailMessage(val to: String, val subject: String, val text: String)

/** Sends email. [ResendMailer] on an instance with `TUNG_RESEND_API_KEY`; tests hand in their own. */
fun interface Mailer {
    /** Throws when the message was not accepted; [WelcomeMail] logs it and goes on. */
    suspend fun send(message: MailMessage)
}

/**
 * Resend's API (`POST /emails`, a bearer key): [from] must be on a domain
 * verified there, such as `Tung <tung@xuper.fun>`. No redirects, ten
 * seconds in all.
 */
class ResendMailer(
    private val apiKey: String,
    private val from: String,
    private val baseUrl: String = RESEND_BASE_URL,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : Mailer {

    override suspend fun send(message: MailMessage) {
        val body = buildJsonObject {
            put("from", from)
            put("to", buildJsonArray { add(JsonPrimitive(message.to)) })
            put("subject", message.subject)
            put("text", message.text)
        }.toString()
        val request = HttpRequest.newBuilder(URI("$baseUrl/emails"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        // Only the status and Resend's error name ("validation_error", …): its message can quote the recipient.
        check(response.statusCode() in 200..299) {
            val name = runCatching { ApiJson.parseToJsonElement(response.body()).jsonObject["name"]?.jsonPrimitive?.content }.getOrNull()
            "Resend answered ${response.statusCode()}${name?.let { " ($it)" }.orEmpty()}"
        }
    }

    companion object {
        const val RESEND_BASE_URL = "https://api.resend.com"
    }
}

/**
 * The two emails the server sends, each once: a welcome to a new account and
 * a thank-you for a test sign-up. In the person's language (`sv`, else
 * English), plain text, with the links of [publicUrl]. Sent on [scope] after
 * the request has been answered: a mail that fails is logged and never fails
 * the sign-up. Without a [mailer] (no `TUNG_RESEND_API_KEY`) nothing is sent.
 */
class WelcomeMail(
    private val mailer: Mailer?,
    private val publicUrl: String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val perDay: Int = MAX_WELCOME_MAILS_PER_DAY,
) {
    private val log = LoggerFactory.getLogger(WelcomeMail::class.java)

    /** The UTC day ([clock] / one day) and how many mails it has sent. */
    private var day = -1L
    private var sentToday = 0

    fun account(email: String, language: String?) = send { url -> accountMessage(email, swedish(language), url) }

    fun tester(email: String, language: String?) = send { url -> testerMessage(email, swedish(language), url) }

    /**
     * At most [perDay] mails a UTC day, whoever asks: registration is open
     * and takes any address, so without a ceiling one could make the server
     * mail strangers by the thousand and spend the sender's reputation.
     */
    private fun underCeiling(): Boolean = synchronized(this) {
        val today = clock() / DAY_MILLIS
        if (today != day) {
            day = today
            sentToday = 0
        }
        if (sentToday >= perDay) {
            if (sentToday == perDay) log.warn("{} welcome emails today: no more are sent until tomorrow (UTC)", perDay)
            sentToday = perDay + 1
            return false
        }
        sentToday++
        true
    }

    /** Builds and sends the message off the request, so not even a bad `TUNG_PUBLIC_URL` can fail a sign-up. */
    private fun send(build: (url: String) -> MailMessage) {
        val mailer = mailer ?: return
        val url = publicUrl ?: return
        if (!underCeiling()) return
        scope.launch(Dispatchers.IO) {
            try {
                mailer.send(build(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The address is the person's; the log says only that a mail failed and why.
                log.warn("A welcome email could not be sent: {}", e.message)
            }
        }
    }

    /** `sv`, `sv-SE`, or a browser's `Accept-Language` whose first choice is Swedish; anything else is English. */
    private fun swedish(language: String?) =
        language?.substringBefore(',')?.substringBefore(';')?.trim()?.lowercase()?.substringBefore('-') == "sv"

    companion object {
        /** Far above a day of real sign-ups on one instance, far below what would hurt the sender. */
        const val MAX_WELCOME_MAILS_PER_DAY = 200
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        internal fun accountMessage(email: String, swedish: Boolean, url: String): MailMessage {
            val host = URI(url).host ?: url
            return if (swedish) {
                MailMessage(
                    email,
                    "Välkommen till Tung",
                    """
                    |Hej!
                    |
                    |Ditt Tung-konto på $host är klart. Din träning synkas nu mellan dina enheter och webbappen.
                    |
                    |Tung är i beta. Appen och servern kan ha fel och störningar. Tjänsten är gratis och drivs på bästa förmåga, utan garantier under testperioden om att den finns kvar eller fungerar. Din träning finns alltid kvar på telefonen också.
                    |
                    |Webbappen: $url/app/
                    |Säg vad du tycker: Inställningar → Feedback i appen.
                    |Integritet: $url/privacy
                    |Radera kontot: $url/delete-account
                    |
                    |Du får det här mejlet en gång, för att du skapade ett konto. Har du inte gjort det? Svara på mejlet så tar vi bort kontot.
                    |
                    |Tung
                    """.trimMargin(),
                )
            } else {
                MailMessage(
                    email,
                    "Welcome to Tung",
                    """
                    |Hi,
                    |
                    |Your Tung account on $host is ready. Your training now syncs between your devices and the web app.
                    |
                    |Tung is in beta. The app and the server can have bugs and outages. The service is free and run on a best-effort basis, with no guarantee during the test period that it stays available or keeps working. Your training always stays on your phone as well.
                    |
                    |The web app: $url/app/
                    |Tell us what you think: Settings → Feedback in the app.
                    |Privacy: $url/privacy
                    |Delete your account: $url/delete-account
                    |
                    |You get this email once, because you created an account. Didn't you? Reply and we delete it.
                    |
                    |Tung
                    """.trimMargin(),
                )
            }
        }

        internal fun testerMessage(email: String, swedish: Boolean, url: String): MailMessage =
            if (swedish) {
                MailMessage(
                    email,
                    "Du är anmäld till testet av Tung",
                    """
                    |Hej!
                    |
                    |Tack för att du vill testa Tung på Android. När nästa testomgång öppnar kommer en inbjudan från Google Play till den här adressen.
                    |
                    |Tills dess kan du prova webbappen: $url/app/
                    |
                    |Tung är i beta. Appen och servern kan ha fel och störningar. Tjänsten är gratis och drivs på bästa förmåga, utan garantier under testperioden om att den finns kvar eller fungerar.
                    |
                    |Har du inte anmält dig? Strunta i mejlet, eller svara så tar vi bort adressen.
                    |
                    |Tung
                    """.trimMargin(),
                )
            } else {
                MailMessage(
                    email,
                    "You're on the list to test Tung",
                    """
                    |Hi,
                    |
                    |Thanks for signing up to test Tung on Android. When the next test round opens, an invitation from Google Play comes to this address.
                    |
                    |Until then you can try the web app: $url/app/
                    |
                    |Tung is in beta. The app and the server can have bugs and outages. The service is free and run on a best-effort basis, with no guarantee during the test period that it stays available or keeps working.
                    |
                    |Didn't sign up? Ignore this email, or reply and we remove the address.
                    |
                    |Tung
                    """.trimMargin(),
                )
            }
    }
}
