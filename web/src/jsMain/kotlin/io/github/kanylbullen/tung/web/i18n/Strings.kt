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

    // Workout
    val startWorkout: String
    val start: String
    val resumeWorkout: String
    val noWorkout: String
    val addExercise: String
    val addSet: String
    fun last(sets: String): String
    val finish: String
    val discard: String
    val finishTitle: String
    fun finishDropsOpen(count: Int): String
    val finishAllLogged: String
    val finishNothingLogged: String
    val keepTraining: String
    val discardTitle: String
    val discardBody: String
    val searchExercises: String
    val noMatches: String
    val close: String
    val emptyWorkout: String
    fun setDone(number: Int): String
    fun removeExercise(name: String): String
    val weight: String
    val reps: String
    val done: String
    val applyToOpenSets: String
    val less: String
    val more: String
    fun logSet(name: String, values: String): String
    fun addSetSticky(name: String, values: String): String
    val rest: String
    val skip: String
    val plusThirty: String
    fun makeCurrent(number: Int): String
    val currentSet: String
    fun removeExerciseTitle(name: String, logged: Int): String
    val remove: String

    // Workout detail
    val duration: String
    val setsLabel: String
    val volumeKg: String
    fun bestSetLine(sets: Int, volume: String, e1rm: String?): String
    fun heartRate(avg: Int, max: Int?): String
    fun calories(kcal: Int): String
    val saveAsRoutine: String
    val routineName: String
    val save: String
    val cancel: String
    fun savedAsRoutine(name: String): String
    val deleteWorkout: String
    val deleteWorkoutTitle: String
    val deleteWorkoutBody: String
    val delete: String
    val notFound: String

    // Routines
    val newRoutine: String
    val newRoutineTitle: String
    val routinesAllEmpty: String
    val rename: String
    val renameRoutine: String
    val deleteRoutine: String
    val deleteRoutineTitle: String
    val deleteRoutineBody: String
    val addRoutineExercise: String
    val routineEmptyHint: String
    val setsShort: String
    fun targetReps(reps: Int?): String
    fun targetRepsTitle(name: String): String
    val clearTargetReps: String
    val moveUp: String
    val moveDown: String
    fun fewerSets(name: String): String
    fun moreSets(name: String): String
    val lastUsedNever: String

    // Progress
    val progress: String
    val last12Weeks: String
    fun streak(weeks: Int): String
    val exercises: String
    val recentRecords: String
    val records: String
    val lastSessions: String
    val progressEmpty: String
    val chartEmpty: String
    fun metricLabel(metric: io.github.kanylbullen.tung.core.progress.ProgressMetric): String
    fun rangeShort(range: io.github.kanylbullen.tung.core.progress.ProgressRange): String
    fun rangeLong(range: io.github.kanylbullen.tung.core.progress.ProgressRange): String
    fun recordLabel(kind: io.github.kanylbullen.tung.core.progress.RecordKind): String
    fun changeIn(change: String, range: String): String
    fun noChangeIn(range: String): String
    fun sessionsIn(count: Int, range: String): String
    fun chartSummary(metric: String, range: String, from: String, to: String): String
    fun weeksSummary(volumes: String): String

    // Settings
    val settings: String
    val training: String
    val defaultRest: String
    val restOff: String
    val myEquipment: String
    val equipmentHint: String
    val everything: String
    val bodyweightOnly: String
    fun equipmentLabel(kind: io.github.kanylbullen.tung.core.Equipment): String
    val account: String
    val about: String
    val version: String
    val licenceText: String
    val sourceCode: String
    val catalogueText: String
    val privacy: String

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
    override val inProgressHint = "Continue here, or on your phone or watch."
    override val routines = "Routines"
    override val routinesEmpty = "Routines you make on your phone show up here."
    override val recent = "Recent workouts"
    override val homeEmpty = "No workouts yet. Finished workouts show up here."
    override val showAll = "Show all"
    override val startWorkout = "Start workout"
    override val start = "Start"
    override val resumeWorkout = "Resume workout"
    override val noWorkout = "No workout in progress."
    override val addExercise = "Add exercise"
    override val addSet = "+ Set"
    override fun last(sets: String): String = "Last: $sets"
    override val finish = "Finish"
    override val discard = "Discard workout"
    override val finishTitle = "Finish the workout?"
    override fun finishDropsOpen(count: Int): String =
        if (count == 1) "1 set that is not ticked will be removed." else "$count sets that are not ticked will be removed."
    override val finishAllLogged = "Every set is logged."
    override val finishNothingLogged = "No set is logged, so the workout is not saved."
    override val keepTraining = "Keep training"
    override val discardTitle = "Discard this workout?"
    override val discardBody = "The sets you logged are deleted."
    override val searchExercises = "Search exercises"
    override val noMatches = "No exercise matches."
    override val close = "Close"
    override val emptyWorkout = "Add an exercise to start."
    override fun setDone(number: Int): String = "Set $number done"
    override fun removeExercise(name: String): String = "Remove $name"
    override val weight = "Weight"
    override val reps = "Reps"
    override val done = "Done"
    override val applyToOpenSets = "Apply to all sets not ticked"
    override val less = "Less"
    override val more = "More"
    override fun logSet(name: String, values: String): String = "Log set · $name · $values"
    override fun addSetSticky(name: String, values: String): String = "Add set · $name · $values"
    override val rest = "Rest"
    override val skip = "Skip"
    override val plusThirty = "+30 s"
    override fun makeCurrent(number: Int): String = "Make set $number the current set"
    override val currentSet = "Current set"
    override fun removeExerciseTitle(name: String, logged: Int): String =
        if (logged == 1) "Remove $name and its logged set?" else "Remove $name and its $logged logged sets?"
    override val remove = "Remove"
    override val duration = "Duration"
    override val setsLabel = "Sets"
    override val volumeKg = "Volume, kg"
    override fun bestSetLine(sets: Int, volume: String, e1rm: String?): String =
        (if (sets == 1) "1 set" else "$sets sets") + " · $volume kg" + (e1rm?.let { " · Est. 1RM $it kg" } ?: "")
    override fun heartRate(avg: Int, max: Int?): String = if (max != null) "Heart rate $avg avg · $max max" else "Heart rate $avg"
    override fun calories(kcal: Int): String = "$kcal kcal"
    override val saveAsRoutine = "Save as routine"
    override val routineName = "Name"
    override val save = "Save"
    override val cancel = "Cancel"
    override fun savedAsRoutine(name: String): String = "Saved as $name"
    override val deleteWorkout = "Delete workout"
    override val deleteWorkoutTitle = "Delete workout?"
    override val deleteWorkoutBody = "The workout and all its sets will be deleted. This cannot be undone."
    override val delete = "Delete"
    override val notFound = "This workout is not here. It may have been deleted on another device."
    override val newRoutine = "+ New routine"
    override val newRoutineTitle = "New routine"
    override val routinesAllEmpty = "No routines. Create one with + New routine, or save a finished workout as a routine from its page."
    override val rename = "Rename"
    override val renameRoutine = "Rename routine"
    override val deleteRoutine = "Delete routine"
    override val deleteRoutineTitle = "Delete routine?"
    override val deleteRoutineBody = "The routine will be deleted. Workouts you did from it stay in your history."
    override val addRoutineExercise = "+ Exercise"
    override val routineEmptyHint = "Add exercises with + Exercise"
    override val setsShort = "sets"
    override fun targetReps(reps: Int?): String = when (reps) {
        null -> "No target reps"
        1 -> "1 rep per set"
        else -> "$reps reps per set"
    }
    override fun targetRepsTitle(name: String): String = "Target reps · $name"
    override val clearTargetReps = "Clear target reps"
    override val moveUp = "Move up"
    override val moveDown = "Move down"
    override fun fewerSets(name: String): String = "Fewer sets of $name"
    override fun moreSets(name: String): String = "More sets of $name"
    override val lastUsedNever = "Never"
    override val progress = "Progress"
    override val last12Weeks = "Last 12 weeks"
    override fun streak(weeks: Int): String = if (weeks == 1) "1-week streak" else "$weeks-week streak"
    override val exercises = "Exercises"
    override val recentRecords = "Recent records"
    override val records = "Records"
    override val lastSessions = "Last sessions"
    override val progressEmpty = "Finish a workout to see your progress."
    override val chartEmpty = "Log this exercise in two workouts to see a chart."
    override fun metricLabel(metric: io.github.kanylbullen.tung.core.progress.ProgressMetric): String = when (metric) {
        io.github.kanylbullen.tung.core.progress.ProgressMetric.E1RM -> "Est. 1RM"
        io.github.kanylbullen.tung.core.progress.ProgressMetric.TOP_SET -> "Top set"
        io.github.kanylbullen.tung.core.progress.ProgressMetric.VOLUME -> "Session volume"
        io.github.kanylbullen.tung.core.progress.ProgressMetric.WEEKLY_VOLUME -> "Weekly volume"
    }
    override fun rangeShort(range: io.github.kanylbullen.tung.core.progress.ProgressRange): String = when (range) {
        io.github.kanylbullen.tung.core.progress.ProgressRange.THREE_MONTHS -> "3M"
        io.github.kanylbullen.tung.core.progress.ProgressRange.ONE_YEAR -> "1Y"
        io.github.kanylbullen.tung.core.progress.ProgressRange.ALL -> "All"
    }
    override fun rangeLong(range: io.github.kanylbullen.tung.core.progress.ProgressRange): String = when (range) {
        io.github.kanylbullen.tung.core.progress.ProgressRange.THREE_MONTHS -> "3 months"
        io.github.kanylbullen.tung.core.progress.ProgressRange.ONE_YEAR -> "12 months"
        io.github.kanylbullen.tung.core.progress.ProgressRange.ALL -> "all time"
    }
    override fun recordLabel(kind: io.github.kanylbullen.tung.core.progress.RecordKind): String = when (kind) {
        io.github.kanylbullen.tung.core.progress.RecordKind.HEAVIEST_WEIGHT -> "Heaviest weight"
        io.github.kanylbullen.tung.core.progress.RecordKind.BEST_E1RM -> "Best est. 1RM"
        io.github.kanylbullen.tung.core.progress.RecordKind.MOST_REPS -> "Most reps"
        io.github.kanylbullen.tung.core.progress.RecordKind.BEST_SESSION_VOLUME -> "Best session volume"
    }
    override fun changeIn(change: String, range: String): String = "$change in $range"
    override fun noChangeIn(range: String): String = "No change in $range"
    override fun sessionsIn(count: Int, range: String): String = if (count == 1) "1 session in $range" else "$count sessions in $range"
    override fun chartSummary(metric: String, range: String, from: String, to: String): String = "$metric, $range: from $from to $to kg"
    override fun weeksSummary(volumes: String): String = "Volume per week, oldest first: $volumes"
    override val settings = "Settings"
    override val training = "Training"
    override val defaultRest = "Default rest"
    override val restOff = "Off"
    override val myEquipment = "My equipment"
    override val equipmentHint = "The exercise picker shows what you can do with these."
    override val everything = "Everything"
    override val bodyweightOnly = "Bodyweight only"
    override fun equipmentLabel(kind: io.github.kanylbullen.tung.core.Equipment): String = when (kind) {
        io.github.kanylbullen.tung.core.Equipment.BARBELL -> "Barbell"
        io.github.kanylbullen.tung.core.Equipment.EZ_BAR -> "EZ bar"
        io.github.kanylbullen.tung.core.Equipment.DUMBBELL -> "Dumbbells"
        io.github.kanylbullen.tung.core.Equipment.KETTLEBELL -> "Kettlebell"
        io.github.kanylbullen.tung.core.Equipment.MACHINE -> "Machine"
        io.github.kanylbullen.tung.core.Equipment.CABLE -> "Cable"
        io.github.kanylbullen.tung.core.Equipment.BAND -> "Band"
        io.github.kanylbullen.tung.core.Equipment.BODYWEIGHT -> "Bodyweight"
        io.github.kanylbullen.tung.core.Equipment.MEDICINE_BALL -> "Medicine ball"
        io.github.kanylbullen.tung.core.Equipment.EXERCISE_BALL -> "Exercise ball"
        io.github.kanylbullen.tung.core.Equipment.OTHER -> "Other"
    }
    override val account = "Account"
    override val about = "About"
    override val version = "Version"
    override val licenceText = "Free software under the GNU AGPL-3.0."
    override val sourceCode = "Source code"
    override val catalogueText = "Open data: Tung's own exercise catalogue is CC0 1.0."
    override val privacy = "Privacy"
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
    override val inProgressHint = "Fortsätt här, eller på telefonen eller klockan."
    override val routines = "Rutiner"
    override val routinesEmpty = "Rutiner du gör på telefonen hamnar här."
    override val recent = "Senaste passen"
    override val homeEmpty = "Inga pass än. Avslutade pass hamnar här."
    override val showAll = "Visa alla"
    override val startWorkout = "Starta pass"
    override val start = "Starta"
    override val resumeWorkout = "Fortsätt passet"
    override val noWorkout = "Inget pass pågår."
    override val addExercise = "Lägg till övning"
    override val addSet = "+ Set"
    override fun last(sets: String): String = "Förra: $sets"
    override val finish = "Avsluta"
    override val discard = "Släng passet"
    override val finishTitle = "Avsluta passet?"
    override fun finishDropsOpen(count: Int): String =
        if (count == 1) "1 set som inte är bockat tas bort." else "$count set som inte är bockade tas bort."
    override val finishAllLogged = "Alla set är loggade."
    override val finishNothingLogged = "Inget set är loggat, så passet sparas inte."
    override val keepTraining = "Fortsätt träna"
    override val discardTitle = "Släng passet?"
    override val discardBody = "Setten du loggat tas bort."
    override val searchExercises = "Sök övning"
    override val noMatches = "Ingen övning matchar."
    override val close = "Stäng"
    override val emptyWorkout = "Lägg till en övning för att börja."
    override fun setDone(number: Int): String = "Set $number klart"
    override fun removeExercise(name: String): String = "Ta bort $name"
    override val weight = "Vikt"
    override val reps = "Reps"
    override val done = "Klar"
    override val applyToOpenSets = "Använd på alla set som inte är bockade"
    override val less = "Mindre"
    override val more = "Mer"
    override fun logSet(name: String, values: String): String = "Logga set · $name · $values"
    override fun addSetSticky(name: String, values: String): String = "Lägg till set · $name · $values"
    override val rest = "Vila"
    override val skip = "Hoppa över"
    override val plusThirty = "+30 s"
    override fun makeCurrent(number: Int): String = "Gör set $number till aktuellt set"
    override val currentSet = "Aktuellt set"
    override fun removeExerciseTitle(name: String, logged: Int): String =
        if (logged == 1) "Ta bort $name och dess loggade set?" else "Ta bort $name och dess $logged loggade set?"
    override val remove = "Ta bort"
    override val duration = "Varaktighet"
    override val setsLabel = "Set"
    override val volumeKg = "Volym, kg"
    override fun bestSetLine(sets: Int, volume: String, e1rm: String?): String =
        "$sets set · $volume kg" + (e1rm?.let { " · Uppsk. 1RM $it kg" } ?: "")
    override fun heartRate(avg: Int, max: Int?): String = if (max != null) "Puls $avg snitt · $max max" else "Puls $avg"
    override fun calories(kcal: Int): String = "$kcal kcal"
    override val saveAsRoutine = "Spara som rutin"
    override val routineName = "Namn"
    override val save = "Spara"
    override val cancel = "Avbryt"
    override fun savedAsRoutine(name: String): String = "Sparad som $name"
    override val deleteWorkout = "Radera pass"
    override val deleteWorkoutTitle = "Radera pass?"
    override val deleteWorkoutBody = "Passet och alla dess set raderas. Det går inte att ångra."
    override val delete = "Radera"
    override val notFound = "Passet finns inte här. Det kan ha raderats på en annan enhet."
    override val newRoutine = "+ Ny rutin"
    override val newRoutineTitle = "Ny rutin"
    override val routinesAllEmpty = "Inga rutiner. Skapa en med + Ny rutin, eller spara ett avslutat pass som rutin från dess sida."
    override val rename = "Byt namn"
    override val renameRoutine = "Byt namn på rutin"
    override val deleteRoutine = "Radera rutin"
    override val deleteRoutineTitle = "Radera rutin?"
    override val deleteRoutineBody = "Rutinen raderas. Pass du har gjort från den finns kvar i historiken."
    override val addRoutineExercise = "+ Övning"
    override val routineEmptyHint = "Lägg till övningar med + Övning"
    override val setsShort = "set"
    override fun targetReps(reps: Int?): String = when (reps) {
        null -> "Inget mål för reps"
        1 -> "1 rep per set"
        else -> "$reps reps per set"
    }
    override fun targetRepsTitle(name: String): String = "Mål för reps · $name"
    override val clearTargetReps = "Ta bort målet för reps"
    override val moveUp = "Flytta upp"
    override val moveDown = "Flytta ner"
    override fun fewerSets(name: String): String = "Färre set av $name"
    override fun moreSets(name: String): String = "Fler set av $name"
    override val lastUsedNever = "Aldrig"
    override val progress = "Framsteg"
    override val last12Weeks = "Senaste 12 veckorna"
    override fun streak(weeks: Int): String = if (weeks == 1) "1 vecka i rad" else "$weeks veckor i rad"
    override val exercises = "Övningar"
    override val recentRecords = "Senaste rekorden"
    override val records = "Rekord"
    override val lastSessions = "Senaste passen"
    override val progressEmpty = "Avsluta ett pass för att se dina framsteg."
    override val chartEmpty = "Logga övningen i två pass för att se ett diagram."
    override fun metricLabel(metric: io.github.kanylbullen.tung.core.progress.ProgressMetric): String = when (metric) {
        io.github.kanylbullen.tung.core.progress.ProgressMetric.E1RM -> "Uppsk. 1RM"
        io.github.kanylbullen.tung.core.progress.ProgressMetric.TOP_SET -> "Toppset"
        io.github.kanylbullen.tung.core.progress.ProgressMetric.VOLUME -> "Passvolym"
        io.github.kanylbullen.tung.core.progress.ProgressMetric.WEEKLY_VOLUME -> "Veckovolym"
    }
    override fun rangeShort(range: io.github.kanylbullen.tung.core.progress.ProgressRange): String = when (range) {
        io.github.kanylbullen.tung.core.progress.ProgressRange.THREE_MONTHS -> "3 mån"
        io.github.kanylbullen.tung.core.progress.ProgressRange.ONE_YEAR -> "1 år"
        io.github.kanylbullen.tung.core.progress.ProgressRange.ALL -> "Allt"
    }
    override fun rangeLong(range: io.github.kanylbullen.tung.core.progress.ProgressRange): String = when (range) {
        io.github.kanylbullen.tung.core.progress.ProgressRange.THREE_MONTHS -> "3 månader"
        io.github.kanylbullen.tung.core.progress.ProgressRange.ONE_YEAR -> "12 månader"
        io.github.kanylbullen.tung.core.progress.ProgressRange.ALL -> "hela tiden"
    }
    override fun recordLabel(kind: io.github.kanylbullen.tung.core.progress.RecordKind): String = when (kind) {
        io.github.kanylbullen.tung.core.progress.RecordKind.HEAVIEST_WEIGHT -> "Tyngsta vikt"
        io.github.kanylbullen.tung.core.progress.RecordKind.BEST_E1RM -> "Bästa uppsk. 1RM"
        io.github.kanylbullen.tung.core.progress.RecordKind.MOST_REPS -> "Flest reps"
        io.github.kanylbullen.tung.core.progress.RecordKind.BEST_SESSION_VOLUME -> "Största passvolym"
    }
    override fun changeIn(change: String, range: String): String = "$change på $range"
    override fun noChangeIn(range: String): String = "Ingen ändring på $range"
    override fun sessionsIn(count: Int, range: String): String = if (count == 1) "1 pass på $range" else "$count pass på $range"
    override fun chartSummary(metric: String, range: String, from: String, to: String): String = "$metric, $range: från $from till $to kg"
    override fun weeksSummary(volumes: String): String = "Volym per vecka, äldst först: $volumes"
    override val settings = "Inställningar"
    override val training = "Träning"
    override val defaultRest = "Standardvila"
    override val restOff = "Av"
    override val myEquipment = "Min utrustning"
    override val equipmentHint = "Övningsväljaren visar det du kan göra med dessa."
    override val everything = "Allt"
    override val bodyweightOnly = "Bara kroppsvikt"
    override fun equipmentLabel(kind: io.github.kanylbullen.tung.core.Equipment): String = when (kind) {
        io.github.kanylbullen.tung.core.Equipment.BARBELL -> "Skivstång"
        io.github.kanylbullen.tung.core.Equipment.EZ_BAR -> "EZ-stång"
        io.github.kanylbullen.tung.core.Equipment.DUMBBELL -> "Hantlar"
        io.github.kanylbullen.tung.core.Equipment.KETTLEBELL -> "Kettlebell"
        io.github.kanylbullen.tung.core.Equipment.MACHINE -> "Maskin"
        io.github.kanylbullen.tung.core.Equipment.CABLE -> "Kabel"
        io.github.kanylbullen.tung.core.Equipment.BAND -> "Gummiband"
        io.github.kanylbullen.tung.core.Equipment.BODYWEIGHT -> "Kroppsvikt"
        io.github.kanylbullen.tung.core.Equipment.MEDICINE_BALL -> "Medicinboll"
        io.github.kanylbullen.tung.core.Equipment.EXERCISE_BALL -> "Pilatesboll"
        io.github.kanylbullen.tung.core.Equipment.OTHER -> "Övrigt"
    }
    override val account = "Konto"
    override val about = "Om"
    override val version = "Version"
    override val licenceText = "Fri programvara under GNU AGPL-3.0."
    override val sourceCode = "Källkod"
    override val catalogueText = "Öppna data: Tungs egen övningskatalog är CC0 1.0."
    override val privacy = "Integritet"
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
