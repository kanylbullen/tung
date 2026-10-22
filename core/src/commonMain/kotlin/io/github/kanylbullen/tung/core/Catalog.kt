package io.github.kanylbullen.tung.core

/** Stored as [key] in the database; the order here is the order in lists. */
enum class MuscleGroup(val key: String) {
    CHEST("chest"),
    BACK("back"),
    SHOULDERS("shoulders"),
    BICEPS("biceps"),
    TRICEPS("triceps"),
    LEGS("legs"),
    GLUTES("glutes"),
    CORE("core"),
    FULLBODY("fullbody");

    companion object {
        fun fromKey(key: String): MuscleGroup = entries.firstOrNull { it.key == key } ?: FULLBODY

        /**
         * The display group of an exercise whose first primary muscle is
         * [firstPrimary]. The catalogue rule: an entry's `muscleGroup` is
         * `of(primaryMuscles[0])`, or `fullbody` for whole-body lifts (burpee,
         * snatch), which no muscle maps to ([muscleGroupMatches]).
         */
        fun of(firstPrimary: Muscle): MuscleGroup = firstPrimary.group
    }
}

/**
 * What an exercise is done with. Stored and synced as [key] (Room's `exercise.equipment`
 * is a string), so a new value needs no schema change; a key this build does not know
 * (a newer catalogue or client) reads as [OTHER]. The order is the order the app lists them in.
 */
enum class Equipment(val key: String) {
    BARBELL("barbell"),
    EZ_BAR("ez_bar"),
    DUMBBELL("dumbbell"),
    KETTLEBELL("kettlebell"),
    MACHINE("machine"),
    CABLE("cable"),
    BAND("band"),
    BODYWEIGHT("bodyweight"),
    MEDICINE_BALL("medicine_ball"),
    EXERCISE_BALL("exercise_ball"),
    OTHER("other");

    companion object {
        fun fromKey(key: String?): Equipment = entries.firstOrNull { it.key == key } ?: OTHER
    }
}
