package io.github.kanylbullen.tung.web.i18n

import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.MuscleGroup
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
    val googleHint: String
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

    // Summary
    val summaryTitle: String
    val newRecord: String
    val musclesWorked: String
    fun musclesWorkedDescription(muscles: String): String
    fun muscleName(muscle: Muscle): String
    fun uncountedSets(count: Int): String

    // Exercises
    val all: String
    fun groupLabel(group: MuscleGroup): String
    val custom: String
    fun equipmentFilter(count: Int?): String
    val addToRoutine: String
    val addToWorkout: String
    val chooseRoutine: String
    val noRoutinesYet: String
    fun addedToRoutine(name: String): String
    val notLogged: String
    fun lastTime(date: String): String
    val bestSetKg: String
    fun inWorkouts(count: Int): String
    val howTo: String
    val primary: String
    val secondary: String
    fun mapDescription(primary: String, secondary: String?): String
    val exerciseNotFound: String

    // Programs
    val programs: String
    val programsHint: String
    fun programsActive(name: String): String
    val programsEmpty: String
    val newProgram: String
    val create: String
    val modeFixed: String
    val modeFixedHint: String
    val modeRotating: String
    val modeRotatingHint: String
    val mode: String
    val active: String
    val activate: String
    val deactivate: String
    fun dayCount(count: Int): String
    fun dayOf(number: Int, count: Int): String
    val noWeekdays: String
    val programDaysEmpty: String
    val addDay: String
    val chooseRoutineForDay: String
    val removeDay: String
    val renameProgram: String
    val deleteProgram: String
    val deleteProgramTitle: String
    val deleteProgramBody: String
    val programNotFound: String
    fun todayFixed(routine: String): String
    val restDay: String
    val chooseAnother: String
    val chooseDay: String
    val dayGone: String
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

    // Server admin (Settings, for the server's admins only)
    val serverAdmin: String
    val serverAdminHint: String
    fun accountCount(count: Int): String
    fun accountsShown(shown: Int, total: Int): String
    fun joined(date: String): String
    val signInGoogle: String
    val signInPassword: String
    val adminBadge: String
    fun lastSeen(date: String): String
    val neverSeen: String
    fun lastWorkout(date: String): String
    val testerSignups: String
    val testerSignupsHint: String
    val testersEmpty: String
    val copyAddresses: String
    val copied: String
    fun removeTester(email: String): String
    val removeTesterBody: String
    val adminFailed: String
    val adminNotAdmin: String
    val tryAgain: String

    // Feedback (Settings → Send feedback, and its list on Server admin)
    val sendFeedback: String
    val sendFeedbackHint: String
    val feedbackBug: String
    val feedbackIdea: String
    val feedbackMessageLabel: String
    val feedbackScreenshotsHint: String
    val feedbackScreenshotNotice: String
    val addScreenshot: String
    fun screenshotAlt(number: Int): String
    fun removeScreenshot(number: Int): String
    fun openScreenshot(number: Int): String
    fun showScreenshots(count: Int): String
    val send: String
    val sending: String
    val feedbackSent: String
    val feedbackTooLarge: String
    val feedbackTooManyToday: String
    val feedbackUnreadable: String
    val feedbackFailed: String
    val feedbackSignedOut: String
    fun feedbackHeading(open: Int): String
    val feedbackEmpty: String
    val markResolved: String
    val reopen: String
    val resolved: String
    val deleteFeedbackTitle: String
    val deleteFeedbackBody: String
    fun platformName(platform: String): String

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
    override val googleHint = "Choose your Google account in the window that opens."
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

    override val summaryTitle = "Workout done"
    override val newRecord = "New record"
    override val musclesWorked = "Muscles worked"
    override fun musclesWorkedDescription(muscles: String) = "Muscles worked: $muscles"
    override fun muscleName(muscle: Muscle): String = when (muscle) {
        Muscle.CHEST -> "chest"
        Muscle.LATS -> "lats"
        Muscle.UPPER_BACK -> "upper back"
        Muscle.LOWER_BACK -> "lower back"
        Muscle.TRAPS -> "traps"
        Muscle.FRONT_DELTS -> "front delts"
        Muscle.SIDE_DELTS -> "side delts"
        Muscle.REAR_DELTS -> "rear delts"
        Muscle.BICEPS -> "biceps"
        Muscle.TRICEPS -> "triceps"
        Muscle.FOREARMS -> "forearms"
        Muscle.QUADS -> "quads"
        Muscle.HAMSTRINGS -> "hamstrings"
        Muscle.GLUTES -> "glutes"
        Muscle.CALVES -> "calves"
        Muscle.ADDUCTORS -> "adductors"
        Muscle.ABDUCTORS -> "abductors"
        Muscle.ABS -> "abs"
        Muscle.OBLIQUES -> "obliques"
        Muscle.HIP_FLEXORS -> "hip flexors"
        Muscle.NECK -> "neck"
    }
    override fun uncountedSets(count: Int) =
        if (count == 1) "1 set of a custom exercise is not counted: custom exercises have no muscles"
        else "$count sets of custom exercises are not counted: custom exercises have no muscles"

    override val all = "All"
    override fun groupLabel(group: MuscleGroup): String = when (group) {
        MuscleGroup.CHEST -> "Chest"
        MuscleGroup.BACK -> "Back"
        MuscleGroup.SHOULDERS -> "Shoulders"
        MuscleGroup.BICEPS -> "Biceps"
        MuscleGroup.TRICEPS -> "Triceps"
        MuscleGroup.LEGS -> "Legs"
        MuscleGroup.GLUTES -> "Glutes"
        MuscleGroup.CORE -> "Core"
        MuscleGroup.FULLBODY -> "Full body"
    }
    override val custom = "Custom"
    override fun equipmentFilter(count: Int?) = "Equipment: " + (count?.toString() ?: "All")
    override val addToRoutine = "Add to routine"
    override val addToWorkout = "Add to workout"
    override val chooseRoutine = "Add to which routine?"
    override val noRoutinesYet = "No routines yet. Create one in the Routines tab with “+ New routine”, or save a finished workout as one."
    override fun addedToRoutine(name: String) = "Added to $name"
    override val notLogged = "Not logged yet."
    override fun lastTime(date: String) = "Last time, $date"
    override val bestSetKg = "Best set, kg"
    override fun inWorkouts(count: Int) = if (count == 1) "In 1 workout" else "In $count workouts"
    override val howTo = "How to do it"
    override val primary = "Primary"
    override val secondary = "Secondary"
    override fun mapDescription(primary: String, secondary: String?) =
        if (secondary == null) "Muscles worked. Primary: $primary." else "Muscles worked. Primary: $primary. Secondary: $secondary."
    override val exerciseNotFound = "This exercise no longer exists."

    override val programs = "Programs"
    override val programsHint = "Routines on fixed weekdays or in rotation"
    override fun programsActive(name: String) = "Active: $name"
    override val programsEmpty = "No programs. A program puts your routines on fixed weekdays or in a rotation, and Home shows the one for today."
    override val newProgram = "New program"
    override val create = "Create"
    override val modeFixed = "Fixed days"
    override val modeFixedHint = "Each routine on the weekdays you pick"
    override val modeRotating = "Rotating"
    override val modeRotatingHint = "Day 1, 2, 3 in order, whatever the date"
    override val mode = "Mode"
    override val active = "Active"
    override val activate = "Activate"
    override val deactivate = "Deactivate"
    override fun dayCount(count: Int) = if (count == 1) "1 day" else "$count days"
    override fun dayOf(number: Int, count: Int) = "Day $number of $count"
    override val noWeekdays = "No weekdays"
    override val programDaysEmpty = "No days yet. Add your routines in the order you train them."
    override val addDay = "Add day"
    override val chooseRoutineForDay = "Choose a routine"
    override val removeDay = "Remove day"
    override val renameProgram = "Rename program"
    override val deleteProgram = "Delete program"
    override val deleteProgramTitle = "Delete program?"
    override val deleteProgramBody = "The program will be deleted. Its routines, and the workouts you did from it, are kept."
    override val programNotFound = "This program no longer exists."
    override fun todayFixed(routine: String) = "Today: $routine"
    override val restDay = "Rest day"
    override val chooseAnother = "Choose another"
    override val chooseDay = "Choose a day"
    override val dayGone = "That day is no longer in the program."
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

    override val serverAdmin = "Server admin"
    override val serverAdminHint = "Every account on this server"
    override fun accountCount(count: Int): String = if (count == 1) "1 account" else "$count accounts"
    override fun accountsShown(shown: Int, total: Int): String = "The newest $shown of $total."
    override fun joined(date: String): String = "Joined $date"
    override val signInGoogle = "Google"
    override val signInPassword = "Password"
    override val adminBadge = "Admin"
    override fun lastSeen(date: String): String = "Last seen $date"
    override val neverSeen = "Never seen since signing up"
    override fun lastWorkout(date: String): String = "latest $date"
    override val testerSignups = "Test sign-ups"
    override val testerSignupsHint = "Addresses left on the front page for the Android test on Google Play. Add them to the testers' list in the Play Console, then remove them here."
    override val testersEmpty = "No one is waiting for an invitation."
    override val copyAddresses = "Copy addresses"
    override val copied = "Copied"
    override fun removeTester(email: String): String = "Remove $email"
    override val removeTesterBody = "Do it once the address is invited in the Play Console."
    override val adminFailed = "Could not reach the server. Check your connection and try again."
    override val adminNotAdmin = "This account is not an admin of the server."
    override val tryAgain = "Try again"

    override val sendFeedback = "Send feedback"
    override val sendFeedbackHint = "Report a bug or suggest a feature"
    override val feedbackBug = "Bug"
    override val feedbackIdea = "Idea"
    override val feedbackMessageLabel = "What happened, or what would you like?"
    override val feedbackScreenshotsHint = "Paste screenshots with Ctrl+V, or add them. At most 3."
    override val feedbackScreenshotNotice = "A screenshot shows whatever was on your screen. Look it over before you send it."
    override val addScreenshot = "Add screenshot"
    override fun screenshotAlt(number: Int): String = "Screenshot $number"
    override fun removeScreenshot(number: Int): String = "Remove screenshot $number"
    override fun openScreenshot(number: Int): String = "Open screenshot $number"
    override fun showScreenshots(count: Int): String = if (count == 1) "Show screenshot" else "Show $count screenshots"
    override val send = "Send"
    override val sending = "Sending…"
    override val feedbackSent = "Thanks. Your feedback has reached us."
    override val feedbackTooLarge = "The screenshots are too large. Remove one and try again."
    override val feedbackTooManyToday = "That is a lot of feedback for one day. Try again tomorrow."
    override val feedbackUnreadable = "That file could not be read as an image."
    override val feedbackFailed = "Could not send it. Check your connection and try again."
    override val feedbackSignedOut = "Your session has ended. Sign in again to send feedback."
    override fun feedbackHeading(open: Int): String = if (open == 0) "Feedback" else "Feedback ($open open)"
    override val feedbackEmpty = "No feedback yet."
    override val markResolved = "Mark resolved"
    override val reopen = "Reopen"
    override val resolved = "Resolved"
    override val deleteFeedbackTitle = "Delete this feedback?"
    override val deleteFeedbackBody = "Its screenshots are deleted too."
    override fun platformName(platform: String): String = when (platform) {
        "android" -> "Android"
        "wear" -> "Wear OS"
        "web" -> "Web"
        "ios" -> "iPhone"
        else -> platform
    }
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
    override val googleHint = "Välj ditt Google-konto i fönstret som öppnas."
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

    override val summaryTitle = "Pass klart"
    override val newRecord = "Nytt rekord"
    override val musclesWorked = "Tränade muskler"
    override fun musclesWorkedDescription(muscles: String) = "Tränade muskler: $muscles"
    override fun muscleName(muscle: Muscle): String = when (muscle) {
        Muscle.CHEST -> "bröst"
        Muscle.LATS -> "lats"
        Muscle.UPPER_BACK -> "övre rygg"
        Muscle.LOWER_BACK -> "ländrygg"
        Muscle.TRAPS -> "trapezius"
        Muscle.FRONT_DELTS -> "främre axlar"
        Muscle.SIDE_DELTS -> "sidoaxlar"
        Muscle.REAR_DELTS -> "bakre axlar"
        Muscle.BICEPS -> "biceps"
        Muscle.TRICEPS -> "triceps"
        Muscle.FOREARMS -> "underarmar"
        Muscle.QUADS -> "framsida lår"
        Muscle.HAMSTRINGS -> "baksida lår"
        Muscle.GLUTES -> "säte"
        Muscle.CALVES -> "vader"
        Muscle.ADDUCTORS -> "insida lår"
        Muscle.ABDUCTORS -> "utsida höft"
        Muscle.ABS -> "mage"
        Muscle.OBLIQUES -> "sneda magmuskler"
        Muscle.HIP_FLEXORS -> "höftböjare"
        Muscle.NECK -> "nacke"
    }
    override fun uncountedSets(count: Int) =
        if (count == 1) "1 set av en egen övning räknas inte: egna övningar har inga muskler"
        else "$count set av egna övningar räknas inte: egna övningar har inga muskler"

    override val all = "Alla"
    override fun groupLabel(group: MuscleGroup): String = when (group) {
        MuscleGroup.CHEST -> "Bröst"
        MuscleGroup.BACK -> "Rygg"
        MuscleGroup.SHOULDERS -> "Axlar"
        MuscleGroup.BICEPS -> "Biceps"
        MuscleGroup.TRICEPS -> "Triceps"
        MuscleGroup.LEGS -> "Ben"
        MuscleGroup.GLUTES -> "Säte"
        MuscleGroup.CORE -> "Bål"
        MuscleGroup.FULLBODY -> "Helkropp"
    }
    override val custom = "Egen"
    override fun equipmentFilter(count: Int?) = "Utrustning: " + (count?.toString() ?: "Alla")
    override val addToRoutine = "Lägg till i rutin"
    override val addToWorkout = "Lägg till i passet"
    override val chooseRoutine = "Till vilken rutin?"
    override val noRoutinesYet = "Inga rutiner än. Skapa en i fliken Rutiner med ”+ Ny rutin”, eller spara ett avslutat pass som en."
    override fun addedToRoutine(name: String) = "Tillagd i $name"
    override val notLogged = "Inte loggad än."
    override fun lastTime(date: String) = "Senast, $date"
    override val bestSetKg = "Bästa set, kg"
    override fun inWorkouts(count: Int) = "I $count pass"
    override val howTo = "Så gör du"
    override val primary = "Primär"
    override val secondary = "Sekundär"
    override fun mapDescription(primary: String, secondary: String?) =
        if (secondary == null) "Muskler som arbetar. Primär: $primary." else "Muskler som arbetar. Primär: $primary. Sekundär: $secondary."
    override val exerciseNotFound = "Övningen finns inte längre."

    override val programs = "Program"
    override val programsHint = "Rutiner på fasta veckodagar eller rullande"
    override fun programsActive(name: String) = "Aktivt: $name"
    override val programsEmpty = "Inga program. Ett program lägger dina rutiner på fasta veckodagar eller i rullande ordning, och Hem visar dagens."
    override val newProgram = "Nytt program"
    override val create = "Skapa"
    override val modeFixed = "Fasta dagar"
    override val modeFixedHint = "Varje rutin på de veckodagar du väljer"
    override val modeRotating = "Rullande"
    override val modeRotatingHint = "Dag 1, 2, 3 i tur och ordning, oavsett datum"
    override val mode = "Läge"
    override val active = "Aktivt"
    override val activate = "Aktivera"
    override val deactivate = "Avaktivera"
    override fun dayCount(count: Int) = if (count == 1) "1 dag" else "$count dagar"
    override fun dayOf(number: Int, count: Int) = "Dag $number av $count"
    override val noWeekdays = "Inga veckodagar"
    override val programDaysEmpty = "Inga dagar än. Lägg till dina rutiner i den ordning du tränar dem."
    override val addDay = "Lägg till dag"
    override val chooseRoutineForDay = "Välj en rutin"
    override val removeDay = "Ta bort dagen"
    override val renameProgram = "Byt namn på program"
    override val deleteProgram = "Radera program"
    override val deleteProgramTitle = "Radera program?"
    override val deleteProgramBody = "Programmet raderas. Dess rutiner, och passen du har gjort från det, finns kvar."
    override val programNotFound = "Programmet finns inte längre."
    override fun todayFixed(routine: String) = "Idag: $routine"
    override val restDay = "Vilodag"
    override val chooseAnother = "Välj en annan"
    override val chooseDay = "Välj en dag"
    override val dayGone = "Den dagen finns inte längre i programmet."
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

    override val serverAdmin = "Serveradmin"
    override val serverAdminHint = "Alla konton på den här servern"
    override fun accountCount(count: Int): String = if (count == 1) "1 konto" else "$count konton"
    override fun accountsShown(shown: Int, total: Int): String = "De $shown senaste av $total."
    override fun joined(date: String): String = "Skapat $date"
    override val signInGoogle = "Google"
    override val signInPassword = "Lösenord"
    override val adminBadge = "Admin"
    override fun lastSeen(date: String): String = "Senast sedd $date"
    override val neverSeen = "Inte sedd sedan kontot skapades"
    override fun lastWorkout(date: String): String = "senaste $date"
    override val testerSignups = "Testanmälningar"
    override val testerSignupsHint = "Adresser från startsidan till Android-testet på Google Play. Lägg in dem i testarlistan i Play Console och ta sedan bort dem här."
    override val testersEmpty = "Ingen väntar på en inbjudan."
    override val copyAddresses = "Kopiera adresser"
    override val copied = "Kopierat"
    override fun removeTester(email: String): String = "Ta bort $email"
    override val removeTesterBody = "Gör det när adressen är inbjuden i Play Console."
    override val adminFailed = "Det gick inte att nå servern. Kontrollera uppkopplingen och försök igen."
    override val adminNotAdmin = "Det här kontot är inte admin på servern."
    override val tryAgain = "Försök igen"

    override val sendFeedback = "Skicka feedback"
    override val sendFeedbackHint = "Rapportera en bugg eller föreslå en funktion"
    override val feedbackBug = "Bugg"
    override val feedbackIdea = "Idé"
    override val feedbackMessageLabel = "Vad hände, eller vad vill du ha?"
    override val feedbackScreenshotsHint = "Klistra in skärmdumpar med Ctrl+V, eller lägg till dem. Högst 3."
    override val feedbackScreenshotNotice = "En skärmdump visar allt som var på skärmen. Titta igenom den innan du skickar."
    override val addScreenshot = "Lägg till skärmdump"
    override fun screenshotAlt(number: Int): String = "Skärmdump $number"
    override fun removeScreenshot(number: Int): String = "Ta bort skärmdump $number"
    override fun openScreenshot(number: Int): String = "Öppna skärmdump $number"
    override fun showScreenshots(count: Int): String = if (count == 1) "Visa skärmdump" else "Visa $count skärmdumpar"
    override val send = "Skicka"
    override val sending = "Skickar…"
    override val feedbackSent = "Tack. Din feedback har kommit fram."
    override val feedbackTooLarge = "Skärmdumparna är för stora. Ta bort en och försök igen."
    override val feedbackTooManyToday = "Det är mycket feedback för en dag. Försök igen i morgon."
    override val feedbackUnreadable = "Filen gick inte att läsa som en bild."
    override val feedbackFailed = "Det gick inte att skicka. Kontrollera uppkopplingen och försök igen."
    override val feedbackSignedOut = "Sessionen har tagit slut. Logga in igen för att skicka feedback."
    override fun feedbackHeading(open: Int): String = if (open == 0) "Feedback" else "Feedback ($open öppna)"
    override val feedbackEmpty = "Ingen feedback än."
    override val markResolved = "Markera som klar"
    override val reopen = "Öppna igen"
    override val resolved = "Klar"
    override val deleteFeedbackTitle = "Ta bort den här feedbacken?"
    override val deleteFeedbackBody = "Skärmdumparna tas också bort."
    override fun platformName(platform: String): String = when (platform) {
        "android" -> "Android"
        "wear" -> "Wear OS"
        "web" -> "Webb"
        "ios" -> "iPhone"
        else -> platform
    }
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
