// Generated from catalog/core.json, do not edit. Run catalog/tools/gen_android.py.

package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.Equipment.BARBELL
import io.github.kanylbullen.tung.core.Equipment.BODYWEIGHT
import io.github.kanylbullen.tung.core.Equipment.CABLE
import io.github.kanylbullen.tung.core.Equipment.DUMBBELL
import io.github.kanylbullen.tung.core.Equipment.MACHINE
import io.github.kanylbullen.tung.core.Equipment.OTHER
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.MuscleGroup.BACK
import io.github.kanylbullen.tung.core.MuscleGroup.BICEPS
import io.github.kanylbullen.tung.core.MuscleGroup.CHEST
import io.github.kanylbullen.tung.core.MuscleGroup.CORE
import io.github.kanylbullen.tung.core.MuscleGroup.FULLBODY
import io.github.kanylbullen.tung.core.MuscleGroup.GLUTES
import io.github.kanylbullen.tung.core.MuscleGroup.LEGS
import io.github.kanylbullen.tung.core.MuscleGroup.SHOULDERS
import io.github.kanylbullen.tung.core.MuscleGroup.TRICEPS

/** A catalogue exercise: a stable [key] for `ex_<key>` and the English [name] as fallback. */
internal data class SeedExercise(
    val key: String,
    val name: String,
    val muscle: MuscleGroup,
    val equipment: Equipment,
)

/**
 * The starting catalogue, generated from `catalog/core.json`. What the user sees
 * comes from the string resource `ex_<key>` in `values/strings.xml` (English)
 * and `values-sv/strings.xml` (Swedish), generated from the same file;
 * [SeedExercise.name] is stored as the English fallback. To add an entry, edit
 * `catalog/core.json` and run `catalog/tools/gen_android.py`.
 */
internal object SeedExercises {
    val all: List<SeedExercise> = listOf(
        SeedExercise("bench_press", "Bench press", CHEST, BARBELL),
        SeedExercise("cable_fly", "Cable fly", CHEST, CABLE),
        SeedExercise("dips", "Dips", CHEST, BODYWEIGHT),
        SeedExercise("dumbbell_bench_press", "Dumbbell bench press", CHEST, DUMBBELL),
        SeedExercise("dumbbell_fly", "Dumbbell fly", CHEST, DUMBBELL),
        SeedExercise("incline_bench_press", "Incline bench press", CHEST, BARBELL),
        SeedExercise("incline_dumbbell_press", "Incline dumbbell press", CHEST, DUMBBELL),
        SeedExercise("machine_chest_press", "Machine chest press", CHEST, MACHINE),
        SeedExercise("push_up", "Push-up", CHEST, BODYWEIGHT),

        SeedExercise("back_extension", "Back extension", BACK, BODYWEIGHT),
        SeedExercise("barbell_row", "Barbell row", BACK, BARBELL),
        SeedExercise("chin_up", "Chin-up", BACK, BODYWEIGHT),
        SeedExercise("deadlift", "Deadlift", BACK, BARBELL),
        SeedExercise("dumbbell_row", "Dumbbell row", BACK, DUMBBELL),
        SeedExercise("lat_pulldown", "Lat pulldown", BACK, CABLE),
        SeedExercise("pull_up", "Pull-up", BACK, BODYWEIGHT),
        SeedExercise("seated_cable_row", "Seated cable row", BACK, CABLE),
        SeedExercise("shrug", "Shrug", BACK, DUMBBELL),
        SeedExercise("t_bar_row", "T-bar row", BACK, BARBELL),

        SeedExercise("arnold_press", "Arnold press", SHOULDERS, DUMBBELL),
        SeedExercise("dumbbell_shoulder_press", "Dumbbell shoulder press", SHOULDERS, DUMBBELL),
        SeedExercise("face_pull", "Face pull", SHOULDERS, CABLE),
        SeedExercise("front_raise", "Front raise", SHOULDERS, DUMBBELL),
        SeedExercise("lateral_raise", "Lateral raise", SHOULDERS, DUMBBELL),
        SeedExercise("machine_shoulder_press", "Machine shoulder press", SHOULDERS, MACHINE),
        SeedExercise("overhead_press", "Overhead press", SHOULDERS, BARBELL),
        SeedExercise("reverse_fly", "Reverse fly", SHOULDERS, DUMBBELL),

        SeedExercise("barbell_curl", "Barbell curl", BICEPS, BARBELL),
        SeedExercise("cable_curl", "Cable curl", BICEPS, CABLE),
        SeedExercise("concentration_curl", "Concentration curl", BICEPS, DUMBBELL),
        SeedExercise("dumbbell_curl", "Dumbbell curl", BICEPS, DUMBBELL),
        SeedExercise("hammer_curl", "Hammer curl", BICEPS, DUMBBELL),
        SeedExercise("preacher_curl", "Preacher curl", BICEPS, BARBELL),

        SeedExercise("bench_dip", "Bench dip", TRICEPS, BODYWEIGHT),
        SeedExercise("close_grip_bench_press", "Close-grip bench press", TRICEPS, BARBELL),
        SeedExercise("overhead_triceps_extension", "Overhead triceps extension", TRICEPS, DUMBBELL),
        SeedExercise("skull_crusher", "Skull crusher", TRICEPS, BARBELL),
        SeedExercise("triceps_kickback", "Triceps kickback", TRICEPS, DUMBBELL),
        SeedExercise("triceps_pushdown", "Triceps pushdown", TRICEPS, CABLE),

        SeedExercise("back_squat", "Back squat", LEGS, BARBELL),
        SeedExercise("bulgarian_split_squat", "Bulgarian split squat", LEGS, DUMBBELL),
        SeedExercise("front_squat", "Front squat", LEGS, BARBELL),
        SeedExercise("goblet_squat", "Goblet squat", LEGS, DUMBBELL),
        SeedExercise("hack_squat", "Hack squat", LEGS, MACHINE),
        SeedExercise("leg_curl", "Leg curl", LEGS, MACHINE),
        SeedExercise("leg_extension", "Leg extension", LEGS, MACHINE),
        SeedExercise("leg_press", "Leg press", LEGS, MACHINE),
        SeedExercise("lunge", "Lunge", LEGS, DUMBBELL),
        SeedExercise("romanian_deadlift", "Romanian deadlift", LEGS, BARBELL),
        SeedExercise("standing_calf_raise", "Standing calf raise", LEGS, MACHINE),

        SeedExercise("cable_kickback", "Cable kickback", GLUTES, CABLE),
        SeedExercise("glute_bridge", "Glute bridge", GLUTES, BODYWEIGHT),
        SeedExercise("hip_thrust", "Hip thrust", GLUTES, BARBELL),
        SeedExercise("machine_hip_abduction", "Hip abduction machine", GLUTES, MACHINE),
        SeedExercise("step_up", "Step-up", GLUTES, DUMBBELL),
        SeedExercise("sumo_deadlift", "Sumo deadlift", GLUTES, BARBELL),

        SeedExercise("ab_wheel_rollout", "Ab wheel rollout", CORE, OTHER),
        SeedExercise("cable_crunch", "Cable crunch", CORE, CABLE),
        SeedExercise("hanging_leg_raise", "Hanging leg raise", CORE, BODYWEIGHT),
        SeedExercise("pallof_press", "Pallof press", CORE, CABLE),
        SeedExercise("plank", "Plank", CORE, BODYWEIGHT),
        SeedExercise("russian_twist", "Russian twist", CORE, OTHER),
        SeedExercise("side_plank", "Side plank", CORE, BODYWEIGHT),
        SeedExercise("sit_up", "Sit-up", CORE, BODYWEIGHT),

        SeedExercise("burpee", "Burpee", FULLBODY, BODYWEIGHT),
        SeedExercise("farmers_walk", "Farmer's walk", FULLBODY, DUMBBELL),
        SeedExercise("kettlebell_swing", "Kettlebell swing", FULLBODY, OTHER),
        SeedExercise("power_clean", "Power clean", FULLBODY, BARBELL),
        SeedExercise("snatch", "Snatch", FULLBODY, BARBELL),
        SeedExercise("thruster", "Thruster", FULLBODY, BARBELL),
    )
}
