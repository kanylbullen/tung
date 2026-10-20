package io.github.kanylbullen.tung.core.health

/**
 * The Health Connect exercise segment types a strength-training session can
 * carry, named after `ExerciseSegment.EXERCISE_SEGMENT_TYPE_<name>` in
 * `androidx.health.connect`. Core cannot see that library, so `data/` maps
 * each entry to the library's constant with an exhaustive `when`; the
 * compiler points there when an entry is added.
 */
enum class HealthSegmentType {
    ARM_CURL,
    BACK_EXTENSION,
    BARBELL_SHOULDER_PRESS,
    BENCH_PRESS,
    BURPEE,
    CRUNCH,
    DEADLIFT,
    DUMBBELL_ROW,
    DUMBBELL_TRICEPS_EXTENSION_TWO_ARM,
    FRONT_RAISE,
    HIP_THRUST,
    KETTLEBELL_SWING,
    LATERAL_RAISE,
    LAT_PULL_DOWN,
    LEG_CURL,
    LEG_EXTENSION,
    LEG_PRESS,
    LEG_RAISE,
    LUNGE,
    PLANK,
    PULL_UP,
    SHOULDER_PRESS,
    SIT_UP,
    SQUAT,
    WEIGHTLIFTING,
}

/**
 * Catalogue key (`catalog/core.json`) → the Health Connect segment type it
 * obviously is. Only clear matches: a variant of a lift maps to the lift
 * (front squat → squat, Romanian deadlift → deadlift, incline bench →
 * bench press). Everything else (flies, rows on a cable, calf raises, dips,
 * …) has no entry, and its sets stay unsegmented in the written session.
 * Keys never change once they exist, so this table only grows.
 */
val HEALTH_CONNECT_SEGMENTS: Map<String, HealthSegmentType> = mapOf(
    "arnold_press" to HealthSegmentType.SHOULDER_PRESS,
    "back_extension" to HealthSegmentType.BACK_EXTENSION,
    "back_squat" to HealthSegmentType.SQUAT,
    "barbell_curl" to HealthSegmentType.ARM_CURL,
    "bench_press" to HealthSegmentType.BENCH_PRESS,
    "burpee" to HealthSegmentType.BURPEE,
    "cable_crunch" to HealthSegmentType.CRUNCH,
    "cable_curl" to HealthSegmentType.ARM_CURL,
    "chin_up" to HealthSegmentType.PULL_UP,
    "close_grip_bench_press" to HealthSegmentType.BENCH_PRESS,
    "concentration_curl" to HealthSegmentType.ARM_CURL,
    "deadlift" to HealthSegmentType.DEADLIFT,
    "dumbbell_bench_press" to HealthSegmentType.BENCH_PRESS,
    "dumbbell_curl" to HealthSegmentType.ARM_CURL,
    "dumbbell_row" to HealthSegmentType.DUMBBELL_ROW,
    "dumbbell_shoulder_press" to HealthSegmentType.SHOULDER_PRESS,
    "front_raise" to HealthSegmentType.FRONT_RAISE,
    "front_squat" to HealthSegmentType.SQUAT,
    "goblet_squat" to HealthSegmentType.SQUAT,
    "hack_squat" to HealthSegmentType.SQUAT,
    "hammer_curl" to HealthSegmentType.ARM_CURL,
    "hanging_leg_raise" to HealthSegmentType.LEG_RAISE,
    "hip_thrust" to HealthSegmentType.HIP_THRUST,
    "incline_bench_press" to HealthSegmentType.BENCH_PRESS,
    "incline_dumbbell_press" to HealthSegmentType.BENCH_PRESS,
    "kettlebell_swing" to HealthSegmentType.KETTLEBELL_SWING,
    "lat_pulldown" to HealthSegmentType.LAT_PULL_DOWN,
    "lateral_raise" to HealthSegmentType.LATERAL_RAISE,
    "leg_curl" to HealthSegmentType.LEG_CURL,
    "leg_extension" to HealthSegmentType.LEG_EXTENSION,
    "leg_press" to HealthSegmentType.LEG_PRESS,
    "lunge" to HealthSegmentType.LUNGE,
    "machine_shoulder_press" to HealthSegmentType.SHOULDER_PRESS,
    "overhead_press" to HealthSegmentType.BARBELL_SHOULDER_PRESS,
    "overhead_triceps_extension" to HealthSegmentType.DUMBBELL_TRICEPS_EXTENSION_TWO_ARM,
    "plank" to HealthSegmentType.PLANK,
    "power_clean" to HealthSegmentType.WEIGHTLIFTING,
    "preacher_curl" to HealthSegmentType.ARM_CURL,
    "pull_up" to HealthSegmentType.PULL_UP,
    "romanian_deadlift" to HealthSegmentType.DEADLIFT,
    "side_plank" to HealthSegmentType.PLANK,
    "sit_up" to HealthSegmentType.SIT_UP,
    "snatch" to HealthSegmentType.WEIGHTLIFTING,
    "sumo_deadlift" to HealthSegmentType.DEADLIFT,
)

/** The segment type of the catalogue exercise [key]; null for a custom or extended exercise and for keys without a match. */
fun healthSegmentFor(key: String?): HealthSegmentType? = key?.let(HEALTH_CONNECT_SEGMENTS::get)
