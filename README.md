# Tung

En logg för styrketräningspass för Android. Som Jefit, fast lugnare: starta ett
pass, lägg till övningar, slå in vikt och reps med en hand mellan seten, och se
vad du gjorde förra gången bredvid varje set. Allt ligger lokalt på telefonen —
inget konto, inget nätverk.

Kotlin, Jetpack Compose, Material 3 och Room. minSdk 26, targetSdk 35.

## Bygga

Ingen Android-toolchain ligger på PATH på devservern, så sätt miljön i samma
skal som du bygger i:

```sh
cd /home/xup/tung
export JAVA_HOME=/home/xup/android/jdk ANDROID_HOME=/home/xup/android/sdk ANDROID_SDK_ROOT=/home/xup/android/sdk
export PATH=$JAVA_HOME/bin:$PATH
echo "sdk.dir=/home/xup/android/sdk" > local.properties   # en gång, filen gitignoreras
./gradlew assembleDebug testDebugUnitTest --no-daemon
```

APK:n hamnar i `app/build/outputs/apk/debug/app-debug.apk`.
Enhetstesterna (rena JVM-tester av domänlogiken) rapporteras under
`app/build/reports/tests/testDebugUnitTest/`.

Typsnittet Archivo ligger i `app/src/main/res/font/` under SIL Open Font
License; licenstexten följer med i APK:n som `assets/licenses/archivo-OFL.txt`.

## Licens

Koden är fri programvara under **GNU AGPL-3.0-or-later**, se `LICENSE`.
Det betyder att den som kör en ändrad version som tjänst måste erbjuda
källkoden till användarna. Övningskatalogen (namn, muskelgrupper,
utrustning) är öppen data under CC0 och får användas utan villkor.
Din egen träningsdata är din: den lämnar aldrig enheten om du inte slår
på synk, och den går alltid att exportera.

Riktningen för projektet, med API, MCP-server, självhosting och klocka,
finns i `docs/architecture.md`.
