// Genererad ur catalog/core.json, redigera inte. Kör catalog/tools/gen_android.py.

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
 * The starting catalogue, generated from `catalog/core.json`. What the user sees
 * comes from the string resource `ex_<key>` in `values/strings.xml` and
 * `values-en/strings.xml`, generated from the same file; [SeedExercise.name] is
 * stored as the Swedish fallback. To add an entry, edit `catalog/core.json` and
 * run `catalog/tools/gen_android.py`.
 */
internal object SeedExercises {
    val all: List<SeedExercise> = listOf(
        SeedExercise("bench_press", "Bänkpress", CHEST, BARBELL),
        SeedExercise("cable_fly", "Kabelflyes", CHEST, CABLE),
        SeedExercise("dips", "Dips", CHEST, BODYWEIGHT),
        SeedExercise("dumbbell_bench_press", "Hantelpress", CHEST, DUMBBELL),
        SeedExercise("dumbbell_fly", "Hantelflyes", CHEST, DUMBBELL),
        SeedExercise("incline_bench_press", "Lutande bänkpress", CHEST, BARBELL),
        SeedExercise("incline_dumbbell_press", "Lutande hantelpress", CHEST, DUMBBELL),
        SeedExercise("machine_chest_press", "Bröstpress i maskin", CHEST, MACHINE),
        SeedExercise("push_up", "Armhävningar", CHEST, BODYWEIGHT),

        SeedExercise("back_extension", "Ryggresningar", BACK, BODYWEIGHT),
        SeedExercise("barbell_row", "Rodd med skivstång", BACK, BARBELL),
        SeedExercise("chin_up", "Chins", BACK, BODYWEIGHT),
        SeedExercise("deadlift", "Marklyft", BACK, BARBELL),
        SeedExercise("dumbbell_row", "Hantelrodd", BACK, DUMBBELL),
        SeedExercise("lat_pulldown", "Latsdrag", BACK, CABLE),
        SeedExercise("pull_up", "Pull-ups", BACK, BODYWEIGHT),
        SeedExercise("seated_cable_row", "Sittande kabelrodd", BACK, CABLE),
        SeedExercise("t_bar_row", "T-gripsrodd", BACK, BARBELL),

        SeedExercise("arnold_press", "Arnoldpress", SHOULDERS, DUMBBELL),
        SeedExercise("dumbbell_shoulder_press", "Axelpress med hantlar", SHOULDERS, DUMBBELL),
        SeedExercise("face_pull", "Face pulls", SHOULDERS, CABLE),
        SeedExercise("front_raise", "Framåtlyft", SHOULDERS, DUMBBELL),
        SeedExercise("lateral_raise", "Sidolyft", SHOULDERS, DUMBBELL),
        SeedExercise("machine_shoulder_press", "Axelpress i maskin", SHOULDERS, MACHINE),
        SeedExercise("overhead_press", "Militärpress", SHOULDERS, BARBELL),
        SeedExercise("reverse_fly", "Omvända flyes", SHOULDERS, DUMBBELL),
        SeedExercise("shrug", "Shrugs", SHOULDERS, DUMBBELL),

        SeedExercise("barbell_curl", "Skivstångscurl", BICEPS, BARBELL),
        SeedExercise("cable_curl", "Kabelcurl", BICEPS, CABLE),
        SeedExercise("concentration_curl", "Koncentrationscurl", BICEPS, DUMBBELL),
        SeedExercise("dumbbell_curl", "Hantelcurl", BICEPS, DUMBBELL),
        SeedExercise("hammer_curl", "Hammercurl", BICEPS, DUMBBELL),
        SeedExercise("preacher_curl", "Scottcurl", BICEPS, BARBELL),

        SeedExercise("bench_dip", "Bänkdips", TRICEPS, BODYWEIGHT),
        SeedExercise("close_grip_bench_press", "Smal bänkpress", TRICEPS, BARBELL),
        SeedExercise("overhead_triceps_extension", "Tricepsextension över huvudet", TRICEPS, DUMBBELL),
        SeedExercise("skull_crusher", "Fransk press", TRICEPS, BARBELL),
        SeedExercise("triceps_kickback", "Kickbacks", TRICEPS, DUMBBELL),
        SeedExercise("triceps_pushdown", "Pushdowns", TRICEPS, CABLE),

        SeedExercise("back_squat", "Knäböj", LEGS, BARBELL),
        SeedExercise("bulgarian_split_squat", "Bulgarisk utfallsböj", LEGS, DUMBBELL),
        SeedExercise("front_squat", "Frontböj", LEGS, BARBELL),
        SeedExercise("goblet_squat", "Gobletböj", LEGS, DUMBBELL),
        SeedExercise("hack_squat", "Hack squat", LEGS, MACHINE),
        SeedExercise("leg_curl", "Bencurl", LEGS, MACHINE),
        SeedExercise("leg_extension", "Bensträck", LEGS, MACHINE),
        SeedExercise("leg_press", "Benpress", LEGS, MACHINE),
        SeedExercise("lunge", "Utfall", LEGS, DUMBBELL),
        SeedExercise("romanian_deadlift", "Rumänska marklyft", LEGS, BARBELL),
        SeedExercise("standing_calf_raise", "Stående vadpress", LEGS, MACHINE),

        SeedExercise("cable_kickback", "Kabelkickbacks", GLUTES, CABLE),
        SeedExercise("glute_bridge", "Bäckenlyft", GLUTES, BODYWEIGHT),
        SeedExercise("hip_thrust", "Höftlyft", GLUTES, BARBELL),
        SeedExercise("machine_hip_abduction", "Höftabduktion i maskin", GLUTES, MACHINE),
        SeedExercise("step_up", "Step-ups", GLUTES, DUMBBELL),
        SeedExercise("sumo_deadlift", "Sumomarklyft", GLUTES, BARBELL),

        SeedExercise("ab_wheel_rollout", "Magrulle", CORE, OTHER),
        SeedExercise("cable_crunch", "Kabelcrunch", CORE, CABLE),
        SeedExercise("hanging_leg_raise", "Hängande benlyft", CORE, BODYWEIGHT),
        SeedExercise("pallof_press", "Pallof press", CORE, CABLE),
        SeedExercise("plank", "Plankan", CORE, BODYWEIGHT),
        SeedExercise("russian_twist", "Rysk twist", CORE, OTHER),
        SeedExercise("side_plank", "Sidoplanka", CORE, BODYWEIGHT),
        SeedExercise("sit_up", "Situps", CORE, BODYWEIGHT),

        SeedExercise("burpee", "Burpees", FULLBODY, BODYWEIGHT),
        SeedExercise("farmers_walk", "Farmers walk", FULLBODY, DUMBBELL),
        SeedExercise("kettlebell_swing", "Kettlebellsving", FULLBODY, OTHER),
        SeedExercise("power_clean", "Frivändning", FULLBODY, BARBELL),
        SeedExercise("snatch", "Ryck", FULLBODY, BARBELL),
        SeedExercise("thruster", "Thrusters", FULLBODY, BARBELL),
    )
}
