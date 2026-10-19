# Järn — agentmanual

Styrketräningslogg för Android. Lokal app: ingen backend, ingen inloggning,
inget nätverk. Allt ligger i Room på telefonen.

## Stack (beslutad)

Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Material 3, Room 2.6.1 via
KSP, Navigation Compose 2.8.5, ViewModel + StateFlow, coroutines. AGP 8.7.3,
Gradle 8.11.1 (wrapper). compileSdk/targetSdk 35, minSdk 26.

**Ingen Hilt/Koin.** `AppContainer` i `JarnApp.kt` håller databasen och
repositoryn lazy. ViewModels skapas med `viewModel(factory = factoryOf { ... })`
(`ui/common/Containers.kt`) och får repository och nav-argument i konstruktorn.

## Lager

| Katalog | Innehåll |
|---|---|
| `domain/` | Rena funktioner utan Android: volym, bästa set, rekord, förifyllning, vikt-parsing/-formatering, diakritikokänslig sökning, `MuscleGroup`/`Equipment`. Allt som går att testa på JVM bor här. |
| `data/` | Entities, `ExerciseDao`, `WorkoutDao`, `JarnDatabase` (seedar ~70 övningar i `onCreate`), `ExerciseRepository`, `WorkoutRepository`. |
| `ui/<skärm>/` | `Screen` + `ViewModel` + `UiState` per skärm: `home`, `workout` (pågående pass + inmatningsarket), `exercises` (flik och väljare i ett), `history` (lista + detalj), `summary`. |
| `ui/common/` | Formatering, delade komponenter, `WorkoutReport`. |
| `ui/theme/` | Färgtokens, typografi, tema. |

Appens viktigaste query är `WorkoutDao.previousSessionSets` — set för en övning
från senaste avslutade pass som innehåller den. Den driver både "Förra: 80 × 8"
och förifyllningen av nya set.

Ett set räknas (`domain.isLogged`) när det är bockat **och** har reps > 0.
"Avsluta pass" raderar allt annat, sedan övningar utan set, och ett pass utan
ett enda räknat set raderas helt i stället för att sparas tomt.

## Bygga

```sh
export JAVA_HOME=/home/xup/android/jdk ANDROID_HOME=/home/xup/android/sdk ANDROID_SDK_ROOT=/home/xup/android/sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug testDebugUnitTest --no-daemon
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Första bygget laddar ner
beroenden och tar några minuter.

## Design: siffrorna är innehållet

Appen används med en hand, mellan set, i dålig belysning, med trötta ögon.
Vikt och reps är det enda som spelar roll; allt annat är kuliss.

- Set-rader: siffror ~28sp, fet, tabulära (`tnum`). Inmatningsarket: 96sp.
- Ett djärvt grepp: inmatningsarket (enormt tal, tumstora −/+, "Klar").
  Resten är lugnt — inga kort-i-kort, inga ikoner som inte gör något, inga
  avdelare där luft räcker, inga versala etiketter, inga illustrationer.
- En hörnradie: 12dp (`JarnCorner`) på knappar, ark och dialoger. 0 på listrader.
- Rörelse bara som svar på handling (bocken fylls, arket glider upp).
- Skärmen hålls tänd på Pågående pass (`view.keepScreenOn`).
- Mörkt/ljust följer systemet. Dynamiska färger är **av**.
- Typsnitt: Archivo (variabel, OFL) — en familj.

| Token | Mörkt | Ljust | Används till |
|---|---|---|---|
| background | `#1A1D21` | `#F6F6F4` | Bakgrund, listrader |
| raised | `#23272D` | `#FFFFFF` | Ark, dialoger, bottenbar, sekundärknapp |
| border | `#2F343B` | `#E2E1DC` | Kantlinjer, swipe-bakgrund |
| text | `#ECE7DD` (krita) | `#1A1D21` | Primärtext |
| muted | `#9AA0A6` | `#5F6368` | Dämpad text, "Förra:" |
| accent | `#F2C230` | `#B98900` | Fyllnad: primärknapp, bockat set |
| accentText | `#F2C230` | `#8A6500` | Accent som text: "Nytt rekord", "Avsluta pass" |

Kontrast (WCAG): krita/grafit 13.8:1, gult/grafit 10.1:1, dämpat/grafit 6.4:1,
dämpat/raised 5.7:1. I ljust läge klarar `#B98900` bara 3.2:1 mot vitt, så den
används enbart som fyllnad (mörk text på den: 5.4:1); som text används
`#8A6500` (4.9:1 mot `#F6F6F4`).

Kopia: svenska, aktiv form, bara första bokstaven versal. Knappar säger vad som
händer ("Starta pass", "Lägg till övning", "Spara"). Fel säger vad som hände
och vad man gör, utan "oj".

## Fällor

- **IPv6/Maven:** Gradle når inte Maven över IPv6 här.
  `gradle.properties` har `-Djava.net.preferIPv4Stack=true` både i
  `org.gradle.jvmargs` och som `systemProp`. Ta inte bort dem.
- **`local.properties`** med `sdk.dir=/home/xup/android/sdk` måste finnas och
  gitignoreras. Saknas den hittar AGP ingen SDK.
- **En gradle-process åt gången.** Maskinen delas; kör aldrig parallella byggen
  och använd `--no-daemon` så att ingen daemon ligger kvar. `/tmp` är tmpfs —
  lägg inget stort där.
- **Wrappern är 8.11.1** och redan nedladdad. Kör inte `gradle wrapper` med en
  annan version.
- **Strängar bara i resurser.** Inga hårdkodade strängar i composables; allt i
  `res/values/strings.xml` (svenska) och `res/values-en/strings.xml`
  (engelska). Undantag: övningsnamnen i seed-datan är data, inte UI.
- **`res/font/` tar bara typsnittsfiler.** Därför ligger OFL-licensen i
  `assets/licenses/`, inte bredvid `.ttf`-filen.
- Inga `!!`, ingen `GlobalScope`, inga blockerande DB-anrop på main. DAO:erna
  är `suspend` eller `Flow`.
- `exportSchema = false` och ingen migrering: ändras schemat måste `version`
  höjas och en `Migration` skrivas innan appen används på riktigt.
