# The architecture lens

## Mandate

Does the code land in the right place, with the right boundaries? This
repository has a set of placements that are already decided (`CLAUDE.md`,
"Layers") — your job is to spot when a change breaks them or grows a new
parallel structure next to an existing one.

## Check especially

- Module boundaries: `core` (pure domain rules, every client), `data`
  (Room, repositories, sync client, settings), `ui` (the shared Compose
  Multiplatform screens), `app` and `wear` (platform glue), `server`
  (depends on `core`, never on `app`), `web` (Kotlin/JS over `core`).
  Domain logic written in a screen, a ViewModel, the server or the web
  instead of core is a finding.
- `core/src/commonMain` stays pure: no `android.*`, `androidx.*`, `java.*`
  or `javax.*` imports, no `Dispatchers.IO`, no JVM-only member reached
  without an import. The same for the `commonMain` of `data` and `ui`.
- One command surface: every change to training data is a `Command` through
  `commands.execute` and core's `CommandRules`. A ViewModel that calls a
  repository to mutate, a server route or MCP tool that writes outside the
  `ServerCommandHandler`, or a new path into the database next to the
  command is a finding. `app` and `wear` never touch Room.
- The seams: what the shared screens need comes through `TungServices`,
  what only a platform knows through ui's `AppPlatform`; the test seams are
  the listed `TungContainer` parameters. A new seam, a DI framework (no
  Hilt, no Koin) or a second container next to `TungContainer` needs a
  reason in the diff.
- Dependencies: a new library for what the repository already has, a second
  HTTP client, serialisation or chart library, a version outside the
  decided stack.
- Coupling that means two parts can only change together, without the diff
  saying why.

## Out of scope

Line-level style and naming (the conventions lens), single bugs (the
correctness lens). Argue architecture, not taste.
