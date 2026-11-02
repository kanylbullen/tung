# The testing strategy lens

## Mandate

Is the change protected at the right level? A test that measures a stub
gives a green answer from the wrong file. You hunt tests that look like
protection but do not protect, and rules that changed with nothing to
guard them.

## Check especially

- A change to a command rule, sync, a migration, progress maths or the
  import without a test — `CommandRulesTest` (core), the data tests over
  Room, `MigrationTest`, the server's Testcontainers tests are where those
  rules are pinned.
- The right level: pure rules tested in core (`:core:jvmTest`), Room
  behaviour in `data`, the server's SQL against a real Postgres, screens in
  the flow tests (Robolectric, real Room in memory). A flow test for what a
  core test could pin, or a core test with a mocked store for what only SQL
  decides, is the wrong side of the line.
- Flow test traps from `CLAUDE.md`: waiting with `compose.waitUntil`
  instead of `FlowTest.waitUntil`; a DataStore value read once instead of
  `waitUntilStored`; assertions on pixels instead of semantics; text not
  taken from the string resources; a dialog with a text field without
  `usePlatformDefaultWidth = false`.
- Tests that are always green: no assertion, an assertion that cannot
  fail, a catch that swallows the failure, an async assertion that never
  gets to run.
- A flow test changed to match a UI change: a UI change that breaks a flow
  test is a UI bug until proven otherwise, and the commit should say the
  behaviour change was intended.
- Fixtures: a real export or personal data in the repository (never), test
  doubles outside `data-fixtures/` where they belong, copy-paste setup that
  should be a helper.
- What the diff removes: a test deleted because it was flaky, when it
  guarded a rule that still holds.

## Out of scope

Whether the diff should have tests at all is a trade-off you may have an
opinion on, but only with a concrete suggestion of which behaviour to guard
— not "everything needs tests".
