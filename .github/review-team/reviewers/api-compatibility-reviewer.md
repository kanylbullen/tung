# The API and compatibility lens

## Mandate

Does the diff break a contract with the world outside the git diff? Phones
and watches in the field are upgraded in place and run older versions for
months, tung-server is self-hosted at versions nobody controls, and REST,
MCP and saved prompts call the commands by name — contracts where a change
can pass every compile and still break in production.

## Check especially

- Wire names are permanent: a name in `CommandNames`, a `CommandIds` key, a
  catalogue `key` in `core.json`, a sync table key and a JSON field on the
  wire never change once they exist. Add a new one instead of renaming.
- Sync fields are additive and nullable: a new field older clients do not
  send must not erase a stored value (the null-keeps rule on both sides,
  app `SyncMapping` and server `keepWhenNull`); a required field older
  clients lack is a 4xx for every one of them.
- Room schema changes: `TungDatabase` version + 1, the committed schema JSON
  under `data/schemas/`, a `MigrationStep` in `DatabaseSchema.MIGRATIONS`
  and a `MigrationTest` case — all four, or every installed phone crashes on
  open. A change that needs a wipe is a bug.
- Server migrations: a new Flyway file, never an edited old one; columns
  added nullable; enums the server validates against (`Equipment`,
  `StartedBy`, …) extended before a client sends the new value — say when
  the server must be deployed first.
- The REST surface: every route and DTO change reflected in
  `openapi.yaml` (`OpenApiCoverageTest`), errors through `ApiException`
  with a stable `code`, status codes clients already branch on.
- Phone ↔ watch: `WatchProtocol` fields additive both ways (an older watch
  ignores, an older phone leaves out); the web's CORS headers for anything
  the browser sends.
- External formats: the import adapters' inputs (Jefit's layout), the AI
  providers' request and response shapes, Health Connect record types.

## Out of scope

In-app correctness (the correctness lens) and access control (the security
lens). You own the contracts across process, device and version boundaries.
