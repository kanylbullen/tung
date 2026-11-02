#!/usr/bin/env bash
set -euo pipefail

# Runs resolve-pr.sh for real against a FAKE gh, and checks what it decides.
# Run it locally or in CI's "Fleet PR selection" job: `bash
# .github/review-team/scripts/test-resolve-pr.sh`. Needs jq (preinstalled on
# GitHub's ubuntu-latest runner).
#
# Why this exists: the CI gate holds back silently. A broken jq line in
# ci_settled makes every head look unrated, the fleet waits for ever, and the
# only trace is a notice in a green job log nobody reads — the fleet simply
# stops reviewing. A rule that fails silently is the worst kind, so the
# decision needs something that can say no.
#
# The fake gh answers from fixtures with REAL jq, so the expressions in
# resolve-pr.sh actually run — a stub that answered pre-chewed would have
# certified any jq at all. An unknown call gives an error instead of an empty
# answer, so a new gh call in the script does not silently become "no
# checks".
#
# Mutation check (do it, it takes a minute): change "SKIPPED" to "SKIPPEDX"
# in ci_settled and "a skipped check counts as green" goes red; remove the
# cap (the AGE branch) and "red but old" goes red; remove the `continue`
# branch in the selection loop and "the selection moves on" goes red.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/resolve-pr.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FIX="$TMP/fix"
OUT="$TMP/output"
LOG="$TMP/log"
mkdir -p "$TMP/bin" "$FIX"

cat > "$TMP/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
JQ='.'; JSON=''; PREV=''
for A in "$@"; do
  case "$PREV" in --jq) JQ="$A" ;; --json) JSON="$A" ;; esac
  PREV="$A"
done
case "${1:-}" in
  pr)
    case "${2:-}" in
      list) F="$FIX/list.json" ;;
      view) F="$FIX/pr-${3}-${JSON%%,*}.json" ;;
      *) echo "fake gh: unknown pr call: $*" >&2; exit 64 ;;
    esac ;;
  api)
    case "${2:-}" in
      graphql)
        # The query carries the PR number in -F nr=<n>; the fixture is under that number.
        NR=''
        for A in "$@"; do case "$A" in nr=*) NR="${A#nr=}" ;; esac; done
        [ -n "$NR" ] || { echo "fake gh: graphql without nr=: $*" >&2; exit 66; }
        F="$FIX/graphql-$NR.json" ;;
      repos/*/commits/*) F="$FIX/commit-${2##*/}.json" ;;
      *) echo "fake gh: unknown api call: $*" >&2; exit 64 ;;
    esac ;;
  *) echo "fake gh: unknown call: $*" >&2; exit 64 ;;
esac
[ -f "$F" ] || { echo "fake gh: the fixture is missing: $F (call: $*)" >&2; exit 65; }
jq -r "$JQ" "$F"
STUB
chmod +x "$TMP/bin/gh"

NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
FRESH="$(date -u -d '-10 minutes' +%Y-%m-%dT%H:%M:%SZ)"
OLD="$(date -u -d '-9 hours' +%Y-%m-%dT%H:%M:%SZ)"

# pr <number> <sha> <commit date> <rollup-json>
pr() {
  local N="$1" SHA="$2" DATE="$3" ROLLUP="$4" ROLL
  printf '{"state":"OPEN"}\n'            > "$FIX/pr-$N-state.json"
  printf '{"headRefOid":"%s"}\n' "$SHA"  > "$FIX/pr-$N-headRefOid.json"
  printf '{"comments":[]}\n'             > "$FIX/pr-$N-comments.json"
  # The same shape GraphQL answers with, including statusCheckRollup being
  # null (not an empty list) on a head without checks.
  if [ "$ROLLUP" = null ]; then ROLL=null; else ROLL="{\"contexts\":{\"nodes\":$ROLLUP}}"; fi
  printf '{"data":{"repository":{"pullRequest":{"commits":{"nodes":[{"commit":{"statusCheckRollup":%s}}]}}}}}\n' \
    "$ROLL" > "$FIX/graphql-$N.json"
  printf '{"commit":{"committer":{"date":"%s"}}}\n' "$DATE" > "$FIX/commit-$SHA.json"
  printf '[{"number":%s,"headRefOid":"%s","isDraft":false,"updatedAt":"%s"}]\n' \
    "$N" "$SHA" "$NOW" > "$FIX/list.json"
}

check() { printf '{"__typename":"CheckRun","name":"%s","status":"%s","conclusion":%s,"startedAt":"%s"}' \
  "$1" "$2" "$3" "${4:-$FRESH}"; }

GREEN="[$(check Android COMPLETED '"SUCCESS"'),$(check Web COMPLETED '"SUCCESS"')]"
RED="[$(check Android COMPLETED '"SUCCESS"'),$(check iOS COMPLETED '"FAILURE"')]"
RUNNING="[$(check Android COMPLETED '"SUCCESS"'),$(check iOS IN_PROGRESS null)]"
QUEUED="[$(check Android COMPLETED '"SUCCESS"'),$(check iOS QUEUED null)]"
SOFT="[$(check Android COMPLETED '"SKIPPED"'),$(check Lint COMPLETED '"NEUTRAL"')]"
STATUS_OK="[{\"__typename\":\"StatusContext\",\"context\":\"Pages\",\"state\":\"SUCCESS\",\"createdAt\":\"$FRESH\"}]"
STATUS_PENDING="[{\"__typename\":\"StatusContext\",\"context\":\"Pages\",\"state\":\"PENDING\",\"createdAt\":\"$FRESH\"}]"

FAILURES=0
# scenario <name> <event> <force> <input_pr> <expectation> [more expectations]
#   line         — the line must be in GITHUB_OUTPUT
#   log:text     — the text must be in the job log
#   notlog:re    — the regex must NOT match any line of the log
#   rc:N         — the script's exit status (default 0 if none is given)
scenario() {
  local NAME="$1" EVENT="$2" FORCE="$3" IN="$4"; shift 4
  : > "$OUT"
  local RC=0
  REPO=kanylbullen/testrepo FRESH_HOURS=24 CI_WAIT_HOURS="${WAIT_HOURS:-4}" \
    GITHUB_EVENT_NAME="$EVENT" FORCE="$FORCE" INPUT_PR="$IN" GH_TOKEN=x \
    GITHUB_OUTPUT="$OUT" FIX="$FIX" PATH="$TMP/bin:$PATH" \
    bash "$SCRIPT" > "$LOG" 2>&1 || RC=$?
  local LINE MISSING='' WANTED_RC=0
  for LINE in "$@"; do
    case "$LINE" in rc:*) WANTED_RC="${LINE#rc:}" ;; esac
  done
  # Without this line a scenario passes that writes the right outputs and
  # THEN dies: the outputs file is already written when the script fails, and
  # every line expectation still matches.
  [ "$RC" = "$WANTED_RC" ] || MISSING="$MISSING  exit status $RC, expected $WANTED_RC"$'\n'
  for LINE in "$@"; do
    case "$LINE" in
      # log:… asks the job log instead of the outputs — that is where the
      # difference between "green" and "could not read" shows, and both give
      # skip=false.
      log:*) grep -qF "${LINE#log:}" "$LOG" || MISSING="$MISSING  missing in the log: ${LINE#log:}"$'\n' ;;
      notlog:*) ! grep -qE "${LINE#notlog:}" "$LOG" || MISSING="$MISSING  the log matches what it must not: ${LINE#notlog:}"$'\n' ;;
      rc:*)   : ;;
      *)      grep -qxF "$LINE" "$OUT"       || MISSING="$MISSING  missing: $LINE"$'\n' ;;
    esac
  done
  if [ -n "$MISSING" ]; then
    FAILURES=$(( FAILURES + 1 ))
    printf 'FAIL %s (rc=%s)\n%s' "$NAME" "$RC" "$MISSING"
    printf '  got:\n'; sed 's/^/    /' "$OUT"
    printf '  log:\n'; sed 's/^/    /' "$LOG" | head -6
  else
    printf 'ok   %s\n' "$NAME"
  fi
}

pr 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$FRESH" "$GREEN"
scenario "green CI is reviewed"                 schedule false 1 "skip=false" "pr=1" "log:CI green (2 checks)"

pr 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$FRESH" "$RED"
scenario "red CI waits"                         schedule false 1 "skip=true" "reason=ci-not-green"
scenario "red CI is reviewed anyway on force"   schedule true  1 "skip=false"
scenario "red CI is reviewed anyway on dispatch" workflow_dispatch false 1 "skip=false"

pr 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$FRESH" "$RUNNING"
scenario "a running check waits"                schedule false 1 "skip=true" "reason=ci-not-green"

pr 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$FRESH" "$QUEUED"
scenario "a queued check waits"                 schedule false 1 "skip=true" "reason=ci-not-green"

# The log line is not decoration: without it the scenario passes even when jq
# fails and ci_settled takes its "could not be read" branch — both give
# skip=false, and that is exactly how the first version of this test passed
# in green on a broken classification.
pr 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$FRESH" "$SOFT"
scenario "a skipped check counts as green"      schedule false 1 "skip=false" "log:CI green (2 checks)"

# The gate only holds back on a definite answer: if the rollup cannot be read
# the PR is reviewed as before, and the log says that this was why.
rm -f "$FIX/graphql-1.json"
scenario "an unreadable rollup does not stop the fleet" schedule false 1 "skip=false" \
  "log:could not be read" "log:::warning::The fleet could not read the checks"

pr 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$FRESH" "$STATUS_OK"
scenario "a green StatusContext is reviewed"    schedule false 1 "skip=false"

pr 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$FRESH" "$STATUS_PENDING"
scenario "a pending StatusContext waits"        schedule false 1 "skip=true" "reason=ci-not-green"

# The cap: the same red head, but nothing has happened on it for nine hours.
pr 1 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb "$OLD" \
  "[$(check Android COMPLETED '"SUCCESS"' "$OLD"),$(check iOS COMPLETED '"FAILURE"' "$OLD")]"
scenario "red but old is reviewed anyway"       schedule false 1 "skip=false"

# A check that STARTED just now keeps the head young even if the commit is
# old: CI is working, and then the fleet should wait.
pr 1 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb "$OLD" \
  "[$(check Android COMPLETED '"SUCCESS"' "$OLD"),$(check iOS IN_PROGRESS null "$FRESH")]"
scenario "old commit but CI running now waits"  schedule false 1 "skip=true" "reason=ci-not-green"

pr 1 cccccccccccccccccccccccccccccccccccccccc "$FRESH" null
scenario "no checks yet waits"                  schedule false 1 "skip=true" "reason=ci-not-green"

pr 1 cccccccccccccccccccccccccccccccccccccccc "$OLD" null
scenario "a repo without CI does not stop the fleet" schedule false 1 "skip=false"

# The committer date comes from a clock we do not own. If it is dated ahead
# it must not postpone the cap: the checks' own timestamps are the clock.
pr 1 eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee "$(date -u -d '+3 days' +%Y-%m-%dT%H:%M:%SZ)" \
  "[$(check Android COMPLETED '"SUCCESS"' "$OLD"),$(check iOS COMPLETED '"FAILURE"' "$OLD")]"
scenario "a commit dated ahead does not postpone the cap" schedule false 1 "skip=false"

# A repository without CI turns the wait off with CI_WAIT_HOURS=0, not with
# an exception in the code. Red head, fresh commit — reviewed anyway.
pr 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$FRESH" "$RED"
WAIT_HOURS=0 scenario "CI_WAIT_HOURS=0 turns the wait off" schedule false 1 "skip=false"

# The same skew on a head WITHOUT checks, where the committer date is the
# only clock: the age should become zero and count from there, not minus
# three days.
pr 1 ffffffffffffffffffffffffffffffffffffffff "$(date -u -d '+3 days' +%Y-%m-%dT%H:%M:%SZ)" null
scenario "a commit dated ahead gives age zero" schedule false 1 \
  "skip=true" "log:waiting for CI for 0 min"

# The check name comes from the PR branch's own workflow files and is written
# into a ::notice::. A newline in a job name must not become a line of its
# own in the fleet's job log — there it would be read as a workflow command.
pr 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$FRESH" \
  "[$(check 'Android\n::error::injected' COMPLETED '"FAILURE"')]"
scenario "a newline in a check name is flattened" schedule false 1 \
  "skip=true" "log:Android ::error::injected" "notlog:^::error::injected"

# The selection: #2 is the most recently updated but red, #1 is green — the
# loop moves on instead of skipping the run.
pr 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$FRESH" "$GREEN"
pr 2 dddddddddddddddddddddddddddddddddddddddd "$FRESH" "$RED"
printf '[{"number":2,"headRefOid":"dddddddddddddddddddddddddddddddddddddddd","isDraft":false,"updatedAt":"%s"},
        {"number":1,"headRefOid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","isDraft":false,"updatedAt":"%s"}]\n' \
  "$NOW" "$FRESH" > "$FIX/list.json"
scenario "the selection moves on to the next candidate" schedule false "" "skip=false" "pr=1"

# The query must not ask for fields outside `checks` and `statuses`. That is
# not a style rule: `gh pr view --json statusCheckRollup` fetched
# `checkSuite.workflowRun`, GraphQL answered DATA PLUS ERRORS, gh gave exit≠0
# and the gate read an empty rollup — useless in every run, with nothing
# failing. One more field of that kind does it again.
# Comment lines out first — this file's own explanation MENTIONS the fields,
# and a guard that trips on its own documentation is one people learn to
# switch off.
if grep -vE '^[[:space:]]*#' "$HERE/resolve-pr.sh" | grep -qE "workflowRun|checkSuite"; then
  FAILURES=$(( FAILURES + 1 ))
  printf 'FAIL the query asks for fields outside checks/statuses (workflowRun, checkSuite …)\n'
else
  printf 'ok   the query stays within checks and statuses\n'
fi

# The wiring, not just the script: the test sets the environment variables
# itself, so a workflow that forgets them goes unnoticed here and dies only on
# the first head that is not green — and only in the repository that forgot.
# The scripts are shared between repositories and the workflow files are
# edited one by one.
WORKFLOW="$HERE/../../workflows/pr-review-fleet.yml"
for VAR in CI_WAIT_HOURS FRESH_HOURS; do
  if grep -qE "^  $VAR: " "$WORKFLOW"; then
    printf 'ok   the workflow sets %s\n' "$VAR"
  else
    FAILURES=$(( FAILURES + 1 ))
    printf 'FAIL the workflow does not set %s (%s)\n' "$VAR" "$WORKFLOW"
  fi
done

if [ "$FAILURES" -gt 0 ]; then
  printf '\n%s scenarios failed.\n' "$FAILURES"
  exit 1
fi
printf '\nAll scenarios green.\n'
