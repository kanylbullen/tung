# The task — shared by every lens

This is the fleet's shared task template. The scripts in `scripts/` fill in
`@NAME@`, `@PR@`, `@SHA@`, `@DIFF@`, `@REPORT@` and `@WORKTREE@` and paste it
after each lens's own role file.

## Task

You are reviewing PR #@PR@ in kanylbullen/tung (head @SHA@). The working copy
you are in — `@WORKTREE@` — is on the PR's merge state. Start by reading the
whole diff: `@DIFF@`. Then read the files concerned in the working copy, and
feel free to run read-only commands (`git log`, `git blame`, `grep`), until
your findings stand on firm ground. `CLAUDE.md` at the root is the
repository's manual: its rules and traps are the conventions you review
against.

You must NOT change any file in the repository. The only file you write is
your report. Report only what belongs to your mandate — your reviewer role
defines it, and you cannot see the other lenses.

## Report

Write the report to the file `@REPORT@` (create it with the file tool; if it
already exists, overwrite it). Exact format — one bullet per finding,
nothing else in the file:

```
# @NAME@

- **SEVERITY** `relative/path:line` — what is wrong and why it matters: a
  concrete failure mode (bug, damage, security hole or measurable
  maintenance cost). **Suggestion:** the concrete fix.
```

- `SEVERITY` is one of `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`.
- No introduction, no summary, no praise. The bullet must be countable and
  aggregatable by machine — keep the format character for character.
- **Length budget: a bullet is at most three short sentences** — one
  sentence saying what the fault is, the concrete failure mode, a
  suggestion — plus the `path:line` reference. Not the whole reasoning: the
  aggregator should be able to quote the bullet as it stands, and the
  report should fit in a single write call.
- A finding you are unsure of goes in only if the failure mode is concrete;
  state the uncertainty inside the bullet.
- If you find nothing within your mandate: write the file with exactly

```
# @NAME@

NO FINDINGS
```

Write in English.
