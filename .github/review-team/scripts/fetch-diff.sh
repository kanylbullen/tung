#!/usr/bin/env bash
set -euo pipefail

# Fetches the PR diff through the API — gh pr diff, no git history needed —
# and guards its size. A diff over the limit is not an error but a decision
# point: the fleet bows out with a comment instead of sliding into half a
# review of a thousand lines.
#
# The decision carries the freshness marker — it is a deterministic answer
# for exactly this head, a rerun cannot land anywhere else, so the gate may
# well block it — and is posted with --edit-last, so a large PR gets ONE
# comment that is updated, not a new copy per push.
#
# Expects in the environment: REPO, FLEET_TMP, MAX_DIFF_BYTES, PR_NUMBER, SHA,
# GH_TOKEN. Outputs: proceed.

PR="${PR_NUMBER:?}"
SHA="${SHA:?}"
DIFF="$FLEET_TMP/pr.diff"

gh pr diff "$PR" -R "$REPO" > "$DIFF"

SIZE="$(wc -c < "$DIFF" | tr -d ' ')"

if [ "$SIZE" -eq 0 ]; then
  echo "::notice::PR #$PR has an empty diff — nothing to review."
  echo "skip" > "$FLEET_TMP/verdict"
  echo "proceed=false" >> "$GITHUB_OUTPUT"
  exit 0
fi

if [ "$SIZE" -gt "$MAX_DIFF_BYTES" ]; then
  {
    echo "<!-- review-fleet:v1 sha=$SHA ts=$(date +%s) pr=$PR -->"
    echo "## ⚠️ The fleet skipped this PR"
    echo ""
    echo "The diff is $SIZE bytes and the limit $MAX_DIFF_BYTES — too large for a"
    echo "full fleet run. Split the PR, or run single lenses from"
    echo '`.github/review-team/reviewers/` against parts of the change.'
  } > "$FLEET_TMP/too-large.md"
  gh pr comment "$PR" -R "$REPO" --body-file "$FLEET_TMP/too-large.md" \
    --edit-last --create-if-none
  echo "::notice::The diff is $SIZE bytes — over the limit $MAX_DIFF_BYTES."
  echo "skip" > "$FLEET_TMP/verdict"
  echo "proceed=false" >> "$GITHUB_OUTPUT"
  exit 0
fi

echo "proceed=true" >> "$GITHUB_OUTPUT"
