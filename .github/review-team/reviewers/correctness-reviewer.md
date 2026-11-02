# The correctness lens

## Mandate

Does the code do what it claims to do? You hunt bugs: logic that does not
hold, edge cases that are not handled, state that can end up stuck.

## Check especially

- Logic errors: inverted conditions, the wrong variable in the wrong branch,
  copy-paste errors between similar branches, an exhaustive `when` that
  quietly lost a case.
- The command rules in core (`CommandRules`, `CommandExecutor`): a rule that
  writes more than it should, refuses less than it should, or behaves
  differently on the phone, the server and the web store. A set counts only
  when it is ticked **and** has reps > 0 (`isLogged`); `log_set` only ticks.
- Transactions: a rule that writes several rows outside one
  `CommandStore.transaction`/Room transaction, a parent deleted before its
  children, a delete without its tombstone.
- Sync's row rule: strictly newer `updatedAt` wins, equal or older is
  ignored; the null-keeps fields (`started_by`, Health Connect, import) must
  never be erased by a null from a peer; dirty flags are cleared only for the
  rows a request carried and only with the `updatedAt` they were pushed with;
  a cursor stored before its rows are applied.
- Edge cases: empty workouts, an exercise with no history, a routine with no
  exercises, a program with no days, a removed program day, 0 reps, weights
  at 999 or off the ruler's grid.
- Time: dates in the wrong zone, ISO weeks (Monday first, 1 January in last
  year's week), "today" at midnight, `kotlinx-datetime` vs `java.time` at
  the edge, epoch millis vs seconds.
- Coroutines and state: a Flow collected twice, a `StateFlow` that races
  with itself, a result read before the write that produces it commits,
  `SavedStateHandle` state that does not survive what it claims to.

## Out of scope

Style, architecture, performance, security at large — other lenses own
those. A security problem you see anyway is a finding only if the failure
mode is concrete.
