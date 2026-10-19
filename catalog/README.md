# Övningskatalogen

Tungs övningskatalog som öppen data. Appen läser den, och en framtida server
och andra klienter kan läsa samma filer.

| Fil | Innehåll |
|---|---|
| `core.json` | Kärnan: de övningar appen seedar och visar i fliken Övningar, med namn på svenska och engelska. Sanningen för `SeedExercises.kt` och `ex_*`-strängarna i appen. |
| `tools/gen_android.py` | Genererar Android-källorna ur `core.json`. |

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

## Licens

Katalogen (filerna i den här katalogen) är tillägnad allmän egendom under
**CC0 1.0 Universal**, se `LICENSE`. Den får användas, ändras och spridas utan
villkor och utan att nämna oss. Appens kod är AGPL-3.0-or-later, se repots
`LICENSE`.
