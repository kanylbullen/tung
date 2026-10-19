#!/usr/bin/env python3
"""Import the extended catalogue from free-exercise-db into catalog/extended.json.

Source: https://github.com/yuhonas/free-exercise-db (Unlicense). Only the
strength-type categories are kept, mapped to Tung's muscle groups and
equipment. Exercises that duplicate the core catalogue (catalog/core.json)
are dropped. Images are not imported: their origin is undocumented.

  python3 -I catalog/tools/import_free_exercise_db.py               # download and write
  python3 -I catalog/tools/import_free_exercise_db.py --input x.json  # use a local copy

The output is deterministic for a given input.
"""

import argparse
import collections
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "catalog" / "core.json"
OUTPUT = ROOT / "catalog" / "extended.json"
SOURCE_URL = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json"
SOURCE = "free-exercise-db"

CATEGORIES = {"strength", "powerlifting", "olympic weightlifting", "strongman"}

MUSCLE = {
    "chest": "chest",
    "lats": "back",
    "middle back": "back",
    "lower back": "back",
    "traps": "back",
    "shoulders": "shoulders",
    "biceps": "biceps",
    "forearms": "biceps",
    "triceps": "triceps",
    "quadriceps": "legs",
    "hamstrings": "legs",
    "calves": "legs",
    "adductors": "legs",
    "abductors": "legs",
    "glutes": "glutes",
    "abdominals": "core",
}  # neck and anything else: fullbody

EQUIPMENT = {
    "barbell": "barbell",
    "e-z curl bar": "barbell",
    "dumbbell": "dumbbell",
    "machine": "machine",
    "cable": "cable",
    "body only": "bodyweight",
}  # anything else, including null: other

# free-exercise-db ids that are the same exercise as a core entry under another
# name. Only obvious pairs: the plain version of the core exercise, not variants.
ALIASES = {
    "Ab_Roller": "ab_wheel_rollout",
    "Arnold_Dumbbell_Press": "arnold_press",
    "Barbell_Bench_Press_-_Medium_Grip": "bench_press",
    "Barbell_Deadlift": "deadlift",
    "Barbell_Hip_Thrust": "hip_thrust",
    "Barbell_Incline_Bench_Press_-_Medium_Grip": "incline_bench_press",
    "Barbell_Squat": "back_squat",
    "Bench_Dips": "bench_dip",
    "Bent_Over_Barbell_Row": "barbell_row",
    "Close-Grip_Barbell_Bench_Press": "close_grip_bench_press",
    "Concentration_Curls": "concentration_curl",
    "Dips_-_Chest_Version": "dips",
    "Dumbbell_Bicep_Curl": "dumbbell_curl",
    "Dumbbell_Flyes": "dumbbell_fly",
    "Dumbbell_Lunges": "lunge",
    "Dumbbell_Shrug": "shrug",
    "Dumbbell_Step_Ups": "step_up",
    "EZ-Bar_Skullcrusher": "skull_crusher",
    "Flat_Bench_Cable_Flyes": "cable_fly",
    "Front_Barbell_Squat": "front_squat",
    "Front_Dumbbell_Raise": "front_raise",
    "Hammer_Curls": "hammer_curl",
    "Hyperextensions_Back_Extensions": "back_extension",
    "Leg_Extensions": "leg_extension",
    "Leverage_Chest_Press": "machine_chest_press",
    "Lying_Leg_Curls": "leg_curl",
    "Machine_Shoulder_Military_Press": "machine_shoulder_press",
    "One-Arm_Dumbbell_Row": "dumbbell_row",
    "One-Legged_Cable_Kickback": "cable_kickback",
    "Pullups": "pull_up",
    "Pushups": "push_up",
    "Reverse_Flyes": "reverse_fly",
    "Seated_Cable_Rows": "seated_cable_row",
    "Side_Bridge": "side_plank",
    "Side_Lateral_Raise": "lateral_raise",
    "Standing_Biceps_Cable_Curl": "cable_curl",
    "Standing_Calf_Raises": "standing_calf_raise",
    "Standing_Dumbbell_Triceps_Extension": "overhead_triceps_extension",
    "Standing_Military_Press": "overhead_press",
    "T-Bar_Row_with_Handle": "t_bar_row",
    "Thigh_Abductor": "machine_hip_abduction",
    "Tricep_Dumbbell_Kickback": "triceps_kickback",
    "Wide-Grip_Lat_Pulldown": "lat_pulldown",
}


def normalize(name):
    """Lowercase, hyphens to spaces, runs of whitespace to one space."""
    return " ".join(name.lower().replace("-", " ").split())


def load_source(path):
    if path:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    with urllib.request.urlopen(SOURCE_URL, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--input", help="local copy of exercises.json instead of downloading")
    args = parser.parse_args()

    core = json.loads(CORE.read_text(encoding="utf-8"))
    core_keys = {e["key"] for e in core}
    core_names = {normalize(e["name"]["en"]): e["key"] for e in core}
    unknown = sorted(set(ALIASES.values()) - core_keys)
    if unknown:
        sys.exit(f"aliases point at keys not in core.json: {unknown}")

    source = load_source(args.input)
    strength = [x for x in source if x.get("category") in CATEGORIES]

    dropped = []
    entries = []
    for x in strength:
        fed_id = x["id"]
        name = " ".join(x["name"].split())
        duplicate_of = core_names.get(normalize(name)) or ALIASES.get(fed_id)
        if duplicate_of:
            dropped.append((fed_id, duplicate_of))
            continue
        primary = x.get("primaryMuscles") or []
        entries.append({
            "id": fed_id,
            "name": {"en": name},
            "muscleGroup": MUSCLE.get(primary[0], "fullbody") if primary else "fullbody",
            "equipment": EQUIPMENT.get(x.get("equipment"), "other"),
            "instructions": [s.strip() for s in (x.get("instructions") or []) if s.strip()],
            "source": SOURCE,
        })

    ids = [e["id"] for e in entries]
    if len(ids) != len(set(ids)):
        sys.exit("duplicate ids in the source")
    entries.sort(key=lambda e: e["id"])

    missing_aliases = sorted(set(ALIASES) - {x["id"] for x in strength})
    for fed_id in missing_aliases:
        print(f"warning: alias {fed_id} is not in the source's strength categories", file=sys.stderr)

    OUTPUT.write_bytes((json.dumps(entries, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))

    print(f"source: {len(source)} exercises, {len(strength)} in {sorted(CATEGORIES)}")
    print(f"dropped as duplicates of core: {len(dropped)}")
    for fed_id, key in sorted(dropped):
        print(f"  {fed_id} -> {key}")
    print(f"wrote {OUTPUT.relative_to(ROOT)}: {len(entries)} exercises")
    for field in ("muscleGroup", "equipment"):
        counts = collections.Counter(e[field] for e in entries)
        print(f"by {field}: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))))


if __name__ == "__main__":
    main()
