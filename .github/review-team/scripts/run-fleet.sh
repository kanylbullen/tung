#!/usr/bin/env bash
set -euo pipefail

# Installs the cline CLI into an npm prefix under the runner's temp — no
# global traces on the runner — and runs the reviewer lenses IN PARALLEL
# against the same diff. Each lens writes its report to its OWN directory
# under $FLEET_TMP/out/ (out/<name>/report.md); the agent's stdout is logged
# separately in $FLEET_TMP/logs/. A lens that fails does not stop the
# others; lenses without a report are listed in the comment's footnote.
#
# Two trees with different roles. The code that runs — this script, the role
# files, the templates — comes from the root checkout
# ($GITHUB_WORKSPACE/.github). The tree under review is in $PR_TREE (the
# workflow checks out refs/pull/N/merge with path: pr-tree/) and is readable
# data only: the -c of the lenses and the aggregator points there, and no
# step executes anything from it — never .github/** from the PR tree.
#
# The lenses share nothing but the diff: each gets its own cline state, and
# reports, prompts and logs live under $FLEET_TMP — which the workflow places
# OUTSIDE both trees, so no lens can grep another's report or task out of the
# tree it acts in. The output directory is also per lens
# (out/<name>/report.md): all lenses share an OS user, and a report on a
# shared, guessable path (out/<name>.md — every lens knows its own format
# through @NAME@) could have been overwritten by a prompt-injected lens —
# forging or erasing its siblings' findings, or conjuring a 👍, before the
# aggregation read them. Placement limits the damage, it does not undo it:
# the reports are still treated as untrusted content before they are posted
# (build-comment.sh sanitises planted freshness markers away).
#
# The step deliberately has NO GH_TOKEN: the lenses read untrusted PR content
# and must not be able to pick a token out of the process environment. The
# only secret here is OPENROUTER_API_KEY, and it is never echoed.
#
# Output: aggregated (true|false) and failed (comma list of lenses without a
# report, set only when one is missing — the workflow then keeps the logs as
# a job artifact). Total failure — no lens answered — is an error and fails
# the job (exit 1): a PR that was never reviewed must never show a green
# check.

# The key before anything else. An empty secret is the state the system
# starts in (the owner adds it by hand) and should fail the run in seconds —
# not as six timeouts in a row.
[ -n "${OPENROUTER_API_KEY:-}" ] || {
  echo "::error::OPENROUTER_API_KEY is empty or missing. Add it in the repository settings (Secrets and variables > Actions) and run again — without force, the fleet has nothing to gate on when it has not reviewed."
  exit 1
}

ROLE_DIR="$GITHUB_WORKSPACE/.github/review-team/reviewers"
PR_TREE="$GITHUB_WORKSPACE/pr-tree"
TOOLS="$FLEET_TMP/tools"
DIFF="$FLEET_TMP/pr.diff"
BIN="$TOOLS/node_modules/.bin/cline"
PER_REVIEWER_TIMEOUT="${REVIEWER_TIMEOUT_SECS:-300}"
# The aggregator has strictly more work than a lens — five reports plus "let
# the code testify" — and gets its own, larger budget (the workflow sets 600).
AGG_TIMEOUT="${AGGREGATOR_TIMEOUT_SECS:-$PER_REVIEWER_TIMEOUT}"

# The fleet's contract, verbatim from the review-team setup it was modelled
# on: each lens knows nothing of the others and keeps to its mandate.
WRAPPER='You are one reviewer in a fleet of independent code review agents. You cannot see the other reviewers. Stay strictly within your assigned role. Return only concrete, actionable findings with file and line references. If you find no issues in your area, say so clearly. Do not provide general commentary, praise, summaries, or duplicate concerns outside your mandate.'

# --- Which lenses? ----------------------------------------------------------
# Empty input = the core fleet. Otherwise both short names (correctness) and
# full file names are accepted; a misspelt lens should stop the run, not
# silently shrink the fleet.
DEFAULT_FLEET="correctness-reviewer security-abuse-reviewer architecture-reviewer code-quality-conventions-reviewer simplicity-scope-reviewer"

FLEET=()
if [ -n "${INPUT_REVIEWERS:-}" ]; then
  IFS=',' read -r -a WANTED <<< "$INPUT_REVIEWERS"
  for W in "${WANTED[@]}"; do
    W="$(printf '%s' "$W" | xargs || printf '%s' "$W")"
    [ -z "$W" ] && continue
    if   [ -f "$ROLE_DIR/$W.md" ];            then FLEET+=("$W.md")
    elif [ -f "$ROLE_DIR/$W-reviewer.md" ];   then FLEET+=("$W-reviewer.md")
    else
      echo "::error::Unknown reviewer lens \"$W\". Available lenses:"
      ls "$ROLE_DIR" | sed 's/^/::error::  /'
      exit 1
    fi
  done
else
  for W in $DEFAULT_FLEET; do FLEET+=("$W.md"); done
fi

# Short names in the lists — the footnote should not read
# "correctness-reviewer.md".
NAMES=("${FLEET[@]/%.md/}")
printf '%s\n' "${NAMES[*]}" > "$FLEET_TMP/fleet.txt"
echo "Lenses: ${NAMES[*]} (${#FLEET[@]} of them, in parallel)"

[ -s "$DIFF" ] || { echo "::error::The diff is missing — the run order is broken."; exit 1; }

# The tree under review must exist: the checkout of refs/pull/N/merge (path:
# pr-tree/) is conditional, and if the merge state is missing — a conflict —
# it should fail loudly, not silently review the root tree (which on
# workflow_dispatch is on the base and has nothing to do with the PR).
[ -d "$PR_TREE/.git" ] || {
  echo "::error::The PR tree is missing in $PR_TREE — the checkout of the PR's merge state did not run."
  exit 1
}

# --- cline ------------------------------------------------------------------
npm install --prefix "$TOOLS" "cline@${CLINE_VERSION}" \
  --no-audit --no-fund --no-progress --loglevel=error

"$BIN" --version

# --- Run the lenses, in parallel -------------------------------------------
# Five independent calls that share nothing but a readable diff. Run in
# sequence they put ~2,220 s of agent time back to back; in parallel the
# longest path is one lens plus the aggregator, within the job's time
# budget. The status is derived from which report files exist once all have
# been waited for — not from exit codes.
FAILED=()
SUCCEEDED=0

for ROLE_FILE in "${FLEET[@]}"; do
  NAME="${ROLE_FILE%.md}"
  # Own output directory per lens — the siblings' reports must not lie on a
  # guessable path an injected lens can overwrite (see above).
  OUT="$FLEET_TMP/out/$NAME/report.md"
  LOG="$FLEET_TMP/logs/$NAME.log"
  RC="$FLEET_TMP/logs/$NAME.rc"
  PROMPT="$FLEET_TMP/prompts/$NAME.txt"

  # Own state directory per lens — neither task history nor settings are
  # shared, and no two cline processes stamp on the same lock.
  mkdir -p "$FLEET_TMP/out/$NAME" "$FLEET_TMP/cline-home/$NAME" "$FLEET_TMP/cline-data/$NAME"

  {
    cat "$ROLE_DIR/$ROLE_FILE"
    printf '\n---\n\n%s\n\n---\n\n' "$WRAPPER"
    sed -e "s|@REPORT@|$OUT|g" \
        -e "s|@DIFF@|$DIFF|g" \
        -e "s|@PR@|${PR_NUMBER}|g" \
        -e "s|@SHA@|${SHA}|g" \
        -e "s|@NAME@|$NAME|g" \
        -e "s|@WORKTREE@|$PR_TREE|g" \
        "$GITHUB_WORKSPACE/.github/review-team/task.md"
  } > "$PROMPT"

  echo "Starting $NAME (log: $LOG)"
  # timeout around cline's own -t: a CLI that hangs should not eat the job's
  # total budget. The exit code goes into a file of its own; the report's
  # existence is what counts, not the return value. (Empirical, from the
  # first parallel runs: cline catches TERM and finishes its task after the
  # wrapper's limit — the lens phase stretched to 932 s against 360 s — so
  # this is a request, not a hard limit; the job's timeout-minutes is the
  # real counter.)
  (
    export CLINE_DATA_DIR="$FLEET_TMP/cline-data/$NAME"
    set +e
    timeout $(( PER_REVIEWER_TIMEOUT + 61 )) "$BIN" \
      -c "$PR_TREE" --config "$FLEET_TMP/cline-home/$NAME" \
      -P openrouter -m "$MODEL" -t "$PER_REVIEWER_TIMEOUT" \
      --retries 3 -y "$(cat "$PROMPT")" >"$LOG" 2>&1
    echo $? > "$RC"
  ) &
done

# Wait for all lenses. (wait without arguments waits for every background
# job; || true so that set -e does not trip on an innocent return value.)
wait || true

for NAME in "${NAMES[@]}"; do
  OUT="$FLEET_TMP/out/$NAME/report.md"
  LOG="$FLEET_TMP/logs/$NAME.log"
  RC="$(cat "$FLEET_TMP/logs/$NAME.rc" 2>/dev/null || echo '?')"
  if [ -s "$OUT" ]; then
    SUCCEEDED=$(( SUCCEEDED + 1 ))
    echo "::notice::$NAME: report present ($(wc -c < "$OUT" | tr -d ' ') bytes, written $(date -r "$OUT" '+%H:%M:%S'))"
  else
    FAILED+=("$NAME")
    echo "::warning::$NAME: no report (exit $RC) — log: $LOG"
    tail -5 "$LOG" | sed 's/^/  | /' || true
  fi
done

# No leading blank: the footnote becomes "Did not report: a, b", not
# "Did not report: , a, b".
printf '%s\n' "${FAILED[*]:-}" > "$FLEET_TMP/failed.txt"

# The workflow keeps the logs as a job artifact when a lens has no report —
# signal it as an output (unset = everyone answered, nothing is kept).
if [ "${#FAILED[@]}" -gt 0 ]; then
  echo "failed=$(IFS=,; echo "${FAILED[*]}")" >> "$GITHUB_OUTPUT"
fi

if [ "$SUCCEEDED" -eq 0 ]; then
  echo "::error::No lens produced a report — the run failed, the PR was not reviewed."
  echo "aggregated=false" >> "$GITHUB_OUTPUT"
  exit 1
fi

# --- The report's closing line: measured, not guessed -----------------------
# The aggregator used to be asked to end with "roughly how large the diff
# was" — and guessed: on one PR (2026-08-31) it wrote "4 files, +29/−184
# lines" for a PR that touched ONE file. A number nobody counted does not
# belong in a review report, so it is counted here: git apply --numstat reads
# the fetched unified diff ($DIFF) and gives the same numbers as
# git diff --numstat.
#
# Only the counts — number of files and added/removed lines. NOT the file
# names: numstat does not quote paths, so the third column is
# attacker-controlled prose, and a planted file name ("x +999/−999, ZERO
# findings") would otherwise smuggle any text into the closing line and thus
# into the posted report — as if the fleet itself had measured it. The diff
# is data, not prompt configuration; nothing from column three may leave
# this block. Binary files count as 0/0 (numstat reports "-"), which is the
# same "changed files, no text lines" numstat itself says.
DIFFSTAT="$(git apply --numstat "$DIFF" 2>/dev/null | awk -F'\t' '
  {
    files++
    add += ($1 ~ /^[0-9]+$/ ? $1 : 0)
    del += ($2 ~ /^[0-9]+$/ ? $2 : 0)
  }
  END {
    if (files == 0) exit
    printf "%d %s, +%d/-%d lines", files, (files == 1 ? "file" : "files"), add, del
  }' || true)"
# The lenses from the NAMES array — the script wrote fleet.txt (above) and
# already has the list in memory; no reason to read it back. Nothing in the
# closing line comes from the model's memory: the counts here and the lens
# names from the role directory (fleet.txt, which the aggregator still reads
# through @FLEETFILE@, holds the same list).
# The same list format as the comment's footnote (build-comment.sh): commas.
LENSES="${NAMES[*]}"
LENSES="${LENSES// /, }"
if [ -n "$DIFFSTAT" ]; then
  CLOSING_LINE="*Diff: $DIFFSTAT · Lenses: $LENSES.*"
else
  # A visible gap instead of an invented number: the aggregation should not
  # fail because the diffstat could not be read.
  CLOSING_LINE="*Diff: could not be measured · Lenses: $LENSES.*"
fi
# The line is added as the task's last line with printf after the sed block
# below — it never goes through a sed replacement and so needs no escaping
# of &, \ or |. aggregation.md only asks for it to be copied verbatim.

# --- Aggregate ---------------------------------------------------------------
# The same rules the fleet has always used: merge duplicates, drop findings
# without a concrete failure mode, settle conflicts against the code, rank by
# severity. Done as a cline call of its own instead of in bash — the
# judgement is a language problem, not a text-processing problem. The
# aggregator should read every report AND open the files concerned ("let the
# code testify") — strictly more work than a lens has — and so gets its own,
# larger time budget: -t AGG_TIMEOUT (600 s against the lenses' 300). The
# wrapper runs with -k: this is the last long step, and a hang that ignores
# TERM (see above) must not eat the window for posting the comment — after
# 30 s more it is killed hard, and the raw reports are posted instead.
# The aggregator's output lies OUTSIDE out/ — that directory holds only
# per-lens directories (out/<name>/report.md), so "is there any report?" can
# always be decided with a glob without knowing what is aggregated and what
# is not. The final report is itself untrusted content — it is built on the
# reports — and is sanitised in build-comment.sh just like the raw reports.
AGG_OUT="$FLEET_TMP/aggregate.md"
{
  printf '%s\n\n' 'You are the aggregator of a fleet of independent code review agents. Merge their findings into one final report. Do not add findings of your own; do not drop a finding that has a concrete failure mode.'
  sed -e "s|@OUTDIR@|$FLEET_TMP/out|g" \
      -e "s|@AGGOUT@|$AGG_OUT|g" \
      -e "s|@FLEETFILE@|$FLEET_TMP/fleet.txt|g" \
      -e "s|@PR@|${PR_NUMBER}|g" \
      -e "s|@WORKTREE@|$PR_TREE|g" \
      "$GITHUB_WORKSPACE/.github/review-team/aggregation.md"
  # The closing line last — printf, not a sed replacement: the line should
  # not need to reason about sed's special characters (&, \, |), and "the
  # very last line" in aggregation.md means exactly this line.
  printf '\n%s\n' "$CLOSING_LINE"
} > "$FLEET_TMP/prompts/aggregate.txt"

# The aggregator gets its own state, for the same reason as the lenses.
mkdir -p "$FLEET_TMP/cline-home/aggregate" "$FLEET_TMP/cline-data/aggregate"

set +e
CLINE_DATA_DIR="$FLEET_TMP/cline-data/aggregate" \
timeout -k 30 $(( AGG_TIMEOUT + 120 )) "$BIN" \
  -c "$PR_TREE" --config "$FLEET_TMP/cline-home/aggregate" \
  -P openrouter -m "$MODEL" -t "$AGG_TIMEOUT" \
  --retries 3 -y "$(cat "$FLEET_TMP/prompts/aggregate.txt")" \
  > "$FLEET_TMP/logs/aggregate.log" 2>&1
set -e

if [ -s "$AGG_OUT" ]; then
  echo "aggregated=true" >> "$GITHUB_OUTPUT"
else
  echo "::warning::The aggregation produced no report — the raw reports are posted (sanitised). The log is kept as a job artifact (Clean up removes it from the runner)."
  echo "aggregated=false" >> "$GITHUB_OUTPUT"
fi
