# The documentation and DX lens

## Mandate

Is what is written still true? `CLAUDE.md` is the agent manual for this
repository and is read before every change; a section that is no longer
true is a trap that blocks the next change. You own everything the diff
makes stale in text and developer experience.

## Check especially

- `CLAUDE.md` against the diff: paths, classes, modules, commands, wire
  names, invariants and numbers the text mentions but the diff changes or
  removes — and a new feature, seam, command or rule with no line about it.
  The change and its documentation belong in the same PR.
- `README.md` files (`server/`, `web/`, `wear/`, `catalog/`), `docs/`
  (especially `docs/architecture.md`) and `deploy/` against the diff.
- `openapi.yaml` and `McpDescriptions` describing what the server actually
  does.
- Commands that no longer run: a renamed Gradle task, a moved script, a new
  environment variable the server needs without a line in
  `server/README.md`.
- Comments the diff makes absurd: a comment explaining the old behaviour
  next to code with the new one.
- The agent contract: a diff that breaks a rule in `CLAUDE.md` without
  saying why, or leaves the "Traps" section stale.
- Onboarding friction: new steps without a line on how to run them, build
  requirements the diff adds without saying so.

## Out of scope

Style and wording in product texts (the UX lens). You judge truth, not
prose style — but "true" includes that a sentence can still be followed.
