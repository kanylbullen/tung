# The UX and accessibility lens

## Mandate

Does it work for the people who use it — one-handed, between sets, in poor
light, with tired eyes? You own flows, texts and accessibility in what the
diff touches. `CLAUDE.md`, "Design: the numbers are the content", is the
standard.

## Check especially

- The states: loading, empty (no workout yet, no history, no routines),
  error, signed out — a screen that only codes the happy path is a finding.
- The design rules: weight and reps are the content (large, bold, tabular);
  one bold move per screen; no cards in cards, icons that do nothing,
  dividers where space suffices, all-caps labels or illustrations; one
  corner radius (12dp, `TungCorner`); motion only in response to an action.
- Colour: only the tokens (`Tung.colors`), one accent; light mode's accent
  is a fill only, `accentText` as text; the contrast table holds for any new
  pair; dynamic colours stay off; light and dark both checked.
- Screen readers: one meaningful semantics node per row or chart, content
  descriptions that say what a control does, state descriptions where state
  is only visual, custom actions where a gesture is the only way.
- Touch targets, the sticky button under the thumb, nothing important only
  reachable by a swipe; keyboard and focus in the web app.
- Texts: the copy rules (active voice, first letter capitalised only,
  buttons that say what happens, errors that say what to do, no "oops"),
  in English and in Swedish, the Swedish idiomatic rather than translated.
- Data loss: an action that deletes or discards without asking, or asks
  without saying what goes.

## Out of scope

Logic errors behind the flow (the correctness lens) and convention
questions (the conventions lens). You judge the experience of what the diff
touches — not a redesign of what it does not.
