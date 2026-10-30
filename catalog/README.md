# The exercise catalogue

Tung's exercise catalogue as open data. The app reads it, and a future server
and other clients can read the same files.

| File | Contents |
|---|---|
| `core.json` | The core: the exercises the app seeds, with names in English and Swedish, and for most of them instructions in English. The source of truth for `SeedExercises.kt` and the `ex_*` strings in the app. |
| `extended.json` | The extended catalogue: just over 600 strength exercises in English, imported from [free-exercise-db](https://github.com/yuhonas/free-exercise-db). Not seeded: the Exercises tab lists it and the exercise picker searches it; an entry gets a database row when it is first added. |
| `tools/gen_android.py` | Generates the Android sources from `core.json`. |
| `tools/import_free_exercise_db.py` | Builds `extended.json` from free-exercise-db, and writes the core entries' `instructions` into `core.json`. |

## Format

`core.json` is a list sorted by `key`, one entry per line:

```json
{"key": "bench_press", "name": {"sv": "Bänkpress", "en": "Bench press"}, "muscleGroup": "chest", "equipment": "barbell", "primaryMuscles": ["chest"], "secondaryMuscles": ["triceps", "front_delts"], "instructions": {"en": ["Lie back on a flat bench. …", "…"]}}
```

- `key`: stable key, lowercase letters and underscores. Never changes once it
  exists: it carries the exercise's id (`catalogExerciseId(key)`) on every device.
- `name`: one name per language, keyed by language tag. `en` is required:
  English is the app's default locale and the fallback name. `sv` is the
  translation the app ships today and is kept complete. English names are gym
  English (Lat pulldown, Skull crusher), not literal translations.
- `muscleGroup`: `chest`, `back`, `shoulders`, `biceps`, `triceps`, `legs`,
  `glutes`, `core` or `fullbody`.
- `equipment`: `barbell`, `ez_bar`, `dumbbell`, `kettlebell`, `machine`,
  `cable`, `band`, `bodyweight`, `medicine_ball`, `exercise_ball` or `other`
  (core's `Equipment`; `other` only when none of the rest fits, e.g. the ab
  wheel). A key never changes once it exists; a reader that does not know a
  key treats it as `other`.
- `primaryMuscles`: 1–3 muscles the exercise is for, most important first;
  `secondaryMuscles`: 0–4 that work along. No muscle in both lists. Values
  from the fixed list below.
- `instructions` (optional): the steps, in order, per language tag. Only
  `en` exists today; the app falls back to English for any other language.
  Written by `tools/import_free_exercise_db.py` from the entry's
  free-exercise-db twin (see "The extended catalogue"), so do not edit them
  by hand: the next import overwrites them. An entry without a twin has none
  (today `bulgarian_split_squat`, `burpee`, `glute_bridge`,
  `kettlebell_swing`, `side_plank` and `thruster`; `side_plank`'s twin has
  no steps). `gen_android.py` ignores the field.

### Muscles

A fixed list (`Muscle` in `core/`), each in one muscle group:

| Group | Muscles |
|---|---|
| `chest` | `chest` |
| `back` | `lats`, `upper_back`, `lower_back`, `traps`, `neck` |
| `shoulders` | `front_delts`, `side_delts`, `rear_delts` |
| `biceps` | `biceps`, `forearms` |
| `triceps` | `triceps` |
| `legs` | `quads`, `hamstrings`, `calves`, `adductors`, `abductors` |
| `glutes` | `glutes` |
| `core` | `abs`, `obliques`, `hip_flexors` |
| `fullbody` | none of its own |

`muscleGroup` stays the display level (the picker's and the tab's
headings) and follows the muscles: **it is the group of the first primary
muscle** (`MuscleGroup.of(primaryMuscles[0])`), except `fullbody`, which a
whole-body lift (burpee, snatch, power clean) may keep whatever its first
muscle. `CatalogTest` checks the rule for both files. Order the primary
muscles so the first one names the group you want; an entry that cannot is
in the wrong group. A key in this list never changes once it exists.

## Adding an exercise to the core

1. Add the entry to `core.json`, in its place in alphabetical order by `key`.
2. Run `python3 -I catalog/tools/gen_android.py` from the repository root.
3. Five files are updated: `data/src/commonMain/kotlin/io/github/kanylbullen/tung/data/SeedExercises.kt`,
   and `src/main/res/values/strings.xml` (English) and
   `src/main/res/values-sv/strings.xml` (Swedish) of both `app/` and `wear/`; in the string files only
   the lines between `<!-- catalog:begin -->` and `<!-- catalog:end -->`.
   Commit all four.

`SeedExercises.kt` stores the English name as the fallback that the app shows
when no `ex_<key>` resource matches.

Never edit the generated files by hand. `gen_android.py --check` writes nothing
and exits with 1 if anything is out of date.

Seeding happens when the database is created. A new core exercise therefore
does not reach an already installed app until there is a migration that adds it.

## Catalogue names in a new language

The generator writes the `ex_*` block into every `values-<lang>/strings.xml`
that contains the catalog markers, using `name.<lang>` from `core.json`, where
`<lang>` is the resource directory's qualifier. Entries without that name are
left out of the file, so Android falls back to English for them. A new app
language that copied `values/strings.xml` therefore gets an empty block until
`core.json` has names in that language; add them and run the generator.

## The extended catalogue

`extended.json` is generated by `tools/import_free_exercise_db.py`, which
fetches `dist/exercises.json` from free-exercise-db (branch `main`) and:

- keeps the categories `strength`, `powerlifting`, `olympic weightlifting`
  and `strongman`;
- maps `primaryMuscles[0]` and `equipment` to our values (the tables are at the
  top of the script; anything unknown becomes `fullbody` and `other`
  respectively). Equipment:

  | free-exercise-db | Tung |
  |---|---|
  | `barbell` | `barbell` |
  | `e-z curl bar` | `ez_bar` |
  | `dumbbell` | `dumbbell` |
  | `kettlebells` | `kettlebell` |
  | `machine` | `machine` |
  | `cable` | `cable` |
  | `bands` | `band` |
  | `body only` | `bodyweight` |
  | `medicine ball` | `medicine_ball` |
  | `exercise ball` | `exercise_ball` |
  | `foam roll`, `other`, none | `other` |

- keeps the source's `primaryMuscles` and `secondaryMuscles`, mapped onto the
  fixed list (`abdominals` → `abs`, `lower back` → `lower_back`, `middle back`
  → `upper_back`, `quadriceps` → `quads`, the rest by the same name), with a
  muscle that is primary dropped from the secondary list. The source has a
  single `shoulders` and does not say which head, so **every source
  `shoulders` becomes `side_delts`**: `front_delts` and `rear_delts` only come
  from `core.json`. A source muscle missing from the table stops the import;
- drops exercises that are already in the core: the same normalised English
  name (lowercase, hyphens to spaces, spaces collapsed) or a pair in the
  script's hand-written alias list (`Barbell Squat` ↔ `back_squat` and so on);
- copies each dropped twin's `instructions` into its core entry in
  `core.json` as `instructions.en` (a twin found by name wins over an alias,
  then the first by id), rewriting the file sorted by `key`, one entry per
  line, every other field as it was. Run `gen_android.py --check` after it:
  nothing it generates depends on the steps;
- leaves out the images, since their origin is not documented.

An entry:

```json
{"id": "Zercher_Squats", "name": {"en": "Zercher Squats"}, "muscleGroup": "legs", "primaryMuscles": ["quads"], "secondaryMuscles": ["calves", "glutes", "hamstrings"], "equipment": "barbell", "instructions": ["…"], "source": "free-exercise-db"}
```

`id` is free-exercise-db's id. The app gives a chosen exercise the id
`catalogExerciseId("ext:" + id)`, so the same exercise gets the same id on every
device. Do not edit `extended.json` by hand; run the script again
(`python3 -I catalog/tools/import_free_exercise_db.py`, or `--input file`
for a local copy). The output is deterministic for the same input, but follows
`main` upstream.

## Import aliases

`aliases/<source>.json` maps another app's exercise names to the catalogue,
for import from that app (CLAUDE.md, "Import"):

```json
{ "source": "jefit", "aliases": { "Barbell Squat": "back_squat", "Dumbbell Squat": "ext:Dumbbell_Squat" } }
```

A target is a core `key`, or an extended-catalogue `id` written `ext:<id>`.
Names are compared like the command surface compares names (case,
diacritics and punctuation do not matter) and kept sorted. Add a name only
for an exact equivalent with the same kind of load; anything else goes to
the import's review, where the person decides. `ImportResolutionTest` (in
`core`) fails on a target that does not exist.

## Licence

The catalogue (the files in this directory) is dedicated to the public domain
under **CC0 1.0 Universal**, see `LICENSE`. It may be used, changed and shared
without conditions and without mentioning us. The app's code is
AGPL-3.0-or-later, see the repository's `LICENSE`.

`extended.json` derives from free-exercise-db (GitHub user yuhonas), which is
released under [the Unlicense](https://github.com/yuhonas/free-exercise-db/blob/main/LICENSE.md)
(public domain). Our processing is placed under CC0 like the rest of the catalogue.
