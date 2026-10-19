# Övningskatalogen

Tungs övningskatalog som öppen data. Appen läser den, och en framtida server
och andra klienter kan läsa samma filer.

| Fil | Innehåll |
|---|---|
| `core.json` | Kärnan: de övningar appen seedar och visar i fliken Övningar, med namn på svenska och engelska. Sanningen för `SeedExercises.kt` och `ex_*`-strängarna i appen. |
| `extended.json` | Den utökade katalogen: drygt 600 styrkeövningar på engelska, importerade ur [free-exercise-db](https://github.com/yuhonas/free-exercise-db). Nås bara via sök i övningsväljaren. |
| `tools/gen_android.py` | Genererar Android-källorna ur `core.json`. |
| `tools/import_free_exercise_db.py` | Bygger `extended.json` ur free-exercise-db. |

## Format

`core.json` är en lista sorterad på `key`, en post per rad:

```json
{"key": "bench_press", "name": {"sv": "Bänkpress", "en": "Bench press"}, "muscleGroup": "chest", "equipment": "barbell"}
```

- `key`: stabil nyckel, gemener och understreck. Ändras aldrig när den väl
  finns: den bär övningens id (`catalogExerciseId(key)`) på varje enhet.
- `name`: `sv` och `en`, båda obligatoriska. Engelska namn är gym-engelska
  (Lat pulldown, Skull crusher), inte ordagranna översättningar.
- `muscleGroup`: `chest`, `back`, `shoulders`, `biceps`, `triceps`, `legs`,
  `glutes`, `core` eller `fullbody`.
- `equipment`: `barbell`, `dumbbell`, `machine`, `cable`, `bodyweight` eller
  `other`.

## Lägga till en övning i kärnan

1. Lägg till posten i `core.json`, på sin plats i bokstavsordning efter `key`.
2. Kör `python3 -I catalog/tools/gen_android.py` från repots rot.
3. Tre filer uppdateras: `app/src/main/kotlin/io/github/kanylbullen/tung/data/SeedExercises.kt`,
   `app/src/main/res/values/strings.xml` och `app/src/main/res/values-en/strings.xml`
   (i strängfilerna bara raderna mellan `<!-- catalog:begin -->` och
   `<!-- catalog:end -->`). Committa alla fyra.

Redigera aldrig de genererade filerna för hand. `gen_android.py --check`
skriver ingenting och avslutar med 1 om något är inaktuellt.

Seedningen sker när databasen skapas. En ny övning i kärnan når alltså inte en
redan installerad app förrän det finns en migrering som lägger till den.

## Den utökade katalogen

`extended.json` genereras av `tools/import_free_exercise_db.py`, som hämtar
`dist/exercises.json` från free-exercise-db (grenen `main`) och:

- behåller kategorierna `strength`, `powerlifting`, `olympic weightlifting`
  och `strongman`;
- mappar `primaryMuscles[0]` och `equipment` till våra värden (tabellerna står
  överst i skriptet; okänt blir `fullbody` respektive `other`);
- tar bort övningar som redan finns i kärnan: samma normaliserade engelska namn
  (gemener, bindestreck till mellanslag, mellanslag hopslagna) eller ett par i
  skriptets handskrivna aliaslista (`Barbell Squat` ↔ `back_squat` osv.);
- tar inte med bilderna, eftersom deras ursprung inte är dokumenterat.

En post:

```json
{"id": "Zercher_Squats", "name": {"en": "Zercher Squats"}, "muscleGroup": "legs", "equipment": "barbell", "instructions": ["…"], "source": "free-exercise-db"}
```

`id` är free-exercise-dbs id. Appen ger en vald övning id:t
`catalogExerciseId("ext:" + id)`, så samma övning får samma id på alla enheter.
Ändra inte `extended.json` för hand; kör skriptet igen
(`python3 -I catalog/tools/import_free_exercise_db.py`, eller `--input fil`
för en lokal kopia). Utdata är deterministisk för samma indata, men följer
`main` uppströms.

## Licens

Katalogen (filerna i den här katalogen) är tillägnad allmän egendom under
**CC0 1.0 Universal**, se `LICENSE`. Den får användas, ändras och spridas utan
villkor och utan att nämna oss. Appens kod är AGPL-3.0-or-later, se repots
`LICENSE`.

`extended.json` härstammar från free-exercise-db (GitHub-användaren yuhonas), som är släppt
under [the Unlicense](https://github.com/yuhonas/free-exercise-db/blob/main/LICENSE.md)
(allmän egendom). Vår bearbetning läggs under CC0 som resten av katalogen.
