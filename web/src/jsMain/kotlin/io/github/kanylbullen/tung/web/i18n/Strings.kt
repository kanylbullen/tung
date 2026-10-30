package io.github.kanylbullen.tung.web.i18n

import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.web.sync.AuthFailure

/**
 * The web app's texts. English is the default and Swedish the first
 * translation, as in the apps; the wording follows the phone's strings
 * where the screen is the same. A new language is a new object here and a
 * line in [stringsFor].
 */
interface Strings {
    /** The language's tag, for `<html lang>` and `Intl`. */
    val tag: String

    val appName: String

    /** Between the parts of one line ("Thu 22 Oct · 47 min"), the phone's `row_separator`. */
    val separator: String
    val loading: String
    val storageUnavailable: String

    // Sign in
    val signInIntro: String
    val email: String
    val password: String
    val server: String
    val useAnotherServer: String
    val signIn: String
    val signingIn: String

    /** Under the Google button, above the email form. */
    val orWithEmail: String
    val errorFields: String
    val errorUrl: String
    val errorUrlHttps: String
    fun authFailure(failure: AuthFailure): String

    // Home
    val home: String
    val thisWeek: String
    fun weekWorkouts(count: Int): String
    fun weekSets(count: Int): String
    val weekNone: String
    fun weekLast(volume: String): String
    val weekBest: String
    fun weekDaysDescription(days: String): String
    val weekDaysNone: String
    fun inProgressFor(duration: String): String
    val inProgressHint: String
    val routines: String
    val routinesEmpty: String
    val recent: String
    val homeEmpty: String
    val showAll: String

    // History
    val history: String
    val historyEmpty: String
    val workoutUntitled: String
    val kg: String
    fun exerciseCount(count: Int): String
    fun durationMinutes(minutes: Int): String
    fun durationHoursMinutes(hours: Int, minutes: Int): String

    // Account and sync
    fun signedInAs(email: String): String
    val signOut: String
    val syncNow: String
    val syncing: String
    val lastSyncNever: String
    val lastSyncJustNow: String
    fun lastSyncMinutesAgo(minutes: Int): String
    fun lastSyncHoursAgo(hours: Int): String
    fun lastSyncOn(date: String): String
    fun syncFailure(failure: SyncFailure): String
}

object English : Strings {
    override val tag = "en"
    override val appName = "Tung"
    override val separator = " · "
    override val loading = "Loading…"
    override val storageUnavailable = "This browser does not let Tung store anything, so it cannot run here. Try another browser, or leave private browsing."

    override val signInIntro = "Sign in to your Tung server to see your training here. It stays in this browser, so it opens without a connection too."
    override val email = "Email"
    override val password = "Password"
    override val server = "Server address"
    override val useAnotherServer = "Use another server"
    override val signIn = "Sign in"
    override val signingIn = "Signing in…"
    override val orWithEmail = "Or with email and password"
    override val errorFields = "Enter your email and password."
    override val errorUrl = "Enter the server address, for example https://tung.example.org."
    override val errorUrlHttps = "Use https://. Plain http only works for localhost and addresses on your own network."
    override fun authFailure(failure: AuthFailure): String = when (failure) {
        AuthFailure.INVALID_CREDENTIALS -> "Wrong email or password."
        AuthFailure.INVALID_REQUEST -> "The server did not accept that email address. Check it and try again."
        AuthFailure.RATE_LIMITED -> "Too many attempts. Wait a minute and try again."
        AuthFailure.NOT_A_SERVER -> "No Tung server answers at that address. Check it and try again."
        AuthFailure.NETWORK -> "Could not reach the server. Check the address and your connection."
        AuthFailure.SERVER -> "The server had a problem. Try again in a while."
        AuthFailure.GOOGLE_REJECTED -> "The server did not accept the Google sign-in. Try again."
        AuthFailure.GOOGLE_EMAIL_UNVERIFIED -> "Google has not verified this account's email address. Use a password, or another Google account."
        AuthFailure.GOOGLE_ACCOUNT_MISMATCH -> "This email belongs to an account linked to another Google account. Sign in with that one or with your password."
        AuthFailure.GOOGLE_NOT_OFFERED -> "This server does not offer Sign in with Google. Use your email and password."
        AuthFailure.GOOGLE_FAILED -> "Google sign-in did not work. Try again, or use your email and password."
    }

    override val home = "Home"
    override val thisWeek = "This week"
    override fun weekWorkouts(count: Int): String = if (count == 1) "1 workout" else "$count workouts"
    override fun weekSets(count: Int): String = if (count == 1) "1 set" else "$count sets"
    override val weekNone = "No workout yet this week"
    override fun weekLast(volume: String): String = "Last week $volume kg"
    override val weekBest = "Best of the last 12 weeks"
    override fun weekDaysDescription(days: String): String = "Volume per day this week: $days"
    override val weekDaysNone = "nothing yet"
    override fun inProgressFor(duration: String): String = "In progress for $duration"
    override val inProgressHint = "Log it on your phone or watch; it shows up here when it is finished."
    override val routines = "Routines"
    override val routinesEmpty = "Routines you make on your phone show up here."
    override val recent = "Recent workouts"
    override val homeEmpty = "No workouts yet. Finished workouts show up here."
    override val showAll = "Show all"
    override val history = "History"
    override val historyEmpty = "Finished workouts show up here."
    override val workoutUntitled = "Workout"
    override val kg = "kg"
    override fun exerciseCount(count: Int): String = if (count == 1) "1 exercise" else "$count exercises"
    override fun durationMinutes(minutes: Int): String = "$minutes min"
    override fun durationHoursMinutes(hours: Int, minutes: Int): String = "$hours h $minutes min"

    override fun signedInAs(email: String): String = "Signed in as $email"
    override val signOut = "Sign out"
    override val syncNow = "Sync now"
    override val syncing = "Syncing…"
    override val lastSyncNever = "Last sync: never"
    override val lastSyncJustNow = "Last sync just now"
    override fun lastSyncMinutesAgo(minutes: Int): String = "Last sync $minutes min ago"
    override fun lastSyncHoursAgo(hours: Int): String = "Last sync $hours h ago"
    override fun lastSyncOn(date: String): String = "Last sync $date"
    override fun syncFailure(failure: SyncFailure): String = when (failure) {
        SyncFailure.NETWORK -> "The last sync could not reach the server. It tries again when you are online."
        SyncFailure.SERVER -> "The server had a problem with the last sync. Sync tries again later."
        SyncFailure.SIGNED_OUT -> "The server signed you out. Sign in again to keep syncing."
        SyncFailure.REJECTED -> "The server refused the last sync. Check that the app and the server are up to date."
    }
}

object Swedish : Strings {
    override val tag = "sv"
    override val appName = "Tung"
    override val separator = " · "
    override val loading = "Laddar…"
    override val storageUnavailable = "Den här webbläsaren låter inte Tung spara något, så appen kan inte köra här. Prova en annan webbläsare, eller lämna privat läge."

    override val signInIntro = "Logga in på din Tung-server för att se din träning här. Den sparas i den här webbläsaren, så den öppnas även utan uppkoppling."
    override val email = "E-post"
    override val password = "Lösenord"
    override val server = "Serveradress"
    override val useAnotherServer = "Använd en annan server"
    override val signIn = "Logga in"
    override val signingIn = "Loggar in…"
    override val orWithEmail = "Eller med e-post och lösenord"
    override val errorFields = "Ange e-post och lösenord."
    override val errorUrl = "Ange serverns adress, till exempel https://tung.example.org."
    override val errorUrlHttps = "Använd https://. Okrypterad http fungerar bara mot localhost och adresser i ditt eget nätverk."
    override fun authFailure(failure: AuthFailure): String = when (failure) {
        AuthFailure.INVALID_CREDENTIALS -> "Fel e-post eller lösenord."
        AuthFailure.INVALID_REQUEST -> "Servern godtog inte den e-postadressen. Kontrollera den och försök igen."
        AuthFailure.RATE_LIMITED -> "För många försök. Vänta en minut och försök igen."
        AuthFailure.NOT_A_SERVER -> "Ingen Tung-server svarar på den adressen. Kontrollera den och försök igen."
        AuthFailure.NETWORK -> "Kunde inte nå servern. Kontrollera adressen och din uppkoppling."
        AuthFailure.SERVER -> "Servern fick ett fel. Försök igen om en stund."
        AuthFailure.GOOGLE_REJECTED -> "Servern godtog inte Google-inloggningen. Försök igen."
        AuthFailure.GOOGLE_EMAIL_UNVERIFIED -> "Google har inte verifierat kontots e-postadress. Använd ett lösenord eller ett annat Google-konto."
        AuthFailure.GOOGLE_ACCOUNT_MISMATCH -> "E-postadressen hör till ett konto som är kopplat till ett annat Google-konto. Logga in med det eller med ditt lösenord."
        AuthFailure.GOOGLE_NOT_OFFERED -> "Den här servern erbjuder inte inloggning med Google. Använd e-post och lösenord."
        AuthFailure.GOOGLE_FAILED -> "Google-inloggningen fungerade inte. Försök igen, eller använd e-post och lösenord."
    }

    override val home = "Hem"
    override val thisWeek = "Den här veckan"
    override fun weekWorkouts(count: Int): String = "$count pass"
    override fun weekSets(count: Int): String = "$count set"
    override val weekNone = "Inget pass än den här veckan"
    override fun weekLast(volume: String): String = "Förra veckan $volume kg"
    override val weekBest = "Bäst på 12 veckor"
    override fun weekDaysDescription(days: String): String = "Volym per dag den här veckan: $days"
    override val weekDaysNone = "inget än"
    override fun inProgressFor(duration: String): String = "Pågår sedan $duration"
    override val inProgressHint = "Logga det på telefonen eller klockan; det hamnar här när det är avslutat."
    override val routines = "Rutiner"
    override val routinesEmpty = "Rutiner du gör på telefonen hamnar här."
    override val recent = "Senaste passen"
    override val homeEmpty = "Inga pass än. Avslutade pass hamnar här."
    override val showAll = "Visa alla"
    override val history = "Historik"
    override val historyEmpty = "Avslutade pass hamnar här."
    override val workoutUntitled = "Pass"
    override val kg = "kg"
    override fun exerciseCount(count: Int): String = if (count == 1) "1 övning" else "$count övningar"
    override fun durationMinutes(minutes: Int): String = "$minutes min"
    override fun durationHoursMinutes(hours: Int, minutes: Int): String = "$hours h $minutes min"

    override fun signedInAs(email: String): String = "Inloggad som $email"
    override val signOut = "Logga ut"
    override val syncNow = "Synka nu"
    override val syncing = "Synkar…"
    override val lastSyncNever = "Senaste synk: aldrig"
    override val lastSyncJustNow = "Synkade nyss"
    override fun lastSyncMinutesAgo(minutes: Int): String = "Synkade för $minutes min sedan"
    override fun lastSyncHoursAgo(hours: Int): String = "Synkade för $hours h sedan"
    override fun lastSyncOn(date: String): String = "Synkade $date"
    override fun syncFailure(failure: SyncFailure): String = when (failure) {
        SyncFailure.NETWORK -> "Senaste synken nådde inte servern. Den försöker igen när du är uppkopplad."
        SyncFailure.SERVER -> "Servern fick ett fel vid senaste synken. Synken försöker igen senare."
        SyncFailure.SIGNED_OUT -> "Servern loggade ut dig. Logga in igen för att fortsätta synka."
        SyncFailure.REJECTED -> "Servern avvisade senaste synken. Kontrollera att appen och servern är uppdaterade."
    }
}

/** The strings for a BCP 47 tag (`navigator.language`): Swedish for `sv*`, else English, the app's fallback. */
fun stringsFor(languageTag: String): Strings =
    if (languageTag.lowercase().let { it == "sv" || it.startsWith("sv-") }) Swedish else English
