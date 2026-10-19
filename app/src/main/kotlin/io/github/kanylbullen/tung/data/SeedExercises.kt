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

/** A catalogue exercise: a stable [key] for `ex_<key>` and the Swedish [name] as fallback. */
internal data class SeedExercise(
    val key: String,
    val name: String,
    val muscle: MuscleGroup,
    val equipment: Equipment,
)

/**
 * The starting catalogue. What the user sees comes from the string resource
 * `ex_<key>` in `values/strings.xml` and `values-en/strings.xml`; [SeedExercise.name]
 * is stored as the Swedish fallback. A new entry here needs a string in both files.
 */
internal object SeedExercises {
    val all: List<SeedExercise> = listOf(
        SeedExercise("bench_press", "Bänkpress", CHEST, BARBELL),
        SeedExercise("incline_bench_press", "Lutande bänkpress", CHEST, BARBELL),
        SeedExercise("dumbbell_bench_press", "Hantelpress", CHEST, DUMBBELL),
        SeedExercise("incline_dumbbell_press", "Lutande hantelpress", CHEST, DUMBBELL),
        SeedExercise("dumbbell_fly", "Hantelflyes", CHEST, DUMBBELL),
        SeedExercise("cable_fly", "Kabelflyes", CHEST, CABLE),
        SeedExercise("machine_chest_press", "Bröstpress i maskin", CHEST, MACHINE),
        SeedExercise("dips", "Dips", CHEST, BODYWEIGHT),
        SeedExercise("push_up", "Armhävningar", CHEST, BODYWEIGHT),

        SeedExercise("deadlift", "Marklyft", BACK, BARBELL),
        SeedExercise("chin_up", "Chins", BACK, BODYWEIGHT),
        SeedExercise("pull_up", "Pull-ups", BACK, BODYWEIGHT),
        SeedExercise("barbell_row", "Rodd med skivstång", BACK, BARBELL),
        SeedExercise("dumbbell_row", "Hantelrodd", BACK, DUMBBELL),
        SeedExercise("lat_pulldown", "Latsdrag", BACK, CABLE),
        SeedExercise("seated_cable_row", "Sittande kabelrodd", BACK, CABLE),
        SeedExercise("t_bar_row", "T-gripsrodd", BACK, BARBELL),
        SeedExercise("back_extension", "Ryggresningar", BACK, BODYWEIGHT),

        SeedExercise("overhead_press", "Militärpress", SHOULDERS, BARBELL),
        SeedExercise("dumbbell_shoulder_press", "Axelpress med hantlar", SHOULDERS, DUMBBELL),
        SeedExercise("arnold_press", "Arnoldpress", SHOULDERS, DUMBBELL),
        SeedExercise("machine_shoulder_press", "Axelpress i maskin", SHOULDERS, MACHINE),
        SeedExercise("lateral_raise", "Sidolyft", SHOULDERS, DUMBBELL),
        SeedExercise("front_raise", "Framåtlyft", SHOULDERS, DUMBBELL),
        SeedExercise("reverse_fly", "Omvända flyes", SHOULDERS, DUMBBELL),
        SeedExercise("face_pull", "Face pulls", SHOULDERS, CABLE),
        SeedExercise("shrug", "Shrugs", SHOULDERS, DUMBBELL),

        SeedExercise("dumbbell_curl", "Hantelcurl", BICEPS, DUMBBELL),
        SeedExercise("barbell_curl", "Skivstångscurl", BICEPS, BARBELL),
        SeedExercise("hammer_curl", "Hammercurl", BICEPS, DUMBBELL),
        SeedExercise("preacher_curl", "Scottcurl", BICEPS, BARBELL),
        SeedExercise("cable_curl", "Kabelcurl", BICEPS, CABLE),
        SeedExercise("concentration_curl", "Koncentrationscurl", BICEPS, DUMBBELL),

        SeedExercise("triceps_pushdown", "Pushdowns", TRICEPS, CABLE),
        SeedExercise("skull_crusher", "Fransk press", TRICEPS, BARBELL),
        SeedExercise("close_grip_bench_press", "Smal bänkpress", TRICEPS, BARBELL),
        SeedExercise("overhead_triceps_extension", "Tricepsextension över huvudet", TRICEPS, DUMBBELL),
        SeedExercise("triceps_kickback", "Kickbacks", TRICEPS, DUMBBELL),
        SeedExercise("bench_dip", "Bänkdips", TRICEPS, BODYWEIGHT),

        SeedExercise("back_squat", "Knäböj", LEGS, BARBELL),
        SeedExercise("front_squat", "Frontböj", LEGS, BARBELL),
        SeedExercise("leg_press", "Benpress", LEGS, MACHINE),
        SeedExercise("hack_squat", "Hack squat", LEGS, MACHINE),
        SeedExercise("goblet_squat", "Gobletböj", LEGS, DUMBBELL),
        SeedExercise("lunge", "Utfall", LEGS, DUMBBELL),
        SeedExercise("bulgarian_split_squat", "Bulgarisk utfallsböj", LEGS, DUMBBELL),
        SeedExercise("romanian_deadlift", "Rumänska marklyft", LEGS, BARBELL),
        SeedExercise("leg_curl", "Bencurl", LEGS, MACHINE),
        SeedExercise("leg_extension", "Bensträck", LEGS, MACHINE),
        SeedExercise("standing_calf_raise", "Stående vadpress", LEGS, MACHINE),

        SeedExercise("hip_thrust", "Höftlyft", GLUTES, BARBELL),
        SeedExercise("glute_bridge", "Bäckenlyft", GLUTES, BODYWEIGHT),
        SeedExercise("sumo_deadlift", "Sumomarklyft", GLUTES, BARBELL),
        SeedExercise("cable_kickback", "Kabelkickbacks", GLUTES, CABLE),
        SeedExercise("machine_hip_abduction", "Höftabduktion i maskin", GLUTES, MACHINE),
        SeedExercise("step_up", "Step-ups", GLUTES, DUMBBELL),

        SeedExercise("plank", "Plankan", CORE, BODYWEIGHT),
        SeedExercise("side_plank", "Sidoplanka", CORE, BODYWEIGHT),
        SeedExercise("sit_up", "Situps", CORE, BODYWEIGHT),
        SeedExercise("hanging_leg_raise", "Hängande benlyft", CORE, BODYWEIGHT),
        SeedExercise("cable_crunch", "Kabelcrunch", CORE, CABLE),
        SeedExercise("russian_twist", "Rysk twist", CORE, OTHER),
        SeedExercise("ab_wheel_rollout", "Magrulle", CORE, OTHER),
        SeedExercise("pallof_press", "Pallof press", CORE, CABLE),

        SeedExercise("power_clean", "Frivändning", FULLBODY, BARBELL),
        SeedExercise("snatch", "Ryck", FULLBODY, BARBELL),
        SeedExercise("thruster", "Thrusters", FULLBODY, BARBELL),
        SeedExercise("kettlebell_swing", "Kettlebellsving", FULLBODY, OTHER),
        SeedExercise("farmers_walk", "Farmers walk", FULLBODY, DUMBBELL),
        SeedExercise("burpee", "Burpees", FULLBODY, BODYWEIGHT),
    )
}
