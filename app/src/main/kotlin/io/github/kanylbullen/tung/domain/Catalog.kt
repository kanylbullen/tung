package io.github.kanylbullen.tung.domain

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
    }
}

enum class Equipment(val key: String) {
    BARBELL("barbell"),
    DUMBBELL("dumbbell"),
    MACHINE("machine"),
    CABLE("cable"),
    BODYWEIGHT("bodyweight"),
    OTHER("other");

    companion object {
        fun fromKey(key: String): Equipment = entries.firstOrNull { it.key == key } ?: OTHER
    }
}
