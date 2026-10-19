package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.domain.Equipment
import io.github.kanylbullen.tung.domain.Equipment.BARBELL
import io.github.kanylbullen.tung.domain.Equipment.BODYWEIGHT
import io.github.kanylbullen.tung.domain.Equipment.CABLE
import io.github.kanylbullen.tung.domain.Equipment.DUMBBELL
import io.github.kanylbullen.tung.domain.Equipment.MACHINE
import io.github.kanylbullen.tung.domain.Equipment.OTHER
import io.github.kanylbullen.tung.domain.MuscleGroup
import io.github.kanylbullen.tung.domain.MuscleGroup.BACK
import io.github.kanylbullen.tung.domain.MuscleGroup.BICEPS
import io.github.kanylbullen.tung.domain.MuscleGroup.CHEST
import io.github.kanylbullen.tung.domain.MuscleGroup.CORE
import io.github.kanylbullen.tung.domain.MuscleGroup.FULLBODY
import io.github.kanylbullen.tung.domain.MuscleGroup.GLUTES
import io.github.kanylbullen.tung.domain.MuscleGroup.LEGS
import io.github.kanylbullen.tung.domain.MuscleGroup.SHOULDERS
import io.github.kanylbullen.tung.domain.MuscleGroup.TRICEPS

/**
 * The starting catalogue. Names are data, not UI strings: they are written to
 * the database once and then belong to the user, so they live here rather than
 * in strings.xml.
 */
internal object SeedExercises {
    val all: List<Triple<String, MuscleGroup, Equipment>> = listOf(
        Triple("Bänkpress", CHEST, BARBELL),
        Triple("Lutande bänkpress", CHEST, BARBELL),
        Triple("Hantelpress", CHEST, DUMBBELL),
        Triple("Lutande hantelpress", CHEST, DUMBBELL),
        Triple("Hantelflyes", CHEST, DUMBBELL),
        Triple("Kabelflyes", CHEST, CABLE),
        Triple("Bröstpress i maskin", CHEST, MACHINE),
        Triple("Dips", CHEST, BODYWEIGHT),
        Triple("Armhävningar", CHEST, BODYWEIGHT),

        Triple("Marklyft", BACK, BARBELL),
        Triple("Chins", BACK, BODYWEIGHT),
        Triple("Pull-ups", BACK, BODYWEIGHT),
        Triple("Rodd med skivstång", BACK, BARBELL),
        Triple("Hantelrodd", BACK, DUMBBELL),
        Triple("Latsdrag", BACK, CABLE),
        Triple("Sittande kabelrodd", BACK, CABLE),
        Triple("T-gripsrodd", BACK, BARBELL),
        Triple("Ryggresningar", BACK, BODYWEIGHT),

        Triple("Militärpress", SHOULDERS, BARBELL),
        Triple("Axelpress med hantlar", SHOULDERS, DUMBBELL),
        Triple("Arnoldpress", SHOULDERS, DUMBBELL),
        Triple("Axelpress i maskin", SHOULDERS, MACHINE),
        Triple("Sidolyft", SHOULDERS, DUMBBELL),
        Triple("Framåtlyft", SHOULDERS, DUMBBELL),
        Triple("Omvända flyes", SHOULDERS, DUMBBELL),
        Triple("Face pulls", SHOULDERS, CABLE),
        Triple("Shrugs", SHOULDERS, DUMBBELL),

        Triple("Hantelcurl", BICEPS, DUMBBELL),
        Triple("Skivstångscurl", BICEPS, BARBELL),
        Triple("Hammercurl", BICEPS, DUMBBELL),
        Triple("Scottcurl", BICEPS, BARBELL),
        Triple("Kabelcurl", BICEPS, CABLE),
        Triple("Koncentrationscurl", BICEPS, DUMBBELL),

        Triple("Pushdowns", TRICEPS, CABLE),
        Triple("Fransk press", TRICEPS, BARBELL),
        Triple("Smal bänkpress", TRICEPS, BARBELL),
        Triple("Tricepsextension över huvudet", TRICEPS, DUMBBELL),
        Triple("Kickbacks", TRICEPS, DUMBBELL),
        Triple("Bänkdips", TRICEPS, BODYWEIGHT),

        Triple("Knäböj", LEGS, BARBELL),
        Triple("Frontböj", LEGS, BARBELL),
        Triple("Benpress", LEGS, MACHINE),
        Triple("Hack squat", LEGS, MACHINE),
        Triple("Gobletböj", LEGS, DUMBBELL),
        Triple("Utfall", LEGS, DUMBBELL),
        Triple("Bulgarisk utfallsböj", LEGS, DUMBBELL),
        Triple("Rumänska marklyft", LEGS, BARBELL),
        Triple("Bencurl", LEGS, MACHINE),
        Triple("Bensträck", LEGS, MACHINE),
        Triple("Stående vadpress", LEGS, MACHINE),

        Triple("Höftlyft", GLUTES, BARBELL),
        Triple("Bäckenlyft", GLUTES, BODYWEIGHT),
        Triple("Sumomarklyft", GLUTES, BARBELL),
        Triple("Kabelkickbacks", GLUTES, CABLE),
        Triple("Höftabduktion i maskin", GLUTES, MACHINE),
        Triple("Step-ups", GLUTES, DUMBBELL),

        Triple("Plankan", CORE, BODYWEIGHT),
        Triple("Sidoplanka", CORE, BODYWEIGHT),
        Triple("Situps", CORE, BODYWEIGHT),
        Triple("Hängande benlyft", CORE, BODYWEIGHT),
        Triple("Kabelcrunch", CORE, CABLE),
        Triple("Rysk twist", CORE, OTHER),
        Triple("Magrulle", CORE, OTHER),
        Triple("Pallof press", CORE, CABLE),

        Triple("Frivändning", FULLBODY, BARBELL),
        Triple("Ryck", FULLBODY, BARBELL),
        Triple("Thrusters", FULLBODY, BARBELL),
        Triple("Kettlebellsving", FULLBODY, OTHER),
        Triple("Farmers walk", FULLBODY, DUMBBELL),
        Triple("Burpees", FULLBODY, BODYWEIGHT),
    )
}
