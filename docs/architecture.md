# Tung – arkitektur och riktning

*Beslutat 2026-10-19. Det här dokumentet beskriver vart appen är på väg;
`CLAUDE.md` beskriver hur den är byggd just nu.*

## Mål

1. **Öppen källkod.** Allt utom användarens egen träningsdata är öppet: app,
   server, MCP-server, övningskatalog, dokumentation.
2. **Öppet API.** Allt appen kan göra går att göra över HTTP, dokumenterat med
   en OpenAPI-specifikation. Inga privata endpoints som appen har men andra
   inte får.
3. **MCP-server.** En agent ska kunna logga ett set, läsa historik och svara på
   "vad körde jag på bänk förra gången" med samma rättigheter som användaren.
4. **Självhostbar.** En person med en VPS ska kunna köra hela tjänsten med en
   `docker compose up`. Vår hostade instans är bara en sådan installation.
5. **Offline först.** Gym har dålig täckning. Appen fungerar fullt ut utan
   server; servern är tillval och ger synk mellan enheter, API och MCP.
6. **Klockan loggar.** En Wear OS-app startar pass och tar emot set med vikt
   och reps från handleden, så att telefonen kan ligga i skåpet.

## Formen

```
┌──────────────┐ Data Layer ┌──────────────┐  synk (HTTPS, API-token)  ┌──────────────────────────┐
│ Wear OS-app  │ ◄────────► │ Android-app  │ ◄───────────────────────► │ tung-server (en binär)   │
│ egen lokal   │ (Bluetooth)│ Room, lokal  │                           │  REST + OpenAPI          │
│ logg         │            │ sanning      │                           │  MCP (streamable HTTP)   │
└──────────────┘            └──────────────┘                           │  auth, synk, export      │
                                                                       └────────────┬─────────────┘
                                agenter / andra klienter ─── REST eller MCP ────────┘
                                                                                    │
                                                                               Postgres
```

- **Appen är sanningen för sin egen enhet.** Den skriver alltid lokalt först
  och synkar när den kan. Ingen vy väntar på nätet.
- **Servern är ett tillval** som användaren pekar ut med en URL i
  inställningarna. Standard är ingen server alls. Självhost och vår instans
  är samma kod och samma inställning.
- **Klockan är en egen klient med samma modell.** Den har sin egen lilla
  logg, skapar set med egna UUID och `updatedAt`, och synkar mot telefonen
  över Wearable Data Layer när den är i närheten. Telefonen slår ihop med
  samma regler som den använder mot servern. Ett synkprotokoll, två
  transporter. Klockan fungerar alltså även när telefonen ligger i skåpet och
  behöver aldrig nå servern själv.
- **API:t är kontraktet.** Appen, MCP-servern och tredje part går genom samma
  REST-yta. MCP-verktygen är tunna omslag över API:t, inte en egen väg in i
  databasen.

## Beslut som är tagna

| Fråga | Beslut | Varför |
|---|---|---|
| App | Kotlin, Jetpack Compose, Room | Redan byggd; native ger bäst inmatning med en hand |
| Server | Kotlin, Ktor, Postgres, en Docker-image | Delar domänkod med appen i en `core`-modul (volym, rekord, förifyllning räknas lika på båda sidor). En binär är det enklaste att självhosta |
| MCP | Inbyggd i servern över streamable HTTP, officiella Kotlin-SDK:n | En container, en auth, en deploy. Registreras i MCP-registret som `io.github.kanylbullen/tung` när den är stabil |
| Klocka | Wear OS, Compose for Wear OS, egen modul `wear/` i samma repo; Data Layer mot telefonen, ingen egen serverkontakt | Samma språk och samma `core`-modul som appen. Burnwatch har redan en Wear-modul på den här maskinen att utgå från |
| Auth | E-post + lösenord i servern, API-tokens för klienter och agenter; OIDC som tillval senare | Självhostare ska inte behöva en identitetsleverantör. Tokens är hur MCP-klienter och skript kopplar in sig |
| Identiteter | UUID genererade på klienten; katalogövningar får deterministiska UUID ur `nameKey` | Två enheter kan skapa data offline utan kollision, och katalogen har samma id överallt |
| Synk | Per rad: `updatedAt`, senaste skrivning vinner, gravstenar för raderingar | Träningsdata ändras sällan samtidigt på två enheter; enkelhet slår CRDT här |
| Övningskatalog | Egen kuraterad kärna (70 övningar, sv + en) som öppen data under CC0; bredare engelsk katalog importerad ur free-exercise-db (Unlicense) nås via sök | Väljaren ska visa det man faktiskt gör, inte 900 övningar. wger valdes bort: CC-BY-SA smittar katalogen och har 3 av 932 övningar på svenska |
| Personlig data | Lämnar aldrig enheten utan att synk är påslagen. Export i JSON när som helst. Ingen analys eller telemetri i appen | Det är den enda delen som inte är öppen, och den ska vara användarens |

## Beslut som väntar

- **Licens.** AGPL-3.0 är förslaget: en självhostbar tjänst med API är exakt
  fallet AGPL är skrivet för, och målet är öppen källkod i OSI-mening (FSL,
  som ScreenTimeAgent använder, är det inte). Katalogdata under CC0 oavsett.
  Ägarens beslut; inget `LICENSE` ligger i repot förrän det är taget.
- **Serverns lagring för minsta självhost.** Postgres är standard. Om det visar
  sig att folk vill köra på en Raspberry Pi utan Postgres kan SQLite läggas
  till bakom samma repository-gränssnitt; det bestäms när någon frågar.
- **Webbklient.** Inte planerad. API:t gör det möjligt för någon annan.

## Ordning

1. **Nu:** UUID-nycklar och `updatedAt` i appen (pågår), licensbeslut,
   repot publikt.
2. **Katalog:** importskript från free-exercise-db till `catalog/`, kärnan
   bryts ut som öppen datafil som både app och server läser.
3. **`core`-modul:** domänlogiken flyttas ur `app/` till en Kotlin-modul utan
   Android-beroenden så att servern kan använda den.
4. **Server v0:** konto, API-token, OpenAPI, CRUD för pass och set, export.
   `docker compose` med Postgres. Ingen synk ännu; API:t först.
5. **Synk i appen:** server-URL i inställningar, push/pull av ändringar sedan
   senaste synk, gravstenar.
6. **MCP:** verktyg över API:t: `log_set`, `last_time`, `start_workout`,
   `finish_workout`, `history`. Registrering i MCP-registret.
7. **Klocka v0:** se pågående pass, nästa set med förra gången som förslag,
   vikt och reps med kronan eller ringen i stora steg, bekräfta med ett tryck,
   vilotimer med haptik. Starta pass från klockan med de senaste övningarna.
   Kräver att `core`-modulen (steg 3) finns, inte servern.
8. **Vår instans** på Coolify, samma image som självhostarna får.

Allt som rör pengar eller en hostad betaltjänst är medvetet utanför. Om det
kommer senare ligger det i vår instans, inte i koden alla får.
