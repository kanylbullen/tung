#!/usr/bin/env bash
set -euo pipefail

# Builds the final comment — marker, aggregated report (or raw reports, or an
# honest message that the fleet could not run) plus footnote — and posts it
# with gh pr comment --edit-last: a PR has ONE fleet comment that is updated
# in place on new pushes and reruns, never a new copy per event (needs
# gh >= 2.31 for --edit-last/--create-if-none). A run that did not stamp the
# marker (failed or partial) never replaces a comment that carries one.
#
# The marker is the freshness gate's key: sha + timestamp for exactly the
# head that was handled. It is written ONLY when a real review was produced
# AND every lens answered — a partial run (FAILED non-empty) does not stamp,
# because the banner at the same time says it is not a clean visit: locking
# reruns for 24 h then would gate the PR on half a review, against the
# invariant that a broken run can always be rerun without force. Nor does
# "the fleet could not run" carry the marker.
#
# The reports are untrusted content: agent output that in turn read
# untrusted PR data (which is why they already live one directory per lens).
# Before any of them gets a place in the comment the freshness marker
# pattern is sanitised away (sanitize below) — a marker planted in a report
# body must not be able to play the gate's key.
#
# The verdict file $FLEET_TMP/verdict drives the final reaction:
#   clean    = 👍  every lens answered and the finding count is zero
#   findings = 😕  findings were found
#   partial  =    some lenses did not answer (or the fleet list is missing)
#                 with no findings — a partial run is no verdict on the PR
#   dirty    = the fleet could not run — no new reaction, the job is red
#   kept     = a failed or partial run left the PR's finished review in place
#              (see below) — that review keeps its reaction, only 👀 goes
#   skip     = the fleet chose to skip — no new reaction
#
# Expects in the environment: REPO, FLEET_TMP, MODEL, PR_NUMBER, SHA, RUN_URL,
# GH_TOKEN. Outputs: findings, posted, mode (aggregate|raw|none), kept.

PR="${PR_NUMBER:?}"
NOW="$(date +%s)"
# The aggregator's final report lies OUTSIDE out/ — that directory holds only
# per-lens directories (out/<name>/report.md), so "is there a lens report?"
# is decided with a glob that never mixes in the aggregate.
AGG="$FLEET_TMP/aggregate.md"

# Reports are untrusted content (see the file header): neutralise the
# freshness marker pattern before it gets a place in the comment. The
# replacement must not contain the string "review-fleet:v1" — resolve-pr.sh
# searches for exactly that when it looks for the gate's key.
sanitize() {
  sed -e 's/review-fleet:v1/review-fleet [neutralised]/g' "$@"
}

FLEET="$(cat "$FLEET_TMP/fleet.txt" 2>/dev/null || echo 'unknown')"
FAILED="$(cat "$FLEET_TMP/failed.txt" 2>/dev/null || echo '')"
# Normalise whitespace: the writers use spaces, but a file with one name per
# line should not break the footnote into lines.
FLEET="${FLEET//$'\n'/ }"
FAILED="${FAILED//$'\n'/ }"
FAILED="${FAILED# }"

TOTAL="$(wc -w <<< "$FLEET" | tr -d ' ')"
MISSED="$(wc -w <<< "$FAILED" | tr -d ' ')"

HAS_REPORTS=0
if ls "$FLEET_TMP"/out/*/report.md >/dev/null 2>&1; then
  HAS_REPORTS=1
fi

{
  # The marker first — but only when a review was actually produced AND every
  # lens answered. A partial run does not stamp (see the file header):
  # resolve-pr.sh would otherwise honour a half-review stamp and block reruns
  # for a day, against the invariant that a broken run can always be rerun
  # without force.
  if [ "$HAS_REPORTS" -eq 1 ] && [ -z "$FAILED" ]; then
    echo "<!-- review-fleet:v1 sha=$SHA ts=$NOW pr=$PR -->"
  fi

  # A partial run is said in so many words, at the top and visibly — four of
  # five answering is not enough for a green visit.
  if [ "$HAS_REPORTS" -eq 1 ] && [ -n "$FAILED" ]; then
    ANSWERED=$(( TOTAL - MISSED ))
    echo "## ⚠️ Partial run — $ANSWERED of $TOTAL lenses answered"
    echo ''
    echo "Did not report: ${FAILED// /, }. The verdict below rests only on those"
    echo 'that answered — it cannot be a clean visit until everyone has answered.'
    echo ''
  fi

  if [ -s "$AGG" ]; then
    sanitize "$AGG"
  elif [ "$HAS_REPORTS" -eq 1 ]; then
    echo '## ⚠️ Fleet: raw reports (the aggregation failed)'
    echo ''
    # One report per lens directory, sanitised — the aggregate lives outside
    # out/ and cannot sneak into the raw list.
    for R in "$FLEET_TMP"/out/*/report.md; do
      echo "### $(basename "$(dirname "$R")")"
      echo ''
      sanitize "$R"
      echo ''
    done
  else
    echo '## ⚠️ The fleet could not run'
    echo ''
    echo 'No lens produced a report — it is the run, not the PR, that is in'
    echo 'trouble. See the job for logs. The most common cause: a missing or'
    echo 'wrong `OPENROUTER_API_KEY` (set in the repository settings).'
    echo 'Run it again — no `force` needed; this comment carries no'
    echo 'freshness marker to gate on.'
  fi

  echo ''
  echo '---'
  echo "<sub>Review fleet — lenses: ${FLEET// /, } · model: \`${MODEL}\` via OpenRouter · [job run](${RUN_URL})"
  if [ -n "$FAILED" ]; then
    echo "<br>Did not report: ${FAILED// /, }"
  fi
  echo "<br>Rerun: \`gh workflow run pr-review-fleet.yml -R ${REPO} -f pr=${PR} -f force=true\` · the fleet only reports — the merge decision is yours.</sub>"
} > "$FLEET_TMP/comment.md"

# Machine finding count + the aggregator's own heading. The two expressions
# of the number can diverge if the bullet format drifts — the reaction must
# never be friendlier than what the comment itself says, so the larger
# number wins.
if [ "$HAS_REPORTS" -eq 1 ] && [ -s "$AGG" ]; then
  SCAN="$(sanitize "$AGG")"
  MODE=aggregate
elif [ "$HAS_REPORTS" -eq 1 ]; then
  SCAN="$(sanitize "$FLEET_TMP"/out/*/report.md 2>/dev/null || true)"
  MODE=raw
else
  SCAN=''
  MODE=none
fi

FINDINGS="$(printf '%s' "$SCAN" | grep -Ec '^- \*\*(CRITICAL|HIGH|MEDIUM|LOW)\*\*' || true)"
FINDINGS="${FINDINGS:-0}"
AGG_SAYS="$(printf '%s' "$SCAN" | sed -n 's/^## ⚠️ Fleet: \([0-9][0-9]*\) finding.*/\1/p' | head -1)"
if [ -n "${AGG_SAYS:-}" ] && [ "$AGG_SAYS" -gt "$FINDINGS" ]; then
  FINDINGS="$AGG_SAYS"
fi

# A run that did not stamp the marker never replaces a finished review.
# --edit-last edits the last comment by the token's user (github-actions[bot])
# — on 2026-11-02 that turned PR #188's full review (13 findings) into "The
# fleet could not run" when the next run's lenses all failed on the
# OpenRouter credit limit, and a scheduled run 16 minutes later did it again;
# the review survived only in GitHub's edit history. So a failed or partial
# run first looks at the comment it would edit: if that one carries a marker
# (for ANY head — a complete review or the diff-too-large decision) and no
# partial banner, it is left alone and this run's report goes to the job
# summary instead. The rule is the one resolve-pr.sh's head_is_fresh reads
# comments by: the bot's comments, "review-fleet:v1" in the body (the
# sanitiser guarantees only a real marker has it), "Partial run" not. A
# human comment is never looked at, and nothing is stamped, so the run can
# still be retried without force — and the gate keys on the head sha, so an
# old head's kept review is never fresh for a new head.
#
# If the comments cannot be read the run keeps rather than overwrites: the
# failure is in the job either way, a lost review is not.
LAST_FLEET=complete
if [ "$HAS_REPORTS" -eq 0 ] || [ -n "$FAILED" ]; then
  LAST_FLEET="$(gh pr view "$PR" -R "$REPO" --json comments --jq \
    '[.comments[] | select(.author.login == "github-actions[bot]" or .author.login == "github-actions")] | last
     | if . == null then "none"
       elif (.body | contains("review-fleet:v1")) and ((.body | contains("Partial run")) | not) then "finished"
       else "notice" end' || true)"
  case "$LAST_FLEET" in none | notice | finished) ;; *) LAST_FLEET=unreadable ;; esac
fi

if [ "$LAST_FLEET" = finished ] || [ "$LAST_FLEET" = unreadable ]; then
  if [ "$HAS_REPORTS" -eq 0 ]; then
    echo "::error::The fleet could not run on $SHA — the PR's finished review was left in place (comments: $LAST_FLEET)."
  else
    echo "::warning::Partial run on $SHA (did not report: ${FAILED// /, }) — the PR's finished review was left in place (comments: $LAST_FLEET); the report is in the job summary."
  fi
  {
    echo '### This run did not replace the review on the PR'
    echo ''
    if [ "$LAST_FLEET" = unreadable ]; then
      echo "The PR's comments could not be read, and a failed or partial run does"
      echo 'not risk overwriting a finished review. What this run would have posted:'
    else
      echo 'The PR already carries a finished fleet review, and a failed or partial'
      echo 'run never replaces one. What this run would have posted:'
    fi
    echo ''
    cat "$FLEET_TMP/comment.md"
    echo ''
  } >> "$GITHUB_STEP_SUMMARY"
  # kept: the PR keeps the review and its reaction — only this run's 👀 goes.
  echo 'kept' > "$FLEET_TMP/verdict"
  {
    echo "findings=$FINDINGS"
    echo "mode=$MODE"
    echo 'posted=false'
    echo 'kept=true'
  } >> "$GITHUB_OUTPUT"
  exit 0
fi

gh pr comment "$PR" -R "$REPO" --body-file "$FLEET_TMP/comment.md" \
  --edit-last --create-if-none

# The verdict is written only once the comment exists: a failed post should
# neither give a reaction nor pretend to a verdict. Findings win over
# partial — but a partial run without findings is never 👍, however many
# reports lie in the output directories.
if [ "$HAS_REPORTS" -eq 0 ]; then
  echo 'dirty' > "$FLEET_TMP/verdict"
elif [ "$FINDINGS" -gt 0 ]; then
  echo 'findings' > "$FLEET_TMP/verdict"
elif [ -n "$FAILED" ] || [ "$FLEET" = 'unknown' ]; then
  echo 'partial' > "$FLEET_TMP/verdict"
else
  echo 'clean' > "$FLEET_TMP/verdict"
fi

{
  echo "findings=$FINDINGS"
  echo "mode=$MODE"
  echo 'posted=true'
} >> "$GITHUB_OUTPUT"
