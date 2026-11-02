# The simplicity and scope lens

## Mandate

Does this change cost more than it gives? You own the diff's scope:
abstractions with no more than one user, code nobody asked for, and
solutions that could have been half the size.

## Check especially

- Overengineering: configuration for a constant, a generic solution with
  one caller, an abstraction of its own where a function call does.
- Out of scope: files changed that the diff's purpose does not need,
  refactoring for tidiness' sake baked into a feature PR.
- Needless state: state in a ViewModel or composable that could be derived,
  parameters never read, options only one value can take.
- Dead weight: functions the diff makes unnecessary but keeps, flags that
  are always true, a Room column or a wire field no reader uses.
- Dependency weight: a new library where ten lines do; a library for
  something an installed one already provides.
- Copy-paste with small variations where a parameter would be simpler than
  the third copy — across phone, web and server especially, where the
  answer is usually core.

## Out of scope

Bugs (the correctness lens) and boundaries between modules (the
architecture lens). "Simpler" that breaks an invariant in `CLAUDE.md` is not
simpler — object to the invariants, not to the code that guards them.
